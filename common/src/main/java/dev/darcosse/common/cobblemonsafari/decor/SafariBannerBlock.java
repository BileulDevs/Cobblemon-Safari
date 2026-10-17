package dev.darcosse.common.cobblemonsafari.decor;

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
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * A Safari Banner standing on the ground: a pole with a cloth showing a
 * picture of {@link BannerDesigns}, swaying like a vanilla banner. Turned
 * in 16 steps like vanilla standing banners and signs. Its wall twin is
 * {@link SafariWallBannerBlock}; one item places either.
 *
 * <p>Right-click with an empty hand opens the design picker, when the player
 * may change it. The block model is empty: {@code SafariBannerRenderer} draws
 * the pole, the bar and the cloth.
 */
public class SafariBannerBlock extends Block implements EntityBlock {

    public static final MapCodec<SafariBannerBlock> CODEC = simpleCodec(SafariBannerBlock::new);

    /** Which way the cloth faces, in 16 steps like vanilla banners. */
    public static final IntegerProperty ROTATION = BlockStateProperties.ROTATION_16;

    /** Outline around the pole, as vanilla's banner. */
    private static final VoxelShape SHAPE = Block.box(4.0D, 0.0D, 4.0D, 12.0D, 16.0D, 12.0D);

    /** Block with the given behaviour; see {@link SafariDecor} for the actual properties. */
    public SafariBannerBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(ROTATION, 0));
    }

    /** Codec required by the block registry since 1.20.5. */
    @Override
    protected MapCodec<SafariBannerBlock> codec() {
        return CODEC;
    }

    /** Rotation only; the picture lives in the block entity. */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ROTATION);
    }

    /** Facing the player who places it, as a vanilla banner. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(ROTATION, RotationSegment.convertToSegment(ctx.getRotation() + 180.0F));
    }

    /** Needs solid ground under the pole, as a vanilla banner. */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).isSolid();
    }

    /** Pops off when the ground under it goes. */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour,
                                     LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return direction == Direction.DOWN && !state.canSurvive(level, pos)
                ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    /** Outline around the pole. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** Turned by structures like a vanilla banner. */
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(ROTATION, rotation.rotate(state.getValue(ROTATION), 16));
    }

    /** Mirrored by structures like a vanilla banner. */
    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(ROTATION, mirror.mirror(state.getValue(ROTATION), 16));
    }

    /**
     * Empty main hand: opens the design picker, or says why not. Anything
     * held falls through; only the main hand counts, so the picker never
     * opens twice.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        return openPicker(stack, level, pos, player, hand);
    }

    /** The picker opening shared by the standing and wall banners. */
    static ItemInteractionResult openPicker(ItemStack stack, Level level, BlockPos pos, Player player,
                                            InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !stack.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            BannerDesigns.open(serverPlayer, pos);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    /** Holds the picture. */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SafariBannerBlockEntity(pos, state);
    }

    /** Tells players how to change the picture, which nothing on the banner hints at. */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("block." + CobblemonSafari.MOD_ID + ".safari_banner.tooltip")
                .withStyle(ChatFormatting.GRAY));
    }
}
