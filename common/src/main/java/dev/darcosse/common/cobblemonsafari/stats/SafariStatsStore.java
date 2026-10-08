package dev.darcosse.common.cobblemonsafari.stats;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persists every player's Safari statistics with the world save.
 *
 * <p>Separate from the session store on purpose: sessions are short-lived and
 * deleted on exit, statistics accumulate forever. Attached to the overworld
 * for the same reason as the session store (always loaded).
 */
public final class SafariStatsStore extends SavedData {

    /** File name under data/. Changing it starts every player's record from zero. */
    private static final String DATA_NAME = CobblemonSafari.MOD_ID + "_stats";

    private static final SavedData.Factory<SafariStatsStore> FACTORY =
            new SavedData.Factory<>(SafariStatsStore::new, SafariStatsStore::load, null);

    private final Map<UUID, PlayerSafariStats> players = new HashMap<>();

    /** The store of the given server, created on first access. */
    public static SafariStatsStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** A player's statistics, or null when they never played the Safari. */
    @Nullable
    public PlayerSafariStats find(UUID playerId) {
        return players.get(playerId);
    }

    /** A player's statistics, created empty on first use. Callers that mutate must mark the store dirty. */
    PlayerSafariStats getOrCreate(UUID playerId) {
        return players.computeIfAbsent(playerId, id -> new PlayerSafariStats());
    }

    /**
     * The best hunters by total catches, ties broken by best single session.
     * Sorts the whole map: fine for a command, never call it per tick.
     */
    public List<PlayerSafariStats> top(int limit) {
        return players.values().stream()
                .filter(stats -> stats.totalCatches() > 0)
                .sorted(Comparator.comparingInt(PlayerSafariStats::totalCatches)
                        .thenComparingInt(PlayerSafariStats::bestSessionCatches)
                        .reversed())
                .limit(limit)
                .toList();
    }

    /** Writes every player record, keyed by UUID string. */
    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        players.forEach((id, stats) -> all.put(id.toString(), stats.save()));
        nbt.put("players", all);
        return nbt;
    }

    /** Reads the records written by {@link #save}, skipping entries whose key is not a UUID. */
    private static SafariStatsStore load(CompoundTag nbt, HolderLookup.Provider registries) {
        SafariStatsStore store = new SafariStatsStore();
        CompoundTag all = nbt.getCompound("players");
        for (String key : all.getAllKeys()) {
            try {
                store.players.put(UUID.fromString(key), PlayerSafariStats.load(all.getCompound(key)));
            } catch (IllegalArgumentException e) {
                CobblemonSafari.LOGGER.warn("Skipping Safari statistics with an invalid player id '{}'", key);
            }
        }
        return store;
    }
}
