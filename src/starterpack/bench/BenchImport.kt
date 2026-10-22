package starterpack.bench

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.impl.campaign.ids.Tags
import com.fs.starfarer.api.loading.WeaponGroupType
import starterpack.catalog.Catalog
import starterpack.model.ShipEntry
import starterpack.model.Template
import starterpack.model.WeaponGroup
import starterpack.store.TemplateStore

/** What a bench trip changed, for reporting in the editor. */
class ImportResult(
    val shipsUpdated: Int = 0,
    val warnings: List<String> = emptyList(),
) {
    val changedAnything: Boolean get() = shipsUpdated > 0

    fun summary(): String = when {
        shipsUpdated == 1 -> "Imported 1 ship from the refit screen."
        shipsUpdated > 1 -> "Imported $shipsUpdated ships from the refit screen."
        warnings.isNotEmpty() -> "Nothing imported from the refit screen."
        else -> "The refit screen had nothing new."
    }
}

/**
 * Reads a refitted [ShipVariantAPI] back into a [ShipEntry].
 *
 * The obvious route -- parsing the `.variant` file the game writes to `saves/missions/` -- is not
 * available: Starsector's classloader refuses `java.io` to mod code outright ("File access and
 * reflection are not allowed to scripts"), and the sanctioned file API reaches only `saves/common`.
 * So the loadout is taken from the live variant object instead, which is better anyway: no parsing,
 * no filename-to-ship guesswork, and the ids are already resolved.
 *
 * The variant's hullmod sets nest -- [ShipVariantAPI.getSMods] within [ShipVariantAPI.getPermaMods]
 * within [ShipVariantAPI.getHullMods] -- whereas this mod keeps four disjoint lists, so importing is
 * mostly subtracting them apart again. See [decomposeHullMods].
 */
object BenchImport {

    /**
     * Copies one refitted variant onto one template ship.
     *
     * Returns false and touches nothing when the variant is not the hull the entry expects -- see
     * [hullsMatch]. Overwriting the wrong ship's loadout is worse than importing none.
     */
    fun importVariant(entry: ShipEntry, variant: ShipVariantAPI, warnings: MutableList<String>): Boolean {
        val hullId = runCatching { variant.hullSpec?.hullId }.getOrNull().orEmpty()
        if (!hullsMatch(hullId, entry.hullId)) {
            warnings += "Skipped a loadout: it is '$hullId' but the ship expects '${entry.hullId}'."
            return false
        }
        return runCatching {
            readLoadout(variant, entry)
            readModules(variant, entry, warnings)
            true
        }.getOrElse {
            warnings += "Could not read the loadout for '${entry.hullId}': ${it.message}"
            false
        }
    }

    /** Everything a refit can change on one ship or one module, copied onto [entry]. */
    private fun readLoadout(variant: ShipVariantAPI, entry: ShipEntry) {
        entry.vents = variant.numFluxVents
        entry.capacitors = variant.numFluxCapacitors
        decomposeHullMods(variant, entry)
        readWeapons(variant, entry)
        readWings(variant, entry)
    }

    /**
     * A module's loadout as a ship entry of its own, keyed to the module's hull.
     *
     * Also what the editor shows for a module nobody has refitted yet, read off its stock variant,
     * so a module looks the same in the editor whichever way its loadout was arrived at. D-mods are
     * dropped: no refit can put one on a module, and the applier never installs them there.
     */
    fun moduleLoadoutOf(module: ShipVariantAPI): ShipEntry {
        val entry = ShipEntry(hullId = runCatching { module.hullSpec?.hullId }.getOrNull().orEmpty())
        readLoadout(module, entry)
        entry.dMods.clear()
        return entry
    }

    /**
     * Whether a refitted variant is the hull the template expects.
     *
     * A ship carrying D-mods is built on its (D) hull, so what comes back is the derived id. Both
     * directions are accepted because the template stores the base hull and its D-mods separately.
     */
    private fun hullsMatch(fromVariant: String, fromTemplate: String): Boolean {
        if (fromVariant.isEmpty() || fromTemplate.isEmpty()) return false
        if (fromVariant == fromTemplate) return true
        return fromVariant.removeSuffix(D_HULL_SUFFIX) == fromTemplate.removeSuffix(D_HULL_SUFFIX)
    }

