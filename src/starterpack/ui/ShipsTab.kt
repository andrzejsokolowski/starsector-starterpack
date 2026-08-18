package starterpack.ui

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.api.loading.WeaponSlotAPI
import starterpack.bench.BenchState
import starterpack.catalog.Catalog
import starterpack.catalog.CatalogEntry
import starterpack.catalog.CatalogKind
import starterpack.catalog.safeBool
import starterpack.catalog.safeGet
import starterpack.model.ShipEntry
import starterpack.model.Template

/**
 * The fleet builder: a list of ships on the left, the selected ship's complete refit on the right.
 *
 * This is the tab the mod exists for. Everything here edits the template directly and persists on
 * each change, so there is no save step and no way to lose work by closing the window.
 */
object ShipsTab {

    private const val GAP = 12f
    private const val LIST_W = 340f
    private const val LABEL_W = 215f

    fun build(host: CustomPanelAPI, width: Float, height: Float) {
        val rightW = width - LIST_W - GAP
        host.scrollRegion(0f, 0f, LIST_W, height, "ships.list") { buildList(LIST_W - 24f) }
        host.scrollRegion(LIST_W + GAP, 0f, rightW, height, "ships.editor") { buildEditor(rightW - 24f) }
    }

    // --- Left: the fleet -------------------------------------------------------------------------

    private fun TooltipMakerAPI.buildList(inner: Float) {
        val template = SetupPanel.template ?: return
        addSectionHeading("Fleet (${template.ships.size})", Alignment.MID, 0f)

        if (template.ships.isEmpty()) {
            addPara("No ships yet. Add one below.", Misc.getGrayColor(), 8f)
        }

        for ((index, ship) in template.ships.withIndex()) {
            val selected = index == SetupPanel.selectedShip
            val flag = if (ship.flagship) "[F] " else ""
            val label = ship.shipName.ifBlank { Catalog.nameOf(CatalogKind.HULL, ship.hullId) }
            checkboxRow("$flag$label", selected, inner, 24f, 3f) {
                SetupPanel.selectedShip = index
                SetupPanel.markDirty()
            }
        }

        buttons(inner, 26f, 12f, listOf(
            RowButton("Add ship") {
                SetupPanel.openPicker("Choose a hull", Catalog.entries(CatalogKind.HULL)) { entry ->
                    SetupPanel.edit { current ->
                        val ship = ShipEntry(hullId = entry.id)
                        // The first ship added is the one you fly; a fleet with no flagship is a
                        // broken campaign and making you tick a box for the obvious case is noise.
                        ship.flagship = current.ships.none { it.flagship }
                        current.ships += ship
                        SetupPanel.selectedShip = current.ships.size - 1
                    }
                }
            },
        ))

        val selectedIndex = SetupPanel.selectedShip
        val hasSelection = selectedIndex in template.ships.indices
        buttons(inner, 24f, 4f, listOf(
            RowButton("Up", enabled = hasSelection && selectedIndex > 0) {
                SetupPanel.edit { move(it, selectedIndex, -1) }
            },
            RowButton("Down", enabled = hasSelection && selectedIndex < template.ships.size - 1) {
                SetupPanel.edit { move(it, selectedIndex, 1) }
            },
        ))
        buttons(inner, 24f, 4f, listOf(
            RowButton("Duplicate", enabled = hasSelection) {
                SetupPanel.edit { current ->
                    val copy = current.ships[selectedIndex].copy()
                    // Two flagships is not a state the applier honours, so the copy is never one.
                    copy.flagship = false
                    current.ships.add(selectedIndex + 1, copy)
                    SetupPanel.selectedShip = selectedIndex + 1
                }
            },
            RowButton("Remove", color = DANGER, enabled = hasSelection) {
                SetupPanel.edit { current ->
                    current.ships.removeAt(selectedIndex)
                    SetupPanel.selectedShip = selectedIndex.coerceAtMost(current.ships.size - 1)
                        .coerceAtLeast(0)
                }
            },
        ))

        buildBenchSection(inner, template)
    }

