package dev.darcosse.common.cobblemonsafari.util;

import java.util.Locale;

/**
 * Conversions between game ticks and human time.
 *
 * <p>Everything the Safari measures internally is in server ticks: they stop
 * when the integrated server is paused and slow down with the server, which is
 * exactly what a fair hunting clock should do. Wall-clock time would let lag
 * eat a player's session.
 */
public final class Ticks {

    /** Nominal server tick rate. /tick rate changes it at runtime; the Safari follows ticks, not seconds. */
    public static final int PER_SECOND = 20;

    private Ticks() {
    }

    /** Seconds to ticks, saturating instead of overflowing on absurd config values. */
    public static int fromSeconds(long seconds) {
        long ticks = seconds * PER_SECOND;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, ticks));
    }

    /** Ticks to whole seconds, rounded up so "0:00" only shows when time is really over. */
    public static int toSecondsCeil(int ticks) {
        if (ticks <= 0) {
            return 0;
        }
        return (ticks + PER_SECOND - 1) / PER_SECOND;
    }

    /**
     * Formats a tick count as {@code m:ss}, or {@code h:mm:ss} from one hour up.
     * Locale.ROOT keeps ASCII digits: some locales format with native digits.
     */
    public static String format(int ticks) {
        int total = toSecondsCeil(ticks);
        int hours = total / 3600;
        int minutes = (total % 3600) / 60;
        int seconds = total % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }
}
