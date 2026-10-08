package dev.darcosse.forge.cobblemonsafari;

import dev.darcosse.common.cobblemonsafari.event.SafariServerEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge game-bus handlers, each a one-line bridge to {@link SafariServerEvents}.
 */
public final class NeoForgeServerEvents {

    private NeoForgeServerEvents() {
    }

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

    /** Post, like Fabric's END_SERVER_TICK, so both loaders see the same world state. */
    static void onServerTick(ServerTickEvent.Post event) {
        SafariServerEvents.onServerTick(event.getServer());
    }

    static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SafariServerEvents.onPlayerJoin(player);
        }
    }

    static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SafariServerEvents.onPlayerDeath(player);
        }
    }

    static void onRegisterCommands(RegisterCommandsEvent event) {
        SafariServerEvents.onRegisterCommands(event.getDispatcher());
    }

    static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (SafariServerEvents.onIncomingDamage(event.getEntity(), event.getSource())) {
            event.setCanceled(true);
        }
    }

    /** Server side; the server-side guarantee for breaking. */
    static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (SafariServerEvents.onBlockBreak(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    /** Server side; backstop for any placement the click check missed. */
    static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player player && SafariServerEvents.onBlockPlace(player)) {
            event.setCanceled(true);
        }
    }

    /** Both sides, when mining starts: no animation, no ghost block. */
    static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (SafariServerEvents.onBlockBreak(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** Both sides; placement and block-transforming tools. */
    static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (SafariServerEvents.onUseItemOnBlock(event.getEntity(), event.getItemStack())) {
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
