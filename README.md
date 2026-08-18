# StarterPack

Build a starting loadout once, stamp it onto every new game.

Made for testing. If you start a lot of campaigns and spend the first ten minutes of each one adding
the same three ships, fitting the same weapons and spending the same skill points, this does that
part for you.

## How it works

1. **Main menu → STARTER PACK.** The editor opens over the title screen. No campaign needed.
2. **Build a template.** Ships, their weapons, fighters, hullmods, S-mods, D-mods; your cargo,
   credits, skill points, story points and hotbar.
3. **Start a new game.** Either tick *Apply automatically on new game* in the editor, or run
   `starterpack` in the console once the campaign has started.

Templates live in `saves/common/starterpack/templates.json`, independent of any save. Deleting every
campaign you own does not touch them, and they are plain JSON if you would rather hand-edit.

## What a template can set

| | |
|---|---|
| **Ships** | Hull, custom name, flagship, vents/capacitors, a weapon per slot, a fighter wing per bay |
| **Modules** | Multi-module hulls keep their modules; loading a stock loadout brings that variant's |
| **Hullmods** | Regular (cost OP), S-mods (built in, count against the limit), free built-ins (permanent, no S-mod cost), D-mods |
| **Cargo** | Commodities, loose weapons, fighter LPCs, hullmod blueprints, special items |
| **Character** | Credits, unspent skill points, story points, level |
| **Hotbar** | All five bars, ten slots each, with per-slot hyperspace overrides |

The *free built-ins* list has no equivalent in a normal game: those hullmods are permanent and cost
nothing, but do not count against the ship's built-in limit, so the ship keeps its full story-point
allowance on top.

**Load a stock loadout** on the ship editor copies any vanilla or modded variant of that hull into
the template in one click. That is usually the fastest way to start — pick the closest stock variant,
then change what you want.

## The refit bench

Fitting ships in this mod's own pickers is fine, but the game already has a better loadout editor:
the refit screen. The bench lends it to you.

1. Build your fleet on the **Ships** tab, then press **Customize in Refit Screen**.
2. Main menu → **Missions** → *! StarterPack Refit Bench* → **Refit**.
   The leading `!` is there to make it stand out: the mission list is ordered by mod load
   order, which a mod cannot influence, so it sits wherever your modlist puts it.
3. Fit your ships in the real refit screen, with real ordnance points and real hullmod rules.
4. **Start the battle.** Your loadouts are saved back the instant it begins; leave straight away.

The mission is not meant to be fought; it exists to own a refit screen.

Starting the battle is not decoration. Mod code cannot read the `.variant` files the game writes —
Starsector's classloader refuses `java.io` outright, and the file API it does provide reaches only
`saves/common`. So the loadouts are taken from the live fleet instead, which needs the mission's
combat engine to exist. In exchange the ships are matched by fleet member id rather than by filename,
so a loadout can never land on the wrong ship.

Weapons, fighters, vents, capacitors, hullmods, S-mods and **weapon groups** come back. Weapon groups
have no editor of their own here, so the bench is the only way to set them.

Adding and removing ships stays in the editor — a mission's fleet is fixed by its definition. D-mods
and free built-ins also stay in the editor, since the refit screen cannot grant either, though it
will not strip them off a ship that already has them.

## Console commands

Console Commands is optional. Without it, use the auto-apply toggle.

```
starterpack                      apply the active template
starterpack apply <name>         apply a specific template
starterpack apply <name> force   apply again in a campaign that already had one
starterpack list                 every saved template
starterpack info <name>          what a template contains
starterpack status               active template, auto-apply state, this save's history
starterpack on / off             toggle auto-apply
```

## Applied once per campaign

A flag in the save records that a pack was applied, so you cannot accidentally double your credits
and re-add your fleet. `force` overrides it.

Whether applying *replaces* your existing fleet and cargo or adds to them is per template, on the
TEMPLATES tab. Both default to replacing.

## Missing content is a warning, not a crash

Every id is checked against your current mod list when the template is applied. Anything that no
longer resolves — a weapon from a mod you turned off — is reported by name and skipped; the rest of
the template still applies. Ids from disabled mods are kept in the file and start working again the
moment the mod comes back.

## Requirements

- Starsector 0.98a-RC8
- LazyLib
- Console Commands (optional)

## Credits

`starterpack.uiframework` is vendored from [Refit Filters](https://github.com/Starficz/RefitFilters) by
Starficz, LGPL-3.0-only. See [CREDITS.txt](CREDITS.txt).

## Building

`gradle.properties` points at your Starsector install:

```
starsectorPath=D:/Games/StarSector
```

```bash
./gradlew build
```

The jar lands in `jars/StarterPack.jar`. The Kotlin runtime is deliberately not bundled — LazyLib
provides it on Starsector's shared classloader.
