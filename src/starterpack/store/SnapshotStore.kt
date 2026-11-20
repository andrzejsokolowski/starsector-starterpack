package starterpack.store

import com.fs.starfarer.api.Global
import org.json.JSONArray
import org.json.JSONObject
import org.lazywizard.lazylib.JSONUtils
import starterpack.model.Snapshot

/**
 * Where snapshots live: their own file in `saves/common/`, next to the templates but never mixed
 * into them.
 *
 * Kept apart from [TemplateStore] because they are a different thing. A template is built by hand
 * and edited; a snapshot is a copy of a campaign, taken in that campaign and only looked at, renamed
 * or deleted afterwards. Sharing one list would put a 40-ship fleet copied from a save next to the
 * three test ships someone built in the editor, and the console names would collide.
 *
 * At most one snapshot is [armed]: that one is laid over every new game, instead of the active
 * template. Read and write failures are logged and never fatal, as with the templates.
 */
object SnapshotStore {

    /** Path under `saves/common/`. Starsector appends `.data` to the file on disk. */
    private const val COMMON_FILE = "starterpack/snapshots.json"

    private const val FORMAT_VERSION = 1

    private val log = Global.getLogger(SnapshotStore::class.java)

    private val snapshots = ArrayList<Snapshot>()

    /** Name of the snapshot that applies to new games, or blank for none. */
    private var armedName: String = ""

    /** Whether new snapshots save the known blueprints and hull mods (1.6.0). Kept between sessions. */
    private var saveBlueprints: Boolean = true

    private var loaded = false

    // --- Load / save ---------------------------------------------------------------------------

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching { readFile() }.onFailure {
            log.error("StarterPack: could not read $COMMON_FILE, starting with no snapshots.", it)
        }
    }

    private fun readFile() {
        val json: JSONObject = JSONUtils.loadCommonJSON(COMMON_FILE)
        if (json.length() == 0) return
        val array = json.optJSONArray("snapshots")
        if (array != null) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                runCatching { snapshots += Snapshot.fromJson(obj) }
                    .onFailure { log.error("StarterPack: skipping a snapshot that failed to parse.", it) }
            }
        }
        armedName = json.optString("armed", "")
        saveBlueprints = json.optBoolean("saveBlueprints", true)
    }

    private fun save() {
        runCatching {
            val json = JSONUtils.CommonDataJSONObject(COMMON_FILE)
            json.put("version", FORMAT_VERSION)
            json.put("snapshots", JSONArray().also { array -> snapshots.forEach { array.put(it.toJson()) } })
            json.put("armed", armedName)
            json.put("saveBlueprints", saveBlueprints)
            json.save()
        }.onFailure {
            log.error("StarterPack: could not write $COMMON_FILE; this session's changes are in memory only.", it)
        }
    }

    fun flush() {
        ensureLoaded()
        save()
    }

    fun location(): String = "saves/common/$COMMON_FILE"

    // --- Queries -------------------------------------------------------------------------------

    fun all(): List<Snapshot> {
        ensureLoaded()
        return snapshots
    }

    fun isEmpty(): Boolean = all().isEmpty()

    fun byName(name: String): Snapshot? =
        all().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /** The snapshot new games start from, or null when none is set. */
    fun armed(): Snapshot? {
        ensureLoaded()
        if (armedName.isBlank()) return null
        return byName(armedName)
    }

    fun isArmed(snapshot: Snapshot): Boolean = armed() === snapshot

    fun isSaveBlueprints(): Boolean {
        ensureLoaded()
        return saveBlueprints
    }

    // --- Mutations -----------------------------------------------------------------------------

    fun setSaveBlueprints(enabled: Boolean) {
        ensureLoaded()
        saveBlueprints = enabled
        save()
    }

    /** Sets the snapshot new games start from, or clears it with null. */
    fun setArmed(snapshot: Snapshot?) {
        ensureLoaded()
        armedName = snapshot?.name.orEmpty()
        save()
    }

    fun add(snapshot: Snapshot): Snapshot {
        ensureLoaded()
        snapshot.name = uniqueName(snapshot.name)
        snapshots += snapshot
        save()
        return snapshot
    }

    /**
     * Puts [fresh] in the place of [old]: same name, same position, still armed if it was. That is
     * what "save over this snapshot" means to someone keeping one snapshot per playthrough.
     */
    fun replace(old: Snapshot, fresh: Snapshot): Snapshot {
        ensureLoaded()
        val index = snapshots.indexOf(old)
        if (index < 0) return add(fresh)
        fresh.name = old.name
        snapshots[index] = fresh
        save()
        return fresh
    }

    fun delete(snapshot: Snapshot) {
        ensureLoaded()
        val wasArmed = isArmed(snapshot)
        if (!snapshots.remove(snapshot)) return
        if (wasArmed) armedName = ""
        save()
    }

    /**
     * Renames in place and keeps the armed pointer on the same snapshot. The editor renames as you
     * type with [persist] off and flushes once the caret leaves the field: a snapshot file holds whole
     * fleets and blueprint lists, too much to write on every keystroke.
     */
    fun rename(snapshot: Snapshot, newName: String, persist: Boolean = true) {
        ensureLoaded()
        val trimmed = newName.trim().ifBlank { return }
        if (trimmed == snapshot.name) return
        val wasArmed = isArmed(snapshot)
        snapshot.name = uniqueName(trimmed, except = snapshot)
        if (wasArmed) armedName = snapshot.name
        if (persist) save()
    }

    /** Appends `2`, `3`, ... until the name is free, as [TemplateStore] does. */
    private fun uniqueName(desired: String, except: Snapshot? = null): String {
        val base = desired.trim().ifBlank { "Snapshot" }
        val taken = snapshots.filter { it !== except }.map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        var suffix = 2
        while ("${base.lowercase()} $suffix" in taken) suffix++
        return "$base $suffix"
    }
}
