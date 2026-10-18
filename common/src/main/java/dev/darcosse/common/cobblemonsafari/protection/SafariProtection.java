package dev.darcosse.common.cobblemonsafari.protection;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.client.SafariClientState;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Decides what is forbidden inside the Safari: taking damage, and changing
 * the terrain (by hand, by fire, or by explosion).
 *
 * <p>Like {@code CaptureGuard}, the terrain checks run on BOTH logical sides:
 * the server decides from its config, the client from the rules the server
 * sent. Refusing on the client too avoids ghost blocks (a block that vanishes
 * then reappears) for placements and tools. Breaking is the exception: it is
 * refused only when the block would actually break, server side, so that
 * mining shows its cracks and the warning appears once instead of on every
 * left click.
 *
 * <p>Interactions that do not change blocks stay allowed on purpose: opening
 * doors and chests, Cobblemon's PC, healing machine and Poké Pot, sitting,
 * riding. The exception is Cobblemon's disc shelf, whose Technical Machines
 * are decoration of the rest houses, not loot.
 */
public final class SafariProtection {

    private static final ResourceLocation COBBLEMON_CAMPFIRE =
            ResourceLocation.fromNamespaceAndPath("cobblemon", "campfire");
    private static final ResourceLocation COBBLEMON_SOUL_CAMPFIRE =
            ResourceLocation.fromNamespaceAndPath("cobblemon", "soul_campfire");

    /**
     * Cobblemon blocks whose right-click opens something and always consumes
     * the click, so a block item in hand is never placed against them. Adding
     * a block that can return PASS would let a block in hand be placed
     * against it (Fabric has no separate placement check to catch it).
     */
    private static final Set<ResourceLocation> STATIONS = Set.of(
            COBBLEMON_CAMPFIRE,
            COBBLEMON_SOUL_CAMPFIRE,
            ResourceLocation.fromNamespaceAndPath("cobblemon", "pc"),
            ResourceLocation.fromNamespaceAndPath("cobblemon", "healing_machine"));

    /**
     * Cobblemon's disc shelf, which holds Technical Machines: the rest houses
     * display some on theirs. Right-clicking takes a disc out, so in the
     * Safari any click on it is refused, which also keeps players from
     * filling it.
     */
    private static final ResourceLocation COBBLEMON_DISC_SHELF =
            ResourceLocation.fromNamespaceAndPath("cobblemon", "disc_shelf");

    private SafariProtection() {
    }

