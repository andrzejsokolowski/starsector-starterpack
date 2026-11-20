package starterpack;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

import starterpack.apply.ApplyResult;
import starterpack.apply.TemplateApplier;
import starterpack.model.Snapshot;
import starterpack.model.Template;
import starterpack.snapshot.SnapshotApplier;
import starterpack.snapshot.SnapshotResult;
import starterpack.store.SnapshotStore;
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
 * <p>What this class does: the optional auto-apply on a new game, and the StarterPack tab on
 * OddiszCommons' Manage (Y) screen when that mod is enabled.</p>
 */
public class StarterPackModPlugin extends BaseModPlugin {

    public static final String MOD_ID = "starterpack";

    /** OddiszCommons is optional. Without it, snapshots are taken with the console. */
    private static final String ODDISZ_COMMONS_ID = "oddisz_commons";

    @Override
    public void onApplicationLoad() throws Exception {
        Global.getLogger(StarterPackModPlugin.class).info("StarterPack: application loaded.");
        if (Global.getSettings().getModManager().isModEnabled(ODDISZ_COMMONS_ID)) {
            try {
                registerManagePage();
            } catch (Throwable ex) {
                // An OddiszCommons without the Manage screen costs the tab, never the game.
                Global.getLogger(StarterPackModPlugin.class)
                        .error("StarterPack: could not add the snapshot tab to the Manage screen.", ex);
            }
        }
    }

    /**
     * In its own method so the class that names OddiszCommons is only reached here, after the check
     * that the mod is enabled.
     */
    private static void registerManagePage() {
        starterpack.snapshot.SnapshotPage.register();
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
        // A snapshot set for new games wins over the template: it is the whole previous campaign, and
        // laying a template over it, or it over a template, would only waste one of them.
        try {
            Snapshot snapshot = SnapshotStore.INSTANCE.armed();
            if (snapshot != null) {
                SnapshotResult result = SnapshotApplier.INSTANCE.apply(snapshot, false);
                if (result.getSucceeded()) {
                    Global.getLogger(StarterPackModPlugin.class).info(
                            "StarterPack: new game started from snapshot \"" + snapshot.getName() + "\" -- "
                                    + result.describe());
                } else {
                    Global.getLogger(StarterPackModPlugin.class).warn(
                            "StarterPack: the snapshot did nothing -- " + result.describe());
                }
                return;
            }
        } catch (Exception ex) {
            Global.getLogger(StarterPackModPlugin.class)
                    .error("StarterPack: applying the snapshot failed; the campaign was left as the game made it.", ex);
            return;
        }

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
