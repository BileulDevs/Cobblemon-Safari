package dev.darcosse.common.cobblemonsafari.event;

import com.mojang.brigadier.CommandDispatcher;
import dev.darcosse.common.cobblemonsafari.structure.RestHouseClerk;
import dev.darcosse.common.cobblemonsafari.capture.CaptureGuard;
import dev.darcosse.common.cobblemonsafari.command.SafariCommands;
import dev.darcosse.common.cobblemonsafari.park.SafariBorder;
import dev.darcosse.common.cobblemonsafari.bounty.SafariBounties;
import dev.darcosse.common.cobblemonsafari.gate.GateResidents;
import dev.darcosse.common.cobblemonsafari.protection.SafariProtection;
import dev.darcosse.common.cobblemonsafari.reset.SafariMapReset;
import dev.darcosse.common.cobblemonsafari.world.SafariWorldState;
import dev.darcosse.common.cobblemonsafari.session.SafariSessionManager;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedRotation;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedSpawner;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedSync;
import dev.darcosse.common.cobblemonsafari.spawn.SafariSpawnBoost;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The only surface loaders talk to. Each loader module translates its own
 * events into these calls, so the behaviour is written once and cannot drift
 * between Fabric and NeoForge.
 */
public final class SafariServerEvents {

    private SafariServerEvents() {
    }

    /** Before any level loads: the only moment the Safari map can be reset safely. */
    public static void onServerStarting(MinecraftServer server) {
        SafariMapReset.onServerStarting(server);
    }

    /** All levels are loaded; the Safari border can be applied. */
    public static void onServerStarted(MinecraftServer server) {
        // Single player keeps these classes loaded from one world to the next:
        // without this, a second world would show the first world's specials.
        FeaturedRotation.invalidate();
        FeaturedSync.reset();
        SafariBounties.reset();
        SafariSpawnBoost.reset();
        SafariBorder.apply(server);
        // A reset map has no rest houses yet, whatever the save says.
        SafariWorldState.get(server).forgetHousesIfMapChanged(SafariMapReset.salt());
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
        SafariBounties.tick(server);
        SafariSpawnBoost.tick(server);
        RestHouseClerk.tick(server);
    }

    /** A player finished joining and can receive custom payloads. */
    public static void onPlayerJoin(ServerPlayer player) {
        SafariMapReset.onPlayerJoin(player);
        SafariSessionManager.onPlayerJoin(player);
        FeaturedSync.sendTo(player);
        SafariBounties.sendTo(player);
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
     * A player is about to break a block: mining is complete, the block would
     * go now. Server side. Mining itself is allowed, so the warning shows once
     * per block rather than on every left click.
     *
     * @return true when the loader must cancel it
     */
    public static boolean onBlockBreak(Player player, BlockState state) {
        return SafariProtection.shouldBlockBreak(player, state);
    }

    /**
     * A player placed a block (server side, after the fact; cancelling reverts it).
     *
     * @return true when the loader must cancel the placement
     */
    public static boolean onBlockPlace(Player player, BlockState placed) {
        return SafariProtection.shouldBlockPlace(player, placed);
    }

    /**
     * A player right-clicks a block, with or without an item, on EITHER logical side.
     *
     * @param clicked the block right-clicked
     * @return true when the loader must cancel the interaction
     */
    public static boolean onUseItemOnBlock(Player player, ItemStack stack, BlockState clicked) {
        return SafariProtection.shouldBlockUseOn(player, stack, clicked);
    }

    /**
     * A living entity is about to take damage (server side).
     *
     * @return true when the loader must cancel the damage
     */
    public static boolean onIncomingDamage(LivingEntity entity, DamageSource source) {
        return SafariProtection.shouldCancelDamage(entity, source) || GateResidents.shouldCancelDamage(entity, source)
                || RestHouseClerk.shouldCancelDamage(entity, source);
    }
}
