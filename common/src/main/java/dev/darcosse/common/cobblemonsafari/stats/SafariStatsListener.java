package dev.darcosse.common.cobblemonsafari.stats;

import dev.darcosse.common.cobblemonsafari.network.SafariNetwork;
import dev.darcosse.common.cobblemonsafari.session.CatchRecord;
import dev.darcosse.common.cobblemonsafari.session.SafariSession;
import dev.darcosse.common.cobblemonsafari.session.SafariSessionListener;
import dev.darcosse.common.cobblemonsafari.session.SessionEndReason;
import net.minecraft.server.level.ServerPlayer;

/**
 * Feeds {@link SafariStatsStore} from session events, and pushes the new
 * record to the player for the wall screens' STATS page. Registered through
 * the public session listener API, like any third-party feature would be.
 */
public final class SafariStatsListener implements SafariSessionListener {

    /** Counts the catch the moment it happens, so a crash before the exit loses nothing. */
    @Override
    public void onCatch(ServerPlayer player, SafariSession session, CatchRecord record) {
        SafariStatsStore store = SafariStatsStore.get(player.getServer());
        PlayerSafariStats stats = store.getOrCreate(player.getUUID());
        stats.setName(player.getGameProfile().getName());
        stats.addCatch(record.speciesId(), record.shiny());
        store.setDirty();
        SafariNetwork.sendStats(player);
    }

    /** Closes the session in the record: count, best score, time hunted. */
    @Override
    public void onEnd(ServerPlayer player, SafariSession session, SessionEndReason reason) {
        SafariStatsStore store = SafariStatsStore.get(player.getServer());
        PlayerSafariStats stats = store.getOrCreate(player.getUUID());
        stats.setName(player.getGameProfile().getName());
        stats.addFinishedSession(session.catches().size(), session.ticksUsed());
        store.setDirty();
        SafariNetwork.sendStats(player);
    }
}
