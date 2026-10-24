package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail;
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.api.spawning.influence.SpawningInfluence;
import com.cobblemon.mod.common.api.spawning.position.SpawnablePosition;
import com.cobblemon.mod.common.api.spawning.spawner.PlayerSpawner;
import com.cobblemon.mod.common.api.spawning.spawner.Spawner;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Takes the species the Safari refuses out of Cobblemon's natural spawn draw
 * around each player, before the weighted pick, instead of cancelling them
 * after it.
 *
 * <p>Why: Cobblemon picks one species by weight, then fires its spawn event,
 * where {@link NativeSpawnRules} can only cancel. A refused species keeps its
 * weight in the draw, so one with a huge weight (a datapack spawning Mew as
 * common with weight 999999) wins nearly every attempt, is cancelled every
 * time, and nothing else ever spawns. Refusing it in
 * {@link #affectSpawnable}, which Cobblemon checks while it builds the list
 * of candidates, removes it from the draw and from {@code /checkspawn}.
 *
 * <p>Three reasons take a species out, all following {@code spawns} in the
 * config (natural spawns only; the board has its own checks):
 * <ul>
 *   <li>its category is not allowed, or it is in {@code excludedSpecies};</li>
 *   <li>it used up its natural cap for the rotation ({@link SpecialSpawnCounter});</li>
 *   <li>{@code maxSameSpeciesNearby} of it already roam around the player.</li>
 * </ul>
 * The last two are snapshots refreshed once a second, so {@link NativeSpawnRules}
 * stays in place behind this filter for the moment they lag, and for spawners
 * that do not carry it (Poké Snacks, other mods).
 *
 * <p>One shared instance sits on each player's spawner, put there by
 * {@link SafariSpawnBoost} like {@link NoveltyInfluence}. Outside the Safari,
 * or on a spawner that is not a player's, it lets everything through.
 *
 * <p>Thread safety: the influence only reads immutable sets published through
 * volatile fields and a concurrent map; the server thread swaps them.
 */
public final class SafariSpawnFilter implements SpawningInfluence {

    /** The one instance; spawners hold it, so it is found again by identity. */
    public static final SafariSpawnFilter INSTANCE = new SafariSpawnFilter();

    /** Species ids refused by the categories and exclusions of {@code spawns}. */
    private static volatile Set<String> forbidden = Set.of();

    /** Species ids that used up their natural cap for the current rotation. */
    private static volatile Set<String> atCap = Set.of();

    /** Per player, the species ids already at {@code maxSameSpeciesNearby} around them. */
    private static final Map<UUID, Set<String>> CROWDED = new ConcurrentHashMap<>();

    /**
     * What {@link #forbidden} was built from: the config object (replaced on
     * every load, so identity tells a reload) and the number of species
     * registered. Server thread only.
     */
    private static SafariConfig builtFor;
    private static int builtSpeciesCount = -1;

    /** Set by a datapack reload, which may change labels without changing the species count. */
    private static volatile boolean dirty = true;

    private SafariSpawnFilter() {
    }

    /**
     * Refreshes the server-wide snapshots: the forbidden set when the config,
     * the datapacks or the species list changed, and the capped set always.
     *
     * <p>Cost, once a second while a player is in the Safari: a reference
     * comparison and a size read; a walk of every registered species (about a
     * thousand label checks) only after a change; and a walk of the
     * rotation's counted Pokémon, a handful.
     */
    static void refresh(MinecraftServer server) {
        SafariConfig config = SafariConfigManager.get();
        int speciesCount = PokemonSpecies.getSpecies().size();
        if (dirty || config != builtFor || speciesCount != builtSpeciesCount) {
            Set<String> refused = new HashSet<>();
            for (Species species : PokemonSpecies.getSpecies()) {
                if (!SpeciesRules.isAllowed(species, SpawnSource.NATURAL)) {
                    refused.add(species.getResourceIdentifier().toString());
                }
            }
            forbidden = Set.copyOf(refused);
            builtFor = config;
            builtSpeciesCount = speciesCount;
            dirty = false;
        }
        atCap = Set.copyOf(SpecialSpawnCounter.speciesAtCap(server, SpawnSource.NATURAL));
    }

    /**
     * Records which species already reach {@code maxSameSpeciesNearby} around
     * a player in the Safari.
     *
     * <p>Counted around the player, not around each candidate spot: Cobblemon
     * asks about hundreds of spots per attempt, one entity query each would
     * cost far more than the attempt itself. The spots lie around the player,
     * so this is close; {@link NativeSpawnRules} still checks the exact spot.
     *
     * <p>Cost: one entity query over a box of
     * {@link NativeSpawnRules#SAME_SPECIES_RADIUS} per player in the Safari,
     * once a second.
     *
     * @param maxNearby {@code spawns.maxSameSpeciesNearby}; 0 or less turns the check off
     */
    static void updateCrowded(ServerPlayer player, int maxNearby) {
        if (maxNearby <= 0) {
            CROWDED.remove(player.getUUID());
            return;
        }
        AABB area = new AABB(player.blockPosition()).inflate(NativeSpawnRules.SAME_SPECIES_RADIUS);
        Map<String, Integer> counts = new HashMap<>();
        // Wild only, as in NativeSpawnRules: a player's own Pokémon is not part of the habitat.
        for (PokemonEntity entity : player.level().getEntitiesOfClass(PokemonEntity.class, area,
                other -> other.getPokemon().isWild())) {
            counts.merge(entity.getPokemon().getSpecies().getResourceIdentifier().toString(), 1, Integer::sum);
        }
        Set<String> crowded = new HashSet<>();
        counts.forEach((id, count) -> {
            if (count >= maxNearby) {
                crowded.add(id);
            }
        });
        if (crowded.isEmpty()) {
            CROWDED.remove(player.getUUID());
        } else {
            CROWDED.put(player.getUUID(), Set.copyOf(crowded));
        }
    }

    /** Drops a player's crowding snapshot, e.g. when they leave the Safari or log out. */
    static void forget(UUID playerId) {
        CROWDED.remove(playerId);
    }

    /** Drops the crowding snapshots of players no longer online. Server thread. */
    static void retain(Set<UUID> online) {
        CROWDED.keySet().retainAll(online);
    }

    /** Asks for a rebuild of the forbidden set, after a datapack reload changed species or labels. */
    public static void invalidate() {
        dirty = true;
    }

    /** Forgets everything; for a server start, as single player reuses this class. */
    static void clear() {
        forbidden = Set.of();
        atCap = Set.of();
        CROWDED.clear();
        builtFor = null;
        builtSpeciesCount = -1;
        dirty = true;
    }

    /**
     * Refuses a natural Pokémon spawn the Safari would cancel anyway.
     * Called by Cobblemon for every candidate of every spot it considers, so
     * it is a string normalisation and three set lookups, nothing more.
     */
    @Override
    public boolean affectSpawnable(SpawnDetail detail, SpawnablePosition position) {
        if (!(detail instanceof PokemonSpawnDetail pokemonDetail)) {
            return true;
        }
        Spawner spawner = position.getSpawner();
        if (!(spawner instanceof PlayerSpawner playerSpawner) || !SafariDimensions.isSafari(position.getWorld())) {
            return true;
        }
        String raw = pokemonDetail.getPokemon().getSpecies();
        // No species (a "random" spawn, a label-based one) cannot be judged
        // here; NativeSpawnRules judges the Pokémon once it is created.
        if (raw == null) {
            return true;
        }
        String id = SpeciesRules.normalizeId(raw);
        if (forbidden.contains(id) || atCap.contains(id)) {
            return false;
        }
        Set<String> crowded = CROWDED.get(playerSpawner.getUuid());
        return crowded == null || !crowded.contains(id);
    }
}
