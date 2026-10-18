package dev.darcosse.fabric.cobblemonsafari;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.event.SafariServerEvents;
import dev.darcosse.common.cobblemonsafari.block.SafariBlockEntities;
import dev.darcosse.common.cobblemonsafari.block.SafariBlocks;
import dev.darcosse.common.cobblemonsafari.item.SafariCreativeTab;
import dev.darcosse.common.cobblemonsafari.item.SafariItems;
import dev.darcosse.common.cobblemonsafari.loot.TicketLoot;
import dev.darcosse.common.cobblemonsafari.worldgen.SafariWorldgen;
import dev.darcosse.fabric.cobblemonsafari.network.FabricSafariNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.item.ItemStack;

/**
 * Fabric entry point (common side, runs on client and dedicated server).
 *
 * <p>Only translates Fabric events into {@link SafariServerEvents} calls; any
 * logic added here would have to be duplicated in the NeoForge module.
 */
public class CobblemonSafariFabric implements ModInitializer {

    /** Wires the common code to Fabric's events. */
    @Override
    public void onInitialize() {
        CobblemonSafari.init(new FabricPlatformAdapter());
        // Before datapacks load: the Safari dimension JSON references this type
        // and the world refuses to open if it is missing.
        SafariWorldgen.registerDensityFunctionTypes((id, codec) ->
                Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, id, codec));
        // Same constraint: the gate, ruin and tower structures use this type.
        SafariWorldgen.registerStructureTypes((id, type) ->
                Registry.register(BuiltInRegistries.STRUCTURE_TYPE, id, type));
        // Blocks first: the board's block item wraps the block.
        SafariBlocks.register((id, block) -> Registry.register(BuiltInRegistries.BLOCK, id, block));
        // Built here, not in common: BlockEntitySupplier is only public through
        // Fabric's transitive access widener (see SafariBlockEntities.TypeFactory).
        SafariBlockEntities.register(
                (constructor, block) -> BlockEntityType.Builder.of(constructor::apply, block).build(null),
                (id, type) -> Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id, type));
        SafariItems.register((id, item) -> Registry.register(BuiltInRegistries.ITEM, id, item));
        // The mod's own tab, after the items it lists.
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, SafariCreativeTab.ID,
                SafariCreativeTab.build(FabricItemGroup.builder()));
        FabricSafariNetworking.registerPayloadTypes();

        // STARTING fires before the levels load, which the map reset needs.
        ServerLifecycleEvents.SERVER_STARTING.register(SafariServerEvents::onServerStarting);
        ServerLifecycleEvents.SERVER_STARTED.register(SafariServerEvents::onServerStarted);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) ->
                SafariServerEvents.onDataReload(server));
        ServerTickEvents.END_SERVER_TICK.register(SafariServerEvents::onServerTick);
        // Every source (built-in or data pack): a pack replacing a village
        // table still gets its tickets, as the server config asks.
        LootTableEvents.MODIFY.register((key, tableBuilder, source, registries) ->
                TicketLoot.poolFor(key.location()).ifPresent(tableBuilder::withPool));

        // JOIN, not INIT: payloads can only be sent once the play channel is ready.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                SafariServerEvents.onPlayerJoin(handler.getPlayer()));

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer player) {
                SafariServerEvents.onPlayerDeath(player);
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                SafariServerEvents.onRegisterCommands(dispatcher));

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !SafariServerEvents.onIncomingDamage(entity, source));

        // Server side, when the block would actually break. Mining itself is not
        // refused (no AttackBlockCallback): refusing every left click spammed
        // the warning each time a player hit a block, e.g. to punch a Pokémon
        // standing in front of it. The player sees the cracks, then the block
        // stays and the warning shows once.
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
                !SafariServerEvents.onBlockBreak(player, state));
        // Both sides; placement and block-transforming tools. Fabric has no
        // separate placement event, so this is also the server-side guarantee.
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) ->
                SafariServerEvents.onUseItemOnBlock(player, player.getItemInHand(hand),
                        level.getBlockState(hitResult.getBlockPos()))
                        ? InteractionResult.FAIL
                        : InteractionResult.PASS);

        // Fired on both sides. Returning FAIL on the client means the use packet
        // is never sent, which is why the client needs the rules too.
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            return SafariServerEvents.onItemUse(player, stack)
                    ? InteractionResultHolder.fail(stack)
                    : InteractionResultHolder.pass(stack);
        });
    }
}
