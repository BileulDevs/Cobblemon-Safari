package dev.darcosse.common.cobblemonsafari.shop;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How many times each player bought each limited shop entry during the
 * current rotation, saved with the world so a restart does not reset the
 * limits.
 *
 * <p>Counts belong to one rotation: a player whose stored rotation is not the
 * current one has bought nothing yet. Old rotations are overwritten lazily,
 * never scanned. Entries are identified by their content
 * ({@link SafariShop#limitKey}), so reordering the shop file keeps the counts.
 * Attached to the overworld like the other stores. Server side.
 */
final class ShopLimitStore extends SavedData {

    /** File name under data/. Changing it resets every player's counts of the rotation. */
    private static final String DATA_NAME = CobblemonSafari.MOD_ID + "_shop_limits";

    private static final SavedData.Factory<ShopLimitStore> FACTORY =
            new SavedData.Factory<>(ShopLimitStore::new, ShopLimitStore::load, null);

    /** One player's purchases of limited entries during one rotation. */
    private static final class Purchases {
        long period;
        final Map<String, Integer> counts = new HashMap<>();

        Purchases(long period) {
            this.period = period;
        }
    }

    private final Map<UUID, Purchases> players = new HashMap<>();

    /** The store of the given server, created on first access. */
    static ShopLimitStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** How many times a player bought an entry during the given rotation. */
    int bought(UUID playerId, long period, String key) {
        Purchases purchases = players.get(playerId);
        return purchases == null || purchases.period != period ? 0 : purchases.counts.getOrDefault(key, 0);
    }

    /** Counts one more purchase of an entry, starting the player's counts afresh on a new rotation. */
    void record(UUID playerId, long period, String key) {
        Purchases purchases = players.get(playerId);
        if (purchases == null || purchases.period != period) {
            purchases = new Purchases(period);
            players.put(playerId, purchases);
        }
        purchases.counts.merge(key, 1, Integer::sum);
        setDirty();
    }

    /** Writes every player's counts, keyed by UUID string. */
    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        players.forEach((id, purchases) -> {
            CompoundTag tag = new CompoundTag();
            tag.putLong("period", purchases.period);
            CompoundTag counts = new CompoundTag();
            purchases.counts.forEach(counts::putInt);
            tag.put("counts", counts);
            all.put(id.toString(), tag);
        });
        nbt.put("players", all);
        return nbt;
    }

    /** Reads what {@link #save} wrote, skipping keys that are not UUIDs. */
    private static ShopLimitStore load(CompoundTag nbt, HolderLookup.Provider registries) {
        ShopLimitStore store = new ShopLimitStore();
        CompoundTag all = nbt.getCompound("players");
        for (String key : all.getAllKeys()) {
            try {
                CompoundTag tag = all.getCompound(key);
                Purchases purchases = new Purchases(tag.getLong("period"));
                CompoundTag counts = tag.getCompound("counts");
                for (String entry : counts.getAllKeys()) {
                    purchases.counts.put(entry, counts.getInt(entry));
                }
                store.players.put(UUID.fromString(key), purchases);
            } catch (IllegalArgumentException e) {
                CobblemonSafari.LOGGER.warn("Skipping Safari shop limits with an invalid player id '{}'", key);
            }
        }
        return store;
    }
}
