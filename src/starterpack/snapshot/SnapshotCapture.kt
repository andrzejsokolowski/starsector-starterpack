package starterpack.snapshot

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CargoAPI
import com.fs.starfarer.api.campaign.SectorAPI
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI
import com.fs.starfarer.api.characters.PersonAPI
import com.fs.starfarer.api.characters.SkillSpecAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.impl.campaign.ids.Factions
import com.fs.starfarer.api.impl.campaign.ids.Items
import com.fs.starfarer.api.impl.campaign.ids.MemFlags
import com.fs.starfarer.api.util.Misc
import starterpack.bench.BenchImport
import starterpack.catalog.Catalog
import starterpack.model.Blueprints
import starterpack.model.CargoSpec
import starterpack.model.CharacterState
import starterpack.model.Hotbar
import starterpack.model.OfficerEntry
import starterpack.model.ShipEntry
import starterpack.model.Snapshot
import starterpack.model.SpecialItem
import starterpack.store.SnapshotStore
import java.time.LocalDate
import java.util.IdentityHashMap

/** A freshly taken snapshot, and what could not be copied into it. */
class CaptureResult(
    val snapshot: Snapshot? = null,
    val warnings: List<String> = emptyList(),
    val failure: String? = null,
)

/**
 * Reads the running campaign into a [Snapshot].
 *
 * Every part is read on its own and a part that throws is reported, not fatal: a mod that does
 * something odd to one ship should not cost the player the snapshot of the other thirty.
 *
 * Loadouts go through [BenchImport.importVariant], the same reader the refit bench uses, so a ship
 * comes out of a campaign exactly as it would come back from the refit screen: hull mods split into
 * regular, S-mods, free built-ins and D-mods, weapon groups kept, refitted modules kept.
 */
object SnapshotCapture {

    /**
     * New Game Plus - Renewed's perks are skills too, bought at its Legacy Station with career points
     * for one campaign at a time. Copying them would hand them out for free, so they stay behind.
     */
    private val SKIPPED_APTITUDES = setOf("ngp_perks", "ngp_fighter_perks")

    private val log = Global.getLogger(SnapshotCapture::class.java)

    /**
     * [saveBlueprints] off leaves the known blueprints and hull mods out, for a new game plus that
     * finds them again. It follows the player's switch unless told otherwise.
     */
    fun capture(name: String? = null, saveBlueprints: Boolean = SnapshotStore.isSaveBlueprints()): CaptureResult {
        val sector = Global.getSector() ?: return CaptureResult(failure = "No campaign is running.")
        val fleet = sector.playerFleet ?: return CaptureResult(failure = "There is no player fleet to copy.")

        val warnings = ArrayList<String>()
        val snapshot = Snapshot()
        val pack = snapshot.pack
        pack.replaceFleet = true
        pack.replaceCargo = true

        snapshot.captain = runCatching { sector.playerPerson?.nameString }.getOrNull().orEmpty().trim()
        snapshot.takenOn = runCatching { sector.clock?.dateString }.getOrNull().orEmpty()
        snapshot.savedOn = runCatching { LocalDate.now().toString() }.getOrDefault("")
        snapshot.name = name?.trim()?.takeIf { it.isNotEmpty() } ?: defaultName(snapshot)

        // --- Ships ---------------------------------------------------------------------------
        val shipIndex = IdentityHashMap<FleetMemberAPI, Int>()
        val members = runCatching { fleet.fleetData.membersListCopy.toList() }.getOrDefault(emptyList())
        for (member in members) {
            if (runCatching { member.isFighterWing }.getOrDefault(false)) continue
            val entry = shipOf(member, warnings) ?: continue
            shipIndex[member] = pack.ships.size
            if (runCatching { member.isMothballed }.getOrDefault(false)) snapshot.mothballed += pack.ships.size
            pack.ships += entry
        }
        if (pack.ships.none { it.flagship }) pack.ships.firstOrNull()?.flagship = true

        // --- Officers and AI core captains ---------------------------------------------------
        runCatching { captureOfficers(fleet.fleetData.officersCopy.map { it.person }, members, shipIndex, snapshot, warnings) }
            .onFailure { warnings += "Officers could not be copied: ${it.message}"; log.error("StarterPack: officers", it) }

        // --- Cargo and credits ---------------------------------------------------------------
        runCatching { fleet.cargo?.let { pack.cargo = cargoOf(it) } }
            .onFailure { warnings += "Cargo could not be copied: ${it.message}"; log.error("StarterPack: cargo", it) }
        runCatching { fleet.cargo?.credits?.get()?.let { pack.credits = it.toLong() } }

        // --- Character -----------------------------------------------------------------------
        runCatching { sector.playerPerson?.stats?.let { snapshot.character = characterOf(it) } }
            .onFailure { warnings += "The character could not be copied: ${it.message}"; log.error("StarterPack: character", it) }

        // --- Standings -----------------------------------------------------------------------
        runCatching { captureStandings(sector, snapshot) }
            .onFailure { warnings += "Faction standings could not be copied: ${it.message}" }

        // --- Hotbar --------------------------------------------------------------------------
        runCatching { pack.hotbar = hotbarOf(sector) }
            .onFailure { warnings += "The hotbar could not be copied: ${it.message}" }

        // --- Blueprints ----------------------------------------------------------------------
        snapshot.blueprintsSaved = saveBlueprints
        if (saveBlueprints) {
            runCatching { snapshot.blueprints = blueprintsOf(sector) }
                .onFailure { warnings += "Blueprints could not be copied: ${it.message}" }
        }

        log.info("StarterPack: took snapshot \"${snapshot.name}\" -- ${snapshot.summary()}")
        warnings.forEach { log.warn("StarterPack: $it") }
        return CaptureResult(snapshot, warnings)
    }

