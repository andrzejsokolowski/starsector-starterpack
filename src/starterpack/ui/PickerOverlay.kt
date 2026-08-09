package starterpack.ui

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.ScrollPanelAPI
import com.fs.starfarer.api.ui.TextFieldAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import com.fs.starfarer.api.util.Misc
import starterpack.catalog.CatalogEntry
import starterpack.uiframework.CustomPanel
import starterpack.uiframework.Font
import starterpack.uiframework.Tooltip
import starterpack.uiframework.TooltipMakerPanel
import starterpack.uiframework.anchorInTopLeftOfParent
import starterpack.uiframework.clearChildren
import starterpack.uiframework.getFontPath
import starterpack.uiframework.onClick
import kotlin.math.max

/**
 * The modal "choose one of these" list: a search box over a scrolling set of rows.
 *
 * ## Why it is capped rather than virtualised
 *
 * The biggest list here is every mountable weapon in the install -- about 1,800 entries on a heavy
 * mod list. Building 1,800 rows is not viable, and a pooled/rebound row set (the approach a browsing
 * UI needs) is a lot of machinery for a list you are meant to *leave* within a few keystrokes. So the
 * list shows the first [MAX_ROWS] matches and says how many it is hiding: with a search box in front
 * of it, anything you are actually looking for is two or three characters away, and the hidden count
 * tells you when you have not narrowed enough rather than silently lying about the catalogue.
 *
 * ## Detail lives in tooltips
 *
 * Each row registers a tooltip carrying the entry's artwork and full details. Tooltip bodies are
 * built lazily by the engine on hover, so 250 of them cost 250 registrations and zero sprites until
 * you point at one -- which is what makes per-row artwork affordable at all.
 */
object PickerOverlay {

    private const val PAD = 16f
    private const val HEADER_H = 30f
    private const val SEARCH_H = 26f
    private const val FOOTER_H = 26f
    private const val ROW_H = 22f
    private const val SCROLL_STEP = 66f
    private const val TOOLTIP_W = 340f

    /** How many matching rows are drawn. Beyond this the footer tells you to narrow the search. */
    private const val MAX_ROWS = 250

    private var listHost: CustomPanelAPI? = null
    private var footerHost: CustomPanelAPI? = null
    private var searchField: TextFieldAPI? = null

    private var listWidth = 0f
    private var listHeight = 0f

    private var all: List<CatalogEntry> = emptyList()
    private var shown: List<CatalogEntry> = emptyList()
    private var currentId: String? = null
    private var onPick: ((CatalogEntry) -> Unit)? = null

    private var lastQuery: String? = null
    private var scroller: ScrollPanelAPI? = null
    private var scrollContentHeight = 0f

    fun create(
        host: UIPanelAPI,
        width: Float,
        height: Float,
        title: String,
        entries: List<CatalogEntry>,
        currentId: String?,
        onPick: (CatalogEntry) -> Unit,
    ): CustomPanelAPI {
        reset()
        all = entries
        this.currentId = currentId
        this.onPick = onPick

        val innerWidth = width - PAD * 2
        listWidth = innerWidth
        listHeight = height - PAD * 2 - HEADER_H - SEARCH_H - FOOTER_H

        val panel = host.CustomPanel(width, height) { plugin ->
            // Fully opaque and full-size: this is what makes the picker modal. Every click while it
            // is open lands here rather than on the editor row that opened it.
            plugin.renderBelow { alpha -> drawBackdrop(plugin.customPanel, alpha, dim = 0.18f) }
            plugin.onScroll { event ->
                val active = scroller ?: return@onScroll
                val step = if (event.eventValue > 0) -SCROLL_STEP else SCROLL_STEP
                val maxOffset = max(0f, scrollContentHeight - listHeight)
                active.yOffset = (active.yOffset + step).coerceIn(0f, maxOffset)
            }

            CustomPanel(innerWidth, HEADER_H) {
                TooltipMakerPanel(innerWidth, HEADER_H) {
                    setParaFont(getFontPath(Font.ORBITRON_20))
                    addPara(title, Misc.getBrightPlayerColor(), 0f)
                }
                plainButton(innerWidth - 100f, 0f, 100f, HEADER_H - 4f, "CANCEL", DANGER) {
                    SetupPanel.closePicker()
                }
            }.anchorInTopLeftOfParent(PAD, PAD)

            CustomPanel(innerWidth, SEARCH_H) {
                TooltipMakerPanel(innerWidth, SEARCH_H) {
                    searchField = addTextField(innerWidth, SEARCH_H, getFontPath(Font.VICTOR_14), 0f).apply {
                        maxChars = 48
                        // Focus follows the picker opening: you came here to search, so the very next
                        // keystroke should be a search rather than a click.
                        runCatching { grabFocus(false) }
                    }
                }
            }.anchorInTopLeftOfParent(PAD, PAD + HEADER_H)

            listHost = CustomPanel(innerWidth, listHeight) {}
                .also { it.anchorInTopLeftOfParent(PAD, PAD + HEADER_H + SEARCH_H) }

            footerHost = CustomPanel(innerWidth, FOOTER_H) {}
                .also { it.anchorInTopLeftOfParent(PAD, PAD + HEADER_H + SEARCH_H + listHeight) }
        }
        panel.anchorInTopLeftOfParent(0f, 0f)

        refilter("")
        return panel
    }

