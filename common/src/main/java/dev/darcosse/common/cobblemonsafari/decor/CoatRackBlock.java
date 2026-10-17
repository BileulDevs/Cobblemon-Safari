package dev.darcosse.common.cobblemonsafari.decor;

import com.mojang.serialization.MapCodec;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.block.WallScreenSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A two-block coat rack with a ranger's jacket on its front hook and a
 * ranger's hat on top. Right-click with a dye recolours the hat (16 colours;
 * brown, the classic felt, when placed).
 *
 * <p>Built like vanilla's tall plants: a lower and an upper half, placed
 * together, removed together, dropping one item. Facing is the side the
 * jacket hangs on.
 */
public class CoatRackBlock extends Block {

    public static final MapCodec<CoatRackBlock> CODEC = simpleCodec(CoatRackBlock::new);

    /** Which half this block is. */
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    /** Side the jacket hangs on. */
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    /**
     * Colour of the hat felt. Held by both halves so either can be dyed; the
     * upper half's model draws it. Part of the block states: renaming the
     * property resets saved hats to brown.
     */
    public static final EnumProperty<DyeColor> HAT = EnumProperty.create("hat", DyeColor.class);

    /** Vanilla's level event id for a block broken (particles and sound), as LevelEvent.PARTICLES_DESTROY_BLOCK. */
    private static final int BLOCK_BREAK_EVENT = 2001;

    /** Felt of a new rack's hat. */
    private static final DyeColor DEFAULT_HAT = DyeColor.BROWN;

    /** Outlines facing north, in pixels: feet and pole below, pole, jacket and hat above. */
    private static final double[][] LOWER_NORTH = {{3, 0, 3, 13, 1, 13}, {7, 1, 7, 9, 16, 9}, {4, 6, 2, 12, 16, 7}};
    private static final double[][] UPPER_NORTH = {{7, 0, 7, 9, 13, 9}, {4, 0, 2, 12, 10, 7}, {3, 13, 3, 13, 16, 13}};

    private static final Map<Direction, VoxelShape> LOWER = new EnumMap<>(Direction.class);
    private static final Map<Direction, VoxelShape> UPPER = new EnumMap<>(Direction.class);

    static {
        for (Direction facing : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            LOWER.put(facing, DecorShapes.union(DecorShapes.rotate(LOWER_NORTH, facing)));
            UPPER.put(facing, DecorShapes.union(DecorShapes.rotate(UPPER_NORTH, facing)));
        }
    }

    /** Block with the given behaviour; see {@link SafariDecor} for the actual properties. */
    public CoatRackBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(HALF, DoubleBlockHalf.LOWER)
                .setValue(FACING, Direction.NORTH).setValue(HAT, DEFAULT_HAT));
    }

    /** Codec required by the block registry since 1.20.5. */
    @Override
    protected MapCodec<CoatRackBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HALF, FACING, HAT);
    }

    /** Jacket toward the player; refused when the block above is taken. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockPos pos = ctx.getClickedPos();
        Level level = ctx.getLevel();
        if (pos.getY() >= level.getMaxBuildHeight() - 1 || !level.getBlockState(pos.above()).canBeReplaced(ctx)) {
            return null;
        }
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    /** Places the upper half, as vanilla's tall plants. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
    }

    /** The upper half needs the lower one under it; the lower one needs solid ground. */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockState below = level.getBlockState(pos.below());
            return below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER;
        }
        return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    /**
     * Removes this half when its partner goes (or the ground under the lower
     * one), and keeps both halves' hat colour in step.
     */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour,
                                     LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        DoubleBlockHalf half = state.getValue(HALF);
        boolean towardPartner = direction == (half == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN);
        if (towardPartner) {
            if (!neighbour.is(this) || neighbour.getValue(HALF) == half) {
                return Blocks.AIR.defaultBlockState();
            }
            return state.setValue(HAT, neighbour.getValue(HAT));
        }
        if (half == DoubleBlockHalf.LOWER && direction == Direction.DOWN && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    /**
     * Breaking the upper half removes the lower one here, dropping its item
     * in survival: only the lower half has loot (see its loot table), so an
     * explosion catching both halves still drops a single rack. Breaking the
     * lower half drops it the normal way and the upper half follows through
     * {@link #updateShape}.
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockPos below = pos.below();
            BlockState lower = level.getBlockState(below);
            if (lower.is(this) && lower.getValue(HALF) == DoubleBlockHalf.LOWER) {
                if (!player.isCreative()) {
                    Block.dropResources(lower, level, below, null, player, player.getMainHandItem());
                }
                level.setBlock(below, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                // Vanilla's "block broken" event: particles and sound of the lower half.
                level.levelEvent(player, BLOCK_BREAK_EVENT, below, Block.getId(lower));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * A dye recolours the hat, both halves at once; consumed outside
     * creative. Same rights as the wall screens, so the racks of the
     * Safari's rest houses keep their colours.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(stack.getItem() instanceof DyeItem dye)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        DyeColor colour = dye.getDyeColor();
        if (state.getValue(HAT) == colour) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            if (!WallScreenSettings.canEdit(serverPlayer, level, pos)) {
                serverPlayer.displayClientMessage(
                        Component.translatable(CobblemonSafari.MOD_ID + ".message.coat_rack_locked"), true);
                return ItemInteractionResult.CONSUME;
            }
            BlockPos lower = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
            BlockState lowerState = level.getBlockState(lower);
            if (lowerState.is(this)) {
                // The upper half follows through updateShape.
                level.setBlock(lower, lowerState.setValue(HAT, colour), Block.UPDATE_ALL);
            }
            stack.consume(1, player);
            level.playSound(null, pos, SoundEvents.DYE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    /** Each half's outline, as drawn. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? LOWER.get(facing) : UPPER.get(facing);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** Tells players the hat takes dyes. */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("block." + CobblemonSafari.MOD_ID + ".coat_rack.tooltip")
                .withStyle(ChatFormatting.GRAY));
    }
}
