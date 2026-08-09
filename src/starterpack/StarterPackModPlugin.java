package starterpack;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

import starterpack.apply.ApplyResult;
import starterpack.apply.TemplateApplier;
import starterpack.model.Template;
import starterpack.store.TemplateStore;

/**
 * Entry point for StarterPack.
 *
 * <p>Deliberately thin. The main-menu button is <em>not</em> registered here: it comes from an
 * {@code EveryFrameCombatPlugin} listed under {@code "plugins"} in our
 * {@code data/config/settings.json}, which the engine merges into the core settings and ticks in
 * every combat engine -- including the one running behind the main menu. See {@link TitleScreenHook}.
 * The console command is likewise registered through {@code data/console/commands.csv} rather than
 * from Java, which is what keeps Console Commands a soft dependency.</p>
 *
 * <p>The only thing this class does is the optional auto-apply.</p>
 */
public class StarterPackModPlugin extends BaseModPlugin {

    public static final String MOD_ID = "starterpack";

    @Override
    public void onApplicationLoad() throws Exception {
        Global.getLogger(StarterPackModPlugin.class).info("StarterPack: application loaded.");
    }

    /**
     * Stamps the active template onto a brand-new campaign, if auto-apply is switched on.
     *
     * <p>{@code onNewGameAfterTimePass} is the correct hook rather than {@code onNewGame}: by the
     * time it runs, the sector, the economy and the player fleet all exist and the game has finished
     * handing out the starting ships, so replacing that fleet is a well-defined operation. Running
     * earlier would mean fighting the campaign generator for ownership of the same objects.</p>
     *
     * <p>Failure is contained. This is a convenience path that runs before the player has any way to
     * intervene, so an exception here must not be allowed to take the new campaign down with it --
     * it is logged and the game proceeds with whatever start it was going to have.</p>
     */
    @Override
    public void onNewGameAfterTimePass() {
        try {
            if (!TemplateStore.INSTANCE.isAutoApplyEnabled()) return;

            Template template = TemplateStore.INSTANCE.active();
            if (template == null) {
                Global.getLogger(StarterPackModPlugin.class)
                        .info("StarterPack: auto-apply is on but no template is active; nothing applied.");
                return;
            }

            ApplyResult result = TemplateApplier.INSTANCE.apply(template, false);
            if (result.getSucceeded()) {
                Global.getLogger(StarterPackModPlugin.class).info(
                        "StarterPack: auto-applied \"" + template.getName() + "\" -- " + result.describe());
            } else {
                Global.getLogger(StarterPackModPlugin.class).warn(
                        "StarterPack: auto-apply did nothing -- " + result.describe());
            }
        } catch (Exception ex) {
            Global.getLogger(StarterPackModPlugin.class)
                    .error("StarterPack: auto-apply failed; the campaign was left as the game made it.", ex);
        }
    }
}
