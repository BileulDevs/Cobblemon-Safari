package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.egg.SafariNestEntity;
import dev.darcosse.common.cobblemonsafari.entity.SafariEntities;
import dev.darcosse.common.cobblemonsafari.session.SafariSessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;

/**
 * The egg nests' spawner: a permanent event, never announced. Every
 * {@code checkEverySeconds}, each player with a session gets a draw; on a
 * success a straw nest ({@link SafariNestEntity}) holding an egg of a drawn
 * line ({@link EggSpecies}) is put on dry ground, in air (never in grass or
 * flowers), 24 to 48 blocks away
 * by default, unless another nest is already within
 * {@code exclusionRadius}. Called once a second by {@link SafariHappenings}.
 * Server side.
 */
public final class EggNests {

    /** Ticks in a minute, for the nests' lifetime. */
    private static final long TICKS_PER_MINUTE = 1200L;

    /** Seconds since the last draw. */
    private static int secondsSinceCheck;

    private EggNests() {
    }

    /** Forgets the draw timer; the nests themselves are saved with the level. */
    static void reset() {
        secondsSinceCheck = 0;
    }

    /**
     * Gives each hunting player a draw every {@code checkEverySeconds}.
     * Cost: once per period, a random number per player, and for the few
     * draws that succeed one spot check and one entity query over the
     * exclusion square.
     *
     * @param safari the Safari level, null when it is not loaded
     */
    static void tick(@Nullable ServerLevel safari) {
        SafariConfig.EggNests config = SafariConfigManager.get().events.eggNests;
        if (!config.enabled || safari == null || ++secondsSinceCheck < config.checkEverySeconds) {
            return;
        }
        secondsSinceCheck = 0;
        RandomSource random = safari.getRandom();
        // Copy: addFreshEntity may run other mods' callbacks.
        for (ServerPlayer player : new ArrayList<>(safari.players())) {
            // Spectators and exempt builders without a session find nothing.
            if (SafariSessionManager.getSession(player) == null || random.nextDouble() >= config.spawnChance) {
                continue;
            }
            tryPlace(safari, player, config, random);
        }
    }

    /** One try at putting a nest near a player; a failed spot costs nothing more than waiting for the next draw. */
    private static void tryPlace(ServerLevel level, ServerPlayer player, SafariConfig.EggNests config,
                                 RandomSource random) {
        // Air only, unlike a Pokémon's spot: a nest drawn inside a tuft of grass or a flower would be hidden.
        BlockPos spot = EventSpawns.spotNear(level, player, config.minDistance, config.maxDistance,
                pos -> level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
                        && !hasNestNear(level, pos, config.exclusionRadius), random);
        if (spot == null) {
            return;
        }
        Species species = EggSpecies.draw(random);
        if (species == null) {
            // The config allows no line at all: nothing to put in a nest.
            return;
        }
        SafariNestEntity nest = SafariEntities.safariNest().create(level);
        if (nest == null) {
            return;
        }
        nest.setUp(species, level.getGameTime() + config.lifetimeMinutes * TICKS_PER_MINUTE);
        // A random turn, so the nests do not all face the same way.
        nest.moveTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, random.nextFloat() * 360.0F, 0.0F);
        level.addFreshEntity(nest);
    }

    /**
     * Whether a nest is already out within {@code radius} blocks (horizontally,
     * and as much vertically). Only loaded nests are seen: one in a chunk no
     * player is near does not keep a new one away.
     */
    private static boolean hasNestNear(ServerLevel level, BlockPos pos, int radius) {
        AABB around = new AABB(pos).inflate(radius);
        return !level.getEntitiesOfClass(SafariNestEntity.class, around, nest -> !nest.isRemoved()).isEmpty();
    }
}