    /**
     * Whether a hit must be cancelled. Server side only.
     *
     * <p>Damage tagged {@code bypasses_invulnerability} (/kill, the void) goes
     * through: blocking it would leave a player falling forever below the world.
     */
    public static boolean shouldCancelDamage(LivingEntity entity, DamageSource source) {
        return entity instanceof ServerPlayer player
                && SafariConfigManager.get().protection.invulnerablePlayers
                && SafariDimensions.isSafari(player.level())
                && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    /**
     * Whether breaking a block must be refused, at the moment it would break.
     * Tells the player why. Placeable blocks (baits) can be picked up again.
     */
    public static boolean shouldBlockBreak(Player player, BlockState state) {
        return deny(player, !isPlaceable(player, state.getBlock()) && isTerrainProtectedFor(player));
    }

    /**
     * Whether a block placement by a player must be refused. Server-side
     * backstop for loaders that report placements separately from clicks.
     */
    public static boolean shouldBlockPlace(Player player, BlockState placed) {
        return deny(player, !isPlaceable(player, placed.getBlock()) && isTerrainProtectedFor(player));
    }

    /**
     * Whether a right-click on a block must be refused: placing a block, or a
     * tool that transforms the block. Other right-clicks stay allowed, and so
     * does placing a block of {@code protection.placeableBlocks}.
     *
     * <p>Cobblemon stations ({@link #STATIONS}) are special. A click that
     * opens them must go through whatever the player holds: refusing it
     * because the hand holds a berry (a block item) would keep the Poké Pot,
     * the PC or the Healing Machine shut. And a crouching click on a campfire
     * is refused, because it takes the rest house's Poké Pot off.
     *
     * @param clicked the block right-clicked
     */
    public static boolean shouldBlockUseOn(Player player, ItemStack stack, BlockState clicked) {
        if (isDiscShelf(clicked) && isTerrainProtectedFor(player)) {
            player.displayClientMessage(Component.translatable(CobblemonSafari.MOD_ID + ".message.shelf_locked"), true);
            return true;
        }
        if (isStation(clicked) && isTerrainProtectedFor(player)) {
            if (player.isSecondaryUseActive() && isCampfire(clicked)) {
                return deny(player, true);
            }
            // Vanilla lets the block handle the click unless the player
            // crouches with something in hand; every station then consumes it,
            // so nothing gets placed.
            boolean handsEmpty = player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty();
            if (!player.isSecondaryUseActive() || handsEmpty) {
                return false;
            }
        }
        boolean placeable = stack.getItem() instanceof BlockItem blockItem
                && isPlaceable(player, blockItem.getBlock());
        return deny(player, !placeable && isTerrainChanging(stack.getItem()) && isTerrainProtectedFor(player));
    }

    /** Whether a block is Cobblemon's disc shelf. */
    private static boolean isDiscShelf(BlockState state) {
        return COBBLEMON_DISC_SHELF.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    /** Whether a block is one of {@link #STATIONS}. */
    private static boolean isStation(BlockState state) {
        return STATIONS.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    /** Whether a block is a Cobblemon cooking campfire (the one holding a Poké Pot). */
    private static boolean isCampfire(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id.equals(COBBLEMON_CAMPFIRE) || id.equals(COBBLEMON_SOUL_CAMPFIRE);
    }

    /**
     * Whether a block is on the placeable list, read from the server config
     * or, on the client, from the rules the server sent.
     */
    private static boolean isPlaceable(Player player, Block block) {
        Set<ResourceLocation> placeable = player.level().isClientSide()
                ? SafariClientState.placeableBlocks()
                : SafariConfigManager.placeableBlocks();
        return !placeable.isEmpty() && placeable.contains(BuiltInRegistries.BLOCK.getKey(block));
    }

    /**
     * Whether using an item in the air must be refused. Only buckets matter:
     * they pick up or pour fluid through a ray trace, without a block click.
     */
    public static boolean shouldBlockItemUse(Player player, ItemStack stack) {
        return deny(player, stack.getItem() instanceof BucketItem && isTerrainProtectedFor(player));
    }

    /** Whether fire must go out instead of spreading. Server side, called from the fire block tick. */
    public static boolean shouldExtinguishFire(Level level) {
        return SafariConfigManager.get().protection.protectBlocks && SafariDimensions.isSafari(level);
    }

    /**
     * Whether an explosion must leave blocks intact. Server side only: the
     * client replays the block list the server sends, which is then empty.
     */
    public static boolean shouldKeepBlocksFromExplosion(Level level) {
        return !level.isClientSide()
                && SafariConfigManager.get().protection.protectBlocks
                && SafariDimensions.isSafari(level);
    }

    /**
     * Items whose use on a block changes the terrain. BlockItem covers every
     * placeable, including powder snow buckets and Cobblemon's placeable items
     * (apricorn seeds, Poké Snacks). The tool list mirrors the vanilla items
     * with a block-transforming {@code useOn}.
     */
    private static boolean isTerrainChanging(Item item) {
        return item instanceof BlockItem
                || item instanceof BucketItem
                || item instanceof FlintAndSteelItem
                || item instanceof FireChargeItem
                || item instanceof BoneMealItem
                || item instanceof HoeItem
                || item instanceof ShovelItem
                || item instanceof AxeItem;
    }

    private static boolean isTerrainProtectedFor(Player player) {
        Level level = player.level();
        boolean protectBlocks;
        boolean exemptCreative;
        if (level.isClientSide()) {
            ResourceLocation safari = SafariClientState.safariDimension();
            // No rules yet: let the server decide rather than guess.
            if (safari == null || !level.dimension().location().equals(safari)) {
                return false;
            }
            protectBlocks = SafariClientState.protectBlocks();
            exemptCreative = SafariClientState.exemptCreativeAndSpectator();
        } else {
            if (!SafariDimensions.isSafari(level)) {
                return false;
            }
            SafariConfig config = SafariConfigManager.get();
            protectBlocks = config.protection.protectBlocks;
            exemptCreative = config.exemptCreativeAndSpectator;
        }
        boolean exempt = exemptCreative && (player.isCreative() || player.isSpectator());
        return protectBlocks && !exempt;
    }

    private static boolean deny(Player player, boolean denied) {
        if (denied) {
            player.displayClientMessage(
                    Component.translatable(CobblemonSafari.MOD_ID + ".message.protected"), true);
        }
        return denied;
    }
}
