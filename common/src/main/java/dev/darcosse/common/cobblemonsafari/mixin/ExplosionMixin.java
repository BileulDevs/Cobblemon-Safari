package dev.darcosse.common.cobblemonsafari.mixin;

import dev.darcosse.common.cobblemonsafari.protection.SafariProtection;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps explosions from destroying blocks inside the Safari.
 *
 * <p>Fabric has no explosion event, so a mixin is needed there anyway; using
 * the same mixin on NeoForge (instead of {@code ExplosionEvent.Detonate})
 * keeps one code path. {@code explode()} computes the list of blocks to
 * destroy, then {@code finalizeExplosion} breaks them and lights fires on
 * them: emptying the list at the end of {@code explode()} prevents both, while
 * knockback and particles still happen. Damage to players is already
 * cancelled by the invulnerability rule.
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {

    @Shadow
    @Final
    private Level level;

    /** Empties the block list once it is computed, when the explosion is inside the Safari. */
    @Inject(method = "explode", at = @At("TAIL"))
    private void cobblemonSafari$keepBlocksInSafari(CallbackInfo ci) {
        if (SafariProtection.shouldKeepBlocksFromExplosion(this.level)) {
            ((Explosion) (Object) this).getToBlow().clear();
        }
    }
}
