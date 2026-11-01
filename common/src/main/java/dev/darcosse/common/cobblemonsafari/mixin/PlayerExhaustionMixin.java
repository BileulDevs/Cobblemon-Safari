package dev.darcosse.common.cobblemonsafari.mixin;

import dev.darcosse.common.cobblemonsafari.protection.SafariProtection;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the food bar still in the Safari when {@code protection.hunger}
 * is on: see {@link SafariProtection#shouldIgnoreExhaustion}.
 *
 * <p>On {@code Player.causeFoodExhaustion}, the one entry every source of
 * exhaustion goes through (movement, healing, attacks, the Hunger effect),
 * rather than on the food data's tick, which would also stop regeneration
 * and starvation from reading a full bar.
 */
@Mixin(Player.class)
public abstract class PlayerExhaustionMixin {

    /** Drops the exhaustion of a player the Safari keeps fed. */
    @Inject(method = "causeFoodExhaustion", at = @At("HEAD"), cancellable = true)
    private void cobblemonSafari$keepFed(float exhaustion, CallbackInfo ci) {
        if (SafariProtection.shouldIgnoreExhaustion((Player) (Object) this)) {
            ci.cancel();
        }
    }
}
