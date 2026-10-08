package dev.darcosse.forge.cobblemonsafari;

import dev.darcosse.common.cobblemonsafari.platform.PlatformAdapter;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;

/**
 * NeoForge implementation of the platform services.
 */
public class NeoForgePlatformAdapter implements PlatformAdapter {

    /** NeoForge's config directory. */
    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    /**
     * The channel is registered as required (see NeoForgeSafariNetworking), so
     * every connected client can receive it and no capability check is needed.
     */
    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }
}
