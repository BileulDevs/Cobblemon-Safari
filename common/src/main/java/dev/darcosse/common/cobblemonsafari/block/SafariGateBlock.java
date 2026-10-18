package dev.darcosse.common.cobblemonsafari.block;

import com.mojang.serialization.MapCodec;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.gate.SafariGate;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The shimmering surface of a Safari Gate: standing in it with banked Safari
 * time sends the player into the Safari.
 *
 * <p>It behaves like a nether portal, through vanilla's {@link Portal}
 * mechanism: a thin pane spanning one horizontal axis, with no collision;
 * standing in it plays the swirling screen effect, and only when the effect
 * completes does the gate try to let the player in. Players without banked
 * time simply stay where they are, with the reason on the action bar. It cannot be mined in survival, so a gate found in the
 * wild cannot be carried home; operators place their own with the creative
 * item. Every gate block has a block entity, but only the bottom corner of a
 * gate (see {@link SafariGateBlockEntity}) looks after its roaming Pokémon,
 * and after its warden only for a generated gate: a block placed by hand
 * brings no warden.
 */
public class SafariGateBlock extends Block implements EntityBlock, Portal {

    public static final MapCodec<SafariGateBlock> CODEC = simpleCodec(SafariGateBlock::new);

    /** Horizontal axis the pane spans, as on the nether portal. */
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;

    /** Pane thickness in pixels, centred in the block. The block model must match. */
    private static final double PANE_MIN = 6.0D;
    private static final double PANE_MAX = 10.0D;

    private static final VoxelShape SHAPE_X = Block.box(0.0D, 0.0D, PANE_MIN, 16.0D, 16.0D, PANE_MAX);
    private static final VoxelShape SHAPE_Z = Block.box(PANE_MIN, 0.0D, 0.0D, PANE_MAX, 16.0D, 16.0D);

    /**
     * Particles of the spore blossom drifting around the pane: one floating
     * spore per pane block every this many client ticks on average, and a
     * falling blossom petal more rarely. The client calls animateTick about
     * once per tick for each nearby block, so a twelve-block gate shows
     * about six floating spores and one petal a second. A spore blossom
     * itself throws fourteen spores a tick over ten blocks: copied as is, a
     * gate would fill the plaza.
     */
    private static final int SPORE_ONE_IN = 2;
    private static final int PETAL_ONE_IN = 12;
    /**
     * How far the floating spores drift from the pane's plane, in blocks
     * each way. Within a block they read as coming from the pane.
     */
    private static final double SPORE_SPREAD = 1.0D;

    /**
     * Extra ticks the client keeps spinning after the countdown, so the swirl
     * does not stop a moment before the server's teleport arrives.
     */
    private static final int CLIENT_GRACE_TICKS = 20;

    /**
     * Client side: per player, the first and last game tick they touched a
     * pane. Weak keys, so players who leave the world are forgotten.
     */
    private static final Map<Player, long[]> CLIENT_WAITS = new WeakHashMap<>();

