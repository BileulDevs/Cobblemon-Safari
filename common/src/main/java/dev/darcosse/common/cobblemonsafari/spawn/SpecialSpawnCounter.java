package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.reset.SafariMapReset;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Enforces the per-rotation cap of capped special species (legendary,
 * mythical, ultra beast, paradox; see {@code SafariConfig.SpecialCategory}).
 *
 * <p>A species uses one slot of its cap per Pokémon caught in the Safari this
 * rotation, plus one per Pokémon of it still roaming the park. A Pokémon that
 * leaves without being caught (despawned, fled, knocked out) gives its slot
 * back, so a missed Rayquaza can come back for someone else; a caught one
 * keeps it until the next rotation.
 *
 * <p>Roaming Pokémon are followed by a sweep every {@link #SWEEP_INTERVAL_SECONDS}
 * rather than by entity removal events, which differ between loaders: an
 * entry whose Pokémon is no longer found around its last position, in a
 * loaded chunk, on {@link #MISSES_BEFORE_GONE} sweeps in a row, is dropped.
 *
 * <p>Saved with the world (attached to the overworld, like the other
 * stores), so restarting mid-rotation neither forgets the catches nor the
 * Pokémon still out there. Everything is dropped when a new rotation starts;
 * roaming entries are also dropped when the Safari map is regenerated, since
 * their Pokémon went with it. Server side.
 */
public final class SpecialSpawnCounter extends SavedData {

    /** File name under data/. Changing it forgets the current rotation's counts once. */
    private static final String DATA_NAME = CobblemonSafari.MOD_ID + "_special_spawns";

    private static final SavedData.Factory<SpecialSpawnCounter> FACTORY =
            new SavedData.Factory<>(SpecialSpawnCounter::new, SpecialSpawnCounter::load, null);

    /**
     * Seconds between two sweeps of the roaming Pokémon. Shorter gives a
     * missed Pokémon's slot back sooner; each sweep costs one entity query
     * per roaming capped Pokémon (a handful at most).
     */
    private static final int SWEEP_INTERVAL_SECONDS = 5;

    /**
     * Consecutive sweeps a Pokémon must be missing before its slot is freed.
     * Entities load a few ticks after their chunk: one miss right after a
     * chunk loads proves nothing. 1 frees slots faster but may free one
     * whose Pokémon is still there.
     */
    private static final int MISSES_BEFORE_GONE = 2;

    /**
     * Half-size of the box searched around a Pokémon's last known position,
     * horizontally and vertically, in blocks. A wild Pokémon walks far less
     * than this between two sweeps; a smaller box risks losing a fast flyer.
     */
    private static final double SEARCH_RADIUS = 32.0D;
    private static final double SEARCH_HALF_HEIGHT = 48.0D;

    /** Server ticks since the last sweep; owned here so the sweep keeps its own cadence. */
    private static int tickCounter;

    /** Rotation the counts belong to; anything else means they are stale. */
    private long period = Long.MIN_VALUE;
    /** Map the roaming entries belong to (SafariMapReset.salt()); another salt means a new map. */
    private long mapSalt = Long.MIN_VALUE;
    /** Pokémon of each capped species caught in the Safari this rotation, by species id. */
    private final Map<String, Integer> caught = new HashMap<>();
    /** Capped Pokémon spawned this rotation and possibly still in the park, by entity UUID. */
    private final Map<UUID, Roaming> roaming = new LinkedHashMap<>();

    /** One roaming Pokémon: its species, where it was last seen, and how many sweeps missed it since. */
    private static final class Roaming {
        private final String species;
        private BlockPos lastSeen;
        private int misses;

        /** A Pokémon just spawned, at its spawn position. */
        private Roaming(String species, BlockPos lastSeen) {
            this.species = species;
            this.lastSeen = lastSeen;
        }
    }

    /** Subscribes to Cobblemon's capture event. Called once from common init. */
    public static void register() {
        // LOWEST: the capture is final by then, as for the catch records.
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.LOWEST, SpecialSpawnCounter::onCaptured);
    }

    /** The counter of the given server, created on first access. */
    public static SpecialSpawnCounter get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /**
     * Whether one more of the species may appear now. Always true for a
     * species without a cap, so callers need no category check of their own.
     */
    public static boolean canSpawn(MinecraftServer server, Species species) {
        int cap = SpeciesRules.spawnCap(species);
        return cap <= 0 || get(server).used(species.getResourceIdentifier().toString()) < cap;
    }

    /**
     * Takes one slot for a Pokémon of a capped species entering the Safari.
     * Call it once the spawn is decided; the slot comes back by itself if the
     * Pokémon is never found in the park (see the class comment).
     *
     * @param entityId UUID of the Pokémon's entity
     * @param pos      where it appears
     */
    public static void recordSpawn(MinecraftServer server, Species species, UUID entityId, BlockPos pos) {
        if (SpeciesRules.spawnCap(species) <= 0) {
            return;
        }
        SpecialSpawnCounter counter = get(server);
        counter.refresh();
        counter.roaming.put(entityId, new Roaming(species.getResourceIdentifier().toString(), pos.immutable()));
        counter.setDirty();
    }

    /**
     * Runs from the server tick; sweeps the roaming Pokémon every
     * {@link #SWEEP_INTERVAL_SECONDS}. Cost: one counter increment per tick,
     * and on sweep ticks one small entity query per roaming capped Pokémon
     * whose chunk is loaded; nothing at all while none roams.
     */
    public static void tick(MinecraftServer server) {
        if (++tickCounter < Ticks.fromSeconds(SWEEP_INTERVAL_SECONDS)) {
            return;
        }
        tickCounter = 0;
        SpecialSpawnCounter counter = get(server);
        counter.refresh();
        if (counter.roaming.isEmpty()) {
            return;
        }
        ServerLevel safari = SafariDimensions.level(server);
        if (safari == null) {
            return;
        }
        counter.sweep(safari);
    }

    /** Forgets the cached cadence; call when a server starts, since single player reuses this class. */
    public static void reset() {
        tickCounter = 0;
    }

    /** A capped species caught inside the Safari keeps its slot until the next rotation. */
    private static void onCaptured(PokemonCapturedEvent event) {
        MinecraftServer server = event.getPlayer().getServer();
        if (server == null || !SafariDimensions.isSafari(event.getPlayer().level())) {
            return;
        }
        Species species = event.getPokemon().getSpecies();
        if (SpeciesRules.spawnCap(species) <= 0) {
            return;
        }
        SpecialSpawnCounter counter = get(server);
        counter.refresh();
        counter.caught.merge(species.getResourceIdentifier().toString(), 1, Integer::sum);
        // Its roaming entry is dropped by the next sweeps, which no longer find
        // the entity; until then the Pokémon counts twice, on the safe side.
        counter.setDirty();
    }

    /** Slots of the species in use: caught this rotation plus still roaming. */
    private int used(String species) {
        refresh();
        int count = caught.getOrDefault(species, 0);
        for (Roaming entry : roaming.values()) {
            if (entry.species.equals(species)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Updates each roaming entry: found again, its position follows; missing
     * from a loaded area too many times, it is dropped. Entries in unloaded
     * chunks are left alone, their Pokémon is saved there.
     */
    private void sweep(ServerLevel safari) {
        boolean changed = false;
        Iterator<Map.Entry<UUID, Roaming>> it = roaming.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Roaming> entry = it.next();
            Roaming pokemon = entry.getValue();
            if (!safari.hasChunkAt(pokemon.lastSeen)) {
                continue;
            }
            UUID id = entry.getKey();
            AABB area = new AABB(pokemon.lastSeen).inflate(SEARCH_RADIUS, SEARCH_HALF_HEIGHT, SEARCH_RADIUS);
            PokemonEntity found = safari.getEntitiesOfClass(PokemonEntity.class, area,
                    entity -> entity.getUUID().equals(id)).stream().findFirst().orElse(null);
            if (found != null) {
                pokemon.lastSeen = found.blockPosition();
                pokemon.misses = 0;
            } else if (++pokemon.misses >= MISSES_BEFORE_GONE) {
                it.remove();
            }
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    /** Forgets a finished rotation, and the roaming entries of a map that was regenerated. */
    private void refresh() {
        long now = FeaturedRotation.period();
        if (now != period) {
            period = now;
            caught.clear();
            roaming.clear();
            setDirty();
        }
        long salt = SafariMapReset.salt();
        if (salt != mapSalt) {
            mapSalt = salt;
            roaming.clear();
            setDirty();
        }
    }

    /** Writes the rotation, map, catches and roaming Pokémon. Keys are the save format. */
    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        nbt.putLong("period", period);
        nbt.putLong("map_salt", mapSalt);
        CompoundTag caughtTag = new CompoundTag();
        caught.forEach(caughtTag::putInt);
        nbt.put("caught", caughtTag);
        ListTag roamingTag = new ListTag();
        roaming.forEach((id, pokemon) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", id);
            entry.putString("species", pokemon.species);
            entry.putInt("x", pokemon.lastSeen.getX());
            entry.putInt("y", pokemon.lastSeen.getY());
            entry.putInt("z", pokemon.lastSeen.getZ());
            roamingTag.add(entry);
        });
        nbt.put("roaming", roamingTag);
        return nbt;
    }

    /** Reads what {@link #save} wrote. Missing keys read as an empty, stale counter. */
    private static SpecialSpawnCounter load(CompoundTag nbt, HolderLookup.Provider registries) {
        SpecialSpawnCounter counter = new SpecialSpawnCounter();
        counter.period = nbt.contains("period") ? nbt.getLong("period") : Long.MIN_VALUE;
        counter.mapSalt = nbt.contains("map_salt") ? nbt.getLong("map_salt") : Long.MIN_VALUE;
        CompoundTag caughtTag = nbt.getCompound("caught");
        for (String key : caughtTag.getAllKeys()) {
            counter.caught.put(key, caughtTag.getInt(key));
        }
        for (Tag element : nbt.getList("roaming", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) element;
            counter.roaming.put(entry.getUUID("id"), new Roaming(entry.getString("species"),
                    new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"))));
        }
        return counter;
    }
}
