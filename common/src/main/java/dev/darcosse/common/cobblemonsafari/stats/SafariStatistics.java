package dev.darcosse.common.cobblemonsafari.stats;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * The Safari's lines in the game's own Statistics screen (General tab):
 * hunts, time hunted, Pokémon, shinies and specials caught, points earned
 * and spent, missions completed, rest house trips, best hunt.
 *
 * <p>Why vanilla statistics on top of the mod's own record
 * ({@link SafariStatsStore}): they show where players already look, are
 * saved per player with the world (stats/&lt;uuid&gt;.json) and can be read
 * by scoreboards ({@code minecraft.custom:cobblemon_safari.pokemon_caught}).
 * The mod's record stays the source of the wall screens and leaderboards.
 *
 * <p>Registered on both sides: the client needs the stats, with their
 * formatter, to list and format what the server sends. Names are
 * translated from {@code stat.cobblemon_safari.<name>}.
 */
public final class SafariStatistics {

    /** Safari games played to the end, whatever the way they ended. */
    public static final ResourceLocation HUNTS = CobblemonSafari.id("hunts");
    /** Time actually spent hunting, in ticks (shown as time). */
    public static final ResourceLocation TIME_HUNTED = CobblemonSafari.id("time_hunted");
    /** Pokémon caught in the Safari. */
    public static final ResourceLocation POKEMON_CAUGHT = CobblemonSafari.id("pokemon_caught");
    /** Shiny Pokémon caught in the Safari. */
    public static final ResourceLocation SHINY_CAUGHT = CobblemonSafari.id("shiny_caught");
    /** Pokémon of the day (specials) caught in the Safari. */
    public static final ResourceLocation SPECIALS_CAUGHT = CobblemonSafari.id("specials_caught");
    /** Most Pokémon caught in a single hunt. A record, not a counter. */
    public static final ResourceLocation BEST_HUNT = CobblemonSafari.id("best_hunt");
    /** Safari points earned by captures and missions (not refunds or operator grants). */
    public static final ResourceLocation POINTS_EARNED = CobblemonSafari.id("points_earned");
    /** Safari points spent in the shop and on mission swaps. */
    public static final ResourceLocation POINTS_SPENT = CobblemonSafari.id("points_spent");
    /** Missions (bounties) completed. */
    public static final ResourceLocation MISSIONS_COMPLETED = CobblemonSafari.id("missions_completed");
    /** Trips to a rest house, by the Guide or the Escape Rope. */
    public static final ResourceLocation REST_HOUSE_TRIPS = CobblemonSafari.id("rest_house_trips");

    /**
     * Every statistic, in the order they are registered. The order sets
     * their numeric ids in the registry: append new ones at the end.
     */
    private static final List<ResourceLocation> ALL = List.of(HUNTS, TIME_HUNTED, POKEMON_CAUGHT, SHINY_CAUGHT,
            SPECIALS_CAUGHT, BEST_HUNT, POINTS_EARNED, POINTS_SPENT, MISSIONS_COMPLETED, REST_HOUSE_TRIPS);

    private SafariStatistics() {
    }

    /**
     * Registers the statistics into the custom stat registry, then creates
     * each one with its formatter. Called once per loader while that
     * registry is open (Fabric: mod init; NeoForge: its RegisterEvent).
     *
     * <p>Why the formatter right away: the first {@code Stats.CUSTOM.get}
     * of a statistic fixes its formatter, and the Statistics screen asks
     * with none, which would show the time hunted as a raw tick count.
     *
     * @param registrar registers a key and its value into {@code Registries.CUSTOM_STAT}
     */
    public static void register(BiConsumer<ResourceLocation, ResourceLocation> registrar) {
        for (ResourceLocation id : ALL) {
            registrar.accept(id, id);
        }
        for (ResourceLocation id : ALL) {
            Stats.CUSTOM.get(id, id.equals(TIME_HUNTED) ? StatFormatter.TIME : StatFormatter.DEFAULT);
        }
    }

    /** Adds to a counter. Does nothing for an amount of 0 or less. */
    public static void add(ServerPlayer player, ResourceLocation stat, int amount) {
        if (amount > 0) {
            player.awardStat(stat, amount);
        }
    }

    /** Adds one to a counter. */
    public static void increment(ServerPlayer player, ResourceLocation stat) {
        player.awardStat(stat, 1);
    }

    /** Raises a record to {@code value} if it is higher than the one held. */
    public static void raise(ServerPlayer player, ResourceLocation stat, int value) {
        ServerStatsCounter counter = player.getStats();
        Stat<ResourceLocation> entry = Stats.CUSTOM.get(stat);
        if (value > counter.getValue(entry)) {
            counter.setValue(player, entry, value);
        }
    }

    /**
     * Carries a player's Safari record from before these statistics existed
     * (1.2 and earlier) into them, on join: each statistic the record also
     * holds is raised to the record's value. A no-op once they agree.
     * Cost: a handful of map reads per login.
     */
    public static void catchUp(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        PlayerSafariStats record = SafariStatsStore.get(server).find(player.getUUID());
        if (record == null) {
            return;
        }
        raise(player, HUNTS, record.sessions());
        // Clamped: a vanilla statistic is an int; 2^31 ticks is about three years of hunting.
        raise(player, TIME_HUNTED, (int) Math.min(Integer.MAX_VALUE, record.ticksHunted()));
        raise(player, POKEMON_CAUGHT, record.totalCatches());
        raise(player, SHINY_CAUGHT, record.shinyCatches());
        raise(player, BEST_HUNT, record.bestSessionCatches());
    }
}
