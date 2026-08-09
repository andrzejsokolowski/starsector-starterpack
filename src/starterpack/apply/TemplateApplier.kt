package starterpack.apply

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.CargoAPI
import com.fs.starfarer.api.campaign.PersistentUIDataAPI.AbilitySlotsAPI
import com.fs.starfarer.api.campaign.SpecialItemData
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.fleet.FleetMemberType
import com.fs.starfarer.api.impl.campaign.DModManager
import com.fs.starfarer.api.impl.campaign.ids.Factions
import com.fs.starfarer.api.loading.VariantSource
import com.fs.starfarer.api.loading.WeaponGroupSpec
import com.fs.starfarer.api.loading.WeaponGroupType
import starterpack.catalog.Catalog
import starterpack.model.CargoSpec
import starterpack.model.Hotbar
import starterpack.model.ShipEntry
import starterpack.model.Template

/**
 * What an apply actually did, so the console and the auto-apply log can say something specific.
 *
 * Warnings are collected rather than thrown. A template naming one weapon from a mod you turned off
 * should still give you the other eleven ships -- reporting the gap is far more useful than refusing
 * the whole operation, and the ids are printed so you can see exactly what went missing.
 */
class ApplyResult(
    val shipsAdded: Int = 0,
    val mothballedAdded: Int = 0,
    val cargoStacks: Int = 0,
    val abilitiesGranted: Int = 0,
    val hotbarSlots: Int = 0,
    val warnings: List<String> = emptyList(),
    val failure: String? = null,
) {
    val succeeded: Boolean get() = failure == null

    fun describe(): String {
        failure?.let { return it }
        val parts = ArrayList<String>(5)
        if (shipsAdded > 0) parts += "$shipsAdded ship${if (shipsAdded == 1) "" else "s"}"
        if (mothballedAdded > 0) parts += "$mothballedAdded mothballed"
        if (cargoStacks > 0) parts += "$cargoStacks cargo stacks"
        if (abilitiesGranted > 0) parts += "$abilitiesGranted abilities"
        if (hotbarSlots > 0) parts += "$hotbarSlots hotbar slots"
        return if (parts.isEmpty()) "nothing to apply" else parts.joinToString(", ")
    }
}

/**
 * Stamps a [Template] onto the running campaign.
 *
 * ## Once per campaign
 *
 * A flag in the sector's persistent data records that a pack was applied. Applying twice would
 * double the credits and re-add every ship, which in a mod whose entire job is "start from a known
 * state" is never what you meant. [apply] refuses on an already-stamped save unless `force` is set,
 * and the flag lives in the save rather than in the template store so that two campaigns from the
 * same template are independent.
 *
 * ## Ids are validated, never assumed
 *
 * Every id is resolved through [Catalog] and a miss becomes a warning, not an exception. Templates
 * are shared between mod lists and hand-edited; treating an unknown id as fatal would make the
 * feature fragile in exactly the situation it exists for.
 *
 * ## Order matters
 *
 * The fleet is built before cargo so that mothballed hulls land in a hold that has already been
 * cleared, and abilities are granted before hotbar slots are written because the engine drops a slot
 * assignment for an ability the character does not know.
 */
object TemplateApplier {

    /** Key in `sector.getPersistentData()`. Presence, not value, is what marks the save. */
    private const val APPLIED_KEY = "\$starterpack_applied"

    private val log = Global.getLogger(TemplateApplier::class.java)

    /** Makes every generated variant id distinct within a process. See [buildVariant]. */
    private var variantCounter = 0

    fun hasBeenApplied(): Boolean =
        Global.getSector()?.persistentData?.containsKey(APPLIED_KEY) == true

    /** Which template stamped this save, or null if none has. */
    fun appliedTemplateName(): String? =
        Global.getSector()?.persistentData?.get(APPLIED_KEY) as? String

