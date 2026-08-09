package starterpack.ui

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.CutStyle
import com.fs.starfarer.api.ui.TextFieldAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import com.fs.starfarer.api.util.Misc
import org.lwjgl.opengl.GL11
import starterpack.uiframework.CustomPanel
import starterpack.uiframework.Font
import starterpack.uiframework.TooltipMakerPanel
import starterpack.uiframework.anchorInTopLeftOfParent
import starterpack.uiframework.bottom
import starterpack.uiframework.drawBorder
import starterpack.uiframework.getFontPath
import starterpack.uiframework.left
import starterpack.uiframework.onClick
import starterpack.uiframework.right
import starterpack.uiframework.top
import java.awt.Color

/**
 * The small vocabulary every tab is built from.
 *
 * The editor's content is tens of rows, not thousands, so everything here rebuilds wholesale rather
 * than being pooled and rebound. That is a deliberate trade: a full rebuild per click is invisible at
 * this size and removes an entire class of stale-view bugs that a mutable widget tree would invite.
 * The one place it would not hold -- the 1,800-entry weapon list -- is handled by capping the picker
 * instead, see [PickerOverlay].
 */

val DANGER: Color = Color(235, 90, 90)
val GOOD: Color = Color(120, 220, 130)

// --- Layout ------------------------------------------------------------------------------------

/**
 * Adds a scrolling element the way the engine expects: **create, fill, then add**.
 *
 * The DSL's `TooltipMakerPanel` calls `addUIElement` before running its builder, so the scroller is
 * wired up against content that is still empty and ends up with no scroll range at all. The overflow
 * then draws straight past the panel edge, because Starsector panels don't clip -- only scrollers do.
 * Filling before adding is what vanilla does everywhere and what makes it work.
 */
fun CustomPanelAPI.scrollingElement(
    width: Float,
    height: Float,
    builder: TooltipMakerAPI.() -> Unit,
): TooltipMakerAPI {
    val element = createUIElement(width, height, true)
    element.builder()
    addUIElement(element).inTL(0f, 0f)
    return element
}

/** A plain, non-scrolling element filling the panel. */
fun CustomPanelAPI.staticElement(
    width: Float,
    height: Float,
    builder: TooltipMakerAPI.() -> Unit,
): TooltipMakerAPI = TooltipMakerPanel(width, height, false, builder)

// --- Buttons -----------------------------------------------------------------------------------

/**
 * A toggle-looking button used for tabs and list selection; [active] draws it checked.
 *
 * Deliberately uses the default checkbox font. `setAreaCheckboxFont` takes a font *path*, and only
 * the handful of fonts the engine has already loaded resolve through it -- anything else leaves the
 * label with a null font and the next `setText` throws deep inside the renderer. Buttons escape this
 * because the framework maps them to named setters like `setButtonFontOrbitron20()` instead of a
 * path, which is why a custom-font button works and a custom-font checkbox does not.
 */
fun UIPanelAPI.tabButton(
    x: Float, y: Float, width: Float, height: Float,
    label: String, active: Boolean,
    onPress: () -> Unit,
) {
    CustomPanel(width, height) {
        TooltipMakerPanel(width, height) {
            addAreaCheckbox(
                label, null,
                Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Misc.getBrightPlayerColor(),
                width, height, 0f,
            ).apply {
                isChecked = active
                onClick(onPress)
            }
        }
    }.anchorInTopLeftOfParent(x, y)
}

fun UIPanelAPI.plainButton(
    x: Float, y: Float, width: Float, height: Float,
    label: String,
    color: Color = Misc.getBasePlayerColor(),
    enabled: Boolean = true,
    onPress: () -> Unit,
) {
    CustomPanel(width, height) {
        TooltipMakerPanel(width, height) {
            addButton(
                label, null, color, Misc.getDarkPlayerColor(),
                Alignment.MID, CutStyle.TL_BR, width, height, 0f,
            ).apply {
                isEnabled = enabled
                onClick(onPress)
            }
        }
    }.anchorInTopLeftOfParent(x, y)
}

/** A button inside a flowing [TooltipMakerAPI], for use in scrolling column layouts. */
fun TooltipMakerAPI.flowButton(
    label: String,
    width: Float,
    height: Float = 24f,
    pad: Float = 4f,
    color: Color = Misc.getBasePlayerColor(),
    enabled: Boolean = true,
    onPress: () -> Unit,
): ButtonAPI = addButton(
    label, null, color, Misc.getDarkPlayerColor(),
    Alignment.MID, CutStyle.TL_BR, width, height, pad,
).apply {
    isEnabled = enabled
    onClick(onPress)
}

fun TooltipMakerAPI.checkboxRow(
    label: String,
    checked: Boolean,
    width: Float,
    height: Float = 22f,
    pad: Float = 4f,
    onToggle: () -> Unit,
): ButtonAPI = addAreaCheckbox(
    label, null,
    Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Misc.getBrightPlayerColor(),
    width, height, pad, true,
).apply {
    isChecked = checked
    onClick(onToggle)
}

