package dev.darcosse.common.cobblemonsafari.world;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Per-world facts the Safari must remember across restarts: which rest houses
 * are already built, and the salt that makes this world's daily specials
 * differ from another world's.
 */
public final class SafariWorldState extends SavedData {

    /** File name under data/. Changing it rebuilds every rest house on top of the old ones. */
    private static final String DATA_NAME = CobblemonSafari.MOD_ID + "_world";

    private static final SavedData.Factory<SafariWorldState> FACTORY =
            new SavedData.Factory<>(SafariWorldState::new, SafariWorldState::load, null);

    /**
     * Random per world, drawn once. Mixed with the rotation index so that two
     * servers do not show the same specials on the same day.
     */
    private long rotationSalt;
    private final Set<String> builtHouses = new HashSet<>();

    /** Fresh state for a world that never had one; draws the salt and asks to be saved. */
    private SafariWorldState() {
        this.rotationSalt = new Random().nextLong();
        setDirty();
    }

    /** The state of the given server, created on first access. */
    public static SafariWorldState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Salt of this world's rotation. */
    public long rotationSalt() {
        return rotationSalt;
    }

    /** Whether a rest house was already built at the given column. */
    public boolean isHouseBuilt(int x, int z) {
        return builtHouses.contains(houseKey(x, z));
    }

    /** Remembers that a rest house now stands at the given column. */
    public void markHouseBuilt(int x, int z) {
        if (builtHouses.add(houseKey(x, z))) {
            setDirty();
        }
    }

    /** Writes the salt and the built houses. Keys are the save format. */
    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        nbt.putLong("rotation_salt", rotationSalt);
        ListTag houses = new ListTag();
        for (String key : builtHouses) {
            houses.add(StringTag.valueOf(key));
        }
        nbt.put("built_houses", houses);
        return nbt;
    }

    /** Reads what {@link #save} wrote. */
    private static SafariWorldState load(CompoundTag nbt, HolderLookup.Provider registries) {
        SafariWorldState state = new SafariWorldState();
        state.rotationSalt = nbt.getLong("rotation_salt");
        for (Tag house : nbt.getList("built_houses", Tag.TAG_STRING)) {
            state.builtHouses.add(house.getAsString());
        }
        // Loading is not a change: undo the dirty flag the constructor set.
        state.setDirty(false);
        return state;
    }

    /** Key identifying a rest house by its column. */
    private static String houseKey(int x, int z) {
        return x + "," + z;
    }
}