    /**
     * The hand-off to the game's own refit screen.
     *
     * The button cannot start the mission itself: the title screen's state machine is obfuscated, and
     * reflecting into it would break on every game patch with a crash on the main menu -- the worst
     * possible place for one. So this stages the bench and says where to click. The return trip needs
     * no button at all, because [starterpack.TitleScreenHook] notices it.
     */
    private fun TooltipMakerAPI.buildBenchSection(inner: Float, template: Template) {
        buttons(inner, 26f, 12f, listOf(
            RowButton("Customize in Refit Screen", enabled = template.ships.isNotEmpty()) {
                BenchState.stage(template)
                SetupPanel.setStatus(
                    "Missions -> \"! StarterPack Refit Bench\" -> REFIT. Start the battle when you " +
                        "are done and your loadouts are saved back here."
                )
            },
        ))

        if (BenchState.awaitingReturn) {
            addPara(
                "Waiting on a refit trip. Open Missions from the main menu.",
                Misc.getHighlightColor(), 4f,
            )
        }
    }

    private fun move(template: Template, index: Int, delta: Int) {
        val target = index + delta
        if (index !in template.ships.indices || target !in template.ships.indices) return
        val ship = template.ships.removeAt(index)
        template.ships.add(target, ship)
        SetupPanel.selectedShip = target
    }

    // --- Right: the refit ------------------------------------------------------------------------