    fun reset() {
        listHost = null
        footerHost = null
        searchField = null
        all = emptyList()
        shown = emptyList()
        currentId = null
        onPick = null
        lastQuery = null
        scroller = null
        scrollContentHeight = 0f
    }

    /** Driven from [SetupPanel.tick]; the picker has no plugin advance of its own. */
    fun tick() {
        val field = searchField ?: return
        val query = field.text.orEmpty().trim()
        if (query != lastQuery) refilter(query)
    }

    private fun refilter(query: String) {
        lastQuery = query
        val terms = query.lowercase().split(' ').filter { it.isNotBlank() }
        val matches = if (terms.isEmpty()) all else all.filter { it.matches(terms) }
        shown = matches.take(MAX_ROWS)
        buildList(matches.size)
    }

    private fun buildList(totalMatches: Int) {
        val host = listHost ?: return
        host.clearChildren()

        val element = host.scrollingElement(listWidth, listHeight) {
            setForceProcessInput(true)
            if (shown.isEmpty()) {
                addPara("Nothing matches that.", Misc.getGrayColor(), 8f)
                return@scrollingElement
            }
            for (entry in shown) {
                val isCurrent = entry.id == currentId
                val row = checkboxRow(rowLabel(entry), isCurrent, listWidth - 20f, ROW_H, 1f) {
                    val callback = onPick
                    SetupPanel.closePicker()
                    callback?.invoke(entry)
                }
                // Registered, not built: the engine only runs this body when the cursor lands on the
                // row, so the sprite is never loaded for the 249 rows you did not look at.
                row.Tooltip(TooltipMakerAPI.TooltipLocation.RIGHT, TOOLTIP_W) {
                    if (entry.sprite.isNotBlank()) {
                        runCatching { addImage(entry.sprite, TOOLTIP_W - 20f, 140f, 0f) }
                    }
                    addPara(entry.name, Misc.getHighlightColor(), 4f)
                    addPara(entry.id, Misc.getGrayColor(), 2f)
                    addPara("%s  |  %s", 4f, Misc.getBasePlayerColor(), entry.primary, entry.secondary)
                    addPara("Source: %s", 2f, Misc.getBasePlayerColor(), entry.sourceMod)
                    if (entry.opCost > 0f) {
                        addPara("Ordnance points: %s", 2f, Misc.getHighlightColor(), formatOp(entry.opCost))
                    }
                }
            }
        }

        scrollContentHeight = runCatching { element.heightSoFar }.getOrDefault(listHeight)
        scroller = runCatching { element.externalScroller }.getOrNull()
        scroller?.yOffset = 0f

        buildFooter(totalMatches)
    }

    private fun buildFooter(totalMatches: Int) {
        val host = footerHost ?: return
        host.clearChildren()
        host.TooltipMakerPanel(listWidth, FOOTER_H) {
            if (totalMatches > shown.size) {
                addPara(
                    "Showing %s of %s matches -- type to narrow it down.",
                    0f, Misc.getHighlightColor(),
                    shown.size.toString(), totalMatches.toString(),
                )
            } else {
                addPara(
                    "%s of %s entries   |   click one to choose it, Escape to cancel",
                    0f, Misc.getHighlightColor(),
                    shown.size.toString(), all.size.toString(),
                )
            }
        }
    }

    /**
     * One row's text.
     *
     * A single string rather than positioned columns: Victor is not monospaced, so padded text goes
     * ragged, and real columns would mean a panel per cell per row. At picker sizes the separators
     * read fine and the tooltip carries anything the line cannot.
     */
    private fun rowLabel(entry: CatalogEntry): String {
        val detail = listOfNotNull(
            entry.primary.takeIf { it.isNotBlank() && it != "-" },
            entry.secondary.takeIf { it.isNotBlank() && it != "-" }?.take(40),
        ).joinToString(" / ")
        val op = if (entry.opCost > 0f) "  [${formatOp(entry.opCost)} OP]" else ""
        return if (detail.isBlank()) "${entry.name}$op" else "${entry.name}   -   $detail$op"
    }

    /** Costs are floats but almost always whole; show `12` rather than `12.0`. */
    private fun formatOp(op: Float): String =
        if (op % 1f == 0f) op.toInt().toString() else String.format("%.1f", op)
}
