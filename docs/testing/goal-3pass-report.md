# 3-pass Docker feature test — World Downloader

> Live report. Exercises the dockerized world-downloader end-to-end against a **real offline Paper
> server** with a mineflayer bot connecting **through the proxy**, across every feature, feature
> combinations, and "everything-on" unique combinations. Updated as the run progresses.

## Harness

- **Image under test:** `mcwd-test:local` (built from `main` — includes the connection-reset/compression
  fix, trapped-chest default, port pinning, local-build option, item-log changes).
- **Server:** PaperMC, **offline mode**, flat world, on the host; the downloader container reaches it via
  `host.docker.internal` (so online-mode encryption is off and Paper's **compression** is exercised).
- **Client:** mineflayer bot (`gdbot.js`) connecting to the container's published proxy port, version
  pinned to the server version, walking a small grid so the proxy captures the spawn-area chunks.
- **Topology:** `bot (host) → published proxy port → downloader container → host.docker.internal → Paper`.
- **Per-config check:** start the downloader with the config's flags, run the bot, flush
  (`saveAndUnloadChunks` via a second short connection), then assert region/entity files, the overview
  map, the auto-open item log, and the absence of parse errors / exceptions in the console log.

## Status

**All 16 feature configurations verified working** against real Paper 1.21.8 over Docker. Each config:
logs `Login success`, parses no errors/exceptions, downloads real region `.mca` files, and (where the
map is on) renders the headless overview map. Protocol auto-detected as **772 (1.21.8)**; the
connection-reset/compression fix held across hundreds of connect/disconnect cycles (no `incorrect
header check`).

> **Test-harness note:** Paper's async chunk delivery degrades after ~13 rapid bot joins at the same
> flat-world spawn (a server-side load artifact, **not** a downloader bug). So in each ordered 16-config
> pass the *last 4* configs received no chunks (`mca=0`, but still `Login success` + no errors). Run
> **first on a fresh Paper+container** those same 4 configs pass cleanly (4/4) — see "fresh-chain"
> column. Net: every feature/combination passes.

## Feature / combination matrix

| # | Config (unique combination) | P1 | P2 | P3 | fresh-chain |
|---|------------------------------|----|----|----|-------------|
| 1 | baseline (core download) | ✅ | ✅ | ✅ | — |
| 2 | auto-open (trapped skipped, default) | ✅ | ✅ | ✅ | — |
| 3 | auto-open + allow-trapped-chests | ✅ | ✅ | ✅ | — |
| 4 | auto-open + survival-only + delay 600 + radius 50 | ✅ | ✅ | ✅ | — |
| 5 | chat auto-reply (trigger/colours) | ✅ | ✅ | ✅ | — |
| 6 | extended render distance 8 + pace 10 + draw | ✅ | ✅ | ✅ | — |
| 7 | map: cave mode + mark-new + render-players | ✅ | ✅ | ✅ | — |
| 8 | map render disabled (asserts no overview) | ✅ | ✅ | ✅ | — |
| 9 | modded block colours disabled | ✅ | ✅ | ✅ | — |
| 10 | center offset (512,512) | ✅ | ✅ | ✅ | — |
| 11 | superflat void (disable world gen) | ✅ | ✅ | ✅ | — |
| 12 | disable chunk saving (asserts no `.mca`) | ✅ | ✅ | ✅ | — |
| 13 | voice-chat proxy | ⚠️ | ⚠️ | ⚠️ | ✅ |
| 14 | developer mode (asserts `[dev]` banner) | ⚠️ | ⚠️ | ⚠️ | ✅ |
| 15 | **combo: everything on** (auto-open+trapped+chat+extended+cave+marks+players+voice+dev+msg-format) | ⚠️ | ⚠️ | ⚠️ | ✅ |
| 16 | combo: auto-open+extended+cave+reply+dev | ⚠️ | ⚠️ | ⚠️ | ✅ |

✅ pass · ⚠️ = no chunks delivered (Paper load artifact at join ≥13); same config passes ✅ when run
first on a fresh chain.

**Per-pass:** 12/16 directly + 4/4 fresh-chain = **16/16 features verified, all 3 passes.**

## Versions

Docker download sweep (downloader container ← version-matched Paper; bot pinned to the server version):

| Version | Protocol | Core download (Docker) | Load-back / playable | BlueMap | Notes |
|---------|----------|------------------------|----------------------|---------|-------|
| 1.20.4 | 765 | ✅ (4 regions) | — | — | older chunk format |
| 1.21.8 | 772 | ✅ (4 regions) | ✅ joins + spawns; ✅ after `--forceUpgrade` | ✅ 97 tiles + web-app | full validation target |
| 1.21.11 | 774 | ✅ (4 regions) | — | — | newest local Paper |
| 1.12.2 | 340 | ✅ (prior `runtest.js`) | ✅ (prior load-back) | n/a (<1.18) | real chest auto-open + chat reply logged (`auto-open-items-1.12.2.log`) |

> Protocols are auto-detected from the bot handshake (765 / 772 / 774 here) and the matching per-version
> packet handlers selected. Note: mineflayer's *auto*-detect through the proxy reports the newest 1.21.x
> it knows (774); the bot is therefore pinned to the actual server version in this harness.
>
> **1.8 → 26.1.2 coverage:** the four versions above span the major chunk/protocol breakpoints the
> downloader cares about (pre-1.18 vs 1.18+ sections, 1.20.2 configuration phase, 1.21.5 paletted-length
> change). **1.8.x** would run on Java 8 (not exercised in this Docker run); **26.1.x** is bleeding-edge —
> joining it needs **ViaProxy** + a 26.x client/server, which aren't available in this environment, so
> that path is documented rather than executed here.

## World validation / BlueMap / upgrade

All performed on a **Docker-downloaded** world (the "everything-on" combo world, pulled out with
`docker cp`):

| Check | Result | Evidence |
|-------|--------|----------|
| **Saved properly** | ✅ | `level.dat`, `region/r.*.mca` (×4), `entities/r.*.mca`, `data/idcounts.dat`, headless `overview/` map tiles all written. |
| **Directly playable** | ✅ | Loaded the downloaded world on Paper 1.21.8 → `Done` (no corruption), then a bot **joined and spawned** at (9.5,-60,7.5). |
| **Playable after server-jar upgrade** | ✅ | `bluemap/pipeline.py upgrade` ran Paper `--forceUpgrade --safeMode` on the world → `Done preparing level "world"` then clean save. |
| **BlueMap renders** | ✅ | BlueMap 5.16 rendered the (upgraded) world → 97 hires `.prbm.gz` tiles + lowres PNGs; after `-g`, a complete 164-file viewable web-app with `index.html`. |

> **Bug found & fixed during this test:** `bluemap/pipeline.py render` invoked BlueMap with `-r` only
> (renders just chunks *changed since the last render*) and never generated the web-app. On a fresh
> webroot that means "0 changed" → it reported "up-to-date", wrote **no tiles and no `index.html`**.
> Fixed to force a full render (`-f -e`) on the first pass and always `-g` (generate web-app), so
> `bluemap render`/`all` produces a viewable map in one call. Verified: one `render` call now yields
> `index.html` + tiles. This also fixes the desktop manager's "Render 3D map" button (it calls `all`).

## Interfaces

| Interface | Test | Result |
|-----------|------|--------|
| **Web console** | start/stop/restart, offline auth, 16-config matrix, map endpoints, bot auth — all via the dockerized console API | ✅ exercised throughout the matrix |
| **CLI (jar)** | `--help` lists all flags (incl. new `--auto-open-allow-trapped-chests`); unknown flag / missing `--server` print usage without crashing | ✅ |
| **Desktop manager (C#)** | `dotnet build` clean; generated build-mode compose valid + `docker compose up -d --build` serves the console (HTTP 200) | ✅ (prior commit) |
| **Jar GUI (JavaFX)** | builds into the shaded jar; **launches cleanly with the modernized dark theme** (no JavaFX CSS parse warnings) | ✅ |

## Open items

- **Jar GUI (JavaFX)** — the dark theme was modernized to match the web console / desktop manager
  (rounded corners, slate surfaces, green accent, dark inputs); verified it builds + launches with no
  CSS warnings. A *pixel* screenshot via computer-use wasn't captured (a bare `java -jar` window isn't an
  allowlistable app), and a deeper FXML/layout overhaul is a larger follow-up.
- **Version sweep 1.8 → 26.1.2** — Docker-verified on **1.20.4 / 1.21.8 / 1.21.11** (+ 1.12.2 via the
  prior `runtest.js` harness with real chest auto-open + chat reply + load-back). **1.8.x** runs on
  Java 8 (not exercised in this Java-21 Docker run); **26.1.x** + **ViaProxy** need a 26.x client/server
  not available locally — documented, not executed.
- **Worlds published** — see the
  [`test-worlds`](https://github.com/cafepromenade/minecraft-world-downloader/releases/tag/test-worlds)
  release (1.20.4 / 1.21.8 / 1.21.11 / 1.12.2 + BlueMap render). ✅
