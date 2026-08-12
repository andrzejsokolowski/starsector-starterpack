package starterpack.ui

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import com.fs.starfarer.api.util.Misc
import org.lwjgl.input.Keyboard
import starterpack.MenuButton
import starterpack.bench.BenchState
import starterpack.catalog.CatalogEntry
import starterpack.model.Template
import starterpack.store.TemplateStore
import starterpack.uiframework.CustomPanel
import starterpack.uiframework.Font
import starterpack.uiframework.TooltipMakerPanel
import starterpack.uiframework.anchorInCenterOfParent
import starterpack.uiframework.anchorInTopLeftOfParent
import starterpack.uiframework.clearChildren
import kotlin.math.max
import kotlin.math.min

/** The editor's top-level sections. Order here is the order of the tab strip. */
enum class Tab(val label: String) {
    TEMPLATES("TEMPLATES"),
    SHIPS("SHIPS"),
    CARGO("CARGO"),
    CHARACTER("CHARACTER"),
    HOTBAR("HOTBAR"),
}

/**
 * The starter-pack editor: one window on the title screen, five tabs, one template being edited.
 *
 * ## Rebuild, don't mutate
 *
 * Any structural change -- picking a hull, adding a hullmod, switching tabs -- calls [markDirty] and
 * the whole content area is rebuilt on the next tick. At this size (tens of rows) a rebuild is free,
 * and it means no widget can ever show a value the model no longer holds. The one thing that must
 * *not* trigger a rebuild is a keystroke in a text field, which would drop the caret mid-word; those
 * commit straight to the model and are covered by [markSaveNeeded] instead.
 *
 * ## Nothing here touches a sector
 *
 * The editor runs at the main menu, where no campaign exists. Everything it reads comes from specs
 * and from [TemplateStore]; the only code that touches a live game is
 * [starterpack.apply.TemplateApplier], which runs much later.
 */
object SetupPanel {

    // --- Layout ------------------------------------------------------------------------------

    private const val PAD = 12f
    private const val HEADER_H = 38f
    private const val FOOTER_H = 34f
    private const val TAB_GAP = 6f
    private const val CLOSE_W = 100f

    // --- Live panel state --------------------------------------------------------------------

    private var root: CustomPanelAPI? = null
    private var headerHost: CustomPanelAPI? = null
    private var contentHost: CustomPanelAPI? = null
    private var footerHost: CustomPanelAPI? = null

    /** The modal picker, parented to [root] above everything else. Null when nothing is being picked. */
    private var overlay: CustomPanelAPI? = null

    private var panelWidth = 0f
    private var panelHeight = 0f
    private var contentWidth = 0f
    private var contentHeight = 0f

    /** Bindings for the text fields the current content owns. Cleared on every rebuild. */
    val bindings = FieldBindings()

    private var dirty = false
    private var saveNeeded = false

    /**
     * Whether the caret was in one of our text fields last frame.
     *
     * Typing must not rebuild the view -- recreating a field mid-word drops the caret -- so a typed
     * value reaches the model but nothing that *derives* from it (the OP budget, a clamped maximum)
     * updates. Watching for the caret leaving gives a natural commit point: click away, or press
     * Enter, and the view catches up.
     */
    private var wasFieldFocused = false

    /**
     * Where each named scrolling region was left.
     *
     * Every structural edit rebuilds the whole content area, and a list that jumps back to the top
     * each time you tick a hullmod would be unusable. Keyed by region rather than by tab so the ship
     * list and the ship editor keep independent positions.
     */
    internal val scrollOffsets = HashMap<String, Float>()

    // --- Editor state --------------------------------------------------------------------------

    var tab: Tab = Tab.TEMPLATES
        private set

    /** The template being edited. Null only when the store is empty. */
    var template: Template? = null
        private set

    /** Index into [Template.ships] of the ship the SHIPS tab is editing. */
    var selectedShip: Int = 0

    /** Which of the five hotbars the HOTBAR tab is showing. */
    var hotbarBar: Int = 0

