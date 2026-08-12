package starterpack.bench

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.fleet.FleetGoal
import com.fs.starfarer.api.fleet.FleetMemberType
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.mission.MissionDefinitionAPI
import starterpack.apply.TemplateApplier
import starterpack.model.Template
import java.io.File

/**
 * The refit bench: a mission whose only purpose is to hand you the game's own refit screen.
 *
 * Starsector lets you refit before a mission starts, and -- crucially -- it *persists* what you built
 * to `saves/missions/<mission id>/mission_<mission id>_ship_<n>.variant`, outside any campaign save.
 * That file is a plain variant JSON carrying hull, vents, capacitors, hullmods, the permanent/S-mod
 * split, weapon groups and wings: very nearly this mod's [starterpack.model.ShipEntry] already.
 *
 * So instead of asking the editor's hand-rolled pickers to compete with the refit screen, the bench
 * stands the active template up as a mission fleet, lets you fit it in the real UI with real ordnance
 * validation, and reads the result back. See [BenchImport] for the return leg.
 *
 * The mission is *not* meant to be fought. It exists to own a refit screen.
 */
object LoadoutBench {

    /** Must match the directory under `data/missions/` and the row in `mission_list.csv`. */
    const val MISSION_ID = "starterpack_bench"

    /**
     * Stand-in when the template has no ships.
     *
     * A mission with an empty player fleet is not something the game is asked to do anywhere in
     * vanilla, and the bench is reached from a menu that cannot show an error dialog, so it gets one
     * throwaway hull and a briefing line explaining itself rather than a crash.
     */
    private const val PLACEHOLDER_VARIANT = "hound_Standard"

    /** The opposing fleet. Never fought; present because a battle needs two sides. */
    private const val OPPONENT_VARIANT = "hound_Standard"

    /**
     * Builds the mission. Called from `data/missions/starterpack_bench/MissionDefinition.java`.
     *
     * That file is compiled by Janino at runtime, so it stays a one-line delegate into here where
     * the real language is available.
     */
    @JvmStatic
    fun defineMission(api: MissionDefinitionAPI) {
        api.initFleet(FleetSide.PLAYER, "ISS", FleetGoal.ATTACK, false)
        api.initFleet(FleetSide.ENEMY, "ISS", FleetGoal.ATTACK, true)

        val template = runCatching { BenchState.templateForBench() }.getOrNull()
        val ships = template?.ships.orEmpty()

        api.setFleetTagline(FleetSide.PLAYER, template?.name ?: "StarterPack")
        api.setFleetTagline(FleetSide.ENEMY, "Nobody. Do not fight this.")

        if (ships.isEmpty()) {
            api.addBriefingItem("This template has no ships yet.")
            api.addBriefingItem("Add them in the STARTER PACK editor, then come back here to fit them.")
            api.addToFleet(FleetSide.PLAYER, PLACEHOLDER_VARIANT, FleetMemberType.SHIP, "Placeholder", true)
        } else {
            api.addBriefingItem("Press REFIT, fit your ships, then leave the mission.")
            api.addBriefingItem("StarterPack reads your loadouts back into the template automatically.")
            api.addBriefingItem("There is nothing to win here -- this battle is a formality.")

            val warnings = ArrayList<String>()
            var added = 0
            for (entry in ships) {
                // Order matters and is the contract with the importer: the game names the saved
                // variant files by fleet index, so member N here is ships[N] there. Anything that
                // fails to build is still counted, so a skipped ship shifts nothing.
                val member = TemplateApplier.buildMember(entry, warnings)
                if (member == null) {
                    api.addToFleet(
                        FleetSide.PLAYER, PLACEHOLDER_VARIANT, FleetMemberType.SHIP,
                        "Unavailable: ${entry.hullId}", added == 0,
                    )
                } else {
                    api.addFleetMember(FleetSide.PLAYER, member)
                }
                added++
            }
            for (warning in warnings) api.addBriefingItem(warning)
        }

        api.addToFleet(FleetSide.ENEMY, OPPONENT_VARIANT, FleetMemberType.SHIP, "Formality", false)

        api.initMap(-6000f, 6000f, -6000f, 6000f)
    }

    /** `saves/missions/starterpack_bench`, wherever this install keeps its saves. */
    fun benchDir(): File = File(savesDir(), "missions/$MISSION_ID")

    /**
     * The saves directory.
     *
     * The launcher passes this as a system property (`-Dcom.fs.starfarer.settings.paths.saves`), so
     * installs that relocate their saves are handled. The fallback matches the stock launcher, whose
     * working directory is `starsector-core`.
     */
    private fun savesDir(): File =
        File(System.getProperty("com.fs.starfarer.settings.paths.saves") ?: "../saves")

    /** Every variant the bench has written, in fleet order. Empty when it has never been visited. */
    fun savedVariants(): List<File> {
        val dir = benchDir()
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".variant") } ?: return emptyList()
        return files.sortedBy { indexOf(it) }
    }

    /**
     * The fleet index encoded in a bench variant's filename.
     *
     * The game names these `mission_<mission id>_ship_<n>.variant`. A name that does not parse
     * returns [Int.MAX_VALUE] so it sorts last and is rejected by the importer rather than silently
     * being treated as ship zero.
     */
    fun indexOf(file: File): Int {
        val name = file.name.removeSuffix(".variant")
        val marker = "_ship_"
        val at = name.lastIndexOf(marker)
        if (at < 0) return Int.MAX_VALUE
        return name.substring(at + marker.length).toIntOrNull() ?: Int.MAX_VALUE
    }

    /**
     * Deletes what the bench previously wrote.
     *
     * Run before sending the user in, so a template that lost a ship cannot have the stale variant of
     * a since-removed slot imported back onto whatever now occupies that index.
     */
    fun clearSavedVariants(): Int {
        var removed = 0
        for (file in savedVariants()) {
            if (runCatching { file.delete() }.getOrDefault(false)) removed++
        }
        return removed
    }

    private val log = Global.getLogger(LoadoutBench::class.java)

    init {
        log.info("StarterPack: refit bench available as mission '$MISSION_ID'.")
    }
}

/**
 * Which template the bench builds, and whether a trip to it is outstanding.
 *
 * Held apart from [LoadoutBench] because the mission is constructed by the game on its own schedule,
 * long after the editor that staged it has been torn down.
 */
object BenchState {

    /** Name of the template staged for the bench, or null to fall back to the active one. */
    private var stagedTemplateName: String? = null

    /** True once the user has been sent to the bench and the result has not yet been read back. */
    var awaitingReturn: Boolean = false
        private set

    /**
     * The last import, waiting to be shown in the editor.
     *
     * The import happens on the main menu, which has nowhere to say anything, so the result is parked
     * here until the editor next opens and can report it.
     */
    var lastImport: ImportResult? = null

    fun stage(template: Template) {
        stagedTemplateName = template.name
        awaitingReturn = true
    }

    fun clearAwaiting() {
        awaitingReturn = false
    }

    /**
     * The template the mission should build.
     *
     * Falls back to the active template so that reaching the bench straight from the Missions menu,
     * without going through the editor, still does something sensible.
     */
    fun templateForBench(): Template? {
        val store = starterpack.store.TemplateStore
        val staged = stagedTemplateName
        return staged?.let { name -> store.all().firstOrNull { it.name == name } } ?: store.active()
    }
}
