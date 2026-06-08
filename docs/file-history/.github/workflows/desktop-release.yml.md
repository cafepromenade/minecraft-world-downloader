# `.github/workflows/desktop-release.yml`

**YAML** · 66 lines · 2,075 bytes · 5 commit(s) · first 2026-06-01 · last 2026-06-01

## Purpose

Builds the WinUI 3 manager app (self-contained, unpackaged) and packages it with NSIS. On a version tag the installer is attached to a GitHub release.

## Commit history

| Date | Commit | Author | Summary |
| --- | --- | --- | --- |
| 2026-06-01 | `f8a9a52` | cafepromenade | CI: call makensis by full path (not on PATH after choco install) |
| 2026-06-01 | `707693f` | cafepromenade | Desktop: switch manager from WinUI 3 to WPF (reliable build) |
| 2026-06-01 | `98434b5` | cafepromenade | Show toggle descriptions on the console; pin WinUI SDK 1.5.x + CI diagnostic |
| 2026-06-01 | `dedc33d` | cafepromenade | Desktop: canonical WinUI 3 project + detailed control descriptions |
| 2026-06-01 | `2122f46` | cafepromenade | Add animated Material console redesign, WinUI 3 manager, NSIS installer, CI |

[← file-history index](../../docs/file-history/README.md)
