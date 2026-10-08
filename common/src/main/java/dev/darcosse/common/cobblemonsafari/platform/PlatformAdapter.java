package dev.darcosse.common.cobblemonsafari.platform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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
}
