# StarterPack

Build a starting loadout once at the main menu and stamp it onto every new game. These
instructions apply to this repository, including when it is opened without the parent mod
workspace. The workspace `AGENTS.md` one folder up governs releases, packaging, Git identity and
communication.

## Notion

Tasks and history: the **StarterPack board** in Andrzej's Notion, under **Starsector mods**
(`collection://75bded8e-4793-4adb-8968-07529b5dd1a2`). Working rules and game API notes:
**Claude Information** (`collection://7bf9b1d6-b9e5-4b62-87f1-757bbaa51919`), starting with
"Start here: working on Starsector mods through Notion". Read the board before working, give every
task a ticket (ticket ids start with SP) and keep it current. StarterPack is also bundled in
Coalesced Little Mods: bring every change over there too. The Notion tools are deferred: load them
with ToolSearch first.

## Essentials

- id `starterpack`, package `starterpack`, Kotlin and Gradle, vendored Starficz `uiframework`
  (LGPL-3.0, notice in `CREDITS.txt`, which ships in the ZIP).
- Build: `./gradlew jar` (runs the whole jar `checkNoForbiddenApis` guard). Package: stage
  `StarterPack/` and write `StarterPack.zip`.
- Templates live in `saves/common/starterpack/templates.json`. No `java.io` anywhere: the refit
  bench reads loadouts from the live fleet, never from `saves/missions`.

## Update metadata

Keep `mod_info.json`, `starterpack.version` and `data/config/version/version_files.csv`
consistent. Forum topic 35856.

- updateCheckURL and masterVersionFile:
  `https://raw.githubusercontent.com/andrzejsokolowski/starsector-starterpack/main/starterpack.version`
- directDownloadURL, asset `StarterPack.zip`:
  `https://github.com/andrzejsokolowski/starsector-starterpack/releases/latest/download/StarterPack.zip`