    private fun TooltipMakerAPI.buildEditor(inner: Float) {
        val template = SetupPanel.template ?: return
        val ship = template.ships.getOrNull(SetupPanel.selectedShip)
        if (ship == null) {
            addPara("Add a ship on the left to start building a loadout.", Misc.getGrayColor(), 8f)
            return
        }

        val hullSpec = Catalog.hullSpec(ship.hullId)
        val hullSize = hullSpec?.hullSize

        // --- Identity ---
        addSectionHeading("Ship", Alignment.MID, 0f)
        pickerRow(
            "Hull", Catalog.nameOf(CatalogKind.HULL, ship.hullId), inner,
            isEmpty = ship.hullId.isBlank(), labelWidth = LABEL_W,
        ) {
            SetupPanel.openPicker("Choose a hull", Catalog.entries(CatalogKind.HULL), ship.hullId) { entry ->
                SetupPanel.edit {
                    if (entry.id != ship.hullId) {
                        // Slot ids and bay counts belong to the old hull, so a hull change has to
                        // clear the loadout. Keeping it would silently drop most of it at apply time
                        // and leave the rest in slots that mean something different now.
                        ship.hullId = entry.id
                        ship.weapons.clear()
                        ship.wings.clear()
                        ship.weaponGroups.clear()
                        ship.modules.clear()
                    }
                }
            }
        }
        if (hullSpec == null && ship.hullId.isNotBlank()) {
            addPara(
                "This hull is not in your current mod list. It will be skipped unless you re-enable " +
                    "the mod that adds it.",
                DANGER, 4f,
            )
        }
        textRow("Ship name", ship.shipName, inner, SetupPanel.bindings, labelWidth = LABEL_W) { text ->
            ship.shipName = text
            SetupPanel.markSaveNeeded()
        }
        checkboxRow("Flagship (the ship you fly)", ship.flagship, inner, 22f, 4f) {
            SetupPanel.edit { current ->
                val wanted = !ship.flagship
                // Exactly one flagship: setting this one clears the others rather than leaving a
                // conflict for the applier to break the tie on.
                current.ships.forEach { it.flagship = false }
                ship.flagship = wanted
            }
        }

        if (hullSpec == null) return

        // --- Presets ---
        val stockVariants = stockVariantsFor(ship.hullId)
        if (stockVariants.isNotEmpty()) {
            buttons(inner, 24f, 8f, listOf(
                RowButton("Load a stock loadout (${stockVariants.size})") {
                    SetupPanel.openPicker("Copy a stock loadout onto ${hullSpec.hullName}", stockVariants) { entry ->
                        SetupPanel.edit { copyFromVariant(ship, entry.id) }
                        SetupPanel.setStatus("Copied \"${entry.name}\" onto ${hullSpec.hullName}.")
                    }
                },
                RowButton("Strip everything", color = DANGER) {
                    SetupPanel.edit {
                        ship.weapons.clear()
                        ship.wings.clear()
                        ship.hullMods.clear()
                        ship.sMods.clear()
                        ship.permaMods.clear()
                        ship.dMods.clear()
                        ship.weaponGroups.clear()
                        ship.vents = 0
                        ship.capacitors = 0
                    }
                },
            ))
            addPara(
                "Loading a stock loadout is the fast path: pick the closest vanilla or modded variant, " +
                    "then change what you want.",
                Misc.getGrayColor(), 2f,
            )
        }

        // --- Ordnance ---
        val totalOp = Catalog.baseOrdnancePoints(ship.hullId)
        val usedOp = usedOrdnancePoints(ship, hullSize)
        val overBudget = usedOp > totalOp
        addSectionHeading("Ordnance", Alignment.MID, 12f)
        addPara(
            "Using %s of %s OP",
            2f, if (overBudget) DANGER else Misc.getHighlightColor(),
            formatOp(usedOp), totalOp.toString(),
        )
        if (overBudget) {
            addPara(
                "Over budget. The game does not enforce this on a variant built by script, so the ship " +
                    "will still be created -- but the refit screen will show it as invalid.",
                DANGER, 2f,
            )
        }
        // The refit screen's own cap, which is per hull size rather than per hull. The headroom
        // above the quoted base is the Flux Regulation allowance -- a template is written long
        // before we know which skills the character will have, so a skilled build stays reachable.
        val fluxCap = Catalog.maxFluxUpgrades(hullSize)
        val baseFluxCap = Catalog.baseFluxUpgradeCap(hullSize)
        intRow("Vents", ship.vents, inner, SetupPanel.bindings, max = fluxCap, labelWidth = LABEL_W,
            onStepped = { SetupPanel.markDirty() }) { value ->
            ship.vents = value
            SetupPanel.markSaveNeeded()
        }
        intRow("Capacitors", ship.capacitors, inner, SetupPanel.bindings, max = fluxCap,
            labelWidth = LABEL_W,
            onStepped = { SetupPanel.markDirty() }) { value ->
            ship.capacitors = value
            SetupPanel.markSaveNeeded()
        }
        addPara(
            "A ${Catalog.sizeLabel(hullSize).lowercase()} takes %s of each in refit, or %s with Flux Regulation.",
            2f, Misc.getGrayColor(), Misc.getHighlightColor(),
            baseFluxCap.toString(), fluxCap.toString(),
        )

        // --- Weapons ---
        val slots = Catalog.fittableSlots(ship.hullId)
        addSectionHeading("Weapons (${slots.count { ship.weapons.containsKey(it.id) }}/${slots.size})",
            Alignment.MID, 12f)
        if (slots.isEmpty()) {
            addPara("This hull has no weapon slots you can fit.", Misc.getGrayColor(), 4f)
        }
        val slotLabels = slotLabels(slots)
        for (slot in slots) {
            val fitted = ship.weapons[slot.id]
            val label = slotLabels[slot.id] ?: slot.id
            pickerRow(
                label,
                fitted?.let { Catalog.nameOf(CatalogKind.WEAPON, it) } ?: "(empty)",
                inner,
                isEmpty = fitted == null,
                labelWidth = LABEL_W,
                onClear = { SetupPanel.edit { ship.weapons.remove(slot.id) } },
            ) {
                // Filtered by the engine's own weaponFits, so the list holds only what will actually
                // mount here -- forty candidates instead of eighteen hundred.
                SetupPanel.openPicker(
                    "Choose a weapon for $label",
                    Catalog.weaponsFitting(slot),
                    fitted,
                ) { entry ->
                    SetupPanel.edit { ship.weapons[slot.id] = entry.id }
                }
            }
        }

        // --- Fighters ---
        val bays = Catalog.fittableBayCount(ship.hullId)
        if (bays > 0) {
            addSectionHeading("Fighter bays ($bays)", Alignment.MID, 12f)
            // The model's wing list is positional, so it has to be at least as long as the bay count
            // before a later bay can be addressed.
            while (ship.wings.size < bays) ship.wings.add(null)
            for (bay in 0 until bays) {
                val fitted = ship.wings.getOrNull(bay)
                pickerRow(
                    "Bay ${bay + 1}",
                    fitted?.let { Catalog.nameOf(CatalogKind.FIGHTER, it) } ?: "(empty)",
                    inner,
                    isEmpty = fitted == null,
                    labelWidth = LABEL_W,
                    onClear = { SetupPanel.edit { ship.wings[bay] = null } },
                ) {
                    SetupPanel.openPicker(
                        "Choose a fighter wing for bay ${bay + 1}",
                        Catalog.entries(CatalogKind.FIGHTER),
                        fitted,
                    ) { entry ->
                        SetupPanel.edit { ship.wings[bay] = entry.id }
                    }
                }
            }
        }

        // --- Modules ---
        // Shown, not editable. Which modules a hull takes is fixed by its design -- the refit screen
        // will not swap them either -- so the only thing worth saying here is which ones you are
        // getting, and that they are coming at all.
        val moduleSlots = Catalog.moduleSlotIds(ship.hullId)
        if (moduleSlots.isNotEmpty()) {
            addSectionHeading("Modules (${moduleSlots.size})", Alignment.MID, 12f)
            addPara(
                "This hull carries ships of its own. They are built and handed over with it, and " +
                    "loading a stock loadout brings that variant's modules along with its weapons.",
                Misc.getGrayColor(), 2f,
            )
            val defaultModules = Catalog.defaultModules(ship.hullId)
            for ((index, slotId) in moduleSlots.withIndex()) {
                val moduleId = ship.modules[slotId]?.takeIf { it.isNotBlank() } ?: defaultModules[slotId]
                if (moduleId == null) {
                    addPara("Module ${index + 1}: nothing in your mod list fits this slot.", DANGER, 2f)
                } else {
                    addPara("Module ${index + 1}: %s", 2f, Misc.getHighlightColor(), Catalog.moduleName(moduleId))
                }
            }
        }

        // --- Hullmods ---
        modSection(
            inner, "Hullmods", ship.hullMods, hullSize,
            emptyNote = "Regular hullmods. These cost ordnance points and can be removed in refit.",
            pickerTitle = "Add a hullmod",
            entries = { Catalog.entries(CatalogKind.HULLMOD) },
            showCost = true,
        )

        modSection(
            inner, "S-mods (built in, count against the limit)", ship.sMods, hullSize,
            emptyNote = "Permanent, free of ordnance points, and counted against the ship's built-in limit " +
                "exactly as spending a story point in refit would.",
            pickerTitle = "Add an S-mod",
            entries = { Catalog.entries(CatalogKind.HULLMOD).filter { canBuildIn(it.id) } },
            showCost = false,
        )

        modSection(
            inner, "Built-in (free, no S-mod limit)", ship.permaMods, hullSize,
            emptyNote = "Permanent and free, but NOT counted as S-mods -- the ship keeps its full " +
                "story-point allowance. There is no way to get this in a normal game.",
            pickerTitle = "Add a free built-in hullmod",
            entries = { Catalog.entries(CatalogKind.HULLMOD).filter { canBuildIn(it.id) } },
            showCost = false,
        )

        modSection(
            inner, "D-mods", ship.dMods, hullSize,
            emptyNote = "Adding any D-mod also swaps the hull to its (D) version, exactly as battle " +
                "damage does.",
            pickerTitle = "Add a D-mod",
            entries = { Catalog.entries(CatalogKind.DMOD) },
            showCost = false,
        )
    }

