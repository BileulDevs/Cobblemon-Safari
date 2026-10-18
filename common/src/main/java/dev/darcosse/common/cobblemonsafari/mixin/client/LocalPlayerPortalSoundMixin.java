package dev.darcosse.common.cobblemonsafari.mixin.client;

import dev.darcosse.common.cobblemonsafari.block.SafariBlocks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.world.level.block.Portal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mutes the nether portal's whoosh when the player steps into a Safari Gate
 * pane: the gate makes no sound. Vanilla plays that whoosh for any portal
 * with the confusion transition, which the gate uses for its overlay; other
 * portals keep it.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerPortalSoundMixin {

    /**
     * The only sound played in {@code handleConfusionTransitionEffect} is the
     * portal trigger. {@code require = 0}: if another mod rewrote this call,
     * the gate keeps the nether whoosh instead of the game refusing to start.
     */
    @Redirect(method = "handleConfusionTransitionEffect",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/sounds/SoundManager;play("
                            + "Lnet/minecraft/client/resources/sounds/SoundInstance;)V"),
            require = 0)
    private void cobblemonSafari$muteGateWhoosh(SoundManager soundManager, SoundInstance whoosh) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        boolean inGate = self.portalProcess != null
                && self.portalProcess.isSamePortal((Portal) SafariBlocks.safariGate());
        if (!inGate) {
            soundManager.play(whoosh);
        }
    }
}
