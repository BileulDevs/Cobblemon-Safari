package dev.darcosse.common.cobblemonsafari.spawn;

import dev.darcosse.common.cobblemonsafari.park.ParkGeometry;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.park.ParkBoundary;
import dev.darcosse.common.cobblemonsafari.session.SafariSessionManager;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import dev.darcosse.common.cobblemonsafari.util.Surface;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Brings the specials of the day into the Safari, in their own habitat.
 *
 * <p>Separate from Cobblemon's spawner on purpose: Cobblemon's weights would
 * drown a handful of specials among hundreds of natural spawns, and its spawn
 * files cannot follow a list that changes every day. Here a special only
 * appears where it lives, so finding the rare ones means exploring the right
 * corner of the park, as in the games.
 *
 * <p>Anti-monopoly: at most {@code maxNearbyPerPlayer} specials around a
 * player, never two of the same species around them, never more than one
 * legendary-class special in the whole park at once, and no more appearances
 * of a legendary-class species per rotation than its category in
 * {@code featured} allows ({@link SpecialSpawnCounter}, board count). Which
 * species may come at all follows {@code featured} too, not {@code spawns}.
 */
public final class FeaturedSpawner {

    /**
     * Entity tag marking a special spawned by this class. Lets the caps count
     * only specials, not the natural Pokémon of the same species.
     */
    public static final String FEATURED_TAG = CobblemonSafari.MOD_ID + ".featured";

    /** Height of the boxes used for nearby checks; covers cliffs without scanning the whole column. */
    private static final double CHECK_HALF_HEIGHT = 48.0;

    /** Blocks kept clear around rest houses, so specials never pop up inside a Pokémon Center. */
    private static final int HOUSE_MARGIN = 3;

    /** Ticks since the server started, counted here so the spawner owns its own cadence. */
    private static int tickCounter;

    /**
     * Radius of the "specials already around this player" check, in blocks.
     * Wider counts specials the player cannot see; each check scans the
     * entity sections the box touches. Quoted in the comment of
     * featured.maxNearbyPerPlayer (SafariConfig): keep both in step.
     */
    private static final int NEARBY_RADIUS = 64;

    /**
     * Spawn distance from the player, in blocks: far enough to be found
     * rather than pop up in view, close enough to be within the simulation
     * distance (beyond it a special spawns frozen and despawns unseen).
     */
    private static final int MIN_DISTANCE = 20;
    private static final int MAX_DISTANCE = 48;

    /** Level range when Cobblemon's spawn data gives none (legendaries, data pack species). */
    private static final int FALLBACK_MIN_LEVEL = 20;
    private static final int FALLBACK_MAX_LEVEL = 40;

    private FeaturedSpawner() {
    }

    /**
     * Runs from the server tick. Does nothing except on attempt ticks.
     *
     * <p>Cost on an attempt tick: per player in the Safari, one random roll,
     * then at most two entity queries and one heightmap read in an already
     * loaded chunk. One attempt every {@code spawnIntervalSeconds} (15 s by
     * default) keeps this negligible.
     */
    public static void tick(MinecraftServer server) {
        tickCounter++;
        SafariConfig.Featured config = SafariConfigManager.get().featured;
        if (!config.enabled || tickCounter % Ticks.fromSeconds(config.spawnIntervalSeconds) != 0) {
            return;
        }
        ServerLevel safari = SafariDimensions.level(server);
        // Cobblemon's gamerule switches every wild spawn off; the specials are wild spawns too.
        if (safari == null || safari.players().isEmpty() || !PokemonSpawningRule.allowed(server)) {
            return;
        }
        List<HabitatEntry> specials = FeaturedRotation.today(server);
        if (specials.isEmpty()) {
            return;
        }
        // Copy: a spawn never removes players, but addFreshEntity may run
        // callbacks from other mods; iterating a snapshot is the safe default.
        for (ServerPlayer player : new ArrayList<>(safari.players())) {
            // Spectators and exempt builders without a session do not attract specials.
            if (SafariSessionManager.getSession(player) == null) {
                continue;
            }
            if (safari.getRandom().nextDouble() < config.spawnChance) {
                trySpawnNear(safari, player, specials, config);
            }
        }
    }

    /** One spawn attempt around a player: picks a loaded surface spot, then a special living there. */
    private static void trySpawnNear(ServerLevel level, ServerPlayer player, List<HabitatEntry> specials,
                                     SafariConfig.Featured config) {
        AABB around = player.getBoundingBox().inflate(NEARBY_RADIUS, CHECK_HALF_HEIGHT, NEARBY_RADIUS);
        List<PokemonEntity> nearbySpecials = level.getEntitiesOfClass(PokemonEntity.class, around,
                entity -> entity.getTags().contains(FEATURED_TAG));
        if (nearbySpecials.size() >= config.maxNearbyPerPlayer) {
            return;
        }

        RandomSource random = level.getRandom();
        double angle = random.nextDouble() * Math.PI * 2.0;
        double distance = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);
        int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
        int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
        if (ParkBoundary.isOutside(x, z) || RestHouses.isNear(x, z, HOUSE_MARGIN)) {
            return;
        }
        // Only loaded chunks: generating terrain for a spawn would stall the tick.
        BlockPos feet = Surface.loaded(level, x, z);
        if (feet == null) {
            return;
        }
        BlockState below = level.getBlockState(feet.below());
        boolean water = !below.getFluidState().isEmpty();
        if (!water && (below.isAir() || !level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir())) {
            return;
        }
        // Water species go into the water itself; land species stand on the ground.
        BlockPos spawnPos = water ? feet.below() : feet;
        Holder<Biome> biome = level.getBiome(spawnPos);

