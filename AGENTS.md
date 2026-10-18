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

Update the board during the work, not only at release time. Before changing files, put the
message on its ticket: a new request gets a new ticket (In progress), and a problem Andrzej
reports on Needs Human Test work moves that ticket to Changes Requested with the notes quoted.
Before ending the turn, update the ticket's body (What was done, How, Commits, Test list) and
its Status, Version and End date. Read the board with `notion-query-data-sources` mode `view`
on `view://3fd20ca7-5d20-81fb-8e3b-000c14bbfcac` or `notion-fetch` a ticket. SQL and rows mode share a
small quota that runs out. The workspace `AGENTS.md` has the full routine.

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
