package dev.darcosse.common.cobblemonsafari.block;

import com.mojang.serialization.MapCodec;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wooden frame placed around Safari Board screens, turning a wall of screens
 * into a mission board: posts and beams around the edge, planks behind, and
 * legs down to the ground.
 *
 * <p>The block shapes itself from its neighbours in the wall plane, like a
 * fence: a beam runs toward every frame next to it (and down to solid
 * ground), and planks fill each quarter of the block that lies between frames
 * and screens. Only blocks facing the same way count, so two boards back to
 * back do not merge.
 *
 * <p>LEFT and RIGHT are as seen by someone reading the board (see
 * {@link ScreenLayout#rightOf}); the block models are authored facing north
 * and turned by the blockstate, which keeps that meaning for every facing.
 */
public class SafariBoardFrameBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<SafariBoardFrameBlock> CODEC = simpleCodec(SafariBoardFrameBlock::new);

    /** Beams toward the neighbour in that direction. */
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");
    public static final BooleanProperty LEFT = BooleanProperty.create("left");
    public static final BooleanProperty RIGHT = BooleanProperty.create("right");

    /** Planks in each quarter of the block, named by corner (top-left, top-right...). */
    public static final BooleanProperty PLANKS_TOP_LEFT = BooleanProperty.create("planks_top_left");
    public static final BooleanProperty PLANKS_TOP_RIGHT = BooleanProperty.create("planks_top_right");
    public static final BooleanProperty PLANKS_BOTTOM_LEFT = BooleanProperty.create("planks_bottom_left");
    public static final BooleanProperty PLANKS_BOTTOM_RIGHT = BooleanProperty.create("planks_bottom_right");

    /**
     * Splayed feet at the bottom of a leg: set on a frame standing on solid
     * ground at the end of a beam (or alone), never in the middle of a beam.
     */
    public static final BooleanProperty FOOT = BooleanProperty.create("foot");

    /** Wood of the planks, changed by right-clicking with vanilla planks. */
    public static final EnumProperty<BoardWood> WOOD = EnumProperty.create("wood", BoardWood.class);

    /**
     * Most frames a single right-click recolours. A board is a few dozen
     * frames; the cap only stops a runaway fill along an absurdly long wall.
     */
    private static final int MAX_RECOLOURED = 1024;

    // Geometry in pixels, for a frame facing north (front toward -Z, back at Z = 16).
    // The block models in assets/.../models/block/safari_board_frame_*.json use
    // the same numbers: change one, change the other.

    /** Beam width in the wall plane. Wider reads as a heavier frame; above ~6 it crowds small screens. */
    private static final double BEAM_MIN = 6.0D;
    private static final double BEAM_MAX = 10.0D;
    /**
     * Front of the beams. The screen panel's front is at 16 - 3 = 13, so the
     * beams stand 3 px proud of the screen, which is what makes it read as framed.
     */
    private static final double BEAM_FRONT = 10.0D;
    /** Front of the planks: flush with the screen panel, so screen and planks form one surface. */
    private static final double PLANKS_FRONT = 16.0D - SafariBoardBlock.PANEL_THICKNESS_PIXELS;
    private static final double HALF = 8.0D;
    private static final double FULL = 16.0D;

    /** Collision and outline shape of every state, built once. */
    private final Map<BlockState, VoxelShape> shapes = new HashMap<>();

    /** Block with the given behaviour; see {@link SafariBlocks} for the actual properties. */
    public SafariBoardFrameBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any()
                .setValue(FACING, Direction.NORTH)
                .setValue(UP, false).setValue(DOWN, false).setValue(LEFT, false).setValue(RIGHT, false)
                .setValue(PLANKS_TOP_LEFT, false).setValue(PLANKS_TOP_RIGHT, false)
                .setValue(PLANKS_BOTTOM_LEFT, false).setValue(PLANKS_BOTTOM_RIGHT, false)
                .setValue(FOOT, false).setValue(WOOD, BoardWood.DEFAULT));
        // The wood never changes the shape: shapes are built for the default
        // wood only, a tenth of the states, and looked up through it.
        for (BlockState state : getStateDefinition().getPossibleStates()) {
            if (state.getValue(WOOD) == BoardWood.DEFAULT) {
                shapes.put(state, buildShape(state));
            }
        }
    }

    /** Codec required by the block registry since 1.20.5. */
    @Override
    protected MapCodec<SafariBoardFrameBlock> codec() {
        return CODEC;
    }

    /** Facing plus the eight connection flags. */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, UP, DOWN, LEFT, RIGHT,
                PLANKS_TOP_LEFT, PLANKS_TOP_RIGHT, PLANKS_BOTTOM_LEFT, PLANKS_BOTTOM_RIGHT, FOOT, WOOD);
    }

    /**
     * Faces the same way as the board or frame that was clicked, so building a
     * frame around a screen never needs the player to stand just right;
     * otherwise faces the player, like the screen itself.
     */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockPos clicked = ctx.getClickedPos().relative(ctx.getClickedFace().getOpposite());
        BlockState clickedState = ctx.getLevel().getBlockState(clicked);
        Direction facing = isBoardPart(clickedState)
                ? clickedState.getValue(FACING)
                : ctx.getHorizontalDirection().getOpposite();
        // Extending a frame keeps its wood, so a board stays one wood while it grows.
        BoardWood wood = clickedState.getBlock() instanceof SafariBoardFrameBlock
                ? clickedState.getValue(WOOD)
                : BoardWood.DEFAULT;
        return connect(ctx.getLevel(), ctx.getClickedPos(),
                defaultBlockState().setValue(FACING, facing).setValue(WOOD, wood));
    }

    /** A direct neighbour changed: reshape from scratch, the rules are cheap. */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return connect(level, pos, state);
    }

    /**
     * Placing a frame can change the planks of frames diagonal to it, which
     * vanilla does not notify (it only updates direct neighbours).
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!sameWall(state, oldState)) {
            refreshDiagonals(level, pos, state.getValue(FACING));
        }
    }

    /** Same as {@link #onPlace}, for removal. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (!sameWall(state, newState)) {
            refreshDiagonals(level, pos, state.getValue(FACING));
        }
    }

    /** Shape matching the model: beams, core and planks of this state. */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.get(state.setValue(WOOD, BoardWood.DEFAULT));
    }

    /**
     * Right-click with vanilla planks: every frame of this board takes that
     * wood. The planks are not used up, it is a paint job, not a craft.
     * Anything else falls through to the normal interaction.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        BoardWood wood = BoardWood.fromItem(stack.getItem());
        if (wood == null) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (state.getValue(WOOD) == wood) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide()) {
            recolour(level, pos, state.getValue(FACING), wood);
            level.playSound(null, pos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    /**
     * Gives a wood to every frame connected to {@code start} in the same wall,
     * walking through frames only (up, down, left, right). Uses
     * UPDATE_KNOWN_SHAPE: the wood changes no connection, so
     * neighbours need not reshape.
     */
    private static void recolour(Level level, BlockPos start, Direction facing, BoardWood wood) {
        Direction right = ScreenLayout.rightOf(facing);
        Direction[] steps = {Direction.UP, Direction.DOWN, right, right.getOpposite()};
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && seen.size() <= MAX_RECOLOURED) {
            BlockPos pos = queue.poll();
            BlockState state = level.getBlockState(pos);
            level.setBlock(pos, state.setValue(WOOD, wood), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            for (Direction step : steps) {
                BlockPos next = pos.relative(step);
                if (!seen.contains(next) && isFrame(level, next, facing)) {
                    seen.add(next);
                    queue.add(next);
                }
            }
        }
    }

    /** Tells players how the frame behaves, which nothing else hints at. */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("block." + CobblemonSafari.MOD_ID + ".safari_board_frame.tooltip")
                .withStyle(ChatFormatting.GRAY));
    }

    // ---- Connection rules ---------------------------------------------------

    /**
     * Recomputes every connection flag of a frame from the blocks around it.
     *
     * <p>Beams go toward neighbouring frames, plus downward onto any block with
     * a solid top so legs reach the ground. A quarter gets planks when the two
     * neighbours on its sides and the one on its corner are all frames or
     * screens: that fills exactly the inside of the frame, never the outside.
     */
    public static BlockState connect(BlockGetter level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        Direction right = ScreenLayout.rightOf(facing);
        Direction left = right.getOpposite();
        BlockPos above = pos.above();
        BlockPos below = pos.below();
        BlockPos toLeft = pos.relative(left);
        BlockPos toRight = pos.relative(right);

        boolean partUp = isPart(level, above, facing);
        boolean partDown = isPart(level, below, facing);
        boolean partLeft = isPart(level, toLeft, facing);
        boolean partRight = isPart(level, toRight, facing);
        BlockState belowState = level.getBlockState(below);

        boolean up = isFrame(level, above, facing);
        boolean frameBelow = isFrame(level, below, facing);
        boolean ground = !frameBelow && belowState.isFaceSturdy(level, below, Direction.UP);
        boolean leftFrame = isFrame(level, toLeft, facing);
        boolean rightFrame = isFrame(level, toRight, facing);
        // Only the ends of a beam stand on the ground: a block with frames on
        // both sides is the middle of a beam and hangs between the two legs.
        boolean beamEnd = !(leftFrame && rightFrame);
        boolean standing = ground && beamEnd;
        return state
                .setValue(UP, up)
                .setValue(DOWN, frameBelow || standing)
                .setValue(LEFT, leftFrame)
                .setValue(RIGHT, rightFrame)
                // Feet wherever a leg meets the ground, corners included.
                .setValue(FOOT, standing)
                .setValue(PLANKS_TOP_LEFT, partUp && partLeft && isPart(level, above.relative(left), facing))
                .setValue(PLANKS_TOP_RIGHT, partUp && partRight && isPart(level, above.relative(right), facing))
                .setValue(PLANKS_BOTTOM_LEFT, partDown && partLeft && isPart(level, below.relative(left), facing))
                .setValue(PLANKS_BOTTOM_RIGHT, partDown && partRight && isPart(level, below.relative(right), facing));
    }

    /**
     * Reshapes the frames on the four diagonals of a position in the wall
     * plane. Called by frames and screens when they appear or disappear,
     * since their diagonal neighbours' planks may depend on them.
     */
    public static void refreshDiagonals(Level level, BlockPos pos, Direction facing) {
        if (level.isClientSide()) {
            return;
        }
        Direction right = ScreenLayout.rightOf(facing);
        BlockPos[] diagonals = {
                pos.above().relative(right), pos.above().relative(right.getOpposite()),
                pos.below().relative(right), pos.below().relative(right.getOpposite())
        };
        for (BlockPos diagonal : diagonals) {
            BlockState current = level.getBlockState(diagonal);
            if (current.getBlock() instanceof SafariBoardFrameBlock && current.getValue(FACING) == facing) {
                BlockState updated = connect(level, diagonal, current);
                if (updated != current) {
                    level.setBlock(diagonal, updated, Block.UPDATE_ALL);
                }
            }
        }
    }

    /** Whether a state is a screen or a frame, i.e. something that has a FACING in this family. */
    private static boolean isBoardPart(BlockState state) {
        return state.getBlock() instanceof SafariBoardBlock || state.getBlock() instanceof SafariBoardFrameBlock;
    }

    /** Whether the block at a position is a screen or frame of the same wall. */
    private static boolean isPart(BlockGetter level, BlockPos pos, Direction facing) {
        BlockState state = level.getBlockState(pos);
        return isBoardPart(state) && state.getValue(FACING) == facing;
    }

    /** Whether the block at a position is a frame of the same wall. */
    private static boolean isFrame(BlockGetter level, BlockPos pos, Direction facing) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof SafariBoardFrameBlock && state.getValue(FACING) == facing;
    }

    /**
     * Whether a state change keeps the same board piece in the same wall, i.e.
     * only connection flags changed. Those changes must not refresh the
     * diagonals again, or every reshape would ripple across the whole wall.
     */
    private static boolean sameWall(BlockState state, BlockState other) {
        return isBoardPart(other) && other.getBlock() == state.getBlock()
                && other.getValue(FACING) == state.getValue(FACING);
    }

    // ---- Shapes ---------------------------------------------------------------

    /** Union of the boxes the model draws for this state, turned to its facing. */
    private static VoxelShape buildShape(BlockState state) {
        Direction facing = state.getValue(FACING);
        VoxelShape shape = box(facing, BEAM_MIN, BEAM_MIN, BEAM_FRONT, BEAM_MAX, BEAM_MAX, FULL);
        // North-facing frame seen from the front: the reader's left is +X.
        Map<BooleanProperty, double[]> parts = new HashMap<>();
        parts.put(UP, new double[]{BEAM_MIN, BEAM_MAX, BEAM_MAX, FULL, BEAM_FRONT});
        parts.put(DOWN, new double[]{BEAM_MIN, BEAM_MAX, 0.0D, BEAM_MIN, BEAM_FRONT});
        parts.put(LEFT, new double[]{BEAM_MAX, FULL, BEAM_MIN, BEAM_MAX, BEAM_FRONT});
        parts.put(RIGHT, new double[]{0.0D, BEAM_MIN, BEAM_MIN, BEAM_MAX, BEAM_FRONT});
        parts.put(PLANKS_TOP_LEFT, new double[]{HALF, FULL, HALF, FULL, PLANKS_FRONT});
        parts.put(PLANKS_TOP_RIGHT, new double[]{0.0D, HALF, HALF, FULL, PLANKS_FRONT});
        parts.put(PLANKS_BOTTOM_LEFT, new double[]{HALF, FULL, 0.0D, HALF, PLANKS_FRONT});
        parts.put(PLANKS_BOTTOM_RIGHT, new double[]{0.0D, HALF, 0.0D, HALF, PLANKS_FRONT});
        for (Map.Entry<BooleanProperty, double[]> part : parts.entrySet()) {
            if (state.getValue(part.getKey())) {
                double[] b = part.getValue();
                shape = Shapes.or(shape, box(facing, b[0], b[2], b[4], b[1], b[3], FULL));
            }
        }
        return shape.optimize();
    }

    /**
     * A box given in north-facing pixel coordinates, turned to the facing the
     * same way the blockstate turns the model (clockwise seen from above).
     */
    private static VoxelShape box(Direction facing, double x0, double y0, double z0,
                                  double x1, double y1, double z1) {
        return switch (facing) {
            case EAST -> Block.box(FULL - z1, y0, x0, FULL - z0, y1, x1);
            case SOUTH -> Block.box(FULL - x1, y0, FULL - z1, FULL - x0, y1, FULL - z0);
            case WEST -> Block.box(z0, y0, FULL - x1, z1, y1, FULL - x0);
            default -> Block.box(x0, y0, z0, x1, y1, z1);
        };
    }
}
