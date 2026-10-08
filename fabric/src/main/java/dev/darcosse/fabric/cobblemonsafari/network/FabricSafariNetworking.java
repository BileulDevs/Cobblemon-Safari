package dev.darcosse.fabric.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.network.SafariFeaturedPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariRulesPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariTimerPayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Declares the Safari payload types to Fabric.
 *
 * <p>Must run in the common initializer, on both sides: a dedicated server has
 * to know the codecs to encode, and the client to decode.
 */
public final class FabricSafariNetworking {

    private FabricSafariNetworking() {
    }

    /** Registers every server-to-client payload type. */
    public static void registerPayloadTypes() {
        PayloadTypeRegistry.playS2C().register(SafariTimerPayload.TYPE, SafariTimerPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SafariRulesPayload.TYPE, SafariRulesPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SafariFeaturedPayload.TYPE, SafariFeaturedPayload.CODEC);
    }
}
