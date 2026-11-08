package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dex.SafariDex;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The aurora ({@code events.aurora}): some clear nights, an aurora lights the
 * Safari's frozen lands, and each hunting player is owed one rare Pokémon of
 * some types (Ice by default). It appears near them as soon as they stand in
 * one of the aurora's habitats or where snow falls, glows for a moment so it
 * can be found, and gets the Aurora Mark when caught.
 *
 * <p>Its life is {@link WeatherEvent}'s, with nightfall as trigger and a dry
 * night as condition (by {@link SafariClock}): drawn at nightfall, it lasts a
 * drawn time, or less if dawn or rain comes. Its pool, pick, spots and
 * Pokémon are {@link EventSpawns}' (uncommon, rare and ultra-rare natural
 * species of its types, under the natural spawns' rules, noted as
 * {@link SpawnSource#AURORA}). Uncommon too because Cobblemon has almost no
 * rare Ice species: rare-only always brought Glaceon.
 *
 * <p>The pick is the player's own: the species they got at their previous
 * aurora is left out (unless nothing else is left), and species they
 * already caught in the Safari weigh {@code spawns.caughtSpeciesWeight}, as
 * in the natural spawns. The previous gift is kept in memory, so a server
 * restart forgets it.
 *
 * <p>The promise: one Pokémon per player and aurora, kept by player UUID for
 * the whole aurora, so leaving and coming back neither loses it nor earns a
 * second one. A failed try (no fitting spot, chunk not loaded) is retried
 * every {@link #RETRY_SECONDS} until it works or the aurora ends; a player
 * owed one keeps the right while away from the frozen lands. Once the
 * Pokémon has appeared, caught or not, the promise is kept.
 *
 * <p>Glowing: Cobblemon clears every effect of a Pokémon when it sets the
 * entity up, a tick after it appears, which wiped the glow at once. The
 * aurora's Pokémon are followed while they should glow, and the effect is
 * put back with its remaining time whenever it is gone.
 *
 * <p>Server thread only. Cost: once a second, a time, weather and clock read,
 * and one entity lookup per aurora Pokémon still glowing;
 * during an aurora, every {@link #RETRY_SECONDS}, per player still owed a
 * Pokémon and standing on the frozen lands, one heightmap read and a few
 * block reads in a loaded chunk.
 */
public final class Aurora {

    /** Entity tag of a Pokémon an aurora brought. */
    public static final String AURORA_TAG = CobblemonSafari.MOD_ID + ".aurora";

    private static final WeatherEvent EVENT = new WeatherEvent("Aurora",
            SafariClock::isNight, level -> SafariClock.isNight(level) && !level.isRaining());

    /**
     * Seconds between two tries for a player still owed a Pokémon. Shorter
     * finds a spot sooner on rough ground; each try is one heightmap read
     * and a few block reads.
     */
    private static final int RETRY_SECONDS = 3;

    /**
     * Spawn distance from the player, in blocks: close, since the Pokémon is
     * theirs and the glow points to it, but out of their lap.
     */
    private static final int MIN_DISTANCE = 16;
    private static final int MAX_DISTANCE = 32;

    private static final int TICKS_PER_SECOND = 20;

    /** The aurora's species, drawn when it starts; empty when none fits. */
    private static List<HabitatEntry> pool = List.of();
    /** Players whose Pokémon has appeared during the current aurora. */
    private static final Set<UUID> SERVED = new HashSet<>();
    /** Seconds since the last round of tries. */
    private static int secondsSinceTry;
    /** Species each player got at their latest aurora, left out of their next pick. */
    private static final Map<UUID, String> LAST_GIFT = new HashMap<>();
    /** Aurora Pokémon that should still glow, by entity UUID, to the game time their glow ends. */
    private static final Map<UUID, Long> GLOWING_UNTIL = new HashMap<>();

    private Aurora() {
    }

    /** Forgets everything; for a server start, as single player reuses this class. */
    static void reset() {
        EVENT.reset();
        pool = List.of();
        SERVED.clear();
        secondsSinceTry = 0;
        LAST_GIFT.clear();
        GLOWING_UNTIL.clear();
    }

    /** Whether an aurora is on in the Safari right now (as of the last second's check). */
    public static boolean isActive() {
        return EVENT.isActive();
    }

    /**
     * Starts, ends and announces the aurora, and brings each player their
     * Pokémon while it lasts. Called once a second by {@link SafariHappenings}.
     *
     * @param safari the Safari level, null when it is not loaded
     */
    static void tick(MinecraftServer server, @Nullable ServerLevel safari) {
        SafariConfig.Aurora config = SafariConfigManager.get().events.aurora;
        WeatherEvent.Change change = EVENT.tick(safari, config.enabled && !config.types.isEmpty(), config.chance,
                config.minDurationMinutes, config.maxDurationMinutes, () -> startMessage(config), Aurora::endMessage);
        if (change == WeatherEvent.Change.STARTED) {
            pool = EventSpawns.drawPool(server, config.types, config.excludeList, SpawnSource.AURORA,
                    EventSpawns.Weights.WITH_UNCOMMON);
            SERVED.clear();
            secondsSinceTry = 0;
            CobblemonSafari.LOGGER.info("Aurora pool: {} species", pool.size());
        } else if (change == WeatherEvent.Change.ENDED) {
            pool = List.of();
            SERVED.clear();
        }
        // Before the aurora's own checks: a glow outlasts the aurora that brought it.
        if (safari != null) {
            keepGlowing(safari);
        }
        if (!EVENT.isActive() || safari == null || pool.isEmpty() || ++secondsSinceTry < RETRY_SECONDS) {
            return;
        }
        secondsSinceTry = 0;
        // Cobblemon's gamerule switches every wild spawn off; the aurora's are wild spawns too.
        if (!PokemonSpawningRule.allowed(server)) {
            return;
        }
        RandomSource random = safari.getRandom();
        // Copy: addFreshEntity may run other mods' callbacks.
        for (ServerPlayer player : new ArrayList<>(safari.players())) {
            // Spectators and exempt builders without a session are owed nothing.
            if (SERVED.contains(player.getUUID()) || SafariSessionManager.getSession(player) == null
                    || !isFrozenLand(safari, player.blockPosition(), config)) {
                continue;
            }
            tryBring(safari, player, config, random);
        }
    }

    /** Whether a spot is on the frozen lands: one of the aurora's habitats, or where snow falls. */
    private static boolean isFrozenLand(ServerLevel level, BlockPos pos, SafariConfig.Aurora config) {
        Holder<Biome> biome = level.getBiome(pos);
        for (String id : config.habitats) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location != null && biome.unwrapKey().map(key -> key.location().equals(location)).orElse(false)) {
                return true;
            }
        }
        // Snow rather than rain there: cold biomes, and high ground cold enough, whatever the relief.
        return biome.value().getPrecipitationAt(pos) == Biome.Precipitation.SNOW;
    }

    /** One try at bringing a player their Pokémon; marks the promise kept when it appears. */
    private static void tryBring(ServerLevel level, ServerPlayer player, SafariConfig.Aurora config,
                                 RandomSource random) {
        BlockPos spot = EventSpawns.spotNear(level, player, MIN_DISTANCE, MAX_DISTANCE,
                pos -> isFrozenLand(level, pos, config), random);
        if (spot == null) {
            return;
        }
        HabitatEntry entry = pickFor(level.getServer(), player, random);
        if (entry == null) {
            return;
        }
        PokemonEntity pokemon = EventSpawns.spawn(level, player, entry, spot, AURORA_TAG, SpawnSource.AURORA, random);
        if (pokemon == null) {
            return;
        }
        SERVED.add(player.getUUID());
        LAST_GIFT.put(player.getUUID(), entry.species().getResourceIdentifier().toString());
        if (config.glowSeconds > 0) {
            int ticks = config.glowSeconds * TICKS_PER_SECOND;
            pokemon.addEffect(new MobEffectInstance(MobEffects.GLOWING, ticks));
            // Cobblemon wipes it a tick later (see the class comment): keepGlowing puts it back.
            GLOWING_UNTIL.put(pokemon.getUUID(), level.getGameTime() + ticks);
        }
        SafariHappenings.tell(player, Component.translatable(key("events.aurora.appeared"),
                pokemon.getPokemon().getSpecies().getTranslatedName()).withStyle(ChatFormatting.AQUA));
    }

    /**
     * The player's pick: not the species of their previous aurora, and their
     * already caught species less often; when that leaves nothing, any
     * species of the pool still allowed.
     */
    @Nullable
    private static HabitatEntry pickFor(MinecraftServer server, ServerPlayer player, RandomSource random) {
        String last = LAST_GIFT.get(player.getUUID());
        Set<String> caught = SafariDex.caughtBy(server, player);
        double caughtWeight = SafariConfigManager.get().spawns.caughtSpeciesWeight;
        HabitatEntry entry = EventSpawns.pick(server, pool, SpawnSource.AURORA, EventSpawns.Weights.WITH_UNCOMMON,
                candidate -> {
                    String id = candidate.species().getResourceIdentifier().toString();
                    if (id.equals(last)) {
                        return 0.0D;
                    }
                    return caught.contains(id) ? caughtWeight : 1.0D;
                }, random);
        // Only the previous gift left (or every species caught at weight 0): better that than nothing.
        return entry != null ? entry
                : EventSpawns.pick(server, pool, SpawnSource.AURORA, EventSpawns.Weights.WITH_UNCOMMON, random);
    }

    /**
     * Puts the glow back on the aurora's Pokémon that lost it before their
     * time, with what is left of it; forgets those whose glow is over or
     * that are gone (caught, despawned, unloaded).
     */
    private static void keepGlowing(ServerLevel level) {
        if (GLOWING_UNTIL.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        Iterator<Map.Entry<UUID, Long>> glows = GLOWING_UNTIL.entrySet().iterator();
        while (glows.hasNext()) {
            Map.Entry<UUID, Long> glow = glows.next();
            int left = (int) (glow.getValue() - now);
            Entity entity = level.getEntity(glow.getKey());
            if (left <= 0 || !(entity instanceof PokemonEntity pokemon) || !pokemon.isAlive()) {
                glows.remove();
            } else if (!pokemon.hasEffect(MobEffects.GLOWING)) {
                pokemon.addEffect(new MobEffectInstance(MobEffects.GLOWING, left));
            }
        }
    }

    /** The aurora's line; also shown by {@code /safari today}. */
    public static Component startMessage(SafariConfig.Aurora config) {
        return Component.translatable(key("events.aurora.announce"), SafariHappenings.typeNames(config.types))
                .withStyle(ChatFormatting.GREEN);
    }

    /** The line telling the aurora has faded. */
    private static Component endMessage() {
        return Component.translatable(key("events.aurora.end")).withStyle(ChatFormatting.GRAY);
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
