package dev.darcosse.common.cobblemonsafari.spawn;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The downpour ({@code events.downpour}): some rains of the Safari turn into
 * a downpour, during which Water and Electric Pokémon (by default) come out
 * more where the rain falls, and catches there earn the Downpour Mark.
 *
 * <p>The rain itself is the Safari's ordinary weather; nothing here makes it
 * rain. Each time a rain starts, a draw ({@code chance}) decides whether it
 * is a downpour, which then lasts a drawn time between
 * {@code minDurationMinutes} and {@code maxDurationMinutes}, or less if the
 * rain stops first. Players in the Safari are told when it starts (or when
 * they come in during one) and when it ends.
 *
 * <p>Not saved: a server restart ends a downpour, and a rain already falling
 * when the server starts is not drawn, so restarting cannot reroll a rain.
 *
 * <p>Ticked by {@link SafariHappenings} on the server thread; the spawn
 * threads read {@link #active} and {@link #types}, published as volatile
 * snapshots. Cost: once a second, a weather read and a clock read.
 */
public final class Downpour {

    private static final long MILLIS_PER_MINUTE = 60_000L;

    /** Whether a downpour is on, for the spawn threads and the marks. */
    private static volatile boolean active;
    /** Lower-case type names it favours, for the spawn threads. */
    private static volatile Set<String> types = Set.of();

    /** When the current downpour ends, in wall-clock millis. */
    private static long endMillis;
    /** Whether it rained at the last check; null before the first one, so a rain already falling is never drawn. */
    @Nullable
    private static Boolean wasRaining;
    /** Players told of the current downpour, so each hears of it once and of its end. */
    private static final Set<UUID> TOLD = new HashSet<>();
    private static final RandomSource RANDOM = RandomSource.create();

    private Downpour() {
    }

    /** Forgets everything; for a server start, as single player reuses this class. */
    static void reset() {
        active = false;
        types = Set.of();
        endMillis = 0L;
        wasRaining = null;
        TOLD.clear();
    }

    /** Whether a downpour is on in the Safari right now (as of the last second's check). */
    public static boolean isActive() {
        return active;
    }

    /**
     * Whether a downpour falls on a block of the Safari right now: one is on,
     * and rain (not snow) reaches that block. Server thread only (reads the
     * level's heightmap).
     */
    public static boolean fallsAt(Level level, BlockPos pos) {
        return active && level.isRainingAt(pos);
    }

    /**
     * Whether a downpour falls on a spawn spot, from what Cobblemon already
     * worked out for it: the spot sees the sky, and its biome gets rain
     * there, not snow or nothing (dunes, volcano). Safe on the spawn threads:
     * reads no chunk.
     */
    static boolean fallsOnSpot(boolean canSeeSky, Biome biome, BlockPos pos) {
        return active && canSeeSky && biome.getPrecipitationAt(pos) == Biome.Precipitation.RAIN;
    }

    /** Lower-case type names the downpour favours, for the spawn threads. */
    static Set<String> types() {
        return types;
    }

    /**
     * Starts, ends and announces the downpour. Called once a second by
     * {@link SafariHappenings}, with the Safari level (null when it is not
     * loaded).
     */
    static void tick(@Nullable ServerLevel safari) {
        SafariConfig.Downpour config = SafariConfigManager.get().events.downpour;
        Set<String> wanted = new HashSet<>();
        for (String type : config.types) {
            wanted.add(type.toLowerCase(Locale.ROOT));
        }
        types = Set.copyOf(wanted);
        boolean raining = safari != null && safari.isRaining();
        boolean rainStarted = Boolean.FALSE.equals(wasRaining) && raining;
        wasRaining = safari == null ? null : raining;

        if (active && (!raining || !config.enabled || System.currentTimeMillis() >= endMillis)) {
            stop(safari);
        } else if (!active && rainStarted && config.enabled && !config.types.isEmpty()
                && RANDOM.nextDouble() < config.chance) {
            start(config);
        }
        if (active && safari != null) {
            for (ServerPlayer player : safari.players()) {
                if (TOLD.add(player.getUUID())) {
                    SafariHappenings.tell(player, startMessage(config));
                }
            }
        }
    }

    /** Starts a downpour for a drawn time. */
    private static void start(SafariConfig.Downpour config) {
        int minutes = config.minDurationMinutes
                + RANDOM.nextInt(config.maxDurationMinutes - config.minDurationMinutes + 1);
        endMillis = System.currentTimeMillis() + minutes * MILLIS_PER_MINUTE;
        active = true;
        CobblemonSafari.LOGGER.info("Downpour in the Safari for up to {} min", minutes);
    }

    /** Ends the downpour and tells those who heard of it and are still in the Safari. */
    private static void stop(@Nullable ServerLevel safari) {
        active = false;
        if (safari != null) {
            for (ServerPlayer player : safari.players()) {
                if (TOLD.contains(player.getUUID())) {
                    SafariHappenings.tell(player, endMessage());
                }
            }
        }
        TOLD.clear();
    }

    /** The downpour's line, naming the favoured types; also shown by {@code /safari today}. */
    public static Component startMessage(SafariConfig.Downpour config) {
        return Component.translatable(key("events.downpour.announce"), SafariHappenings.typeNames(config.types))
                .withStyle(ChatFormatting.BLUE);
    }

    /** The line telling the downpour is over. */
    private static Component endMessage() {
        return Component.translatable(key("events.downpour.end")).withStyle(ChatFormatting.GRAY);
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
