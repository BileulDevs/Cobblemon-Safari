package dev.darcosse.common.cobblemonsafari.bounty;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Every player's progress on the current missions, saved with the world.
 *
 * <p>Progress belongs to one rotation: a player whose stored rotation is not
 * the current one simply has no progress yet. Old rotations are overwritten
 * lazily, never scanned. Attached to the overworld like the other stores.
 */
public final class SafariBountyStore extends SavedData {

    /** File name under data/. Changing it wipes every player's progress of the day. */
    private static final String DATA_NAME = CobblemonSafari.MOD_ID + "_bounties";

    private static final SavedData.Factory<SafariBountyStore> FACTORY =
            new SavedData.Factory<>(SafariBountyStore::new, SafariBountyStore::load, null);

    /** A player's catches toward each mission of one rotation, and whether the all-done bonus was paid. */
    static final class Progress {
        long period;
        int[] counts;
        boolean bonusPaid;

        Progress(long period, int missions) {
            this.period = period;
            this.counts = new int[missions];
        }
    }

    private final Map<UUID, Progress> players = new HashMap<>();

    /** The store of the given server, created on first access. */
    public static SafariBountyStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /**
     * A player's progress for the given rotation, created (all zero) when
     * missing or from another rotation, or when the number of missions changed.
     */
    Progress progress(UUID playerId, long period, int missions) {
        Progress progress = players.get(playerId);
        if (progress == null || progress.period != period || progress.counts.length != missions) {
            progress = new Progress(period, missions);
            players.put(playerId, progress);
            setDirty();
        }
        return progress;
    }

    /** Marks the store for saving after a change to a {@link Progress} it handed out. */
    void changed() {
        setDirty();
    }

    /** Writes every player's progress, keyed by UUID string. */
    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        players.forEach((id, progress) -> {
            CompoundTag tag = new CompoundTag();
            tag.putLong("period", progress.period);
            tag.putIntArray("counts", progress.counts);
            tag.putBoolean("bonus_paid", progress.bonusPaid);
            all.put(id.toString(), tag);
        });
        nbt.put("players", all);
        return nbt;
    }

    /** Reads what {@link #save} wrote, skipping keys that are not UUIDs. */
    private static SafariBountyStore load(CompoundTag nbt, HolderLookup.Provider registries) {
        SafariBountyStore store = new SafariBountyStore();
        CompoundTag all = nbt.getCompound("players");
        for (String key : all.getAllKeys()) {
            try {
                CompoundTag tag = all.getCompound(key);
                int[] counts = tag.getIntArray("counts");
                Progress progress = new Progress(tag.getLong("period"), counts.length);
                progress.counts = counts;
                progress.bonusPaid = tag.getBoolean("bonus_paid");
                store.players.put(UUID.fromString(key), progress);
            } catch (IllegalArgumentException e) {
                CobblemonSafari.LOGGER.warn("Skipping Safari bounty progress with an invalid player id '{}'", key);
            }
        }
        return store;
    }
}
