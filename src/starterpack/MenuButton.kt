package starterpack

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.CutStyle
import com.fs.starfarer.api.ui.UIPanelAPI
import com.fs.starfarer.api.util.Misc
import starterpack.ui.SetupPanel
import starterpack.uiframework.Button
import starterpack.uiframework.CustomPanel
import starterpack.uiframework.Font
import starterpack.uiframework.TooltipMakerPanel
import starterpack.uiframework.anchorInBottomRightOfParent
import starterpack.uiframework.onClick

/**
 * The main-menu entry point: a single button anchored at the bottom of the title screen, and the
 * open/close lifecycle of the editor it toggles.
 *
 * Injection is driven by [TitleScreenHook], which calls [injectInto] once per screen-panel instance
 * and [forget] when we leave the menu.
 */
object MenuButton {

    private const val BUTTON_WIDTH = 180f
    private const val BUTTON_HEIGHT = 30f

    /**
     * Padding from the screen edge.
     *
     * Bottom-**right**, one button-height up. The right corner is where the author's other
     * title-screen mods live, and the lift keeps this clear of StopBloatingMe's DE-BLOAT button
     * sitting in the corner itself.
     */
    private const val EDGE_PAD_X = 24f
    private const val EDGE_PAD_Y = 24f + BUTTON_HEIGHT + 8f

    /** The button's own container, so we can drop it when the menu is rebuilt. */
    private var buttonPanel: CustomPanelAPI? = null

    /** The open editor panel, or null when closed. */
    private var openPanel: CustomPanelAPI? = null

    /** The screen panel both of the above are parented to. */
    private var host: UIPanelAPI? = null

    fun injectInto(screenPanel: UIPanelAPI) {
        host = screenPanel

        val container = screenPanel.CustomPanel(BUTTON_WIDTH, BUTTON_HEIGHT) { _ ->
            TooltipMakerPanel(BUTTON_WIDTH, BUTTON_HEIGHT) {
                Button(
                    text = "STARTER PACK",
                    baseColor = Misc.getBasePlayerColor(),
                    bgColor = Misc.getDarkPlayerColor(),
                    align = Alignment.MID,
                    style = CutStyle.TL_BR,
                    width = BUTTON_WIDTH,
                    height = BUTTON_HEIGHT,
                    font = Font.ORBITRON_20,
                ) {
                    onClick { toggle(screenPanel) }
                }
            }
        }
        container.anchorInBottomRightOfParent(EDGE_PAD_X, EDGE_PAD_Y)
        buttonPanel = container
    }

    /** Drops our references without touching the panels -- the engine discards the whole tree. */
    fun forget() {
        buttonPanel = null
        openPanel = null
        host = null
        SetupPanel.dispose()
    }

    private fun toggle(screenPanel: UIPanelAPI) {
        if (openPanel != null) close() else open(screenPanel)
    }

    private fun open(screenPanel: UIPanelAPI) {
        openPanel = runCatching { SetupPanel.create(screenPanel) }
            .onFailure {
                Global.getLogger(MenuButton::class.java).error("StarterPack: editor failed to open.", it)
                SetupPanel.dispose()
            }
            .getOrNull()
    }

    fun close() {
        val panel = openPanel ?: return
        openPanel = null
        runCatching { host?.removeComponent(panel) }
        SetupPanel.dispose()
    }
}
