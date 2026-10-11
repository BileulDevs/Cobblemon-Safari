package dev.darcosse.common.cobblemonsafari.world;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Per-world facts the Safari must remember across restarts: which rest houses
 * are already built and where each one actually stands, and the salt that
 * makes this world's daily specials differ from another world's.
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
    /** Configured positions (anchors) whose house is built. */
    private final Set<String> builtHouses = new HashSet<>();
    /**
     * Where the house of each anchor was built: the site search may move it
     * away from the configured position to fit the terrain. Anchors missing
     * here (saves from before the search) were built on the anchor itself.
     */
    private final Map<String, String> houseSites = new HashMap<>();
    /** Map salt the built houses belong to; a reset map has none of them. */
    private long housesMapSalt;

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

    /**
     * Forgets the built houses when the Safari map was reset since they were
     * built, so they are built again on the new terrain.
     */
    public void forgetHousesIfMapChanged(long mapSalt) {
        if (housesMapSalt != mapSalt) {
            builtHouses.clear();
            houseSites.clear();
            housesMapSalt = mapSalt;
            setDirty();
        }
    }

    /** Whether a rest house was already built at the given column. */
    public boolean isHouseBuilt(int x, int z) {
        return builtHouses.contains(houseKey(x, z));
    }

    /**
     * Remembers that the house of an anchor is built, and where.
     *
     * @param anchorX configured position
     * @param siteX   centre column of the house actually built
     */
    public void markHouseBuilt(int anchorX, int anchorZ, int siteX, int siteZ) {
        String anchor = houseKey(anchorX, anchorZ);
        builtHouses.add(anchor);
        houseSites.put(anchor, houseKey(siteX, siteZ));
        setDirty();
    }

    /**
     * Centre column {x, z} of the house built for an anchor, or null when
     * that house is not built.
     */
    public int[] houseSite(int anchorX, int anchorZ) {
        String anchor = houseKey(anchorX, anchorZ);
        if (!builtHouses.contains(anchor)) {
            return null;
        }
        String site = houseSites.get(anchor);
        if (site == null) {
            return new int[]{anchorX, anchorZ};
        }
        String[] parts = site.split(",");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    /** Writes the salt, the built houses and their sites. Keys are the save format. */
    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        nbt.putLong("rotation_salt", rotationSalt);
        ListTag houses = new ListTag();
        for (String key : builtHouses) {
            houses.add(StringTag.valueOf(key));
        }
        nbt.put("built_houses", houses);
        CompoundTag sites = new CompoundTag();
        houseSites.forEach(sites::putString);
        nbt.put("house_sites", sites);
        nbt.putLong("houses_map_salt", housesMapSalt);
        return nbt;
    }

    /** Reads what {@link #save} wrote. */
    private static SafariWorldState load(CompoundTag nbt, HolderLookup.Provider registries) {
        SafariWorldState state = new SafariWorldState();
        state.rotationSalt = nbt.getLong("rotation_salt");
        // Absent in older saves: 0, the salt of the original map, which is right.
        state.housesMapSalt = nbt.getLong("houses_map_salt");
        for (Tag house : nbt.getList("built_houses", Tag.TAG_STRING)) {
            state.builtHouses.add(house.getAsString());
        }
        // Absent in older saves: their houses stand on their anchors.
        CompoundTag sites = nbt.getCompound("house_sites");
        for (String anchor : sites.getAllKeys()) {
            state.houseSites.put(anchor, sites.getString(anchor));
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