    private fun defaultName(snapshot: Snapshot): String =
        listOf(snapshot.captain, snapshot.takenOn).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Snapshot" }

    // --- Ships ---------------------------------------------------------------------------------

    private fun shipOf(member: FleetMemberAPI, warnings: MutableList<String>): ShipEntry? {
        val variant = runCatching { member.variant }.getOrNull() ?: return null
        val hull = runCatching { variant.hullSpec }.getOrNull() ?: return null
        // A ship with D-mods flies on its (D) hull. A template keeps the hull and its D-mods apart,
        // and the applier swaps to the (D) hull itself when there are D-mods to put on.
        val hullId = runCatching {
            if (hull.isDHull) hull.dParentHullId?.takeIf { it.isNotBlank() } ?: BenchImport.baseHullId(hull.hullId)
            else hull.hullId
        }.getOrNull().orEmpty()
        if (hullId.isBlank()) return null

        val entry = ShipEntry(hullId = hullId)
        entry.shipName = runCatching { member.shipName }.getOrNull().orEmpty()
        entry.flagship = runCatching { member.isFlagship }.getOrDefault(false)
        if (!BenchImport.importVariant(entry, variant, warnings)) return null
        dropHiddenMods(entry)
        entry.moduleLoadouts.values.forEach { dropHiddenMods(it) }
        return entry
    }

    /**
     * Hull mods the player cannot see are put on by other mods, which keep what they mean somewhere
     * else in the save. Carried into a new game without that, they would be orphans at best.
     */
    private fun dropHiddenMods(entry: ShipEntry) {
        fun hidden(id: String) = runCatching { Catalog.hullModSpec(id)?.let { it.isHidden || it.isHiddenEverywhere } }
            .getOrNull() == true
        entry.hullMods.removeAll { hidden(it) }
        entry.sMods.removeAll { hidden(it) }
        entry.permaMods.removeAll { hidden(it) }
    }

    // --- Officers ------------------------------------------------------------------------------

