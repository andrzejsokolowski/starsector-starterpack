package starterpack.ui

import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.util.Misc
import starterpack.model.Template
import starterpack.store.TemplateStore

/**
 * Template management: which packs exist, which one the console and auto-apply use, and the switches
 * that decide whether a new game gets stamped at all.
 *
 * Split left/right rather than one column because the list and the editing controls are used
 * together -- you rename the thing you just clicked, and having the list vanish under a form would
 * cost a click every time.
 */
object TemplatesTab {

    private const val GAP = 12f
    private const val LIST_W = 440f

    /**
     * Deleting a template destroys work no save can give back, so the button arms on the first click
     * and only fires on a second one inside this window.
     */
    private const val DELETE_ARM_MS = 4_000L
    private var deleteArmedAt = 0L
    private var deleteArmedFor: Template? = null

    fun build(host: CustomPanelAPI, width: Float, height: Float) {
        val rightW = width - LIST_W - GAP
        host.scrollRegion(0f, 0f, LIST_W, height, "templates.list") { buildList(LIST_W - 24f) }
        host.scrollRegion(LIST_W + GAP, 0f, rightW, height, "templates.details") {
            buildDetails(rightW - 24f)
        }
    }

    // --- Left: the list ------------------------------------------------------------------------

    private fun com.fs.starfarer.api.ui.TooltipMakerAPI.buildList(inner: Float) {
        addSectionHeading("Templates", Alignment.MID, 0f)

        val templates = TemplateStore.all()
        if (templates.isEmpty()) {
            addPara(
                "No templates yet. Create one, then build the fleet you want every test to start with.",
                Misc.getGrayColor(), 8f,
            )
        }

        for (template in templates) {
            val isEditing = template === SetupPanel.template
            val isActive = TemplateStore.activeName().equals(template.name, ignoreCase = true)
            // A leading star marks the one the console and auto-apply will use, which is not
            // necessarily the one you are editing.
            val marker = if (isActive) "* " else ""
            checkboxRow("$marker${template.name}", isEditing, inner, 24f, 5f) {
                SetupPanel.selectTemplate(template)
            }
            addPara(template.summary(), Misc.getGrayColor(), 1f)
        }

        buttons(inner, 26f, 12f, listOf(
            RowButton("New template") {
                SetupPanel.selectTemplate(TemplateStore.create())
                SetupPanel.setStatus("Created a new template.")
            },
        ))
    }

    // --- Right: details and switches -------------------------------------------------------------

    private fun com.fs.starfarer.api.ui.TooltipMakerAPI.buildDetails(inner: Float) {
        val template = SetupPanel.template
        if (template == null) {
            addPara("Select a template on the left, or create one to get started.", Misc.getGrayColor(), 8f)
            return
        }

        addSectionHeading("Selected template", Alignment.MID, 0f)

        textRow("Name", template.name, inner, SetupPanel.bindings, maxChars = 48) { text ->
            // Renaming through the store, not the field: it keeps the "active" pointer attached to
            // this template and resolves collisions, both of which a raw assignment would miss.
            TemplateStore.rename(template, text)
            SetupPanel.markSaveNeeded()
        }
        addPara("Contents: %s", 6f, Misc.getHighlightColor(), template.summary())

        val isActive = TemplateStore.activeName().equals(template.name, ignoreCase = true)
        val armed = isDeleteArmed(template)
        buttons(inner, 26f, 10f, listOf(
            RowButton(if (isActive) "Active" else "Make active", enabled = !isActive) {
                TemplateStore.setActive(template)
                SetupPanel.setStatus("\"${template.name}\" is now the active template.")
            },
            RowButton("Duplicate") {
                val copy = TemplateStore.duplicate(template)
                SetupPanel.selectTemplate(copy)
                SetupPanel.setStatus("Duplicated as \"${copy.name}\".")
            },
            RowButton(
                if (armed) "CONFIRM DELETE" else "Delete",
                color = if (armed) DANGER else Misc.getBasePlayerColor(),
            ) {
                if (isDeleteArmed(template)) {
                    val name = template.name
                    TemplateStore.delete(template)
                    deleteArmedAt = 0L
                    deleteArmedFor = null
                    SetupPanel.selectTemplate(TemplateStore.all().firstOrNull())
                    SetupPanel.setStatus("Deleted \"$name\".")
                } else {
                    deleteArmedAt = System.currentTimeMillis()
                    deleteArmedFor = template
                    SetupPanel.markDirty()
                }
            },
        ))
        if (armed) addPara("Click again to delete. Cancels itself in a few seconds.", DANGER, 4f)

        addSectionHeading("How it gets applied", Alignment.MID, 14f)
        checkboxRow(
            "Apply the active template automatically when a new game starts",
            TemplateStore.isAutoApplyEnabled(), inner, 22f, 6f,
        ) {
            TemplateStore.setAutoApply(!TemplateStore.isAutoApplyEnabled())
            SetupPanel.markDirty()
        }
        addPara(
            "With this off, run %s in the console once your new campaign has started. Either way it " +
                "applies once per save -- add %s to re-apply.",
            6f, Misc.getGrayColor(), Misc.getHighlightColor(),
            "starterpack apply", "force",
        )

        addSectionHeading("What gets overwritten", Alignment.MID, 14f)
        checkboxRow(
            "Disband the starting fleet before adding these ships",
            template.replaceFleet, inner, 22f, 4f,
        ) {
            SetupPanel.edit { it.replaceFleet = !it.replaceFleet }
        }
        checkboxRow(
            "Empty the hold before adding this cargo",
            template.replaceCargo, inner, 22f, 4f,
        ) {
            SetupPanel.edit { it.replaceCargo = !it.replaceCargo }
        }
        addPara(
            "Turn these off to add to what the game gave you instead of replacing it.",
            Misc.getGrayColor(), 4f,
        )
    }

    private fun isDeleteArmed(template: Template): Boolean =
        deleteArmedFor === template &&
            deleteArmedAt != 0L &&
            System.currentTimeMillis() - deleteArmedAt < DELETE_ARM_MS
}
