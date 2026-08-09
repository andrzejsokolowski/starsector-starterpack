package starterpack.commands

import org.lazywizard.console.BaseCommand
import org.lazywizard.console.BaseCommand.CommandContext
import org.lazywizard.console.BaseCommand.CommandResult
import org.lazywizard.console.Console
import starterpack.apply.TemplateApplier
import starterpack.model.Template
import starterpack.store.TemplateStore

/**
 * `starterpack [apply|list|info|status|off] [name] [force]`
 *
 * The console half of the mod. The editor at the main menu builds templates; this stamps one onto a
 * running campaign.
 *
 * This class is only ever loaded when Console Commands is active -- it is registered through
 * `data/console/commands.csv`, which the console mod reads and nobody else does. That is what keeps
 * Console Commands a soft dependency: without it, this file is dead weight in the jar rather than a
 * `NoClassDefFoundError` at load.
 */
class StarterPackCommand : BaseCommand {

    override fun runCommand(args: String, context: CommandContext): CommandResult {
        val tokens = args.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val subcommand = tokens.firstOrNull()?.lowercase() ?: "apply"

        return when (subcommand) {
            "list" -> list()
            "status" -> status()
            "off" -> setAutoApply(false)
            "on" -> setAutoApply(true)
            "info" -> info(tokens.drop(1))
            "apply" -> apply(tokens.drop(1), context)
            // No subcommand given: the bare word is either a template name or nothing at all, and
            // "starterpack mytest" reading as "apply mytest" is what anyone would expect it to do.
            else -> apply(tokens, context)
        }
    }

    // --- Subcommands -----------------------------------------------------------------------------

    private fun list(): CommandResult {
        val templates = TemplateStore.all()
        if (templates.isEmpty()) {
            Console.showMessage(
                "No templates yet. Open the game's main menu and click STARTER PACK to build one."
            )
            return CommandResult.SUCCESS
        }
        val activeName = TemplateStore.activeName()
        Console.showMessage("StarterPack templates (${templates.size}), stored in ${TemplateStore.location()}:")
        for (template in templates) {
            val marker = if (template.name.equals(activeName, ignoreCase = true)) " *" else "  "
            Console.showMessage("$marker ${template.name}  --  ${template.summary()}")
        }
        Console.showMessage("* = active. 'starterpack apply' with no name uses it.")
        return CommandResult.SUCCESS
    }

    private fun status(): CommandResult {
        val active = TemplateStore.activeName().ifBlank { "none" }
        val auto = if (TemplateStore.isAutoApplyEnabled()) "on" else "off"
        Console.showMessage("StarterPack: active template = $active, auto-apply on new game = $auto.")
        val applied = TemplateApplier.appliedTemplateName()
        if (applied != null) {
            Console.showMessage("This campaign already had \"$applied\" applied.")
        } else {
            Console.showMessage("This campaign has not had a template applied.")
        }
        return CommandResult.SUCCESS
    }

    private fun setAutoApply(enabled: Boolean): CommandResult {
        TemplateStore.setAutoApply(enabled)
        Console.showMessage("StarterPack: auto-apply on new game is now ${if (enabled) "on" else "off"}.")
        return CommandResult.SUCCESS
    }

    private fun info(rest: List<String>): CommandResult {
        val template = resolve(rest.joinToString(" ")) ?: return CommandResult.ERROR
        Console.showMessage("StarterPack template \"${template.name}\":")
        Console.showMessage("  ${template.summary()}")
        Console.showMessage(
            "  Replace fleet: ${template.replaceFleet}, replace cargo: ${template.replaceCargo}"
        )
        for (ship in template.ships) {
            val flag = if (ship.flagship) " [flagship]" else ""
            val mods = ship.hullMods.size + ship.sMods.size + ship.permaMods.size + ship.dMods.size
            Console.showMessage(
                "  - ${ship.hullId}$flag: ${ship.weapons.size} weapons, " +
                    "${ship.wings.count { it != null }} wings, $mods hullmods, " +
                    "${ship.vents}v/${ship.capacitors}c"
            )
        }
        return CommandResult.SUCCESS
    }

    private fun apply(rest: List<String>, context: CommandContext): CommandResult {
        if (!context.isCampaignAccessible) {
            Console.showMessage("StarterPack: start or load a campaign first.")
            return CommandResult.WRONG_CONTEXT
        }

        // 'force' can be anywhere in the arguments; a template name can contain spaces, so the flag
        // is filtered out of the name rather than being required to sit in a fixed position.
        val force = rest.any { it.equals("force", ignoreCase = true) }
        val name = rest.filterNot { it.equals("force", ignoreCase = true) }.joinToString(" ")

        val template = resolve(name) ?: return CommandResult.ERROR

        val result = try {
            TemplateApplier.apply(template, force)
        } catch (ex: Exception) {
            Console.showException("StarterPack: applying \"${template.name}\" failed.", ex)
            return CommandResult.ERROR
        }

        if (!result.succeeded) {
            Console.showMessage("StarterPack: ${result.describe()}")
            return CommandResult.ERROR
        }

        Console.showMessage("StarterPack: applied \"${template.name}\" -- ${result.describe()}")
        if (result.warnings.isNotEmpty()) {
            Console.showMessage("${result.warnings.size} thing(s) could not be applied:")
            result.warnings.forEach { Console.showMessage("  - $it") }
        }
        return CommandResult.SUCCESS
    }

    // --- Helpers ---------------------------------------------------------------------------------

    /**
     * Turns an argument into a template, reporting precisely why not when it cannot.
     *
     * An empty name falls back to the active template, which is the common case: you set the active
     * one once in the editor and then only ever type `starterpack`.
     */
    private fun resolve(name: String): Template? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            val active = TemplateStore.active()
            if (active == null) {
                if (TemplateStore.isEmpty()) {
                    Console.showMessage(
                        "StarterPack: no templates exist. Build one from the STARTER PACK button on " +
                            "the main menu."
                    )
                } else {
                    Console.showMessage(
                        "StarterPack: no active template. Name one, or set an active one in the editor. " +
                            "Run 'starterpack list' to see them."
                    )
                }
            }
            return active
        }

        val found = TemplateStore.byName(trimmed)
        if (found == null) {
            Console.showMessage("StarterPack: no template called \"$trimmed\". Run 'starterpack list'.")
        }
        return found
    }
}
