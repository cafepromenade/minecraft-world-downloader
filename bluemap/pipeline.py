#!/usr/bin/env python3
"""
BlueMap pipeline for the Minecraft world-downloader.

Turns a downloaded world into an interactive 3D web map:

  1. upgrade  — run a temporary server jar with --forceUpgrade to upgrade the saved world to the
                latest data format / folder structure, then it stops automatically.
  2. render   — run BlueMap (standalone CLI) to render the world into a web map.
  3. serve    — host the rendered web map with BlueMap's integrated webserver.
  4. all      — upgrade (optional) + render in one go.

BlueMap supports Minecraft 1.13+ (3D rendering is best on 1.18+ worlds, which is the focus here).

Examples:
  python pipeline.py fetch-bluemap
  python pipeline.py upgrade --world /data/world --server-jar paper.jar
  python pipeline.py render  --world /data/world --out /data/bluemap-web
  python pipeline.py all     --world /data/world --server-jar paper.jar --out /data/bluemap-web --serve
  python pipeline.py serve   --config /data/bluemap-config

Settings (all BlueMap-controllable options) can be supplied with --settings settings.json; see
settings.example.json.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request

# Last BlueMap release that runs on Java 21 (5.17+ require Java 25). Override with --bluemap-version.
DEFAULT_BLUEMAP_VERSION = "5.16"
BLUEMAP_URL = "https://github.com/BlueMap-Minecraft/BlueMap/releases/download/v{v}/bluemap-{v}-cli.jar"

DEFAULT_SETTINGS = {
    "acceptDownload": True,          # required: accepts Mojang's EULA to download the client for textures
    "renderThreadCount": 0,          # 0 => auto (cpu-1)
    "webserverPort": 8100,
    "webserverEnabled": True,
    "dimensions": ["overworld", "nether", "end"],   # which dimension maps to render (if present)
    # per-map render options:
    "ambientLight": 0.1,
    "skyColor": "#7dabff",
    "renderEdges": True,
    "saveHiresLayer": True,
    "minY": None,
    "maxY": None,
}


def log(*a):
    print("[bluemap-pipeline]", *a, flush=True)


def java_bin(explicit):
    if explicit:
        return explicit
    jh = os.environ.get("JAVA_HOME")
    if jh:
        cand = os.path.join(jh, "bin", "java.exe" if os.name == "nt" else "java")
        if os.path.isfile(cand):
            return cand
    return "java"


def load_settings(path):
    s = dict(DEFAULT_SETTINGS)
    if path and os.path.isfile(path):
        with open(path) as fh:
            s.update(json.load(fh))
    return s


# ---------------------------------------------------------------------------------------------
# fetch
# ---------------------------------------------------------------------------------------------
def fetch_bluemap(version, dest):
    url = BLUEMAP_URL.format(v=version)
    log(f"downloading BlueMap {version} CLI -> {dest}")
    os.makedirs(os.path.dirname(os.path.abspath(dest)), exist_ok=True)
    urllib.request.urlretrieve(url, dest)
    log(f"downloaded {os.path.getsize(dest)} bytes")
    return dest


def ensure_bluemap(args):
    if args.bluemap_jar and os.path.isfile(args.bluemap_jar):
        return args.bluemap_jar
    jar = args.bluemap_jar or os.path.join(args.workdir, f"bluemap-{args.bluemap_version}-cli.jar")
    if not os.path.isfile(jar):
        fetch_bluemap(args.bluemap_version, jar)
    return jar


# ---------------------------------------------------------------------------------------------
# 1. upgrade — temporary server with --forceUpgrade
# ---------------------------------------------------------------------------------------------
def upgrade(args):
    world = os.path.abspath(args.world)
    if not os.path.isdir(world):
        raise SystemExit(f"world not found: {world}")
    java = java_bin(args.java)
    if not args.server_jar or not os.path.isfile(args.server_jar):
        raise SystemExit("--server-jar is required for upgrade (a Paper/vanilla server jar)")

    if args.in_place:
        srv_dir = os.path.dirname(world) or "."
        level_name = os.path.basename(world.rstrip("/\\"))
        out_world = world
    else:
        srv_dir = os.path.join(args.workdir, "upgrade-server")
        level_name = "world"
        out_world = os.path.join(srv_dir, level_name)
        os.makedirs(srv_dir, exist_ok=True)
        if os.path.isdir(out_world):
            shutil.rmtree(out_world)
        log(f"copying world -> {out_world}")
        shutil.copytree(world, out_world)

    # The downloader writes a version-specific 'downloaded' datapack (the source server's registry
    # snapshot). It won't load on a different server version and aborts the upgrade, so drop it and the
    # stale session lock from the working copy. (In --in-place mode we move datapacks aside, restoring
    # after.) --safeMode below also makes the server ignore the level.dat reference to it.
    moved_datapacks = None
    for junk in ("session.lock",):
        jp = os.path.join(out_world, junk)
        if os.path.exists(jp):
            try: os.remove(jp)
            except OSError: pass
    dp = os.path.join(out_world, "datapacks")
    if os.path.isdir(dp):
        if args.in_place:
            moved_datapacks = dp + ".bak"
            shutil.move(dp, moved_datapacks)
        else:
            shutil.rmtree(dp, ignore_errors=True)

    with open(os.path.join(srv_dir, "eula.txt"), "w") as fh:
        fh.write("eula=true\n")
    # Bind a free OS-assigned port; the upgrade server never needs network access, and the default
    # 25565 may be busy.
    import socket
    _s = socket.socket(); _s.bind(("127.0.0.1", 0)); free_port = _s.getsockname()[1]; _s.close()
    with open(os.path.join(srv_dir, "server.properties"), "w") as fh:
        fh.write(f"level-name={level_name}\nonline-mode=false\nmax-players=1\nmotd=wdl-upgrade\n"
                 f"server-port={free_port}\nserver-ip=127.0.0.1\nenable-rcon=false\nenable-query=false\n")

    cmd = [java, "-Xmx2G", "-jar", os.path.abspath(args.server_jar), "--nogui", "--forceUpgrade", "--safeMode"]
    log("running force-upgrade:", " ".join(cmd), "(cwd=%s)" % srv_dir)
    # --forceUpgrade upgrades all chunks then proceeds to start the server normally. We wait for the
    # server to finish starting ("Done ("), which means the upgrade is complete, then send "stop" for a
    # clean shutdown that flushes the upgraded chunks + level.dat.
    proc = subprocess.Popen(cmd, cwd=srv_dir, stdin=subprocess.PIPE,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    upgraded_seen = False
    stop_sent = False
    for line in proc.stdout:
        sys.stdout.write("  [server] " + line)
        low = line.lower()
        if ("forcing world upgrade" in low or "upgrading" in low
                or "optimiz" in low or "% completed" in low or "% finished" in low):
            upgraded_seen = True
        if (not stop_sent) and ("done (" in low or "for help, type" in low):
            try:
                proc.stdin.write("stop\n")
                proc.stdin.flush()
            except Exception:
                pass
            stop_sent = True
    code = proc.wait()
    log(f"server exited (code {code}); upgrade activity detected: {upgraded_seen}")
    if moved_datapacks and os.path.isdir(moved_datapacks):
        try: shutil.move(moved_datapacks, dp)
        except OSError: pass
    if code != 0 and not args.allow_nonzero:
        raise SystemExit(f"force-upgrade failed (exit {code})")
    return out_world


# ---------------------------------------------------------------------------------------------
# 2. render — BlueMap CLI
# ---------------------------------------------------------------------------------------------
def set_conf(text, key, value):
    """Set `key: value` in a HOCON-ish config, replacing the existing (possibly commented) line."""
    if isinstance(value, bool):
        v = "true" if value else "false"
    elif isinstance(value, str):
        v = '"%s"' % value
    else:
        v = str(value)
    pat = re.compile(r'^(#\s*)?' + re.escape(key) + r'\s*:.*$', re.MULTILINE)
    if pat.search(text):
        return pat.sub(f"{key}: {v}", text, count=1)
    return text.rstrip() + f"\n{key}: {v}\n"


def patch_file(path, pairs):
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    for k, v in pairs:
        if v is not None:
            text = set_conf(text, k, v)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def dimension_exists(world, dim):
    # overworld region at <world>/region; nether at <world>/DIM-1/region; end at <world>/DIM1/region
    sub = {"overworld": "region", "nether": os.path.join("DIM-1", "region"),
           "end": os.path.join("DIM1", "region")}.get(dim, "region")
    d = os.path.join(world, sub)
    return os.path.isdir(d) and any(f.endswith(".mca") for f in os.listdir(d))


def render(args):
    world = os.path.abspath(args.world)
    if not os.path.isdir(world):
        raise SystemExit(f"world not found: {world}")
    java = java_bin(args.java)
    jar = ensure_bluemap(args)
    settings = load_settings(args.settings)
    cfg_dir = os.path.abspath(args.config or os.path.join(args.workdir, "bluemap-config"))
    webroot = os.path.abspath(args.out or os.path.join(args.workdir, "bluemap-web"))
    data_dir = os.path.abspath(os.path.join(args.workdir, "bluemap-data"))

    # 1) generate default config if needed
    if not os.path.isfile(os.path.join(cfg_dir, "core.conf")):
        log("generating BlueMap default config...")
        subprocess.run([java, "-jar", jar, "-c", cfg_dir], check=False)

    # 2) patch core / webapp / webserver
    patch_file(os.path.join(cfg_dir, "core.conf"), [
        ("accept-download", bool(settings.get("acceptDownload", True))),
        ("render-thread-count", int(settings.get("renderThreadCount", 0))),
        ("data", data_dir.replace("\\", "/")),
        ("metrics", False),
    ])
    patch_file(os.path.join(cfg_dir, "webapp.conf"), [
        ("enabled", True),
        ("webroot", webroot.replace("\\", "/")),
    ])
    patch_file(os.path.join(cfg_dir, "webserver.conf"), [
        ("enabled", bool(settings.get("webserverEnabled", True))),
        ("webroot", webroot.replace("\\", "/")),
        ("port", int(settings.get("webserverPort", 8100))),
    ])
    # Co-locate the file storage with the webroot so the webapp finds the rendered tiles
    # (the default root is a cwd-relative "web/maps").
    file_store = os.path.join(cfg_dir, "storages", "file.conf")
    if os.path.isfile(file_store):
        patch_file(file_store, [("root", (os.path.join(webroot, "maps")).replace("\\", "/"))])

    # 3) patch / prune per-dimension maps
    maps_dir = os.path.join(cfg_dir, "maps")
    wanted = set(settings.get("dimensions", ["overworld", "nether", "end"]))
    for name in ("overworld", "nether", "end"):
        conf = os.path.join(maps_dir, f"{name}.conf")
        if not os.path.isfile(conf):
            continue
        if name not in wanted or not dimension_exists(world, name):
            log(f"skipping dimension '{name}' (not present / not selected)")
            os.remove(conf)
            continue
        patch_file(conf, [
            ("world", world.replace("\\", "/")),
            ("ambient-light", settings.get("ambientLight")),
            ("sky-color", settings.get("skyColor")),
            ("render-edges", settings.get("renderEdges")),
            ("save-hires-layer", settings.get("saveHiresLayer")),
            ("min-y", settings.get("minY")),
            ("max-y", settings.get("maxY")),
        ])

    # 4) render
    # Force a FULL render the first time (with -r alone BlueMap only renders chunks changed since the
    # last render — on a fresh webroot that's "0 changed", so it reports "up-to-date" and writes no
    # tiles). Also always (re)generate the web-app (-g) so the map is actually viewable in a browser;
    # without it there is no index.html. -e renders map edges on the initial full pass.
    first_render = not os.path.isdir(os.path.join(webroot, "maps"))
    cmd = [java, "-jar", jar, "-c", cfg_dir, "-r", "-g"]
    if first_render:
        cmd += ["-f", "-e"]
    log("rendering%s (this downloads client textures on first run)..." % (" [full]" if first_render else ""))
    rc = subprocess.run(cmd, check=False)
    if rc.returncode != 0 and not args.allow_nonzero:
        raise SystemExit(f"BlueMap render failed (exit {rc.returncode})")
    log(f"render complete -> {webroot}")
    return webroot, cfg_dir


# ---------------------------------------------------------------------------------------------
# 3. serve
# ---------------------------------------------------------------------------------------------
def serve(args):
    java = java_bin(args.java)
    jar = ensure_bluemap(args)
    cfg_dir = os.path.abspath(args.config or os.path.join(args.workdir, "bluemap-config"))
    log(f"starting BlueMap webserver (config {cfg_dir})")
    subprocess.run([java, "-jar", jar, "-c", cfg_dir, "-w"], check=False)


# ---------------------------------------------------------------------------------------------
def main():
    p = argparse.ArgumentParser(description="BlueMap pipeline for the world-downloader")
    p.add_argument("command", choices=["fetch-bluemap", "upgrade", "render", "serve", "all"])
    p.add_argument("--world", help="path to the downloaded world")
    p.add_argument("--server-jar", help="Paper/vanilla server jar for --forceUpgrade")
    p.add_argument("--bluemap-jar", help="path to a BlueMap CLI jar (downloaded if omitted)")
    p.add_argument("--bluemap-version", default=DEFAULT_BLUEMAP_VERSION)
    p.add_argument("--out", help="webroot output directory for the rendered map")
    p.add_argument("--config", help="BlueMap config directory")
    p.add_argument("--settings", help="settings.json with BlueMap options")
    p.add_argument("--workdir", default=".", help="working directory for config/data/jar (default cwd)")
    p.add_argument("--java", help="path to java (defaults to JAVA_HOME/java)")
    p.add_argument("--in-place", action="store_true", help="upgrade the world in place (no copy)")
    p.add_argument("--serve", dest="then_serve", action="store_true", help="for 'all': serve after rendering")
    p.add_argument("--allow-nonzero", action="store_true", help="don't fail on non-zero exit codes")
    args = p.parse_args()
    args.workdir = os.path.abspath(args.workdir)
    os.makedirs(args.workdir, exist_ok=True)

    if args.command == "fetch-bluemap":
        fetch_bluemap(args.bluemap_version, args.bluemap_jar or os.path.join(args.workdir, f"bluemap-{args.bluemap_version}-cli.jar"))
    elif args.command == "upgrade":
        upgrade(args)
    elif args.command == "render":
        render(args)
    elif args.command == "serve":
        serve(args)
    elif args.command == "all":
        if args.server_jar:
            args.world = upgrade(args)
        render(args)
        if args.then_serve:
            serve(args)


if __name__ == "__main__":
    main()
