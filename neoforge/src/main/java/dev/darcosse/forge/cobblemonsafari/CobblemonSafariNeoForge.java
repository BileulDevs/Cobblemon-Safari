package dev.darcosse.forge.cobblemonsafari;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.forge.cobblemonsafari.client.NeoForgeClientSetup;
import dev.darcosse.forge.cobblemonsafari.network.NeoForgeSafariNetworking;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge entry point.
 *
 * <p>Only translates NeoForge events into common calls (see
 * {@link NeoForgeServerEvents}); any logic added here would have to be
 * duplicated in the Fabric module.
 */
@Mod(CobblemonSafari.MOD_ID)
public class CobblemonSafariNeoForge {

    /** Wires the common code to NeoForge's buses. */
    public CobblemonSafariNeoForge(IEventBus modEventBus) {
        CobblemonSafari.init(new NeoForgePlatformAdapter());
        modEventBus.addListener(NeoForgeSafariNetworking::register);
        modEventBus.addListener(NeoForgeWorldgenRegistration::onRegister);
        modEventBus.addListener(NeoForgeItemRegistration::onRegister);

        NeoForge.EVENT_BUS.addListener(NeoForgeServerEvents::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(NeoForgeServerEvents::onServerStarted);
        NeoForge.EVENT_BUS.addListener(NeoForgeServerEvents::onDatapackSync);
        NeoForge.EVENT_BUS.addListener(NeoForgeServerEvents::onLootTableLoad);
        NeoForge.EVENT_BUS.addListener(NeoForgeServerEvents::onServerTick);
        NeoForge.EVENT_BUS.addListener(NeoForgeServerEvents::onPlayerLoggedIn);
        // LOWEST so another mod that cancels the death (totem-like effects) runs
        // first; cancelled deaths are then not delivered to us at all.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, NeoForgeServerEvents::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(NeoForgeServerEvents::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(NeoForgeServerEvents::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(NeoForgeServerEvents::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(NeoForgeServerEvents::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(NeoForgeServerEvents::onBlockPlace);
        NeoForge.EVENT_BUS.addListener(NeoForgeServerEvents::onRightClickBlock);

        // The client class is only touched inside this branch, so a dedicated
        // server never loads it (it references GuiGraphics).
        if (FMLEnvironment.dist == Dist.CLIENT) {
            NeoForgeClientSetup.init(modEventBus);
        }
    }
}
