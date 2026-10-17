package dev.darcosse.common.cobblemonsafari.block;

import dev.darcosse.common.cobblemonsafari.gate.GateClearing;
import dev.darcosse.common.cobblemonsafari.gate.GateResidents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Block entity of a Safari Gate block. The one at the gate's bottom corner
 * (lowest block, smallest coordinate along the axis) is the gate's
 * caretaker: every few seconds it makes sure the roaming Pokémon are there,
 * and the warden too when the gate keeps one. The others only count ticks.
 *
 * <p>Two flags are saved. {@link #KEEPS_WARDEN_KEY}: true for the blocks of
 * a generated Safari Gate structure, false for a block an operator places by
 * hand, so a gate built in creative only gets the roaming Pokémon.
 * {@link #CLEAR_TREES_KEY}: set by the structure file only, it asks the
 * corner to remove once the trees the structure cut through
 * ({@link GateClearing}, spread over a few ticks), then is cleared on
 * every block. Residents themselves are
 * found again by their entity tags, so a gate rebuilt or moved by an
 * operator simply adopts or respawns them.
 */
public class SafariGateBlockEntity extends BlockEntity {

    /**
     * Save key of {@link #keepsWarden}. Part of the save format and of the
     * structure file (structure/safari_gate.nbt carries it on every pane
     * block); renaming it makes every generated gate lose its warden.
     * Operators set it by hand with
     * {@code /data merge block <x> <y> <z> {keeps_warden:1b}}.
     */
    public static final String KEEPS_WARDEN_KEY = "keeps_warden";

    /**
     * Save key of {@link #clearTrees}. Only structure/safari_gate.nbt sets
     * it; missing reads as false, so neither a hand-placed gate nor a gate
     * of an older world ever has blocks around it removed.
     */
    public static final String CLEAR_TREES_KEY = "clear_trees";

    /**
     * Ticks between two resident checks. Each check is two entity box
     * queries; lower values make strays, and a warden removed with /kill,
     * come back faster at a higher cost.
     */
    private static final int CHECK_INTERVAL_TICKS = 100;

    /**
     * Delay before the first check after the block entity loads. Entities
     * load slightly after the chunk's block entities; checking right away
     * would find nobody and spawn duplicates of residents about to load.
     */
    private static final int WARMUP_TICKS = 200;

    /**
     * Longest gate side scanned when finding the corner, the middle and the
     * warden flag, along the axis and upward. Real gates are 2 to 4 wide and
     * up to 5 high; larger costs up to this squared block reads per check.
     */
    private static final int MAX_GATE_SPAN = 8;

    /** Ticks since this block entity was loaded; not saved on purpose (see {@link #WARMUP_TICKS}). */
    private int ticksLoaded;

    /**
     * Whether the gate this block belongs to keeps a warden. False for a new
     * block (placed by hand); see {@link #loadAdditional} for saved ones.
     */
    private boolean keepsWarden;

    /** Whether the cut trees around this generated gate are still to be removed. */
    private boolean clearTrees;

    /**
     * The tree clearing under way, on the corner only; null otherwise. Not
     * saved: after a reload the flag is still set and a new job starts.
     */
    @Nullable
    private GateClearing clearing;

    /** Block entity at the given gate block. */
    public SafariGateBlockEntity(BlockPos pos, BlockState state) {
        super(SafariBlockEntities.safariGate(), pos, state);
    }

    /**
     * Reads the warden flag. A saved block without the key keeps a warden:
     * that is every gate block of a world from before the flag existed, all
     * of which had one, and a structure saved without the key. A block placed
     * by hand never goes through here before being saved with false.
     */
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        keepsWarden = !tag.contains(KEEPS_WARDEN_KEY) || tag.getBoolean(KEEPS_WARDEN_KEY);
        clearTrees = tag.getBoolean(CLEAR_TREES_KEY);
    }

    /**
     * Writes both flags, always, so a hand-placed block stays without warden
     * after a reload. The tree flag is only written while set, so a re-saved
     * structure does not carry a stale false.
     */
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean(KEEPS_WARDEN_KEY, keepsWarden);
        if (clearTrees) {
            tag.putBoolean(CLEAR_TREES_KEY, true);
        }
    }

    /**
     * Server tick. Cheap for every block (one increment and a modulo); the
     * corner check and the resident upkeep run once per interval. While a
     * generated gate's tree clearing runs, its corner also reads up to
     * GateClearing.READS_PER_TICK blocks a tick, for a few ticks, once.
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, SafariGateBlockEntity gate) {
        gate.ticksLoaded++;
        if (gate.clearing != null && level instanceof ServerLevel clearingLevel) {
            GateClearing.Status status = gate.clearing.step(clearingLevel);
            if (status != GateClearing.Status.RUNNING) {
                gate.clearing = null;
            }
            if (status == GateClearing.Status.DONE) {
                finishClearing(level, pos, state);
            }
        }
        if (gate.ticksLoaded < WARMUP_TICKS
                // Offset by position so that the gates of a loaded area do not all work on the same tick.
                || Math.floorMod(gate.ticksLoaded + pos.hashCode(), CHECK_INTERVAL_TICKS) != 0
                || !(level instanceof ServerLevel serverLevel)
                || !isCorner(level, pos, state)) {
            return;
        }
        Direction.Axis axis = state.getValue(SafariGateBlock.AXIS);
        int width = widthOf(level, pos, axis);
        Direction along = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
        BlockPos middle = pos.relative(along, width / 2);
        List<SafariGateBlockEntity> blocks = gateBlocks(level, pos, along, width);
        if (gate.clearing == null && blocks.stream().anyMatch(block -> block.clearTrees)) {
            gate.clearing = new GateClearing(serverLevel, middle);
        }
        GateResidents.maintain(serverLevel, middle, axis, blocks.stream().anyMatch(block -> block.keepsWarden));
    }

    /** Drops the tree flag from every block of the gate once its clearing is done, so it never runs again. */
    private static void finishClearing(Level level, BlockPos corner, BlockState state) {
        Direction.Axis axis = state.getValue(SafariGateBlock.AXIS);
        Direction along = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
        for (SafariGateBlockEntity block : gateBlocks(level, corner, along, widthOf(level, corner, axis))) {
            block.clearTrees = false;
            block.setChanged();
        }
    }

    /** Whether this block is the gate's bottom corner, the one that owns the residents. */
    private static boolean isCorner(Level level, BlockPos pos, BlockState state) {
        Direction back = Direction.fromAxisAndDirection(state.getValue(SafariGateBlock.AXIS),
                Direction.AxisDirection.NEGATIVE);
        return !isGate(level, pos.below()) && !isGate(level, pos.relative(back));
    }

    /** Number of gate blocks in the bottom row, from the corner along the axis. */
    private static int widthOf(Level level, BlockPos corner, Direction.Axis axis) {
        Direction along = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
        int width = 1;
        while (width < MAX_GATE_SPAN && isGate(level, corner.relative(along, width))) {
            width++;
        }
        return width;
    }

    /**
     * Every block entity of the gate, column by column from the corner. The
     * flags are read across all of them rather than on the corner alone: a
     * generated gate an operator widens by hand, or flags on one block with
     * /data, still keeps its warden.
     */
    private static List<SafariGateBlockEntity> gateBlocks(Level level, BlockPos corner, Direction along, int width) {
        List<SafariGateBlockEntity> blocks = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < MAX_GATE_SPAN; y++) {
                if (!(level.getBlockEntity(corner.relative(along, x).above(y)) instanceof SafariGateBlockEntity gate)) {
                    // Top of this column reached.
                    break;
                }
                blocks.add(gate);
            }
        }
        return blocks;
    }

    /** Whether the block at a position is part of a gate. */
    private static boolean isGate(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof SafariGateBlock;
    }
}