    private fun captureOfficers(
        officers: List<PersonAPI>,
        members: List<FleetMemberAPI>,
        shipIndex: Map<FleetMemberAPI, Int>,
        snapshot: Snapshot,
        warnings: MutableList<String>,
    ) {
        // Who captains what, by person identity: two officers can share a name.
        val shipOfCaptain = IdentityHashMap<PersonAPI, Int>()
        for (member in members) {
            val index = shipIndex[member] ?: continue
            val captain = runCatching { member.captain }.getOrNull() ?: continue
            shipOfCaptain[captain] = index
        }

        val seen = IdentityHashMap<PersonAPI, Boolean>()
        for (person in officers) {
            if (person == null || runCatching { person.isPlayer }.getOrDefault(false)) continue
            seen[person] = true
            snapshot.officers += officerOf(person).also { it.ship = shipOfCaptain[person] ?: -1 }
        }

        for (member in members) {
            val index = shipIndex[member] ?: continue
            val captain = runCatching { member.captain }.getOrNull() ?: continue
            if (seen.containsKey(captain)) continue
            if (runCatching { captain.isPlayer || captain.isDefault }.getOrDefault(true)) continue
            if (runCatching { captain.isAICore }.getOrDefault(false)) {
                snapshot.officers += officerOf(captain).also { it.ship = index }
            } else {
                // A captain who is neither an officer nor an AI core belongs to some quest or mod,
                // which knows that person by id. A copy would be a stranger with their name.
                warnings += "${runCatching { captain.nameString }.getOrDefault("A captain")} on " +
                    "${runCatching { member.shipName }.getOrDefault("a ship")} is not one of your officers " +
                    "and was not copied."
            }
        }
    }

    private fun officerOf(person: PersonAPI): OfficerEntry {
        val stats = person.stats
        return OfficerEntry(
            firstName = runCatching { person.name?.first }.getOrNull().orEmpty(),
            lastName = runCatching { person.name?.last }.getOrNull().orEmpty(),
            gender = runCatching { person.gender?.name }.getOrNull() ?: "ANY",
            portrait = runCatching { person.portraitSprite }.getOrNull().orEmpty(),
            personality = runCatching { person.personalityAPI?.id }.getOrNull() ?: "steady",
            level = runCatching { stats.level }.getOrDefault(1),
            xp = runCatching { stats.xp }.getOrDefault(0L),
            skills = skillsOf(stats, forPlayer = false),
            aiCoreId = if (runCatching { person.isAICore }.getOrDefault(false)) person.aiCoreId.orEmpty() else "",
            mentored = runCatching { Misc.isMentored(person) }.getOrDefault(false),
            exceptional = runCatching {
                person.memoryWithoutUpdate.`is`(MemFlags.EXCEPTIONAL_SLEEPER_POD_OFFICER, true)
            }.getOrDefault(false),
            unremovable = runCatching { Misc.isUnremovable(person) }.getOrDefault(false),
        )
    }

    // --- Character -----------------------------------------------------------------------------

    private fun characterOf(stats: MutableCharacterStatsAPI): CharacterState = CharacterState(
        level = runCatching { stats.level }.getOrDefault(1),
        xp = runCatching { stats.xp }.getOrDefault(0L),
        bonusXp = runCatching { stats.bonusXp }.getOrDefault(0L),
        deferredBonusXp = runCatching { stats.deferredBonusXp }.getOrDefault(0L),
        skillPoints = runCatching { stats.points }.getOrDefault(0),
        storyPoints = runCatching { stats.storyPoints }.getOrDefault(0),
        skills = skillsOf(stats, forPlayer = true),
    )

    /**
     * Every skill with a level, by id. Aptitudes are skipped, they are not skills one can have, and
     * for the player also the skills only characters other than the player can have.
     */
    private fun skillsOf(stats: MutableCharacterStatsAPI, forPlayer: Boolean): MutableMap<String, Float> {
        val out = LinkedHashMap<String, Float>()
        val skills = runCatching { stats.skillsCopy?.toList() }.getOrNull().orEmpty()
        for (skill in skills) {
            val spec = runCatching { skill.skill }.getOrNull() ?: continue
            val level = runCatching { skill.level }.getOrDefault(0f)
            if (level <= 0f) continue
            if (runCatching { spec.isAptitudeEffect }.getOrDefault(false)) continue
            if (forPlayer && !isCopiedPlayerSkill(spec)) continue
            out[spec.id] = level
        }
        return out
    }

    /**
     * Whether a skill of the player's goes into a snapshot. The applier asks the same question
     * before it clears the new character's skills, so it never clears one it cannot give back.
     */
    fun isCopiedPlayerSkill(spec: SkillSpecAPI): Boolean {
        if (runCatching { spec.isAptitudeEffect }.getOrDefault(false)) return false
        if (runCatching { spec.hasTag("npc_only") }.getOrDefault(false)) return false
        return runCatching { spec.governingAptitudeId }.getOrNull() !in SKIPPED_APTITUDES
    }

