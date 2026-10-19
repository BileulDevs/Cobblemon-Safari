package dev.darcosse.common.cobblemonsafari;

import dev.darcosse.common.cobblemonsafari.battle.BattleGuard;
import dev.darcosse.common.cobblemonsafari.capture.CaptureGuard;
import dev.darcosse.common.cobblemonsafari.capture.SafariBallBonus;
import dev.darcosse.common.cobblemonsafari.credit.SafariCredit;
import dev.darcosse.common.cobblemonsafari.gate.SafariGate;
import dev.darcosse.common.cobblemonsafari.points.SafariPoints;
import dev.darcosse.common.cobblemonsafari.bounty.SafariBounties;
import dev.darcosse.common.cobblemonsafari.capture.CatchTracker;
import dev.darcosse.common.cobblemonsafari.command.SafariDialogueFunctions;
import dev.darcosse.common.cobblemonsafari.session.SafariSessionManager;
import dev.darcosse.common.cobblemonsafari.spawn.NativeSpawnRules;
import dev.darcosse.common.cobblemonsafari.spawn.SpecialSpawnCounter;
import dev.darcosse.common.cobblemonsafari.stats.SafariStatsListener;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.platform.Platform;
import dev.darcosse.common.cobblemonsafari.platform.PlatformAdapter;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-independent entry point of Cobblemon Safari.
 *
 * <p>Each loader module calls {@link #init(PlatformAdapter)} once, as early as
 * possible, registers {@code SafariBlocks}, then {@code SafariItems}, and the
 * {@code SafariWorldgen} types in its own registry phase, then wires its own events to {@code SafariServerEvents}. Nothing in
 * here may touch client-only classes: this runs on dedicated servers too.
 */
public final class CobblemonSafari {

    /** Mod id, also the namespace of every resource, tag and translation key. */
    public static final String MOD_ID = "cobblemon_safari";

    /** Shared logger; prefix every message with context, the id is already in the logger name. */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private CobblemonSafari() {
    }

    /**
     * Bootstraps the common side: platform services, server configuration and
     * the Cobblemon event subscriptions that enforce the Safari rules.
     */
    public static void init(PlatformAdapter adapter) {
        Platform.set(adapter);
        SafariConfigManager.load();
        CaptureGuard.register();
        CatchTracker.register();
        BattleGuard.register();
        NativeSpawnRules.register();
        SpecialSpawnCounter.register();
        SafariBallBonus.register();
        SafariCredit.register();
        SafariGate.register();
        SafariPoints.register();
        SafariBounties.register();
        SafariDialogueFunctions.register();
        SafariSessionManager.addListener(new SafariStatsListener());
        LOGGER.info("Cobblemon Safari initialised");
    }

    /** Builds an id in the mod namespace. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
