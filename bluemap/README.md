# BlueMap pipeline

Turn a downloaded world into an interactive **3D web map** with [BlueMap](https://github.com/BlueMap-Minecraft/BlueMap).

```
downloaded world  ->  (1) upgrade with a server jar  ->  (2) BlueMap render  ->  (3) serve web map
```

1. **upgrade** — runs a temporary Paper/vanilla server with `--forceUpgrade --safeMode` to upgrade the
   saved world to the latest data format, then stops it automatically. (The downloader's version-
   specific `downloaded` datapack is stripped from the working copy so the upgrade doesn't abort.)
2. **render** — runs BlueMap (standalone CLI) to render the world into a self-contained web map.
3. **serve** — hosts the rendered map with BlueMap's integrated webserver.

BlueMap supports Minecraft 1.13+; 3D rendering is best on **1.18+** worlds (the focus here).

## Requirements

- **Java** (Java 21 works with BlueMap ≤ 5.16, the pinned default; 5.17+ need Java 25).
- A **server jar** (Paper or vanilla) for the upgrade step — only if you want the upgrade.
- Network access on first render (BlueMap downloads the Minecraft client jar for textures).

## Usage

```bash
# download the BlueMap CLI jar (pinned to a Java-21-compatible version)
python pipeline.py fetch-bluemap --workdir ./bm

# upgrade a downloaded world to the latest format (optional but recommended)
python pipeline.py upgrade --world /data/world --server-jar paper.jar --workdir ./bm

# render the (upgraded) world to a web map
python pipeline.py render --world ./bm/upgrade-server/world --out ./bm/web --workdir ./bm

# or do it all at once and serve it
python pipeline.py all --world /data/world --server-jar paper.jar --out ./bm/web --workdir ./bm --serve

# serve an already-rendered map
python pipeline.py serve --config ./bm/bluemap-config --workdir ./bm
```

The rendered web map is self-contained under `--out` (`index.html` + `maps/`). Serve it with BlueMap's
webserver (`serve`), any static file server, or the world-downloader web console (it links to it).

## Settings

Pass `--settings settings.json` (see `settings.example.json`) to control BlueMap:

| key | meaning |
|-----|---------|
| `acceptDownload` | accept Mojang's EULA so BlueMap can download client textures (required) |
| `renderThreadCount` | render threads (0 = auto) |
| `webserverEnabled`, `webserverPort` | BlueMap's integrated webserver |
| `dimensions` | which of `overworld` / `nether` / `end` to render (only those present are rendered) |
| `ambientLight` | ambient light strength (0–1) |
| `skyColor` | sky/background colour |
| `renderEdges` | render map edges |
| `saveHiresLayer` | save the hi-res 3D layer |
| `minY`, `maxY` | clip the rendered Y range (null = full) |

## Docker

A `bluemap` service is provided in `docker-compose.yml` (profile `bluemap`) that renders the
downloaded world and serves it on port 8100. See the repo's Docker docs.

## Verified

The `upgrade` (Paper `--forceUpgrade`) and `render` (BlueMap 5.16) steps were tested end-to-end on a
downloaded 1.20.4 world: upgrade to 1.21.11 completed and stopped cleanly, and BlueMap produced a
self-contained web map.
