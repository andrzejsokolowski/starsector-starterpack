package starterpack.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * A running campaign, saved so a later new game can start from it: the "new game plus" half of the
 * mod, kept apart from the hand-built [Template]s on purpose.
 *
 * ## What it is made of
 *
 * The parts a template already knows how to stamp -- ships with their loadouts, cargo, credits,
 * faction standings and the hotbar -- live in [pack], so the applier that builds a template's fleet
 * builds a snapshot's fleet the same way. Everything a template has no notion of sits beside it: the
 * character's exact level, experience and skills, the officers and AI cores captaining the ships,
 * which ships were mothballed, and the blueprints and hull mods the player had learned.
 *
 * ## Exact, not "on top"
 *
 * A template *grants* skill points and story points on top of whatever the new game hands out. A
 * snapshot is a copy of a moment, so [character] is applied as exact values instead, and [pack]
 * carries no grants of its own.
 *
 * ## What is left out
 *
 * Anything that belongs to the old sector rather than to the player: colonies, storage at markets,
 * contacts, quests, commissions. Ship damage and combat readiness are not kept either; a new game
 * starts with a repaired fleet. The player's name and portrait are the ones the new game was started
 * with, since that is chosen right before the snapshot is laid over it.
 */
class Snapshot(
    var name: String = "Snapshot",
    /** The in-game date it was taken, as the campaign clock writes it. */
    var takenOn: String = "",
    /** The real-world date it was taken, `YYYY-MM-DD`. */
    var savedOn: String = "",
    /** The player character's name at the time, so a list of snapshots says whose they are. */
    var captain: String = "",

    /** Fleet, cargo, credits, standings and hotbar, applied through the template applier. */
    val pack: Template = Template(),
    /** Indices into [Template.ships] of the ships that were mothballed in the fleet. */
    val mothballed: MutableSet<Int> = LinkedHashSet(),
    /** The player character's level, experience, points and skills, or null to leave them alone. */
    var character: CharacterState? = null,
    /** Officers, and the AI cores that captained ships. */
    val officers: MutableList<OfficerEntry> = ArrayList(),
    /** Ship, weapon and fighter blueprints and hull mods the player knew. */
    var blueprints: Blueprints = Blueprints(),
) {

    /** A one-line summary for list rows and the console. */
    fun summary(): String {
        val parts = ArrayList<String>(6)
        val ships = pack.ships.size
        if (ships > 0) parts += "$ships ship${if (ships == 1) "" else "s"}"
        val people = officers.count { it.aiCoreId.isBlank() }
        if (people > 0) parts += "$people officer${if (people == 1) "" else "s"}"
        val cores = officers.size - people
        if (cores > 0) parts += "$cores AI core captain${if (cores == 1) "" else "s"}"
        if (!pack.cargo.isEmpty()) parts += "${pack.cargo.stackCount()} cargo stacks"
        pack.credits?.let { parts += "${formatCredits(it)} credits" }
        character?.let { parts += "level ${it.level}" }
        return if (parts.isEmpty()) "empty" else parts.joinToString(", ")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put(Template.KEY_VERSION, FORMAT_VERSION)
        put("name", name)
        put("takenOn", takenOn)
        put("savedOn", savedOn)
        put("captain", captain)
        put("pack", pack.toJson())
        if (mothballed.isNotEmpty()) put("mothballed", JSONArray(mothballed.toList()))
        character?.let { put("character", it.toJson()) }
        if (officers.isNotEmpty()) {
            put("officers", JSONArray().also { array -> officers.forEach { array.put(it.toJson()) } })
        }
        put("blueprints", blueprints.toJson())
    }

    companion object {
        const val FORMAT_VERSION = 1

        fun fromJson(json: JSONObject): Snapshot = Snapshot(
            name = json.optString("name", "Snapshot"),
            takenOn = json.optString("takenOn", ""),
            savedOn = json.optString("savedOn", ""),
            captain = json.optString("captain", ""),
            pack = json.optJSONObject("pack")?.let { Template.fromJson(it) } ?: Template(),
            mothballed = json.optJSONArray("mothballed").toIntSet(),
            character = json.optJSONObject("character")?.let { CharacterState.fromJson(it) },
            officers = json.optJSONArray("officers").mapObjects { OfficerEntry.fromJson(it) },
            blueprints = json.optJSONObject("blueprints")?.let { Blueprints.fromJson(it) } ?: Blueprints(),
        )

        /** 1234567 as "1,234,567", whatever the PC's language: the game's fonts lack other separators. */
        fun formatCredits(credits: Long): String = String.format(Locale.US, "%,d", credits)
    }
}

/**
 * The player character as it was: level and experience, unspent and story points, and every skill
 * with its level (2 is elite).
 */
