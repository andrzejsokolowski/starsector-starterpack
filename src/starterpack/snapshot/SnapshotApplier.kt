package starterpack.snapshot

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.characters.FullName
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI
import com.fs.starfarer.api.characters.PersonAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.impl.campaign.ids.Factions
import com.fs.starfarer.api.impl.campaign.ids.MemFlags
import com.fs.starfarer.api.impl.campaign.ids.Ranks
import com.fs.starfarer.api.util.Misc
import starterpack.apply.TemplateApplier
import starterpack.catalog.Catalog
import starterpack.model.CharacterState
import starterpack.model.OfficerEntry
import starterpack.model.Snapshot
import java.util.Random

/** What applying a snapshot did. */
class SnapshotResult(
    val ships: Int = 0,
    val officers: Int = 0,
    val aiCores: Int = 0,
    val cargoStacks: Int = 0,
    val skills: Int = 0,
    val blueprints: Int = 0,
    val warnings: List<String> = emptyList(),
    val failure: String? = null,
) {
    val succeeded: Boolean get() = failure == null

    fun describe(): String {
        failure?.let { return it }
        val parts = ArrayList<String>(6)
        if (ships > 0) parts += "$ships ship${if (ships == 1) "" else "s"}"
        if (officers > 0) parts += "$officers officer${if (officers == 1) "" else "s"}"
        if (aiCores > 0) parts += "$aiCores AI core captain${if (aiCores == 1) "" else "s"}"
        if (cargoStacks > 0) parts += "$cargoStacks cargo stacks"
        if (skills > 0) parts += "$skills skills"
        if (blueprints > 0) parts += "$blueprints blueprints and hull mods"
        return if (parts.isEmpty()) "nothing to apply" else parts.joinToString(", ")
    }
}

/**
 * Lays a [Snapshot] over the running campaign, normally a brand-new one.
 *
 * The fleet, cargo, credits, standings and hotbar go through [TemplateApplier], so a snapshot's ships
 * are built exactly the way a template's are, and the same "once per campaign" flag guards both.
 * The rest is what only a snapshot has, applied in an order that matters:
 *
 * 1. The character first: officer slots come from the character's skills, so the officers that
 *    follow need those skills to be in place.
 * 2. Officers and AI cores next, onto the ships the fleet step reported building.
 * 3. Blueprints last. They only ever add to what the new game knows.
 */
object SnapshotApplier {

    private val log = Global.getLogger(SnapshotApplier::class.java)

    fun apply(snapshot: Snapshot, force: Boolean = false): SnapshotResult {
        val sector = Global.getSector() ?: return SnapshotResult(failure = "No campaign is running.")
        val fleet = sector.playerFleet ?: return SnapshotResult(failure = "No player fleet to apply to.")

        // A copy, so nothing done here can change the stored snapshot, and named so the campaign's
        // "already applied" flag says it was a snapshot.
        val pack = snapshot.pack.copy("Snapshot: ${snapshot.name}")
        pack.replaceFleet = true
        pack.replaceCargo = true
        pack.skillPoints = 0
        pack.storyPoints = 0
        pack.level = null

        val built = HashMap<Int, FleetMemberAPI>()
        val packResult = TemplateApplier.apply(pack, force) { index, member -> built[index] = member }
        if (!packResult.succeeded) return SnapshotResult(failure = packResult.describe())

        val warnings = ArrayList(packResult.warnings)

        // A new game starts with a repaired, ready fleet, whatever state the old one was left in.
        for ((index, member) in built) {
            runCatching {
                member.status?.repairFully()
                member.repairTracker?.let { it.cr = it.maxCR }
            }
            if (index in snapshot.mothballed) {
                runCatching {
                    member.repairTracker?.let {
                        // What the ship goes back to when it is taken out of mothballs.
                        it.crPriorToMothballing = it.maxCR
                        it.isMothballed = true
                    }
                }
            }
        }

        var skills = 0
        snapshot.character?.let { character ->
            runCatching { skills = applyCharacter(character, warnings) }
                .onFailure { warnings += "The character could not be fully set: ${it.message}"; log.error("StarterPack", it) }
        }

        // The new game's own officers make way for the saved ones, as its ships did.
        runCatching {
            for (data in fleet.fleetData.officersCopy.toList()) {
                val person = data?.person ?: continue
                if (person.isPlayer) continue
                fleet.fleetData.removeOfficer(person)
            }
        }

        var officers = 0
        var aiCores = 0
        for (entry in snapshot.officers) {
            runCatching {
                if (entry.aiCoreId.isNotBlank()) {
                    if (applyAiCore(entry, built, warnings)) aiCores++
                } else {
                    if (applyOfficer(entry, fleet, built, warnings)) officers++
                }
            }.onFailure { warnings += "${entry.displayName()} could not be added: ${it.message}" }
        }

        val blueprints = runCatching { applyBlueprints(snapshot, warnings) }.getOrElse {
            warnings += "Blueprints could not be added: ${it.message}"
            0
        }

        runCatching { fleet.fleetData?.setSyncNeeded() }
        runCatching { fleet.forceSync() }

        val result = SnapshotResult(
            ships = packResult.shipsAdded,
            officers = officers,
            aiCores = aiCores,
            cargoStacks = packResult.cargoStacks,
            skills = skills,
            blueprints = blueprints,
            warnings = warnings,
        )
        log.info("StarterPack: applied snapshot \"${snapshot.name}\" -- ${result.describe()}")
        warnings.forEach { log.warn("StarterPack: $it") }
        return result
    }

