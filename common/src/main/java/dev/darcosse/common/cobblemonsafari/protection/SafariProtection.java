package dev.darcosse.common.cobblemonsafari.protection;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.client.SafariClientState;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
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

/**
 * Decides what is forbidden inside the Safari: taking damage, and changing
 * the terrain (by hand, by fire, or by explosion).
 *
 * <p>Like {@code CaptureGuard}, the terrain checks run on BOTH logical sides:
 * the server decides from its config, the client from the rules the server
 * sent. Refusing on the client too avoids ghost blocks (a block that vanishes
 * then reappears) and stops the mining animation from even starting.
 *
 * <p>Interactions that do not change blocks stay allowed on purpose: opening
 * doors and chests, Cobblemon's PC and healing machine, sitting, riding.
 */
public final class SafariProtection {

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
     * Whether breaking a block (or starting to mine it) must be refused.
     * Tells the player why.
     */
    public static boolean shouldBlockBreak(Player player) {
        return deny(player, isTerrainProtectedFor(player));
    }

    /**
     * Whether a block placement by a player must be refused. Server-side
     * backstop for loaders that report placements separately from clicks.
     */
    public static boolean shouldBlockPlace(Player player) {
        return deny(player, isTerrainProtectedFor(player));
    }

    /**
     * Whether using an item ON a block must be refused: placing it, or a tool
     * that transforms the block. Other right-clicks on blocks stay allowed.
     */
    public static boolean shouldBlockUseOn(Player player, ItemStack stack) {
        return deny(player, isTerrainChanging(stack.getItem()) && isTerrainProtectedFor(player));
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
        return SafariConfigManager.get().protection.preventFireSpread && SafariDimensions.isSafari(level);
    }

    /**
     * Whether an explosion must leave blocks intact. Server side only: the
     * client replays the block list the server sends, which is then empty.
     */
    public static boolean shouldKeepBlocksFromExplosion(Level level) {
        return !level.isClientSide()
                && SafariConfigManager.get().protection.preventExplosionBlockDamage
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
