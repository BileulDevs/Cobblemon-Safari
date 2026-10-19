package dev.darcosse.common.cobblemonsafari.gate;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes what is left of the trees a generated Safari Gate cut through.
 *
 * <p>The gate structure clears the ground it stands on only up to a few
 * blocks high (structure/safari_gate.nbt keeps air there and leaves out the
 * air above, so the crowns of neighbouring trees overhanging the gate are
 * kept whole). A tree that grew where the gate now stands loses its trunk
 * to that air and leaves its crown floating. Worldgen cannot tell such a
 * crown from a neighbour's: the gate is placed chunk by chunk, and a tree may
 * span chunks generated before and after. So this runs once, in game, when
 * the whole area is loaded, and removes:
 * <ul>
 *   <li>every group of connected logs none of which rests on a solid block
 *       other than logs and leaves (a cut crown); logs of a tree standing on
 *       the ground, or of the gate's own trees and woodwork, all have one;</li>
 *   <li>then every natural (non persistent) leaf left more than
 *       {@link #LEAF_REACH} leaves away from any log, which is the rule
 *       Minecraft's own leaf decay applies, done at once and without drops.</li>
 * </ul>
 * Player-placed leaves (persistent) and anything else are never touched.
 *
 * <p>Spread over several ticks: the area is read a few columns at a time,
 * at most {@link #READS_PER_TICK} blocks per tick, and only between the
 * column's lowest possible root and its top block (heightmap), which skips
 * the open sky that makes up most of the area. The removal itself, a few
 * hundred blocks at most, happens on the last tick. One job per gate,
 * owned by the gate's corner block entity; dropped and started again on a
 * later check if part of the area is not loaded. Server side.
 *
 * <p>Rest houses use it too, for the trees their clearing and their terrain
 * blending cut: they give their own footprint and a box (the house itself)
 * whose logs and leaves are never treated as trees, since a house's beams
 * are logs too.
 */
public final class GateClearing {

    /**
     * Half-width of the gate structure, in blocks from the middle of the
     * pane (structure/safari_gate.nbt is 27 wide, its pane in the middle).
     * Must follow the template if its size changes.
     */
    private static final int FOOTPRINT_HALF = 13;

    /**
     * How far beyond the footprint a cut crown may reach, in blocks. Large
     * oak and birch crowns spread about 4 to 5 blocks from their trunk.
     */
    private static final int CROWN_REACH = 6;

    /**
     * Highest block above the gate's ground searched for crowns. The
     * tallest trees of the gate's biomes stay under 30 blocks; more costs
     * one more horizontal slice of block reads.
     */
    private static final int MAX_TREE_HEIGHT = 40;

    /**
     * Leaves farther than this from a log, counted through leaves, decay
     * in vanilla (LeavesBlock distance 7). Must stay 6 to match vanilla.
     */
    private static final int LEAF_REACH = 6;

    /**
     * Depth below the ground included in the scan, so that trees standing
     * lower on a slope next to the gate are seen down to their roots.
     */
    private static final int BELOW_GROUND = 6;

    /**
     * Setting a block without neighbour updates (no leaf decay chain, no
     * physics) and without reshaping its neighbours: a reshaped neighbour
     * that loses its support breaks, and Minecraft forces its drop
     * (Level.setBlock clears UPDATE_SUPPRESS_DROPS for neighbours), which
     * would leave cocoa beans and petals on the ground.
     */
    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /**
     * Block reads allowed per tick while scanning. In plains a column costs
     * a handful of reads and the scan ends in a tick or two; in a dense
     * forest (about 25 reads a column over 2 601 columns) it takes some
     * 15 ticks. Higher finishes sooner with a bigger hitch on each tick.
     */
    private static final int READS_PER_TICK = 4096;

    private final BlockPos middle;
    /** What the log names: "Safari Gate", "Safari rest house". */
    private final String owner;
    /** Blocks never counted as trees (a building made of logs); null for none. */
    @Nullable
    private final BoundingBox keep;
    private final int ground;
    private final Box scan;
    private final Box removal;
    /** Next column to read, as an index into the scan area, row by row along X. */
    private int nextColumn;
    private final Set<Long> logs = new HashSet<>();
    private final Set<Long> leaves = new HashSet<>();
    /** Vines of the scan area, checked once the trees are removed: those left hanging on nothing go too. */
    private final List<Long> vines = new ArrayList<>();

    /** A job for the gate whose pane's middle bottom block is given; nothing is read yet. */
    public GateClearing(ServerLevel level, BlockPos middle) {
        this(level, middle, FOOTPRINT_HALF, null, "Safari Gate");
    }

    /**
     * A job for any build: {@code middle} is the block just above the
     * build's ground at its centre, {@code footprintHalf} how far from it,
     * horizontally, trees may have been cut. Logs and leaves inside
     * {@code keep} are part of the build: never removed, and holding
     * nothing up (a crown resting on a roof still rests on something solid).
     */
    public GateClearing(ServerLevel level, BlockPos middle, int footprintHalf, @Nullable BoundingBox keep,
                        String owner) {
        this.middle = middle.immutable();
        this.owner = owner;
        this.keep = keep;
        this.ground = middle.getY() - 1;
        // Removal area: the footprint plus the reach of a crown.
        int removeHalf = footprintHalf + CROWN_REACH;
        // Scan area: one leaf reach further, so that a leaf at the edge of
        // the removal area still finds the log that holds it.
        int scanHalf = removeHalf + LEAF_REACH;
        int maxY = Math.min(level.getMaxBuildHeight() - 1, ground + MAX_TREE_HEIGHT + LEAF_REACH);
        this.scan = new Box(middle.getX() - scanHalf, middle.getX() + scanHalf,
                Math.max(level.getMinBuildHeight(), ground - BELOW_GROUND), maxY,
                middle.getZ() - scanHalf, middle.getZ() + scanHalf);
        this.removal = new Box(middle.getX() - removeHalf, middle.getX() + removeHalf, ground + 1,
                Math.min(maxY, ground + MAX_TREE_HEIGHT), middle.getZ() - removeHalf, middle.getZ() + removeHalf);
    }

    /**
     * Advances the job by one tick: reads the next columns, or, once all are
     * read, removes the cut trees. Cost: at most {@link #READS_PER_TICK}
     * block reads (plus a chunk check per chunk of the area), and on the
     * last tick a walk over the logs and leaves found.
     *
     * @return what the caller should do with the job next
     */
    public Status step(ServerLevel level) {
        if (!loaded(level)) {
            // Part of the area is not loaded: what was read may be stale.
            // Dropped, the gate starts a new job on a later check.
            return Status.WAITING;
        }
        int width = scan.maxX - scan.minX + 1;
        int columns = width * (scan.maxZ - scan.minZ + 1);
        int reads = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        while (nextColumn < columns && reads < READS_PER_TICK) {
            int x = scan.minX + nextColumn % width;
            int z = scan.minZ + nextColumn / width;
            nextColumn++;
            // Nothing stands above the heightmap, so the column ends there
            // instead of at the top of the scan: most of the area is open
            // sky. WORLD_SURFACE, not MOTION_BLOCKING: vines block no
            // motion, so a column holding only vines hanging beside a crown
            // would end at the ground, and those vines would never be seen
            // (left hanging in the air once the crown is removed).
            int top = Math.min(scan.maxY, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1);
            for (int y = scan.minY; y <= top; y++) {
                BlockState state = level.getBlockState(cursor.set(x, y, z));
                reads++;
                if (keep != null && keep.isInside(cursor)) {
                    continue;
                }
                if (state.is(BlockTags.LOGS)) {
                    logs.add(cursor.asLong());
                } else if (state.is(Blocks.VINE)) {
                    vines.add(cursor.asLong());
                } else if (state.is(BlockTags.LEAVES)) {
                    leaves.add(cursor.asLong());
                }
            }
        }
        if (nextColumn < columns) {
            return Status.RUNNING;
        }
        removeCutTrees(level);
        return Status.DONE;
    }

    /** Outcome of one {@link #step}. */
    public enum Status {
        /** Still reading: call again next tick. */
        RUNNING,
        /** Part of the area is not loaded: drop the job, nothing was changed. */
        WAITING,
        /** Cut trees removed (or none found): never run again for this gate. */
        DONE
    }

    /** Removes the floating logs, the bee nests and cocoa pods on them and the orphan leaves, from what the scan found. */
    private void removeCutTrees(ServerLevel level) {
        List<Long> cutLogs = floatingLogs(level, logs, leaves, scan, removal);
        for (long log : cutLogs) {
            logs.remove(log);
        }
        List<Long> orphanLeaves = orphanLeaves(level, logs, leaves, removal);

        int nests = 0;
        for (long log : cutLogs) {
            BlockPos pos = BlockPos.of(log);
            // Read during the scan, a few ticks ago: someone may have changed it since.
            if (!level.getBlockState(pos).is(BlockTags.LOGS)) {
                continue;
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
            // A bee nest hangs under a branch, a cocoa pod on a jungle trunk: they would float like the crown.
            for (Direction side : Direction.values()) {
                BlockPos next = pos.relative(side);
                if (level.getBlockState(next).is(Blocks.BEE_NEST) || level.getBlockState(next).is(Blocks.COCOA)) {
                    level.setBlock(next, Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
                    nests++;
                }
            }
        }
        for (long leaf : orphanLeaves) {
            BlockPos pos = BlockPos.of(leaf);
            if (level.getBlockState(pos).is(BlockTags.LEAVES)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
            }
        }
        int lostVines = detachVines(level);
        if (!cutLogs.isEmpty() || !orphanLeaves.isEmpty() || lostVines > 0) {
            CobblemonSafari.LOGGER.info("{} at {}: removed {} logs, {} leaves, {} bee nests or cocoa pods and {} vines of cut trees",
                    owner, middle.toShortString(), cutLogs.size(), orphanLeaves.size(), nests, lostVines);
        }
    }

    /**
     * Gives every vine of the scan area the shape vanilla would give it now
     * that the trees are gone: a face whose log or leaf was removed is
     * dropped, and a vine left with no face becomes air. Top down, so a vine
     * hanging from one that just went loses its hold in turn, as vanilla's
     * chain of updates would. Without this, the vines of a cut jungle tree
     * would stay hanging in the air: blocks are removed without updating
     * their neighbours.
     *
     * <p>The new shape comes from {@code Block.updateFromNeighbourShapes},
     * the vine's own rules; nothing drops (set without updates).
     *
     * @return how many vines were removed
     */
    private int detachVines(ServerLevel level) {
        vines.sort((a, b) -> Integer.compare(BlockPos.getY(b), BlockPos.getY(a)));
        int removed = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (long vine : vines) {
            BlockState state = level.getBlockState(cursor.set(vine));
            if (!state.is(Blocks.VINE)) {
                continue;
            }
            BlockState updated = Block.updateFromNeighbourShapes(state, level, cursor);
            if (updated == state) {
                continue;
            }
            level.setBlock(cursor, updated, PLACE_FLAGS);
            if (updated.isAir()) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Logs of every group that touches the removal area and rests on
     * nothing. A group reaching the edge of the scan is kept: what holds it
     * may lie beyond.
     */
    private static List<Long> floatingLogs(ServerLevel level, Set<Long> logs, Set<Long> leaves, Box scan,
                                           Box removal) {
        List<Long> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (long start : logs) {
            if (seen.contains(start) || !removal.contains(BlockPos.of(start))) {
                continue;
            }
            List<Long> group = new ArrayList<>();
            boolean held = false;
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            seen.add(start);
            while (!queue.isEmpty()) {
                long current = queue.poll();
                group.add(current);
                BlockPos pos = BlockPos.of(current);
                if (!held && (scan.onEdge(pos) || restsOnSolid(level, pos, logs, leaves, cursor))) {
                    held = true;
                }
                // Branches join diagonally: 26 neighbours, as a tree is drawn.
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long next = BlockPos.offset(current, dx, dy, dz);
                            if (logs.contains(next) && seen.add(next)) {
                                queue.add(next);
                            }
                        }
                    }
                }
            }
            if (!held) {
                result.addAll(group);
            }
        }
        return result;
    }

    /**
     * Whether a log stands on something solid that is neither a log nor
     * leaves: the ground under a trunk, a wall under a beam. "Solid" is a
     * non-empty collision box, so tall grass or a flower does not count.
     */
    private static boolean restsOnSolid(ServerLevel level, BlockPos log, Set<Long> logs, Set<Long> leaves,
                                        BlockPos.MutableBlockPos cursor) {
        long below = BlockPos.offset(log.asLong(), 0, -1, 0);
        if (logs.contains(below) || leaves.contains(below)) {
            return false;
        }
        cursor.set(log.getX(), log.getY() - 1, log.getZ());
        return !level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty();
    }

    /**
     * Natural leaves of the removal area more than {@link #LEAF_REACH}
     * leaves away from every remaining log: a breadth-first walk from all
     * logs through leaves, the way vanilla computes LeavesBlock distance.
     */
    private static List<Long> orphanLeaves(ServerLevel level, Set<Long> logs, Set<Long> leaves, Box removal) {
        Map<Long, Integer> distance = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        for (long log : logs) {
            distance.put(log, 0);
            queue.add(log);
        }
        while (!queue.isEmpty()) {
            long current = queue.poll();
            int steps = distance.get(current);
            if (steps >= LEAF_REACH) {
                continue;
            }
            for (Direction side : Direction.values()) {
                long next = BlockPos.offset(current, side);
                if (leaves.contains(next) && !distance.containsKey(next)) {
                    distance.put(next, steps + 1);
                    queue.add(next);
                }
            }
        }
        List<Long> result = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (long leaf : leaves) {
            if (distance.containsKey(leaf)) {
                continue;
            }
            cursor.set(leaf);
            if (!removal.contains(cursor)) {
                continue;
            }
            BlockState state = level.getBlockState(cursor);
            // Placed by a player (or by the gate's builder): never decays, never removed.
            if (state.hasProperty(BlockStateProperties.PERSISTENT) && state.getValue(BlockStateProperties.PERSISTENT)) {
                continue;
            }
            result.add(leaf);
        }
        return result;
    }

    /** Whether every chunk of the scan area is loaded, checked one block per chunk (about 16 checks). */
    private boolean loaded(ServerLevel level) {
        for (int x = scan.minX; x <= scan.maxX + 15; x += 16) {
            for (int z = scan.minZ; z <= scan.maxZ + 15; z += 16) {
                if (!level.hasChunkAt(new BlockPos(Math.min(x, scan.maxX), ground, Math.min(z, scan.maxZ)))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** An axis-aligned box of block positions, bounds included. */
    private record Box(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {

        /** Whether a position lies inside. */
        boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        /** Whether a position lies on the outer shell, where what lies beyond is unknown. */
        boolean onEdge(BlockPos pos) {
            return pos.getX() == minX || pos.getX() == maxX || pos.getY() == minY || pos.getY() == maxY
                    || pos.getZ() == minZ || pos.getZ() == maxZ;
        }
    }
}
