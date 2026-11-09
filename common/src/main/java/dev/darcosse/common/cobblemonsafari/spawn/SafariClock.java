package dev.darcosse.common.cobblemonsafari.spawn;

import net.minecraft.server.level.ServerLevel;

/**
 * Day and night in the Safari, by its clock, for the events tied to them
 * (the heatwave by day, the aurora by night).
 *
 * <p>Read from the time, not the sky's brightness ({@code Level.isDay()}),
 * which rain darkens: a rain passing by would otherwise look like a night
 * and then a new dawn, and draw an event again.
 */
final class SafariClock {

    /** Ticks in a Minecraft day. */
    private static final long DAY_TICKS = 24_000L;
    /** Sunset in a day's ticks: 0 is sunrise, 12 000 the sun setting. */
    private static final long SUNSET_TICK = 12_000L;

    private SafariClock() {
    }

    /** Whether it is day in the Safari: from sunrise to sunset. */
    static boolean isDaytime(ServerLevel level) {
        return Math.floorMod(level.getDayTime(), DAY_TICKS) < SUNSET_TICK;
    }

    /** Whether it is night in the Safari: from sunset to sunrise. */
    static boolean isNight(ServerLevel level) {
        return !isDaytime(level);
    }
}
