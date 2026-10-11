package dev.darcosse.fabric.cobblemonsafari.client;

import dev.darcosse.common.cobblemonsafari.block.SafariBlockEntities;
import dev.darcosse.common.cobblemonsafari.block.SafariBlocks;
import dev.darcosse.common.cobblemonsafari.client.BountyClientState;
import dev.darcosse.common.cobblemonsafari.client.FeaturedClientState;
import dev.darcosse.common.cobblemonsafari.client.SafariClient;
import dev.darcosse.common.cobblemonsafari.client.SafariClientState;
import dev.darcosse.common.cobblemonsafari.client.board.SafariBoardRenderer;
import dev.darcosse.common.cobblemonsafari.client.hud.SafariTimerHud;
import dev.darcosse.common.cobblemonsafari.client.sky.SafariSkyEffects;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.network.SafariFeaturedPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariBountiesPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariClientNetwork;
import dev.darcosse.common.cobblemonsafari.network.SafariRulesPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariShopPayload;
import dev.darcosse.common.cobblemonsafari.network.ShopClientBridge;
import dev.darcosse.common.cobblemonsafari.network.SafariTimerPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

/**
 * Fabric client entry point: payload receivers, HUD, board screens, sky
 * effects, the gate's render layer and disconnect cleanup.
 */
public class CobblemonSafariFabricClient implements ClientModInitializer {

    /** Wires the client side. */
    @Override
    public void onInitializeClient() {
        SafariClient.init();

        ClientPlayNetworking.registerGlobalReceiver(SafariTimerPayload.TYPE, (payload, context) ->
                context.client().execute(() -> SafariClientState.onTimer(payload)));
        ClientPlayNetworking.registerGlobalReceiver(SafariRulesPayload.TYPE, (payload, context) ->
                context.client().execute(() -> SafariClientState.onRules(payload)));
        ClientPlayNetworking.registerGlobalReceiver(SafariFeaturedPayload.TYPE, (payload, context) ->
                context.client().execute(() -> FeaturedClientState.onFeatured(payload)));
        ClientPlayNetworking.registerGlobalReceiver(SafariBountiesPayload.TYPE, (payload, context) ->
                context.client().execute(() -> BountyClientState.onBounties(payload)));

        ClientPlayNetworking.registerGlobalReceiver(SafariShopPayload.TYPE, (payload, context) ->
                context.client().execute(() -> ShopClientBridge.onShop(payload)));
        SafariClientNetwork.setSender(ClientPlayNetworking::send);

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SafariClient.onDisconnect());

        HudRenderCallback.EVENT.register(SafariTimerHud::render);
        // Vanilla registry, made public by Fabric's transitive access widener.
        BlockEntityRenderers.register(SafariBlockEntities.safariBoard(), SafariBoardRenderer::new);

        DimensionRenderingRegistry.registerDimensionEffects(SafariDimensions.EFFECTS_ID, new SafariSkyEffects());

        // The gate pane is see-through like a nether portal. NeoForge reads
        // "render_type" from the block model; Fabric ignores that key.
        BlockRenderLayerMap.INSTANCE.putBlock(SafariBlocks.safariGate(), RenderType.translucent());
    }
}
