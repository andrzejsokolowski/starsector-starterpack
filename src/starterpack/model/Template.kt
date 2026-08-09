package starterpack.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * The saved shape of a starter pack: everything the applier will stamp onto a fresh campaign.
 *
 * ## Everything is stored as plain ids
 *
 * No spec is ever resolved here. A template written while a mod was enabled survives that mod being
 * turned off -- the ids it names simply stop matching, the applier reports them as warnings, and they
 * start working again the moment the mod comes back. Resolving at save time would instead bake in a
 * hard failure, or silently drop the entry.
 *
 * ## Absent means "don't touch"
 *
 * A field left at its default is a field the applier skips, not a field it zeroes. That is what makes
 * a template that only sets credits safe to apply -- it will not also wipe your fleet. The
 * [replaceFleet] / [replaceCargo] flags are the explicit opt-in for the destructive behaviour, and
 * both default to true because "overwrite what you have" is the whole point of the mod.
 *
 * ## JSON round-trips through defaults, never through exceptions
 *
 * Every read goes through `opt*`, so a hand-edited file missing half its keys loads as a valid
 * template rather than throwing. People are expected to edit these by hand; a template that refuses
 * to load because a key is missing would be worse than one that loads with a sensible blank.
 */
class Template(
    var name: String = "New template",

    // --- Fleet -------------------------------------------------------------------------------
    /** Ships to give the player, in fleet order. The first one flagged [ShipEntry.flagship] flies. */
    val ships: MutableList<ShipEntry> = ArrayList(),
    /** Ships to hand over mothballed (they land in cargo, not the active fleet). */
    val mothballed: MutableList<ShipEntry> = ArrayList(),
    /** Disband the player's existing fleet before adding ours. */
    var replaceFleet: Boolean = true,

    // --- Cargo -------------------------------------------------------------------------------
    var cargo: CargoSpec = CargoSpec(),
    /** Empty the player's hold before adding ours. */
    var replaceCargo: Boolean = true,

    // --- Character ---------------------------------------------------------------------------
    /** Absolute credit balance to set, or null to leave the player's money alone. */
    var credits: Long? = null,
    /** Unspent skill points to grant on top of whatever the player already has. */
    var skillPoints: Int = 0,
    /** Story points to grant on top of whatever the player already has. */
    var storyPoints: Int = 0,
    /** Character level to set, or null to leave it alone. Levelling does not itself grant points. */
    var level: Int? = null,

    // --- Hotbar ------------------------------------------------------------------------------
    var hotbar: Hotbar = Hotbar(),
) {

    fun copy(newName: String = name): Template = fromJson(toJson()).also { it.name = newName }

    /** A one-line summary for list rows and the console's `list` output. */
    fun summary(): String {
        val parts = ArrayList<String>(5)
        if (ships.isNotEmpty()) parts += "${ships.size} ship${if (ships.size == 1) "" else "s"}"
        if (mothballed.isNotEmpty()) parts += "${mothballed.size} mothballed"
        if (!cargo.isEmpty()) parts += "${cargo.stackCount()} cargo stacks"
        credits?.let { parts += "${it}cr" }
        if (skillPoints > 0) parts += "${skillPoints}sp"
        if (storyPoints > 0) parts += "${storyPoints}story"
        if (hotbar.assignedCount() > 0) parts += "${hotbar.assignedCount()} hotbar slots"
        return if (parts.isEmpty()) "empty" else parts.joinToString(", ")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_VERSION, FORMAT_VERSION)
        put("name", name)
        put("ships", JSONArray().also { array -> ships.forEach { array.put(it.toJson()) } })
        put("mothballed", JSONArray().also { array -> mothballed.forEach { array.put(it.toJson()) } })
        put("replaceFleet", replaceFleet)
        put("cargo", cargo.toJson())
        put("replaceCargo", replaceCargo)
        credits?.let { put("credits", it) }
        put("skillPoints", skillPoints)
        put("storyPoints", storyPoints)
        level?.let { put("level", it) }
        put("hotbar", hotbar.toJson())
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val KEY_VERSION = "formatVersion"

        fun fromJson(json: JSONObject): Template = Template(
            name = json.optString("name", "Unnamed"),
            ships = json.optJSONArray("ships").mapObjects { ShipEntry.fromJson(it) },
            mothballed = json.optJSONArray("mothballed").mapObjects { ShipEntry.fromJson(it) },
            replaceFleet = json.optBoolean("replaceFleet", true),
            cargo = json.optJSONObject("cargo")?.let { CargoSpec.fromJson(it) } ?: CargoSpec(),
            replaceCargo = json.optBoolean("replaceCargo", true),
            credits = if (json.has("credits")) json.optLong("credits") else null,
            skillPoints = json.optInt("skillPoints", 0),
            storyPoints = json.optInt("storyPoints", 0),
            level = if (json.has("level")) json.optInt("level") else null,
            hotbar = json.optJSONObject("hotbar")?.let { Hotbar.fromJson(it) } ?: Hotbar(),
        )
    }
}