    /**
     * Splits the variant's nested hullmod sets into this mod's four disjoint lists.
     *
     * `getHullMods()` is everything installed, `getPermaMods()` the subset built in permanently, and
     * `getSMods()` the subset of *those* that also counts against the built-in limit. This mod's
     * `permaMods` means specifically the **free** built-ins -- permanent but not counted -- so it is
     * the difference of the two, and it is the one thing the refit screen cannot grant.
     *
     * The hull's own innate built-ins are dropped: they come with the hull, cost nothing, and cannot
     * be removed, so recording them would make the applier try to install what is already there.
     */
    private fun decomposeHullMods(variant: ShipVariantAPI, entry: ShipEntry) {
        val innate = runCatching { variant.hullSpec?.builtInMods?.toSet() }.getOrNull().orEmpty()
        val all = runCatching { variant.hullMods?.toList() }.getOrNull().orEmpty().filterNot { it in innate }
        val perma = runCatching { variant.permaMods?.toSet() }.getOrNull().orEmpty()
        val sMods = runCatching { variant.sMods?.toSet() }.getOrNull().orEmpty()

        val dMods = all.filter { isDMod(it) }.toSet()

        entry.dMods.clear()
        entry.dMods.addAll(dMods)

        entry.sMods.clear()
        entry.sMods.addAll(all.filter { it in sMods && it !in dMods })

        entry.permaMods.clear()
        entry.permaMods.addAll(all.filter { it in perma && it !in sMods && it !in dMods })

        entry.hullMods.clear()
        entry.hullMods.addAll(all.filterNot { it in perma || it in sMods || it in dMods })
    }

    private fun isDMod(id: String): Boolean =
        runCatching { Catalog.hullModSpec(id)?.hasTag(Tags.HULLMOD_DMOD) == true }.getOrDefault(false)

    /**
     * Weapons and their groups.
     *
     * Groups are recorded rather than regenerated because the refit screen is the only place this mod
     * lets you arrange them; throwing them away here would make the trip lossy in the one dimension
     * the editor cannot otherwise reach. Built-in slots are excluded -- they come with the hull and
     * cannot be changed, so writing them into the template would only invite the applier to refit
     * something that was never removable.
     */
    private fun readWeapons(variant: ShipVariantAPI, entry: ShipEntry) {
        val fitted = runCatching { variant.nonBuiltInWeaponSlots?.toSet() }.getOrNull().orEmpty()

        entry.weapons.clear()
        for (slot in fitted) {
            val weaponId = runCatching { variant.getWeaponId(slot) }.getOrNull() ?: continue
            if (weaponId.isNotBlank()) entry.weapons[slot] = weaponId
        }

        entry.weaponGroups.clear()
        val groups = runCatching { variant.weaponGroups?.toList() }.getOrNull().orEmpty()
        for (spec in groups) {
            val slots = runCatching { spec.slots?.filter { it in entry.weapons } }.getOrNull().orEmpty()
            if (slots.isEmpty()) continue
            entry.weaponGroups += WeaponGroup(
                autofire = runCatching { spec.isAutofireOnByDefault }.getOrDefault(false),
                mode = if (runCatching { spec.type }.getOrNull() == WeaponGroupType.ALTERNATING) {
                    WeaponGroup.ALTERNATING
                } else {
                    WeaponGroup.LINKED
                },
                slots = ArrayList(slots),
            )
        }
    }

    /**
     * Fighter wings, by bay.
     *
     * Bay position is meaningful, so an empty bay survives as a null rather than being dropped -- the
     * same reason the template's own serialisation writes JSON nulls here. The hull's built-in bays
     * are skipped so the list lines up with the bays the applier actually assigns.
     */
    private fun readWings(variant: ShipVariantAPI, entry: ShipEntry) {
        val wings = runCatching { variant.wings?.toList() }.getOrNull().orEmpty()
        val hullSpec = runCatching { variant.hullSpec }.getOrNull()

        val out = ArrayList<String?>()
        for ((bay, wingId) in wings.withIndex()) {
            val builtIn = runCatching { hullSpec?.isBuiltInWing(bay) == true }.getOrDefault(false)
            if (builtIn) continue
            out += wingId?.takeIf { it.isNotBlank() }
        }
        while (out.isNotEmpty() && out.last() == null) out.removeAt(out.size - 1)

        entry.wings.clear()
        entry.wings.addAll(out)
    }