/** [onPress] is last so a call site can pass it as a trailing lambda. */
class RowButton(
    val label: String,
    val color: Color = Misc.getBasePlayerColor(),
    val enabled: Boolean = true,
    /** Draw as a checkbox-style toggle rather than a push button. */
    val toggle: Boolean = false,
    val active: Boolean = false,
    val onPress: () -> Unit,
)

/**
 * Lays buttons out left-to-right in a fixed-width strip.
 *
 * [TooltipMakerAPI] only flows vertically, so a row of buttons has to be a positioned panel added
 * through `addCustom`.
 */
fun buttonRow(width: Float, height: Float, entries: List<RowButton>): CustomPanelAPI {
    val panel = Global.getSettings().createCustom(width, height, null)
    if (entries.isEmpty()) return panel
    val gap = 4f
    val each = (width - (entries.size - 1) * gap) / entries.size
    var x = 0f
    for (entry in entries) {
        if (entry.toggle) {
            panel.tabButton(x, 0f, each, height, entry.label, entry.active, onPress = entry.onPress)
        } else {
            panel.plainButton(x, 0f, each, height, entry.label, entry.color, entry.enabled, entry.onPress)
        }
        x += each + gap
    }
    return panel
}

/** [buttonRow] added straight into a flowing element. */
fun TooltipMakerAPI.buttons(width: Float, height: Float, pad: Float, entries: List<RowButton>) {
    addCustom(buttonRow(width, height, entries), pad)
}

// --- Composite rows ----------------------------------------------------------------------------

/**
 * A labelled slot: fixed-width label, a wide button showing the current value, and an optional clear
 * cross. This is the workhorse of the whole editor -- every hull, weapon, wing, hullmod and ability
 * choice is one of these.
 *
 * [value] is drawn greyed when [isEmpty], so an unfilled slot reads as unfilled at a glance rather
 * than as a thing called "(empty)".
 */
fun TooltipMakerAPI.pickerRow(
    label: String,
    value: String,
    width: Float,
    isEmpty: Boolean = false,
    labelWidth: Float = 150f,
    pad: Float = 3f,
    height: Float = 22f,
    onClear: (() -> Unit)? = null,
    onPick: () -> Unit,
) {
    val clearWidth = if (onClear != null) 24f else 0f
    val gap = 4f
    val buttonWidth = (width - labelWidth - clearWidth - gap * 2).coerceAtLeast(60f)

    val panel = Global.getSettings().createCustom(width, height, null)
    panel.CustomPanel(labelWidth, height) {
        TooltipMakerPanel(labelWidth, height) {
            // A space rather than "": an empty para can come back sized to nothing, which throws the
            // rest of the row's layout out. Unlabelled rows (the hullmod lists) rely on this.
            addPara(label.ifBlank { " " }, Misc.getGrayColor(), 2f)
        }
    }.anchorInTopLeftOfParent(0f, 0f)

    panel.plainButton(
        labelWidth + gap, 0f, buttonWidth, height,
        value,
        color = if (isEmpty) Misc.getGrayColor() else Misc.getBasePlayerColor(),
        onPress = onPick,
    )
    onClear?.let { clear ->
        panel.plainButton(
            labelWidth + gap + buttonWidth + gap, 0f, clearWidth, height,
            "X", color = DANGER, enabled = !isEmpty, onPress = clear,
        )
    }
    addCustom(panel, pad)
}

/**
 * A labelled number with -/+ steppers and an editable field.
 *
 * The field is bound through [FieldBindings] rather than read on submit, because Starsector text
 * fields have no change callback -- the only way to notice an edit is to poll, and the panel is
 * already ticking.
 */
