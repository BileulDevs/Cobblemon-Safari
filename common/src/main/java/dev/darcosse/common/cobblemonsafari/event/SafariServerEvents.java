package dev.darcosse.common.cobblemonsafari.event;

import dev.darcosse.common.cobblemonsafari.network.SafariNetwork;
import com.mojang.brigadier.CommandDispatcher;
import dev.darcosse.common.cobblemonsafari.structure.RestHouseStaff;
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
import dev.darcosse.common.cobblemonsafari.spawn.SafariSpawnFilter;
import dev.darcosse.common.cobblemonsafari.spawn.SpecialSpawnCounter;
import dev.darcosse.common.cobblemonsafari.structure.RestHousePrebuilder;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import dev.darcosse.common.cobblemonsafari.teleport.RestHouseTravel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The only surface loaders talk to. Each loader module translates its own
 * events into these calls, so the behaviour is written once and cannot drift
 * between Fabric and NeoForge.
 */
public final class SafariServerEvents {

    /**
     * Players who joined since the last tick ended, waiting to be relocated
     * if needed. Server thread only. Cleared every tick, so it never holds
     * more than one tick's logins, and a player of a previous world (single
     * player) at most until the first tick of the next one, where they are
     * not online and are skipped.
     */
    private static final Set<UUID> JOINED = new LinkedHashSet<>();

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
        FeaturedRotation.attach(server);
        FeaturedSync.reset();
        SafariBounties.reset();
        SafariSpawnBoost.reset();
        SpecialSpawnCounter.reset();
        RestHouseTravel.reset();
        RestHousePrebuilder.reset();
        SafariBorder.apply(server);
        // A reset map has no rest houses yet, whatever the save says.
        SafariWorldState.get(server).forgetHousesIfMapChanged(SafariMapReset.salt());
        RestHouses.reset();
        RestHouses.prepare(server);
    }

    /**
     * Datapacks were reloaded: spawn files, biome tags or species labels may
     * have changed, so the habitat index, today's specials and the natural
     * spawn filter are rebuilt on next use.
     */
    public static void onDataReload(MinecraftServer server) {
        FeaturedRotation.invalidate();
        SafariSpawnFilter.invalidate();
    }

    /** End of every server tick. */
    public static void onServerTick(MinecraftServer server) {
        settleJoinedPlayers(server);
        SafariSessionManager.tick(server);
        FeaturedSpawner.tick(server);
        SpecialSpawnCounter.tick(server);
        FeaturedSync.tick(server);
        SafariBounties.tick(server);
        SafariSpawnBoost.tick(server);
        RestHouses.tick(server);
        RestHouseStaff.tick(server);
        RestHouseTravel.tick(server);
        RestHousePrebuilder.tick(server);
    }

    /**
     * A player is joining and can receive custom payloads. Only packets are
     * sent here; anything that moves the player waits for the end of the
     * tick ({@link #settleJoinedPlayers}).
     *
     * <p>Why: Fabric fires its JOIN event in the middle of vanilla's
     * {@code PlayerList.placeNewPlayer}, before the player is added to its
     * level. A teleport to another dimension at that point would register the
     * player in the target level, after which vanilla adds them to the
     * original level as well; the chunk tracking of the two disagrees and
     * the next dimension change crashes the server (NullPointerException in
     * {@code DistanceManager.removePlayer}). NeoForge's event comes later,
     * but sharing one path keeps both loaders identical.
     */
    public static void onPlayerJoin(ServerPlayer player) {
        SafariSessionManager.onPlayerJoin(player);
        FeaturedSync.sendTo(player);
        SafariBounties.sendTo(player);
        SafariNetwork.sendStats(player);
        JOINED.add(player.getUUID());
    }

    /**
     * Relocates the players who joined during this tick, now that each is
     * fully placed in its level: out of the Safari without a session, or to
     * the arrival point when their map is gone. Cost: nothing on ticks
     * without a login.
     */
    private static void settleJoinedPlayers(MinecraftServer server) {
        if (JOINED.isEmpty()) {
            return;
        }
        // Copy: a relocation may in principle trigger another login callback.
        List<UUID> joined = new ArrayList<>(JOINED);
        JOINED.clear();
        for (UUID id : joined) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                // Disconnected within the same tick.
                continue;
            }
            // Sessions first: a player without one is sent out, and the map
            // reset's relocation then has nothing to do.
            SafariSessionManager.settleJoined(player);
            SafariMapReset.onPlayerJoin(player);
        }
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
                || RestHouseStaff.shouldCancelDamage(entity, source);
    }
}
