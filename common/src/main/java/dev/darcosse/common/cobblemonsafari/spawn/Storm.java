package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.park.ParkBoundary;
import dev.darcosse.common.cobblemonsafari.reset.SafariMapReset;
import dev.darcosse.common.cobblemonsafari.session.SafariSessionManager;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import dev.darcosse.common.cobblemonsafari.util.Surface;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The storm ({@code events.storm}): some thunderstorms of the Safari bring
 * rare Pokémon of some types (Electric and Dragon by default) to the high
 * ground, near the players standing there, each with a harmless lightning
 * flash. A Pokémon the storm brought gets the Storm Mark when caught.
 *
 * <p>Its life (draw when a thunderstorm starts, drawn duration,
 * announcements, no reroll on restart) is {@link WeatherEvent}'s; it adds up
 * with the downpour, a thunderstorm being a rain too.
 *
 * <p>Its Pokémon do not come through Cobblemon's spawner: their species
 * mostly live elsewhere (a Dragon in the lagoon), and Cobblemon would refuse
 * them on a ridge. The pool is drawn when the storm starts from the Safari's
 * own natural species ({@link FeaturedRotation#index}) of the storm's types
 * and weighted rarities, under the natural spawns' rules ({@code spawns}:
 * excluded species, legendary-class categories and their caps) plus
 * {@code events.storm.excludeList}. Each is noted as
 * {@link SpawnSource#STORM} in {@link SpecialSpawnCounter}: the natural caps
 * apply to the storm's own count, so its capture never uses up a natural
 * slot, and the mark can tell it apart.
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
    /** Height of the nearby check box; covers cliffs without scanning the whole column. */
    private static final double CHECK_HALF_HEIGHT = 48.0D;

    /**
     * Spawn distance from the player, in blocks: out of the player's lap but
     * within the simulation distance, and close enough to the flash to be
     * found. Wider spreads them over more ground; closer pops them in view.
     */
    private static final int MIN_DISTANCE = 16;
    private static final int MAX_DISTANCE = 40;

    /** Blocks kept clear around rest houses, so no storm Pokémon lands on a roof. */
    private static final int HOUSE_MARGIN = 3;

    /** Level range when the species' natural levels are unknown. */
    private static final int FALLBACK_MIN_LEVEL = 20;
    private static final int FALLBACK_MAX_LEVEL = 40;

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
            pool = drawPool(server, config);
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
        AABB around = player.getBoundingBox().inflate(NEARBY_RADIUS, CHECK_HALF_HEIGHT, NEARBY_RADIUS);
        if (level.getEntitiesOfClass(PokemonEntity.class, around,
                entity -> entity.getTags().contains(STORM_TAG)).size() >= config.maxNearby) {
            return;
        }
        double angle = random.nextDouble() * Math.PI * 2.0D;
        double distance = MIN_DISTANCE + random.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE);
        int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
        int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
        if (ParkBoundary.isOutside(x, z) || RestHouses.isNearSurfaceHouse(x, z, HOUSE_MARGIN)) {
            return;
        }
        // Only loaded chunks: generating terrain for a spawn would stall the tick.
        BlockPos feet = Surface.loaded(level, x, z);
        if (feet == null) {
            return;
        }
        BlockState below = level.getBlockState(feet.below());
        // Dry ground under the open sky: the storm's Pokémon come down on the high ground.
        if (below.isAir() || !below.getFluidState().isEmpty() || !level.getBlockState(feet).isAir()
                || !level.getBlockState(feet.above()).isAir() || !level.canSeeSky(feet)
                || !isHighGround(level, feet, level.getBiome(feet), config)) {
            return;
        }
        HabitatEntry entry = pick(level.getServer(), config, random);
        if (entry != null) {
            spawn(level, player, entry, feet, config, random);
        }
    }

    /**
     * The storm's species: the Safari's natural species with one of its
     * types, a rarity of positive weight, and allowed in the storm. Drawn
     * once per storm: the index walk is a few thousand lookups.
     */
    private static List<HabitatEntry> drawPool(MinecraftServer server, SafariConfig.Storm config) {
        Set<String> wanted = new HashSet<>();
        for (String type : config.types) {
            wanted.add(type.toLowerCase(Locale.ROOT));
        }
        List<SwarmRules.Exclusion> exclusions = SwarmRules.parseExclusions(config.excludeList);
        List<HabitatEntry> entries = new ArrayList<>();
        for (Map.Entry<ResourceLocation, HabitatEntry> indexed : FeaturedRotation.index(server).entrySet()) {
            HabitatEntry entry = indexed.getValue();
            // Natural species only: a species placed by its types alone does not live in the Safari.
            if (!entry.natural() || SwarmRules.weightOf(entry.rarity(), config.weights) <= 0
                    || !hasType(entry, wanted) || !SpeciesRules.isAllowed(entry.species(), SpawnSource.STORM)
                    || SwarmRules.isExcluded(exclusions, indexed.getKey().toString(), properties(entry))) {
                continue;
            }
            entries.add(entry);
        }
        return List.copyOf(entries);
    }

    /** Whether one of the species' types is wanted. */
    private static boolean hasType(HabitatEntry entry, Set<String> wanted) {
        for (ElementalType type : entry.species().getTypes()) {
            if (wanted.contains(type.getName().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Weighted pick among the pool, by rarity, leaving out species that used
     * up their storm appearances for this rotation; null when none is left.
     */
    @Nullable
    private static HabitatEntry pick(MinecraftServer server, SafariConfig.Storm config, RandomSource random) {
        List<HabitatEntry> candidates = new ArrayList<>();
        int total = 0;
        for (HabitatEntry entry : pool) {
            if (SpecialSpawnCounter.canSpawn(server, entry.species(), SpawnSource.STORM)) {
                candidates.add(entry);
                total += SwarmRules.weightOf(entry.rarity(), config.weights);
            }
        }
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (HabitatEntry entry : candidates) {
            roll -= SwarmRules.weightOf(entry.rarity(), config.weights);
            if (roll < 0) {
                return entry;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /** The species' most common natural spawn (form, aspects), as the specials start from. */
    private static PokemonProperties properties(HabitatEntry entry) {
        PokemonProperties properties = entry.template() != null ? entry.template().copy() : new PokemonProperties();
        properties.setSpecies(entry.species().getResourceIdentifier().toString());
        return properties;
    }

    /**
     * Creates and places the Pokémon as the specials' spawner does, notes it
     * as the storm's, then flashes the lightning when its cooldown allows.
     */
    private static void spawn(ServerLevel level, ServerPlayer player, HabitatEntry entry, BlockPos pos,
                              SafariConfig.Storm config, RandomSource random) {
        PokemonProperties properties = properties(entry);
        properties.setLevel(level(entry, random));
        // The player is passed so shiny-chance modifiers such as a Shiny Charm apply.
        PokemonEntity pokemonEntity = properties.createEntity(level, player);
        pokemonEntity.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, random.nextFloat() * 360.0F, 0.0F);
        pokemonEntity.addTag(STORM_TAG);
        // Noted only once really added: another mod may veto the entity.
        if (!level.addFreshEntity(pokemonEntity)) {
            return;
        }
        SpecialSpawnCounter.recordSpawn(level.getServer(), entry.species(), SpawnSource.STORM,
                pokemonEntity.getUUID(), pokemonEntity.getPokemon().getUuid(), pos);
        long now = System.currentTimeMillis();
        if (config.lightning && now - lastFlashMillis >= config.lightningCooldownSeconds * MILLIS_PER_SECOND) {
            flash(level, pos);
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

    /** A level within the species' natural range in the Safari, or the fallback when unknown. */
    private static int level(HabitatEntry entry, RandomSource random) {
        int min = entry.minLevel() > 0 ? entry.minLevel() : FALLBACK_MIN_LEVEL;
        int max = entry.maxLevel() >= min ? entry.maxLevel() : Math.max(min, FALLBACK_MAX_LEVEL);
        return min + random.nextInt(max - min + 1);
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
