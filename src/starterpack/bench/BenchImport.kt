package starterpack.bench

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.impl.campaign.ids.Tags
import org.json.JSONObject
import starterpack.catalog.Catalog
import starterpack.model.ShipEntry
import starterpack.model.Template
import starterpack.model.WeaponGroup
import java.io.File

/** What a bench trip changed, for reporting in the editor. */
class ImportResult(
    val shipsUpdated: Int = 0,
    val warnings: List<String> = emptyList(),
) {
    val changedAnything: Boolean get() = shipsUpdated > 0

    fun summary(): String = when {
        shipsUpdated == 1 -> "Imported 1 ship from the refit bench."
        shipsUpdated > 1 -> "Imported $shipsUpdated ships from the refit bench."
        warnings.isNotEmpty() -> "Nothing imported from the refit bench."
        else -> "The refit bench had nothing new."
    }
}

/**
 * Reads what the game wrote after a trip to the refit bench back into a template.
 *
 * The file the game leaves behind is a stock variant JSON:
 *
 * ```json
 * { "hullId": "...", "fluxVents": 4, "fluxCapacitors": 0,
 *   "hullMods": [...], "permaMods": [...], "sMods": [...],
 *   "weaponGroups": [ {"autofire": false, "mode": "ALTERNATING", "weapons": {"WS0001": "..."}} ],
 *   "wings": [] }
 * ```
 *
 * Its three hullmod lists nest -- `sMods` is a subset of `permaMods`, which is a subset of
 * `hullMods` -- whereas this mod keeps four disjoint lists, so importing is mostly a matter of
 * subtracting them apart again. See [decomposeHullMods].
 */
object BenchImport {

    /**
     * Imports whatever the bench left behind, then clears it.
     *
     * Called on every return to the main menu, not only after the editor's button was used, so that
     * reaching the bench straight from the Missions menu works too.
     *
     * The files are deleted afterwards -- successes and failures alike. The bench is a transfer
     * buffer, not storage: leaving a file that failed to match would re-warn on every visit to the
     * menu forever, and a file that failed to match is one whose ship the template no longer has,
     * which makes its contents stale anyway.
     */
    fun consumeOnReturn(): ImportResult? {
        val template = BenchState.templateForBench()
        if (LoadoutBench.savedVariants().isEmpty() || template == null) {
            BenchState.clearAwaiting()
            return null
        }

        val result = runCatching { importInto(template) }
            .getOrElse { ImportResult(warnings = listOf("Refit bench import failed: ${it.message}")) }

        if (result.changedAnything) runCatching { starterpack.store.TemplateStore.flush() }
        LoadoutBench.clearSavedVariants()
        BenchState.clearAwaiting()
        BenchState.lastImport = result
        log.info("StarterPack: ${result.summary()}")
        return result
    }

    /**
     * Reads every variant the bench saved into [template].
     *
     * Ships are matched by the fleet index in the filename, which is the order [LoadoutBench] added
     * them. That mapping is an assumption about how the game names these files, so every match is
     * checked against the hull it claims to be and a mismatch is refused rather than written -- a
     * wrong import would silently overwrite a loadout, which is worse than not importing at all.
     */
    fun importInto(template: Template): ImportResult {
        val files = LoadoutBench.savedVariants()
        if (files.isEmpty()) return ImportResult()

        val warnings = ArrayList<String>()
        var updated = 0

        for (file in files) {
            val index = LoadoutBench.indexOf(file)
            if (index !in template.ships.indices) {
                warnings += "Ignored ${file.name}: the template has no ship at position ${index + 1}."
                continue
            }
            val entry = template.ships[index]
            val json = readJson(file)
            if (json == null) {
                warnings += "Could not read ${file.name}."
                continue
            }

            val hullId = json.optString("hullId", "")
            if (!hullsMatch(hullId, entry.hullId)) {
                warnings += "Ignored ${file.name}: it holds '$hullId' but ship ${index + 1} is " +
                    "'${entry.hullId}'. Fit it again on the bench."
                continue
            }

            runCatching { applyTo(entry, json) }
                .onSuccess { updated++ }
                .onFailure { warnings += "Could not import ${file.name}: ${it.message}" }
        }

        return ImportResult(updated, warnings)
    }

    /**
     * Whether a variant's hull is the one the template expects.
     *
     * A ship carrying D-mods is built on its (D) hull, so the bench writes back the derived id. Both
     * directions are accepted because the template stores the base hull and the D-mods separately.
     */
    private fun hullsMatch(fromFile: String, fromTemplate: String): Boolean {
        if (fromFile.isEmpty() || fromTemplate.isEmpty()) return false
        if (fromFile == fromTemplate) return true
        return fromFile.removeSuffix(D_HULL_SUFFIX) == fromTemplate.removeSuffix(D_HULL_SUFFIX)
    }

