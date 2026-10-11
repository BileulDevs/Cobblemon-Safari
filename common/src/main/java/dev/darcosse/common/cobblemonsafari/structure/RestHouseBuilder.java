package dev.darcosse.common.cobblemonsafari.structure;

import com.cobblemon.mod.common.block.entity.CampfireBlockEntity;
import com.cobblemon.mod.common.block.entity.HealingMachineBlockEntity;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.block.SafariBlocks;
import dev.darcosse.common.cobblemonsafari.block.SafariBoardFrameBlock;
import dev.darcosse.common.cobblemonsafari.util.Surface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Builds one rest house: an 11×11 Pokémon Center with a front plaza.
 *
 * <p>Inside: a tiled floor with a red carpet to the counter, the Healing
 * Machine on the counter, a PC, a cooking corner with a Cobblemon Poké Pot
 * on its campfire, and a seating corner. Outside: a stepped red roof with a
 * Poké Ball band over the glass front, lanterns, flower beds, and (entrance
 * house only) a framed Safari Board standing on the plaza.
 *
 * <p>Layout is written in local coordinates where the door is on the +Z
 * side ("front") and local +X is {@link #localEast()}; {@link #at} rotates
 * everything to the requested facing. The floor sits at the median
 * ground height, a stone foundation fills whatever is below, and the volume
 * above is cleared, so the house stands level on any terrain.
 */
final class RestHouseBuilder {

    /** Local extent of the house itself: x and z from 0 to this value. Must match {@link RestHouses#HALF_SIZE}. */
    private static final int SIZE = RestHouses.HALF_SIZE * 2;

    /** Interior height between floor and ceiling. 4 fits a player on a ridden Pokémon. */
    private static final int INTERIOR_HEIGHT = 4;

    /** Front plaza, in local z, in front of the front wall (z = SIZE). Holds the board and lanterns. */
    private static final int PLAZA_FIRST_Z = SIZE + 1;
    private static final int PLAZA_LAST_Z = SIZE + 4;
    /** The plaza and the cleared volume reach this far beyond the house sides. */
    private static final int SIDE_MARGIN = 2;

    /**
     * Deepest the foundation goes. Over a deep lagoon it stops here and the
     * house stands on a pillar of this height; deeper would only waste blocks.
     */
    private static final int MAX_FOUNDATION_DEPTH = 24;

    /** Air cleared above the floor: roof (three steps) plus a margin for cut tree trunks. */
    private static final int CLEAR_HEIGHT = 11;

    /**
     * Only clients are told about the changes; neighbours are not updated.
     * Neighbour updates would make cut leaves decay and nearby water try to
     * flow into the freshly cleared space, which is exactly what is not wanted.
     */
    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS;

    /**
     * Framed Safari Board of the entrance house, on the plaza to the left of
     * the porch: screen columns (local x), its two rows (local y) and the
     * local z it stands on. Frames surround it, their bottom row on the
     * plaza so the frame grows feet.
     */
    private static final int BOARD_FIRST_X = 0;
    private static final int BOARD_LAST_X = 2;
    private static final int BOARD_BOTTOM_Y = 2;
    private static final int BOARD_TOP_Y = 3;
    private static final int BOARD_Z = SIZE + 3;

    /**
     * Where the clerk stands: behind the counter (local z 2), between the
     * shelves (x 1-2 and SIZE-2 to SIZE-1), next to the Healing Machine.
     * Must stay a free cell of {@link #interior}; RestHouseClerk finds the
     * floor there by looking for the gap under the ceiling.
     */
    static final int CLERK_X = SIZE / 2 - 2;
    static final int CLERK_Z = 1;

    /** Cobblemon blocks and items, looked up by id so no Kotlin object accessor is involved. */
    private static final ResourceLocation HEALING_MACHINE_ID = cobblemon("healing_machine");
    private static final ResourceLocation PC_ID = cobblemon("pc");
    private static final ResourceLocation CAMPFIRE_ID = cobblemon("campfire");
    private static final ResourceLocation POT_ITEM_ID = cobblemon("campfire_pot_red");

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
        builder.plaza();
        builder.shell();
        builder.roof();
        builder.interior();
        if (entrance) {
            builder.board();
        }
    }

    // ---- Ground ----------------------------------------------------------

    /**
     * Median of the ground height over nine points of the footprint. The median,
     * not the mean: one tree stump or one river cell must not lift or sink the
     * whole house.
     */
    private int findFloorY() {
        int[] heights = new int[9];
        int i = 0;
        for (int lx = 0; lx <= SIZE; lx += SIZE / 2) {
            for (int lz = 0; lz <= SIZE; lz += SIZE / 2) {
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

    /** Fills from just below the floor down to solid ground, under the house and its plaza. */
    private void foundation() {
        for (int lx = -SIDE_MARGIN; lx <= SIZE + SIDE_MARGIN; lx++) {
            for (int lz = 0; lz <= PLAZA_LAST_Z; lz++) {
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

    /** Empties the volume of the house, a ring around it for the eaves, and the plaza. */
    private void clear() {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int lx = -SIDE_MARGIN - 1; lx <= SIZE + SIDE_MARGIN + 1; lx++) {
            for (int lz = -1; lz <= PLAZA_LAST_Z + 1; lz++) {
                for (int y = 1; y <= CLEAR_HEIGHT; y++) {
                    set(at(lx, y, lz), air);
                }
            }
        }
    }

    /**
     * Stone plaza in front of the house, a path to the door, flower beds
     * along the front wall and lantern posts on each side of the doorway.
     */
    private void plaza() {
        int door = SIZE / 2;
        for (int lx = -SIDE_MARGIN; lx <= SIZE + SIDE_MARGIN; lx++) {
            for (int lz = PLAZA_FIRST_Z; lz <= PLAZA_LAST_Z; lz++) {
                boolean border = lx == -SIDE_MARGIN || lx == SIZE + SIDE_MARGIN || lz == PLAZA_LAST_Z;
                boolean path = Math.abs(lx - door) <= 1;
                set(at(lx, 0, lz), border ? Blocks.STONE_BRICKS.defaultBlockState()
                        : path ? Blocks.POLISHED_ANDESITE.defaultBlockState()
                        : Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        // Flower beds along the front wall, either side of the path.
        Block[] flowers = {Blocks.POPPY, Blocks.DANDELION, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY};
        int flower = 0;
        for (int lx = 0; lx <= SIZE; lx++) {
            if (Math.abs(lx - door) <= 2) {
                continue;
            }
            set(at(lx, 0, PLAZA_FIRST_Z), Blocks.MOSS_BLOCK.defaultBlockState());
            set(at(lx, 1, PLAZA_FIRST_Z), flowers[flower++ % flowers.length].defaultBlockState());
        }
        // Lantern posts flanking the path.
        for (int lx : new int[]{door - 2, door + 2}) {
            set(at(lx, 0, PLAZA_FIRST_Z), Blocks.POLISHED_ANDESITE.defaultBlockState());
            set(at(lx, 1, PLAZA_FIRST_Z), Blocks.POLISHED_ANDESITE.defaultBlockState());
            set(at(lx, 2, PLAZA_FIRST_Z), Blocks.LANTERN.defaultBlockState());
        }
    }

    // ---- Building ----------------------------------------------------------

    /** Tiled floor, white walls with quartz corners, red band, windows, glass front and doorway. */
    private void shell() {
        BlockState white = Blocks.WHITE_CONCRETE.defaultBlockState();
        BlockState red = Blocks.RED_CONCRETE.defaultBlockState();
        BlockState pillar = Blocks.QUARTZ_PILLAR.defaultBlockState();
        BlockState tileA = Blocks.SMOOTH_QUARTZ.defaultBlockState();
        BlockState tileB = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        BlockState glass = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        for (int lx = 0; lx <= SIZE; lx++) {
            for (int lz = 0; lz <= SIZE; lz++) {
                // Checkerboard tiles, the usual Pokémon Center floor.
                set(at(lx, 0, lz), (lx + lz) % 2 == 0 ? tileA : tileB);
                boolean sideX = lx == 0 || lx == SIZE;
                boolean sideZ = lz == 0 || lz == SIZE;
                if (sideX || sideZ) {
                    for (int y = 1; y <= INTERIOR_HEIGHT; y++) {
                        set(at(lx, y, lz), sideX && sideZ ? pillar : white);
                    }
                    set(at(lx, INTERIOR_HEIGHT + 1, lz), sideX && sideZ ? pillar : red);
                } else {
                    // Ceiling, lit by sea lanterns in a grid.
                    boolean lamp = lx % 3 == 2 && lz % 3 == 2;
                    set(at(lx, INTERIOR_HEIGHT + 1, lz), lamp
                            ? Blocks.SEA_LANTERN.defaultBlockState() : white);
                }
            }
        }
        // Side windows are offset so they never sit behind the PC (right wall,
        // near the counter) or the cooking corner (left wall, near the door).
        for (int y = 2; y <= 3; y++) {
            for (int lz = 2; lz <= 4; lz++) {
                set(at(0, y, lz), glass);
            }
            for (int lz = 6; lz <= 8; lz++) {
                set(at(SIZE, y, lz), glass);
            }
            for (int lx : new int[]{3, 4, 6, 7}) {
                set(at(lx, y, 0), glass);
            }
        }
        // Glass front, three high, with a three-wide doorway in the middle and
        // no door block, so nothing can be closed on a ridden Pokémon.
        int door = SIZE / 2;
        for (int lx = 1; lx < SIZE; lx++) {
            for (int y = 1; y <= 3; y++) {
                set(at(lx, y, SIZE), Math.abs(lx - door) <= 1 ? air : glass);
            }
        }
        // A Poké Ball above the doorway: red over a black button between white.
        set(at(door, INTERIOR_HEIGHT, SIZE), Blocks.BLACK_CONCRETE.defaultBlockState());
        set(at(door - 1, INTERIOR_HEIGHT, SIZE), white);
        set(at(door + 1, INTERIOR_HEIGHT, SIZE), white);
    }

    /** Stepped red roof with eaves overhanging the walls by one block, white trim and a white cap. */
    private void roof() {
        BlockState red = Blocks.RED_CONCRETE.defaultBlockState();
        BlockState trim = Blocks.SMOOTH_QUARTZ_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        int base = INTERIOR_HEIGHT + 2;
        for (int lx = -1; lx <= SIZE + 1; lx++) {
            for (int lz = -1; lz <= SIZE + 1; lz++) {
                boolean eave = lx == -1 || lx == SIZE + 1 || lz == -1 || lz == SIZE + 1;
                set(at(lx, base, lz), eave ? trim : red);
            }
        }
        for (int lx = 1; lx <= SIZE - 1; lx++) {
            for (int lz = 1; lz <= SIZE - 1; lz++) {
                set(at(lx, base + 1, lz), red);
            }
        }
        for (int lx = 3; lx <= SIZE - 3; lx++) {
            for (int lz = 3; lz <= SIZE - 3; lz++) {
                set(at(lx, base + 2, lz), Blocks.WHITE_CONCRETE.defaultBlockState());
            }
        }
    }

    /**
     * Counter with the Healing Machine, red carpet from the door, PC,
     * cooking corner with the Poké Pot, and seating.
     */
    private void interior() {
        int door = SIZE / 2;
        BlockState counter = Blocks.SMOOTH_QUARTZ.defaultBlockState();
        for (int lx = 2; lx <= SIZE - 2; lx++) {
            if (lx != door) {
                set(at(lx, 1, 2), counter);
            }
        }
        placeHealingMachine(at(door, 1, 2));
        // Behind the counter: shelves against the back wall.
        for (int lx : new int[]{1, 2, SIZE - 2, SIZE - 1}) {
            set(at(lx, 1, 1), Blocks.BOOKSHELF.defaultBlockState());
            set(at(lx, 2, 1), Blocks.BOOKSHELF.defaultBlockState());
        }
        // Red carpet from the doorway to the counter.
        for (int lz = 3; lz < SIZE; lz++) {
            set(at(door, 1, lz), Blocks.RED_CARPET.defaultBlockState());
        }

        // PC against the right wall, screen toward the room. Like every
        // Cobblemon station, its "facing" is the direction its placer LOOKED
        // (see placePc): a player in the room looks east at it.
        placePc(at(SIZE - 1, 1, 4), localDirection(Direction.EAST));

        // Cooking corner on the left wall, near the door, away from the counter queue.
        // Same convention: a player in the room looks west at the campfire.
        placePokePot(at(1, 1, 7), localDirection(Direction.WEST));
        set(at(1, 1, 6), Blocks.BARREL.defaultBlockState());
        set(at(1, 1, 8), Blocks.SMOKER.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING,
                localDirection(Direction.EAST)));

        // Seating corner by the right windows: two benches around a small table.
        BlockState seat = Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        set(at(SIZE - 1, 1, 6), seat);
        set(at(SIZE - 1, 1, 8), seat);
        set(at(SIZE - 1, 1, 7), Blocks.SPRUCE_FENCE.defaultBlockState());
        set(at(SIZE - 1, 2, 7), Blocks.WHITE_CARPET.defaultBlockState());

        // Plants by the doorway.
        set(at(1, 1, SIZE - 1), Blocks.POTTED_FERN.defaultBlockState());
        set(at(SIZE - 1, 1, SIZE - 1), Blocks.POTTED_AZALEA.defaultBlockState());
    }

    /**
     * Framed Safari Board standing on the plaza, screen toward the front so
     * players walking up to the house read it. Frames are placed first, then
     * each one is given the connections, planks and feet the frame block
     * would compute itself when placed by hand.
     */
    private void board() {
        Direction screenFacing = facing;
        BlockState screen = SafariBlocks.safariBoard().defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, screenFacing);
        BlockState frame = SafariBlocks.safariBoardFrame().defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, screenFacing);
        List<BlockPos> frames = new ArrayList<>();
        for (int lx = BOARD_FIRST_X - 1; lx <= BOARD_LAST_X + 1; lx++) {
            for (int y = BOARD_BOTTOM_Y - 1; y <= BOARD_TOP_Y + 1; y++) {
                BlockPos pos = at(lx, y, BOARD_Z);
                boolean inside = lx >= BOARD_FIRST_X && lx <= BOARD_LAST_X && y >= BOARD_BOTTOM_Y && y <= BOARD_TOP_Y;
                if (inside) {
                    set(pos, screen);
                } else {
                    set(pos, frame);
                    frames.add(pos);
                }
            }
        }
        for (BlockPos pos : frames) {
            set(pos, SafariBoardFrameBlock.connect(level, pos, level.getBlockState(pos)));
        }
    }

    // ---- Cobblemon blocks ---------------------------------------------------

    /**
     * Places Cobblemon's Healing Machine and makes it infinite. A machine
     * placed by code starts empty and recharges slowly; in a timed park, a
     * player waiting for the charge is a player losing hunting time. The flag
     * is the same one Cobblemon sets for creative-placed machines.
     */
    private void placeHealingMachine(BlockPos pos) {
        BlockState state = cobblemonBlock(HEALING_MACHINE_ID);
        if (state == null) {
            return;
        }
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

    /**
     * Places Cobblemon's PC, both halves: Cobblemon adds the top half in
     * {@code setPlacedBy}, which a block placed by code never calls.
     *
     * @param placerLook direction a player placing it by hand would look in:
     *                   {@code PCBlock.getStateForPlacement} stores that, and
     *                   the screen faces the other way, toward the player.
     *                   Passing the screen direction (as a first version did)
     *                   turns the PC to the wall.
     */
    private void placePc(BlockPos pos, Direction placerLook) {
        BlockState bottom = cobblemonBlock(PC_ID);
        if (bottom == null) {
            return;
        }
        bottom = with(bottom, "facing", placerLook.getSerializedName());
        set(pos, with(bottom, "part", "bottom"));
        set(pos.above(), with(bottom, "part", "top"));
    }

    /**
     * Places Cobblemon's campfire with a Poké Pot (campfire pot) on it, ready
     * to cook. The pot is the block entity's item, as when a player puts one
     * on a campfire.
     *
     * @param placerLook direction a player placing it by hand would look in,
     *                   which {@code CampfireBlock.getStateForPlacement} stores
     *                   in both {@code facing} and {@code item_facing}
     */
    private void placePokePot(BlockPos pos, Direction placerLook) {
        BlockState campfire = cobblemonBlock(CAMPFIRE_ID);
        if (campfire == null) {
            return;
        }
        campfire = with(with(campfire, "facing", placerLook.getSerializedName()), "item_facing",
                placerLook.getSerializedName());
        set(pos, campfire);
        Item pot = BuiltInRegistries.ITEM.get(POT_ITEM_ID);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (pot == Items.AIR || !(blockEntity instanceof CampfireBlockEntity campfireEntity)) {
            CobblemonSafari.LOGGER.error("Could not put a {} on the rest house campfire", POT_ITEM_ID);
            return;
        }
        campfireEntity.setPotItem(new ItemStack(pot));
        campfireEntity.setChanged();
    }

    /** Default state of a Cobblemon block, or null (logged) when Cobblemon no longer has it. */
    private static BlockState cobblemonBlock(ResourceLocation id) {
        Block block = BuiltInRegistries.BLOCK.get(id);
        // The block registry is defaulted: a missing Cobblemon block yields air.
        if (block == Blocks.AIR) {
            CobblemonSafari.LOGGER.error("Block {} not found; rest house built without it", id);
            return null;
        }
        return block.defaultBlockState();
    }

    /**
     * A state with one property set by name and value name. Used for
     * Cobblemon's own properties (PC part, campfire facing), whose Kotlin
     * enum types are not worth binding to; unknown names leave the state as is.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState with(BlockState state, String name, String value) {
        Property property = state.getBlock().getStateDefinition().getProperty(name);
        if (property == null) {
            return state;
        }
        Optional<Comparable> parsed = property.getValue(value);
        return parsed.isPresent() ? state.setValue(property, parsed.get()) : state;
    }

    private static ResourceLocation cobblemon(String path) {
        return ResourceLocation.fromNamespaceAndPath("cobblemon", path);
    }

    // ---- Coordinates ----------------------------------------------------------

    /**
     * World direction of a local one: local SOUTH is the door side ({@link #facing}),
     * local EAST is {@link #localEast()}.
     */
    private Direction localDirection(Direction local) {
        return switch (local) {
            case SOUTH -> facing;
            case NORTH -> facing.getOpposite();
            case EAST -> localEast();
            case WEST -> localEast().getOpposite();
            default -> local;
        };
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
     * World position of a local cell. Local (HALF, HALF) is the centre column;
     * the rotation keeps handedness, so the layout is turned, never mirrored.
     */
    private BlockPos at(int lx, int y, int lz) {
        return rotate(centerX, centerZ, facing, lx, lz).atY(floorY + y);
    }

    /**
     * World column (at y 0) of a local cell of a house centred on
     * (centerX, centerZ) with its door toward {@code facing}. Static so that
     * code outside the build (the clerk's upkeep) finds the same cells.
     */
    static BlockPos rotate(int centerX, int centerZ, Direction facing, int lx, int lz) {
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
        return new BlockPos(centerX + wx, 0, centerZ + wz);
    }

    /** World column where the clerk stands, behind the counter; see {@link #CLERK_X}. */
    static BlockPos clerkColumn(int centerX, int centerZ, Direction facing) {
        return rotate(centerX, centerZ, facing, CLERK_X, CLERK_Z);
    }

    /** Places one block with the builder's update flags. */
    private void set(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, PLACE_FLAGS);
    }
}
