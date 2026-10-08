package dev.darcosse.common.cobblemonsafari.structure;

import com.cobblemon.mod.common.block.entity.HealingMachineBlockEntity;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.block.SafariBlocks;
import dev.darcosse.common.cobblemonsafari.util.Surface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/**
 * Builds one rest house: a 9x9 mini Pokémon Center (white walls, red roof,
 * glass front) with a Cobblemon Healing Machine behind the counter.
 *
 * <p>Layout is written in local coordinates where the door is on the +Z side
 * ("front"); {@link #at} rotates them to the requested facing. The floor sits
 * at the median ground height of the footprint, a stone foundation fills
 * whatever is below (slopes, rivers), and the volume above is cleared, so the
 * house stands level on any terrain the seed produced.
 */
final class RestHouseBuilder {

    /** Interior height between floor and ceiling. 4 fits a player on a ridden Pokémon. */
    private static final int INTERIOR_HEIGHT = 4;

    /**
     * Deepest the foundation goes. Over a deep lagoon it stops here and the
     * house stands on a pillar of this height; deeper would only waste blocks.
     */
    private static final int MAX_FOUNDATION_DEPTH = 24;

    /** Air cleared above the floor, enough for the roof and a tree trunk cut cleanly. */
    private static final int CLEAR_HEIGHT = 9;

    /**
     * Only clients are told about the changes; neighbours are not updated.
     * Neighbour updates would make cut leaves decay and nearby water try to
     * flow into the freshly cleared space, which is exactly what is not wanted.
     */
    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS;

    /**
     * Safari Board screen of the entrance house: local z range on the left
     * wall and height in blocks. Must stay between the windows (local z 3 to
     * 5) and the front wall (local z 8), and under the red band (y 4).
     */
    private static final int BOARD_FIRST_LZ = 6;
    private static final int BOARD_LAST_LZ = 7;
    private static final int BOARD_HEIGHT = 2;

    /** Cobblemon's healing machine, looked up by id so no Kotlin object accessor is involved. */
    private static final ResourceLocation HEALING_MACHINE_ID =
            ResourceLocation.fromNamespaceAndPath("cobblemon", "healing_machine");

    private final ServerLevel level;
    private final int centerX;
    private final int centerZ;
    private final Direction facing;
    private int floorY;

    private RestHouseBuilder(ServerLevel level, int centerX, int centerZ, Direction facing) {
        this.level = level;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.facing = facing;
    }

    /**
     * Builds a rest house centred on the given column.
     *
     * @param facing   direction the door opens toward
     * @param entrance whether to add the Safari Board (the entrance house only)
     */
    static void build(ServerLevel level, int centerX, int centerZ, Direction facing, boolean entrance) {
        RestHouseBuilder builder = new RestHouseBuilder(level, centerX, centerZ, facing);
        builder.floorY = builder.findFloorY();
        builder.foundation();
        builder.clear();
        builder.shell();
        builder.interior(entrance);
    }

    /**
     * Median of the ground height over nine points of the footprint. The median,
     * not the mean: one tree stump or one river cell must not lift or sink the
     * whole house.
     */
    private int findFloorY() {
        int[] heights = new int[9];
        int i = 0;
        for (int lx = 0; lx <= 8; lx += 4) {
            for (int lz = 0; lz <= 8; lz += 4) {
                BlockPos column = at(lx, 0, lz);
                // First free block minus one: the floor replaces the top ground block.
                heights[i++] = Surface.forced(level, column.getX(), column.getZ()).getY() - 1;
            }
        }
        Arrays.sort(heights);
        int median = heights[heights.length / 2];
        int ceiling = level.getMaxBuildHeight() - CLEAR_HEIGHT - 1;
        return Math.max(level.getMinBuildHeight() + MAX_FOUNDATION_DEPTH, Math.min(ceiling, median));
    }

    /** Fills from just below the floor down to solid ground, under the house and its porch. */
    private void foundation() {
        for (int lx = 0; lx <= 8; lx++) {
            for (int lz = 0; lz <= 10; lz++) {
                if (lz > 8 && (lx < 3 || lx > 5)) {
                    continue;
                }
                for (int depth = 1; depth <= MAX_FOUNDATION_DEPTH; depth++) {
                    BlockPos pos = at(lx, -depth, lz);
                    BlockState existing = level.getBlockState(pos);
                    boolean solidGround = existing.getFluidState().isEmpty()
                            && existing.isFaceSturdy(level, pos, Direction.UP);
                    if (solidGround) {
                        break;
                    }
                    set(pos, Blocks.STONE_BRICKS.defaultBlockState());
                }
            }
        }
    }