    fun apply(template: Template, force: Boolean = false): ApplyResult {
        val sector = Global.getSector()
            ?: return ApplyResult(failure = "No campaign is running.")
        if (hasBeenApplied() && !force) {
            val previous = appliedTemplateName() ?: "a template"
            return ApplyResult(
                failure = "This campaign already had \"$previous\" applied. Add 'force' to apply anyway."
            )
        }

        val fleet = sector.playerFleet
            ?: return ApplyResult(failure = "No player fleet to apply to.")

        val warnings = ArrayList<String>()
        var shipsAdded = 0
        var mothballedAdded = 0

        // --- Fleet ---------------------------------------------------------------------------
        // Never disband into nothing. A fleet with no ships is a campaign you cannot play, and a
        // template that only sets credits should not be able to produce one by leaving replaceFleet
        // at its default.
        if (template.replaceFleet && template.ships.isNotEmpty()) clearFleet(fleet)

        var flagshipAssigned = false
        for (entry in template.ships) {
            val member = buildMember(entry, warnings) ?: continue
            fleet.fleetData.addFleetMember(member)
            shipsAdded++
            // Only the first flagged entry wins; a template with two flagships is a hand-edit slip,
            // and silently promoting the last one would be surprising.
            if (entry.flagship && !flagshipAssigned) {
                fleet.fleetData.setFlagship(member)
                flagshipAssigned = true
            }
        }
        // A fleet with no flagship is a broken campaign -- the game expects a ship you are flying.
        if (!flagshipAssigned) {
            fleet.fleetData.membersListCopy.firstOrNull()?.let { fleet.fleetData.setFlagship(it) }
        }

        // --- Cargo ---------------------------------------------------------------------------
        val cargo = fleet.cargo
        if (template.replaceCargo && cargo != null) clearCargo(cargo)

        var cargoStacks = 0
        if (cargo != null) {
            cargoStacks = applyCargo(cargo, template.cargo, warnings)
            for (entry in template.mothballed) {
                val member = buildMember(entry, warnings) ?: continue
                if (addMothballed(cargo, member)) mothballedAdded++
            }
        }

        // --- Character -----------------------------------------------------------------------
        template.credits?.let { cargo?.credits?.set(it.toFloat()) }

        val stats = sector.playerPerson?.stats
        if (stats != null) {
            // Level first: the engine grants points of its own when the level goes up, and our
            // explicit grant should sit on top of that rather than be swallowed by it.
            template.level?.let { level -> runCatching { stats.level = level } }
            if (template.skillPoints > 0) runCatching { stats.addPoints(template.skillPoints) }
            if (template.storyPoints > 0) runCatching { stats.addStoryPoints(template.storyPoints) }
        } else {
            warnings += "No player character; skill points, story points and level were skipped."
        }

        // --- Abilities and hotbar ------------------------------------------------------------
        val abilitiesGranted = grantAbilities(template.hotbar, warnings)
        val hotbarSlots = applyHotbar(template.hotbar, warnings)

        runCatching { fleet.forceSync() }
        sector.persistentData[APPLIED_KEY] = template.name

        val result = ApplyResult(
            shipsAdded = shipsAdded,
            mothballedAdded = mothballedAdded,
            cargoStacks = cargoStacks,
            abilitiesGranted = abilitiesGranted,
            hotbarSlots = hotbarSlots,
            warnings = warnings,
        )
        log.info("StarterPack: applied \"${template.name}\" -- ${result.describe()}")
        warnings.forEach { log.warn("StarterPack: $it") }
        return result
    }

    // --- Fleet ---------------------------------------------------------------------------------

    private fun clearFleet(fleet: CampaignFleetAPI) {
        // Copy first: removing while iterating the live list is how you drop every other ship.
        for (member in fleet.fleetData.membersListCopy) {
            runCatching { fleet.fleetData.removeFleetMember(member) }
        }
    }

    private fun buildMember(entry: ShipEntry, warnings: MutableList<String>): FleetMemberAPI? {
        val variant = buildVariant(entry, warnings) ?: return null
        val member = runCatching {
            Global.getFactory().createFleetMember(FleetMemberType.SHIP, variant)
        }.getOrElse {
            warnings += "Could not create a fleet member for hull '${entry.hullId}': ${it.message}"
            return null
        }
        if (entry.shipName.isNotBlank()) runCatching { member.shipName = entry.shipName }
        entry.combatReadiness?.let { cr -> runCatching { member.repairTracker.cr = cr.coerceIn(0f, 1f) } }
        return member
    }

