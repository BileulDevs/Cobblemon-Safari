package dev.darcosse.common.cobblemonsafari.platform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/**
 * Services the common code needs but cannot implement without a loader API.
 *
 * <p>Kept deliberately tiny: every method added here must be implemented twice,
 * so anything that can be written against vanilla belongs in common instead.
 */
public interface PlatformAdapter {

    /** Directory where configuration files live. */
    Path getConfigDir();

    /**
     * Sends a server-to-client payload. Implementations must silently skip
     * clients that cannot receive it rather than throw: a missing HUD is a
     * cosmetic problem, a crashed tick loop is not.
     */
    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /**
     * Tells the loader and the other mods that a level is about to be closed
     * and dropped (Fabric's ServerWorldEvents.UNLOAD, NeoForge's
     * LevelEvent.Unload), as on a server stop. Called by a live map reset
     * while the level is still in the server's map.
     */
    void fireLevelUnload(MinecraftServer server, ServerLevel level);

    /**
     * Tells the loader and the other mods that a level was created and added
     * to the server (Fabric's ServerWorldEvents.LOAD, NeoForge's
     * LevelEvent.Load), and lets the loader refresh what it caches of the
     * server's levels. Called by a live map reset once the new level is in
     * the server's map.
     */
    void fireLevelLoad(MinecraftServer server, ServerLevel level);

    /**
     * Lets the loader drop what it caches of the server's level list after
     * the list changed (NeoForge ticks levels from a cached array). Nothing
     * on Fabric, which ticks the vanilla map.
     */
    void levelsChanged(MinecraftServer server);
}
