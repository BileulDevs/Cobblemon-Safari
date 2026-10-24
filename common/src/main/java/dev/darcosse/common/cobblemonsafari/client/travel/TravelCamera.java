package dev.darcosse.common.cobblemonsafari.client.travel;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The game camera during the flight cinematic: standing at the spot the
 * server chose, turning to follow the flying double. Read every frame by
 * CameraTravelMixin at the end of vanilla's camera setup, which it then
 * overrides.
 *
 * <p>Why the camera stands still and only turns: a camera flying along
 * would have to load the terrain it crosses, while the player's client only
 * holds what lies around the player; a fixed spot near the house is always
 * loaded.
 *
 * <p>Client only; state of the client thread.
 */
public final class TravelCamera {

    /**
     * Time constant of the camera's turn toward its target, in milliseconds.
     * Shorter follows the bird more tightly but shows its small jolts from
     * position updates; longer lags behind a fast take-off.
     */
    private static final double TURN_TIME_MS = 120.0D;

    /**
     * Share of the actor's height aimed at: the top of the bird, where the
     * rider sits, so both stay in frame.
     */
    private static final double AIM_HEIGHT_SHARE = 1.0D;

    /** One frame's camera: where it stands and where it looks. */
    public record Frame(Vec3 position, float yaw, float pitch) {
    }

    private static boolean active;
    private static int actorId = -1;
    private static Vec3 position = Vec3.ZERO;
    private static Vec3 fallbackLook = Vec3.ZERO;
    private static float yaw;
    private static float pitch;
    /** True until the first frame of a shot, which looks straight at its target instead of turning. */
    private static boolean snap;
    private static long lastFrameAt;

    private TravelCamera() {
    }

    /**
     * Starts a shot: stand there and follow that entity, looking at the
     * fallback point until the entity reaches the client.
     */
    public static void shoot(int actor, Vec3 camera, Vec3 look) {
        active = true;
        actorId = actor;
        position = camera;
        fallbackLook = look;
        snap = true;
    }

    /** Gives the camera back to the player. */
    public static void stop() {
        active = false;
        actorId = -1;
    }

    /** Whether a shot overrides the camera. */
    public static boolean isActive() {
        return active;
    }

    /**
     * This frame's camera, or null to leave vanilla's. Cost: an entity
     * lookup by id and a few trigonometric calls, once per rendered frame,
     * only during a shot.
     */
    @Nullable
    public static Frame frame(float partialTick) {
        if (!active) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 target = fallbackLook;
        Entity actor = minecraft.level == null || actorId < 0 ? null : minecraft.level.getEntity(actorId);
        if (actor != null) {
            target = actor.getPosition(partialTick).add(0.0D, actor.getBbHeight() * AIM_HEIGHT_SHARE, 0.0D);
        }
        Vec3 toward = target.subtract(position);
        double flat = Math.sqrt(toward.x * toward.x + toward.z * toward.z);
        float wantedYaw = (float) (Mth.atan2(-toward.x, toward.z) * Mth.RAD_TO_DEG);
        float wantedPitch = (float) (-Mth.atan2(toward.y, flat) * Mth.RAD_TO_DEG);
        long now = Util.getMillis();
        if (snap) {
            yaw = wantedYaw;
            pitch = wantedPitch;
            snap = false;
        } else {
            // Frame-rate independent easing: the same turn at 30 or 240 fps.
            double weight = 1.0D - Math.exp(-(now - lastFrameAt) / TURN_TIME_MS);
            yaw += (float) (Mth.wrapDegrees(wantedYaw - yaw) * weight);
            pitch += (float) ((wantedPitch - pitch) * weight);
        }
        lastFrameAt = now;
        return new Frame(position, yaw, pitch);
    }
}
