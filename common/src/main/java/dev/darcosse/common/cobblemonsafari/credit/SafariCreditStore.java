package dev.darcosse.common.cobblemonsafari.credit;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Every player's banked Safari time ("crédit"), saved with the world.
 *
 * <p>Tickets add to it; entering the Safari spends it. Attached to the
 * overworld like the session and statistics stores, because the overworld is
 * always loaded.
 */
public final class SafariCreditStore extends SavedData {

    /** File name under data/. Changing it wipes every player's banked time. */
    private static final String DATA_NAME = CobblemonSafari.MOD_ID + "_credit";

    private static final SavedData.Factory<SafariCreditStore> FACTORY =
            new SavedData.Factory<>(SafariCreditStore::new, SafariCreditStore::load, null);

    private final Map<UUID, Integer> ticks = new HashMap<>();

    /** The store of the given server, created on first access. */
    public static SafariCreditStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Banked time of a player, in ticks; 0 when they never bought any. */
    public int get(UUID playerId) {
        return ticks.getOrDefault(playerId, 0);
    }

    /** Replaces a player's banked time; zero or less removes the entry. */
    void set(UUID playerId, int value) {
        if (value <= 0) {
            ticks.remove(playerId);
        } else {
            ticks.put(playerId, value);
        }
        setDirty();
    }

    /** Writes every balance, keyed by UUID string. */
    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        ticks.forEach((id, value) -> all.putInt(id.toString(), value));
        nbt.put("players", all);
        return nbt;
    }

    /** Reads the balances written by {@link #save}, skipping keys that are not UUIDs. */
    private static SafariCreditStore load(CompoundTag nbt, HolderLookup.Provider registries) {
        SafariCreditStore store = new SafariCreditStore();
        CompoundTag all = nbt.getCompound("players");
        for (String key : all.getAllKeys()) {
            try {
                store.ticks.put(UUID.fromString(key), all.getInt(key));
            } catch (IllegalArgumentException e) {
                CobblemonSafari.LOGGER.warn("Skipping Safari credit with an invalid player id '{}'", key);
            }
        }
        return store;
    }
}