    /**
     * Turns one [ShipEntry] into a live variant.
     *
     * The D-hull swap happens before anything else is installed, because it replaces the hull spec
     * outright -- doing it afterwards would discard the weapons and mods already fitted. That is the
     * same order vanilla uses when a recovered wreck gets its damage.
     */
    private fun buildVariant(entry: ShipEntry, warnings: MutableList<String>): ShipVariantAPI? {
        val hullSpec = Catalog.hullSpec(entry.hullId)
        if (hullSpec == null) {
            warnings += "Unknown hull '${entry.hullId}' -- ship skipped."
            return null
        }

        // Unique per built ship: two Onslaughts in one template would otherwise share a variant id,
        // and an id is the handle the game uses to tell one refit variant from another.
        val variantId = "${entry.hullId}_starterpack_${variantCounter++}"
        val variant = runCatching {
            Global.getSettings().createEmptyVariant(variantId, hullSpec)
        }.getOrElse {
            warnings += "Could not create a variant for hull '${entry.hullId}': ${it.message}"
            return null
        }
        variant.source = VariantSource.REFIT

        val validDMods = entry.dMods.filter { id ->
            (Catalog.hullModSpec(id) != null).also {
                if (!it) warnings += "Unknown D-mod '$id' on '${entry.hullId}' -- skipped."
            }
        }
        if (validDMods.isNotEmpty()) runCatching { DModManager.setDHull(variant) }

        // --- Weapons ---
        for ((slotId, weaponId) in entry.weapons) {
            if (Catalog.weaponSpec(weaponId) == null) {
                warnings += "Unknown weapon '$weaponId' on '${entry.hullId}' -- slot $slotId left empty."
                continue
            }
            runCatching { variant.addWeapon(slotId, weaponId) }.onFailure {
                warnings += "Could not fit '$weaponId' into slot $slotId on '${entry.hullId}'."
            }
        }

        // --- Fighters ---
        // An empty variant has no wing list until the hull's built-ins are installed, and
        // setWingId writes by index into that list. Doing this first is what makes bay assignment
        // land rather than fall out of range.
        runCatching { variant.refreshBuiltInWings() }

        // Template bay N means "the Nth bay the player can actually fill". Built-in wings own their
        // bay permanently, so the indices the engine wants are the non-built-in ones, in order.
        val fittableBayIndices = (0 until (hullSpec.fighterBays))
            .filter { index -> runCatching { !hullSpec.isBuiltInWing(index) }.getOrDefault(true) }
        for ((position, wingId) in entry.wings.withIndex()) {
            if (wingId == null) continue
            val bayIndex = fittableBayIndices.getOrNull(position)
            if (bayIndex == null) {
                warnings += "'${entry.hullId}' has no bay ${position + 1} -- wing '$wingId' skipped."
                continue
            }
            if (Catalog.wingSpec(wingId) == null) {
                warnings += "Unknown fighter wing '$wingId' on '${entry.hullId}' -- bay left empty."
                continue
            }
            runCatching { variant.setWingId(bayIndex, wingId) }.onFailure {
                warnings += "Could not fit wing '$wingId' into bay ${position + 1} on '${entry.hullId}'."
            }
        }

        // --- Hullmods ---
        for (modId in entry.hullMods) {
            if (Catalog.hullModSpec(modId) == null) {
                warnings += "Unknown hullmod '$modId' on '${entry.hullId}' -- skipped."
                continue
            }
            runCatching { variant.addMod(modId) }
        }
        // S-mods and free built-ins differ only in the isSMod flag: both are permanent and cost no
        // OP, but only an S-mod counts against the ship's built-in limit and shows the S-mod bonus.
        for (modId in entry.sMods) {
            if (Catalog.hullModSpec(modId) == null) {
                warnings += "Unknown S-mod '$modId' on '${entry.hullId}' -- skipped."
                continue
            }
            runCatching { variant.addPermaMod(modId, true) }
        }
        for (modId in entry.permaMods) {
            if (Catalog.hullModSpec(modId) == null) {
                warnings += "Unknown built-in hullmod '$modId' on '${entry.hullId}' -- skipped."
                continue
            }
            runCatching { variant.addPermaMod(modId, false) }
        }
        for (modId in validDMods) {
            runCatching {
                // Mirrors DModManager: un-suppress first, in case the D-hull we just swapped to
                // already carries this mod in a suppressed state.
                variant.removeSuppressedMod(modId)
                variant.addPermaMod(modId, false)
            }
        }

        // --- Flux ---
        runCatching {
            variant.numFluxVents = entry.vents.coerceAtLeast(0)
            variant.numFluxCapacitors = entry.capacitors.coerceAtLeast(0)
        }

        // --- Weapon groups ---
        applyWeaponGroups(variant, entry)

        return variant
    }

