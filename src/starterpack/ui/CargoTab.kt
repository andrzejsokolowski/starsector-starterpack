package starterpack.ui

import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import starterpack.catalog.Catalog
import starterpack.catalog.CatalogKind
import starterpack.model.SpecialItem

/**
 * What lands in the hold: commodities, loose weapons, fighter LPCs, hullmod blueprints and special
 * items.
 *
 * One column rather than a split view -- unlike ships, a cargo entry has nothing to edit beyond its
 * quantity, so there is no detail pane to put anywhere.
 */
object CargoTab {

    private const val LABEL_W = 300f

    /** Steps chosen per kind: you buy supplies by the hundred and AI cores one at a time. */
    private const val COMMODITY_STEP = 50
    private const val ITEM_STEP = 1

    fun build(host: CustomPanelAPI, width: Float, height: Float) {
        host.scrollRegion(0f, 0f, width, height, "cargo.body") { body(width - 24f) }
    }

    private fun TooltipMakerAPI.body(inner: Float) {
        val template = SetupPanel.template ?: return
        val cargo = template.cargo

        addPara(
            "Everything here is added to the hold when the template is applied. Whether the existing " +
                "hold is emptied first is set on the TEMPLATES tab.",
            Misc.getGrayColor(), 0f,
        )

        // --- Commodities ---
        addSectionHeading("Commodities (${cargo.commodities.size})", Alignment.MID, 12f)
        addPara(
            "Supplies, fuel, crew, marines, metals -- anything a market trades.",
            Misc.getGrayColor(), 2f,
        )
        for ((id, quantity) in LinkedHashMap(cargo.commodities)) {
            intRow(
                Catalog.nameOf(CatalogKind.COMMODITY, id), quantity.toInt(), inner, SetupPanel.bindings,
                min = 0, max = 1_000_000, step = COMMODITY_STEP, labelWidth = LABEL_W,
                onStepped = { SetupPanel.markDirty() },
                onRemove = { SetupPanel.edit { cargo.commodities.remove(id) } },
            ) { value ->
                cargo.commodities[id] = value.toFloat()
                SetupPanel.markSaveNeeded()
            }
        }
        addRowButton(inner, "Add a commodity", CatalogKind.COMMODITY) { id ->
            SetupPanel.edit { cargo.commodities.putIfAbsent(id, 100f) }
        }

        // --- Weapons ---
        addSectionHeading("Loose weapons (${cargo.weapons.size})", Alignment.MID, 12f)
        addPara("Spare weapons in the hold, not mounted on anything.", Misc.getGrayColor(), 2f)
        for ((id, count) in LinkedHashMap(cargo.weapons)) {
            intRow(
                Catalog.nameOf(CatalogKind.WEAPON, id), count, inner, SetupPanel.bindings,
                min = 0, max = 9_999, step = ITEM_STEP, labelWidth = LABEL_W,
                onStepped = { SetupPanel.markDirty() },
                onRemove = { SetupPanel.edit { cargo.weapons.remove(id) } },
            ) { value ->
                cargo.weapons[id] = value
                SetupPanel.markSaveNeeded()
            }
        }
        addRowButton(inner, "Add a weapon", CatalogKind.WEAPON) { id ->
            SetupPanel.edit { cargo.weapons.putIfAbsent(id, 1) }
        }

        // --- Fighters ---
        addSectionHeading("Fighter LPCs (${cargo.fighters.size})", Alignment.MID, 12f)
        addPara("Spare wings in the hold, not fitted to a bay.", Misc.getGrayColor(), 2f)
        for ((id, count) in LinkedHashMap(cargo.fighters)) {
            intRow(
                Catalog.nameOf(CatalogKind.FIGHTER, id), count, inner, SetupPanel.bindings,
                min = 0, max = 9_999, step = ITEM_STEP, labelWidth = LABEL_W,
                onStepped = { SetupPanel.markDirty() },
                onRemove = { SetupPanel.edit { cargo.fighters.remove(id) } },
            ) { value ->
                cargo.fighters[id] = value
                SetupPanel.markSaveNeeded()
            }
        }
        addRowButton(inner, "Add a fighter wing", CatalogKind.FIGHTER) { id ->
            SetupPanel.edit { cargo.fighters.putIfAbsent(id, 1) }
        }

        // --- Hullmod blueprints ---
        addSectionHeading("Hullmod blueprints (${cargo.hullModSpecs.size})", Alignment.MID, 12f)
        addPara(
            "The items you install to learn a hullmod. Note this teaches the mod -- it does not put it " +
                "on a ship; that is done per ship on the SHIPS tab.",
            Misc.getGrayColor(), 2f,
        )
        for ((id, count) in LinkedHashMap(cargo.hullModSpecs)) {
            intRow(
                Catalog.nameOf(CatalogKind.HULLMOD, id), count, inner, SetupPanel.bindings,
                min = 0, max = 999, step = ITEM_STEP, labelWidth = LABEL_W,
                onStepped = { SetupPanel.markDirty() },
                onRemove = { SetupPanel.edit { cargo.hullModSpecs.remove(id) } },
            ) { value ->
                cargo.hullModSpecs[id] = value
                SetupPanel.markSaveNeeded()
            }
        }
        addRowButton(inner, "Add a hullmod blueprint", CatalogKind.HULLMOD) { id ->
            SetupPanel.edit { cargo.hullModSpecs.putIfAbsent(id, 1) }
        }

        // --- Special items ---
        addSectionHeading("Special items (${cargo.specials.size})", Alignment.MID, 12f)
        addPara(
            "AI cores, colony items and blueprints. Some carry a sub-id -- a ship blueprint needs the " +
                "hull id, for instance -- which goes in the data box.",
            Misc.getGrayColor(), 2f,
        )
        for (item in ArrayList(cargo.specials)) {
            intRow(
                Catalog.nameOf(CatalogKind.SPECIAL_ITEM, item.id), item.quantity, inner,
                SetupPanel.bindings,
                min = 0, max = 999, step = ITEM_STEP, labelWidth = LABEL_W,
                onStepped = { SetupPanel.markDirty() },
                onRemove = { SetupPanel.edit { cargo.specials.remove(item) } },
            ) { value ->
                item.quantity = value
                SetupPanel.markSaveNeeded()
            }
            textRow("   data (optional)", item.data, inner, SetupPanel.bindings, labelWidth = LABEL_W) { text ->
                item.data = text.trim()
                SetupPanel.markSaveNeeded()
            }
        }
        addRowButton(inner, "Add a special item", CatalogKind.SPECIAL_ITEM) { id ->
            SetupPanel.edit { cargo.specials += SpecialItem(id = id, quantity = 1) }
        }

        addSpacer(20f)
    }

    /**
     * The "Add a …" button under each section.
     *
     * Entries already in the list are filtered out of the picker: adding a duplicate would either be
     * a no-op or silently overwrite the quantity you set, and neither reads as an answer to the
     * click.
     */
    private fun TooltipMakerAPI.addRowButton(
        inner: Float,
        label: String,
        kind: CatalogKind,
        onPick: (String) -> Unit,
    ) {
        buttons(inner, 24f, 6f, listOf(
            RowButton(label) {
                SetupPanel.openPicker(label, Catalog.entries(kind).filter { !alreadyHas(kind, it.id) }) {
                    onPick(it.id)
                }
            },
        ))
    }

    private fun alreadyHas(kind: CatalogKind, id: String): Boolean {
        val cargo = SetupPanel.template?.cargo ?: return false
        return when (kind) {
            CatalogKind.COMMODITY -> cargo.commodities.containsKey(id)
            CatalogKind.WEAPON -> cargo.weapons.containsKey(id)
            CatalogKind.FIGHTER -> cargo.fighters.containsKey(id)
            CatalogKind.HULLMOD -> cargo.hullModSpecs.containsKey(id)
            // Special items can legitimately repeat with different data (two ship blueprints), so
            // they are never filtered out.
            else -> false
        }
    }
}
