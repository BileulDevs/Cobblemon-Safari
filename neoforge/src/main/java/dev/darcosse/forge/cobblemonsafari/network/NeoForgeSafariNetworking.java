package dev.darcosse.forge.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.client.FeaturedClientState;
import dev.darcosse.common.cobblemonsafari.client.SafariClientState;
import dev.darcosse.common.cobblemonsafari.network.SafariFeaturedPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariNetwork;
import dev.darcosse.common.cobblemonsafari.network.SafariRulesPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariTimerPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the Safari payloads on NeoForge.
 *
 * <p>Registered as REQUIRED (no {@code optional()}), unlike JEC: the Safari
 * relies on the client to pre-check Poké Ball use, so a client without the mod
 * would see its ball count desync. NeoForge refuses such clients at login.
 * The handlers are registered on both dists; they only touch
 * {@link SafariClientState} and {@link FeaturedClientState}, which have no
 * client-only imports.
 */
public final class NeoForgeSafariNetworking {

    private NeoForgeSafariNetworking() {
    }

    /** Mod-bus handler for payload registration. */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(SafariNetwork.PROTOCOL_VERSION);

        registrar.playToClient(SafariTimerPayload.TYPE, SafariTimerPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> SafariClientState.onTimer(payload)));
        registrar.playToClient(SafariRulesPayload.TYPE, SafariRulesPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> SafariClientState.onRules(payload)));
        registrar.playToClient(SafariFeaturedPayload.TYPE, SafariFeaturedPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> FeaturedClientState.onFeatured(payload)));
    }
}