    /**
     * Installs the template's weapon groups, or lets the engine work them out.
     *
     * Auto-generation is the default because hand-maintained groups rot the moment you change a
     * weapon, and the engine's grouping is what the refit screen would have given you anyway. An
     * explicit group list is honoured verbatim, with any weapon it forgot swept into a group at the
     * end -- an unassigned weapon is one you cannot fire.
     */
    private fun applyWeaponGroups(variant: ShipVariantAPI, entry: ShipEntry) {
        if (entry.weaponGroups.isEmpty()) {
            runCatching { variant.autoGenerateWeaponGroups() }
            return
        }
        runCatching {
            for (group in entry.weaponGroups) {
                val spec = WeaponGroupSpec(WeaponGroupType.LINKED)
                spec.isAutofireOnByDefault = group.autofire
                group.slots.filter { variant.getWeaponId(it) != null }.forEach { spec.addSlot(it) }
                if (spec.slots.isNotEmpty()) variant.addWeaponGroup(spec)
            }
            if (variant.hasUnassignedWeapons()) variant.assignUnassignedWeapons()
        }.onFailure {
            runCatching { variant.autoGenerateWeaponGroups() }
        }
    }

    /**
     * Puts a built ship into the hold as a mothballed hull.
     *
     * `CargoAPI.addMothballedShip` only takes a variant *id*, which cannot name a variant we built at
     * runtime, so the member goes straight into the mothballed fleet data instead. That list needs a
     * faction before it can be touched, and on a brand-new campaign it may not have one yet.
     */
    private fun addMothballed(cargo: CargoAPI, member: FleetMemberAPI): Boolean {
        // Unconditional and defensive: getMothballedShips() is documented as requiring init first and
        // is free to throw rather than return null, so there is nothing safe to test beforehand.
        // Initialising an already-initialised list is a no-op.
        runCatching { cargo.initMothballedShips(Factions.PLAYER) }
        return runCatching {
            member.repairTracker.isMothballed = true
            cargo.mothballedShips.addFleetMember(member)
            true
        }.getOrDefault(false)
    }

    // --- Cargo ---------------------------------------------------------------------------------

    private fun clearCargo(cargo: CargoAPI) {
        runCatching { cargo.clear() }
    }

    private fun applyCargo(cargo: CargoAPI, spec: CargoSpec, warnings: MutableList<String>): Int {
        var stacks = 0

        for ((id, quantity) in spec.commodities) {
            if (quantity <= 0f) continue
            if (Global.getSettings().getCommoditySpec(id) == null) {
                warnings += "Unknown commodity '$id' -- skipped."
                continue
            }
            runCatching { cargo.addCommodity(id, quantity); stacks++ }
        }
        for ((id, count) in spec.weapons) {
            if (count <= 0) continue
            if (Catalog.weaponSpec(id) == null) {
                warnings += "Unknown weapon '$id' in cargo -- skipped."
                continue
            }
            runCatching { cargo.addWeapons(id, count); stacks++ }
        }
        for ((id, count) in spec.fighters) {
            if (count <= 0) continue
            if (Catalog.wingSpec(id) == null) {
                warnings += "Unknown fighter wing '$id' in cargo -- skipped."
                continue
            }
            runCatching { cargo.addFighters(id, count); stacks++ }
        }
        for ((id, count) in spec.hullModSpecs) {
            if (count <= 0) continue
            if (Catalog.hullModSpec(id) == null) {
                warnings += "Unknown hullmod spec '$id' in cargo -- skipped."
                continue
            }
            runCatching { cargo.addHullmods(id, count); stacks++ }
        }
        for (item in spec.specials) {
            if (item.quantity <= 0 || item.id.isBlank()) continue
            if (Global.getSettings().getSpecialItemSpec(item.id) == null) {
                warnings += "Unknown special item '${item.id}' -- skipped."
                continue
            }
            runCatching {
                cargo.addSpecial(SpecialItemData(item.id, item.data.ifBlank { null }), item.quantity.toFloat())
                stacks++
            }
        }
        return stacks
    }