    // --- Character -----------------------------------------------------------------------------

    /**
     * Sets the character to exactly what was saved and returns how many skills were set.
     *
     * Experience can only be added, never set, so the difference is added with level-ups and bonus
     * experience off; the level, points and bonus experience are then set outright, which also undoes
     * anything the addition handed out on its own.
     */
    private fun applyCharacter(character: CharacterState, warnings: MutableList<String>): Int {
        val stats = Global.getSector()?.playerPerson?.stats ?: run {
            warnings += "No player character; level, skills and points were skipped."
            return 0
        }
        runCatching { stats.setSkipRefresh(true) }

        val missing = character.xp - runCatching { stats.xp }.getOrDefault(0L)
        if (missing > 0L) runCatching { stats.addXP(missing, null, false, false, false) }
        runCatching { stats.level = character.level }
        runCatching { stats.points = character.skillPoints }
        runCatching { stats.storyPoints = character.storyPoints }
        runCatching { stats.bonusXp = character.bonusXp }
        runCatching { stats.deferredBonusXp = character.deferredBonusXp }

        // The new game's own picks go, the saved ones come in. Skills a snapshot never copies (NG+
        // perks, NPC-only skills) are left as they are.
        for (skill in runCatching { stats.skillsCopy?.toList() }.getOrNull().orEmpty()) {
            val spec = runCatching { skill.skill }.getOrNull() ?: continue
            if (runCatching { skill.level }.getOrDefault(0f) <= 0f) continue
            if (!SnapshotCapture.isCopiedPlayerSkill(spec)) continue
            runCatching { stats.setSkillLevel(spec.id, 0f) }
        }
        val set = setSkills(stats, character.skills, "you", warnings)

        runCatching { stats.setSkipRefresh(false) }
        runCatching { stats.refreshCharacterStatsEffects() }
        return set
    }

    private fun setSkills(
        stats: MutableCharacterStatsAPI,
        skills: Map<String, Float>,
        who: String,
        warnings: MutableList<String>,
    ): Int {
        var set = 0
        for ((id, level) in skills) {
            if (runCatching { Global.getSettings().getSkillSpec(id) }.getOrNull() == null) {
                warnings += "Unknown skill '$id' on $who -- skipped."
                continue
            }
            runCatching { stats.setSkillLevel(id, level) }.onSuccess { set++ }
        }
        return set
    }

    // --- Officers ------------------------------------------------------------------------------

    private fun applyOfficer(
        entry: OfficerEntry,
        fleet: CampaignFleetAPI,
        built: Map<Int, FleetMemberAPI>,
        warnings: MutableList<String>,
    ): Boolean {
        val faction = Global.getSector()?.playerFaction ?: return false
        val person = runCatching { faction.createRandomPerson(Random()) }.getOrNull()
            ?: Global.getFactory().createPerson().also { it.setFaction(Factions.PLAYER) }

        runCatching { person.stats.setSkipRefresh(true) }
        val gender = runCatching { FullName.Gender.valueOf(entry.gender) }.getOrDefault(FullName.Gender.ANY)
        if (entry.firstName.isNotBlank() || entry.lastName.isNotBlank()) {
            runCatching { person.name = FullName(entry.firstName, entry.lastName, gender) }
        }
        runCatching { person.gender = gender }
        if (entry.portrait.isNotBlank()) runCatching { person.portraitSprite = entry.portrait }
        runCatching { person.setPersonality(entry.personality) }
        runCatching { person.rankId = Ranks.SPACE_LIEUTENANT }
        runCatching { person.postId = Ranks.POST_OFFICER }

        layOver(person, entry, warnings)

        runCatching { fleet.fleetData.addOfficer(person) }.onFailure {
            warnings += "${entry.displayName()} could not join the fleet: ${it.message}"
            return false
        }
        val member = built[entry.ship]
        if (entry.ship >= 0 && member != null && !runCatching { member.isFlagship }.getOrDefault(false)) {
            runCatching { member.captain = person }
        }
        return true
    }

