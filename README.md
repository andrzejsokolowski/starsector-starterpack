# StarterPack

Build a starting loadout once, stamp it onto every new game.

Made for testing. If you start a lot of campaigns and spend the first ten minutes of each one adding
the same three ships, fitting the same weapons and spending the same skill points, this does that
part for you.

It also makes a new game plus: save a campaign you are playing as a [snapshot](#snapshots-a-new-game-plus),
and the next new game starts with that fleet, character and cargo.

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
| **Modules** | Multi-module hulls keep their modules, and each module has its own weapons, fighters, vents/capacitors and hullmods; loading a stock loadout brings that variant's modules |
| **Hullmods** | Regular (cost OP), S-mods (built in, count against the limit), free built-ins (permanent, no S-mod cost), D-mods |
| **Cargo** | Commodities, loose weapons, fighter LPCs, hullmod blueprints, special items |
| **Character** | Credits, unspent skill points, story points, level |
| **Reputation** | One standing for every faction, plus single factions on top of it, from -100 to 100 |
| **Hotbar** | All five bars, ten slots each, with per-slot hyperspace overrides |

The *free built-ins* list has no equivalent in a normal game: those hullmods are permanent and cost
nothing, but do not count against the ship's built-in limit, so the ship keeps its full story-point
allowance on top.

**Load a stock loadout** on the ship editor copies any vanilla or modded variant of that hull into
the template in one click. That is usually the fastest way to start — pick the closest stock variant,
then change what you want.

## Faction standings

The CHARACTER tab sets where you stand with each faction, on the same -100 to 100 scale as the
console's `setrelation`. Tick *Set my standing with every faction* to give one value to every faction
the intel screen lists, then add single factions to override it: everyone at 0 but the pirates at
-50, say. Hidden factions such as the Remnant are left out of "every faction" but can be added one by
one. Leave it all unset and the standings stay as the campaign start made them.

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
have no editor of their own here, so the bench is the only way to set them. On a multi-module ship
the same goes for every module you fit on its own tab.

Adding and removing ships stays in the editor — a mission's fleet is fixed by its definition. D-mods
and free built-ins also stay in the editor, since the refit screen cannot grant either, though it
will not strip them off a ship that already has them.

## Snapshots: a new game plus

A snapshot is a copy of a running campaign that a new game can start from. It is kept apart from
the templates: templates are built by hand in the editor, snapshots are taken in a campaign.

**Taking one.** With OddiszCommons 1.1.0 or newer installed, open the **Manage (Y)** screen in the
campaign, go to the **Others** tab, pick **StarterPack** in its list and press **SAVE A SNAPSHOT**. *Save over it* replaces an older snapshot with
the campaign as it is now. Without OddiszCommons, run `starterpack snapshot` in the console.

**What it keeps:**

| | |
|---|---|
| **Fleet** | Every ship with its name, loadout, modules, S-mods, D-mods and weapon groups, the flagship, and which ships were mothballed. Ships start repaired. |
| **Officers** | Name, portrait, personality, level, experience and skills, on the ship they captained. Mentored and exceptional officers stay that way. Mercenaries come along as regular officers. |
| **AI core captains** | The core on each ship, with its level and skills. |
| **Cargo** | Everything in the fleet's hold, and your credits. |
| **Character** | Level, experience, skills, unspent skill points, story points and the bonus experience still owed for spent story points. |
| **Reputation** | Your standing with every faction. |
| **Hotbar** | All five bars and every ability you know. |
| **Blueprints** | Every ship, fighter and weapon blueprint and every hull mod you know. Applying only adds; nothing the new game knows is forgotten. |

Left out on purpose, because they belong to the old sector: colonies, storage at markets, contacts,
quests and commissions. Hull mods the game hides are left out too: other mods put them on and keep
what they mean elsewhere in the save. The perks of New Game Plus - Renewed stay behind, since they
are bought for each campaign. Your name and portrait are the ones you pick for the new game.

**Starting from one.** Main menu, STARTER PACK, **SNAPSHOTS** tab: tick *Start every new game from
this snapshot*. Every new game then gets it laid over the start, instead of the active template,
once per campaign like a template. The tab also shows what each snapshot holds, and renames or
deletes them. `starterpack snapshot apply <name>` applies one to a campaign that is already running.

Snapshots live in `saves/common/starterpack/snapshots.json`, next to the templates.

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

starterpack snapshot [name]               save this campaign as a snapshot
starterpack snapshot list                 every saved snapshot
starterpack snapshot use <name>           start every new game from it
starterpack snapshot off                  stop starting new games from a snapshot
starterpack snapshot apply <name> [force] apply a snapshot to this campaign now
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

- Starsector 0.98.5a
- LazyLib
- Console Commands (optional)
- OddiszCommons 1.1.0 or newer (optional, for saving snapshots from the Manage screen)

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