/**
 * One ship and its complete refit.
 *
 * [baseVariant] is a convenience, not a dependency: picking a stock variant in the editor copies its
 * loadout into the fields below and is then forgotten. The applier never reads it, so a template
 * cannot break when a mod renames a variant.
 */
class ShipEntry(
    var hullId: String = "",
    /** Custom ship name, or blank to let the game roll one. */
    var shipName: String = "",
    /** Only the first flagged entry is honoured; the applier ignores the rest. */
    var flagship: Boolean = false,

    var vents: Int = 0,
    var capacitors: Int = 0,

    /** Regular hullmods -- removable in refit, cost OP. */
    val hullMods: MutableList<String> = ArrayList(),
    /** Built-in permanently at no OP cost and marked as an S-mod (counts against the S-mod limit). */
    val sMods: MutableList<String> = ArrayList(),
    /** Built in permanently WITHOUT counting as an S-mod -- the "free built-in" case. */
    val permaMods: MutableList<String> = ArrayList(),
    /** D-mods. Adding any of these also swaps the hull to its (D) variant, as combat damage does. */
    val dMods: MutableList<String> = ArrayList(),

    /** Weapon per slot id, e.g. `WS0001` -> `hellbore`. Slots not named here are left empty. */
    val weapons: MutableMap<String, String> = LinkedHashMap(),
    /** Fighter wing per bay index. A null entry leaves that bay empty. */
    val wings: MutableList<String?> = ArrayList(),

    /**
     * Weapon groups, or empty to let the game auto-generate them. Auto-generation is the default
     * because hand-maintaining groups through weapon changes is tedious and the game's grouping is
     * usually what you wanted anyway.
     */
    val weaponGroups: MutableList<WeaponGroup> = ArrayList(),

    /** Combat readiness as a 0..1 fraction, or null to leave the game's default (usually full). */
    var combatReadiness: Float? = null,
) {

    fun copy(): ShipEntry = fromJson(toJson())

    fun toJson(): JSONObject = JSONObject().apply {
        put("hull", hullId)
        if (shipName.isNotBlank()) put("shipName", shipName)
        if (flagship) put("flagship", true)
        put("vents", vents)
        put("capacitors", capacitors)
        putIdsIfAny("hullMods", hullMods)
        putIdsIfAny("sMods", sMods)
        putIdsIfAny("permaMods", permaMods)
        putIdsIfAny("dMods", dMods)
        if (weapons.isNotEmpty()) {
            put("weapons", JSONObject().also { obj -> weapons.forEach { (slot, id) -> obj.put(slot, id) } })
        }
        if (wings.any { it != null }) {
            // JSONObject.NULL rather than a skipped entry: bay position is meaningful, so an empty
            // bay 0 with a filled bay 1 has to survive the round trip as exactly that.
            put("wings", JSONArray().also { array -> wings.forEach { array.put(it ?: JSONObject.NULL) } })
        }
        if (weaponGroups.isNotEmpty()) {
            put("weaponGroups", JSONArray().also { array -> weaponGroups.forEach { array.put(it.toJson()) } })
        }
        combatReadiness?.let { put("cr", it.toDouble()) }
    }

    companion object {
        fun fromJson(json: JSONObject): ShipEntry = ShipEntry(
            hullId = json.optString("hull", ""),
            shipName = json.optString("shipName", ""),
            flagship = json.optBoolean("flagship", false),
            vents = json.optInt("vents", 0),
            capacitors = json.optInt("capacitors", 0),
            hullMods = json.optJSONArray("hullMods").toIdList(),
            sMods = json.optJSONArray("sMods").toIdList(),
            permaMods = json.optJSONArray("permaMods").toIdList(),
            dMods = json.optJSONArray("dMods").toIdList(),
            weapons = json.optJSONObject("weapons").toStringMap(),
            wings = json.optJSONArray("wings").toNullableIdList(),
            weaponGroups = json.optJSONArray("weaponGroups").mapObjects { WeaponGroup.fromJson(it) },
            combatReadiness = if (json.has("cr")) json.optDouble("cr", 1.0).toFloat() else null,
        )
    }
}

