package dev.darcosse.common.cobblemonsafari.client;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * The pixel-art safari landscapes drawn by tools/gui_assets.py, each in three
 * lights (morning, dusk, night), and which light matches the time of day.
 * The shop, the Safari Board and each wall screen page have their own
 * scene but share the time rule, so all change light together.
 *
 * <p>Free of client-only imports, but only used by client code.
 */
public final class SafariLandscapes {

    /**
     * One scene: its three textures, their size in pixels (for UV maths) and
     * the height of its horizon as a share of the picture, on which a crop
     * is centred so a cropped screen still shows the horizon line.
     */
    public record Scene(ResourceLocation morning, ResourceLocation dusk, ResourceLocation night,
                        int width, int height, float horizon) {

        /** The texture for the time of day of a level (see the bounds in {@link SafariLandscapes}); morning without a level. */
        public ResourceLocation forTime(@Nullable Level level) {
            return switch (light(level)) {
                case NIGHT -> night;
                case DUSK -> dusk;
                case MORNING -> morning;
            };
        }
    }

    /**
     * Behind the shop panel, drawn 1:1. Size and horizon must match WIDTH,
     * HEIGHT and HORIZON in tools/gui_assets.py.
     */
    public static final Scene SHOP = scene("shop_background_", 300, 232, 0.58F);

    /**
     * Behind the Safari Board screens, cropped to each screen's shape. Size
     * and horizon must match BOARD_WIDTH, BOARD_HEIGHT and BOARD_HORIZON in
     * tools/gui_assets.py.
     */
    public static final Scene BOARD = scene("board_background_", 384, 192, 0.52F);

    /**
     * Behind the wall screens' STATS page: the rangers' camp. Same size and
     * horizon as the board's, matching record_background in tools/gui_assets.py.
     */
    public static final Scene RECORD = scene("record_background_", 384, 192, 0.52F);

    /**
     * Behind the wall screens' BOUNTIES page: the expedition trail. Same size
     * and horizon as the board's, matching missions_background in tools/gui_assets.py.
     */
    public static final Scene MISSIONS = scene("missions_background_", 384, 192, 0.52F);

    /**
     * Which light shows when, in ticks of the day (0 = 6 am, 6000 = noon,
     * 12000 = 6 pm, 18000 = midnight). Night runs from soon after sunset to
     * just before sunrise; dusk covers late afternoon and sunset, and the
     * dawn, whose warm light it shares; morning is the rest of the day.
     * Moving a bound shifts when the pictures change, nothing else.
     */
    private static final long TICKS_PER_DAY = 24000L;
    private static final long DUSK_START = 10000L;
    private static final long NIGHT_START = 13500L;
    private static final long NIGHT_END = 22500L;

    /** The three lights each scene is drawn in. */
    private enum Light { MORNING, DUSK, NIGHT }

    private SafariLandscapes() {
    }

    /**
     * The light for the time of day of a level. A dimension with a fixed time
     * (no day cycle) uses that time; no level gives the morning.
     */
    private static Light light(@Nullable Level level) {
        if (level == null) {
            return Light.MORNING;
        }
        long time = Math.floorMod(level.dimensionType().fixedTime().orElse(level.getDayTime()), TICKS_PER_DAY);
        if (time >= NIGHT_START && time < NIGHT_END) {
            return Light.NIGHT;
        }
        if (time >= DUSK_START) {
            return Light.DUSK;
        }
        return Light.MORNING;
    }

    /** A scene whose textures are {@code textures/gui/<prefix><light>.png}, as written by tools/gui_assets.py. */
    private static Scene scene(String prefix, int width, int height, float horizon) {
        return new Scene(texture(prefix, "morning"), texture(prefix, "dusk"), texture(prefix, "night"),
                width, height, horizon);
    }

    private static ResourceLocation texture(String prefix, String light) {
        return CobblemonSafari.id("textures/gui/" + prefix + light + ".png");
    }
}
