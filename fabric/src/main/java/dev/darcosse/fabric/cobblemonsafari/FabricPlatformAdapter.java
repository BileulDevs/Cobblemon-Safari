package dev.darcosse.fabric.cobblemonsafari;

import dev.darcosse.common.cobblemonsafari.platform.PlatformAdapter;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/**
 * Fabric implementation of the platform services.
 */
public class FabricPlatformAdapter implements PlatformAdapter {

    /** Fabric's config directory. */
    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    /**
     * Sends only if the client declared the channel. Fabric does not refuse
     * clients without the mod, so a vanilla-network client must simply get no HUD.
     */
    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (ServerPlayNetworking.canSend(player, payload.type())) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
