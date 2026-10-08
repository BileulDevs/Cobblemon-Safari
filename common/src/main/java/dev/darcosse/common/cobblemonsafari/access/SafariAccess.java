package dev.darcosse.common.cobblemonsafari.access;

import dev.darcosse.common.cobblemonsafari.session.SafariSessionManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Public entry point for anything that lets a player into the Safari.
 *
 * <p>Access methods (command, NPC, ticket item, paid gate...) all call
 * {@link #requestEntry}; they differ only in the duration they grant and the
 * requirements they register. No access method is hard-coded here.
 */
public final class SafariAccess {

    private static final List<EntryRequirement> REQUIREMENTS = new CopyOnWriteArrayList<>();

    private SafariAccess() {
    }

    /** Adds a requirement applied to every non-bypassing entry. */
    public static void addRequirement(EntryRequirement requirement) {
        REQUIREMENTS.add(requirement);
    }

    /**
     * Tries to start a session and send the player in.
     *
     * @param durationTicks       time granted; clamped to the configured maximum
     * @param source              free-form id of what granted the entry, kept on the session
     * @param bypassRequirements  true for operator commands, which must work even when a fee is misconfigured
     */
    public static EntryResult requestEntry(ServerPlayer player, int durationTicks, String source,
                                           boolean bypassRequirements) {
        if (!bypassRequirements) {
            for (EntryRequirement requirement : REQUIREMENTS) {
                Component denial = requirement.check(player);
                if (denial != null) {
                    return EntryResult.denied(denial);
                }
            }
        }
        EntryResult result = SafariSessionManager.start(player, durationTicks, source);
        if (result.success() && !bypassRequirements) {
            for (EntryRequirement requirement : REQUIREMENTS) {
                requirement.consume(player);
            }
        }
        return result;
    }
}
