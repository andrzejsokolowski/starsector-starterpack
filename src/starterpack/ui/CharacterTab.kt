package starterpack.ui

import com.fs.starfarer.api.campaign.RepLevel
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import starterpack.catalog.Catalog
import starterpack.catalog.CatalogKind
import starterpack.model.Template

/**
 * The character block: money, points, level, faction standings.
 *
 * Credits and level are *optional* in a way the other numbers are not, so they are text fields where
 * blank means "leave whatever the game gave me" rather than steppers with a zero. Points are additive
 * grants and zero genuinely means "grant none", so those are steppers.
 */
object CharacterTab {

    private const val LABEL_W = 260f

    /**
     * Ceiling on the point grants.
     *
     * Not a game limit -- the engine takes whatever it is handed -- and deliberately far above any
     * number anyone would type on purpose. It exists only so the steppers have something to clamp
     * against and so a field of digits cannot overflow an Int; "effectively infinite story points"
     * is a legitimate thing to want and the editor should not argue about it.
     */
    private const val MAX_POINTS = 9_999_999

    /** Five points a press: small enough to land on any of the game's thresholds, which are all multiples of five. */
    private const val REPUTATION_STEP = 5

    fun build(host: CustomPanelAPI, width: Float, height: Float) {
        host.scrollRegion(0f, 0f, width, height, "character.body") { body(width - 24f) }
    }

    private fun TooltipMakerAPI.body(inner: Float) {
        val template = SetupPanel.template ?: return
        val fieldWidth = minOf(inner, 700f)

        addSectionHeading("Money", Alignment.MID, 0f)
        textRow(
            "Credits", template.credits?.toString().orEmpty(), fieldWidth, SetupPanel.bindings,
            labelWidth = LABEL_W, maxChars = 15,
        ) { text ->
            // Blank clears the override entirely rather than setting zero -- "don't touch my money"
            // and "take all my money" are different intentions and both need to be expressible.
            template.credits = text.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
            SetupPanel.markSaveNeeded()
        }
        addPara(
            if (template.credits == null) {
                "Leave blank to keep whatever credits the campaign start gave you."
            } else {
                "Your balance will be set to exactly this."
            },
            Misc.getGrayColor(), 2f,
        )

        addSectionHeading("Points", Alignment.MID, 14f)
        intRow(
            "Skill points", template.skillPoints, fieldWidth, SetupPanel.bindings,
            min = 0, max = MAX_POINTS, step = 1, labelWidth = LABEL_W,
            onStepped = { SetupPanel.markDirty() },
        ) { value ->
            template.skillPoints = value
            SetupPanel.markSaveNeeded()
        }
        addPara(
            "Unspent points, granted on top of what you already have. Spend them in the character " +
                "screen -- the template does not choose skills for you.",
            Misc.getGrayColor(), 2f,
        )

        intRow(
            "Story points", template.storyPoints, fieldWidth, SetupPanel.bindings,
            min = 0, max = MAX_POINTS, step = 1, labelWidth = LABEL_W,
            onStepped = { SetupPanel.markDirty() },
        ) { value ->
            template.storyPoints = value
            SetupPanel.markSaveNeeded()
        }
        addPara(
            "Granted on top of your current story points. Type as many as you like -- %s is a " +
                "perfectly good answer if you would rather not think about accrual.",
            2f, Misc.getGrayColor(), Misc.getHighlightColor(), "99999",
        )

        addSectionHeading("Level", Alignment.MID, 14f)
        textRow(
            "Character level", template.level?.toString().orEmpty(), fieldWidth, SetupPanel.bindings,
            labelWidth = LABEL_W, maxChars = 4,
        ) { text ->
            template.level = text.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
            SetupPanel.markSaveNeeded()
        }
        addPara(
            "Leave blank to keep your current level. Setting it may grant skill points of its own, " +
                "which stack with the grant above.",
            Misc.getGrayColor(), 2f,
        )

        reputation(template, fieldWidth)

        addSectionHeading("Summary", Alignment.MID, 14f)
        addPara("This template will apply: %s", 4f, Misc.getHighlightColor(), template.summary())
        addSpacer(20f)
    }

    /**
     * One value for every faction, then single factions on top of it.
     *
     * The label carries the level the number lands on ("Pirates (Hostile)"), because -50 means
     * nothing at a glance and the level is what the game will show you.
     */
    private fun TooltipMakerAPI.reputation(template: Template, fieldWidth: Float) {
        addSectionHeading("Reputation", Alignment.MID, 14f)
        checkboxRow("Set my standing with every faction", template.allReputation != null, fieldWidth) {
            SetupPanel.edit { it.allReputation = if (it.allReputation == null) 0 else null }
        }
        template.allReputation?.let { value ->
            reputationRow("All factions", value, fieldWidth) { template.allReputation = it }
        }
        addPara(
            if (template.allReputation == null) {
                "Unticked, every faction keeps the standing the campaign start gave you."
            } else {
                "Every faction in the intel screen's faction list. Hidden ones such as the Remnant are " +
                    "left alone; add them below if you want them changed."
            },
            Misc.getGrayColor(), 2f,
        )

        for ((factionId, value) in LinkedHashMap(template.reputations)) {
            reputationRow(
                Catalog.nameOf(CatalogKind.FACTION, factionId), value, fieldWidth,
                onRemove = { SetupPanel.edit { it.reputations.remove(factionId) } },
            ) { template.reputations[factionId] = it }
        }
        buttons(fieldWidth, 24f, 6f, listOf(
            RowButton("Add a faction") {
                val choices = Catalog.entries(CatalogKind.FACTION).filter { !template.reputations.containsKey(it.id) }
                SetupPanel.openPicker("Add a faction", choices) { entry ->
                    // Starts where "all factions" is, so adding one changes nothing until you move it.
                    SetupPanel.edit { it.reputations[entry.id] = it.allReputation ?: 0 }
                }
            },
        ))
        addPara(
            "A faction added here gets its own standing instead of the one above. From %s to %s: " +
                "%s is hostile, %s neutral, %s cooperative.",
            2f, Misc.getGrayColor(), Misc.getHighlightColor(),
            "${Template.MIN_REPUTATION}", "${Template.MAX_REPUTATION}", "-50", "0", "100",
        )
    }

    private fun TooltipMakerAPI.reputationRow(
        label: String,
        value: Int,
        fieldWidth: Float,
        onRemove: (() -> Unit)? = null,
        onChange: (Int) -> Unit,
    ) {
        intRow(
            "$label (${levelName(value)})", value, fieldWidth, SetupPanel.bindings,
            min = Template.MIN_REPUTATION, max = Template.MAX_REPUTATION, step = REPUTATION_STEP,
            labelWidth = LABEL_W, onStepped = { SetupPanel.markDirty() }, onRemove = onRemove,
        ) { changed ->
            onChange(changed)
            SetupPanel.markSaveNeeded()
        }
    }

    private fun levelName(value: Int): String =
        runCatching { RepLevel.getLevelFor(value / 100f).displayName }.getOrDefault("$value")
}
