package dev.darcosse.forge.cobblemonsafari;

import dev.darcosse.common.cobblemonsafari.platform.PlatformAdapter;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
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

    /** Posts NeoForge's level unload event, as the server does when it stops. */
    @Override
    public void fireLevelUnload(MinecraftServer server, ServerLevel level) {
        NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));
    }

    /** Posts NeoForge's level load event, as the server does when it creates its levels. */
    @Override
    public void fireLevelLoad(MinecraftServer server, ServerLevel level) {
        NeoForge.EVENT_BUS.post(new LevelEvent.Load(level));
    }

    /**
     * NeoForge ticks the levels from an array it caches; marking it dirty
     * rebuilds it at the next tick, without the old level and with the new.
     */
    @Override
    @SuppressWarnings("deprecation")
    public void levelsChanged(MinecraftServer server) {
        server.markWorldsDirty();
    }
}