    // --- Sections --------------------------------------------------------------------------------

    /**
     * One list-of-hullmods section: a row per installed mod with a remove cross, then an add button.
     *
     * The four lists differ only in where they are stored and what the applier does with them, so
     * they share this rather than being copy-pasted four times with subtly different behaviour.
     */
    private fun TooltipMakerAPI.modSection(
        inner: Float,
        heading: String,
        mods: MutableList<String>,
        hullSize: ShipAPI.HullSize?,
        emptyNote: String,
        pickerTitle: String,
        entries: () -> List<CatalogEntry>,
        showCost: Boolean,
    ) {
        addSectionHeading("$heading (${mods.size})", Alignment.MID, 12f)
        addPara(emptyNote, Misc.getGrayColor(), 2f)

        for (modId in ArrayList(mods)) {
            val name = Catalog.nameOf(CatalogKind.HULLMOD, modId)
            val cost = if (showCost) Catalog.hullModCost(modId, hullSize) else 0
            val label = if (showCost && cost > 0) "$name  ($cost OP)" else name
            pickerRow(
                "", label, inner,
                labelWidth = 8f,
                onClear = { SetupPanel.edit { mods.remove(modId) } },
            ) {
                // Clicking the mod itself swaps it for another, which is what you want far more often
                // than removing and re-adding.
                SetupPanel.openPicker(pickerTitle, entries(), modId) { entry ->
                    SetupPanel.edit {
                        val index = mods.indexOf(modId)
                        if (index >= 0 && entry.id !in mods) mods[index] = entry.id
                    }
                }
            }
        }

        buttons(inner, 24f, 4f, listOf(
            RowButton("Add") {
                SetupPanel.openPicker(pickerTitle, entries()) { entry ->
                    SetupPanel.edit { if (entry.id !in mods) mods += entry.id }
                }
            },
        ))
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private fun canBuildIn(modId: String): Boolean =
        Catalog.hullModSpec(modId)?.let { Catalog.canBuildIn(it) } ?: false

    /**
     * Human names for a hull's weapon slots, keyed by slot id.
     *
     * The raw slot id (`WS0001`) means nothing to anyone building a loadout -- it is an internal
     * handle, and the refit screen never shows it. What actually distinguishes two slots to a player
     * is size, mount type and *which one of the several identical ones* it is, so that is what this
     * produces: "Medium Ballistic turret 2".
     *
     * The number is only added when a hull has more than one slot of that exact kind; a ship with a
     * single large hardpoint gets "Large Ballistic hardpoint", not "... 1".
     */
    private fun slotLabels(slots: List<WeaponSlotAPI>): Map<String, String> {
        fun kindOf(slot: WeaponSlotAPI): String {
            val size = slot.safeGet { slotSize?.displayName }.orEmpty()
            val type = slot.safeGet { weaponType?.displayName }.orEmpty()
                .lowercase().replaceFirstChar { it.uppercase() }
            val mount = when {
                slot.safeBool { isHardpoint } -> "hardpoint"
                slot.safeBool { isTurret } -> "turret"
                else -> ""
            }
            return listOf(size, type, mount).filter { it.isNotBlank() }.joinToString(" ")
        }

        val totals = HashMap<String, Int>()
        for (slot in slots) totals[kindOf(slot)] = (totals[kindOf(slot)] ?: 0) + 1

        val seen = HashMap<String, Int>()
        val out = LinkedHashMap<String, String>()
        for (slot in slots) {
            val kind = kindOf(slot)
            val index = (seen[kind] ?: 0) + 1
            seen[kind] = index
            val name = kind.ifBlank { slot.id }
            out[slot.id] = if ((totals[kind] ?: 0) > 1) "$name $index" else name
        }
        return out
    }

    /**
     * What the loadout costs.
     *
     * Computed from specs rather than by building a real variant: the editor runs at the main menu
     * where there is no character, and `computeOPCost` wants character stats. Skill bonuses are
     * therefore not included, which makes this the *base* cost -- pessimistic, and pessimistic is the
     * right direction for a budget display.
     */
    private fun usedOrdnancePoints(ship: ShipEntry, hullSize: ShipAPI.HullSize?): Float {
        var used = (ship.vents + ship.capacitors).toFloat()
        for (weaponId in ship.weapons.values) {
            used += Catalog.lookup(CatalogKind.WEAPON, weaponId)?.opCost ?: 0f
        }
        for (wingId in ship.wings.filterNotNull()) {
            used += Catalog.lookup(CatalogKind.FIGHTER, wingId)?.opCost ?: 0f
        }
        // Only regular hullmods cost OP. S-mods, free built-ins and D-mods are all free by
        // construction, which is most of why you would use them here.
        for (modId in ship.hullMods) {
            used += Catalog.hullModCost(modId, hullSize).toFloat()
        }
        return used
    }

    private fun formatOp(op: Float): String =
        if (op % 1f == 0f) op.toInt().toString() else String.format("%.1f", op)

    /**
     * Every stock variant defined for a hull, as picker entries.
     *
     * Built on demand rather than cached in [Catalog] because it is per-hull and only ever consulted
     * when you press the button.
     */
    private fun stockVariantsFor(hullId: String): List<CatalogEntry> {
        if (hullId.isBlank()) return emptyList()
        val ids = runCatching {
            Global.getSettings().hullIdToVariantListMap?.getList(hullId).orEmpty()
        }.getOrDefault(emptyList())

        return ids.mapNotNull { variantId ->
            val variant = runCatching { Global.getSettings().getVariant(variantId) }.getOrNull()
                ?: return@mapNotNull null
            CatalogEntry(
                id = variantId,
                name = variant.displayName.orEmpty().ifBlank { variantId },
                primary = "${variant.fittedWeaponSlots?.size ?: 0} weapons",
                secondary = "${variant.numFluxVents}v / ${variant.numFluxCapacitors}c",
                sourceMod = variantId,
                sprite = variant.hullSpec?.spriteName.orEmpty(),
            )
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * Copies a stock variant's loadout into the template entry.
     *
     * Only non-built-in content is copied: built-in weapons, wings and hullmods come from the hull
     * itself and are re-applied automatically when the variant is rebuilt, so copying them would
     * either duplicate them or fail to install into slots that are not ours to fill.
     */
    private fun copyFromVariant(ship: ShipEntry, variantId: String) {
        val variant: ShipVariantAPI = runCatching { Global.getSettings().getVariant(variantId) }
            .getOrNull() ?: return

        ship.weapons.clear()
        ship.wings.clear()
        ship.hullMods.clear()
        ship.sMods.clear()
        ship.permaMods.clear()
        ship.weaponGroups.clear()
        ship.modules.clear()

        // Modules are part of what a stock variant *is* -- two variants of one modular hull can carry
        // different ones -- so they come across with the loadout rather than being left to the
        // applier's default.
        val moduleSlots = Catalog.moduleSlotIds(ship.hullId)
        if (moduleSlots.isNotEmpty()) {
            ship.modules.putAll(Catalog.modulesOf(variant).filterKeys { it in moduleSlots })
        }

        for (slotId in Catalog.fittableSlots(ship.hullId).map { it.id }) {
            runCatching { variant.getWeaponId(slotId) }.getOrNull()?.let { ship.weapons[slotId] = it }
        }

        val hullSpec = Catalog.hullSpec(ship.hullId)
        if (hullSpec != null) {
            val fittableBays = (0 until hullSpec.fighterBays)
                .filter { runCatching { !hullSpec.isBuiltInWing(it) }.getOrDefault(true) }
            for (bayIndex in fittableBays) {
                ship.wings += runCatching { variant.getWingId(bayIndex) }.getOrNull()?.ifBlank { null }
            }
        }

        val sMods = runCatching { variant.sMods.orEmpty().toSet() }.getOrDefault(emptySet())
        val permaMods = runCatching { variant.permaMods.orEmpty().toSet() }.getOrDefault(emptySet())
        for (modId in runCatching { variant.nonBuiltInHullmods.orEmpty() }.getOrDefault(emptyList())) {
            when {
                modId in sMods -> ship.sMods += modId
                modId in permaMods -> ship.permaMods += modId
                else -> ship.hullMods += modId
            }
        }

        ship.vents = runCatching { variant.numFluxVents }.getOrDefault(0)
        ship.capacitors = runCatching { variant.numFluxCapacitors }.getOrDefault(0)
    }
}