    private fun applyTo(entry: ShipEntry, json: JSONObject) {
        entry.vents = json.optInt("fluxVents", entry.vents)
        entry.capacitors = json.optInt("fluxCapacitors", entry.capacitors)

        decomposeHullMods(json, entry)

        val groups = readWeaponGroups(json)
        entry.weaponGroups.clear()
        entry.weapons.clear()
        for ((group, weapons) in groups) {
            entry.weaponGroups += group
            entry.weapons.putAll(weapons)
        }

        entry.wings.clear()
        entry.wings.addAll(readWings(json))
    }

    /**
     * Splits the variant's nested hullmod lists into this mod's four disjoint ones.
     *
     * The game stores every installed mod in `hullMods`, then marks subsets of it: `permaMods` are
     * built in permanently, and `sMods` are the ones that additionally count against the built-in
     * limit. This mod's `permaMods` means specifically the *free* built-ins -- permanent but not
     * counted -- so it is the difference of the two, which is exactly the thing the refit screen has
     * no way to express and only this editor can grant.
     */
    private fun decomposeHullMods(json: JSONObject, entry: ShipEntry) {
        val all = json.optJSONArray("hullMods").toIds()
        val perma = json.optJSONArray("permaMods").toIds()
        val sMods = json.optJSONArray("sMods").toIds()

        val dMods = all.filter { isDMod(it) }.toSet()

        entry.dMods.clear()
        entry.dMods.addAll(dMods)

        entry.sMods.clear()
        entry.sMods.addAll(sMods.filterNot { it in dMods })

        entry.permaMods.clear()
        entry.permaMods.addAll(perma.filterNot { it in sMods || it in dMods })

        entry.hullMods.clear()
        entry.hullMods.addAll(all.filterNot { it in perma || it in sMods || it in dMods })
    }

    private fun isDMod(id: String): Boolean =
        runCatching { Catalog.hullModSpec(id)?.hasTag(Tags.HULLMOD_DMOD) == true }.getOrDefault(false)

    /**
     * Reads the weapon groups, and with them the slot-to-weapon map.
     *
     * Every mounted weapon lives inside a group in this format, so the groups *are* the loadout --
     * there is no separate weapon list to cross-check against.
     */
    private fun readWeaponGroups(json: JSONObject): List<Pair<WeaponGroup, Map<String, String>>> {
        val array = json.optJSONArray("weaponGroups") ?: return emptyList()
        val result = ArrayList<Pair<WeaponGroup, Map<String, String>>>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val weapons = LinkedHashMap<String, String>()
            obj.optJSONObject("weapons")?.let { mounted ->
                val keys = mounted.keys()
                while (keys.hasNext()) {
                    val slot = keys.next() as? String ?: continue
                    if (mounted.isNull(slot)) continue
                    val weaponId = mounted.optString(slot, "").trim()
                    if (weaponId.isNotEmpty() && weaponId != "null") weapons[slot] = weaponId
                }
            }
            if (weapons.isEmpty()) continue
            val group = WeaponGroup(
                autofire = obj.optBoolean("autofire", false),
                mode = if (obj.optString("mode", WeaponGroup.LINKED) == WeaponGroup.ALTERNATING) {
                    WeaponGroup.ALTERNATING
                } else {
                    WeaponGroup.LINKED
                },
                slots = ArrayList(weapons.keys),
            )
            result += group to weapons
        }
        return result
    }

    /**
     * Fighter wings, by bay.
     *
     * Bay position is meaningful, so an empty bay has to survive as a null rather than be dropped --
     * the same reason the template's own serialisation writes JSON nulls here. Trailing empties are
     * trimmed because a list of nothing but nulls is just "no wings".
     */
    private fun readWings(json: JSONObject): List<String?> {
        val array = json.optJSONArray("wings") ?: return emptyList()
        val wings = ArrayList<String?>()
        for (i in 0 until array.length()) {
            if (array.isNull(i)) {
                wings += null
                continue
            }
            val id = array.optString(i, "").trim()
            wings += if (id.isEmpty() || id == "null") null else id
        }
        while (wings.isNotEmpty() && wings.last() == null) wings.removeAt(wings.size - 1)
        return wings
    }

    private fun readJson(file: File): JSONObject? = runCatching {
        JSONObject(file.readText(Charsets.UTF_8))
    }.onFailure {
        log.warn("StarterPack: could not parse bench variant ${file.name}.", it)
    }.getOrNull()

    private fun org.json.JSONArray?.toIds(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            if (isNull(i)) continue
            val id = optString(i, "").trim()
            // Never optString alone -- Starsector's bundled org.json hands back the string "null"
            // for a JSON null. See the note in starterpack.model.Template.
            if (id.isNotEmpty() && id != "null") out += id
        }
        return out
    }

    /** Suffix the game appends when a hull is swapped to its damaged version. */
    private const val D_HULL_SUFFIX = "_D"

    private val log = Global.getLogger(BenchImport::class.java)
}
