package dev.darcosse.common.cobblemonsafari.session;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;

/**
 * Why a session ended. Drives the message shown to the player and lets
 * listeners (statistics, rewards) tell a completed hunt from an aborted one.
 */
public enum SessionEndReason {
    /** The clock ran out. */
    EXPIRED(true),
    /** The player asked to leave. */
    VOLUNTARY(true),
    /** An operator ended it. */
    ADMIN(true),
    /** The player died inside; the respawn takes them out, no teleport needed. */
    DEATH(false),
    /** The player ended up outside the Safari by other means (portal, /tp, respawn...). */
    LEFT(false);

    /** Whether ending with this reason must teleport the player out. */
    public final boolean teleportsOut;

    SessionEndReason(boolean teleportsOut) {
        this.teleportsOut = teleportsOut;
    }

    /** Translation key of the chat message announcing this end. */
    public String translationKey() {
        return CobblemonSafari.MOD_ID + ".session.end." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
