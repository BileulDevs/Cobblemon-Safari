package dev.darcosse.common.cobblemonsafari.world;

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
import java.util.Random;
import java.util.Set;

/**
 * Per-world facts the Safari must remember across restarts: which rest houses
 * are already built and where each one actually stands, where the others
 * will be built once a player comes near them, and the salt that
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
    /**
     * Rotations started by hand (/safari rotate), added to the rotation
     * index computed from the clock. Only ever grows, so a forced rotation
     * never reuses the number, and thus the draw, bounty progress and shop
     * limits, of an earlier one.
     */
    private long rotationOffset;
    /**
     * The last rotation whose specials were drawn, those specials, and the
     * ones that draw had to leave out (the specials of the rotation before).
     * Kept so a redraw of the same rotation (restart, reload) excludes the
     * same species and comes out the same; see {@link #excludedFromSpecials}.
     */
    private long specialsPeriod = Long.MIN_VALUE;
    private final List<String> specials = new ArrayList<>();
    private final List<String> specialsExcluded = new ArrayList<>();
    /** Anchors (park.ParkGeometry.REST_HOUSES) whose house is built. */
    private final Set<String> builtHouses = new HashSet<>();
    /**
     * Where the house of each anchor was built: the site search may move it
     * away from its anchor to fit the terrain. A built anchor missing here
     * is taken as built on the anchor itself.
     */
    private final Map<String, String> houseSites = new HashMap<>();
    /**
     * Sites chosen for the houses not built yet, per anchor, best first: the
     * first is where the house will stand, the others are used in turn if
     * the real terrain shows water there. Kept so a restart before a house
     * is built does not search again, and the house keeps the spot already
     * sent to clients.
     */
    private final Map<String, List<String>> housePlans = new HashMap<>();
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

    /** Rotations started by hand so far; see {@link #rotationOffset}. */
    public long rotationOffset() {
        return rotationOffset;
    }

    /**
     * The species the draw of a rotation must leave out: the specials of the
     * last rotation drawn before it, so no special comes back right away. A
     * rotation drawn again (restart, datapack reload) gets the same answer.
     */
    public List<String> excludedFromSpecials(long period) {
        return period == specialsPeriod ? List.copyOf(specialsExcluded) : List.copyOf(specials);
    }

    /**
     * Records the specials of a rotation. The previous rotation's become the
     * excluded ones the first time a new rotation is recorded; a redraw of
     * the same rotation keeps them.
     */
    public void recordSpecials(long period, List<String> drawn) {
        if (period != specialsPeriod) {
            specialsExcluded.clear();
            specialsExcluded.addAll(specials);
            specialsPeriod = period;
        }
        if (!specials.equals(drawn)) {
            specials.clear();
            specials.addAll(drawn);
        }
        setDirty();
    }

    /** Counts one more rotation started by hand and asks to be saved. */
    public void advanceRotation() {
        rotationOffset++;
        setDirty();
    }

    /**
     * Forgets the built houses when the Safari map was reset since they were
     * built, so they are built again on the new terrain.
     */
    public void forgetHousesIfMapChanged(long mapSalt) {
        if (housesMapSalt != mapSalt) {
            builtHouses.clear();
            houseSites.clear();
            // Sites were chosen on the old terrain.
            housePlans.clear();
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
     * @param anchorX anchor of the house (park.ParkGeometry.REST_HOUSES)
     * @param siteX   centre column of the house actually built
     */
    public void markHouseBuilt(int anchorX, int anchorZ, int siteX, int siteZ) {
        String anchor = houseKey(anchorX, anchorZ);
        builtHouses.add(anchor);
        houseSites.put(anchor, houseKey(siteX, siteZ));
        housePlans.remove(anchor);
        setDirty();
    }

    /**
     * Sites still open for the house of an anchor, {x, z} each, best first;
     * empty when the house is built or no site was chosen yet.
     */
    public List<int[]> housePlan(int anchorX, int anchorZ) {
        List<int[]> result = new ArrayList<>();
        for (String site : housePlans.getOrDefault(houseKey(anchorX, anchorZ), List.of())) {
            result.add(parseColumn(site));
        }
        return result;
    }

    /**
     * Records the sites chosen for the house of an anchor, best first;
     * an empty list forgets them.
     */
    public void setHousePlan(int anchorX, int anchorZ, List<int[]> sites) {
        String anchor = houseKey(anchorX, anchorZ);
        if (sites.isEmpty()) {
            housePlans.remove(anchor);
        } else {
            List<String> keys = new ArrayList<>();
            for (int[] site : sites) {
                keys.add(houseKey(site[0], site[1]));
            }
            housePlans.put(anchor, keys);
        }
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
        return parseColumn(site);
    }

    /** A column {x, z} from its key, see {@link #houseKey}. */
    private static int[] parseColumn(String key) {
        String[] parts = key.split(",");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    /** Writes the salt, the built houses, their sites and the planned ones. Keys are the save format. */
    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        nbt.putLong("rotation_salt", rotationSalt);
        nbt.putLong("rotation_offset", rotationOffset);
        nbt.putLong("specials_period", specialsPeriod);
        nbt.put("specials", strings(specials));
        nbt.put("specials_excluded", strings(specialsExcluded));
        ListTag houses = new ListTag();
        for (String key : builtHouses) {
            houses.add(StringTag.valueOf(key));
        }
        nbt.put("built_houses", houses);
        CompoundTag sites = new CompoundTag();
        houseSites.forEach(sites::putString);
        nbt.put("house_sites", sites);
        CompoundTag plans = new CompoundTag();
        housePlans.forEach((anchor, planned) -> plans.put(anchor, strings(planned)));
        nbt.put("house_plans", plans);
        nbt.putLong("houses_map_salt", housesMapSalt);
        return nbt;
    }

    /** Reads what {@link #save} wrote. */
    private static SafariWorldState load(CompoundTag nbt, HolderLookup.Provider registries) {
        SafariWorldState state = new SafariWorldState();
        state.rotationSalt = nbt.getLong("rotation_salt");
        // Absent: 0, no rotation forced yet.
        state.rotationOffset = nbt.getLong("rotation_offset");
        // Absent: no rotation recorded, so nothing is excluded.
        state.specialsPeriod = nbt.contains("specials_period") ? nbt.getLong("specials_period") : Long.MIN_VALUE;
        for (Tag species : nbt.getList("specials", Tag.TAG_STRING)) {
            state.specials.add(species.getAsString());
        }
        for (Tag species : nbt.getList("specials_excluded", Tag.TAG_STRING)) {
            state.specialsExcluded.add(species.getAsString());
        }
        // Absent: 0, the salt of the original map.
        state.housesMapSalt = nbt.getLong("houses_map_salt");
        for (Tag house : nbt.getList("built_houses", Tag.TAG_STRING)) {
            state.builtHouses.add(house.getAsString());
        }
        // Absent: built houses stand on their anchors (see houseSite).
        CompoundTag sites = nbt.getCompound("house_sites");
        for (String anchor : sites.getAllKeys()) {
            state.houseSites.put(anchor, sites.getString(anchor));
        }
        // Absent: unbuilt houses get their sites chosen again.
        CompoundTag plans = nbt.getCompound("house_plans");
        for (String anchor : plans.getAllKeys()) {
            List<String> planned = new ArrayList<>();
            for (Tag site : plans.getList(anchor, Tag.TAG_STRING)) {
                planned.add(site.getAsString());
            }
            if (!planned.isEmpty()) {
                state.housePlans.put(anchor, planned);
            }
        }
        // Loading is not a change: undo the dirty flag the constructor set.
        state.setDirty(false);
        return state;
    }

    /** Key identifying a rest house by its column. */
    private static String houseKey(int x, int z) {
        return x + "," + z;
    }

    /** A list of strings as an NBT list. */
    private static ListTag strings(List<String> values) {
        ListTag list = new ListTag();
        for (String value : values) {
            list.add(StringTag.valueOf(value));
        }
        return list;
    }
}
