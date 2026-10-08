package dev.darcosse.common.cobblemonsafari.event;

import com.mojang.brigadier.CommandDispatcher;
import dev.darcosse.common.cobblemonsafari.capture.CaptureGuard;
import dev.darcosse.common.cobblemonsafari.command.SafariCommands;
import dev.darcosse.common.cobblemonsafari.park.SafariBorder;
import dev.darcosse.common.cobblemonsafari.protection.SafariProtection;
import dev.darcosse.common.cobblemonsafari.session.SafariSessionManager;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedRotation;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedSpawner;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedSync;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The only surface loaders talk to. Each loader module translates its own
 * events into these calls, so the behaviour is written once and cannot drift
 * between Fabric and NeoForge.
 */
public final class SafariServerEvents {

    private SafariServerEvents() {
    }

    /** All levels are loaded; the Safari border can be applied. */
    public static void onServerStarted(MinecraftServer server) {
        // Single player keeps these classes loaded from one world to the next:
        // without this, a second world would show the first world's specials.
        FeaturedRotation.invalidate();
        FeaturedSync.reset();
        SafariBorder.apply(server);
        RestHouses.ensureBuilt(server);
    }

    /**
     * Datapacks were reloaded: spawn files or biome tags may have changed, so
     * the habitat index and today's specials are rebuilt on next use.
     */
    public static void onDataReload(MinecraftServer server) {
        FeaturedRotation.invalidate();
    }

    /** End of every server tick. */
    public static void onServerTick(MinecraftServer server) {
        SafariSessionManager.tick(server);
        FeaturedSpawner.tick(server);
        FeaturedSync.tick(server);
    }

    /** A player finished joining and can receive custom payloads. */
    public static void onPlayerJoin(ServerPlayer player) {
        SafariSessionManager.onPlayerJoin(player);
        FeaturedSync.sendTo(player);
    }

    /** A player died (not cancelled). */
    public static void onPlayerDeath(ServerPlayer player) {
        SafariSessionManager.onPlayerDeath(player);
    }

    /** Command registration. */
    public static void onRegisterCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        SafariCommands.register(dispatcher);
    }

    /**
     * A player is about to use an item, on EITHER logical side.
     *
     * @return true when the loader must cancel the use
     */
    public static boolean onItemUse(Player player, ItemStack stack) {
        return CaptureGuard.shouldBlockUse(player, stack) || SafariProtection.shouldBlockItemUse(player, stack);
    }

    /**
     * A player is about to break a block, or to start mining it (both sides).
     *
     * @return true when the loader must cancel it
     */
    public static boolean onBlockBreak(Player player) {
        return SafariProtection.shouldBlockBreak(player);
    }

    /**
     * A player placed a block (server side, after the fact; cancelling reverts it).
     *
     * @return true when the loader must cancel the placement
     */
    public static boolean onBlockPlace(Player player) {
        return SafariProtection.shouldBlockPlace(player);
    }

    /**
     * A player right-clicks a block with an item, on EITHER logical side.
     *
     * @return true when the loader must cancel the interaction
     */
    public static boolean onUseItemOnBlock(Player player, ItemStack stack) {
        return SafariProtection.shouldBlockUseOn(player, stack);
    }

    /**
     * A living entity is about to take damage (server side).
     *
     * @return true when the loader must cancel the damage
     */
    public static boolean onIncomingDamage(LivingEntity entity, DamageSource source) {
        return SafariProtection.shouldCancelDamage(entity, source);
    }
}
