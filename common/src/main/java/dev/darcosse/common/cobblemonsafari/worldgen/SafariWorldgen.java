package dev.darcosse.common.cobblemonsafari.worldgen;

import com.mojang.serialization.MapCodec;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.function.BiConsumer;

/**
 * Java-side worldgen types the Safari data depends on.
 *
 * <p>Registration itself is loader-specific (Fabric registers directly,
 * NeoForge through {@code RegisterEvent}); this class only lists what to
 * register, so both loaders stay in sync. The types MUST be registered before
 * datapacks load, otherwise the Safari dimension fails to parse and the world
 * refuses to open.
 */
public final class SafariWorldgen {

    /** Id of {@link IslandCoordinateFunction}; referenced by the generated JSON. */
    public static final ResourceLocation ISLAND_COORDINATE = CobblemonSafari.id("island_coordinate");

    private SafariWorldgen() {
    }

    /** Hands every density function type to the loader's registrar. */
    public static void registerDensityFunctionTypes(
            BiConsumer<ResourceLocation, MapCodec<? extends DensityFunction>> registrar) {
        registrar.accept(ISLAND_COORDINATE, IslandCoordinateFunction.CODEC.codec());
    }
}
