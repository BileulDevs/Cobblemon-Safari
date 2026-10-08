package dev.darcosse.forge.cobblemonsafari.client;

import dev.darcosse.common.cobblemonsafari.block.SafariBlockEntities;
import dev.darcosse.common.cobblemonsafari.client.SafariClient;
import dev.darcosse.common.cobblemonsafari.client.hud.SafariTimerHud;
import dev.darcosse.common.cobblemonsafari.client.sky.SafariSkyEffects;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge client wiring. CLIENT ONLY: only reached through the dist check in
 * the mod constructor.
 */
public final class NeoForgeClientSetup {

    private NeoForgeClientSetup() {
    }

    /** Loads client preferences and registers client listeners. */
    public static void init(IEventBus modEventBus) {
        SafariClient.init();
        modEventBus.addListener(NeoForgeClientSetup::onRegisterGuiLayers);
        modEventBus.addListener(NeoForgeClientSetup::onRegisterDimensionEffects);
        modEventBus.addListener(NeoForgeClientSetup::onRegisterRenderers);
        NeoForge.EVENT_BUS.addListener(NeoForgeClientSetup::onLoggingOut);
    }

    /** Mod-bus handler: the timer HUD, above every vanilla layer. */
    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(SafariTimerHud.LAYER_ID, SafariTimerHud::render);
    }

    /** Mod-bus handler: the Safari sky. */
    private static void onRegisterDimensionEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(SafariDimensions.EFFECTS_ID, new SafariSkyEffects());
    }

    /** Mod-bus handler: the board screen renderer, with NeoForge's culling box. */
    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(SafariBlockEntities.safariBoard(), NeoForgeSafariBoardRenderer::new);
    }

    /** Game-bus handler: forgets server state when leaving a world or server. */
    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SafariClient.onDisconnect();
    }
}
