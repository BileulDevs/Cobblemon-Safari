package dev.darcosse.common.cobblemonsafari.block;

import com.mojang.serialization.MapCodec;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The Safari Wall Screen: the Safari Board's picture on a panel held off the
 * wall by a single arm, tilted toward the floor like a TV mounted high, or
 * toward the ceiling like a screen mounted low.
 *
 * <p>Up to {@link WallScreenLayout#MAX_WIDTH} screens side by side, facing the
 * same way, merge into one wider panel with one arm at its centre. They never
 * merge with Safari Boards, and never vertically.
 *
 * <p>Right-click with an empty hand opens a settings screen choosing what the
 * group shows ({@link WallScreenContent}) and its tilt, when the player may
 * change it (see {@link WallScreenSettings#canEdit}). Both settings are block
 * state properties, so they reach every client through the normal chunk sync.
 *
 * <p>The block model is empty: the panel moves with the tilt, which a baked
 * model cannot follow by 5° steps, so the block entity renderer draws the
 * panel, the arm and the picture.
 */
public class SafariWallScreenBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final MapCodec<SafariWallScreenBlock> CODEC = simpleCodec(SafariWallScreenBlock::new);

    /**
     * Degrees per step of the tilt slider. Smaller steps give a finer slider
     * and more block states; the renderer and the hit boxes follow any value.
     */
    public static final int TILT_STEP_DEGREES = 5;

    /**
     * Number of steps on each side of 0°, so the range is -30° to +30°.
     * Raising it tilts further: past about 40° the edge of the panel that
     * swings back goes into the wall unless {@link #PIVOT_DEPTH_PIXELS} grows
     * with it (that half swings back by {@code PANEL_HEIGHT / 2 × sin(tilt)}).
     * Changing it changes the block state list, so existing screens with a
     * higher value than the new maximum fall back to the default.
     */
    public static final int MAX_TILT_STEPS = 6;

    /** How far the panel is tilted, in steps of {@link #TILT_STEP_DEGREES}; the direction is {@link #UPWARD}. */
    public static final IntegerProperty TILT = IntegerProperty.create("tilt", 0, MAX_TILT_STEPS);

    /**
     * Whether the panel faces up (bottom away from the wall) instead of down
     * (top away from the wall). A separate flag rather than a signed TILT
     * range: TILT keeps its values 0 to 6 with their old meaning, so the
     * screens of existing worlds keep their angle. Meaningless at tilt 0;
     * read the angle through {@link #signedTilt}.
     */
    public static final BooleanProperty UPWARD = BooleanProperty.create("upward");

    /** What the screen shows. New screens show the specials, like the Safari Board. */
    public static final EnumProperty<WallScreenContent> CONTENT = EnumProperty.create("content", WallScreenContent.class);

    /**
     * Panel height in pixels (1/16 block). Under 16 so the tilted panel stays
     * inside its block: screens stacked vertically then never cut into each
     * other, and the hit box never pokes into the block above.
     */
    public static final float PANEL_HEIGHT_PIXELS = 14.0F;

    /** Panel thickness in pixels. The picture is drawn just in front of it. */
    public static final float PANEL_THICKNESS_PIXELS = 2.0F;

    /** Height of the hinge the panel tilts around, from the bottom of the block; the panel's middle. */
    public static final float PIVOT_HEIGHT_PIXELS = 8.0F;

    /**
     * Distance from the wall to the back of the panel at the hinge, in pixels:
     * the arm's length. At 30° the bottom edge comes back to 1.5 px from the
     * wall; shortening the arm below {@code PANEL_HEIGHT / 2 × sin(max tilt)}
     * (3.5 px) pushes it into the wall.
     */
    public static final float PIVOT_DEPTH_PIXELS = 5.0F;

    /**
     * Number of horizontal slices approximating the tilted panel in the hit
     * box. A tilted slab cannot be one box: each slice is the box around its
     * part of the panel, giving a staircase. More slices fit tighter and cost
     * more boxes per collision check; 4 keeps the steps under a pixel at 30°.
     */
    private static final int HITBOX_SLICES = 4;

    /** Pixels in a block, for {@link Block#box} coordinates. */
    private static final double FULL = 16.0D;

    /** Hit boxes per facing then per signed tilt step (index = step + MAX_TILT_STEPS), built once. */
    private static final Map<Direction, VoxelShape[]> SHAPES = buildShapes();

    /** Block with the given behaviour; see {@link SafariBlocks} for the actual properties. */
    public SafariWallScreenBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH).setValue(TILT, 0)
                .setValue(UPWARD, false).setValue(CONTENT, WallScreenContent.FEATURED));
    }

    /** Codec required by the block registry since 1.20.5. */
    @Override
    protected MapCodec<SafariWallScreenBlock> codec() {
        return CODEC;
    }

    /** Which way the screen points, how far it is tilted and what it shows. */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, TILT, UPWARD, CONTENT);
    }

    /**
     * Placed facing the player like the Safari Board, so its back lands
     * against the wall that was clicked. Takes the tilt and content of a
     * screen it joins, so a group never shows two settings at once: the left
     * neighbour wins, as it is the closer one to the group's anchor.
     */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction facing = ctx.getHorizontalDirection().getOpposite();
        Direction right = ScreenLayout.rightOf(facing);
        BlockState placed = defaultBlockState().setValue(FACING, facing);
        for (Direction side : new Direction[]{right.getOpposite(), right}) {
            BlockState neighbour = ctx.getLevel().getBlockState(ctx.getClickedPos().relative(side));
            if (neighbour.getBlock() instanceof SafariWallScreenBlock
                    && neighbour.getValue(FACING) == facing) {
                return withSignedTilt(placed, signedTilt(neighbour))
                        .setValue(CONTENT, neighbour.getValue(CONTENT));
            }
        }
        return placed;
    }

    /** The tilted panel, as a staircase of slices (see {@link #HITBOX_SLICES}). */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING))[signedTilt(state) + MAX_TILT_STEPS];
    }

    /**
     * Empty hand: right-click opens the settings screen of the group, or says
     * why not. Anything held falls through, so another screen in hand is
     * placed next to this one as usual.
     *
     * <p>Only the main hand counts: vanilla calls this once per hand, and the
     * off hand being empty too would otherwise open the screen twice.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND || !stack.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            WallScreenSettings.open(serverPlayer, pos, state);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    /** Every wall screen has a block entity; only a group's anchor draws. */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SafariWallScreenBlockEntity(pos, state);
    }

    /** Tells players how screens merge and tilt, which nothing on the block hints at. */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("block." + CobblemonSafari.MOD_ID + ".safari_wall_screen.tooltip")
                .withStyle(ChatFormatting.GRAY));
    }

    /**
     * Angle of a signed tilt step value, in degrees; the one conversion
     * shared with the renderer. Positive faces the floor, negative the ceiling.
     */
    public static float tiltDegrees(int tiltSteps) {
        return tiltSteps * (float) TILT_STEP_DEGREES;
    }

    /** The tilt of a screen as one signed step value: positive toward the floor, negative toward the ceiling. */
    public static int signedTilt(BlockState state) {
        int steps = state.getValue(TILT);
        return state.getValue(UPWARD) ? -steps : steps;
    }

    /**
     * A screen state with the given signed tilt, clamped to the range. Zero
     * clears {@link #UPWARD}, so one angle has one state.
     */
    public static BlockState withSignedTilt(BlockState state, int signedSteps) {
        int clamped = Math.max(-MAX_TILT_STEPS, Math.min(MAX_TILT_STEPS, signedSteps));
        return state.setValue(TILT, Math.abs(clamped)).setValue(UPWARD, clamped < 0);
    }

    /**
     * Builds the hit boxes. Each slice of the panel is rotated around the
     * hinge exactly like the renderer does, then boxed. Worked in "wall
     * space": x along the wall, y up, depth = distance from the wall.
     */
    private static Map<Direction, VoxelShape[]> buildShapes() {
        Map<Direction, VoxelShape[]> shapes = new EnumMap<>(Direction.class);
        for (Direction facing : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            VoxelShape[] byTilt = new VoxelShape[2 * MAX_TILT_STEPS + 1];
            for (int tilt = -MAX_TILT_STEPS; tilt <= MAX_TILT_STEPS; tilt++) {
                double angle = Math.toRadians(tiltDegrees(tilt));
                double sin = Math.sin(angle);
                double cos = Math.cos(angle);
                // The first slice seeds the union, as in SafariBoardFrameBlock, instead
                // of an empty-shape helper the project does not otherwise rely on.
                VoxelShape shape = null;
                double sliceHeight = PANEL_HEIGHT_PIXELS / HITBOX_SLICES;
                for (int slice = 0; slice < HITBOX_SLICES; slice++) {
                    // Slice corners relative to the hinge, before rotation.
                    double yLow = -PANEL_HEIGHT_PIXELS / 2.0D + slice * sliceHeight;
                    double yHigh = yLow + sliceHeight;
                    double minY = Double.MAX_VALUE;
                    double maxY = -Double.MAX_VALUE;
                    double minDepth = Double.MAX_VALUE;
                    double maxDepth = -Double.MAX_VALUE;
                    for (double y : new double[]{yLow, yHigh}) {
                        for (double depth : new double[]{0.0D, PANEL_THICKNESS_PIXELS}) {
                            // Same rotation as the renderer's: a positive angle swings the top
                            // away from the wall, a negative one the bottom.
                            double rotatedY = y * cos - depth * sin;
                            double rotatedDepth = y * sin + depth * cos;
                            minY = Math.min(minY, rotatedY);
                            maxY = Math.max(maxY, rotatedY);
                            minDepth = Math.min(minDepth, rotatedDepth);
                            maxDepth = Math.max(maxDepth, rotatedDepth);
                        }
                    }
                    VoxelShape sliceBox = box(facing,
                            clamp(PIVOT_HEIGHT_PIXELS + minY), clamp(PIVOT_HEIGHT_PIXELS + maxY),
                            clamp(PIVOT_DEPTH_PIXELS + minDepth), clamp(PIVOT_DEPTH_PIXELS + maxDepth));
                    shape = shape == null ? sliceBox : Shapes.or(shape, sliceBox);
                }
                byTilt[tilt + MAX_TILT_STEPS] = shape.optimize();
            }
            shapes.put(facing, byTilt);
        }
        return shapes;
    }

    /** Keeps a coordinate inside the block, where {@link Block#box} expects it. */
    private static double clamp(double pixels) {
        return Math.max(0.0D, Math.min(FULL, pixels));
    }

    /**
     * A full-width box in wall space turned into block coordinates. The wall
     * is behind the screen: for a screen facing north it is the south face
     * (z = 16), so depth d sits at z = 16 - d.
     */
    private static VoxelShape box(Direction facing, double y0, double y1, double depth0, double depth1) {
        return switch (facing) {
            case SOUTH -> Block.box(0.0D, y0, depth0, FULL, y1, depth1);
            case EAST -> Block.box(depth0, y0, 0.0D, depth1, y1, FULL);
            case WEST -> Block.box(FULL - depth1, y0, 0.0D, FULL - depth0, y1, FULL);
            default -> Block.box(0.0D, y0, FULL - depth1, FULL, y1, FULL - depth0);
        };
    }
}
