package starterpack.catalog

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ModSpecAPI
import com.fs.starfarer.api.campaign.SpecialItemSpecAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipHullSpecAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.impl.campaign.ids.Tags
import com.fs.starfarer.api.loading.FighterWingSpecAPI
import com.fs.starfarer.api.loading.HullModSpecAPI
import com.fs.starfarer.api.loading.WeaponSlotAPI
import com.fs.starfarer.api.loading.WeaponSpecAPI

/**
 * One pickable thing, flattened for the list UI.
 *
 * Everything the picker needs is resolved once, at index time, so filtering a 3,000-entry list on
 * each keystroke is field reads and one substring test rather than thousands of spec lookups.
 */
class CatalogEntry(
    val id: String,
    val name: String,
    /** Coarse grouping shown in the picker's second column -- hull size, weapon size, item type. */
    val primary: String,
    /** Finer detail shown in the third column -- designation, weapon type, tier. */
    val secondary: String,
    val sourceMod: String,
    val sprite: String = "",
    /** Ordnance point cost where the concept applies (weapons, wings, hullmods), else 0. */
    val opCost: Float = 0f,
) {
    val searchBlob: String = "$name $id $primary $secondary $sourceMod".lowercase()

    /** Matches a whitespace-separated query: every term must appear somewhere in the blob. */
    fun matches(terms: List<String>): Boolean = terms.all { searchBlob.contains(it) }
}

/** The picker categories. Each maps to one lazily-built list in [Catalog]. */
enum class CatalogKind {
    HULL,
    WEAPON,
    FIGHTER,
    HULLMOD,
    DMOD,
    COMMODITY,
    SPECIAL_ITEM,
    ABILITY,
}

/**
 * The catalogue of everything the current mod list defines, in the shape the editor's pickers want.
 *
 * Built lazily per kind and cached for the process. Specs do not change after load, and the editor
 * runs at the main menu where nothing else is competing for time, so there is no invalidation story
 * beyond [invalidate] for completeness.
 *
 * Everything here is read-only spec inspection. Nothing in this file touches a sector, which is what
 * lets the whole editor run from the title screen.
 */
object Catalog {

    private const val VANILLA = "Vanilla"
    private const val UNSPECIFIED = "-"

    private val cache = HashMap<CatalogKind, List<CatalogEntry>>()
    private val byId = HashMap<CatalogKind, Map<String, CatalogEntry>>()

    fun entries(kind: CatalogKind): List<CatalogEntry> = cache.getOrPut(kind) {
        val started = System.nanoTime()
        val built = when (kind) {
            CatalogKind.HULL -> buildHulls()
            CatalogKind.WEAPON -> buildWeapons()
            CatalogKind.FIGHTER -> buildFighters()
            CatalogKind.HULLMOD -> buildHullMods(dMods = false)
            CatalogKind.DMOD -> buildHullMods(dMods = true)
            CatalogKind.COMMODITY -> buildCommodities()
            CatalogKind.SPECIAL_ITEM -> buildSpecialItems()
            CatalogKind.ABILITY -> buildAbilities()
        }
        Global.getLogger(Catalog::class.java).info(
            "StarterPack: indexed ${built.size} $kind entries in ${(System.nanoTime() - started) / 1_000_000} ms"
        )
        built
    }

    fun lookup(kind: CatalogKind, id: String): CatalogEntry? =
        byId.getOrPut(kind) { entries(kind).associateBy { it.id } }[id]

    /** The display name for an id, falling back to the raw id so unknown ids stay visible, not blank. */
    fun nameOf(kind: CatalogKind, id: String): String = lookup(kind, id)?.name ?: id

    fun invalidate() {
        cache.clear()
        byId.clear()
    }

    // --- Builders ------------------------------------------------------------------------------

