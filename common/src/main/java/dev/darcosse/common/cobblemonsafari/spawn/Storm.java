package dev.darcosse.common.cobblemonsafari.spawn;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.reset.SafariMapReset;
import dev.darcosse.common.cobblemonsafari.session.SafariSessionManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The storm ({@code events.storm}): some thunderstorms of the Safari bring
 * rare Pokémon of some types (Electric and Dragon by default) to the high
 * ground, near the players standing there, each with a harmless lightning
 * flash. A Pokémon the storm brought gets the Storm Mark when caught.
 *
 * <p>Its life (draw when a thunderstorm starts, drawn duration,
 * announcements, no reroll on restart) is {@link WeatherEvent}'s; it adds up
 * with the downpour, a thunderstorm being a rain too. Its pool, pick, spots
 * and Pokémon are {@link EventSpawns}' (rare and ultra-rare natural species
 * of its types, under the natural spawns' rules, noted as
 * {@link SpawnSource#STORM}).
 *
 * <p>Server thread only. Cost: once a second, a weather and clock read; during
 * the storm, every {@code checkEverySeconds} (15 s by default), per hunting
 * player on high ground, one entity query, one heightmap read and a few
 * block reads in loaded chunks.
 */
public final class Storm {

    /** Entity tag of a Pokémon the storm brought, for the per-player cap. */
    public static final String STORM_TAG = CobblemonSafari.MOD_ID + ".storm";

    private static final WeatherEvent EVENT = new WeatherEvent("Storm", ServerLevel::isThundering);

    /**
     * Radius of the "storm Pokémon already around this player" check, in
     * blocks: maxNearby counts within it. Quoted in the comment of
     * events.storm.maxNearby (SafariConfig): keep both in step.
     */
    private static final int NEARBY_RADIUS = 64;

    /**
     * Spawn distance from the player, in blocks: out of the player's lap but
     * within the simulation distance, and close enough to the flash to be
     * found. Wider spreads them over more ground; closer pops them in view.
     */
    private static final int MIN_DISTANCE = 16;
    private static final int MAX_DISTANCE = 40;

    private static final long MILLIS_PER_SECOND = 1000L;

    /** The storm's species, drawn when it starts; empty when none fits. */
    private static List<HabitatEntry> pool = List.of();
    /** Seconds since the last round of spawn attempts. */
    private static int secondsSinceAttempt;
    /** Wall-clock time of the last lightning flash, for its cooldown. */
    private static long lastFlashMillis;

    private Storm() {
    }

    /** Forgets everything; for a server start, as single player reuses this class. */
    static void reset() {
        EVENT.reset();
        pool = List.of();
        secondsSinceAttempt = 0;
        lastFlashMillis = 0L;
    }

    /** Whether the storm event is on in the Safari right now (as of the last second's check). */
    public static boolean isActive() {
        return EVENT.isActive();
    }

    /**
     * Starts, ends and announces the storm, and brings its Pokémon while it
     * lasts. Called once a second by {@link SafariHappenings}.
     *
     * @param safari the Safari level, null when it is not loaded
     */
    static void tick(MinecraftServer server, @Nullable ServerLevel safari) {
        SafariConfig.Storm config = SafariConfigManager.get().events.storm;
        WeatherEvent.Change change = EVENT.tick(safari, config.enabled && !config.types.isEmpty(), config.chance,
                config.minDurationMinutes, config.maxDurationMinutes, () -> startMessage(config), Storm::endMessage);
        if (change == WeatherEvent.Change.STARTED) {
            pool = EventSpawns.drawPool(server, config.types, config.excludeList, SpawnSource.STORM,
                    EventSpawns.Weights.RARE_FINDS);
            secondsSinceAttempt = 0;
            CobblemonSafari.LOGGER.info("Storm pool: {} species", pool.size());
        } else if (change == WeatherEvent.Change.ENDED) {
            pool = List.of();
        }
        if (!EVENT.isActive() || safari == null || pool.isEmpty()) {
            return;
        }
        if (++secondsSinceAttempt < config.checkEverySeconds) {
            return;
        }
        secondsSinceAttempt = 0;
        // Cobblemon's gamerule switches every wild spawn off; the storm's are wild spawns too.
        if (!PokemonSpawningRule.allowed(server)) {
            return;
        }
        RandomSource random = safari.getRandom();
        // Copy: addFreshEntity may run other mods' callbacks.
        for (ServerPlayer player : new ArrayList<>(safari.players())) {
            // Spectators and exempt builders without a session do not draw the storm's Pokémon.
            if (SafariSessionManager.getSession(player) == null
                    || !isHighGround(safari, player.blockPosition(), safari.getBiome(player.blockPosition()), config)
                    || random.nextDouble() >= config.spawnChance) {
                continue;
            }
            trySpawnNear(safari, player, config, random);
        }
    }

    /**
     * Whether a spot is where the storm brings its Pokémon: one of its
     * habitats, or high enough above the sea for the map's relief.
     */
    private static boolean isHighGround(ServerLevel level, BlockPos pos, Holder<Biome> biome, SafariConfig.Storm config) {
        for (String id : config.habitats) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location != null && biome.unwrapKey().map(key -> key.location().equals(location)).orElse(false)) {
                return true;
            }
        }
        // Scaled with the relief: the same share of the island counts as high on a flat or a mountainous map.
        double above = config.minAltitudeAboveSea * SafariMapReset.relief().heightFactor();
        return pos.getY() >= level.getSeaLevel() + above;
    }

    /** One attempt around a player: an open-air spot on high ground nearby, then one of the storm's species. */
    private static void trySpawnNear(ServerLevel level, ServerPlayer player, SafariConfig.Storm config,
                                     RandomSource random) {
        if (EventSpawns.countNear(level, player, STORM_TAG, NEARBY_RADIUS) >= config.maxNearby) {
            return;
        }
        // The storm's Pokémon come down on the high ground: the spot must be high ground too.
        BlockPos spot = EventSpawns.spotNear(level, player, MIN_DISTANCE, MAX_DISTANCE,
                pos -> isHighGround(level, pos, level.getBiome(pos), config), random);
        if (spot == null) {
            return;
        }
        HabitatEntry entry = EventSpawns.pick(level.getServer(), pool, SpawnSource.STORM,
                EventSpawns.Weights.RARE_FINDS, random);
        if (entry == null
                || EventSpawns.spawn(level, player, entry, spot, STORM_TAG, SpawnSource.STORM, random) == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (config.lightning && now - lastFlashMillis >= config.lightningCooldownSeconds * MILLIS_PER_SECOND) {
            flash(level, spot);
            lastFlashMillis = now;
        }
    }

    /**
     * A visual-only lightning bolt: its flash and thunder, no damage and no
     * fire (LightningBolt skips both when visual only), so it cannot hurt
     * the Pokémon it announces nor set the high ground alight.
     */
    private static void flash(ServerLevel level, BlockPos pos) {
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) {
            return;
        }
        bolt.moveTo(Vec3.atBottomCenterOf(pos));
        bolt.setVisualOnly(true);
        level.addFreshEntity(bolt);
    }

    /** The storm's line, naming its types; also shown by {@code /safari today}. */
    public static Component startMessage(SafariConfig.Storm config) {
        return Component.translatable(key("events.storm.announce"), SafariHappenings.typeNames(config.types))
                .withStyle(ChatFormatting.YELLOW);
    }

    /** The line telling the storm is over. */
    private static Component endMessage() {
        return Component.translatable(key("events.storm.end")).withStyle(ChatFormatting.GRAY);
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
