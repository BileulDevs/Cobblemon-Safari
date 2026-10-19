package dev.darcosse.common.cobblemonsafari.structure;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.gate.GateClearing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds one rest house: the Safari Pokémon Center, placed from the
 * structure template {@code data/cobblemon_safari/structure/safari_pokecenter.nbt}.
 *
 * <p>The template holds everything: the building, its ground layer, the
 * front steps, the counter with two Healing Machines (saved infinite), the
 * PC, the cooking corner with its Poké Pot, the TM shelves, the fossil
 * corner and the wall screens. This class fits it to the terrain:
 * <ul>
 *   <li>the template's ground layer is set level with the terrain (median
 *       of the ground under it, trees and plants ignored), on a foundation;</li>
 *   <li>the terrain around it is reshaped over {@link #SKIRT} blocks: flat
 *       next to the house, then sloping back to the natural ground, so the
 *       house stands on a plaza instead of in a trench or on a step;</li>
 *   <li>its grass and dirt take the ground of the biome (sand in the
 *       dunes, snow-covered grass in the snowfield, stone on the peaks...);</li>
 *   <li>the trees it cut are removed, as around the Safari Gates.</li>
 * </ul>
 *
 * <p>Building is a {@link Job} done in steps, one per tick, so that a house
 * built while players are around costs a few small ticks rather than one
 * long one.
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
     * Blocks around the template also checked for water: the cleared ring
     * plus one, so no pond touches the cleared space and flows in once a
     * block next to it is updated.
     */
    private static final int WATER_CHECK_MARGIN = CLEAR_MARGIN + 1;

    /**
     * Deepest the foundation goes. Over a deep lagoon it stops here and the
     * house stands on a pillar of this height; deeper would only waste blocks.
     */
    private static final int MAX_FOUNDATION_DEPTH = 24;

    /**
     * Width, in blocks beyond the template, of the terrain reshaped around
     * it. Wider gives gentler slopes on rough ground but touches more
     * chunks (all must be loaded to build) and more blocks. With the 10
     * blocks of relief the site search tolerates, 10 keeps slopes near one
     * block per block.
     */
    static final int SKIRT = 10;

    /**
     * Part of the skirt kept exactly level with the house's ground, in
     * blocks: a plaza around it. 0 starts the slope at the walls.
     */
    private static final int FLAT_RING = 2;

    /**
     * Skirt columns reshaped per tick. The skirt has about 1,200; each costs
     * a few block changes. Higher finishes in fewer ticks, each longer.
     */
    private static final int SKIRT_COLUMNS_PER_TICK = 400;

    /**
     * How far down from the top of a column the ground search goes through
     * trunks, leaves and plants. Taller than the tallest Safari trees
     * (jungle giants, about 30 blocks); a column with nothing solid within
     * it is treated as having its ground at the top.
     */
    private static final int MAX_GROUND_DESCENT = 48;

    /**
     * Blocks above a reshaped column checked for plants left floating:
     * tall grass and large flowers are two blocks high, a snow layer may sit
     * on top. More only reads more.
     */
    private static final int LOOSE_PLANT_HEIGHT = 3;

    /** Marker of a water column in a {@link Survey}. */
    private static final int WATER = Integer.MIN_VALUE;

    /** Snow layer put back on reshaped ground and on the house's grass in snowy biomes. */
    private static final BlockState SNOW_LAYER = Blocks.SNOW.defaultBlockState();

    /**
     * Only clients are told about the changes; neighbours are neither
     * updated nor reshaped. Neighbour updates would make cut leaves decay and
     * nearby water flow into the freshly cleared space. Reshaping
     * (UPDATE_KNOWN_SHAPE left out) would break every flower, petal or tuft
     * of grass that lost its support, and Minecraft forces the drop on those
     * neighbours whatever the flags say (Level.setBlock clears
     * UPDATE_SUPPRESS_DROPS for them): items would be left all over each
     * site. Whatever loses its support is removed by the build itself
     * instead.
     */
    private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /**
     * How far a tree whose crown reached into the cleared volume is
     * followed, in leaves, to find its trunk: vanilla's leaf reach
     * (LeavesBlock distance), so every leaf finds the log that keeps it.
     */
    private static final int LEAF_REACH = 6;

    /**
     * Most logs felled for one house. A dense forest's trees never share
     * logs, so a group this big is something else (a builder's work):
     * left alone.
     */
    private static final int MAX_FELLED_LOGS = 4096;

    /**
     * Items younger than the build and lying within this many blocks of
     * the reshaped area are removed once the build is done, in case
     * anything still dropped (a block of another mod breaking in its own
     * way). Items a player dropped before the build started are older and
     * stay.
     */
    private static final int SWEEP_MARGIN = 8;

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

    // ---- Area ----------------------------------------------------------

    /**
     * Columns a house centred on (centerX, centerZ) covers: the template,
     * turned, as {minX, minZ, maxX, maxZ}.
     */
    private static int[] templateRect(int centerX, int centerZ, Rotation rotation) {
        BlockPos a = world(centerX, centerZ, rotation, 0, 0);
        BlockPos b = world(centerX, centerZ, rotation, SIZE_X - 1, SIZE_Z - 1);
        return new int[]{Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ())};
    }

    /**
     * Whether every chunk a build of this house changes is loaded: the
     * template and its skirt. Cheap (one lookup per chunk, about 16); never
     * loads anything.
     */
    static boolean areaLoaded(ServerLevel level, int centerX, int centerZ, Direction facing) {
        int[] rect = templateRect(centerX, centerZ, rotationFor(facing));
        return level.hasChunksAt(rect[0] - SKIRT, rect[1] - SKIRT, rect[2] + SKIRT, rect[3] + SKIRT);
    }

    /**
     * Loads, generating them if needed, every chunk a build of this house
     * changes. Slow (up to about 16 chunks generated on the server thread):
     * only for the entrance house, which must stand before anyone arrives.
     */
    static void loadArea(ServerLevel level, int centerX, int centerZ, Direction facing) {
        int[] rect = templateRect(centerX, centerZ, rotationFor(facing));
        for (int cx = SectionPos.blockToSectionCoord(rect[0] - SKIRT);
             cx <= SectionPos.blockToSectionCoord(rect[2] + SKIRT); cx++) {
            for (int cz = SectionPos.blockToSectionCoord(rect[1] - SKIRT);
                 cz <= SectionPos.blockToSectionCoord(rect[3] + SKIRT); cz++) {
                level.getChunk(cx, cz);
            }
        }
    }

    // ---- Coordinates ----------------------------------------------------------

    /**
     * World column (at y 0) of a template cell of a house centred on
     * (centerX, centerZ) with its door toward {@code facing}: exactly where
     * a build places that cell.
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

    /** World column of a template cell, for a rotation already chosen; see {@link #column}. */
    private static BlockPos world(int centerX, int centerZ, Rotation rotation, int lx, int lz) {
        BlockPos relative = StructureTemplate.transform(new BlockPos(lx, 0, lz), Mirror.NONE, rotation, PIVOT);
        return new BlockPos(centerX - PIVOT.getX() + relative.getX(), 0, centerZ - PIVOT.getZ() + relative.getZ());
    }

    /** World column of a template cell, from the template's placement origin. */
    private static BlockPos local(BlockPos origin, Rotation rotation, int lx, int lz) {
        return world(origin.getX() + PIVOT.getX(), origin.getZ() + PIVOT.getZ(), rotation, lx, lz);
    }

    // ---- Ground ----------------------------------------------------------

    /**
     * Columns, under the template and within {@link #WATER_CHECK_MARGIN} of
     * it, whose top block is water or waterlogged, read from the real
     * terrain (the chunks are generated if needed). 0 means the house can be
     * built there without standing in water or having water flow into the
     * cleared ring. Used to pick the entrance house's site at server start.
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
     * Whether a block belongs to a tree or a huge mushroom rather than to
     * the ground: looked through when searching for the ground, so a trunk
     * does not count as a hill.
     */
    private static boolean isTreePart(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(Blocks.MUSHROOM_STEM)
                || state.is(Blocks.RED_MUSHROOM_BLOCK) || state.is(Blocks.BROWN_MUSHROOM_BLOCK)
                || state.is(Blocks.BEE_NEST);
    }

    /**
     * Height of the ground block of a loaded column, looking through trees
     * and anything without collision (grass, flowers, snow layers), or
     * {@link #WATER} when water lies on it.
     */
    private static int groundTop(ServerLevel level, BlockPos.MutableBlockPos cursor, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        int bottom = Math.max(level.getMinBuildHeight(), top - MAX_GROUND_DESCENT);
        for (int y = top; y > bottom; y--) {
            BlockState state = level.getBlockState(cursor.set(x, y, z));
            if (isTreePart(state)) {
                continue;
            }
            if (!state.getFluidState().isEmpty()) {
                return WATER;
            }
            if (state.getCollisionShape(level, cursor).isEmpty()) {
                continue;
            }
            return y;
        }
        return top;
    }

    /**
     * Whether a block can stand for the ground of a whole house: a full,
     * plain block (no chest of a ruin, no slab of a path).
     */
    private static boolean isGroundMaterial(ServerLevel level, BlockPos pos, BlockState state) {
        return !state.isAir() && !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && state.isCollisionShapeFullBlock(level, pos);
    }

    /** The state without its snowy look (grass, podzol, mycelium); unchanged for other blocks. */
    private static BlockState withoutSnow(BlockState state) {
        return state.hasProperty(BlockStateProperties.SNOWY) ? state.setValue(BlockStateProperties.SNOWY, false) : state;
    }

    /** The most frequent key of a tally, or the fallback when it is empty. */
    private static BlockState mostFrequent(Map<BlockState, Integer> tally, BlockState fallback) {
        if (tally.isEmpty()) {
            return fallback;
        }
        return Collections.max(tally.entrySet(), Map.Entry.comparingByValue()).getKey();
    }

    /**
     * What the terrain under and around a house looked like before the
     * build, read once: the ground height of every column of the template
     * and its skirt, its top and second blocks, and whether snow lay on it.
     * The house's height and ground blocks are derived from it.
     */
    private static final class Survey {
        /** First column of the surveyed area (template plus skirt) and its size. */
        final int minX;
        final int minZ;
        final int sizeX;
        final int sizeZ;
        /** Ground height per column, or {@link #WATER}. */
        final int[] ground;
        /** Height of the water's surface, for water columns. */
        final int[] waterTop;
        final BlockState[] top;
        final BlockState[] sub;
        final boolean[] snow;
        /** Water columns under the template and within {@link #WATER_CHECK_MARGIN} of it. */
        int wetColumns;
        /** Height of the template's ground layer: the median ground under the template. */
        int groundY;
        /** Ground blocks of the biome, for the template's grass and dirt and for the foundation. */
        BlockState groundTop;
        BlockState groundSub;
        /** Whether most of the ground under the template had snow on it. */
        boolean snowy;

        Survey(int[] rect) {
            this.minX = rect[0] - SKIRT;
            this.minZ = rect[1] - SKIRT;
            this.sizeX = rect[2] - rect[0] + 1 + 2 * SKIRT;
            this.sizeZ = rect[3] - rect[1] + 1 + 2 * SKIRT;
            int count = sizeX * sizeZ;
            this.ground = new int[count];
            this.waterTop = new int[count];
            this.top = new BlockState[count];
            this.sub = new BlockState[count];
            this.snow = new boolean[count];
        }

        /** Index of a world column in the survey's arrays. */
        int index(int x, int z) {
            return (x - minX) + (z - minZ) * sizeX;
        }

        /** Whether a world column lies in the surveyed area. */
        boolean contains(int x, int z) {
            return x >= minX && x < minX + sizeX && z >= minZ && z < minZ + sizeZ;
        }

        /**
         * Reads the area. Cost: every column of the template and skirt
         * (about 1,600), a few block reads each, more under trees.
         */
        static Survey read(ServerLevel level, int[] rect) {
            Survey survey = new Survey(rect);
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            List<Integer> heights = new ArrayList<>();
            Map<BlockState, Integer> tops = new HashMap<>();
            Map<BlockState, Integer> subs = new HashMap<>();
            int snowyColumns = 0;
            int counted = 0;
            for (int x = survey.minX; x < survey.minX + survey.sizeX; x++) {
                for (int z = survey.minZ; z < survey.minZ + survey.sizeZ; z++) {
                    int i = survey.index(x, z);
                    int y = groundTop(level, cursor, x, z);
                    boolean underTemplate = x >= rect[0] && x <= rect[2] && z >= rect[1] && z <= rect[3];
                    boolean nearTemplate = x >= rect[0] - WATER_CHECK_MARGIN && x <= rect[2] + WATER_CHECK_MARGIN
                            && z >= rect[1] - WATER_CHECK_MARGIN && z <= rect[3] + WATER_CHECK_MARGIN;
                    survey.ground[i] = y;
                    if (y == WATER) {
                        survey.waterTop[i] = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                        if (nearTemplate) {
                            survey.wetColumns++;
                        }
                        continue;
                    }
                    BlockState top = level.getBlockState(cursor.set(x, y, z));
                    BlockState sub = level.getBlockState(cursor.set(x, y - 1, z));
                    survey.top[i] = top;
                    survey.sub[i] = isGroundMaterial(level, cursor, sub) ? sub : top;
                    survey.snow[i] = level.getBlockState(cursor.set(x, y + 1, z)).is(Blocks.SNOW);
                    if (!underTemplate) {
                        continue;
                    }
                    heights.add(y);
                    counted++;
                    if (survey.snow[i]) {
                        snowyColumns++;
                    }
                    if (isGroundMaterial(level, cursor.set(x, y, z), top)) {
                        tops.merge(withoutSnow(top), 1, Integer::sum);
                    }
                    if (isGroundMaterial(level, cursor.set(x, y - 1, z), sub)) {
                        subs.merge(withoutSnow(sub), 1, Integer::sum);
                    }
                }
            }
            if (heights.isEmpty()) {
                // All water (the entrance's last resort): stand at sea level.
                heights.add(level.getSeaLevel());
            }
            Collections.sort(heights);
            // The median, not the mean: one stump or one ditch must not lift or sink the whole house.
            int median = heights.get(heights.size() / 2);
            int ceiling = level.getMaxBuildHeight() - SIZE_Y - CLEAR_ABOVE - 1;
            survey.groundY = Math.max(level.getMinBuildHeight() + MAX_FOUNDATION_DEPTH, Math.min(ceiling, median));
            survey.groundTop = mostFrequent(tops, Blocks.GRASS_BLOCK.defaultBlockState());
            survey.groundSub = mostFrequent(subs, Blocks.DIRT.defaultBlockState());
            survey.snowy = counted > 0 && snowyColumns * 2 > counted;
            return survey;
        }
    }

    // ---- Job ----------------------------------------------------------

    /** What a {@link Job#step} did, and what the caller should do next. */
    enum Progress {
        /** More to do: call again next tick. */
        RUNNING,
        /** Part of the area is not loaded: nothing was changed this time; call again later. */
        WAITING,
        /** The real terrain has water at this site: nothing was built; try another site. */
        WET,
        /** The house now stands (clerk and players may use it); call again to remove the cut trees. */
        PLACED,
        /** Finished. */
        DONE
    }

    /**
     * The build of one house, in steps: survey of the terrain, felling of
     * the trees in the way, foundation and clearing, terrain blending (a
     * few ticks), placement of the template, then removal of the trees cut
     * by the reshaping and of any dropped item (a few ticks more). Every step
     * first checks that the area is still loaded, since changing a block of
     * an unloaded chunk would load it on the server thread.
     */
    static final class Job {

        /** Steps of a build, in order; see {@link #step}. */
        private enum Stage { SURVEY, FELL, GROUND, SKIRT, PLACE, TREES }

        final int centerX;
        final int centerZ;
        final Direction facing;
        private final Rotation rotation;
        private final StructureTemplate template;
        /** Whether to build even with water at the site (the last site left). */
        private final boolean acceptWater;
        private final int[] rect;
        private Stage stage = Stage.SURVEY;
        private Survey survey;
        /** Next skirt column to reshape, as an index into the survey. */
        private int nextSkirtColumn;
        @Nullable
        private GateClearing clearing;
        /** Game time the build started at; items younger than the build are its own drops. */
        private long startTime = Long.MIN_VALUE;

        /** A build not started yet; see {@link #start}. */
        private Job(int centerX, int centerZ, Direction facing, StructureTemplate template, boolean acceptWater) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.facing = facing;
            this.rotation = rotationFor(facing);
            this.template = template;
            this.acceptWater = acceptWater;
            this.rect = templateRect(centerX, centerZ, rotation);
        }

        /**
         * A build of a house centred on the given column, nothing done yet;
         * null (logged) when the template is missing.
         *
         * @param facing      direction the door opens toward
         * @param acceptWater build even if the survey finds water at the site
         */
        @Nullable
        static Job start(ServerLevel level, int centerX, int centerZ, Direction facing, boolean acceptWater) {
            Optional<StructureTemplate> found = level.getStructureManager().get(TEMPLATE_ID);
            if (found.isEmpty()) {
                CobblemonSafari.LOGGER.error("Structure template {} is missing; no rest house built at {}, {}",
                        TEMPLATE_ID, centerX, centerZ);
                return null;
            }
            StructureTemplate template = found.get();
            if (template.getSize().getX() != SIZE_X || template.getSize().getY() != SIZE_Y
                    || template.getSize().getZ() != SIZE_Z) {
                // Positions of the clerk and the arrival are written for this size.
                CobblemonSafari.LOGGER.warn("Structure template {} is {} instead of {}x{}x{}; check RestHouseBuilder",
                        TEMPLATE_ID, template.getSize(), SIZE_X, SIZE_Y, SIZE_Z);
            }
            return new Job(centerX, centerZ, facing, template, acceptWater);
        }

        /**
         * Does the next step. Cost per call, at most: the survey (about
         * 1,600 columns read), or the foundation and clearing (about 7,000
         * block changes), or {@link #SKIRT_COLUMNS_PER_TICK} skirt columns,
         * or the template (about 4,000 blocks), or one tick of tree removal
         * (GateClearing's budget).
         */
        Progress step(ServerLevel level) {
            if (stage == Stage.TREES) {
                return stepTrees(level);
            }
            if (!areaLoaded(level, centerX, centerZ, facing)) {
                return Progress.WAITING;
            }
            switch (stage) {
                case SURVEY -> {
                    startTime = level.getGameTime();
                    survey = Survey.read(level, rect);
                    if (survey.wetColumns > 0 && !acceptWater) {
                        CobblemonSafari.LOGGER.info("Rest house spot {}, {} has water on {} columns; trying the next one",
                                centerX, centerZ, survey.wetColumns);
                        return Progress.WET;
                    }
                    if (survey.wetColumns > 0) {
                        CobblemonSafari.LOGGER.warn("Building the rest house at {}, {} despite water on {} columns: no drier spot",
                                centerX, centerZ, survey.wetColumns);
                    }
                    stage = Stage.FELL;
                    return Progress.RUNNING;
                }
                case FELL -> {
                    fellCutTrees(level);
                    stage = Stage.GROUND;
                    return Progress.RUNNING;
                }
                case GROUND -> {
                    BlockPos origin = origin();
                    foundation(level, origin);
                    clear(level, origin);
                    stage = Stage.SKIRT;
                    return Progress.RUNNING;
                }
                case SKIRT -> {
                    if (reshapeSkirt(level)) {
                        stage = Stage.PLACE;
                    }
                    return Progress.RUNNING;
                }
                case PLACE -> {
                    place(level);
                    stage = Stage.TREES;
                    return Progress.PLACED;
                }
                default -> {
                    return Progress.RUNNING;
                }
            }
        }

        /**
         * Runs every step up to the placement at once, the area being
         * loaded ({@link #loadArea}). For the entrance house at server start.
         *
         * @return false when the build could not go that far (water refused, area not loaded)
         */
        boolean runUntilPlaced(ServerLevel level) {
            while (true) {
                Progress progress = step(level);
                if (progress == Progress.PLACED) {
                    return true;
                }
                if (progress != Progress.RUNNING) {
                    return false;
                }
            }
        }

        /** Where the template's corner goes: its ground layer at the surveyed height. */
        private BlockPos origin() {
            return new BlockPos(centerX - PIVOT.getX(), survey.groundY, centerZ - PIVOT.getZ());
        }

        /**
         * Fills with the biome's second ground block from just below the
         * template's ground layer down to solid ground, under the whole
         * template: on a slope or over a hollow the house would otherwise
         * stand on air.
         */
        private void foundation(ServerLevel level, BlockPos origin) {
            for (int lx = 0; lx < SIZE_X; lx++) {
                for (int lz = 0; lz < SIZE_Z; lz++) {
                    BlockPos column = local(origin, rotation, lx, lz);
                    for (int depth = 1; depth <= MAX_FOUNDATION_DEPTH; depth++) {
                        BlockPos pos = column.atY(origin.getY() - depth);
                        BlockState existing = level.getBlockState(pos);
                        if (existing.getFluidState().isEmpty() && existing.isFaceSturdy(level, pos, Direction.UP)) {
                            break;
                        }
                        level.setBlock(pos, survey.groundSub, PLACE_FLAGS);
                    }
                }
            }
        }

        /**
         * Empties the volume above the template's ground layer, a ring of
         * {@link #CLEAR_MARGIN} around it and {@link #CLEAR_ABOVE} blocks above
         * it. The template then places its own blocks and air inside.
         */
        private void clear(ServerLevel level, BlockPos origin) {
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

        /**
         * Reshapes the next skirt columns: each is brought to a height
         * between the house's ground (next to it) and its own natural ground
         * (at the skirt's edge), cut down or filled up with its own top and
         * second blocks, so the slope keeps the look of the place. Corners
         * are rounded (distance to the template, not to its box), water is
         * left alone, and a column next to water is never cut below it.
         *
         * @return true once every skirt column is done
         */
        private boolean reshapeSkirt(ServerLevel level) {
            int total = survey.sizeX * survey.sizeZ;
            int done = 0;
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            while (nextSkirtColumn < total && done < SKIRT_COLUMNS_PER_TICK) {
                int i = nextSkirtColumn++;
                int x = survey.minX + i % survey.sizeX;
                int z = survey.minZ + i / survey.sizeX;
                int dx = Math.max(Math.max(rect[0] - x, x - rect[2]), 0);
                int dz = Math.max(Math.max(rect[1] - z, z - rect[3]), 0);
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance == 0 || distance > SKIRT || survey.ground[i] == WATER) {
                    continue;
                }
                done++;
                reshapeColumn(level, cursor, i, x, z, distance);
            }
            return nextSkirtColumn >= total;
        }

        /** Brings one skirt column to its target height; see {@link #reshapeSkirt}. */
        private void reshapeColumn(ServerLevel level, BlockPos.MutableBlockPos cursor, int i, int x, int z,
                                   double distance) {
            int natural = survey.ground[i];
            double weight = distance <= FLAT_RING ? 0.0
                    : Mth.smoothstep((distance - FLAT_RING) / (SKIRT - FLAT_RING));
            int target = (int) Math.round(survey.groundY + (natural - survey.groundY) * weight);
            if (target < natural) {
                // Cut, but never below a neighbouring pond: it would flow in at the next block update.
                target = Math.max(target, waterFloor(x, z));
            }
            if (target == natural) {
                return;
            }
            BlockState top = survey.top[i];
            if (target < natural) {
                for (int y = target + 1; y <= natural; y++) {
                    level.setBlock(cursor.set(x, y, z), Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
                }
            } else {
                for (int y = natural + 1; y < target; y++) {
                    level.setBlock(cursor.set(x, y, z), survey.sub[i], PLACE_FLAGS);
                }
            }
            level.setBlock(cursor.set(x, target, z), top, PLACE_FLAGS);
            // A cut leaves the old plants above the old ground; a fill buried
            // their lower half, its upper half sticks out above the new ground.
            clearLoosePlants(level, cursor, x, z, (target < natural ? natural : target) + 1);
            if (survey.snow[i]) {
                level.setBlock(cursor.set(x, target + 1, z), SNOW_LAYER, PLACE_FLAGS);
            }
        }

        /**
         * Lowest height a column may be cut to: one above the surface of the
         * water in any of its four neighbours, or no limit.
         */
        private int waterFloor(int x, int z) {
            int floor = Integer.MIN_VALUE;
            int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] side : sides) {
                int nx = x + side[0];
                int nz = z + side[1];
                if (survey.contains(nx, nz) && survey.ground[survey.index(nx, nz)] == WATER) {
                    floor = Math.max(floor, survey.waterTop[survey.index(nx, nz)]);
                }
            }
            return floor;
        }

        /**
         * Removes what was growing on the old ground and would now float
         * or be buried halfway: plants and snow layers (blocks without
         * collision) from {@code fromY} up, a few blocks at most. Trees are
         * left to the tree removal, which knows which ones were cut.
         */
        private static void clearLoosePlants(ServerLevel level, BlockPos.MutableBlockPos cursor, int x, int z,
                                             int fromY) {
            for (int y = fromY; y < fromY + LOOSE_PLANT_HEIGHT; y++) {
                BlockState state = level.getBlockState(cursor.set(x, y, z));
                if (state.isAir() || isTreePart(state) || !state.getFluidState().isEmpty()
                        || !state.getCollisionShape(level, cursor).isEmpty()) {
                    return;
                }
                level.setBlock(cursor, Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
            }
        }

        /**
         * Places the template, its ground in the biome's blocks, and covers
         * its open ground with snow where the ground had some.
         */
        private void place(ServerLevel level) {
            BlockPos origin = origin();
            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setRotation(rotation)
                    .setRotationPivot(PIVOT)
                    .setIgnoreEntities(true)
                    // No shape pass at the template's edge: it would break and drop the plants around it.
                    .setKnownShape(true)
                    .addProcessor(new TemplateFixer(survey.groundY, survey.groundTop, survey.groundSub));
            template.placeInWorld(level, origin, origin, settings, level.getRandom(), PLACE_FLAGS);
            if (!survey.snowy) {
                return;
            }
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int x = rect[0]; x <= rect[2]; x++) {
                for (int z = rect[1]; z <= rect[3]; z++) {
                    BlockState ground = level.getBlockState(cursor.set(x, survey.groundY, z));
                    if (!ground.is(survey.groundTop.getBlock())
                            || !level.getBlockState(cursor.set(x, survey.groundY + 1, z)).isAir()) {
                        continue;
                    }
                    level.setBlock(cursor, SNOW_LAYER, PLACE_FLAGS);
                    if (ground.hasProperty(BlockStateProperties.SNOWY)) {
                        level.setBlock(cursor.set(x, survey.groundY, z),
                                ground.setValue(BlockStateProperties.SNOWY, true), PLACE_FLAGS);
                    }
                }
            }
        }

        /**
         * One tick of tree removal around the house (the gates' GateClearing,
         * with the house itself kept out). Started again from scratch when
         * part of its area is unloaded, since what it read may be stale.
         */
        private Progress stepTrees(ServerLevel level) {
            if (clearing == null) {
                BoundingBox house = new BoundingBox(rect[0], survey.groundY, rect[1],
                        rect[2], survey.groundY + SIZE_Y + CLEAR_ABOVE, rect[3]);
                int reach = Math.max(rect[2] - rect[0], rect[3] - rect[1]) / 2 + 1 + SKIRT;
                clearing = new GateClearing(level, new BlockPos(centerX, survey.groundY + 1, centerZ), reach,
                        house, "Safari rest house");
            }
            GateClearing.Status status = clearing.step(level);
            if (status == GateClearing.Status.WAITING) {
                clearing = null;
                return Progress.WAITING;
            }
            if (status != GateClearing.Status.DONE) {
                return Progress.RUNNING;
            }
            sweepDrops(level);
            return Progress.DONE;
        }

        /**
         * Fells, whole, every tree whose crown or trunk reaches into the
         * volume about to be cleared. Clearing alone would cut them flat
         * against the house, leaving half crowns standing next to it; the trees the
         * reshaping undermines are left to the tree removal, which sees them
         * float.
         *
         * <p>How: the logs and natural leaves inside the volume are the seeds;
         * from the leaves, a walk through leaves of up to {@link #LEAF_REACH}
         * steps finds the logs holding them; from those logs, every log
         * touching them (diagonals included, as a tree is drawn) is felled.
         * A group reaching the edge of the build area or bigger than
         * {@link #MAX_FELLED_LOGS} is left whole: it may go on in chunks not
         * loaded, or not be a tree. Their leaves, now far from any log, are
         * removed by the tree removal at the end of the build.
         *
         * <p>Cost: the volume is read once (about 6,500 blocks), plus the
         * walks, a few hundred blocks per tree.
         */
        private void fellCutTrees(ServerLevel level) {
            int minX = rect[0] - CLEAR_MARGIN;
            int maxX = rect[2] + CLEAR_MARGIN;
            int minZ = rect[1] - CLEAR_MARGIN;
            int maxZ = rect[3] + CLEAR_MARGIN;
            int minY = survey.groundY + 1;
            int maxY = survey.groundY + SIZE_Y + CLEAR_ABOVE - 1;
            BoundingBox area = new BoundingBox(rect[0] - SKIRT, level.getMinBuildHeight(), rect[1] - SKIRT,
                    rect[2] + SKIRT, level.getMaxBuildHeight() - 1, rect[3] + SKIRT);
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            ArrayDeque<Long> leafQueue = new ArrayDeque<>();
            Map<Long, Integer> leafSteps = new HashMap<>();
            Set<Long> trunks = new HashSet<>();
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        BlockState state = level.getBlockState(cursor.set(x, y, z));
                        if (state.is(BlockTags.LOGS)) {
                            trunks.add(cursor.asLong());
                        } else if (isNaturalLeaf(state)) {
                            leafSteps.put(cursor.asLong(), 0);
                            leafQueue.add(cursor.asLong());
                        }
                    }
                }
            }
            // Leaves to the logs that hold them, breadth first, as vanilla measures leaf distance.
            while (!leafQueue.isEmpty()) {
                long current = leafQueue.poll();
                int steps = leafSteps.get(current);
                for (Direction side : Direction.values()) {
                    long next = BlockPos.offset(current, side);
                    cursor.set(next);
                    if (!area.isInside(cursor) || leafSteps.containsKey(next)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (state.is(BlockTags.LOGS)) {
                        trunks.add(next);
                    } else if (steps + 1 < LEAF_REACH && isNaturalLeaf(state)) {
                        leafSteps.put(next, steps + 1);
                        leafQueue.add(next);
                    }
                }
            }
            Set<Long> seen = new HashSet<>();
            int felled = 0;
            int trees = 0;
            for (long start : trunks) {
                if (seen.contains(start)) {
                    continue;
                }
                List<Long> group = treeLogs(level, start, area, seen);
                if (group == null) {
                    continue;
                }
                trees++;
                for (long log : group) {
                    level.setBlock(cursor.set(log), Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
                    removeAttached(level, cursor);
                }
                felled += group.size();
            }
            if (trees > 0) {
                CobblemonSafari.LOGGER.info("Safari rest house at {}, {}: felled {} trees ({} logs) it would have cut",
                        centerX, centerZ, trees, felled);
            }
        }

        /**
         * The logs of the group containing {@code start} (diagonals
         * included), all marked seen; null when the group touches the edge of
         * the area or exceeds {@link #MAX_FELLED_LOGS}, which leaves it standing.
         */
        @Nullable
        private static List<Long> treeLogs(ServerLevel level, long start, BoundingBox area, Set<Long> seen) {
            List<Long> group = new ArrayList<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            boolean keep = false;
            queue.add(start);
            seen.add(start);
            while (!queue.isEmpty()) {
                long current = queue.poll();
                group.add(current);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long next = BlockPos.offset(current, dx, dy, dz);
                            if (seen.contains(next)) {
                                continue;
                            }
                            cursor.set(next);
                            if (!area.isInside(cursor)) {
                                // The tree goes on beyond what may be touched.
                                keep = true;
                                continue;
                            }
                            if (level.getBlockState(cursor).is(BlockTags.LOGS)) {
                                seen.add(next);
                                queue.add(next);
                            }
                        }
                    }
                }
                if (group.size() > MAX_FELLED_LOGS) {
                    keep = true;
                }
            }
            return keep ? null : group;
        }

        /**
         * Removes what hangs on a felled log and would float: cocoa pods and
         * bee nests. Vines and leaves are left: vines hang on leaves too,
         * and the leaves are judged by the tree removal.
         */
        private static void removeAttached(ServerLevel level, BlockPos log) {
            for (Direction side : Direction.values()) {
                BlockPos next = log.relative(side);
                BlockState state = level.getBlockState(next);
                if (state.is(Blocks.COCOA) || state.is(Blocks.BEE_NEST)) {
                    level.setBlock(next, Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
                }
            }
        }

        /** Whether a block is a leaf that grew there (player-placed leaves are persistent). */
        private static boolean isNaturalLeaf(BlockState state) {
            return state.is(BlockTags.LEAVES) && !(state.hasProperty(BlockStateProperties.PERSISTENT)
                    && state.getValue(BlockStateProperties.PERSISTENT));
        }

        /**
         * Removes the items lying around the house that are younger than
         * the build: anything that still dropped while it went on. One
         * entity query, once per house.
         */
        private void sweepDrops(ServerLevel level) {
            if (startTime == Long.MIN_VALUE) {
                return;
            }
            long elapsed = level.getGameTime() - startTime;
            int margin = SKIRT + SWEEP_MARGIN;
            AABB box = new AABB(
                    rect[0] - margin, survey.groundY - MAX_FOUNDATION_DEPTH, rect[1] - margin,
                    rect[2] + margin + 1, survey.groundY + MAX_GROUND_DESCENT, rect[3] + margin + 1);
            List<net.minecraft.world.entity.item.ItemEntity> drops = level.getEntitiesOfClass(
                    ItemEntity.class, box, item -> item.getAge() <= elapsed);
            for (ItemEntity item : drops) {
                item.discard();
            }
            if (!drops.isEmpty()) {
                CobblemonSafari.LOGGER.info("Safari rest house at {}, {}: removed {} dropped items", centerX, centerZ,
                        drops.size());
            }
        }
    }

    // ---- Placement ----------------------------------------------------------

    /**
     * Fixes, as each template block is placed, what a plain placement gets
     * wrong. Everything else passes through unchanged.
     * <ul>
     *   <li><b>Ground.</b> The template's ground layer (its y 0) is grass
     *       and dirt; they become the ground blocks of the site, so a house
     *       in the dunes stands on sand. The grass of the planters, higher
     *       up, is kept: they are gardens.</li>
     *   <li><b>Orientation.</b> Minecraft turns a block by asking the block
     *       ({@code Block.rotate}); a block that does not answer keeps its
     *       saved facing. Cobblemon's disc shelf, TM machine and fossil
     *       machine do not (checked in the 1.8.0 sources), so in a turned
     *       house they would face the walls. For such blocks (rotation a no-op,
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

        /** World height of the template's ground layer. */
        private final int groundY;
        private final BlockState groundTop;
        private final BlockState groundSub;

        TemplateFixer(int groundY, BlockState groundTop, BlockState groundSub) {
            this.groundY = groundY;
            this.groundTop = groundTop;
            this.groundSub = groundSub;
        }

        /** One template block as it must be placed: ground swapped, orientation fixed, fossil links dropped. */
        @Nullable
        @Override
        public StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos offset, BlockPos pivot,
                                                                 StructureTemplate.StructureBlockInfo original,
                                                                 StructureTemplate.StructureBlockInfo placed,
                                                                 StructurePlaceSettings settings) {
            BlockState state = turnIfIgnored(placed.state(), settings.getRotation());
            // placed.pos() is already the world position.
            if (placed.pos().getY() == groundY) {
                if (state.is(Blocks.GRASS_BLOCK)) {
                    state = groundTop;
                } else if (state.is(Blocks.DIRT)) {
                    state = groundSub;
                }
            }
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

        /** Never serialised (see the class comment), so any registered type will do. */
        @Override
        protected StructureProcessorType<?> getType() {
            return StructureProcessorType.NOP;
        }
    }
}
