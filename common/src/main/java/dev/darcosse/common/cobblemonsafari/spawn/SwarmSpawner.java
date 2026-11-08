package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.spawning.spawner.PlayerSpawner;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.park.ParkBoundary;
import dev.darcosse.common.cobblemonsafari.session.SafariSessionManager;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import dev.darcosse.common.cobblemonsafari.util.Surface;
import dev.darcosse.common.cobblemonsafari.util.Ticks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;

/**
 * Keeps the swarm visible: around each player standing in the swarm's
 * habitat, tops up the swarming species to {@code events.swarm.minNearby}.
 *
 * <p>Why on top of Cobblemon's natural spawns: there, the swarm only weighs
 * more among the Pokémon of the spot ({@link SafariHappeningsInfluence}).
 * Cobblemon first rolls a bucket, then a kind of spot, then a species by
 * weight among everything living there, so the swarm's share depends on its
 * raw spawn weights (from 0.07 to 18 in Cobblemon's own files) and on how
 * many other species share the habitat: a lighter species could swarm and
 * barely show. Raising the multiplier would only trade that for a habitat
 * holding nothing else.
 *
 * <p>This spawner does not roll among species: it spawns the swarm's own
 * details, through Cobblemon's machinery ({@link SwarmSpawnPass}), so every
 * condition of the detail, the Safari's filters (exclusions, caps, crowding)
 * and {@link NativeSpawnRules} still apply. Only players with a hunting
 * session attract it, as for the specials. It is also the only way a species
 * met only through Poké Snacks (Gallade) swarms: it judges those spawns
 * without the snack requirement, which Cobblemon's spawner never lifts.
 *
 * <p>Server side. Cost: every {@code checkEverySeconds} (5 s by default), per
 * player in the Safari, a biome read; for those standing in the swarm's
 * habitat, one entity query, and while the swarm is below its floor up to
 * {@link #SPOT_TRIES} biome reads and one scan of a 1024-block zone. Nothing
 * while there is no swarm.
 */
public final class SwarmSpawner {

    /**
     * Spawn distance from the player, in blocks: far enough not to pop up in
     * front of them, close enough to be within the simulation distance and
     * inside the radius the floor is counted in.
     */
    private static final int MIN_DISTANCE = 16;
    private static final int MAX_DISTANCE = 40;

    /**
     * Random spots tried per attempt to land in the habitat. Only biome reads;
     * more helps a player at the habitat's edge, at almost no cost. Raising it
     * does not raise the number of zone scans, which stays one per attempt.
     */
    private static final int SPOT_TRIES = 4;

    /** Blocks kept clear around rest houses, as for the specials. */
    private static final int HOUSE_MARGIN = 3;

    /** Height of the box the floor is counted in; covers cliffs without scanning the whole column. */
    private static final double COUNT_HALF_HEIGHT = 48.0;

    private static int tickCounter;

    private SwarmSpawner() {
    }

    /** Forgets the cadence; for a server start, as single player reuses this class. */
    public static void reset() {
        tickCounter = 0;
    }

    /** Runs from the server tick. Does nothing except on attempt ticks. */
    public static void tick(MinecraftServer server) {
        SafariConfig config = SafariConfigManager.get();
        SafariConfig.Swarm settings = config.events.swarm;
        if (++tickCounter < Ticks.fromSeconds(settings.checkEverySeconds)) {
            return;
        }
        tickCounter = 0;
        if (settings.minNearby <= 0 || !config.spawns.nativeSpawns) {
            return;
        }
        ServerLevel safari = SafariDimensions.level(server);
        // Cobblemon's gamerule switches every wild spawn off; the swarm is wild spawns too.
        if (safari == null || safari.players().isEmpty() || !PokemonSpawningRule.allowed(server)) {
            return;
        }
        Swarm.Active swarm = Swarm.current(server);
        // At its cap (events.swarm.rarities), every scan would be refused: skip them.
        if (swarm == null || swarm.details().isEmpty()
                || !SpecialSpawnCounter.canSpawn(server, swarm.species(), SpawnSource.SWARM)) {
            return;
        }
        int floor = floor(swarm, settings.minNearby, config.spawns.maxSameSpeciesNearby);
        // Copy: a spawn never removes players, but other mods react to new entities.
        for (ServerPlayer player : new ArrayList<>(safari.players())) {
            if (SafariSessionManager.getSession(player) == null
                    || !safari.getBiome(player.blockPosition()).is(swarm.biome())) {
                continue;
            }
            PlayerSpawner spawner = PlayerExtensionsKt.getSpawner(player);
            if (spawner != null && countAround(safari, player, swarm) < floor) {
                tryNear(safari, player, spawner, swarm);
            }
        }
    }

    /**
     * How many of the swarming species to keep around a player: minNearby,
     * but never above the same-species cap the Safari applies to it, which
     * would refuse the spawn anyway and waste the scan.
     *
     * @param usualCap {@code spawns.maxSameSpeciesNearby}; 0 or less means no cap
     */
    private static int floor(Swarm.Active swarm, int minNearby, int usualCap) {
        int cap = Swarm.sameSpeciesCap(swarm.speciesId(), usualCap);
        return cap > 0 ? Math.min(minNearby, cap) : minNearby;
    }

    /**
     * Wild Pokémon of the swarming species around the player, in the radius
     * the Safari's same-species cap uses, so the two counts agree.
     */
    private static int countAround(ServerLevel level, ServerPlayer player, Swarm.Active swarm) {
        AABB area = player.getBoundingBox().inflate(NativeSpawnRules.SAME_SPECIES_RADIUS, COUNT_HALF_HEIGHT,
                NativeSpawnRules.SAME_SPECIES_RADIUS);
        return level.getEntitiesOfClass(PokemonEntity.class, area,
                entity -> entity.getPokemon().isWild() && entity.getPokemon().getSpecies() == swarm.species()).size();
    }

    /** One attempt around a player: a loaded surface spot in the habitat, then one spawn there. */
    private static void tryNear(ServerLevel level, ServerPlayer player, PlayerSpawner spawner, Swarm.Active swarm) {
        RandomSource random = level.getRandom();
        for (int i = 0; i < SPOT_TRIES; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
            if (ParkBoundary.isOutside(x, z) || RestHouses.isNearSurfaceHouse(x, z, HOUSE_MARGIN)) {
                continue;
            }
            // Only loaded chunks: generating terrain for a spawn would stall the tick.
            BlockPos feet = Surface.loaded(level, x, z);
            if (feet == null || !level.getBiome(feet).is(swarm.biome())) {
                continue;
            }
            // One zone scan per attempt, found or not: the cost stays bounded
            // when the habitat has no valid spot right now (wrong hour, light).
            SwarmSpawnPass.trySpawn(level, player, spawner, feet, swarm.details(), swarm.snackFree(), swarm.biome(),
                    random);
            return;
        }
    }
}