    /** Transient message shown in the footer, e.g. after a delete. */
    private var status: String = ""

    // --- Construction ----------------------------------------------------------------------------

    fun create(screenPanel: UIPanelAPI): CustomPanelAPI {
        val settings = Global.getSettings()
        val width = min(1560f, settings.screenWidth - 80f)
        val height = min(940f, settings.screenHeight - 80f)

        panelWidth = width
        panelHeight = height
        contentWidth = width - PAD * 2
        contentHeight = height - PAD * 2 - HEADER_H - FOOTER_H

        // Pick up whatever the store considers current, so opening the editor lands you where the
        // console would act.
        template = TemplateStore.active() ?: TemplateStore.all().firstOrNull()
        selectedShip = 0
        hotbarBar = 0
        // A refit bench trip is imported on the main menu, where nothing can be shown. Opening the
        // editor is the first chance to report it, so the parked result becomes the opening status.
        status = BenchState.lastImport?.let { report ->
            BenchState.lastImport = null
            (listOf(report.summary()) + report.warnings).joinToString("  ")
        }.orEmpty()
        scrollOffsets.clear()

        val panel = screenPanel.CustomPanel(width, height) { plugin ->
            plugin.renderBelow { alpha -> drawBackdrop(plugin.customPanel, alpha) }
            plugin.advance { tick() }
            plugin.onKeyDown { event ->
                when (event.eventValue) {
                    // Escape closes the picker first, then the window -- the innermost thing wins,
                    // which is what every other modal in the game does.
                    Keyboard.KEY_ESCAPE -> if (overlay != null) closePicker() else MenuButton.close()
                    // Enter is the other way people finish typing a number. Starsector text fields
                    // keep focus through it, so without this the ordnance budget would sit stale
                    // until you happened to click elsewhere.
                    Keyboard.KEY_RETURN, Keyboard.KEY_NUMPADENTER ->
                        if (bindings.anyFocused()) markDirty()
                }
            }

            headerHost = CustomPanel(contentWidth, HEADER_H) {}
                .also { it.anchorInTopLeftOfParent(PAD, PAD) }

            contentHost = CustomPanel(contentWidth, contentHeight) {}
                .also { it.anchorInTopLeftOfParent(PAD, PAD + HEADER_H) }

            footerHost = CustomPanel(contentWidth, FOOTER_H) {}
                .also { it.anchorInTopLeftOfParent(PAD, PAD + HEADER_H + contentHeight) }
        }
        panel.anchorInCenterOfParent()
        root = panel

        // Each section is built independently. The panel is already in the UI tree by this point, so
        // an exception escaping here would leave an empty bordered box on screen with no way to tell
        // what went wrong -- which is exactly what a bad font call once produced. Failing one section
        // at a time keeps the rest usable and puts the cause in the log.
        buildSafely("header") { buildHeader() }
        buildSafely("content") { rebuildContent() }
        buildSafely("footer") { buildFooter() }
        return panel
    }

    private fun buildSafely(what: String, block: () -> Unit) {
        runCatching(block).onFailure {
            Global.getLogger(SetupPanel::class.java)
                .error("StarterPack: could not build the editor's $what.", it)
            status = "Part of the editor ($what) failed to build -- see starsector.log."
        }
    }

    fun dispose() {
        // Any pending text edit is committed on the way out; the alternative is losing the last thing
        // you typed because you closed the window instead of clicking away first.
        if (saveNeeded) runCatching { TemplateStore.flush() }
        saveNeeded = false

        root = null
        headerHost = null
        contentHost = null
        footerHost = null
        overlay = null
        bindings.clear()
        scrollOffsets.clear()
        dirty = false
        PickerOverlay.reset()
    }

    // --- Per-frame -------------------------------------------------------------------------------

