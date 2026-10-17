package dev.darcosse.common.cobblemonsafari.decor;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * A Safari Banner hung on a wall: the bar against the wall at the top of
 * the block, the cloth hanging into the block below, as a vanilla wall
 * banner. Placed by the same item as {@link SafariBannerBlock}, whose
 * name it shares.
 */
public class SafariWallBannerBlock extends Block implements EntityBlock {

    public static final MapCodec<SafariWallBannerBlock> CODEC = simpleCodec(SafariWallBannerBlock::new);

    /** Direction the cloth faces; the wall is behind it. */
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    /** Outlines against the wall, as vanilla's wall banner, by facing. */
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Map.of(
            Direction.NORTH, Block.box(0.0D, 0.0D, 14.0D, 16.0D, 12.5D, 16.0D),
            Direction.SOUTH, Block.box(0.0D, 0.0D, 0.0D, 16.0D, 12.5D, 2.0D),
            Direction.WEST, Block.box(14.0D, 0.0D, 0.0D, 16.0D, 12.5D, 16.0D),
            Direction.EAST, Block.box(0.0D, 0.0D, 0.0D, 2.0D, 12.5D, 16.0D)));

    /** Block with the given behaviour; see {@link SafariDecor} for the actual properties. */
    public SafariWallBannerBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    /** Codec required by the block registry since 1.20.5. */
    @Override
    protected MapCodec<SafariWallBannerBlock> codec() {
        return CODEC;
    }

    /**
     * Same name as the standing banner, which owns the item. Asked of the
     * standing block rather than of this block's item, as vanilla's wall
     * banner does: whether a loader maps a wall block to its item depends on
     * the loader, the standing block's own name does not.
     */
    @Override
    public String getDescriptionId() {
        return SafariDecor.safariBanner().getDescriptionId();
    }

    /** Pick-block gives the banner item, for the same reason as the name. */
    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(SafariDecor.safariBanner());
    }

    /** Facing only; the picture lives in the block entity. */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** Needs a solid block behind it. */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.relative(state.getValue(FACING).getOpposite())).isSolid();
    }

    /** Pops off when its wall goes. */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour,
                                     LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return direction == state.getValue(FACING).getOpposite() && !state.canSurvive(level, pos)
                ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    /** The first wall around the clicked spot, in the order the player looks, as a vanilla wall banner. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState state = defaultBlockState();
        for (Direction direction : ctx.getNearestLookingDirections()) {
            if (direction.getAxis().isHorizontal()) {
                state = state.setValue(FACING, direction.getOpposite());
                if (state.canSurvive(ctx.getLevel(), ctx.getClickedPos())) {
                    return state;
                }
            }
        }
        return null;
    }

    /** Outline against the wall. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    /** Turned by structures. */
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    /** Mirrored by structures. */
    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** Empty main hand: the design picker, as on the standing banner. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        return SafariBannerBlock.openPicker(stack, level, pos, player, hand);
    }

    /** Holds the picture. */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SafariBannerBlockEntity(pos, state);
    }
}
