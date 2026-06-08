# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository. Read this before making changes.

This is the **cafepromenade** fork of minecraft-world-downloader: a Minecraft proxy that sits between a
client and a server and saves the chunks/entities/containers the client sees, plus a web console,
desktop manager, an auto-explore bot, and a BlueMap pipeline. See [`handoff.md`](handoff.md) for the
project state and architecture; this file is the practical "how to build/test/work here" reference.

## Repository layout

| Path | Language | What it is |
|------|----------|-----------|
| `src/main/java` | Java 21 | The downloader proxy itself (packet handling, world saving, GUI). |
| `src/main/resources/protocol-versions.json` | JSON | Per-protocol packet-ID → name maps. Source of truth for versions. |
| `src/test/java` | Java | Unit tests (JUnit 5). |
| `web/` | Python (Flask) | Dockerized web management console (`app.py`, templates, static). |
| `desktop/` | C# (WPF, net8.0-windows) | Windows desktop manager that launches the Docker container + BlueMap + bot. |
| `scraper/` | Node.js (mineflayer) | Auto-explore bot(s) that walk/fly a grid through the proxy. |
| `bluemap/` | Python | Pipeline: upgrade a saved world with a server jar, then render a 3D map with BlueMap. |
| `installer/installer.nsi` | NSIS | Windows installer for the desktop manager. |
| `docs/wiki/` | Markdown | User documentation (mirrors the GitHub wiki). |
| `.github/workflows/` | YAML | CI: build, tests, docker image, desktop release, all-in-one release. |

## Build & run

### Java proxy (primary)
- **Requires JDK 21** (`<java.version>21</java.version>`). The default `java` on PATH may be JRE 8;
  set `JAVA_HOME` to a JDK 21 (on the maintainer's machine: `/c/Program Files/Android/openjdk/jdk-21.0.8`).
- Maven is the build tool (`mvn`, e.g. chocolatey apache-maven).

```bash
export JAVA_HOME="/path/to/jdk-21"
mvn package            # builds target/world-downloader.jar — TESTS ARE SKIPPED BY DEFAULT
mvn test -DskipTests=false   # run the unit tests explicitly
```

> `<skipTests>true</skipTests>` is set in `pom.xml` so `mvn package` just builds. The shaded jar is
> `target/world-downloader.jar`; the thin jar is `target/minecraft-world-downloader-<ver>.jar`.

Run headless: `java -jar target/world-downloader.jar --no-gui -s <server> -o <world>`. See
`docs/wiki/Command-Line-Options.md` for every flag (keep it in sync when adding `@Option`s).

### Web console (Docker)
```bash
docker compose up -d --build      # web console on :8080, proxy on :25565
docker compose --profile bluemap up -d bluemap   # optional 3D map on :8100
```
The image bundles the jar (Maven multi-stage build), Python/Flask, and Node + the scraper.

### Desktop manager (C#)
```bash
dotnet build desktop/WorldDownloaderManager.csproj -c Release        # build-verify
dotnet publish desktop/WorldDownloaderManager.csproj -c Release -r win-x64 --self-contained true -o publish
```
Self-contained publish is required for distribution (framework-dependent builds silently fail to launch
on machines without the .NET 8 Desktop Runtime).

### Scraper (Node) and BlueMap (Python)
```bash
cd scraper && npm install && node scrape.js --config config.json
python bluemap/pipeline.py all --world <world> --server-jar <paper.jar> --out <webroot> --serve
```

## Conventions & gotchas

- **Versions / packets.** Adding a Minecraft version = add a block to `protocol-versions.json`
  (keyed by protocol number → `{version, dataVersion, clientBound{idHex:name}, serverBound{...}}`),
  add a `Version` enum entry, and (if packet layout changed) a versioned handler/chunk class.
  **Verify packet IDs against PrismarineJS minecraft-data**, and cross-check the repo's existing IDs.
  Only packets the downloader needs are mapped; unmapped packets are forwarded untouched.
- **Packet handlers.** Operators live in `packets/handler/*` keyed by packet *name*; they read from a
  copy and return `true` to forward / `false` to drop. Per-packet handler exceptions are caught in
  `DataReader.readPackets` and the packet is still forwarded — don't rely on a handler throwing to
  break a connection.
- **Version branching** uses `Config.versionReporter().isAtLeast(Version.V…)` and
  `Config.versionReporter().select(...)`. NBT chat components are read with `readNbtTag()` for 1.20.3+,
  JSON `readString()` before.
- **Item NBT**: 1.20.5+ uses `count`(int)+`components`; older uses `Count`(byte)+`tag`.
- **`protocol-versions.json` is CRLF with tab indentation** — match the existing style; git may warn
  about LF→CRLF, that's expected.
- **Disconnect logging**: login kicks, in-game kicks, and socket closes log `[disconnect] …`. Routine
  socket closes (`Connection reset`, `Socket closed`, …) are treated as benign (no stack trace) — see
  `ProxyServer.isBenignClose`.
- **New downloader option** = add the `@Option` in `Config.java`, then mirror it in the web console
  `OPTIONS` list (`web/app.py`) and document it in `docs/wiki/Command-Line-Options.md`.
- **Commits**: end commit messages with `Co-Authored-By: Claude <noreply@anthropic.com>`. Commit/push
  only when asked. Don't commit local analysis artifacts (see `.gitignore`).
- **Default branch is `main`** (upstream is `master`).

## Testing

Unit tests: `mvn test -DskipTests=false` (two tests can fail in some environments: `PaletteTransformerTest`
[Mockito + JDK 21] and `CircleGeneratorTest` [stale `target/`; run `mvn clean` first]).

A **live integration harness** (Paper server ← proxy ← mineflayer bot) lives **outside the repo** on the
maintainer's machine at `C:/Users/cntow/mcwdtest/` — it builds the jar, runs end-to-end downloads,
auto-open of every container type, chat reply, saved-world load-back, the scraper (dedup / survival /
adventure / creative / no-stuck), the server-jar upgrade, and the BlueMap render. Key scripts:
`runtest.js <version>`, `run_all_tests.sh`, `testscraper*.js`, `testrd.js`. Build the jar before running.

## When unsure

Prefer the dedicated tools and match surrounding code style. For protocol work, confirm IDs against
minecraft-data rather than guessing. Verify changes build (`mvn package`) and, where a harness exists,
run the relevant integration test before claiming something works.