    private fun tick() {
        // Text edits commit to the model as you type but only reach disk once you click away, so a
        // long name is one write rather than one per character.
        if (bindings.tick()) markSaveNeeded()

        val fieldFocused = bindings.anyFocused()
        if (wasFieldFocused && !fieldFocused) {
            // The caret just left a field. Everything derived from what was typed -- the ordnance
            // budget, a value that got clamped to its maximum -- is stale until the view is rebuilt,
            // and rebuilding was unsafe while the field had focus.
            markDirty()
        }
        wasFieldFocused = fieldFocused

        if (saveNeeded && !fieldFocused) {
            saveNeeded = false
            runCatching { TemplateStore.flush() }
        }

        PickerOverlay.tick()

        if (dirty) {
            dirty = false
            buildSafely("content") { rebuildContent() }
            buildSafely("header") { buildHeader() }
            buildSafely("footer") { buildFooter() }
        }
    }

    /** Request a full content rebuild on the next tick. Safe to call from inside a click handler. */
    fun markDirty() {
        dirty = true
    }

    /** Note that the model changed but the view is still correct -- flush once the caret leaves. */
    fun markSaveNeeded() {
        saveNeeded = true
    }

    /** Mutates the template, persists it, and rebuilds. The normal path for every structural edit. */
    fun edit(block: (Template) -> Unit) {
        val current = template ?: return
        block(current)
        runCatching { TemplateStore.flush() }
        markDirty()
    }

    fun setStatus(message: String) {
        status = message
        markDirty()
    }

    fun selectTemplate(selected: Template?) {
        template = selected
        selectedShip = 0
        hotbarBar = 0
        scrollOffsets.clear()
        markDirty()
    }

    // --- Header ----------------------------------------------------------------------------------

    private fun buildHeader() {
        val host = headerHost ?: return
        host.clearChildren()

        val available = contentWidth - CLOSE_W - TAB_GAP * Tab.entries.size
        val tabWidth = min(200f, available / Tab.entries.size)

        var x = 0f
        for (entry in Tab.entries) {
            // The tabs that edit a template are meaningless without one, so clicking them does
            // nothing until the store has something in it.
            val usable = entry == Tab.TEMPLATES || template != null
            host.tabButton(x, 0f, tabWidth, HEADER_H - 4f, entry.label, entry == tab) {
                if (usable && tab != entry) {
                    tab = entry
                    markDirty()
                }
            }
            x += tabWidth + TAB_GAP
        }

        host.plainButton(contentWidth - CLOSE_W, 0f, CLOSE_W, HEADER_H - 4f, "CLOSE") {
            MenuButton.close()
        }
    }

    // --- Content ---------------------------------------------------------------------------------

    private fun rebuildContent() {
        val host = contentHost ?: return
        host.clearChildren()
        bindings.clear()

        if (template == null && tab != Tab.TEMPLATES) tab = Tab.TEMPLATES

        // A tab that throws leaves the tab strip alive so you can switch away from it, rather than
        // taking the window down with it.
        runCatching {
            when (tab) {
                Tab.TEMPLATES -> TemplatesTab.build(host, contentWidth, contentHeight)
                Tab.SHIPS -> ShipsTab.build(host, contentWidth, contentHeight)
                Tab.CARGO -> CargoTab.build(host, contentWidth, contentHeight)
                Tab.CHARACTER -> CharacterTab.build(host, contentWidth, contentHeight)
                Tab.HOTBAR -> HotbarTab.build(host, contentWidth, contentHeight)
            }
        }.onFailure { failure ->
            Global.getLogger(SetupPanel::class.java)
                .error("StarterPack: the ${tab.label} tab failed to build.", failure)
            host.clearChildren()
            host.TooltipMakerPanel(contentWidth, contentHeight) {
                addPara(
                    "The ${tab.label} tab failed to build: ${failure.message ?: failure.javaClass.simpleName}. " +
                        "The full stack trace is in starsector.log. Other tabs still work.",
                    DANGER, 8f,
                )
            }
        }

        // The overlay is re-parented last so it keeps drawing over the content: a rebuild happens
        // underneath an open picker and must not put the picker behind it.
        overlay?.let { existing ->
            runCatching {
                root?.removeComponent(existing)
                root?.addComponent(existing)
            }
        }
    }

