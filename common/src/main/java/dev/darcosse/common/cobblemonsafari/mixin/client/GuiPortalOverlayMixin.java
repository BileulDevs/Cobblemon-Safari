package dev.darcosse.common.cobblemonsafari.mixin.client;

import dev.darcosse.common.cobblemonsafari.client.gate.GatePortalOverlay;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Paints the screen overlay with the gate's amber texture while the player waits in a
 * Safari Gate. Vanilla always draws it with the nether portal's sprite,
 * whatever portal the player stands in.
 */
@Mixin(Gui.class)
public abstract class GuiPortalOverlayMixin {

    /**
     * Swaps the sprite the overlay is drawn with. {@code require = 0}: if
     * another mod rewrote this call, the overlay simply stays purple instead
     * of the game refusing to start.
     */
    @Redirect(method = "renderPortalOverlay",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/BlockModelShaper;getParticleIcon("
                            + "Lnet/minecraft/world/level/block/state/BlockState;)"
                            + "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"),
            require = 0)
    private TextureAtlasSprite cobblemonSafari$gateOverlay(BlockModelShaper shaper, BlockState vanillaPortal) {
        return GatePortalOverlay.sprite(shaper, vanillaPortal);
    }
}
