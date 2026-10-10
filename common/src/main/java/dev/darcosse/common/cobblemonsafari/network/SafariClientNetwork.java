package dev.darcosse.common.cobblemonsafari.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.function.Consumer;

/**
 * Client-to-server sending, for common client code (the shop screen).
 *
 * <p>The loader's client entry point installs its own send function; common
 * code cannot call Fabric's or NeoForge's networking directly. Holds no
 * client-only types, so it is safe to load on a dedicated server.
 */
public final class SafariClientNetwork {

    /** Until a loader installs one, requests are dropped: there is no server to talk to anyway. */
    private static Consumer<CustomPacketPayload> sender = payload -> {
    };

    private SafariClientNetwork() {
    }

    /** Installs the loader's send function. Called once from the client entry point. */
    public static void setSender(Consumer<CustomPacketPayload> loaderSender) {
        sender = loaderSender;
    }

    /** Sends a payload to the server. */
    public static void sendToServer(CustomPacketPayload payload) {
        sender.accept(payload);
    }
}
