package dev.darcosse.common.cobblemonsafari.points;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Every player's Safari points, saved with the world.
 *
 * <p>Captures in the Safari add to it; the warden's shop spends it. Attached
 * to the overworld like the credit and statistics stores, because the
 * overworld is always loaded.
 */
public final class SafariPointsStore extends SavedData {

    /** File name under data/. Changing it wipes every player's points. */
    private static final String DATA_NAME = CobblemonSafari.MOD_ID + "_points";

    private static final SavedData.Factory<SafariPointsStore> FACTORY =
            new SavedData.Factory<>(SafariPointsStore::new, SafariPointsStore::load, null);

    private final Map<UUID, Integer> points = new HashMap<>();

    /** The store of the given server, created on first access. */
    public static SafariPointsStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Points of a player; 0 when they never earned any. */
    public int get(UUID playerId) {
        return points.getOrDefault(playerId, 0);
    }

    /** Replaces a player's points; zero or less removes the entry. */
    void set(UUID playerId, int value) {
        if (value <= 0) {
            points.remove(playerId);
        } else {
            points.put(playerId, value);
        }
        setDirty();
    }

    /** Writes every balance, keyed by UUID string. */
    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        points.forEach((id, value) -> all.putInt(id.toString(), value));
        nbt.put("players", all);
        return nbt;
    }

    /** Reads the balances written by {@link #save}, skipping keys that are not UUIDs. */
    private static SafariPointsStore load(CompoundTag nbt, HolderLookup.Provider registries) {
        SafariPointsStore store = new SafariPointsStore();
        CompoundTag all = nbt.getCompound("players");
        for (String key : all.getAllKeys()) {
            try {
                store.points.put(UUID.fromString(key), all.getInt(key));
            } catch (IllegalArgumentException e) {
                CobblemonSafari.LOGGER.warn("Skipping Safari points with an invalid player id '{}'", key);
            }
        }
        return store;
    }
}
