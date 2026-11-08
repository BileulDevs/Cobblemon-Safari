package dev.darcosse.common.cobblemonsafari.spawn;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The life of an event tied to the Safari's weather (the downpour, the
 * storm): it may start when that weather starts, lasts a drawn time or
 * until the weather ends, and is announced to the players in the Safari.
 *
 * <p>The weather itself is the Safari's ordinary weather; nothing here
 * changes it. Each time it starts, a draw ({@code chance}) decides whether
 * the event comes with it; the event then lasts between the given minimum
 * and maximum, in wall-clock minutes, or less if the weather stops first.
 * The first reading after a server start is never a start, so restarting
 * cannot reroll the weather; the event itself is not saved.
 *
 * <p>One instance per event, ticked once a second on the server thread;
 * {@link #isActive} is a volatile snapshot for the spawn threads.
 */
final class WeatherEvent {

    private static final long MILLIS_PER_MINUTE = 60_000L;

    /** What happened at a tick, for the event to react (build its pool, clear its state). */
    enum Change { NONE, STARTED, ENDED }

    /** Name in the log. */
    private final String name;
    /** Whether the Safari has this event's weather now. */
    private final Predicate<ServerLevel> weather;

    private volatile boolean active;
    /** When the current event ends, in wall-clock millis. */
    private long endMillis;
    /** The weather at the last check; null before the first one, so weather already there is never drawn. */
    @Nullable
    private Boolean hadWeather;
    /** Players told of the current event, so each hears of it once and of its end. */
    private final Set<UUID> told = new HashSet<>();
    private final RandomSource random = RandomSource.create();

    /**
     * @param name    the event's name in the log
     * @param weather whether the Safari has the event's weather (raining, thundering)
     */
    WeatherEvent(String name, Predicate<ServerLevel> weather) {
        this.name = name;
        this.weather = weather;
    }

    /** Forgets everything; for a server start, as single player reuses the event classes. */
    void reset() {
        active = false;
        endMillis = 0L;
        hadWeather = null;
        told.clear();
    }

    /** Whether the event is on (as of the last second's check). */
    boolean isActive() {
        return active;
    }

    /**
     * Starts or ends the event from the Safari's weather and the config, and
     * tells the players: the start (or their arrival during the event) and
     * the end. Call once a second.
     *
     * @param safari     the Safari level, null when it is not loaded
     * @param enabled    the event's switch; off ends a running event
     * @param chance     chance, 0 to 1, that the weather starting brings the event
     * @param minMinutes shortest event, at least 1
     * @param maxMinutes longest event, at least {@code minMinutes}
     * @param started    the line telling the event is on, built only when someone needs it
     * @param ended      the line telling it is over
     */
    Change tick(@Nullable ServerLevel safari, boolean enabled, double chance, int minMinutes, int maxMinutes,
                Supplier<Component> started, Supplier<Component> ended) {
        boolean now = safari != null && weather.test(safari);
        boolean weatherStarted = Boolean.FALSE.equals(hadWeather) && now;
        hadWeather = safari == null ? null : now;

        Change change = Change.NONE;
        if (active && (!now || !enabled || System.currentTimeMillis() >= endMillis)) {
            active = false;
            change = Change.ENDED;
            if (safari != null) {
                Component line = ended.get();
                for (ServerPlayer player : safari.players()) {
                    if (told.contains(player.getUUID())) {
                        SafariHappenings.tell(player, line);
                    }
                }
            }
            told.clear();
        } else if (!active && weatherStarted && enabled && random.nextDouble() < chance) {
            int minutes = minMinutes + random.nextInt(maxMinutes - minMinutes + 1);
            endMillis = System.currentTimeMillis() + minutes * MILLIS_PER_MINUTE;
            active = true;
            change = Change.STARTED;
            CobblemonSafari.LOGGER.info("{} in the Safari for up to {} min", name, minutes);
        }
        if (active && safari != null) {
            for (ServerPlayer player : safari.players()) {
                if (told.add(player.getUUID())) {
                    SafariHappenings.tell(player, started.get());
                }
            }
        }
        return change;
    }
}
