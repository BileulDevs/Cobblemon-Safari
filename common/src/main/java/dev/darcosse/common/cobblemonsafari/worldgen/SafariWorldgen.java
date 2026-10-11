package dev.darcosse.common.cobblemonsafari.worldgen;

import com.mojang.serialization.MapCodec;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.StructureType;

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

    /** Id of {@link DryJigsawStructure}'s type; referenced by the structure JSON of the gate, ruins and towers. */
    public static final ResourceLocation DRY_JIGSAW = CobblemonSafari.id("dry_jigsaw");

    /**
     * The structure type instance. A structure reports its type by identity,
     * so the registered object and the one {@link DryJigsawStructure#type()}
     * returns must be this same instance.
     */
    private static final StructureType<DryJigsawStructure> DRY_JIGSAW_TYPE = () -> DryJigsawStructure.CODEC;

    private SafariWorldgen() {
    }

    /** The registered structure type of {@link DryJigsawStructure}. */
    static StructureType<DryJigsawStructure> dryJigsawType() {
        return DRY_JIGSAW_TYPE;
    }

    /** Hands every structure type to the loader's registrar. Same timing constraint as the density functions. */
    public static void registerStructureTypes(BiConsumer<ResourceLocation, StructureType<?>> registrar) {
        registrar.accept(DRY_JIGSAW, DRY_JIGSAW_TYPE);
    }

    /** Hands every density function type to the loader's registrar. */
    public static void registerDensityFunctionTypes(
            BiConsumer<ResourceLocation, MapCodec<? extends DensityFunction>> registrar) {
        registrar.accept(ISLAND_COORDINATE, IslandCoordinateFunction.CODEC.codec());
    }
}
