package starterpack.bench

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.mission.FleetSide
import starterpack.store.TemplateStore

/**
 * Collects the refitted loadouts from inside the bench mission.
 *
 * Reading the `.variant` files the game writes is impossible from mod code -- Starsector's
 * classloader refuses `java.io` outright and its file API reaches only `saves/common` -- so the
 * loadouts are taken from the live fleet instead. By the time a mission's combat engine exists, the
 * player has been through refit and every [FleetMemberAPI] carries exactly what they built.
 *
 * Registered by [LoadoutBench.defineMission] through `MissionDefinitionAPI.addPlugin`, so it exists
 * only inside the bench and never ticks anywhere else.
 *
 * Members are matched to template ships by [FleetMemberAPI.getId], recorded when the mission fleet
 * was assembled. That is exact -- no reliance on ordering, filenames or indices surviving the round
 * trip -- and a member whose id was never recorded is simply ignored.
 */
class BenchCapture : BaseEveryFrameCombatPlugin() {

    /** Capture is a one-shot; the fleet does not change afterwards and rewriting each frame is waste. */
    private var captured = false

    override fun init(engine: CombatEngineAPI?) {
        captured = false
    }

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (captured) return
        captured = true
        runCatching { capture() }
            .onFailure { log.error("StarterPack: could not capture loadouts from the refit bench.", it) }
    }

    private fun capture() {
        val engine = Global.getCombatEngine() ?: return
        val template = BenchState.templateForBench() ?: return
        val expected = BenchState.memberIds()
        if (expected.isEmpty() || template.ships.isEmpty()) return

        val manager = engine.getFleetManager(FleetSide.PLAYER) ?: return
        val members = ArrayList<FleetMemberAPI>()
        runCatching { manager.reservesCopy?.let { members.addAll(it) } }
        runCatching { manager.deployedCopy?.let { members.addAll(it) } }

        val warnings = ArrayList<String>()
        var updated = 0
        val seen = HashSet<Int>()

        for (member in members) {
            val id = runCatching { member.id }.getOrNull() ?: continue
            val index = expected.indexOf(id)
            // Ships the bench substituted for a missing hull were recorded as a blank id, so they
            // hold their position in the list without ever matching a real member.
            if (index < 0 || index !in template.ships.indices) continue
            if (!seen.add(index)) continue
            val variant = runCatching { member.variant }.getOrNull() ?: continue
            if (BenchImport.importVariant(template.ships[index], variant, warnings)) updated++
        }

        val result = ImportResult(updated, warnings)
        if (result.changedAnything) runCatching { TemplateStore.flush() }
        BenchState.lastImport = result
        BenchState.clearAwaiting()
        log.info("StarterPack: ${result.summary()}")

        runCatching {
            engine.combatUI?.addMessage(0, "StarterPack: ${result.summary()} You can leave now.")
        }
    }

    private companion object {
        private val log = Global.getLogger(BenchCapture::class.java)
    }
}