        HabitatEntry entry = pick(specials, biome, water, spawnPos, nearbySpecials, level, player.getServer(), random);
        if (entry == null) {
            return;
        }
        spawn(level, player, entry, spawnPos, config, random);
    }

    /**
     * Weighted pick among the specials living in that biome, leaving out those
     * whose natural spawns refuse the hour or the light at that spot (a nocturnal
     * species by day, a Gastly in daylight), species already present around the
     * player, legendary-class species already roaming the park and those that
     * used up their appearances for this rotation.
     */
    @Nullable
    private static HabitatEntry pick(List<HabitatEntry> specials, Holder<Biome> biome, boolean water,
                                     BlockPos spawnPos, List<PokemonEntity> nearbySpecials, ServerLevel level, MinecraftServer server,
                                     RandomSource random) {
        List<HabitatEntry> candidates = new ArrayList<>();
        int totalWeight = 0;
        for (HabitatEntry entry : specials) {
            boolean livesHere = (water ? entry.waterHabitats() : entry.landHabitats()).stream().anyMatch(key -> biome.is(key));
            // Cheap checks first: the window check reads light, the others are lookups.
            if (!livesHere || !SpeciesRules.isAllowed(entry.species(), SpawnSource.FEATURED)
                    || isPresent(nearbySpecials, entry)
                    || (SpeciesRules.isSpecial(entry.species()) && isRoamingAnywhere(level, entry))
                    || !SpecialSpawnCounter.canSpawn(server, entry.species(), SpawnSource.FEATURED)
                    || !entry.canSpawnAt(level, spawnPos, biome, water)) {
                continue;
            }
            int weight = weight(entry.rarity());
            if (weight > 0) {
                candidates.add(entry);
                totalWeight += weight;
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        int roll = random.nextInt(totalWeight);
        for (HabitatEntry entry : candidates) {
            roll -= weight(entry.rarity());
            if (roll < 0) {
                return entry;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /** Whether a special of the entry's species is among the given entities. */
    private static boolean isPresent(List<PokemonEntity> entities, HabitatEntry entry) {
        for (PokemonEntity entity : entities) {
            if (entity.getPokemon().getSpecies() == entry.species()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a special of this species already roams the park. Scans the
     * whole park, but only for legendary-class specials, i.e. rarely.
     */
    private static boolean isRoamingAnywhere(ServerLevel level, HabitatEntry entry) {
        int half = ParkGeometry.BORDER_HALF_SIZE;
        AABB parkBox = new AABB(ParkGeometry.CENTER_X - half, level.getMinBuildHeight(), ParkGeometry.CENTER_Z - half,
                ParkGeometry.CENTER_X + half, level.getMaxBuildHeight(), ParkGeometry.CENTER_Z + half);
        return !level.getEntitiesOfClass(PokemonEntity.class, parkBox,
                entity -> entity.getTags().contains(FEATURED_TAG)
                        && entity.getPokemon().getSpecies() == entry.species()).isEmpty();
    }

    /** Configured pick weight of a rarity. */
    private static int weight(Rarity rarity) {
        SafariConfig.Featured config = SafariConfigManager.get().featured;
        return switch (rarity) {
            case COMMON, UNCOMMON -> config.weightUncommon;
            case RARE -> config.weightRare;
            case ULTRA_RARE -> config.weightUltraRare;
        };
    }

    /**
     * Creates and places the Pokémon the way Cobblemon's own spawn command
     * does. Starting from the natural spawn's properties keeps its form
     * (regional variants, aspects); the player is passed so shiny-chance
     * modifiers such as a Shiny Charm apply.
     */
    private static void spawn(ServerLevel level, ServerPlayer player, HabitatEntry entry, BlockPos pos,
                              SafariConfig.Featured config, RandomSource random) {
        PokemonProperties properties = entry.template() != null ? entry.template().copy() : new PokemonProperties();
        properties.setSpecies(entry.species().getResourceIdentifier().toString());
        properties.setLevel(level(entry, config, random));

        PokemonEntity pokemonEntity = properties.createEntity(level, player);
        pokemonEntity.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                random.nextFloat() * 360.0F, 0.0F);
        pokemonEntity.addTag(FEATURED_TAG);
        // Counted only once really added: another mod may veto the entity.
        if (level.addFreshEntity(pokemonEntity)) {
            SpecialSpawnCounter.recordSpawn(level.getServer(), entry.species(), SpawnSource.FEATURED,
                    pokemonEntity.getUUID(), pokemonEntity.getPokemon().getUuid(), pos);
        }
    }

    /** A level within the natural range, or the configured fallback when the species has none. */
    private static int level(HabitatEntry entry, SafariConfig.Featured config, RandomSource random) {
        int min = entry.minLevel() > 0 ? entry.minLevel() : FALLBACK_MIN_LEVEL;
        int max = entry.maxLevel() >= min ? entry.maxLevel() : Math.max(min, FALLBACK_MAX_LEVEL);
        return min + random.nextInt(max - min + 1);
    }
}