    // --- Abilities and hotbar ------------------------------------------------------------------

    /**
     * Teaches the player every ability the template needs.
     *
     * This has to happen before slots are written: the engine treats a slot holding an ability the
     * character does not know as empty, so the order is not cosmetic.
     */
    private fun grantAbilities(hotbar: Hotbar, warnings: MutableList<String>): Int {
        val characterData = Global.getSector()?.characterData ?: return 0
        val known = Catalog.entries(starterpack.catalog.CatalogKind.ABILITY).map { it.id }.toSet()
        var granted = 0
        for (abilityId in hotbar.abilitiesToGrant()) {
            // The catalogue comes from abilities.csv, so an id missing from it is genuinely undefined
            // rather than merely unusual -- adding it would leave a dead entry in the character data.
            if (known.isNotEmpty() && abilityId !in known) {
                warnings += "Unknown ability '$abilityId' -- not granted."
                continue
            }
            runCatching {
                characterData.addAbility(abilityId)
                // Vanilla's AddAbility rule command sets this alongside the grant; some content
                // checks the memory flag rather than the ability set.
                characterData.memoryWithoutUpdate?.set("\$ability:$abilityId", true, 0f)
                granted++
            }
        }
        return granted
    }

    /**
     * Writes the hotbar.
     *
     * The engine exposes one bar at a time through [AbilitySlotsAPI], so this walks the bars, writing
     * each and restoring the player's original bar at the end. `getCurrSlotsCopy()` returns copies of
     * the *list*, but the [com.fs.starfarer.api.campaign.PersistentUIDataAPI.AbilitySlotAPI] objects
     * in it are live -- setting an id on one is what vanilla's own AddAbility does.
     */
    private fun applyHotbar(hotbar: Hotbar, warnings: MutableList<String>): Int {
        val slotsApi: AbilitySlotsAPI = Global.getSector()?.uiData?.abilitySlotsAPI ?: run {
            if (hotbar.assignedCount() > 0) warnings += "No ability bar available; hotbar was skipped."
            return 0
        }
        if (hotbar.assignedCount() == 0 && !hotbar.replaceExisting) return 0

        var written = 0
        val originalBar = runCatching { slotsApi.currBarIndex }.getOrDefault(0)
        runCatching {
            for (barIndex in 0 until Hotbar.BARS) {
                slotsApi.currBarIndex = barIndex
                val liveSlots = slotsApi.currSlotsCopy ?: continue
                for (slotIndex in 0 until minOf(Hotbar.SLOTS_PER_BAR, liveSlots.size)) {
                    val slot = liveSlots[slotIndex] ?: continue
                    val wanted = hotbar.slots[barIndex][slotIndex]
                    val wantedHyper = hotbar.hyperSlots[barIndex][slotIndex]

                    if (wanted == null) {
                        // Only blank a slot we were told to own. Without replaceExisting, a template
                        // that sets three slots leaves whatever the game put in the other seven.
                        if (hotbar.replaceExisting) {
                            slot.abilityId = null
                            slot.inHyperAbilityId = null
                        }
                        continue
                    }
                    slot.abilityId = wanted
                    slot.inHyperAbilityId = wantedHyper
                    written++
                }
            }
        }.onFailure {
            warnings += "Hotbar could not be fully written: ${it.message}"
        }
        runCatching { slotsApi.currBarIndex = originalBar }
        return written
    }
}
