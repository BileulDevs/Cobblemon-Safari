package dev.darcosse.common.cobblemonsafari.structure;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.util.Surface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Builds one rest house: the Safari Pokémon Center, placed from the
 * structure template {@code data/cobblemon_safari/structure/safari_pokecenter.nbt}.
 *
 * <p>The template holds everything: the building, its ground layer, the
 * front steps, the counter with two Healing Machines (saved infinite), the
 * PC, the cooking corner with its Poké Pot, the TM shelves, the fossil
 * corner and the wall screens. This class only finds the floor height, fills
 * a foundation under the template, clears the volume above it (trees, hill)
 * and places it turned so that its door faces the requested direction.
 *
 * <p>Template coordinates ("local"): x 0 to 19, y 0 to 10, z 0 to 18. Layer
 * y 0 is the ground (dirt and grass), y 1 the floor. The door is on the
 * west side (local -X), in the middle (z 9), up three steps. Every position
 * this class gives out (clerk, arrival, site samples) goes through
 * {@link #column}, the same transform Minecraft applies to the template, so
 * they always match what was placed.
 */
final class RestHouseBuilder {

    /** The Pokémon Center template. Renaming the file means changing this id. */
    private static final ResourceLocation TEMPLATE_ID = CobblemonSafari.id("safari_pokecenter");

    /**
     * Template cell the house turns around, and which stands on the house's
     * centre column. The middle of the 20 × 19 template; RestHouses.HALF_SIZE
     * is the distance from it to the farthest wall.
     */
    private static final BlockPos PIVOT = new BlockPos(10, 0, 9);

    /** Direction the template's door faces as saved: west. Must follow the template if it is turned. */
    private static final Direction TEMPLATE_DOOR = Direction.WEST;

    /** Template size, as saved; checked against the file at build time, logged if it differs. */
    private static final int SIZE_X = 20;
    private static final int SIZE_Y = 11;
    private static final int SIZE_Z = 19;

    /** Template cell, on its floor, where the clerk stands: behind the counter, between the two Healing Machines. */
    static final int CLERK_X = 17;
    static final int CLERK_Z = 9;

    /**
     * Template column where arriving players stand: the grass in front of
     * the steps, on the door's axis. One block further in, the awning over
     * the entrance would be taken for the ground.
     */
    private static final int ARRIVAL_X = 0;
    private static final int ARRIVAL_Z = 9;

    /**
     * Air cleared above the template, on top of its own height: room for
     * the trunks and crowns of trees cut by the house. More costs block
     * changes at each build (once per map).
     */
    private static final int CLEAR_ABOVE = 4;

    /**
     * Ring cleared around the template, in blocks, so a hill or a tree is
     * not left touching the walls.
     */
    private static final int CLEAR_MARGIN = 1;

    /**
     * Blocks around the template also checked for water by
     * {@link #wetColumns}: the cleared ring plus one, so no pond touches the
     * cleared space and flows in once a block next to it is updated.
     */
    private static final int WATER_CHECK_MARGIN = CLEAR_MARGIN + 1;

    /**
     * Deepest the foundation goes. Over a deep lagoon it stops here and the
     * house stands on a pillar of this height; deeper would only waste blocks.
     */
    private static final int MAX_FOUNDATION_DEPTH = 24;

    /**
     * Only clients are told about the changes; neighbours are not updated.
     * Neighbour updates would make cut leaves decay and nearby water try to
     * flow into the freshly cleared space, which is exactly what is not wanted.
     */
    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS;

    /**
     * Cobblemon's fossil machine blocks. The template stores their
     * multiblock links as absolute positions of the world it was saved in;
     * placed anywhere else they point at unrelated blocks. They are placed
     * unlinked instead (see {@link TemplateFixer}).
     */
    private static final Set<ResourceLocation> FOSSIL_BLOCKS = Set.of(
            ResourceLocation.fromNamespaceAndPath("cobblemon", "fossil_analyzer"),
            ResourceLocation.fromNamespaceAndPath("cobblemon", "restoration_tank"),
            ResourceLocation.fromNamespaceAndPath("cobblemon", "monitor"));

    /** Block entity keys holding those links (Cobblemon's DataKeys values, as found in the template). */
    private static final String[] FOSSIL_LINK_KEYS = {"ControllerBlock", "MultiblockStore"};
    private static final String FOSSIL_FORMED_KEY = "Formed";

    private RestHouseBuilder() {
    }

    /**
     * Builds a rest house centred on the given column.
     *
     * @param facing direction the door opens toward
     * @return false (logged) when the template is missing, nothing being built
     */
    static boolean build(ServerLevel level, int centerX, int centerZ, Direction facing) {
        Optional<StructureTemplate> found = level.getStructureManager().get(TEMPLATE_ID);
        if (found.isEmpty()) {
            CobblemonSafari.LOGGER.error("Structure template {} is missing; no rest house built at {}, {}",
                    TEMPLATE_ID, centerX, centerZ);
            return false;
        }
        StructureTemplate template = found.get();
        if (template.getSize().getX() != SIZE_X || template.getSize().getY() != SIZE_Y
                || template.getSize().getZ() != SIZE_Z) {
            // Positions of the clerk and the arrival are written for this size.
            CobblemonSafari.LOGGER.warn("Structure template {} is {} instead of {}x{}x{}; check RestHouseBuilder",
                    TEMPLATE_ID, template.getSize(), SIZE_X, SIZE_Y, SIZE_Z);
        }
        Rotation rotation = rotationFor(facing);
        // Template y 0 is ground: it goes one below the floor, where the old ground was.
        int groundY = findFloorY(level, centerX, centerZ, rotation) - 1;
        BlockPos origin = new BlockPos(centerX - PIVOT.getX(), groundY, centerZ - PIVOT.getZ());
        foundation(level, origin, rotation);
        clear(level, origin, rotation);
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(rotation)
                .setRotationPivot(PIVOT)
                .setIgnoreEntities(true)
                .addProcessor(TemplateFixer.INSTANCE);
        template.placeInWorld(level, origin, origin, settings, level.getRandom(), PLACE_FLAGS);
        return true;
    }

    // ---- Coordinates ----------------------------------------------------------

    /**
     * World column (at y 0) of a template cell of a house centred on
     * (centerX, centerZ) with its door toward {@code facing}: exactly where
     * {@link #build} places that cell.
     */
    static BlockPos column(int centerX, int centerZ, Direction facing, int lx, int lz) {
        return world(centerX, centerZ, rotationFor(facing), lx, lz);
    }

    /** World column where the clerk stands, behind the counter; see {@link #CLERK_X}. */
    static BlockPos clerkColumn(int centerX, int centerZ, Direction facing) {
        return column(centerX, centerZ, facing, CLERK_X, CLERK_Z);
    }

    /** World column where arriving players stand, in front of the steps; see {@link #ARRIVAL_X}. */
    static BlockPos arrivalColumn(int centerX, int centerZ, Direction facing) {
        return column(centerX, centerZ, facing, ARRIVAL_X, ARRIVAL_Z);
    }

    /** The rotation that turns the template's door ({@link #TEMPLATE_DOOR}) toward {@code facing}. */
    private static Rotation rotationFor(Direction facing) {
        for (Rotation rotation : Rotation.values()) {
            if (rotation.rotate(TEMPLATE_DOOR) == facing) {
                return rotation;
            }
        }
        return Rotation.NONE;
    }

    // ---- Ground ----------------------------------------------------------

    /**
     * Columns, under the template and within {@link #WATER_CHECK_MARGIN} of
     * it, whose top block is water or waterlogged, read from the real
     * terrain (the chunks are generated if needed). 0 means the house can be
     * built there without standing in water or having water flow into the
     * cleared ring.
     */
    static int wetColumns(ServerLevel level, int centerX, int centerZ, Direction facing) {
        Rotation rotation = rotationFor(facing);
        int wet = 0;
        for (int lx = -WATER_CHECK_MARGIN; lx < SIZE_X + WATER_CHECK_MARGIN; lx++) {
            for (int lz = -WATER_CHECK_MARGIN; lz < SIZE_Z + WATER_CHECK_MARGIN; lz++) {
                BlockPos column = world(centerX, centerZ, rotation, lx, lz);
                LevelChunk chunk = level.getChunk(SectionPos.blockToSectionCoord(column.getX()),
                        SectionPos.blockToSectionCoord(column.getZ()));
                // WORLD_SURFACE counts water (unlike the ground heightmaps): its top block is the water itself.
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, column.getX() & 15, column.getZ() & 15);
                if (!chunk.getFluidState(column.atY(top)).isEmpty()) {
                    wet++;
                }
            }
        }
        return wet;
    }

    /**
     * Median of the ground height over nine points of the footprint. The
     * median, not the mean: one tree stump or one river cell must not lift or
     * sink the whole house. The floor replaces the top ground block.
     */
    private static int findFloorY(ServerLevel level, int centerX, int centerZ, Rotation rotation) {
        int[] heights = new int[9];
        int i = 0;
        for (int lx : new int[]{0, SIZE_X / 2, SIZE_X - 1}) {
            for (int lz : new int[]{0, SIZE_Z / 2, SIZE_Z - 1}) {
                BlockPos column = world(centerX, centerZ, rotation, lx, lz);
                heights[i++] = Surface.forced(level, column.getX(), column.getZ()).getY() - 1;
            }
        }
        Arrays.sort(heights);
        int median = heights[heights.length / 2];
        int ceiling = level.getMaxBuildHeight() - SIZE_Y - CLEAR_ABOVE - 1;
        return Math.max(level.getMinBuildHeight() + MAX_FOUNDATION_DEPTH, Math.min(ceiling, median));
    }

    /** World column of a template cell, for a rotation already chosen; see {@link #column}. */
    private static BlockPos world(int centerX, int centerZ, Rotation rotation, int lx, int lz) {
        BlockPos relative = StructureTemplate.transform(new BlockPos(lx, 0, lz), Mirror.NONE, rotation, PIVOT);
        return new BlockPos(centerX - PIVOT.getX() + relative.getX(), 0, centerZ - PIVOT.getZ() + relative.getZ());
    }

    /**
     * Fills with dirt from just below the template's ground layer down to
     * solid ground, under the whole template: on a slope or over a hollow
     * the house would otherwise stand on air.
     */
    private static void foundation(ServerLevel level, BlockPos origin, Rotation rotation) {
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        for (int lx = 0; lx < SIZE_X; lx++) {
            for (int lz = 0; lz < SIZE_Z; lz++) {
                BlockPos column = local(origin, rotation, lx, lz);
                for (int depth = 1; depth <= MAX_FOUNDATION_DEPTH; depth++) {
                    BlockPos pos = column.atY(origin.getY() - depth);
                    BlockState existing = level.getBlockState(pos);
                    if (existing.getFluidState().isEmpty() && existing.isFaceSturdy(level, pos, Direction.UP)) {
                        break;
                    }
                    level.setBlock(pos, dirt, PLACE_FLAGS);
                }
            }
        }
    }

    /**
     * Empties the volume above the template's ground layer, a ring of
     * {@link #CLEAR_MARGIN} around it and {@link #CLEAR_ABOVE} blocks above
     * it. The template then places its own blocks and air inside.
     */
    private static void clear(ServerLevel level, BlockPos origin, Rotation rotation) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int lx = -CLEAR_MARGIN; lx < SIZE_X + CLEAR_MARGIN; lx++) {
            for (int lz = -CLEAR_MARGIN; lz < SIZE_Z + CLEAR_MARGIN; lz++) {
                BlockPos column = local(origin, rotation, lx, lz);
                for (int y = 1; y < SIZE_Y + CLEAR_ABOVE; y++) {
                    level.setBlock(column.atY(origin.getY() + y), air, PLACE_FLAGS);
                }
            }
        }
    }

    /** World column of a template cell, from the template's placement origin. */
    private static BlockPos local(BlockPos origin, Rotation rotation, int lx, int lz) {
        return world(origin.getX() + PIVOT.getX(), origin.getZ() + PIVOT.getZ(), rotation, lx, lz);
    }

    // ---- Placement ----------------------------------------------------------

    /**
     * Fixes, as each template block is placed, what Minecraft's template
     * rotation gets wrong for Cobblemon's blocks. Everything else passes
     * through unchanged.
     * <ul>
     *   <li><b>Orientation.</b> Minecraft turns a block by asking the block
     *       ({@code Block.rotate}); a block that does not answer keeps its
     *       saved facing. Cobblemon's disc shelf, TM machine and fossil
     *       machine do not (checked in the 1.8.0 sources), so in a turned
     *       house they faced the walls. For such blocks (rotation a no-op,
     *       yet a horizontal direction property), every horizontal direction
     *       property is turned here instead. Minecraft applies its own
     *       rotation after this processor, which leaves them as set.</li>
     *   <li><b>Fossil machine links</b> (see {@link #FOSSIL_BLOCKS}): dropped,
     *       so the blocks stand as an unformed machine, a decoration, instead
     *       of pointing at positions of another world. Without them
     *       Cobblemon's {@code FossilMultiblockEntity} has neither structure
     *       nor master (checked in its {@code loadAdditional}).</li>
     * </ul>
     *
     * <p>Used only in code, never read from a file: its type is therefore
     * never serialised, and NOP stands in for a registered one.
     */
    private static final class TemplateFixer extends StructureProcessor {

        static final TemplateFixer INSTANCE = new TemplateFixer();

        @Nullable
        @Override
        public StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pivot,
                                                                 StructureTemplate.StructureBlockInfo original,
                                                                 StructureTemplate.StructureBlockInfo placed,
                                                                 StructurePlaceSettings settings) {
            BlockState state = turnIfIgnored(placed.state(), settings.getRotation());
            CompoundTag nbt = placed.nbt();
            if (nbt != null && FOSSIL_BLOCKS.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()))) {
                nbt = nbt.copy();
                for (String key : FOSSIL_LINK_KEYS) {
                    nbt.remove(key);
                }
                nbt.putBoolean(FOSSIL_FORMED_KEY, false);
            }
            if (state == placed.state() && nbt == placed.nbt()) {
                return placed;
            }
            return new StructureTemplate.StructureBlockInfo(placed.pos(), state, nbt);
        }

        /**
         * The state with its horizontal direction properties turned, when the
         * block itself ignores rotation; the state unchanged otherwise.
         */
        @SuppressWarnings("unchecked")
        private static BlockState turnIfIgnored(BlockState state, Rotation rotation) {
            if (rotation == Rotation.NONE || state.rotate(rotation) != state) {
                return state;
            }
            BlockState turned = state;
            for (Property<?> property : state.getProperties()) {
                if (property.getValueClass() != Direction.class) {
                    continue;
                }
                Property<Direction> directionProperty = (Property<Direction>) property;
                Direction value = state.getValue(directionProperty);
                Direction rotated = rotation.rotate(value);
                // Up and down stay; a property that cannot hold the turned value is left alone.
                if (value.getAxis().isHorizontal() && directionProperty.getPossibleValues().contains(rotated)) {
                    turned = turned.setValue(directionProperty, rotated);
                }
            }
            return turned;
        }

        @Override
        protected StructureProcessorType<?> getType() {
            return StructureProcessorType.NOP;
        }
    }
}