    // --- Footer ----------------------------------------------------------------------------------

    private fun buildFooter() {
        val host = footerHost ?: return
        host.clearChildren()
        host.TooltipMakerPanel(contentWidth, FOOTER_H) {
            val active = TemplateStore.activeName().ifBlank { "none" }
            val auto = if (TemplateStore.isAutoApplyEnabled()) "on" else "off"
            addPara(
                "Active: %s   |   Auto-apply on new game: %s   |   Saved to %s",
                2f, Misc.getHighlightColor(), active, auto, TemplateStore.location(),
            )
            if (status.isNotBlank()) addPara(status, Misc.getGrayColor(), 2f)
        }
    }

    // --- Picker ----------------------------------------------------------------------------------

    /**
     * Opens the modal picker over the whole window.
     *
     * Full-size on purpose: the overlay covers the editor entirely, so every click while it is open
     * lands on it rather than on a button underneath. A smaller floating list would leave the rest of
     * the window live and let you edit the thing you are in the middle of choosing for.
     */
    fun openPicker(
        title: String,
        entries: List<CatalogEntry>,
        currentId: String? = null,
        onPick: (CatalogEntry) -> Unit,
    ) {
        val host = root ?: return
        closePicker()

        // Starsector buttons handle their own input regardless of what is drawn over them: a panel
        // on top hides the editor visually but every button underneath still takes the click. The
        // only way to make the picker genuinely modal is for those buttons not to exist while it is
        // open, so the editor is torn down here and rebuilt by the markDirty in closePicker.
        headerHost?.clearChildren()
        contentHost?.clearChildren()
        footerHost?.clearChildren()
        bindings.clear()

        overlay = PickerOverlay.create(host, panelWidth, panelHeight, title, entries, currentId, onPick)
    }

    fun closePicker() {
        val existing = overlay ?: return
        overlay = null
        runCatching { root?.removeComponent(existing) }
        PickerOverlay.reset()
        markDirty()
    }

    fun isPickerOpen(): Boolean = overlay != null
}

/**
 * A scrolling region that owns its own wheel handling and remembers where it was left.
 *
 * Wheel events have to be bound to the panel the cursor is over, not to the window: bound higher up,
 * one region swallows the wheel everywhere and the others cannot be scrolled at all. Each region
 * therefore gets its own [starterpack.uiframework.ExtendableCustomUIPanelPlugin] with an `onScroll`
 * that drives only its own scroller.
 *
 * [key] names the region so its offset survives the rebuild that follows every edit.
 */
fun CustomPanelAPI.scrollRegion(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    key: String,
    builder: TooltipMakerAPI.() -> Unit,
): CustomPanelAPI {
    val step = 60f
    var element: TooltipMakerAPI? = null
    var contentHeight = height

    val region = CustomPanel(width, height) { plugin ->
        element = scrollingElement(width, height) {
            setForceProcessInput(true)
            builder()
        }
        contentHeight = runCatching { element!!.heightSoFar }.getOrDefault(height)

        plugin.onScroll { event ->
            // An open picker is modal; letting the wheel through would scroll the list behind it.
            if (SetupPanel.isPickerOpen()) return@onScroll
            val scroller = runCatching { element?.externalScroller }.getOrNull() ?: return@onScroll
            val delta = if (event.eventValue > 0) -step else step
            val maxOffset = max(0f, contentHeight - height)
            val next = (scroller.yOffset + delta).coerceIn(0f, maxOffset)
            scroller.yOffset = next
            SetupPanel.scrollOffsets[key] = next
        }
    }
    region.anchorInTopLeftOfParent(x, y)

    // Restore after the content exists, so the offset can be clamped against a real content height.
    runCatching {
        val scroller = element?.externalScroller
        if (scroller != null) {
            val restored = (SetupPanel.scrollOffsets[key] ?: 0f)
                .coerceIn(0f, max(0f, contentHeight - height))
            scroller.yOffset = restored
            SetupPanel.scrollOffsets[key] = restored
        }
    }
    return region
}
