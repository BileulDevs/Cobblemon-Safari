package dev.darcosse.common.cobblemonsafari.access;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * A condition a player must meet to enter the Safari: a fee, a ticket, a
 * quest flag, a cooldown...
 *
 * <p>Entry is two-phase so that a failing requirement never leaves a player
 * charged by another one: every requirement is {@link #check checked} first,
 * and only if all pass, and the player actually arrives, is each one
 * {@link #consume consumed}.
 */
public interface EntryRequirement {

    /**
     * @return null when the player may enter, otherwise the reason shown to them
     */
    @Nullable
    Component check(ServerPlayer player);

    /** Takes whatever the requirement costs. Only called after every check passed and the teleport succeeded. */
    default void consume(ServerPlayer player) {
    }
}
