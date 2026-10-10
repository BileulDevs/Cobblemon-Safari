package dev.darcosse.common.cobblemonsafari.client.gate;

import dev.darcosse.common.cobblemonsafari.block.SafariBlocks;
import dev.darcosse.common.cobblemonsafari.block.SafariGateBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Chooses the sprite of the portal screen overlay: the Safari Gate's own
 * texture while the player stands in a gate, vanilla's otherwise. Client
 * only; called by {@code GuiPortalOverlayMixin} every frame the overlay shows.
 */
public final class GatePortalOverlay {

    /**
     * Whether the last portal the player stood in was a gate. Kept after
     * they step out because the overlay keeps fading for a moment once the
     * portal state is gone; without it the fade would turn purple.
     */
    private static boolean lastWasGate;

    private GatePortalOverlay() {
    }

    /** The sprite to draw the overlay with. One field read and a comparison per frame. */
    public static TextureAtlasSprite sprite(BlockModelShaper shaper, BlockState vanillaPortal) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.portalProcess != null) {
            SafariGateBlock gate = (SafariGateBlock) SafariBlocks.safariGate();
            lastWasGate = player.portalProcess.isSamePortal(gate);
        }
        return shaper.getParticleIcon(lastWasGate ? SafariBlocks.safariGate().defaultBlockState() : vanillaPortal);
    }
}
