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
| `loginStaggerMs` | delay between starting each bot |

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