    // --- Cargo ---------------------------------------------------------------------------------

    private fun cargoOf(cargo: CargoAPI): CargoSpec {
        val spec = CargoSpec()
        val specials = LinkedHashMap<Pair<String, String>, Float>()
        for (stack in cargo.stacksCopy.orEmpty()) {
            if (stack == null || runCatching { stack.isNull }.getOrDefault(true)) continue
            val size = runCatching { stack.size }.getOrDefault(0f)
            if (size <= 0f) continue
            when {
                stack.isCommodityStack -> stack.commodityId?.let { id ->
                    spec.commodities[id] = (spec.commodities[id] ?: 0f) + size
                }
                stack.isWeaponStack -> stack.weaponSpecIfWeapon?.weaponId?.let { id ->
                    spec.weapons[id] = (spec.weapons[id] ?: 0) + size.toInt()
                }
                stack.isFighterWingStack -> stack.fighterWingSpecIfWing?.id?.let { id ->
                    spec.fighters[id] = (spec.fighters[id] ?: 0) + size.toInt()
                }
                stack.isSpecialStack -> stack.specialDataIfSpecial?.let { data ->
                    if (data.id == Items.MODSPEC && !data.data.isNullOrBlank()) {
                        spec.hullModSpecs[data.data] = (spec.hullModSpecs[data.data] ?: 0) + size.toInt()
                    } else if (!data.id.isNullOrBlank()) {
                        val key = data.id to data.data.orEmpty()
                        specials[key] = (specials[key] ?: 0f) + size
                    }
                }
            }
        }
        for ((key, quantity) in specials) {
            val count = quantity.toInt()
            if (count > 0) spec.specials += SpecialItem(key.first, key.second, count)
        }
        return spec
    }

    // --- Standings -----------------------------------------------------------------------------

    /** Every faction's standing with the player, on the editor's -100 to 100 scale. */
    private fun captureStandings(sector: SectorAPI, snapshot: Snapshot) {
        val player = sector.playerFaction ?: return
        for (faction in sector.allFactions.orEmpty()) {
            val id = faction?.id ?: continue
            if (id == Factions.PLAYER) continue
            val value = runCatching { player.getRelationship(id) }.getOrNull() ?: continue
            snapshot.pack.reputations[id] = Math.round(value * 100f)
        }
    }

    // --- Hotbar --------------------------------------------------------------------------------

    private fun hotbarOf(sector: SectorAPI): Hotbar {
        val hotbar = Hotbar(replaceExisting = true)
        runCatching { sector.characterData?.abilities?.let { hotbar.granted.addAll(it.sorted()) } }
        val slotsApi = sector.uiData?.abilitySlotsAPI ?: return hotbar
        val originalBar = runCatching { slotsApi.currBarIndex }.getOrDefault(0)
        try {
            for (bar in 0 until Hotbar.BARS) {
                slotsApi.currBarIndex = bar
                val slots = slotsApi.currSlotsCopy ?: continue
                for (slot in 0 until minOf(Hotbar.SLOTS_PER_BAR, slots.size)) {
                    val live = slots[slot] ?: continue
                    hotbar.slots[bar][slot] = live.abilityId?.takeIf { it.isNotBlank() }
                    hotbar.hyperSlots[bar][slot] = live.inHyperAbilityId?.takeIf { it.isNotBlank() }
                }
            }
        } finally {
            runCatching { slotsApi.currBarIndex = originalBar }
        }
        return hotbar
    }

    // --- Blueprints ----------------------------------------------------------------------------

    private fun blueprintsOf(sector: SectorAPI): Blueprints {
        val faction = sector.playerFaction
        return Blueprints(
            ships = runCatching { faction?.knownShips?.sorted() }.getOrNull().orEmpty().toMutableList(),
            fighters = runCatching { faction?.knownFighters?.sorted() }.getOrNull().orEmpty().toMutableList(),
            weapons = runCatching { faction?.knownWeapons?.sorted() }.getOrNull().orEmpty().toMutableList(),
            hullMods = runCatching { sector.characterData?.hullMods?.sorted() }.getOrNull().orEmpty().toMutableList(),
        )
    }
}
