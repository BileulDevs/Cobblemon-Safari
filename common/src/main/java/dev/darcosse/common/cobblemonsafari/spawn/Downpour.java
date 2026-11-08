package dev.darcosse.common.cobblemonsafari.spawn;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The downpour ({@code events.downpour}): some rains of the Safari turn into
 * a downpour, during which Water and Electric Pokémon (by default) come out
 * more where the rain falls, and catches there earn the Downpour Mark.
 *
 * <p>Its life (draw when a rain starts, drawn duration, announcements, no
 * reroll on restart) is {@link WeatherEvent}'s. Ticked by
 * {@link SafariHappenings} on the server thread; the spawn threads read
 * {@link #isActive} and {@link #types}, published as volatile snapshots.
 * Cost: once a second, a weather read and a clock read.
 */
public final class Downpour {

    private static final WeatherEvent EVENT = new WeatherEvent("Downpour", ServerLevel::isRaining);

    /** Lower-case type names it favours, for the spawn threads. */
    private static volatile Set<String> types = Set.of();

    private Downpour() {
    }

    /** Forgets everything; for a server start, as single player reuses this class. */
    static void reset() {
        EVENT.reset();
        types = Set.of();
    }

    /** Whether a downpour is on in the Safari right now (as of the last second's check). */
    public static boolean isActive() {
        return EVENT.isActive();
    }

    /**
     * Whether a downpour falls on a block of the Safari right now: one is on,
     * and rain (not snow) reaches that block. Server thread only (reads the
     * level's heightmap).
     */
    public static boolean fallsAt(Level level, BlockPos pos) {
        return isActive() && level.isRainingAt(pos);
    }

    /**
     * Whether a downpour falls on a spawn spot, from what Cobblemon already
     * worked out for it: the spot sees the sky, and its biome gets rain
     * there, not snow or nothing (dunes, volcano). Safe on the spawn threads:
     * reads no chunk.
     */
    static boolean fallsOnSpot(boolean canSeeSky, Biome biome, BlockPos pos) {
        return isActive() && canSeeSky && biome.getPrecipitationAt(pos) == Biome.Precipitation.RAIN;
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
        EVENT.tick(safari, config.enabled && !config.types.isEmpty(), config.chance, config.minDurationMinutes,
                config.maxDurationMinutes, () -> startMessage(config), Downpour::endMessage);
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