    /**
     * Station modules: which module each slot holds, and what is fitted on it.
     *
     * The ids the refit leaves in the module map are never recorded. Saving a mission ship renames
     * every one of its modules, touched or not, to an id shared with the next ship's modules -- see
     * [Catalog.isStableModuleId] -- so the id is the one thing about a module here that cannot be
     * trusted. Which module sits in a slot is fixed by the hull anyway (the refit screen cannot swap
     * one), so the template keeps the stock id it already had, and only an id the bench itself wrote
     * in an earlier version is dropped so the hull's own module takes over again.
     *
     * The loadout is read off the module variant the ship *holds*, which is the one that was
     * refitted, and is checked against the hull the slot's stock module is built on before it is
     * kept. A module whose loadout still matches its stock variant stores nothing, so it keeps
     * following the mod that adds it.
     */
    private fun readModules(variant: ShipVariantAPI, entry: ShipEntry, warnings: MutableList<String>) {
        val slots = Catalog.moduleSlotIds(runCatching { variant.hullSpec }.getOrNull())
        if (slots.isEmpty()) return
        val defaults = Catalog.defaultModules(entry.hullId)

        for ((index, slotId) in slots.withIndex()) {
            val recorded = entry.modules[slotId]
            if (recorded != null && !Catalog.isStableModuleId(recorded)) entry.modules.remove(slotId)

            val stockId = Catalog.resolveModule(entry.modules[slotId], defaults[slotId]) ?: continue
            val stock = Catalog.variant(stockId) ?: continue
            val module = runCatching { variant.getModuleVariant(slotId) }.getOrNull() ?: continue

            val loadout = moduleLoadoutOf(module)
            val stockHull = Catalog.moduleHullId(stockId).orEmpty()
            if (loadout.hullId.removeSuffix(D_HULL_SUFFIX) != stockHull.removeSuffix(D_HULL_SUFFIX)) {
                warnings += "Module ${index + 1} on '${entry.hullId}' came back as a different part; " +
                    "its refit was not kept."
                continue
            }
            // Recorded against the stock module's hull, which is what the applier checks it against.
            loadout.hullId = stockHull
            if (sameLoadout(loadout, moduleLoadoutOf(stock))) {
                entry.moduleLoadouts.remove(slotId)
            } else {
                entry.moduleLoadouts[slotId] = loadout
            }
        }
        // Slots the hull no longer has, after a mod update, would only confuse the next refit.
        entry.moduleLoadouts.keys.retainAll(slots.toSet())
    }

    /** Whether two loadouts would build the same module. */
    fun sameLoadout(a: ShipEntry, b: ShipEntry): Boolean =
        a.hullId == b.hullId &&
            a.vents == b.vents && a.capacitors == b.capacitors &&
            a.weapons == b.weapons &&
            a.wings == b.wings &&
            a.hullMods.toSet() == b.hullMods.toSet() &&
            a.sMods.toSet() == b.sMods.toSet() &&
            a.permaMods.toSet() == b.permaMods.toSet() &&
            a.weaponGroups.map { it.toJson().toString() } == b.weaponGroups.map { it.toJson().toString() }

    /**
     * The opportunistic path home: the variant ids a mission refit leaves registered.
     *
     * The game names a refitted mission ship `mission_<mission id>_ship_<n>` and keeps it in the
     * variant store after the mission is left, so the loadouts can be collected on the main menu with
     * no combat at all. The store also holds every such ship from earlier sessions, loaded from disk
     * at startup, which is why only ships saved since the bench was built count. When nothing was
     * saved this finds nothing, and [BenchCapture] picks the ships up in the mission instead.
     */
    fun consumeOnReturn(): ImportResult? {
        // Only after the bench was built this session. The game loads every saved mission ship from
        // disk at startup, so without this a plain restart re-imported the last bench trip over the
        // active template -- edits made since included -- and with module ids that no longer named
        // the right modules.
        val baseline = BenchState.baseline() ?: return null
        val template = BenchState.templateForBench() ?: return null
        if (template.ships.isEmpty()) return null

        val warnings = ArrayList<String>()
        var updated = 0

        for ((index, entry) in template.ships.withIndex()) {
            val variant = BenchState.savedMissionVariant(index) ?: continue
            // Saving a refit registers a new object under the id, so the same object as when the
            // bench was built means this ship was not refitted on this trip. A ship the bench never
            // built this session is left alone for the same reason.
            if (!baseline.containsKey(index) || baseline[index] === variant) continue
            if (importVariant(entry, variant, warnings)) updated++
        }
        BenchState.clearBaseline()

        if (updated == 0 && warnings.isEmpty()) return null

        val result = ImportResult(updated, warnings)
        if (result.changedAnything) runCatching { TemplateStore.flush() }
        BenchState.clearAwaiting()
        BenchState.lastImport = result
        log.info("StarterPack: ${result.summary()}")
        return result
    }

    /** Suffix the game appends when a hull is swapped to its damaged version. */
    private const val D_HULL_SUFFIX = "_D"

    private val log = Global.getLogger(BenchImport::class.java)
}
