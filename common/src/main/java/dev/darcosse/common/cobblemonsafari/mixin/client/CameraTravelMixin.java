package dev.darcosse.common.cobblemonsafari.mixin.client;

import dev.darcosse.common.cobblemonsafari.client.travel.TravelCamera;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the travel cinematic film the flying double with the game's own
 * camera: once vanilla has placed the camera at the player's eyes, a shot
 * under way moves it to its own spot and turns it toward the bird
 * ({@link TravelCamera}). Rendering, culling and sound all follow the
 * camera, so the world is seen and heard from there.
 */
@Mixin(Camera.class)
public abstract class CameraTravelMixin {

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    /**
     * Overrides the camera during a shot. {@code require = 0}: if another
     * mod rewrote this method, the flight is simply seen from the player's
     * eyes instead of the game refusing to start.
     */
    @Inject(method = "setup", at = @At("TAIL"), require = 0)
    private void cobblemonSafari$travelShot(BlockGetter level, Entity entity, boolean detached,
                                            boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        TravelCamera.Frame frame = TravelCamera.frame(partialTick);
        if (frame != null) {
            // Rotation first, as vanilla does: the position does not depend on it here, but a
            // mod hooking setPosition may read the rotation.
            setRotation(frame.yaw(), frame.pitch());
            setPosition(frame.position());
        }
    }
}
