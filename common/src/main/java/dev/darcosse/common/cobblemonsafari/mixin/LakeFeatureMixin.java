package dev.darcosse.common.cobblemonsafari.mixin;

import dev.darcosse.common.cobblemonsafari.worldgen.LakeGuard;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuses lakes over the mod's structures: see {@link LakeGuard}.
 */
@Mixin(LakeFeature.class)
public abstract class LakeFeatureMixin {

    /** Cancels a lake whose box reaches a guarded structure, reported as not placed. */
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void cobblemonSafari$keepAwayFromStructures(FeaturePlaceContext<LakeFeature.Configuration> context,
                                                        CallbackInfoReturnable<Boolean> cir) {
        // The lake moves its origin 4 blocks down before filling, never sideways.
        if (LakeGuard.refuses(context.level(), context.origin())) {
            cir.setReturnValue(false);
        }
    }
}
