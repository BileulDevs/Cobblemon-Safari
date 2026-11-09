package dev.darcosse.fabric.cobblemonsafari;

import dev.darcosse.common.cobblemonsafari.platform.PlatformAdapter;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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

    /** Fires Fabric's world unload event, as the server does when it stops. */
    @Override
    public void fireLevelUnload(MinecraftServer server, ServerLevel level) {
        ServerWorldEvents.UNLOAD.invoker().onWorldUnload(server, level);
    }

    /** Fires Fabric's world load event, as the server does when it creates its levels. */
    @Override
    public void fireLevelLoad(MinecraftServer server, ServerLevel level) {
        ServerWorldEvents.LOAD.invoker().onWorldLoad(server, level);
    }

    /** Nothing to refresh: Fabric ticks the vanilla level map. */
    @Override
    public void levelsChanged(MinecraftServer server) {
    }
}