    /**
     * Ownable hulls only.
     *
     * Auto-generated `(D)` hulls are excluded because they are not independently pickable: D-mods on
     * a template swap the hull to its D variant at apply time, exactly as combat damage does, so
     * offering `onslaught_D` alongside `onslaught` would be two paths to one outcome and the first
     * would silently ignore whatever D-mods you chose. Stations, modules and fighter hulls are
     * excluded because they cannot be a fleet member.
     */
    private fun buildHulls(): List<CatalogEntry> {
        val out = ArrayList<CatalogEntry>()
        for (spec in Global.getSettings().allShipHullSpecs.orEmpty()) {
            if (spec == null) continue
            if (spec.safeBool { isDefaultDHull }) continue
            if (!isOwnableHull(spec)) continue
            out += CatalogEntry(
                id = spec.hullId,
                name = spec.hullName.orEmpty().ifBlank { spec.hullId },
                primary = sizeLabel(spec.hullSize),
                secondary = spec.designation.clean(),
                sourceMod = modName(spec.safeGet { sourceMod }),
                sprite = spec.spriteName.orEmpty(),
                opCost = spec.safeFloat { getOrdnancePoints(null).toFloat() },
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /**
     * Weapons you could actually mount.
     *
     * Built-in, decorative, system and launch-bay "weapons" are filtered out: they are hull furniture
     * the engine mounts itself, and putting one in a normal slot produces a ship that either looks
     * broken or refuses to build.
     */
    private fun buildWeapons(): List<CatalogEntry> {
        val out = ArrayList<CatalogEntry>()
        for (spec in Global.getSettings().allWeaponSpecs.orEmpty()) {
            if (spec == null) continue
            if (spec.type in NON_MOUNTABLE_WEAPON_TYPES) continue
            out += CatalogEntry(
                id = spec.weaponId,
                name = spec.weaponName.orEmpty().ifBlank { spec.weaponId },
                primary = spec.size?.displayName ?: UNSPECIFIED,
                secondary = spec.type?.displayName ?: UNSPECIFIED,
                sourceMod = modName(spec.safeGet { sourceMod }),
                // Turret art is the recognisable view; hardpoint-only weapons fall back to theirs.
                sprite = spec.turretSpriteName.orEmpty().ifBlank { spec.hardpointSpriteName.orEmpty() },
                opCost = spec.safeFloat { getOrdnancePointCost(null) },
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun buildFighters(): List<CatalogEntry> {
        val out = ArrayList<CatalogEntry>()
        for (spec in Global.getSettings().allFighterWingSpecs.orEmpty()) {
            if (spec == null) continue
            out += CatalogEntry(
                id = spec.id,
                name = spec.wingName.orEmpty().ifBlank { spec.id },
                primary = roleLabel(spec),
                secondary = "Tier ${spec.tier}",
                sourceMod = modName(spec.safeGet { sourceMod }),
                // A wing has no art of its own; its single fighter's hull carries the sprite.
                sprite = spec.safeGet { variant?.hullSpec?.spriteName }.orEmpty(),
                opCost = spec.safeFloat { getOpCost(null) },
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /**
     * Hullmods, split into the two lists the editor treats separately.
     *
     * D-mods are their own picker because they are not a subset of "hullmods you might want" -- they
     * are damage, they go on through a different code path at apply time, and mixing them into the
     * regular list would mean scrolling past forty ways to make your ship worse.
     *
     * Hidden mods are dropped: `isHiddenEverywhere` marks scaffolding the game installs itself
     * (module tracking, story-mission markers), and putting one on a ship by hand ranges from inert
     * to save-breaking.
     */
    private fun buildHullMods(dMods: Boolean): List<CatalogEntry> {
        val out = ArrayList<CatalogEntry>()
        for (spec in Global.getSettings().allHullModSpecs.orEmpty()) {
            if (spec == null) continue
            if (spec.safeBool { isHiddenEverywhere }) continue
            if (spec.safeBool { hasTag(Tags.HULLMOD_DMOD) } != dMods) continue
            out += CatalogEntry(
                id = spec.id,
                name = spec.displayName.orEmpty().ifBlank { spec.id },
                primary = if (dMods) "D-mod" else "Tier ${spec.tier}",
                secondary = if (canBuildIn(spec)) "S-moddable" else "Cannot be built in",
                sourceMod = modName(spec.safeGet { sourceMod }),
                sprite = spec.spriteName.orEmpty(),
                // Cost is per hull size; the editor recomputes it against the actual hull.
                opCost = spec.safeFloat { getCostFor(ShipAPI.HullSize.CRUISER).toFloat() },
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** Whether a hullmod can be made permanent with a story point (and so appear in the S-mod list). */
    fun canBuildIn(spec: HullModSpecAPI): Boolean =
        !spec.safeBool { hasTag(Tags.HULLMOD_NO_BUILD_IN) } && !spec.safeBool { hasTag(Tags.HULLMOD_DMOD) }

    /**
     * Commodities you can hold.
     *
     * Meta commodities (`ships`, `blueprints`, `credits`) are accounting rows the economy uses to talk
     * to itself, not cargo -- adding one to the hold produces a stack the game cannot render or price.
     */
    private fun buildCommodities(): List<CatalogEntry> {
        val out = ArrayList<CatalogEntry>()
        for (spec in Global.getSettings().allCommoditySpecs.orEmpty()) {
            if (spec == null) continue
            if (spec.safeBool { isMeta }) continue
            out += CatalogEntry(
                id = spec.id,
                name = spec.name.orEmpty().ifBlank { spec.id },
                primary = if (spec.safeBool { isNonEcon }) "Non-economic" else "Standard",
                secondary = spec.demandClass.clean(),
                sourceMod = modName(spec.safeGet { sourceMod }),
                sprite = spec.iconName.orEmpty(),
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /**
     * Special items.
     *
     * Mission items are dropped rather than merely sorted low: scripted missions hand those over
     * directly and check for them by identity, so starting with one can only break the quest that
     * needs it.
     */
    private fun buildSpecialItems(): List<CatalogEntry> {
        val out = ArrayList<CatalogEntry>()
        for (spec in Global.getSettings().allSpecialItemSpecs.orEmpty()) {
            if (spec == null) continue
            if (spec.safeBool { hasTag(Tags.MISSION_ITEM) }) continue
            out += CatalogEntry(
                id = spec.id,
                name = spec.name.orEmpty().ifBlank { spec.id },
                primary = itemType(spec),
                secondary = spec.manufacturer.clean(),
                sourceMod = modName(spec.safeGet { sourceMod }),
                sprite = spec.iconName.orEmpty(),
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /**
     * Campaign abilities, read straight from the merged `abilities.csv`.
     *
     * There is no `getAllAbilitySpecs()` on [com.fs.starfarer.api.SettingsAPI], so the spreadsheet is
     * the only complete list -- and reading it merged means modded abilities show up without us
     * knowing anything about the mods that added them. Rows with a blank id are the comment lines
     * vanilla uses as section headers.
     */
    private fun buildAbilities(): List<CatalogEntry> {
        val out = ArrayList<CatalogEntry>()
        runCatching {
            val rows = Global.getSettings().getMergedSpreadsheetData("id", "data/campaign/abilities.csv")
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val id = row.optString("id", "").trim()
                if (id.isEmpty()) continue
                out += CatalogEntry(
                    id = id,
                    name = row.optString("name", "").trim().ifBlank { id },
                    primary = row.optString("type", "").trim().ifBlank { UNSPECIFIED },
                    secondary = row.optString("desc", "").trim(),
                    sourceMod = VANILLA,          // the merged sheet does not carry a source column
                    sprite = row.optString("icon", "").trim(),
                )
            }
        }.onFailure {
            Global.getLogger(Catalog::class.java)
                .error("StarterPack: could not read abilities.csv; the hotbar picker will be empty.", it)
        }
        return out.sortedBy { it.name.lowercase() }
    }

    // --- Hull introspection --------------------------------------------------------------------

    /**
     * The weapon slots a player can actually fit something into.
     *
     * Built-in slots already hold a fixed weapon and cannot be changed; decorative, system and
     * station-module slots are not weapons at all. Returned in the hull's own declaration order,
     * which is the order the refit screen shows and so the order the editor should.
     */
    fun fittableSlots(hullId: String): List<WeaponSlotAPI> {
        val spec = hullSpec(hullId) ?: return emptyList()
        return spec.allWeaponSlotsCopy.orEmpty().filterNotNull().filter { slot ->
            slot.safeBool { isWeaponSlot } &&
                !slot.safeBool { isBuiltIn } &&
                !slot.safeBool { isDecorative } &&
                !slot.safeBool { isSystemSlot } &&
                !slot.safeBool { isStationModule } &&
                !slot.safeBool { isHidden }
        }
    }

    /**
     * How many fighter bays the player can fill.
     *
     * Built-in wings occupy their bay permanently, so they are subtracted -- a Legion XIV has bays the
     * refit screen will not let you touch, and offering them would produce a variant the game
     * silently rewrites.
     */
    fun fittableBayCount(hullId: String): Int {
        val spec = hullSpec(hullId) ?: return 0
        val total = spec.safeInt { fighterBays }
        val builtIn = spec.safeGet { builtInWings }?.size ?: 0
        return (total - builtIn).coerceAtLeast(0)
    }

    fun hullSpec(hullId: String): ShipHullSpecAPI? =
        if (hullId.isBlank()) null else runCatching { Global.getSettings().getHullSpec(hullId) }.getOrNull()

    fun weaponSpec(weaponId: String): WeaponSpecAPI? =
        if (weaponId.isBlank()) null else runCatching { Global.getSettings().getWeaponSpec(weaponId) }.getOrNull()

    fun wingSpec(wingId: String): FighterWingSpecAPI? =
        if (wingId.isBlank()) null else runCatching { Global.getSettings().getFighterWingSpec(wingId) }.getOrNull()

    fun hullModSpec(modId: String): HullModSpecAPI? =
        if (modId.isBlank()) null else runCatching { Global.getSettings().getHullModSpec(modId) }.getOrNull()

    /**
     * Weapons that fit a given slot, in catalogue order.
     *
     * Delegates the actual test to the engine's own `weaponFits`, which knows the size and mount-type
     * rules including whatever a mod has changed about them. Filtering here rather than showing
     * everything and rejecting on click is what makes the picker usable: a large ballistic hardpoint
     * has maybe forty candidates out of eighteen hundred weapons.
     */
    fun weaponsFitting(slot: WeaponSlotAPI?): List<CatalogEntry> {
        val all = entries(CatalogKind.WEAPON)
        if (slot == null) return all
        return all.filter { entry ->
            val spec = weaponSpec(entry.id) ?: return@filter false
            runCatching { slot.weaponFits(spec) }.getOrDefault(false)
        }
    }

    /** Total ordnance points on a hull, before any skill bonuses (there is no character at the menu). */
    fun baseOrdnancePoints(hullId: String): Int =
        hullSpec(hullId)?.safeInt { getOrdnancePoints(null) } ?: 0

    /** A hullmod's OP cost on a specific hull size -- costs are per size, so the hull matters. */
    fun hullModCost(modId: String, size: ShipAPI.HullSize?): Int {
        val spec = hullModSpec(modId) ?: return 0
        return spec.safeInt { getCostFor(size ?: ShipAPI.HullSize.FRIGATE) }
    }

    // --- Labels --------------------------------------------------------------------------------

    private fun isOwnableHull(spec: ShipHullSpecAPI): Boolean {
        val hints = spec.hints ?: return true
        if (hints.contains(ShipHullSpecAPI.ShipTypeHints.STATION)) return false
        if (hints.contains(ShipHullSpecAPI.ShipTypeHints.MODULE)) return false
        if (hints.contains(ShipHullSpecAPI.ShipTypeHints.UNDER_PARENT)) return false
        if (spec.hullSize == ShipAPI.HullSize.FIGHTER) return false
        return true
    }

    private val NON_MOUNTABLE_WEAPON_TYPES = setOf(
        WeaponAPI.WeaponType.BUILT_IN,
        WeaponAPI.WeaponType.DECORATIVE,
        WeaponAPI.WeaponType.SYSTEM,
        WeaponAPI.WeaponType.STATION_MODULE,
        WeaponAPI.WeaponType.LAUNCH_BAY,
    )

    fun sizeLabel(size: ShipAPI.HullSize?): String = when (size) {
        ShipAPI.HullSize.FRIGATE -> "Frigate"
        ShipAPI.HullSize.DESTROYER -> "Destroyer"
        ShipAPI.HullSize.CRUISER -> "Cruiser"
        ShipAPI.HullSize.CAPITAL_SHIP -> "Capital"
        ShipAPI.HullSize.FIGHTER -> "Fighter"
        else -> UNSPECIFIED
    }

    private fun roleLabel(spec: FighterWingSpecAPI): String =
        spec.safeGet { role?.name }?.lowercase()?.replaceFirstChar { it.uppercase() } ?: UNSPECIFIED

    private fun itemType(spec: SpecialItemSpecAPI): String {
        fun has(tag: String) = spec.safeBool { hasTag(tag) }
        return when {
            has("colony_item") -> "Colony item"
            has("package_bp") -> "Blueprint package"
            has("single_bp") -> "Blueprint (any)"
            has("modspec") -> "Hullmod spec"
            has("ai_core") -> "AI core"
            else -> UNSPECIFIED
        }
    }

    private fun modName(mod: ModSpecAPI?): String = mod?.name?.trim().orEmpty().ifBlank { VANILLA }

    private fun String?.clean(): String = this?.trim().orEmpty().ifBlank { UNSPECIFIED }
}

// --- Spec-access helpers -------------------------------------------------------------------------
//
// Modded specs are not always complete, and a single mod that returns null from a getter the API
// declares non-null would otherwise take the whole catalogue build down. Every optional read goes
// through one of these, so a bad spec costs one blank field rather than an empty picker.

internal inline fun <T, R> T.safeGet(block: T.() -> R?): R? = runCatching { block() }.getOrNull()
internal inline fun <T> T.safeBool(block: T.() -> Boolean): Boolean = runCatching { block() }.getOrDefault(false)
internal inline fun <T> T.safeInt(block: T.() -> Int): Int = runCatching { block() }.getOrDefault(0)
internal inline fun <T> T.safeFloat(block: T.() -> Float): Float = runCatching { block() }.getOrDefault(0f)
