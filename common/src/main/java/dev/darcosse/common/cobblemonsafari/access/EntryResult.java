package dev.darcosse.common.cobblemonsafari.access;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Outcome of an entry request.
 *
 * @param success whether the player is now inside with a session
 * @param reason  why it failed, shown to whoever requested the entry; null on success
 */
public record EntryResult(boolean success, @Nullable Component reason) {

    /** Successful entry. */
    public static EntryResult ok() {
        return new EntryResult(true, null);
    }

    /** Refused entry with a player-facing reason. */
    public static EntryResult denied(Component reason) {
        return new EntryResult(false, reason);
    }
}
