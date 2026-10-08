package dev.darcosse.common.cobblemonsafari.block;

import com.mojang.serialization.MapCodec;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The Safari Board: a display screen showing the special Pokémon of the day
 * and a countdown, like a departure board in a station.
 *
 * <p>Boards placed side by side, facing the same way, merge into one big
 * screen (see {@link ScreenLayout}). The picture is drawn on the client by
 * the board renderer from data the server pushes to every player, so the
 * block itself holds no state and a board placed anywhere is always current.
 */
public class SafariBoardBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final MapCodec<SafariBoardBlock> CODEC = simpleCodec(SafariBoardBlock::new);

    /**
     * Thickness of the panel in pixels (1/16 block). The panel sits against
     * the back of the block so it hangs flat on a wall. The renderer draws the
     * picture just in front of it: changing this value moves the picture too
     * (see {@code SafariBoardRenderer}), and the block model must be changed
     * to match.
     */
    public static final int PANEL_THICKNESS_PIXELS = 3;

    private static final double PANEL_FRONT = 16.0D - PANEL_THICKNESS_PIXELS;

    /** Hit boxes, one per facing: the panel is on the side opposite the face. */
    private static final VoxelShape SHAPE_NORTH = Block.box(0.0D, 0.0D, PANEL_FRONT, 16.0D, 16.0D, 16.0D);
    private static final VoxelShape SHAPE_SOUTH = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, PANEL_THICKNESS_PIXELS);
    private static final VoxelShape SHAPE_EAST = Block.box(0.0D, 0.0D, 0.0D, PANEL_THICKNESS_PIXELS, 16.0D, 16.0D);
    private static final VoxelShape SHAPE_WEST = Block.box(PANEL_FRONT, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);

    /** Block with the given behaviour; see {@link SafariBlocks} for the actual properties. */
    public SafariBoardBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    /** Codec required by the block registry since 1.20.5. */
    @Override
    protected MapCodec<SafariBoardBlock> codec() {
        return CODEC;
    }

    /** The board's only property: which way its screen points. */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /**
     * Placed facing the player, so the screen is readable right away and its
     * back lands against the wall that was clicked.
     */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    /** Thin panel against the back of the block. */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SHAPE_SOUTH;
            case EAST -> SHAPE_EAST;
            case WEST -> SHAPE_WEST;
            default -> SHAPE_NORTH;
        };
    }

    /** Every board has a block entity; only the bottom-left one of a screen draws it. */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SafariBoardBlockEntity(pos, state);
    }

    /** Tells players that boards merge, which nothing else on the block hints at. */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("block." + CobblemonSafari.MOD_ID + ".safari_board.tooltip")
                .withStyle(ChatFormatting.GRAY));
    }
}
