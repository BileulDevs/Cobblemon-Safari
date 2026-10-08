package dev.darcosse.common.cobblemonsafari.client.sky;

import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side look of the Safari sky. CLIENT ONLY.
 *
 * <p>The sky and horizon colours themselves come from the biomes
 * ({@code sky_color} / {@code fog_color}, set by the worldgen generator); this
 * class adds what data cannot express: a warmer, pinker sunrise and sunset,
 * lower clouds that wrap the central summit, and a fog that keeps a golden
 * tint as the light fades instead of turning grey-blue like the overworld.
 *
 * <p>The sky geometry is still the vanilla one (sun, moon, stars). A fully
 * custom sky means a renderer, which is loader- and version-specific; it can
 * be layered on top of this later without touching the rest.
 */
public final class SafariSkyEffects extends DimensionSpecialEffects {

    /**
     * Cloud layer height. The overworld uses 192; at 170 the clouds cut
     * through the central summit, which is the single strongest cue that this
     * is not the overworld. Much lower and they cover the rocky ring too.
     */
    private static final float CLOUD_HEIGHT = 170.0f;

    /**
     * Half-width of the sunrise/sunset window, as the cosine of the sun angle.
     * Wider than the overworld (0.4) so dusk lingers a little longer.
     */
    private static final float TWILIGHT_WINDOW = 0.45f;

    /** Sunrise colour at its most intense (RGB), a coral pink instead of the overworld orange. */
    private static final float SUNSET_RED = 1.0f;
    private static final float SUNSET_GREEN = 0.52f;
    private static final float SUNSET_BLUE = 0.42f;

    /**
     * How much of the fog colour survives at night, per channel. The overworld
     * keeps more blue; keeping more red and green holds the golden haze.
     */
    private static final float FOG_NIGHT_RED = 0.10f;
    private static final float FOG_NIGHT_GREEN = 0.08f;
    private static final float FOG_NIGHT_BLUE = 0.06f;

    /** Reused array: the caller reads it immediately, and allocating per frame would be waste. */
    private final float[] sunriseColor = new float[4];

    /** Normal sky with ground, like the overworld; only the tuning differs. */
    public SafariSkyEffects() {
        super(CLOUD_HEIGHT, true, SkyType.NORMAL, false, false);
    }

    /**
     * Colour of the sunrise/sunset glow, or null outside twilight.
     *
     * @param timeOfDay    celestial angle, 0..1
     * @param partialTicks unused: the glow depends only on the sun position
     */
    @Nullable
    @Override
    public float[] getSunriseColor(float timeOfDay, float partialTicks) {
        float sunHeight = Mth.cos(timeOfDay * ((float) Math.PI * 2f));
        if (sunHeight < -TWILIGHT_WINDOW || sunHeight > TWILIGHT_WINDOW) {
            return null;
        }
        // 0 at the edges of the window, 1 when the sun crosses the horizon.
        float progress = sunHeight / TWILIGHT_WINDOW * 0.5f + 0.5f;
        float alpha = 1.0f - (1.0f - Mth.sin(progress * (float) Math.PI)) * 0.99f;
        sunriseColor[0] = SUNSET_RED * (progress * 0.25f + 0.75f);
        sunriseColor[1] = SUNSET_GREEN * (progress * progress * 0.6f + 0.4f);
        sunriseColor[2] = SUNSET_BLUE * (progress * progress * 0.5f + 0.5f);
        sunriseColor[3] = alpha * alpha;
        return sunriseColor;
    }

    /** Darkens the biome fog with daylight while keeping its warm tint. */
    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 biomeFogColor, float daylight) {
        return biomeFogColor.multiply(
                daylight * (1.0f - FOG_NIGHT_RED) + FOG_NIGHT_RED,
                daylight * (1.0f - FOG_NIGHT_GREEN) + FOG_NIGHT_GREEN,
                daylight * (1.0f - FOG_NIGHT_BLUE) + FOG_NIGHT_BLUE);
    }

    /** No thick near-fog anywhere: the Safari is meant to be seen across. */
    @Override
    public boolean isFoggyAt(int x, int y) {
        return false;
    }
}