/** One weapon group: which slots are in it and whether it starts in autofire. */
class WeaponGroup(
    var autofire: Boolean = false,
    val slots: MutableList<String> = ArrayList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("autofire", autofire)
        put("slots", JSONArray(slots))
    }

    companion object {
        fun fromJson(json: JSONObject): WeaponGroup = WeaponGroup(
            autofire = json.optBoolean("autofire", false),
            slots = json.optJSONArray("slots").toIdList(),
        )
    }
}

/**
 * What lands in the hold.
 *
 * Kept as five separate maps rather than one tagged list because the game's cargo API takes each
 * kind through a different call, and because the editor shows them as separate sections anyway.
 * Quantities are floats -- fuel and supplies are genuinely fractional in Starsector.
 */
class CargoSpec(
    /** Commodity id -> units. Covers supplies, fuel, crew, marines, metals, everything on the market. */
    val commodities: MutableMap<String, Float> = LinkedHashMap(),
    /** Weapon id -> count of loose weapons in the hold. */
    val weapons: MutableMap<String, Int> = LinkedHashMap(),
    /** Fighter wing id -> count of loose LPCs in the hold. */
    val fighters: MutableMap<String, Int> = LinkedHashMap(),
    /** Hullmod id -> count of `modspec` items, i.e. the hullmod blueprints you install to learn one. */
    val hullModSpecs: MutableMap<String, Int> = LinkedHashMap(),
    /** Special items: AI cores, colony items, blueprints. */
    val specials: MutableList<SpecialItem> = ArrayList(),
) {
    fun isEmpty(): Boolean = stackCount() == 0

    fun stackCount(): Int =
        commodities.size + weapons.size + fighters.size + hullModSpecs.size + specials.size

    fun toJson(): JSONObject = JSONObject().apply {
        if (commodities.isNotEmpty()) {
            put("commodities", JSONObject().also { obj ->
                commodities.forEach { (id, qty) -> obj.put(id, qty.toDouble()) }
            })
        }
        if (weapons.isNotEmpty()) {
            put("weapons", JSONObject().also { obj -> weapons.forEach { (id, n) -> obj.put(id, n) } })
        }
        if (fighters.isNotEmpty()) {
            put("fighters", JSONObject().also { obj -> fighters.forEach { (id, n) -> obj.put(id, n) } })
        }
        if (hullModSpecs.isNotEmpty()) {
            put("hullModSpecs", JSONObject().also { obj -> hullModSpecs.forEach { (id, n) -> obj.put(id, n) } })
        }
        if (specials.isNotEmpty()) {
            put("specials", JSONArray().also { array -> specials.forEach { array.put(it.toJson()) } })
        }
    }

    companion object {
        fun fromJson(json: JSONObject): CargoSpec = CargoSpec(
            commodities = json.optJSONObject("commodities").toFloatMap(),
            weapons = json.optJSONObject("weapons").toIntMap(),
            fighters = json.optJSONObject("fighters").toIntMap(),
            hullModSpecs = json.optJSONObject("hullModSpecs").toIntMap(),
            specials = json.optJSONArray("specials").mapObjects { SpecialItem.fromJson(it) },
        )
    }
}

