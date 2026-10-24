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

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Enforces the per-rotation cap of capped special species (legendary,
 * mythical, ultra beast, paradox; see {@code SafariConfig.SpecialCategory}).
 *
 * <p>Each way into the Safari ({@link SpawnSource}) has its own count and
 * its own cap, read from its config section: a Rayquaza brought by the board
 * never uses a slot of the natural spawns, nor the reverse. A catch is
 * charged to the way its Pokémon came in, found by the Pokémon's UUID among
 * the roaming entries; one the counter never saw spawn counts as natural.
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

    /**
     * Save format version. 1 (no key) had one count for both ways; 2 counts
     * each way apart and keeps each roaming Pokémon's way and Pokémon UUID.
     * A version 1 save loads with everything charged to the natural spawns.
     */
    private static final int SAVE_VERSION = 2;

    /** Key of the catches charged to each way; the natural one keeps version 1's key, so old saves read into it. */
    private static final String CAUGHT_NATURAL_KEY = "caught";
    private static final String CAUGHT_FEATURED_KEY = "caught_featured";

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
    /** Pokémon of each capped species caught in the Safari this rotation, by way in, then by species id. */
    private final Map<SpawnSource, Map<String, Integer>> caught = new EnumMap<>(SpawnSource.class);
    /** Capped Pokémon spawned this rotation and possibly still in the park, by entity UUID. */
    private final Map<UUID, Roaming> roaming = new LinkedHashMap<>();

    /**
     * One roaming Pokémon: its species, the way it came in, where it was
     * last seen, and how many sweeps missed it since.
     */
    private static final class Roaming {
        private final String species;
        private final SpawnSource source;
        /**
         * UUID of the Pokémon (not of its entity), which survives the
         * capture: the capture event carries the Pokémon, whose entity is
         * already discarded by then. Null for an entry of a version 1 save.
         */
        @Nullable
        private final UUID pokemonId;
        private BlockPos lastSeen;
        private int misses;

        /** A Pokémon just spawned, at its spawn position. */
        private Roaming(String species, SpawnSource source, @Nullable UUID pokemonId, BlockPos lastSeen) {
            this.species = species;
            this.source = source;
            this.pokemonId = pokemonId;
            this.lastSeen = lastSeen;
        }
    }

    /** An empty counter; the catch map holds one entry per way so lookups never meet a missing key. */
    private SpecialSpawnCounter() {
        for (SpawnSource source : SpawnSource.values()) {
            caught.put(source, new HashMap<>());
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
     * Whether one more of the species may come in through this way now.
     * Always true for a species without a cap, so callers need no category
     * check of their own.
     */
    public static boolean canSpawn(MinecraftServer server, Species species, SpawnSource source) {
        int cap = SpeciesRules.spawnCap(species, source);
        return cap <= 0 || get(server).used(species.getResourceIdentifier().toString(), source) < cap;
    }

    /**
     * Takes one slot of this way for a Pokémon of a capped species entering
     * the Safari. Call it once the spawn is decided; the slot comes back by
     * itself if the Pokémon is never found in the park (see the class comment).
     *
     * @param entityId  UUID of the Pokémon's entity, followed by the sweeps
     * @param pokemonId UUID of the Pokémon itself, to charge its capture to this way
     * @param pos       where it appears
     */
    public static void recordSpawn(MinecraftServer server, Species species, SpawnSource source,
                                   UUID entityId, UUID pokemonId, BlockPos pos) {
        if (SpeciesRules.spawnCap(species, source) <= 0) {
            return;
        }
        SpecialSpawnCounter counter = get(server);
        counter.refresh();
        counter.roaming.put(entityId, new Roaming(species.getResourceIdentifier().toString(), source, pokemonId,
                pos.immutable()));
        counter.setDirty();
    }

    /**
     * Ids of the species that used up this way's cap for the rotation, for
     * filters that must not even offer them. Only species with a slot in use
     * can be at their cap, so this walks the counter, not every species.
     * Server thread.
     */
    public static Set<String> speciesAtCap(MinecraftServer server, SpawnSource source) {
        SpecialSpawnCounter counter = get(server);
        counter.refresh();
        Map<String, Integer> used = new HashMap<>(counter.caught.get(source));
        for (Roaming entry : counter.roaming.values()) {
            if (entry.source == source) {
                used.merge(entry.species, 1, Integer::sum);
            }
        }
        Set<String> atCap = new HashSet<>();
        used.forEach((id, count) -> {
            ResourceLocation location = ResourceLocation.tryParse(id);
            Species species = location == null ? null : PokemonSpecies.getByIdentifier(location);
            if (species == null) {
                return;
            }
            int cap = SpeciesRules.spawnCap(species, source);
            if (cap > 0 && count >= cap) {
                atCap.add(id);
            }
        });
        return atCap;
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

    /**
     * A capped species caught inside the Safari keeps its slot until the next
     * rotation, in the count of the way it came in.
     */
    private static void onCaptured(PokemonCapturedEvent event) {
        MinecraftServer server = event.getPlayer().getServer();
        if (server == null || !SafariDimensions.isSafari(event.getPlayer().level())) {
            return;
        }
        Species species = event.getPokemon().getSpecies();
        SpecialSpawnCounter counter = get(server);
        counter.refresh();
        SpawnSource source = counter.sourceOf(event.getPokemon().getUuid());
        if (SpeciesRules.spawnCap(species, source) <= 0) {
            return;
        }
        counter.caught.get(source).merge(species.getResourceIdentifier().toString(), 1, Integer::sum);
        // Its roaming entry is dropped by the next sweeps, which no longer find
        // the entity; until then the Pokémon counts twice, on the safe side.
        counter.setDirty();
    }

    /**
     * The way a Pokémon came in, by its Pokémon UUID. Natural when unknown:
     * spawns the counter never recorded (an uncapped species, a Poké Snack,
     * a version 1 save) all come from Cobblemon's spawner.
     */
    private SpawnSource sourceOf(UUID pokemonId) {
        for (Roaming entry : roaming.values()) {
            if (pokemonId.equals(entry.pokemonId)) {
                return entry.source;
            }
        }
        return SpawnSource.NATURAL;
    }

    /** Slots of the species in use for this way: caught this rotation plus still roaming. */
    private int used(String species, SpawnSource source) {
        refresh();
        int count = caught.get(source).getOrDefault(species, 0);
        for (Roaming entry : roaming.values()) {
            if (entry.source == source && entry.species.equals(species)) {
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
            caught.values().forEach(Map::clear);
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

    /**
     * Writes the rotation, map, catches and roaming Pokémon. Keys are the save
     * format: change one and {@link #load} must read both, then raise
     * {@link #SAVE_VERSION}. {@link #load} reads the same keys.
     */
    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        nbt.putInt("version", SAVE_VERSION);
        nbt.putLong("period", period);
        nbt.putLong("map_salt", mapSalt);
        nbt.put(CAUGHT_NATURAL_KEY, caughtTag(SpawnSource.NATURAL));
        nbt.put(CAUGHT_FEATURED_KEY, caughtTag(SpawnSource.FEATURED));
        ListTag roamingTag = new ListTag();
        roaming.forEach((id, pokemon) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", id);
            entry.putString("species", pokemon.species);
            entry.putString("source", pokemon.source.name());
            if (pokemon.pokemonId != null) {
                entry.putUUID("pokemon_id", pokemon.pokemonId);
            }
            entry.putInt("x", pokemon.lastSeen.getX());
            entry.putInt("y", pokemon.lastSeen.getY());
            entry.putInt("z", pokemon.lastSeen.getZ());
            roamingTag.add(entry);
        });
        nbt.put("roaming", roamingTag);
        return nbt;
    }

    /** One way's catches as a compound, species id to count. */
    private CompoundTag caughtTag(SpawnSource source) {
        CompoundTag tag = new CompoundTag();
        caught.get(source).forEach(tag::putInt);
        return tag;
    }

    /**
     * Reads what {@link #save} wrote, versions 1 and 2. Missing keys read as
     * an empty, stale counter; a version 1 save has no featured catches and
     * no way on its roaming entries, which all become natural.
     */
    private static SpecialSpawnCounter load(CompoundTag nbt, HolderLookup.Provider registries) {
        SpecialSpawnCounter counter = new SpecialSpawnCounter();
        counter.period = nbt.contains("period") ? nbt.getLong("period") : Long.MIN_VALUE;
        counter.mapSalt = nbt.contains("map_salt") ? nbt.getLong("map_salt") : Long.MIN_VALUE;
        readCaught(nbt.getCompound(CAUGHT_NATURAL_KEY), counter.caught.get(SpawnSource.NATURAL));
        readCaught(nbt.getCompound(CAUGHT_FEATURED_KEY), counter.caught.get(SpawnSource.FEATURED));
        for (Tag element : nbt.getList("roaming", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) element;
            UUID pokemonId = entry.hasUUID("pokemon_id") ? entry.getUUID("pokemon_id") : null;
            counter.roaming.put(entry.getUUID("id"), new Roaming(entry.getString("species"),
                    readSource(entry.getString("source")), pokemonId,
                    new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"))));
        }
        return counter;
    }

    /** Copies a compound of species id to count into a catch map. */
    private static void readCaught(CompoundTag tag, Map<String, Integer> into) {
        for (String key : tag.getAllKeys()) {
            into.put(key, tag.getInt(key));
        }
    }

    /** A saved way; natural when absent (version 1) or unknown (a renamed constant). */
    private static SpawnSource readSource(String raw) {
        for (SpawnSource source : SpawnSource.values()) {
            if (source.name().equals(raw)) {
                return source;
            }
        }
        return SpawnSource.NATURAL;
    }
}
