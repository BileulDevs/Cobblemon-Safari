package dev.darcosse.forge.cobblemonsafari;

import dev.darcosse.common.cobblemonsafari.event.SafariServerEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import dev.darcosse.common.cobblemonsafari.loot.TicketLoot;
import net.neoforged.neoforge.event.LootTableLoadEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge game-bus handlers, each a one-line bridge to {@link SafariServerEvents}.
 */
public final class NeoForgeServerEvents {

    private NeoForgeServerEvents() {
    }

    /** Fired before the levels load, which the map reset needs. */
    static void onServerAboutToStart(ServerAboutToStartEvent event) {
        SafariServerEvents.onServerStarting(event.getServer());
    }

    /** All levels are loaded. */
    static void onServerStarted(ServerStartedEvent event) {
        SafariServerEvents.onServerStarted(event.getServer());
    }

    /**
     * Fired after a datapack reload with no player, and again per player on
     * login with that player. Only the reload matters here.
     */
    static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) {
            SafariServerEvents.onDataReload(event.getPlayerList().getServer());
        }
    }

    /**
     * A loot table is loading: adds the Safari Ticket pool to the tables the
     * server config lists, like Fabric's LootTableEvents.MODIFY.
     */
    static void onLootTableLoad(LootTableLoadEvent event) {
        TicketLoot.poolFor(event.getName()).ifPresent(pool -> event.getTable().addPool(pool.build()));
    }

    /** Post, like Fabric's END_SERVER_TICK, so both loaders see the same world state. */
    static void onServerTick(ServerTickEvent.Post event) {
        SafariServerEvents.onServerTick(event.getServer());
    }

    /** A player joined; packets only, see SafariServerEvents.onPlayerJoin. */
    static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SafariServerEvents.onPlayerJoin(player);
        }
    }

    /** A player died inside or outside the Safari. */
    static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SafariServerEvents.onPlayerDeath(player);
        }
    }

    /** Registers /safari. */
    static void onRegisterCommands(RegisterCommandsEvent event) {
        SafariServerEvents.onRegisterCommands(event.getDispatcher());
    }

    /** Cancels damage the Safari rules forbid (players inside, gate residents, clerks). */
    static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (SafariServerEvents.onIncomingDamage(event.getEntity(), event.getSource())) {
            event.setCanceled(true);
        }
    }

    /**
     * Server side, when the block would actually break. Mining itself is not
     * refused (no LeftClickBlock listener): refusing every left click would
     * spam the warning each time a player hit a block. The player sees the cracks,
     * then the block stays and the warning shows once.
     */
    static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (SafariServerEvents.onBlockBreak(event.getPlayer(), event.getState())) {
            event.setCanceled(true);
        }
    }

    /** Server side; backstop for any placement the click check missed. */
    static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player player
                && SafariServerEvents.onBlockPlace(player, event.getPlacedBlock())) {
            event.setCanceled(true);
        }
    }

    /** Both sides; placement and block-transforming tools. */
    static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (SafariServerEvents.onUseItemOnBlock(event.getEntity(), event.getItemStack(),
                event.getLevel().getBlockState(event.getPos()))) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    /** Fired on both logical sides, like Fabric's UseItemCallback. */
    static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (SafariServerEvents.onItemUse(event.getEntity(), event.getItemStack())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }
}
