package starterpack.store

import com.fs.starfarer.api.Global
import org.json.JSONArray
import org.json.JSONObject
import org.lazywizard.lazylib.JSONUtils
import starterpack.model.Template

/**
 * Where templates live: one JSON file in Starsector's common data folder, shared by every save.
 *
 * ## Why not the save
 *
 * A starter pack describes what a campaign should *begin* with, so by the time a save exists it has
 * already done its job. Storing it in the save would also make the editor impossible: the editor runs
 * at the main menu, where there is no sector to write to. `saves/common/` is the same place
 * Starsector keeps its own cross-campaign state, and it survives deleting every save you own.
 *
 * ## Written on every change
 *
 * The file is small and edits are user-driven, so there is no reason to batch. A crash or an alt-F4
 * can never lose a template.
 *
 * ## Failure is non-fatal
 *
 * A read that throws leaves an empty store and logs; a write that throws leaves the in-memory state
 * authoritative for the session and logs. Neither takes the main menu down with it.
 */
object TemplateStore {

    /** Path under `saves/common/`. Starsector appends `.data` to the file on disk. */
    private const val COMMON_FILE = "starterpack/templates.json"

    private const val FORMAT_VERSION = 1

    private val log = Global.getLogger(TemplateStore::class.java)

    private val templates = ArrayList<Template>()

    /** Name of the template `starterpack apply` and auto-apply use when given no argument. */
    private var activeName: String = ""

    /** Whether a new game should get the active template stamped on it without being asked. */
    private var autoApply: Boolean = false

    private var loaded = false

    // --- Load / save ---------------------------------------------------------------------------

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true          // set first: a failed read must not retry on every frame
        runCatching { readFile() }.onFailure {
            log.error("StarterPack: could not read $COMMON_FILE, starting with no templates.", it)
        }
    }

    private fun readFile() {
        val json: JSONObject = JSONUtils.loadCommonJSON(COMMON_FILE)
        if (json.length() == 0) return          // no file yet (or an empty one): nothing to restore

        val array = json.optJSONArray("templates")
        if (array != null) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                // One malformed template must not cost you the other nine.
                runCatching { templates += Template.fromJson(obj) }
                    .onFailure { log.error("StarterPack: skipping a template that failed to parse.", it) }
            }
        }
        activeName = json.optString("active", "")
        autoApply = json.optBoolean("autoApply", false)
    }

    private fun save() {
        runCatching {
            val json = JSONUtils.CommonDataJSONObject(COMMON_FILE)
            json.put("version", FORMAT_VERSION)
            json.put("templates", JSONArray().also { array -> templates.forEach { array.put(it.toJson()) } })
            json.put("active", activeName)
            json.put("autoApply", autoApply)
            json.save()
        }.onFailure {
            log.error("StarterPack: could not write $COMMON_FILE; this session's changes are in memory only.", it)
        }
    }

    /** Persists the current state. Call after mutating a [Template] that the store already holds. */
    fun flush() {
        ensureLoaded()
        save()
    }

    /** Where the store lives, for the "this is shared by every save" note in the UI. */
    fun location(): String = "saves/common/$COMMON_FILE"

    // --- Queries -------------------------------------------------------------------------------

    fun all(): List<Template> {
        ensureLoaded()
        return templates
    }

    fun isEmpty(): Boolean = all().isEmpty()

    /** Case-insensitive, because console arguments are typed by hand. */
    fun byName(name: String): Template? =
        all().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /**
     * The template that applies when none is named.
     *
     * Falls back to the only template when exactly one exists: a single-template store has no
     * ambiguity to resolve, and making the user set an "active" flag to use it would be ceremony.
     */
    fun active(): Template? {
        ensureLoaded()
        byName(activeName)?.let { return it }
        return templates.singleOrNull()
    }

    fun activeName(): String = active()?.name.orEmpty()

    fun isAutoApplyEnabled(): Boolean {
        ensureLoaded()
        return autoApply
    }

    // --- Mutations -----------------------------------------------------------------------------

    fun setActive(template: Template?) {
        ensureLoaded()
        activeName = template?.name.orEmpty()
        save()
    }

    fun setAutoApply(enabled: Boolean) {
        ensureLoaded()
        autoApply = enabled
        save()
    }

    fun add(template: Template): Template {
        ensureLoaded()
        template.name = uniqueName(template.name)
        templates += template
        // First template in an empty store becomes active, so the console works immediately.
        if (activeName.isBlank()) activeName = template.name
        save()
        return template
    }

    fun create(name: String = "New template"): Template = add(Template(name = name))

    fun duplicate(template: Template): Template = add(template.copy(newName = "${template.name} copy"))

    fun delete(template: Template) {
        ensureLoaded()
        if (!templates.remove(template)) return
        if (activeName.equals(template.name, ignoreCase = true)) {
            activeName = templates.firstOrNull()?.name.orEmpty()
        }
        save()
    }

    /**
     * Renames in place, keeping the active pointer attached to the same template rather than to the
     * name it used to have.
     */
    fun rename(template: Template, newName: String) {
        ensureLoaded()
        val trimmed = newName.trim().ifBlank { return }
        if (trimmed == template.name) return
        val wasActive = activeName.equals(template.name, ignoreCase = true)
        template.name = uniqueName(trimmed, except = template)
        if (wasActive) activeName = template.name
        save()
    }

    /**
     * Appends `2`, `3`, ... until the name is free.
     *
     * Names are the console's only handle on a template, so two templates called "test" would make
     * one of them unreachable from the command line. Uniqueness is enforced here rather than
     * rejected at the UI, so the user always gets *a* result from a rename.
     */
    private fun uniqueName(desired: String, except: Template? = null): String {
        val base = desired.trim().ifBlank { "Unnamed" }
        val taken = templates.filter { it !== except }.map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        var suffix = 2
        while ("${base.lowercase()} $suffix" in taken) suffix++
        return "$base $suffix"
    }
}
