# mcwd-scraper — automatic world explorer

Mineflayer bots that connect **through the world-downloader proxy** and walk/fly a grid so the proxy
captures every chunk in an area automatically — no human walking required.

## How it fits together

```
[scraper bot(s)]  ->  [world-downloader proxy]  ->  [Minecraft server]
        walks/flies a grid        saves chunks to disk
```

Point the bots at the **proxy** (e.g. `localhost:25565`), not the real server. Start the downloader
first, then run the scraper.

## Install

```bash
cd scraper
npm install
```

## Run

```bash
cp config.example.json config.json   # edit it
node scrape.js --config config.json
```

## Settings (`config.json`)

| key | meaning |
|-----|---------|
| `host`, `port` | the **proxy** address the bots connect to |
| `version` | force a protocol version, or `false` to auto-detect |
| `accounts` | one entry per bot: `{ "auth": "microsoft", "username": "you@example.com" }` or `{ "auth": "offline", "username": "Name" }`. Multiple entries = multiple bots; the grid is split between them. |
| `center` + `radius` | area to cover, in **blocks**, around a center |
| `bbox` | alternatively an explicit box `{ "minX", "minZ", "maxX", "maxZ" }` (block coords); takes precedence over center/radius |
| `chunkStep` | visit every Nth chunk (1 = every chunk) |
| `flyWhenAble` | fly the grid in **creative/spectator** |
| `walkWhenGrounded` | walk the grid in **survival/adventure** |
| `flyAltitude` | Y to fly at when flying |
| `arriveRadius`, `waypointTimeoutMs`, `loadWaitMs` | navigation tuning (arrival distance, per-waypoint timeout, dwell time so the proxy captures the chunk) |
| `visitedFile` | where visited chunks are remembered |
| `revisit` | `true` ignores the visited cache and re-walks everything |
| `containerDwellMs` | extra pause per chunk so the proxy can auto-open + save nearby containers (use with the downloader's `--auto-open-containers`) |
| `finalDrainMs` | after finishing the grid, stay connected this long so the proxy flushes pending chunk/container saves before disconnecting ("wait till all containers are saved"). The downloader also saves everything on disconnect. |
| `loginStaggerMs` | delay between starting each bot |

## Capturing containers while scraping

Run the downloader with `--auto-open-containers` so it opens and saves containers as the bots pass by.
Set `containerDwellMs` (e.g. 400–800) so each chunk is dwelt on long enough for the open+save, and keep
`finalDrainMs` non-zero so the last saves are flushed before the bots leave.

## Large areas (e.g. 3000×3000)

The area is just `radius`/`bbox` in blocks, so a 3000×3000 scrape is `"bbox": {"minX":-1500,"minZ":-1500,
"maxX":1500,"maxZ":1500}` (~35k chunks). Notes:

- **Walking** (survival/adventure) a 3000×3000 area is many hours of real time. Use **multiple bots**
  (the grid is partitioned across them) to cut it down, and lower `loadWaitMs`.
- **Flying** (creative/spectator) is far faster for large areas; prefer it when the gamemode allows.
- The visited cache means you can stop and resume — re-runs skip what's already downloaded.

Verified: scraping works in **survival** and **adventure** (pathfinder walking) and in
**creative/spectator** (flight), with download + visited-dedup confirmed end-to-end.

## Gamemode awareness

The bot reads `bot.game.gameMode` after spawning:

- **creative** → uses creative flight to fly the grid quickly.
- **spectator** → flies via movement controls.
- **survival / adventure** → walks the grid. Uses
  [mineflayer-pathfinder](https://github.com/PrismarineJS/mineflayer-pathfinder) when installed; falls
  back to a simple flat-world walk otherwise.

Each behaviour can be turned off with `flyWhenAble` / `walkWhenGrounded`.

## Re-runs / dedup

Visited chunks are saved to `visitedFile`. Re-running skips chunks already downloaded. Set
`"revisit": true` to force a full re-walk (e.g. to pick up changes).

## Microsoft login

Set an account's `auth` to `microsoft`. On first run a device code is printed
(`open https://microsoft.com/link and enter code XXXX-XXXX`). Tokens are cached per account under
`.minecraft-auth/<username>/` so later runs don't prompt again. Requires
[prismarine-auth](https://github.com/PrismarineJS/prismarine-auth).