/**
 * A special item stack.
 *
 * [data] is the item's sub-id and is not optional decoration: a `ship_bp` with no data is a
 * meaningless item, and `modspec` carries the hullmod id there. Blank means the item takes no data.
 */
class SpecialItem(
    var id: String = "",
    var data: String = "",
    var quantity: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        if (data.isNotBlank()) put("data", data)
        put("quantity", quantity)
    }

    companion object {
        fun fromJson(json: JSONObject): SpecialItem = SpecialItem(
            id = json.optString("id", ""),
            data = json.optString("data", ""),
            quantity = json.optInt("quantity", 1),
        )
    }
}

/**
 * The ability hotbar: five bars of ten slots, matching the engine's `AbilitySlotsAPI`.
 *
 * [granted] is separate from what is in the slots on purpose. Putting an ability in a slot does not
 * teach it to the player -- that is `CharacterDataAPI.addAbility` -- and an ability can legitimately
 * be known but unslotted. The editor grants anything you slot, but the file distinguishes the two so
 * a hand-edited template can do either.
 *
 * Each slot may also carry a distinct in-hyperspace ability, which is how vanilla puts Emergency Burn
 * and Sustained Burn on one key.
 */
class Hotbar(
    /** [BARS] x [SLOTS_PER_BAR] ability ids; null means an empty slot. */
    val slots: Array<Array<String?>> = Array(BARS) { arrayOfNulls<String>(SLOTS_PER_BAR) },
    /** Optional per-slot override used while in hyperspace. Same shape as [slots]. */
    val hyperSlots: Array<Array<String?>> = Array(BARS) { arrayOfNulls<String>(SLOTS_PER_BAR) },
    /** Abilities to teach the player. Anything slotted above is granted whether or not it is listed here. */
    val granted: MutableList<String> = ArrayList(),
    /** Replace the player's existing hotbar rather than only filling empty slots. */
    var replaceExisting: Boolean = true,
) {

    fun assignedCount(): Int = slots.sumOf { bar -> bar.count { it != null } }

    /** Every ability the applier will have to teach: the explicit grants plus everything slotted. */
    fun abilitiesToGrant(): Set<String> {
        val out = LinkedHashSet(granted)
        slots.forEach { bar -> bar.forEach { it?.let(out::add) } }
        hyperSlots.forEach { bar -> bar.forEach { it?.let(out::add) } }
        return out
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("slots", slots.toJsonGrid())
        if (hyperSlots.any { bar -> bar.any { it != null } }) put("hyperSlots", hyperSlots.toJsonGrid())
        if (granted.isNotEmpty()) put("granted", JSONArray(granted))
        put("replaceExisting", replaceExisting)
    }

    private fun Array<Array<String?>>.toJsonGrid(): JSONArray = JSONArray().also { outer ->
        forEach { bar ->
            outer.put(JSONArray().also { inner -> bar.forEach { inner.put(it ?: JSONObject.NULL) } })
        }
    }

    companion object {
        const val BARS = 5
        const val SLOTS_PER_BAR = 10

        fun fromJson(json: JSONObject): Hotbar = Hotbar(
            slots = json.optJSONArray("slots").toGrid(),
            hyperSlots = json.optJSONArray("hyperSlots").toGrid(),
            granted = json.optJSONArray("granted").toIdList(),
            replaceExisting = json.optBoolean("replaceExisting", true),
        )

        /** Reads a grid, clamping to [BARS] x [SLOTS_PER_BAR] so a hand-edited file can't overflow. */
        private fun JSONArray?.toGrid(): Array<Array<String?>> {
            val grid = Array(BARS) { arrayOfNulls<String>(SLOTS_PER_BAR) }
            val outer = this ?: return grid
            for (bar in 0 until minOf(BARS, outer.length())) {
                val inner = outer.optJSONArray(bar) ?: continue
                for (slot in 0 until minOf(SLOTS_PER_BAR, inner.length())) {
                    // Via optIdOrNull, never optString -- see the note there on org.json returning
                    // the string "null" for a JSON null.
                    grid[bar][slot] = inner.optIdOrNull(slot)
                }
            }
            return grid
        }
    }
}

