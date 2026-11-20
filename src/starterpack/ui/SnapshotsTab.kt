package starterpack.ui

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc
import starterpack.catalog.Catalog
import starterpack.model.Snapshot
import starterpack.store.SnapshotStore

/**
 * Snapshots at the main menu: the campaigns saved for a new game plus, kept apart from the templates.
 *
 * Nothing in a snapshot is edited here. It is a copy of a campaign, so this tab only shows what it
 * holds and lets you rename it, delete it, or pick it as the start of every new game. Taking one
 * happens in a campaign, on the Manage (Y) screen.
 */
object SnapshotsTab {

    private const val GAP = 12f
    private const val LIST_W = 440f
    private const val DELETE_ARM_MS = 4_000L

    /** The snapshot shown on the right. Kept by identity; falls back to the first one. */
    private var selected: Snapshot? = null

    private var deleteArmedAt = 0L
    private var deleteArmedFor: Snapshot? = null

    fun build(host: CustomPanelAPI, width: Float, height: Float) {
        val all = SnapshotStore.all()
        if (selected == null || all.none { it === selected }) selected = SnapshotStore.armed() ?: all.firstOrNull()

        val rightW = width - LIST_W - GAP
        host.scrollRegion(0f, 0f, LIST_W, height, "snapshots.list") { buildList(LIST_W - 24f) }
        host.scrollRegion(LIST_W + GAP, 0f, rightW, height, "snapshots.details") { buildDetails(rightW - 24f) }
    }

    // --- Left: the list ------------------------------------------------------------------------

    private fun TooltipMakerAPI.buildList(inner: Float) {
        addSectionHeading("Snapshots", Alignment.MID, 0f)
        val snapshots = SnapshotStore.all()
        if (snapshots.isEmpty()) {
            addPara(
                "No snapshots yet. In a campaign, save one on the Manage (Y) screen: Others tab, " +
                    "StarterPack. Needs OddiszCommons.",
                Misc.getGrayColor(), 8f,
            )
            return
        }
        for (snapshot in snapshots) {
            val marker = if (SnapshotStore.isArmed(snapshot)) "* " else ""
            checkboxRow("$marker${snapshot.name}", snapshot === selected, inner, 24f, 5f) {
                selected = snapshot
                SetupPanel.scrollOffsets.remove("snapshots.details")
                SetupPanel.markDirty()
            }
            addPara(snapshot.summary(), Misc.getGrayColor(), 1f)
        }
        addPara("* = new games start from it.", Misc.getGrayColor(), 10f)
    }

    // --- Right: what it holds ------------------------------------------------------------------

