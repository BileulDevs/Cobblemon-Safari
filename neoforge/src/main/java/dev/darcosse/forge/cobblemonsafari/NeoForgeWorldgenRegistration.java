package dev.darcosse.forge.cobblemonsafari;

import dev.darcosse.common.cobblemonsafari.worldgen.SafariWorldgen;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Registers the Safari worldgen types on NeoForge.
 *
 * <p>{@link RegisterEvent} fires once per registry; the registry key check is
 * done by {@code RegisterEvent#register} itself, so this is a no-op for every
 * other registry.
 */
public final class NeoForgeWorldgenRegistration {

    private NeoForgeWorldgenRegistration() {
    }

    /** Mod-bus handler; must run before datapacks load, which RegisterEvent guarantees. */
    static void onRegister(RegisterEvent event) {
        SafariWorldgen.registerDensityFunctionTypes((id, codec) ->
                event.register(Registries.DENSITY_FUNCTION_TYPE, id, () -> codec));
        SafariWorldgen.registerStructureTypes((id, type) ->
                event.register(Registries.STRUCTURE_TYPE, id, () -> type));
    }
}