fun TooltipMakerAPI.intRow(
    label: String,
    value: Int,
    width: Float,
    bindings: FieldBindings,
    min: Int = 0,
    max: Int = Int.MAX_VALUE,
    step: Int = 1,
    labelWidth: Float = 150f,
    pad: Float = 3f,
    /**
     * Run after a stepper press, but *not* after a keystroke.
     *
     * Steppers change the number the field is displaying, so the view has to be rebuilt to show it.
     * Typing must not rebuild -- recreating the field mid-word would drop the caret and make the box
     * unusable -- so the text binding commits to the model and leaves the view alone.
     */
    onStepped: () -> Unit = {},
    /** Adds a trailing remove cross. Used by the cargo lists, where a row can be deleted outright. */
    onRemove: (() -> Unit)? = null,
    onChange: (Int) -> Unit,
) {
    val height = 22f
    val gap = 4f
    val stepWidth = 26f
    val removeWidth = if (onRemove != null) 24f + gap else 0f
    val fieldWidth =
        (width - labelWidth - stepWidth * 2 - gap * 3 - removeWidth).coerceIn(50f, 140f)

    val panel = Global.getSettings().createCustom(width, height, null)
    panel.CustomPanel(labelWidth, height) {
        TooltipMakerPanel(labelWidth, height) { addPara(label, Misc.getGrayColor(), 2f) }
    }.anchorInTopLeftOfParent(0f, 0f)

    panel.plainButton(labelWidth + gap, 0f, stepWidth, height, "-", enabled = value > min) {
        onChange((value.toLong() - step).coerceIn(min.toLong(), max.toLong()).toInt())
        onStepped()
    }

    var field: TextFieldAPI? = null
    panel.CustomPanel(fieldWidth, height) {
        TooltipMakerPanel(fieldWidth, height) {
            field = addTextField(fieldWidth, height, getFontPath(Font.VICTOR_14), 0f).apply {
                text = value.toString()
                maxChars = 12
            }
        }
    }.anchorInTopLeftOfParent(labelWidth + gap + stepWidth + gap, 0f)

    val plusX = labelWidth + gap + stepWidth + gap + fieldWidth + gap
    panel.plainButton(plusX, 0f, stepWidth, height, "+") {
        onChange((value.toLong() + step).coerceIn(min.toLong(), max.toLong()).toInt())
        onStepped()
    }
    onRemove?.let { remove ->
        panel.plainButton(plusX + stepWidth + gap, 0f, 24f, height, "X", color = DANGER, onPress = remove)
    }

    field?.let { textField ->
        bindings.bind(textField) { text ->
            // Blank and garbage both mean "the user is mid-edit"; only commit a parseable number, and
            // never rewrite the field here -- that would fight the caret.
            val parsed = text.trim().toLongOrNull() ?: return@bind
            val clamped = parsed.coerceIn(min.toLong(), max.toLong()).toInt()
            if (clamped != value) onChange(clamped)
        }
    }
    addCustom(panel, pad)
}

/** A labelled free-text field, committed through [FieldBindings] on each change. */
fun TooltipMakerAPI.textRow(
    label: String,
    value: String,
    width: Float,
    bindings: FieldBindings,
    labelWidth: Float = 150f,
    pad: Float = 3f,
    maxChars: Int = 64,
    onChange: (String) -> Unit,
) {
    val height = 22f
    val gap = 4f
    val fieldWidth = (width - labelWidth - gap).coerceAtLeast(80f)

    val panel = Global.getSettings().createCustom(width, height, null)
    panel.CustomPanel(labelWidth, height) {
        TooltipMakerPanel(labelWidth, height) { addPara(label, Misc.getGrayColor(), 2f) }
    }.anchorInTopLeftOfParent(0f, 0f)

    var field: TextFieldAPI? = null
    panel.CustomPanel(fieldWidth, height) {
        TooltipMakerPanel(fieldWidth, height) {
            field = addTextField(fieldWidth, height, getFontPath(Font.VICTOR_14), 0f).apply {
                text = value
                this.maxChars = maxChars
            }
        }
    }.anchorInTopLeftOfParent(labelWidth + gap, 0f)

    field?.let { textField -> bindings.bind(textField) { onChange(it) } }
    addCustom(panel, pad)
}

// --- Chrome ------------------------------------------------------------------------------------

/** Opaque body plus a border, so a panel reads as a window over the drifting title screen. */
fun drawBackdrop(panel: CustomPanelAPI, alpha: Float, dim: Float = 0.35f) {
    val dark = Misc.getDarkPlayerColor()
    GL11.glDisable(GL11.GL_TEXTURE_2D)
    GL11.glColor4f(
        dark.red / 255f * dim, dark.green / 255f * dim, dark.blue / 255f * dim,
        0.97f * alpha,
    )
    GL11.glRectf(panel.left, panel.bottom, panel.right, panel.top)
    val base = Misc.getBasePlayerColor()
    GL11.glColor4f(base.red / 255f, base.green / 255f, base.blue / 255f, alpha)
    drawBorder(panel.left, panel.top, panel.right, panel.bottom)
}

/**
 * Polls text fields and pushes changes into the model.
 *
 * Starsector's [TextFieldAPI] exposes no listener, so an edit can only be noticed by comparing the
 * text to what it was last frame. Every rebuilt view registers its fields here and the panel's tick
 * walks them; [clear] on rebuild drops the old fields so a stale one can never write into the model
 * after the widget that owned it is gone.
 */
class FieldBindings {

    private class Binding(val field: TextFieldAPI, val onChange: (String) -> Unit) {
        var last: String = field.text.orEmpty()
    }

    private val bindings = ArrayList<Binding>()

    fun bind(field: TextFieldAPI, onChange: (String) -> Unit) {
        bindings += Binding(field, onChange)
    }

    fun clear() = bindings.clear()

    /** True if any field changed this frame, so the caller can decide whether to rebuild. */
    fun tick(): Boolean {
        var changed = false
        // Snapshot: an onChange may rebuild the view, which clears the live list mid-iteration.
        for (binding in ArrayList(bindings)) {
            val current = runCatching { binding.field.text.orEmpty() }.getOrDefault(binding.last)
            if (current != binding.last) {
                binding.last = current
                changed = true
                binding.onChange(current)
            }
        }
        return changed
    }

    /** Whether the caret is in one of our fields, so keyboard shortcuts can stand down. */
    fun anyFocused(): Boolean = bindings.any { runCatching { it.field.hasFocus() }.getOrDefault(false) }
}
