# Credits & attribution

This project (the **cafepromenade** fork) builds on the work of many people and open-source
projects. Thank you to all of them.

## Original project

- **minecraft-world-downloader** by **Mirco Kroon** — the upstream project this is forked from.
  <https://github.com/mircokroon/minecraft-world-downloader>
- The original Go launcher: <https://github.com/mircokroon/minecraft-world-downloader-launcher>

## Forks this fork incorporates / borrowed fixes from

- **TheHecateII** — player skin-heads on the map, modded-block map colours, the Simple Voice Chat /
  PlasmoVoice UDP proxy, `CustomPayload` handling, NeoForge null-safety fixes, and the wall/fence and
  grass/flower map-colour additions. <https://github.com/TheHecateII/minecraft-world-downloader>
- **7byLoper** — UTF-8 decoding fix for protocol strings (`DataTypeProvider.readString`).
  <https://github.com/7byLoper/minecraft-world-downloader>
- **trichhoffson** — `VarLong` 32-bit-shift overflow fix (`DataTypeProvider.readVarLong`).
  <https://github.com/trichhoffson/minecraft-world-downloader>

A scan of every fork ahead of upstream was used to find the above; all other forks were already
covered by, or superseded by, this fork.

## Runtime dependencies — downloader (Java)

- **jo-nbt** (NBT parsing) — <https://github.com/llbit/jo-nbt>
- **Gson** (JSON) — <https://github.com/google/gson>
- **Unirest for Java** (HTTP client) — <https://github.com/Kong/unirest-java>
- **NanoHTTPD** (embedded HTTP) — <https://github.com/NanoHttpd/nanohttpd>
- **Apache Commons IO** — <https://github.com/apache/commons-io>
- **Apache Commons Lang** — <https://github.com/apache/commons-lang>
- **args4j** (command-line parsing) — <https://github.com/kohsuke/args4j>
- **SLF4J** (logging API + simple binding) — <https://github.com/qos-ch/slf4j>
- **dnsjava** (DNS / SRV lookup) — <https://github.com/dnsjava/dnsjava>
- **OpenJFX / JavaFX** (GUI: controls, graphics, fxml, swing) — <https://github.com/openjdk/jfx>

## Build / test dependencies (Java)

- **JUnit 5 (Jupiter)** — <https://github.com/junit-team/junit5>
- **AssertJ** — <https://github.com/assertj/assertj>
- **Mockito** — <https://github.com/mockito/mockito>
- **Apache Maven** (compiler / shade / surefire plugins) — <https://github.com/apache/maven>
- **javafx-maven-plugin** — <https://github.com/openjfx/javafx-maven-plugin>

## Web console (Python)

- **Flask** — <https://github.com/pallets/flask>
- **Waitress** (WSGI server) — <https://github.com/Pylons/waitress>
- **Requests** (HTTP) — <https://github.com/psf/requests>

The live map UI is dependency-free (plain HTML5 canvas + JavaScript, no third-party JS libraries).

## Desktop manager (C#)

- **.NET 8** runtime — <https://github.com/dotnet/runtime>
- **WPF** (Windows Presentation Foundation) — <https://github.com/dotnet/wpf>

## Installer

- **NSIS** (Nullsoft Scriptable Install System) — <https://github.com/kichik/nsis> (mirror) /
  <https://nsis.sourceforge.io/>

## Auto-explore scraper bot (Node.js)

- **Mineflayer** — <https://github.com/PrismarineJS/mineflayer>
- **mineflayer-pathfinder** — <https://github.com/PrismarineJS/mineflayer-pathfinder>
- **prismarine-auth** (Microsoft authentication) — <https://github.com/PrismarineJS/prismarine-auth>
- **minecraft-data** / PrismarineJS ecosystem — <https://github.com/PrismarineJS/minecraft-data>

## Map renderer (BlueMap pipeline)

- **BlueMap** — 3D web map renderer — <https://github.com/BlueMap-Minecraft/BlueMap>
- **PaperMC** — server used to upgrade the saved world to the latest data format —
  <https://github.com/PaperMC/Paper>

## Protocol reference

- **PrismarineJS / minecraft-data** — packet ID / format reference used when adding version support.
  <https://github.com/PrismarineJS/minecraft-data>
- **minecraft.wiki** (formerly wiki.vg) protocol documentation — <https://minecraft.wiki/w/Java_Edition_protocol>

---

If your work is used here and is not credited, please open an issue or PR — it will be added.
