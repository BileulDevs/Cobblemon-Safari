package dev.darcosse.common.cobblemonsafari.client.hud;

import dev.darcosse.common.cobblemonsafari.config.SafariClientConfig;
import dev.darcosse.common.cobblemonsafari.util.Ticks;

/**
 * Decides how the timer looks for a given remaining time.
 *
 * <p>Isolated from the renderer so the colour rules can change without
 * touching layout code.
 */
public final class TimerStyle {

    /** ARGB colours. Alpha must be non-zero: GuiGraphics skips fully transparent text. */
    private static final int COLOR_NORMAL = 0xFFFFFFFF;
    private static final int COLOR_LOW = 0xFFFFC04D;
    private static final int COLOR_CRITICAL = 0xFFFF5555;
    private static final int COLOR_CRITICAL_DIM = 0xFFAA2222;
    private static final int COLOR_EXPIRED = 0xFFAAAAAA;

    /**
     * Half-period of the critical pulse, in ticks. Shorter reads as alarm,
     * longer as a slow breathing; below ~5 it becomes a flicker.
     */
    private static final int PULSE_HALF_PERIOD_TICKS = 10;

    private TimerStyle() {
    }

    /** Text colour for the given remaining time. */
    public static int color(int remainingTicks, long clientGameTime, SafariClientConfig config) {
        if (remainingTicks <= 0) {
            return COLOR_EXPIRED;
        }
        int seconds = Ticks.toSecondsCeil(remainingTicks);
        if (seconds <= config.criticalTimeSeconds) {
            boolean bright = (clientGameTime / PULSE_HALF_PERIOD_TICKS) % 2 == 0;
            return bright ? COLOR_CRITICAL : COLOR_CRITICAL_DIM;
        }
        if (seconds <= config.lowTimeSeconds) {
            return COLOR_LOW;
        }
        return COLOR_NORMAL;
    }
}
