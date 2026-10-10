package dev.darcosse.common.cobblemonsafari.block;

import dev.darcosse.common.cobblemonsafari.gate.GateResidents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity of a Safari Gate block. The one at the gate's bottom corner
 * (lowest block, smallest coordinate along the axis) is the gate's
 * caretaker: every few seconds it makes sure the warden and the roaming
 * Pokémon are there. The others only count ticks.
 *
 * <p>Nothing is saved: residents are found again by their entity tags, so
 * a gate rebuilt or moved by an operator simply adopts or respawns them.
 */
public class SafariGateBlockEntity extends BlockEntity {

    /**
     * Ticks between two resident checks. Each check is two entity box
     * queries; lower values make strays come back faster at a higher cost.
     */
    private static final int CHECK_INTERVAL_TICKS = 100;

    /**
     * Delay before the first check after the block entity loads. Entities
     * load slightly after the chunk's block entities; checking right away
     * would find nobody and spawn duplicates of residents about to load.
     */
    private static final int WARMUP_TICKS = 200;

    /** Longest gate side scanned when finding the corner and the middle. Real gates are 2 to 4 wide. */
    private static final int MAX_GATE_SPAN = 8;

    /** Ticks since this block entity was loaded; not saved on purpose (see {@link #WARMUP_TICKS}). */
    private int ticksLoaded;

    /** Block entity at the given gate block. */
    public SafariGateBlockEntity(BlockPos pos, BlockState state) {
        super(SafariBlockEntities.safariGate(), pos, state);
    }

    /**
     * Server tick. Cheap for every block (one increment and a modulo); the
     * corner check and the resident upkeep run once per interval.
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, SafariGateBlockEntity gate) {
        gate.ticksLoaded++;
        if (gate.ticksLoaded < WARMUP_TICKS
                // Offset by position so that the gates of a loaded area do not all work on the same tick.
                || Math.floorMod(gate.ticksLoaded + pos.hashCode(), CHECK_INTERVAL_TICKS) != 0
                || !(level instanceof ServerLevel serverLevel)
                || !isCorner(level, pos, state)) {
            return;
        }
        GateResidents.maintain(serverLevel, middleOf(level, pos, state), state.getValue(SafariGateBlock.AXIS));
    }

    /** Whether this block is the gate's bottom corner, the one that owns the residents. */
    private static boolean isCorner(Level level, BlockPos pos, BlockState state) {
        Direction back = Direction.fromAxisAndDirection(state.getValue(SafariGateBlock.AXIS),
                Direction.AxisDirection.NEGATIVE);
        return !isGate(level, pos.below()) && !isGate(level, pos.relative(back));
    }

    /** Bottom block in the middle of the gate, measured from its corner. */
    private static BlockPos middleOf(Level level, BlockPos corner, BlockState state) {
        Direction along = Direction.fromAxisAndDirection(state.getValue(SafariGateBlock.AXIS),
                Direction.AxisDirection.POSITIVE);
        int width = 1;
        while (width < MAX_GATE_SPAN && isGate(level, corner.relative(along, width))) {
            width++;
        }
        return corner.relative(along, width / 2);
    }

    /** Whether the block at a position is part of a gate. */
    private static boolean isGate(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof SafariGateBlock;
    }
}
