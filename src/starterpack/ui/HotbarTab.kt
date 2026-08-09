package starterpack.ui

import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import starterpack.catalog.Catalog
import starterpack.catalog.CatalogKind
import starterpack.model.Hotbar

/**
 * The ability hotbar: five bars of ten slots, matching the game's own layout.
 *
 * One bar is shown at a time because that is how the game shows it -- fifty rows at once would be a
 * wall, and you think about the hotbar one bar at a time anyway.
 *
 * Slotting an ability here also *teaches* it. The two are separate in the file format, but making
 * them separate in the UI would mean a hotbar full of abilities that silently do nothing, which is
 * the single most confusing thing this tab could do.
 */
object HotbarTab {

    private const val LABEL_W = 130f

    fun build(host: CustomPanelAPI, width: Float, height: Float) {
        host.scrollRegion(0f, 0f, width, height, "hotbar.body") { body(width - 24f) }
    }

    private fun TooltipMakerAPI.body(inner: Float) {
        val template = SetupPanel.template ?: return
        val hotbar = template.hotbar
        val fieldWidth = minOf(inner, 900f)

        addPara(
            "Anything you slot here is also taught to your character, so the key works the moment the " +
                "campaign starts.",
            Misc.getGrayColor(), 0f,
        )

        // --- Bar selector ---
        addSectionHeading("Bar", Alignment.MID, 10f)
        buttons(fieldWidth, 26f, 4f, (0 until Hotbar.BARS).map { barIndex ->
            val filled = hotbar.slots[barIndex].count { it != null }
            RowButton(
                label = "Bar ${barIndex + 1}${if (filled > 0) "  ($filled)" else ""}",
                toggle = true,
                active = barIndex == SetupPanel.hotbarBar,
            ) {
                SetupPanel.hotbarBar = barIndex
                SetupPanel.markDirty()
            }
        })

        // --- Slots ---
        val bar = SetupPanel.hotbarBar.coerceIn(0, Hotbar.BARS - 1)
        addSectionHeading("Bar ${bar + 1} slots", Alignment.MID, 12f)
        val abilities = Catalog.entries(CatalogKind.ABILITY)
        if (abilities.isEmpty()) {
            addPara(
                "No abilities could be read from the game's data files, so there is nothing to assign.",
                DANGER, 4f,
            )
            return
        }

        for (slot in 0 until Hotbar.SLOTS_PER_BAR) {
            val assigned = hotbar.slots[bar][slot]
            pickerRow(
                "Slot ${slot + 1}",
                assigned?.let { Catalog.nameOf(CatalogKind.ABILITY, it) } ?: "(empty)",
                fieldWidth,
                isEmpty = assigned == null,
                labelWidth = LABEL_W,
                onClear = {
                    SetupPanel.edit {
                        hotbar.slots[bar][slot] = null
                        // The hyperspace override is meaningless without a base ability in the slot,
                        // so clearing one clears both rather than leaving an orphan.
                        hotbar.hyperSlots[bar][slot] = null
                    }
                },
            ) {
                SetupPanel.openPicker("Ability for bar ${bar + 1}, slot ${slot + 1}", abilities, assigned) { entry ->
                    SetupPanel.edit { hotbar.slots[bar][slot] = entry.id }
                }
            }

            // The in-hyperspace override only makes sense once the slot holds something; offering it
            // on an empty slot would be a control that cannot do anything.
            if (assigned != null) {
                val hyper = hotbar.hyperSlots[bar][slot]
                pickerRow(
                    "   in hyperspace",
                    hyper?.let { Catalog.nameOf(CatalogKind.ABILITY, it) } ?: "(same as above)",
                    fieldWidth,
                    isEmpty = hyper == null,
                    labelWidth = LABEL_W,
                    onClear = { SetupPanel.edit { hotbar.hyperSlots[bar][slot] = null } },
                ) {
                    SetupPanel.openPicker(
                        "Hyperspace ability for bar ${bar + 1}, slot ${slot + 1}", abilities, hyper,
                    ) { entry ->
                        SetupPanel.edit { hotbar.hyperSlots[bar][slot] = entry.id }
                    }
                }
            }
        }

        // --- Behaviour ---
        addSectionHeading("Behaviour", Alignment.MID, 14f)
        checkboxRow(
            "Clear the slots I left empty (otherwise the game's defaults stay in them)",
            hotbar.replaceExisting, fieldWidth, 22f, 4f,
        ) {
            SetupPanel.edit { hotbar.replaceExisting = !hotbar.replaceExisting }
        }

        val granted = hotbar.abilitiesToGrant()
        if (granted.isNotEmpty()) {
            addSectionHeading("Abilities this template teaches (${granted.size})", Alignment.MID, 14f)
            addPara(granted.joinToString(", ") { Catalog.nameOf(CatalogKind.ABILITY, it) },
                Misc.getGrayColor(), 4f)
        }
        addSpacer(20f)
    }
}
