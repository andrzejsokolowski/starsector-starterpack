package starterpack.bench

import com.fs.starfarer.api.fleet.FleetGoal
import com.fs.starfarer.api.fleet.FleetMemberType
import com.fs.starfarer.api.mission.FleetSide
import com.fs.starfarer.api.mission.MissionDefinitionAPI
import starterpack.apply.TemplateApplier
import starterpack.model.Template

/**
 * The refit bench: a mission whose only purpose is to hand you the game's own refit screen.
 *
 * Starsector lets you refit before a mission starts, so the bench stands the active template up as a
 * mission fleet and lets you fit it in the real UI with real ordnance validation. [BenchCapture],
 * registered here, reads the result back out of the live fleet once the mission begins.
 *
 * The mission is *not* meant to be fought. It exists to own a refit screen.
 *
 * **Nothing on this class may mention `java.io`.** `data/missions/starterpack_bench/
 * MissionDefinition.java` is compiled at runtime by Janino, and resolving its single call to
 * [defineMission] makes Janino call `getDeclaredMethods()` here -- which forces the JVM to load every
 * type in every signature on this object through a classloader that refuses `java.io` outright. The
 * same ban applies to the rest of the mod at runtime; the build enforces it jar-wide.
 */
object LoadoutBench {

    /** Must match the directory under `data/missions/` and the row in `mission_list.csv`. */
    const val MISSION_ID = "starterpack_bench"

    /**
     * Stand-in when a template ship cannot be built.
     *
     * A mission with an empty player fleet is not something the game is asked to do anywhere in
     * vanilla, and the bench is reached from a menu that cannot show an error dialog, so a missing
     * hull gets one throwaway ship and a briefing line rather than a crash.
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

        val memberIds = ArrayList<String>()

        if (ships.isEmpty()) {
            api.addBriefingItem("This template has no ships yet.")
            api.addBriefingItem("Add them in the STARTER PACK editor, then come back here to fit them.")
            api.addToFleet(FleetSide.PLAYER, PLACEHOLDER_VARIANT, FleetMemberType.SHIP, "Placeholder", true)
        } else {
            api.addBriefingItem("Press REFIT and fit your ships.")
            api.addBriefingItem("Then start the battle: StarterPack saves your loadouts the instant it begins.")
            api.addBriefingItem("There is nothing to win here -- leave as soon as it starts.")

            val warnings = ArrayList<String>()
            for ((index, entry) in ships.withIndex()) {
                val member = TemplateApplier.buildMember(entry, warnings)
                if (member == null) {
                    api.addToFleet(
                        FleetSide.PLAYER, PLACEHOLDER_VARIANT, FleetMemberType.SHIP,
                        "Unavailable: ${entry.hullId}", index == 0,
                    )
                    // A blank id holds this ship's position without ever matching a real member, so
                    // a hull from a disabled mod cannot shift everything after it onto the wrong ship.
                    memberIds += ""
                } else {
                    api.addFleetMember(FleetSide.PLAYER, member)
                    memberIds += runCatching { member.id }.getOrNull().orEmpty()
                }
            }
            for (warning in warnings) api.addBriefingItem(warning)
        }

        BenchState.recordMembers(memberIds)

        api.addToFleet(FleetSide.ENEMY, OPPONENT_VARIANT, FleetMemberType.SHIP, "Formality", false)
        api.addPlugin(BenchCapture())

        api.initMap(-6000f, 6000f, -6000f, 6000f)
    }
}

/**
 * Which template the bench builds, and how its fleet maps back onto that template.
 *
 * Held apart from [LoadoutBench] because the mission is constructed by the game on its own schedule,
 * long after the editor that staged it has been torn down.
 */
object BenchState {

    /** Name of the template staged for the bench, or null to fall back to the active one. */
    private var stagedTemplateName: String? = null

    /**
     * Fleet member ids in template-ship order, recorded as the mission fleet is assembled.
     *
     * This is the whole mapping back: [BenchCapture] looks a member's id up here to find which
     * template ship it is. Matching on identity rather than on position means a reordered or
     * partially-built fleet cannot write a loadout onto the wrong ship.
     */
    private var memberIds: List<String> = emptyList()

    /** True once the user has been sent to the bench and the result has not yet been read back. */
    var awaitingReturn: Boolean = false
        private set

    /**
     * The last import, waiting to be shown in the editor.
     *
     * Capture happens inside the mission and on the main menu, neither of which can show the editor's
     * status line, so the result is parked here until the editor next opens.
     */
    var lastImport: ImportResult? = null

    fun stage(template: Template) {
        stagedTemplateName = template.name
        awaitingReturn = true
    }

    fun clearAwaiting() {
        awaitingReturn = false
    }

    fun recordMembers(ids: List<String>) {
        memberIds = ArrayList(ids)
    }

    fun memberIds(): List<String> = memberIds

    /** The id the game gives ship [index] of a mission's player fleet once it has been refitted. */
    fun missionVariantId(index: Int): String = "mission_${LoadoutBench.MISSION_ID}_ship_$index"

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