class CharacterState(
    var level: Int = 1,
    var xp: Long = 0L,
    /** Story point bonus experience still waiting to be earned, and the part held back for later. */
    var bonusXp: Long = 0L,
    var deferredBonusXp: Long = 0L,
    /** Unspent skill points. */
    var skillPoints: Int = 0,
    var storyPoints: Int = 0,
    val skills: MutableMap<String, Float> = LinkedHashMap(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("level", level)
        put("xp", xp)
        put("bonusXp", bonusXp)
        put("deferredBonusXp", deferredBonusXp)
        put("skillPoints", skillPoints)
        put("storyPoints", storyPoints)
        put("skills", skills.toJsonObject())
    }

    companion object {
        fun fromJson(json: JSONObject): CharacterState = CharacterState(
            level = json.optInt("level", 1).coerceAtLeast(1),
            xp = json.optLong("xp", 0L).coerceAtLeast(0L),
            bonusXp = json.optLong("bonusXp", 0L).coerceAtLeast(0L),
            deferredBonusXp = json.optLong("deferredBonusXp", 0L).coerceAtLeast(0L),
            skillPoints = json.optInt("skillPoints", 0).coerceAtLeast(0),
            storyPoints = json.optInt("storyPoints", 0).coerceAtLeast(0),
            skills = json.optJSONObject("skills").toFloatMap(),
        )
    }
}

/**
 * One officer, or one AI core captaining a ship.
 *
 * An AI core ([aiCoreId] set) is rebuilt through the game's own AI core officer plugin, so it gets
 * whatever the core normally carries, and then has the captured level and skills laid over it -- an
 * integrated core keeps its extra level that way.
 */
class OfficerEntry(
    var firstName: String = "",
    var lastName: String = "",
    /** `MALE`, `FEMALE` or `ANY`, as the game's `FullName.Gender` names them. */
    var gender: String = "ANY",
    var portrait: String = "",
    /** `timid`, `cautious`, `steady`, `aggressive` or `reckless`. */
    var personality: String = "steady",
    var level: Int = 1,
    var xp: Long = 0L,
    val skills: MutableMap<String, Float> = LinkedHashMap(),
    /** Index into the snapshot's ships of the ship this officer captains, or -1 for none. */
    var ship: Int = -1,
    /** The AI core commodity id when this captain is an AI core, otherwise blank. */
    var aiCoreId: String = "",
    /** Officer Training mentored this officer, so they level further than usual. */
    var mentored: Boolean = false,
    /** An exceptional officer from a cryopod, past the usual level and elite limits. */
    var exceptional: Boolean = false,
    /** The captain cannot be taken off the ship, as with an integrated AI core. */
    var unremovable: Boolean = false,
) {
    fun displayName(): String = "$firstName $lastName".trim().ifBlank { aiCoreId.ifBlank { "Officer" } }

    fun toJson(): JSONObject = JSONObject().apply {
        put("firstName", firstName)
        put("lastName", lastName)
        put("gender", gender)
        put("portrait", portrait)
        put("personality", personality)
        put("level", level)
        put("xp", xp)
        put("skills", skills.toJsonObject())
        put("ship", ship)
        if (aiCoreId.isNotBlank()) put("aiCoreId", aiCoreId)
        if (mentored) put("mentored", true)
        if (exceptional) put("exceptional", true)
        if (unremovable) put("unremovable", true)
    }

    companion object {
        fun fromJson(json: JSONObject): OfficerEntry = OfficerEntry(
            firstName = json.optString("firstName", ""),
            lastName = json.optString("lastName", ""),
            gender = json.optString("gender", "ANY"),
            portrait = json.optString("portrait", ""),
            personality = json.optString("personality", "steady"),
            level = json.optInt("level", 1).coerceAtLeast(1),
            xp = json.optLong("xp", 0L).coerceAtLeast(0L),
            skills = json.optJSONObject("skills").toFloatMap(),
            ship = json.optInt("ship", -1),
            aiCoreId = json.optString("aiCoreId", "").trim(),
            mentored = json.optBoolean("mentored", false),
            exceptional = json.optBoolean("exceptional", false),
            unremovable = json.optBoolean("unremovable", false),
        )
    }
}

/**
 * What the player knew: blueprints and hull mods. Applying only ever adds to what the new game
 * already knows, it never forgets anything.
 */
class Blueprints(
    val ships: MutableList<String> = ArrayList(),
    val fighters: MutableList<String> = ArrayList(),
    val weapons: MutableList<String> = ArrayList(),
    /** Hull mods the character has learned, from modspecs or from the start. */
    val hullMods: MutableList<String> = ArrayList(),
) {
    fun count(): Int = ships.size + fighters.size + weapons.size + hullMods.size

    fun toJson(): JSONObject = JSONObject().apply {
        putIdsIfAny("ships", ships)
        putIdsIfAny("fighters", fighters)
        putIdsIfAny("weapons", weapons)
        putIdsIfAny("hullMods", hullMods)
    }

    companion object {
        fun fromJson(json: JSONObject): Blueprints = Blueprints(
            ships = json.optJSONArray("ships").toIdList(),
            fighters = json.optJSONArray("fighters").toIdList(),
            weapons = json.optJSONArray("weapons").toIdList(),
            hullMods = json.optJSONArray("hullMods").toIdList(),
        )
    }
}

private fun Map<String, Float>.toJsonObject(): JSONObject =
    JSONObject().also { obj -> forEach { (id, value) -> obj.put(id, value.toDouble()) } }

private fun JSONArray?.toIntSet(): MutableSet<Int> {
    val out = LinkedHashSet<Int>()
    val array = this ?: return out
    for (i in 0 until array.length()) {
        if (array.isNull(i)) continue
        val value = array.optInt(i, -1)
        if (value >= 0) out += value
    }
    return out
}
