package dev.darcosse.common.cobblemonsafari.mixin;

import dev.darcosse.common.cobblemonsafari.worldgen.SafariFeatureFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuses, in the Safari, the features tagged
 * {@code cobblemon_safari:not_in_safari}: see {@link SafariFeatureFilter}.
 *
 * <p>{@code placeWithBiomeCheck} is the entry the chunk generator uses to
 * decorate a chunk with its biomes' features; {@code place}, used by
 * commands and by features nested in others, is left alone so that
 * {@code /place feature} still works for testing.
 */
@Mixin(PlacedFeature.class)
public abstract class PlacedFeatureMixin {

    /** Cancels the placement of a refused feature, reported as not placed. */
    @Inject(method = "placeWithBiomeCheck", at = @At("HEAD"), cancellable = true)
    private void cobblemonSafari$refuseInSafari(WorldGenLevel level, ChunkGenerator generator, RandomSource random,
                                                BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (SafariFeatureFilter.refuses((PlacedFeature) (Object) this, level, generator)) {
            cir.setReturnValue(false);
        }
    }
}
