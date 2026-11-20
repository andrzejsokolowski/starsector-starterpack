package starterpack.snapshot

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import oddiszcommons.manage.Manage
import oddiszcommons.manage.ManagePage
import starterpack.StarterPackModPlugin
import starterpack.model.Snapshot
import starterpack.store.SnapshotStore
import starterpack.ui.DANGER
import starterpack.ui.RowButton
import starterpack.ui.buttons
import starterpack.ui.scrollRegion
import starterpack.uiframework.ExtendableCustomUIPanelPlugin
import starterpack.uiframework.clearChildren
import starterpack.uiframework.setPlugin
import kotlin.math.min

/**
 * The StarterPack tab on OddiszCommons' Manage (Y) screen: where a snapshot of the running campaign
 * is taken, saved over, set for new games, or deleted.
 *
 * This is the only class that names OddiszCommons. [register] is reached only when that mod is
 * enabled, so without it nothing here is ever loaded, and the console's `starterpack snapshot` is
 * the way to take one.
 *
 * Like the main-menu editor, the page rebuilds itself on the next frame after any change rather than
 * patching widgets in place.
 */
class SnapshotPage : ManagePage {

    private var root: CustomPanelAPI? = null
    private var width = 0f
    private var height = 0f
    private var dirty = false
    private var status = ""

    /** Delete and "save over" both throw away a snapshot, so each takes a second click. */
    private var confirmKey: String? = null
    private var confirmAt = 0L

    override fun createPanel(width: Float, height: Float): CustomPanelAPI {
        this.width = width
        this.height = height
        val panel = Global.getSettings().createCustom(width, height, null)
        val plugin = ExtendableCustomUIPanelPlugin(panel)
        panel.setPlugin(plugin)
        plugin.advance {
            if (dirty) {
                dirty = false
                build()
            }
        }
        root = panel
        build()
        return panel
    }

    override fun panelClosed() {
        root = null
        confirmKey = null
    }

    private fun markDirty() {
        dirty = true
    }

    private fun build() {
        val panel = root ?: return
        runCatching { panel.clearChildren() }
        val columnW = min(920f, width - 40f)
        val x = ((width - columnW) / 2f).coerceAtLeast(0f)
        runCatching {
            panel.scrollRegion(x, 10f, columnW, height - 20f, "manage.snapshots") { content(columnW - 24f) }
        }.onFailure {
            Global.getLogger(SnapshotPage::class.java).error("StarterPack: the snapshot page failed to build.", it)
        }
    }

    private fun TooltipMakerAPI.content(inner: Float) {
        addSectionHeading("StarterPack snapshots", Alignment.MID, 0f)
        addPara(
            "Saves your fleet, officers, cargo, credits, character, faction standings, hotbar and " +
                "blueprints. A new game can start from it.",
            Misc.getGrayColor(), 8f,
        )
        buttons(min(360f, inner), 30f, 10f, listOf(
            RowButton("SAVE A SNAPSHOT") { save(null) },
        ))
        if (status.isNotBlank()) addPara(status, Misc.getHighlightColor(), 6f)

        val snapshots = SnapshotStore.all()
        addSectionHeading("Saved snapshots", Alignment.MID, 16f)
        if (snapshots.isEmpty()) {
            addPara("None yet.", Misc.getGrayColor(), 8f)
            return
        }
        for (snapshot in snapshots.toList()) row(snapshot, inner)
    }

    private fun TooltipMakerAPI.row(snapshot: Snapshot, inner: Float) {
        val armed = SnapshotStore.isArmed(snapshot)
        val title = if (armed) "${snapshot.name}   (new games start from this)" else snapshot.name
        addPara(title, if (armed) Misc.getPositiveHighlightColor() else Misc.getHighlightColor(), 12f)
        val taken = listOf(snapshot.takenOn, snapshot.savedOn).filter { it.isNotBlank() }.joinToString(", ")
        if (taken.isNotBlank()) addPara("Taken $taken", Misc.getGrayColor(), 2f)
        addPara(snapshot.summary(), Misc.getGrayColor(), 2f)

        val replaceKey = "replace:${snapshot.name}"
        val deleteKey = "delete:${snapshot.name}"
        buttons(inner, 26f, 6f, listOf(
            RowButton(if (armed) "Stop using for new games" else "Use for new games") {
                SnapshotStore.setArmed(if (armed) null else snapshot)
                status = if (armed) {
                    "New games no longer start from \"${snapshot.name}\"."
                } else {
                    "New games will start from \"${snapshot.name}\"."
                }
                markDirty()
            },
            RowButton(
                if (isConfirming(replaceKey)) "CONFIRM SAVE OVER" else "Save over it",
                color = if (isConfirming(replaceKey)) DANGER else Misc.getBasePlayerColor(),
            ) {
                if (isConfirming(replaceKey)) {
                    confirmKey = null
                    save(snapshot)
                } else {
                    confirm(replaceKey)
                }
            },
            RowButton(
                if (isConfirming(deleteKey)) "CONFIRM DELETE" else "Delete",
                color = if (isConfirming(deleteKey)) DANGER else Misc.getBasePlayerColor(),
            ) {
                if (isConfirming(deleteKey)) {
                    confirmKey = null
                    SnapshotStore.delete(snapshot)
                    status = "Deleted \"${snapshot.name}\"."
                    markDirty()
                } else {
                    confirm(deleteKey)
                }
            },
        ))
    }

    /** Takes a snapshot, as a new one or in the place of [over]. */
    private fun save(over: Snapshot?) {
        val result = runCatching { SnapshotCapture.capture() }.getOrElse {
            Global.getLogger(SnapshotPage::class.java).error("StarterPack: taking a snapshot failed.", it)
            CaptureResult(failure = "The snapshot failed. See starsector.log.")
        }
        val snapshot = result.snapshot
        status = if (snapshot == null) {
            result.failure ?: "Nothing was saved."
        } else {
            val stored = if (over != null) SnapshotStore.replace(over, snapshot) else SnapshotStore.add(snapshot)
            val note = if (result.warnings.isEmpty()) "" else {
                "  ${result.warnings.size} thing(s) were left out, listed in starsector.log."
            }
            "Saved \"${stored.name}\": ${stored.summary()}.$note"
        }
        markDirty()
    }

    private fun confirm(key: String) {
        confirmKey = key
        confirmAt = System.currentTimeMillis()
        markDirty()
    }

    private fun isConfirming(key: String): Boolean =
        confirmKey == key && System.currentTimeMillis() - confirmAt < CONFIRM_MS

    companion object {
        const val TAB_NAME = "StarterPack"
        private const val CONFIRM_MS = 4_000L

        /** Adds the tab. Call only when OddiszCommons is enabled. */
        @JvmStatic
        fun register() {
            Manage.addPage(StarterPackModPlugin.MOD_ID, TAB_NAME, SnapshotPage())
        }
    }
}