    private fun TooltipMakerAPI.buildDetails(inner: Float) {
        val snapshot = selected
        if (snapshot == null) {
            addPara("Select a snapshot on the left.", Misc.getGrayColor(), 8f)
            return
        }
        val gray = Misc.getGrayColor()
        val highlight = Misc.getHighlightColor()

        addSectionHeading("Selected snapshot", Alignment.MID, 0f)
        textRow("Name", snapshot.name, inner, SetupPanel.bindings, maxChars = 64) { text ->
            SnapshotStore.rename(snapshot, text, persist = false)
            SetupPanel.markSnapshotSaveNeeded()
        }
        val taken = listOf(snapshot.captain, snapshot.takenOn, snapshot.savedOn).filter { it.isNotBlank() }
        if (taken.isNotEmpty()) addPara("Taken: %s", 6f, highlight, taken.joinToString(", "))

        val armed = SnapshotStore.isArmed(snapshot)
        checkboxRow("Start every new game from this snapshot", armed, inner, 22f, 8f) {
            SnapshotStore.setArmed(if (armed) null else snapshot)
            SetupPanel.markDirty()
        }
        addPara(
            "It replaces the fleet, cargo, credits, character, standings and hotbar the new game starts " +
                "with, instead of the active template. Once per campaign.",
            gray, 4f,
        )

        val deleteArmed = deleteArmedFor === snapshot && System.currentTimeMillis() - deleteArmedAt < DELETE_ARM_MS
        buttons(min(220f, inner), 26f, 8f, listOf(
            RowButton(
                if (deleteArmed) "CONFIRM DELETE" else "Delete",
                color = if (deleteArmed) DANGER else Misc.getBasePlayerColor(),
            ) {
                if (deleteArmedFor === snapshot && System.currentTimeMillis() - deleteArmedAt < DELETE_ARM_MS) {
                    val name = snapshot.name
                    SnapshotStore.delete(snapshot)
                    deleteArmedFor = null
                    selected = null
                    SetupPanel.setStatus("Deleted snapshot \"$name\".")
                } else {
                    deleteArmedFor = snapshot
                    deleteArmedAt = System.currentTimeMillis()
                    SetupPanel.markDirty()
                }
            },
        ))

        // --- Character ---
        addSectionHeading("Character", Alignment.MID, 14f)
        val character = snapshot.character
        if (character == null) {
            addPara("Not saved.", gray, 6f)
        } else {
            addPara(
                "Level %s, %s unspent skill points, %s story points",
                6f, highlight, "${character.level}", "${character.skillPoints}", "${character.storyPoints}",
            )
            if (character.skills.isNotEmpty()) {
                addPara(skillList(character.skills), gray, 4f)
            }
        }
        snapshot.pack.credits?.let { addPara("Credits: %s", 4f, highlight, Snapshot.formatCredits(it)) }

        // --- Fleet ---
        val ships = snapshot.pack.ships
        addSectionHeading("Fleet (${ships.size})", Alignment.MID, 14f)
        val captains = snapshot.officers.filter { it.ship >= 0 }.associateBy { it.ship }
        for ((index, ship) in ships.withIndex()) {
            val hull = runCatching { Catalog.hullSpec(ship.hullId)?.hullName }.getOrNull() ?: ship.hullId
            val name = ship.shipName.ifBlank { hull }
            val notes = ArrayList<String>(3)
            if (ship.shipName.isNotBlank()) notes += hull
            if (ship.flagship) notes += "flagship"
            if (index in snapshot.mothballed) notes += "mothballed"
            if (ship.dMods.isNotEmpty()) notes += "${ship.dMods.size} D-mods"
            if (ship.sMods.isNotEmpty()) notes += "${ship.sMods.size} S-mods"
            captains[index]?.let { notes += "captain ${it.displayName()}" }
            val known = Catalog.hullSpec(ship.hullId) != null
            addPara(
                if (notes.isEmpty()) name else "$name  (${notes.joinToString(", ")})",
                if (known) Misc.getTextColor() else DANGER, 3f,
            )
        }

        // --- Officers ---
        val people = snapshot.officers.filter { it.aiCoreId.isBlank() }
        val cores = snapshot.officers.filter { it.aiCoreId.isNotBlank() }
        addSectionHeading("Officers (${people.size})", Alignment.MID, 14f)
        if (people.isEmpty()) addPara("None.", gray, 6f)
        for (officer in people) {
            val ship = ships.getOrNull(officer.ship)?.let { it.shipName.ifBlank { it.hullId } }
            val text = "${officer.displayName()}, level ${officer.level}, ${officer.personality}" +
                (ship?.let { ", on $it" } ?: ", no ship")
            addPara(text, Misc.getTextColor(), 3f)
        }
        if (cores.isNotEmpty()) {
            addPara("AI core captains: %s", 8f, highlight, "${cores.size}")
            for (core in cores) {
                val name = runCatching { Global.getSettings().getCommoditySpec(core.aiCoreId)?.name }.getOrNull()
                    ?: core.aiCoreId
                val ship = ships.getOrNull(core.ship)?.let { it.shipName.ifBlank { it.hullId } } ?: "?"
                addPara("$name, level ${core.level}, on $ship", Misc.getTextColor(), 3f)
            }
        }

        // --- The rest ---
        addSectionHeading("Also saved", Alignment.MID, 14f)
        val cargo = snapshot.pack.cargo
        addPara("Cargo: %s stacks", 6f, highlight, "${cargo.stackCount()}")
        addPara("Faction standings: %s factions", 3f, highlight, "${snapshot.pack.reputations.size}")
        addPara("Hotbar: %s slots", 3f, highlight, "${snapshot.pack.hotbar.assignedCount()}")
        val bp = snapshot.blueprints
        if (snapshot.blueprintsSaved) {
            addPara(
                "Known: %s ship, %s fighter and %s weapon blueprints, %s hull mods",
                3f, highlight, "${bp.ships.size}", "${bp.fighters.size}", "${bp.weapons.size}", "${bp.hullMods.size}",
            )
        } else {
            addPara("Known blueprints and hull mods: not saved", gray, 3f)
        }
    }

    private fun skillList(skills: Map<String, Float>): String = skills.entries.joinToString(", ") { (id, level) ->
        val name = runCatching { Global.getSettings().getSkillSpec(id)?.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: id
        if (level >= 2f) "$name (elite)" else name
    }

    private fun min(a: Float, b: Float): Float = if (a < b) a else b
}