    /** Block with the given behaviour; see {@link SafariBlocks} for the actual properties. */
    public SafariGateBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(AXIS, Direction.Axis.X));
    }

    /** Codec required by the block registry since 1.20.5. */
    @Override
    protected MapCodec<SafariGateBlock> codec() {
        return CODEC;
    }

    /** The pane's only property: the axis it spans. */
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    /** Placed facing the player, so it reads as a doorway in front of them. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(AXIS, ctx.getHorizontalDirection().getClockWise().getAxis());
    }

    /** Thin pane along its axis. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(AXIS) == Direction.Axis.Z ? SHAPE_Z : SHAPE_X;
    }

    /**
     * Hides the faces between two panes of the same gate, as glass does:
     * through a translucent pane they would show as darker seams.
     */
    @Override
    protected boolean skipRendering(BlockState state, BlockState adjacent, Direction direction) {
        return adjacent.is(this) || super.skipRendering(state, adjacent, direction);
    }

    /**
     * Quarter turns swap the axis. Needed by structure placement: a gate
     * generated rotated would otherwise stand across its own doorway.
     */
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90, COUNTERCLOCKWISE_90 -> state.setValue(AXIS,
                    state.getValue(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
            default -> state;
        };
    }

    /**
     * Starts or continues the portal countdown, exactly like the nether
     * portal. Runs on BOTH sides on purpose: the client needs its own portal
     * state to draw the swirl and play the trigger sound. Only players: a
     * mob or an item has no Safari time to spend.
     */
    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (!(entity instanceof Player player) || !entity.canUsePortal(false)) {
            return;
        }
        if (level.isClientSide() && waitedPastCountdown(level, player)) {
            return;
        }
        entity.setAsInsidePortal(this, pos);
    }

    /**
     * Client side: whether the player has stood in the pane longer than the
     * countdown. The server stops a refused player's countdown through the
     * vanilla portal cooldown, but that cooldown only exists on the server:
     * without this, the client of a player with no Safari time would keep
     * spinning for as long as they stand in the pane. Stepping out (one tick
     * without touching a pane) starts over.
     */
    private static boolean waitedPastCountdown(Level level, Player player) {
        long now = level.getGameTime();
        long[] wait = CLIENT_WAITS.get(player);
        if (wait == null || now - wait[1] > 1) {
            wait = new long[] {now, now};
            CLIENT_WAITS.put(player, wait);
        } else {
            wait[1] = now;
        }
        return now - wait[0] > countdown(level, player) + CLIENT_GRACE_TICKS;
    }

    /**
     * Ticks a player must stand in the pane: the nether portal's own game
     * rules, so a server tuned for quick portals gets quick gates too.
     */
    @Override
    public int getPortalTransitionTime(ServerLevel level, Entity entity) {
        return entity instanceof Player player ? countdown(level, player) : 0;
    }

    /**
     * The nether portal's delay for this player. On the client the game rules
     * hold their default values (they are not synced), which is the common
     * case; a server with custom delays only shifts when the swirl fades.
     */
    private static int countdown(Level level, Player player) {
        return Math.max(1, level.getGameRules().getInt(player.getAbilities().invulnerable
                ? GameRules.RULE_PLAYERS_NETHER_PORTAL_CREATIVE_DELAY
                : GameRules.RULE_PLAYERS_NETHER_PORTAL_DEFAULT_DELAY));
    }

    /**
     * Called once the countdown completes. Entry goes through the mod's own
     * session start, which teleports the player itself (loaned balls, timer,
     * return point); vanilla is therefore always handed null, which it reads
     * as "nowhere to go". A refused player stays in the pane and, as with a
     * nether portal, has to step out and back in to try again.
     */
    @Nullable
    @Override
    public DimensionTransition getPortalDestination(ServerLevel level, Entity entity, BlockPos pos) {
        if (entity instanceof ServerPlayer player) {
            SafariGate.onPortalCompleted(player, level.getBlockState(pos), pos);
        }
        return null;
    }

    /** The nether portal's swirling screen and nausea-like wobble while waiting. */
    @Override
    public Portal.Transition getLocalTransition() {
        return Portal.Transition.CONFUSION;
    }

    /**
     * Spore blossom spores and petals, so the gate reads as active from a
     * distance. No sound: unlike the nether portal, the gate does not hum.
     */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(SPORE_ONE_IN) == 0) {
            // Spread across the pane's plane only, so the spores hang in front
            // of and behind the doorway rather than inside the posts.
            boolean alongX = state.getValue(AXIS) == Direction.Axis.X;
            double spread = (random.nextDouble() * 2.0D - 1.0D) * SPORE_SPREAD;
            double x = pos.getX() + random.nextDouble() + (alongX ? 0.0D : spread);
            double y = pos.getY() + random.nextDouble();
            double z = pos.getZ() + random.nextDouble() + (alongX ? spread : 0.0D);
            level.addParticle(ParticleTypes.SPORE_BLOSSOM_AIR, x, y, z, 0.0D, 0.0D, 0.0D);
        }
        if (random.nextInt(PETAL_ONE_IN) == 0) {
            level.addParticle(ParticleTypes.FALLING_SPORE_BLOSSOM, pos.getX() + random.nextDouble(),
                    pos.getY() + random.nextDouble(), pos.getZ() + random.nextDouble(), 0.0D, 0.0D, 0.0D);
        }
    }

    /** Every gate block has one; only the gate's bottom corner does any work. */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SafariGateBlockEntity(pos, state);
    }

    /** Server-side ticker for the gate's residents; nothing runs on the client. */
    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide() || type != SafariBlockEntities.safariGate()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<SafariGateBlockEntity>) SafariGateBlockEntity::serverTick;
    }

    /** Tells operators what the creative item does, which nothing else hints at. */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("block." + CobblemonSafari.MOD_ID + ".safari_gate.tooltip")
                .withStyle(ChatFormatting.GRAY));
    }
}