// --- JSON helpers ------------------------------------------------------------------------------
//
// org.json has no generics and returns raw types, so these keep the casts and the null handling in
// one place rather than smeared through every fromJson above.

private fun JSONObject.putIdsIfAny(key: String, ids: List<String>) {
    if (ids.isNotEmpty()) put(key, JSONArray(ids))
}

private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): MutableList<T> {
    val out = ArrayList<T>()
    val array = this ?: return out
    for (i in 0 until array.length()) {
        array.optJSONObject(i)?.let { out += transform(it) }
    }
    return out
}

private fun JSONArray?.toIdList(): MutableList<String> {
    val out = ArrayList<String>()
    val array = this ?: return out
    for (i in 0 until array.length()) {
        // Same null handling as the positional lists: these arrays are only ever written with real
        // ids, but a hand-edited file is free to contain a null and must not turn it into an id.
        array.optIdOrNull(i)?.let { out += it }
    }
    return out
}

/** Like [toIdList] but preserves empty entries as nulls -- for positional lists such as fighter bays. */
private fun JSONArray?.toNullableIdList(): MutableList<String?> {
    val out = ArrayList<String?>()
    val array = this ?: return out
    for (i in 0 until array.length()) {
        out += array.optIdOrNull(i)
    }
    return out
}

/**
 * Reads one possibly-null entry out of a JSON array.
 *
 * **`optString` cannot be used here.** Starsector bundles a version of org.json whose
 * `optString(index, default)` returns the literal four-character string `"null"` for a JSON null
 * rather than the default -- verified against the game's own `json.jar`, for both parsed text and
 * in-memory `JSONObject.NULL`. Positional lists (hotbar slots, fighter bays) are mostly nulls, so
 * every empty slot came back as an ability or wing genuinely named `null`: a nine-ability hotbar
 * loaded as fifty filled slots. `isNull` reports these correctly, so the check goes through that.
 *
 * The literal `"null"` is also rejected on the way in, so templates already written by the broken
 * build heal themselves the first time they are loaded instead of needing to be rebuilt by hand.
 */
private fun JSONArray.optIdOrNull(index: Int): String? {
    if (isNull(index)) return null
    val id = optString(index, "").trim()
    return if (id.isEmpty() || id == "null") null else id
}

private fun JSONObject?.toStringMap(): MutableMap<String, String> {
    val out = LinkedHashMap<String, String>()
    val json = this ?: return out
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next() as String
        val value = json.optString(key, "").trim()
        if (value.isNotEmpty()) out[key] = value
    }
    return out
}

private fun JSONObject?.toIntMap(): MutableMap<String, Int> {
    val out = LinkedHashMap<String, Int>()
    val json = this ?: return out
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next() as String
        out[key] = json.optInt(key, 0)
    }
    return out
}

private fun JSONObject?.toFloatMap(): MutableMap<String, Float> {
    val out = LinkedHashMap<String, Float>()
    val json = this ?: return out
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next() as String
        out[key] = json.optDouble(key, 0.0).toFloat()
    }
    return out
}