    /**
     * An AI core is made by the game's own plugin for that core, so it carries what such a core
     * always carries, then gets the saved level and skills. Without the mod that adds the core there
     * is no plugin, and the ship goes without.
     */
    private fun applyAiCore(entry: OfficerEntry, built: Map<Int, FleetMemberAPI>, warnings: MutableList<String>): Boolean {
        val member = built[entry.ship] ?: return false
        val plugin = runCatching { Misc.getAICoreOfficerPlugin(entry.aiCoreId) }.getOrNull()
        if (plugin == null) {
            warnings += "Unknown AI core '${entry.aiCoreId}' -- ${runCatching { member.shipName }.getOrDefault("a ship")} " +
                "has no captain."
            return false
        }
        val person = runCatching { plugin.createPerson(entry.aiCoreId, Factions.PLAYER, Random()) }.getOrNull()
            ?: return false
        runCatching { person.stats.setSkipRefresh(true) }
        layOver(person, entry, warnings)
        runCatching { member.captain = person }.onFailure { return false }
        return true
    }

    /** The saved level, experience, skills and flags, over whatever the person was made with. */
    private fun layOver(person: PersonAPI, entry: OfficerEntry, warnings: MutableList<String>) {
        val stats = person.stats
        for (skill in runCatching { stats.skillsCopy?.toList() }.getOrNull().orEmpty()) {
            val spec = runCatching { skill.skill }.getOrNull() ?: continue
            if (runCatching { spec.isAptitudeEffect }.getOrDefault(false)) continue
            if (runCatching { skill.level }.getOrDefault(0f) > 0f) runCatching { stats.setSkillLevel(spec.id, 0f) }
        }
        val missing = entry.xp - runCatching { stats.xp }.getOrDefault(0L)
        if (missing > 0L) runCatching { stats.addXP(missing, null, false, false, false) }
        runCatching { stats.level = entry.level }
        setSkills(stats, entry.skills, entry.displayName(), warnings)

        if (entry.mentored) runCatching { Misc.setMentored(person, true) }
        if (entry.exceptional) {
            runCatching { person.memoryWithoutUpdate.set(MemFlags.EXCEPTIONAL_SLEEPER_POD_OFFICER, true) }
        }
        if (entry.unremovable) runCatching { Misc.setUnremovable(person, true) }

        runCatching { stats.setSkipRefresh(false) }
        runCatching { stats.refreshCharacterStatsEffects() }
    }

    // --- Blueprints ----------------------------------------------------------------------------

    private fun applyBlueprints(snapshot: Snapshot, warnings: MutableList<String>): Int {
        val sector = Global.getSector() ?: return 0
        val faction = sector.playerFaction ?: return 0
        val known = snapshot.blueprints
        var added = 0
        var missing = 0

        for (id in known.ships) {
            if (Catalog.hullSpec(id) == null) { missing++; continue }
            if (runCatching { faction.knowsShip(id) }.getOrDefault(false)) continue
            runCatching { faction.addKnownShip(id, false) }.onSuccess { added++ }
        }
        for (id in known.fighters) {
            if (Catalog.wingSpec(id) == null) { missing++; continue }
            if (runCatching { id in faction.knownFighters }.getOrDefault(false)) continue
            runCatching { faction.addKnownFighter(id, false) }.onSuccess { added++ }
        }
        for (id in known.weapons) {
            if (Catalog.weaponSpec(id) == null) { missing++; continue }
            if (runCatching { id in faction.knownWeapons }.getOrDefault(false)) continue
            runCatching { faction.addKnownWeapon(id, false) }.onSuccess { added++ }
        }
        val character = sector.characterData
        for (id in known.hullMods) {
            if (Catalog.hullModSpec(id) == null) { missing++; continue }
            if (character == null || runCatching { character.knowsHullMod(id) }.getOrDefault(false)) continue
            runCatching { character.addHullMod(id) }.onSuccess { added++ }
        }
        if (missing > 0) {
            warnings += "$missing blueprint${if (missing == 1) " is" else "s are"} from mods that are not " +
                "in your mod list -- skipped."
        }
        return added
    }
}
