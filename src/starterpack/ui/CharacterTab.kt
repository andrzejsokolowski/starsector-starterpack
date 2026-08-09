package starterpack.ui

import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc

/**
 * The character block: money, points, level.
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
     * Not a game limit -- the engine will happily take more -- but a typo guard. These are text
     * fields, and there is no meaningful difference between 999 free story points and 99999 except
     * that one of them is what you meant to type.
     */
    private const val MAX_POINTS = 999

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
            "Unspent points, granted on top of what you already have, up to %s. Spend them in the " +
                "character screen -- the template does not choose skills for you.",
            2f, Misc.getGrayColor(), Misc.getHighlightColor(), MAX_POINTS.toString(),
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
            "Granted on top of your current story points, up to %s. A larger number is clamped to " +
                "that when you click away.",
            2f, Misc.getGrayColor(), Misc.getHighlightColor(), MAX_POINTS.toString(),
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

        addSectionHeading("Summary", Alignment.MID, 14f)
        addPara("This template will apply: %s", 4f, Misc.getHighlightColor(), template.summary())
        addSpacer(20f)
    }
}
