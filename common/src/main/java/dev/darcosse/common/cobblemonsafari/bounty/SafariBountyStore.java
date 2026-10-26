package dev.darcosse.common.cobblemonsafari.bounty;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every player's progress on the current missions, saved with the world:
 * catches per mission, species already counted by a "different species"
 * mission, and the missions the player swapped at the clerk.
 *
 * <p>Progress belongs to one rotation: a player whose stored rotation is not
 * the current one simply has no progress yet. Old rotations are overwritten
 * lazily, never scanned. Attached to the overworld like the other stores.
 */
public final class SafariBountyStore extends SavedData {

    /** File name under data/. Changing it wipes every player's progress of the day. */
    private static final String DATA_NAME = CobblemonSafari.MOD_ID + "_bounties";

    /**
     * Version of the saved format. 1 (no key): counts and bonus only. 2: adds
     * the seen species and the swaps. Raise it on every change to save/load.
     */
    private static final int DATA_VERSION = 2;

    private static final SavedData.Factory<SafariBountyStore> FACTORY =
            new SavedData.Factory<>(SafariBountyStore::new, SafariBountyStore::load, null);

    /** A player's progress on the missions of one rotation. */
    static final class Progress {
        long period;
        int[] counts;
        boolean bonusPaid;
        /** Per mission, the species already counted (ids), for {@link BountyKind#DIFFERENT}. */
        List<Set<String>> seen;
        /** Missions this player swapped, by slot; they replace the shared ones of those slots. */
        Map<Integer, Bounty> swapped = new HashMap<>();
        /** Swaps made this rotation. */
        int swaps;

        /** Fresh progress, nothing caught yet, for a rotation with the given number of missions. */
        Progress(long period, int missions) {
            this.period = period;
            this.counts = new int[missions];
            this.seen = new ArrayList<>(missions);
            for (int i = 0; i < missions; i++) {
                seen.add(new HashSet<>());
            }
        }

        /** Starts a slot over, for a swapped mission. */
        void clearSlot(int slot) {
            counts[slot] = 0;
            seen.get(slot).clear();
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

    /** Writes every player's progress, keyed by UUID string. MUST match {@link #load}. */
    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        players.forEach((id, progress) -> {
            CompoundTag tag = new CompoundTag();
            tag.putLong("period", progress.period);
            tag.putIntArray("counts", progress.counts);
            tag.putBoolean("bonus_paid", progress.bonusPaid);
            ListTag seen = new ListTag();
            for (Set<String> species : progress.seen) {
                ListTag one = new ListTag();
                species.forEach(name -> one.add(StringTag.valueOf(name)));
                seen.add(one);
            }
            tag.put("seen", seen);
            CompoundTag swapped = new CompoundTag();
            progress.swapped.forEach((slot, bounty) -> swapped.put(Integer.toString(slot), write(bounty)));
            tag.put("swapped", swapped);
            tag.putInt("swaps", progress.swaps);
            all.put(id.toString(), tag);
        });
        nbt.putInt("version", DATA_VERSION);
        nbt.put("players", all);
        return nbt;
    }

    /**
     * Reads what {@link #save} wrote, skipping keys that are not UUIDs and
     * swapped missions of a kind this build does not know. A version 1 file
     * reads with no seen species and no swaps.
     */
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
                ListTag seen = tag.getList("seen", Tag.TAG_LIST);
                for (int i = 0; i < Math.min(seen.size(), counts.length); i++) {
                    ListTag one = seen.getList(i);
                    for (int j = 0; j < one.size(); j++) {
                        progress.seen.get(i).add(one.getString(j));
                    }
                }
                CompoundTag swapped = tag.getCompound("swapped");
                for (String slot : swapped.getAllKeys()) {
                    Bounty bounty = read(swapped.getCompound(slot));
                    int index = Integer.parseInt(slot);
                    if (bounty != null && index >= 0 && index < counts.length) {
                        progress.swapped.put(index, bounty);
                    }
                }
                progress.swaps = tag.getInt("swaps");
                store.players.put(UUID.fromString(key), progress);
            } catch (IllegalArgumentException e) {
                // UUID.fromString and Integer.parseInt both throw subclasses of this.
                CobblemonSafari.LOGGER.warn("Skipping Safari bounty progress with an invalid key under '{}'", key);
            }
        }
        return store;
    }

    /** A mission as NBT, its kind by name so that adding kinds never shifts saved ones. */
    private static CompoundTag write(Bounty bounty) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", bounty.kind().name());
        tag.putString("target", bounty.target());
        tag.putInt("count", bounty.count());
        tag.putInt("reward", bounty.reward());
        return tag;
    }

    /** A mission written by {@link #write}; null for a kind this build does not know. */
    private static Bounty read(CompoundTag tag) {
        try {
            return new Bounty(BountyKind.valueOf(tag.getString("kind")), tag.getString("target"),
                    Math.max(1, tag.getInt("count")), tag.getInt("reward"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
