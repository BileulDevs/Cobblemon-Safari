package dev.darcosse.common.cobblemonsafari.credit;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.access.EntryResult;
import dev.darcosse.common.cobblemonsafari.access.SafariAccess;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.session.SafariSession;
import dev.darcosse.common.cobblemonsafari.session.SafariSessionListener;
import dev.darcosse.common.cobblemonsafari.session.SafariSessionManager;
import dev.darcosse.common.cobblemonsafari.session.SessionEndReason;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Banked Safari time: what tickets buy and what entering the Safari spends.
 *
 * <p>The one place that reads and writes {@link SafariCreditStore}, so the cap
 * and the refund rule cannot be bypassed. Server side only.
 */
public final class SafariCredit {

    /** Source id of sessions paid with banked time; only those are refunded. */
    public static final String SOURCE = CobblemonSafari.MOD_ID + ":credit";

    private SafariCredit() {
    }

    /** Registers the refund listener. Called once from common init. */
    public static void register() {
        SafariSessionManager.addListener(new SafariSessionListener() {
            /**
             * Gives back the unused time of a hunt paid with credit, when the
             * player left on purpose or died inside. Death counts as leaving:
             * players inside are invulnerable by default, so only /kill or the
             * void kill them there, and neither should cost the hunt.
             */
            @Override
            public void onEnd(ServerPlayer player, SafariSession session, SessionEndReason reason) {
                if ((reason == SessionEndReason.VOLUNTARY || reason == SessionEndReason.DEATH)
                        && SOURCE.equals(session.source())
                        && SafariConfigManager.get().ticket.refundUnusedTimeOnLeave
                        && session.remainingTicks() > 0) {
                    int refunded = add(player, session.remainingTicks());
                    if (refunded > 0) {
                        player.sendSystemMessage(Component.translatable(key("message.credit_refunded"),
                                Ticks.format(refunded), Ticks.format(balance(player))));
                    }
                }
            }
        });
    }

    /** A player's banked time, in ticks. */
    public static int balance(ServerPlayer player) {
        return SafariCreditStore.get(player.getServer()).get(player.getUUID());
    }

    /**
     * Banks time, up to the configured cap.
     *
     * @return the ticks actually added; 0 when the bank is already full
     */
    public static int add(ServerPlayer player, int deltaTicks) {
        SafariCreditStore store = SafariCreditStore.get(player.getServer());
        int before = store.get(player.getUUID());
        int cap = Ticks.fromSeconds(SafariConfigManager.get().ticket.maxCreditSeconds);
        // long: a huge configured cap plus a huge ticket must not wrap around.
        int after = (int) Math.min(cap, (long) before + Math.max(0, deltaTicks));
        if (after > before) {
            store.set(player.getUUID(), after);
        }
        return Math.max(0, after - before);
    }

    /**
     * Enters the Safari, paying with banked time: as much as one session may
     * last, the rest stays in the bank. Entry requirements still apply.
     */
    public static EntryResult enter(ServerPlayer player) {
        return enter(player, false);
    }

    /**
     * Enters the Safari, paying with banked time.
     *
     * <p>Banked time is the only currency: no entry path hands out free time,
     * so a hunt always traces back to a ticket (or an operator's explicit
     * {@code /safari credit add}).
     *
     * @param bypassRequirements true for operator commands, which must keep working when a fee is misconfigured
     */
    public static EntryResult enter(ServerPlayer player, boolean bypassRequirements) {
        int banked = balance(player);
        if (banked <= 0) {
            return EntryResult.denied(Component.translatable(key("message.no_credit")));
        }
        int spent = Math.min(banked, SafariConfigManager.maxDurationTicks());
        EntryResult result = SafariAccess.requestEntry(player, spent, SOURCE, bypassRequirements);
        if (result.success()) {
            SafariCreditStore.get(player.getServer()).set(player.getUUID(), banked - spent);
        }
        return result;
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
