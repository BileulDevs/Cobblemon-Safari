package dev.darcosse.common.cobblemonsafari.mixin;

import dev.darcosse.common.cobblemonsafari.worldgen.SafariStructureFilter;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Drops the structure sets the Safari refuses ({@link SafariStructureFilter})
 * from the Safari's chunk generator.
 *
 * <p>{@code hasBiomesForStructureSet} is the filter vanilla already applies
 * when it lists the sets a dimension can place (a set is kept when one of
 * its structures may appear in one of the dimension's biomes); answering
 * "no" there removes the set for the whole level, placement lookups and
 * {@code /locate} included, at no cost per chunk. Other dimensions are
 * untouched: the filter only answers for the Safari's biome source.
 */
@Mixin(ChunkGeneratorStructureState.class)
public abstract class ChunkGeneratorStructureStateMixin {

    /** Refuses the Safari's excluded structure sets before vanilla's biome check. */
    @Inject(method = "hasBiomesForStructureSet", at = @At("HEAD"), cancellable = true)
    private static void cobblemonSafari$refuseInSafari(StructureSet set, BiomeSource biomeSource,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (SafariStructureFilter.refuses(set, biomeSource)) {
            cir.setReturnValue(false);
        }
    }
}
