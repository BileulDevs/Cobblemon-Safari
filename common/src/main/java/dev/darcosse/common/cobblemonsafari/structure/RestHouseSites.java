package dev.darcosse.common.cobblemonsafari.structure;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.park.ParkBoundary;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks where a rest house is built: the best spot near its configured
 * position (its anchor), since a fixed position lands in a lake, on a cliff
 * or half in the sea on many maps.
 *
 * <p>Candidates on a grid around the anchor are judged on the terrain the
 * generator would produce, read from the noise ({@link ChunkGenerator#getBaseHeight})
 * without generating a single chunk: dry ground under the whole footprint
 * and plaza, little relief, inside the park, away from the other houses and
 * from the spots where the Safari's ruins and towers may stand. Among the
 * good ones, the flattest wins, closeness to the anchor breaking ties.
 *
 * <p>Server side; called once per house and map, at server start.
 */
final class RestHouseSites {

    /**
     * Grid step between candidates, in blocks. Smaller is finer and slower:
     * the cost grows with its inverse square. At 12, a 64-block search reads
     * about 2,000 column heights from the noise, a second or so per house.
     */
    private static final int STEP = 12;

    /**
     * Footprint samples, in house-local coordinates (see {@link RestHouseBuilder#rotate}):
     * the corners, edges and middle of the house plus the far edge of the
     * plaza and the cleared ring, so the whole building lot is judged.
     */
    private static final int[] SAMPLE_X = {-2, RestHouses.HALF_SIZE, 2 * RestHouses.HALF_SIZE + 2};
    private static final int[] SAMPLE_Z = {0, RestHouses.HALF_SIZE, 2 * RestHouses.HALF_SIZE, 2 * RestHouses.HALF_SIZE + 4};

    /**
     * Highest acceptable difference between the highest and lowest sample, in
     * blocks: the builder levels the lot with a foundation and clears the
     * hill above, so a few blocks are fine; more and the house sits on a
     * stone pillar or in a trench.
     */
    private static final int MAX_RELIEF = 5;
    /** Relief allowed by the second, relaxed search when the strict one finds nothing. */
    private static final int RELAXED_RELIEF = 10;

    /** Weight of one block of relief against one block of distance from the anchor. */
    private static final int RELIEF_WEIGHT = 12;

    /** Smallest distance between two houses' centres, in blocks. */
    private static final int MIN_HOUSE_SPACING = 64;

    /**
     * Chunks kept between a house and any spot where a Safari ruin or tower
     * may generate. The check is on candidate spots, not on structures
     * actually built (that would need the chunks generated), so it is
     * slightly over-cautious.
     */
    private static final int STRUCTURE_CLEARANCE_CHUNKS = 3;

    /** Structure sets written by tools/worldgen/generate_worldgen.py (SAFARI_STRUCTURE_SETS). */
    private static final List<ResourceKey<StructureSet>> SAFARI_STRUCTURE_SETS = List.of(
            ResourceKey.create(Registries.STRUCTURE_SET, CobblemonSafari.id("safari_ruins/towers")),
            ResourceKey.create(Registries.STRUCTURE_SET, CobblemonSafari.id("safari_ruins/ruins")));

    private RestHouseSites() {
    }

    /**
     * The centre column {x, z} for a house, or the anchor itself (logged)
     * when no candidate is acceptable even with the relaxed relief.
     *
     * @param radius how far from the anchor to look, in blocks
     * @param taken  centres of the houses already placed on this map
     */
    static int[] find(ServerLevel level, int anchorX, int anchorZ, int radius, List<int[]> taken) {
        Context context = new Context(level);
        int[] site = search(context, anchorX, anchorZ, radius, taken, MAX_RELIEF);
        if (site == null) {
            site = search(context, anchorX, anchorZ, radius, taken, RELAXED_RELIEF);
        }
        if (site == null) {
            CobblemonSafari.LOGGER.warn("No good spot for the rest house near {}, {}; building it on that position",
                    anchorX, anchorZ);
            return new int[]{anchorX, anchorZ};
        }
        return site;
    }

    /** One pass over the grid; null when nothing passes. */
    @Nullable
    private static int[] search(Context context, int anchorX, int anchorZ, int radius, List<int[]> taken,
                                int maxRelief) {
        int[] best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx += STEP) {
            for (int dz = -radius; dz <= radius; dz += STEP) {
                int x = anchorX + dx;
                int z = anchorZ + dz;
                if (tooClose(x, z, taken) || nearStructure(context, x, z)) {
                    continue;
                }
                int relief = relief(context, x, z, maxRelief);
                if (relief < 0) {
                    continue;
                }
                double score = relief * RELIEF_WEIGHT + Math.sqrt((double) dx * dx + (double) dz * dz);
                if (score < bestScore) {
                    bestScore = score;
                    best = new int[]{x, z};
                }
            }
        }
        return best;
    }

    /**
     * Relief of the lot of a house centred on (x, z), or -1 when the lot is
     * unacceptable: water on any sample, a sample outside the park, or relief
     * above the limit.
     */
    private static int relief(Context context, int x, int z, int maxRelief) {
        Direction facing = RestHouses.doorFacing(x, z);
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int lx : SAMPLE_X) {
            for (int lz : SAMPLE_Z) {
                BlockPos column = RestHouseBuilder.rotate(x, z, facing, lx, lz);
                if (ParkBoundary.isOutside(column.getX(), column.getZ())) {
                    return -1;
                }
                // WORLD_SURFACE_WG counts water, OCEAN_FLOOR_WG does not:
                // any difference is water standing on the ground.
                int surface = context.height(column, Heightmap.Types.WORLD_SURFACE_WG);
                int ground = context.height(column, Heightmap.Types.OCEAN_FLOOR_WG);
                if (surface != ground || ground <= context.seaLevel) {
                    return -1;
                }
                low = Math.min(low, ground);
                high = Math.max(high, ground);
                if (high - low > maxRelief) {
                    return -1;
                }
            }
        }
        return high - low;
    }

    /** Whether a centre is closer than {@link #MIN_HOUSE_SPACING} to a house already placed. */
    private static boolean tooClose(int x, int z, List<int[]> taken) {
        for (int[] other : taken) {
            long dx = x - other[0];
            long dz = z - other[1];
            if (dx * dx + dz * dz < (long) MIN_HOUSE_SPACING * MIN_HOUSE_SPACING) {
                return true;
            }
        }
        return false;
    }

    /** Whether a Safari ruin or tower may generate within {@link #STRUCTURE_CLEARANCE_CHUNKS} of a column. */
    private static boolean nearStructure(Context context, int x, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        for (Holder<StructureSet> set : context.structureSets) {
            if (context.structureState.hasStructureChunkInRange(set, chunkX, chunkZ, STRUCTURE_CLEARANCE_CHUNKS)) {
                return true;
            }
        }
        return false;
    }

    /** What the search reads, looked up once per house. */
    private static final class Context {
        final ServerLevel level;
        final ChunkGenerator generator;
        final RandomState randomState;
        final ChunkGeneratorStructureState structureState;
        final int seaLevel;
        final List<Holder<StructureSet>> structureSets = new ArrayList<>();

        Context(ServerLevel level) {
            this.level = level;
            this.generator = level.getChunkSource().getGenerator();
            this.randomState = level.getChunkSource().randomState();
            this.structureState = level.getChunkSource().getGeneratorState();
            this.seaLevel = generator.getSeaLevel();
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
            for (ResourceKey<StructureSet> key : SAFARI_STRUCTURE_SETS) {
                // Missing only if the data pack was altered: houses then ignore structures.
                registry.getHolder(key).ifPresent(structureSets::add);
            }
        }

        /** Height the generator gives a column, without generating its chunk. */
        int height(BlockPos column, Heightmap.Types type) {
            return generator.getBaseHeight(column.getX(), column.getZ(), type, level, randomState);
        }
    }
}
