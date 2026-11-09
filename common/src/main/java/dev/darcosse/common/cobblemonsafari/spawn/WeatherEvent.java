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
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The life of an event tied to the Safari's weather or time of day (the
 * downpour, the storm, the heatwave): it may start when its moment comes,
 * lasts a drawn time or until its conditions end, and is announced to the
 * players in the Safari.
 *
 * <p>Two tests drive it. The trigger is the moment that may bring it (a rain
 * starting, a day dawning): each time the trigger turns true while the
 * condition holds, a draw ({@code chance}) decides whether the event comes.
 * The condition is what it needs to last (rain, a dry day); the event lasts
 * between the given minimum and maximum, in minutes counted by its own
 * once-a-second calls (so a paused single player game pauses it too, and a
 * lagging server stretches it), or less if the condition stops first. For a weather event both are the weather.
 * Nothing here changes the weather or the time. The first reading after a
 * server start is never a trigger, so restarting cannot reroll a moment;
 * the event itself is not saved.
 *
 * <p>One instance per event, ticked once a second on the server thread;
 * {@link #isActive} is a volatile snapshot for the spawn threads.
 */
final class WeatherEvent {

    /** Calls to {@link #tick} in a minute: it is called once a second. */
    private static final int TICKS_PER_MINUTE = 60;

    /** What happened at a tick, for the event to react (build its pool, clear its state). */
    enum Change { NONE, STARTED, ENDED }

    /** Name in the log. */
    private final String name;
    /** The moment that may bring the event, drawn when it turns true. */
    private final Predicate<ServerLevel> trigger;
    /** What the event needs to start and to last. */
    private final Predicate<ServerLevel> condition;

    private volatile boolean active;
    /**
     * Calls to {@link #tick} left before the current event ends. Counted rather
     * than read from the wall clock: the clock runs on while a single player
     * game is paused, and an event could be over before the player resumes.
     */
    private int secondsLeft;
    /** The trigger at the last check; null before the first one, so a moment already there is never drawn. */
    @Nullable
    private Boolean hadTrigger;
    /** Players told of the current event, so each hears of it once and of its end. */
    private final Set<UUID> told = new HashSet<>();
    private final RandomSource random = RandomSource.create();

    /**
     * An event drawn when its weather starts and lasting while it does.
     *
     * @param name    the event's name in the log
     * @param weather whether the Safari has the event's weather (raining, thundering)
     */
    WeatherEvent(String name, Predicate<ServerLevel> weather) {
        this(name, weather, weather);
    }

    /**
     * @param name      the event's name in the log
     * @param trigger   the moment that may bring the event (a day dawning)
     * @param condition what the event needs to start and to last (a dry day)
     */
    WeatherEvent(String name, Predicate<ServerLevel> trigger, Predicate<ServerLevel> condition) {
        this.name = name;
        this.trigger = trigger;
        this.condition = condition;
    }

    /** Forgets everything; for a server start, as single player reuses the event classes. */
    void reset() {
        active = false;
        secondsLeft = 0;
        hadTrigger = null;
        told.clear();
    }

    /** Whether the event is on (as of the last second's check). */
    boolean isActive() {
        return active;
    }

    /**
     * Starts or ends the event from the Safari's weather or time and the config, and
     * tells the players: the start (or their arrival during the event) and
     * the end. Call once a second.
     *
     * @param safari     the Safari level, null when it is not loaded
     * @param enabled    the event's switch; off ends a running event
     * @param chance     chance, 0 to 1, that the trigger turning true brings the event
     * @param minMinutes shortest event, at least 1
     * @param maxMinutes longest event, at least {@code minMinutes}
     * @param started    the line telling the event is on, built only when someone needs it
     * @param ended      the line telling it is over
     */
    Change tick(@Nullable ServerLevel safari, boolean enabled, double chance, int minMinutes, int maxMinutes,
                Supplier<Component> started, Supplier<Component> ended) {
        return tick(safari, enabled, chance, minMinutes, maxMinutes, () -> true, started, ended);
    }

    /**
     * As {@link #tick(ServerLevel, boolean, double, int, int, Supplier, Supplier)},
     * with a last check made only once the draw has said yes: {@code ready}
     * false keeps the event from starting (and from being announced), as for
     * an event whose Pokémon pool turns out empty. It may build what the
     * event needs, since it runs at most once per moment.
     */
    Change tick(@Nullable ServerLevel safari, boolean enabled, double chance, int minMinutes, int maxMinutes,
                BooleanSupplier ready, Supplier<Component> started, Supplier<Component> ended) {
        boolean triggered = safari != null && trigger.test(safari);
        boolean lasting = safari != null && condition.test(safari);
        boolean moment = Boolean.FALSE.equals(hadTrigger) && triggered;
        hadTrigger = safari == null ? null : triggered;

        Change change = Change.NONE;
        if (active && (!lasting || !enabled || --secondsLeft <= 0)) {
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
        } else if (!active && moment && lasting && enabled && random.nextDouble() < chance && ready.getAsBoolean()) {
            int minutes = minMinutes + random.nextInt(maxMinutes - minMinutes + 1);
            secondsLeft = minutes * TICKS_PER_MINUTE;
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
