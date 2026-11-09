package dev.darcosse.common.cobblemonsafari.spawn;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The heatwave ({@code events.heatwave}): some dry days, the Safari's hot
 * lands (dunes, volcano, badlands, savanna by default) bake, and Fire and
 * Ground Pokémon (by default) come out more there.
 *
 * <p>Its life is {@link WeatherEvent}'s, with the dawn as trigger and a dry
 * day as condition (by {@link SafariClock}): at each dawn without rain, a draw decides whether the day
 * brings a heatwave, which lasts a drawn time, or less if rain comes or night
 * falls. Rain stopping in the afternoon is no dawn, so it never draws a
 * second time the same day.
 *
 * <p>Like the downpour it only weighs Cobblemon's own spawns: no species
 * appears that does not already live in those habitats. No mark.
 *
 * <p>Ticked by {@link SafariHappenings} on the server thread; the spawn
 * threads read {@link #isActive}, {@link #types} and {@link #habitats},
 * published as volatile snapshots. Cost: once a second, a time, weather and
 * clock read.
 */
public final class Heatwave {

    private static final WeatherEvent EVENT = new WeatherEvent("Heatwave",
            SafariClock::isDaytime, level -> SafariClock.isDaytime(level) && !level.isRaining());

    /** Lower-case type names it favours, for the spawn threads. */
    private static volatile Set<String> types = Set.of();
    /** Habitats where it weighs, for the spawn threads. */
    private static volatile Set<ResourceLocation> habitats = Set.of();

    private Heatwave() {
    }

    /** Forgets everything; for a server start, as single player reuses this class. */
    static void reset() {
        EVENT.reset();
        types = Set.of();
        habitats = Set.of();
    }

    /** Whether a heatwave is on in the Safari right now (as of the last second's check). */
    public static boolean isActive() {
        return EVENT.isActive();
    }

    /** Lower-case type names the heatwave favours, for the spawn threads. */
    static Set<String> types() {
        return types;
    }

    /** Whether a spawn spot's biome is one of the heatwave's habitats. Safe on the spawn threads. */
    static boolean bakes(Holder<Biome> biome) {
        Set<ResourceLocation> hot = habitats;
        return biome.unwrapKey().map(key -> hot.contains(key.location())).orElse(false);
    }

    /**
     * Starts, ends and announces the heatwave. Called once a second by
     * {@link SafariHappenings}, with the Safari level (null when it is not
     * loaded).
     */
    static void tick(@Nullable ServerLevel safari) {
        SafariConfig.Heatwave config = SafariConfigManager.get().events.heatwave;
        Set<String> wantedTypes = new HashSet<>();
        for (String type : config.types) {
            wantedTypes.add(type.toLowerCase(Locale.ROOT));
        }
        types = Set.copyOf(wantedTypes);
        Set<ResourceLocation> wantedHabitats = new HashSet<>();
        for (String id : config.habitats) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location != null) {
                wantedHabitats.add(location);
            }
        }
        habitats = Set.copyOf(wantedHabitats);
        EVENT.tick(safari, config.enabled && !config.types.isEmpty() && !config.habitats.isEmpty(), config.chance,
                config.minDurationMinutes, config.maxDurationMinutes, () -> startMessage(config), Heatwave::endMessage);
    }

    /** The heatwave's line, naming the favoured types; also shown by {@code /safari today}. */
    public static Component startMessage(SafariConfig.Heatwave config) {
        return Component.translatable(key("events.heatwave.announce"), SafariHappenings.typeNames(config.types))
                .withStyle(ChatFormatting.GOLD);
    }

    /** The line telling the heatwave is over. */
    private static Component endMessage() {
        return Component.translatable(key("events.heatwave.end")).withStyle(ChatFormatting.GRAY);
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