    /** Empties the volume of the house, a one-block ring around it, and the porch. */
    private void clear() {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int lx = -1; lx <= 9; lx++) {
            for (int lz = -1; lz <= 10; lz++) {
                for (int y = 1; y <= CLEAR_HEIGHT; y++) {
                    set(at(lx, y, lz), air);
                }
            }
        }
    }

    /** Floor, walls, windows, door opening and roof. */
    private void shell() {
        BlockState white = Blocks.WHITE_CONCRETE.defaultBlockState();
        BlockState red = Blocks.RED_CONCRETE.defaultBlockState();
        BlockState glass = Blocks.GLASS.defaultBlockState();
        BlockState light = Blocks.GLOWSTONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        for (int lx = 0; lx <= 8; lx++) {
            for (int lz = 0; lz <= 8; lz++) {
                set(at(lx, 0, lz), white);
                boolean edge = lx == 0 || lx == 8 || lz == 0 || lz == 8;
                boolean corner = (lx == 0 || lx == 8) && (lz == 0 || lz == 8);
                if (edge) {
                    for (int y = 1; y <= INTERIOR_HEIGHT; y++) {
                        // The red band under the roof and red corners: the Pokémon Center look.
                        set(at(lx, y, lz), corner || y == INTERIOR_HEIGHT ? red : white);
                    }
                }
                set(at(lx, INTERIOR_HEIGHT + 1, lz), red);
            }
        }
        // Stepped roof, lights hidden in the lower layer.
        for (int lx = 1; lx <= 7; lx++) {
            for (int lz = 1; lz <= 7; lz++) {
                set(at(lx, INTERIOR_HEIGHT + 2, lz), red);
            }
        }
        for (int[] spot : new int[][]{{2, 2}, {6, 2}, {2, 6}, {6, 6}}) {
            set(at(spot[0], INTERIOR_HEIGHT + 1, spot[1]), light);
        }
        // Windows: two panes on each side, two on each side of the door, two at the back.
        for (int y = 2; y <= 3; y++) {
            for (int l = 3; l <= 5; l++) {
                set(at(0, y, l), glass);
                set(at(8, y, l), glass);
            }
            for (int lx : new int[]{1, 2, 6, 7}) {
                set(at(lx, y, 8), glass);
            }
            for (int lx : new int[]{2, 3, 5, 6}) {
                set(at(lx, y, 0), glass);
            }
        }
        // Three-wide doorway: no door block, so nothing can be closed on a ridden Pokémon.
        for (int lx = 3; lx <= 5; lx++) {
            set(at(lx, 1, 8), air);
            set(at(lx, 2, 8), air);
        }
        // Porch in front of the door.
        BlockState porch = Blocks.SMOOTH_STONE.defaultBlockState();
        for (int lx = 3; lx <= 5; lx++) {
            for (int lz = 9; lz <= 10; lz++) {
                set(at(lx, 0, lz), porch);
            }
        }
    }

    /** Counter, Healing Machine, red carpet from the door, decoration, and the board. */
    private void interior(boolean entrance) {
        BlockState counter = Blocks.SMOOTH_QUARTZ.defaultBlockState();
        for (int lx = 2; lx <= 6; lx++) {
            if (lx != 4) {
                set(at(lx, 1, 2), counter);
            }
        }
        for (int lz = 3; lz <= 8; lz++) {
            set(at(4, 0, lz), Blocks.RED_CONCRETE.defaultBlockState());
        }
        set(at(1, 1, 1), Blocks.BOOKSHELF.defaultBlockState());
        set(at(7, 1, 1), Blocks.BOOKSHELF.defaultBlockState());
        set(at(7, 1, 4), Blocks.POTTED_FERN.defaultBlockState());
        set(at(7, 1, 6), Blocks.POTTED_FERN.defaultBlockState());
        placeHealingMachine(at(4, 1, 2));

        if (entrance) {
            // A 2×2 screen on the left wall, readable from the doorway. Kept to
            // the two cells next to the front wall: further in, it would cover
            // the windows from the inside.
            BlockState board = SafariBlocks.safariBoard().defaultBlockState()
                    .setValue(HorizontalDirectionalBlock.FACING, localEast());
            for (int y = 1; y <= BOARD_HEIGHT; y++) {
                for (int lz = BOARD_FIRST_LZ; lz <= BOARD_LAST_LZ; lz++) {
                    set(at(1, y, lz), board);
                }
            }
        }
    }

    /**
     * Places Cobblemon's Healing Machine and makes it infinite. A machine
     * placed by code starts empty and recharges slowly; in a timed park, a
     * player waiting for the charge is a player losing hunting time. The flag
     * is the same one Cobblemon sets for creative-placed machines.
     */
    private void placeHealingMachine(BlockPos pos) {
        Block machine = BuiltInRegistries.BLOCK.get(HEALING_MACHINE_ID);
        // The block registry is defaulted: a missing Cobblemon block yields air.
        if (machine == Blocks.AIR) {
            CobblemonSafari.LOGGER.error("Block {} not found; rest house built without a Healing Machine",
                    HEALING_MACHINE_ID);
            return;
        }
        BlockState state = machine.defaultBlockState();
        if (state.hasProperty(HorizontalDirectionalBlock.FACING)) {
            // Cobblemon stores the direction its placer was looking; a player
            // standing at the door looks toward the back of the house.
            state = state.setValue(HorizontalDirectionalBlock.FACING, facing.getOpposite());
        }
        set(pos, state);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof HealingMachineBlockEntity healingMachine) {
            healingMachine.setInfinite(true);
            healingMachine.setChanged();
        }
    }

    /** World direction of local +X, i.e. the facing turned a quarter counter-clockwise. */
    private Direction localEast() {
        return switch (facing) {
            case SOUTH -> Direction.EAST;
            case NORTH -> Direction.WEST;
            case EAST -> Direction.NORTH;
            default -> Direction.SOUTH;
        };
    }

    /**
     * World position of a local cell. Local (4, 4) is the centre column; the
     * rotation keeps handedness, so the layout is turned, never mirrored.
     */
    private BlockPos at(int lx, int y, int lz) {
        int dx = lx - RestHouses.HALF_SIZE;
        int dz = lz - RestHouses.HALF_SIZE;
        int wx;
        int wz;
        switch (facing) {
            case NORTH -> {
                wx = -dx;
                wz = -dz;
            }
            case EAST -> {
                wx = dz;
                wz = -dx;
            }
            case WEST -> {
                wx = -dz;
                wz = dx;
            }
            default -> {
                wx = dx;
                wz = dz;
            }
        }
        return new BlockPos(centerX + wx, floorY + y, centerZ + wz);
    }

    /** Places one block with the builder's update flags. */
    private void set(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, PLACE_FLAGS);
    }
}
