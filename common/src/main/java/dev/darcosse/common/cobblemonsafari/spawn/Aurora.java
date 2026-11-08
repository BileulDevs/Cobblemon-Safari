package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.ShinyChanceCalculationEvent;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dex.SafariDex;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The aurora ({@code events.aurora}): some clear nights, an aurora lights the
 * Safari. Each hunting player is owed two rare Pokémon, one per group: an
 * Ice one (by default) on the frozen lands, its frozenHabitats or where
 * snow falls, and a Psychic or Fairy one (by default) anywhere else. Each
 * appears near them as soon as they stand where its group lives, glows for a
 * moment so it can be found, and gets the Aurora Mark when caught. While the
 * aurora lasts, every Pokémon appearing in the Safari is more often shiny
 * ({@code shinyChanceMultiplier}).
 *
 * <p>Its life is {@link WeatherEvent}'s, with nightfall as trigger and a dry
 * night as condition (by {@link SafariClock}): drawn at nightfall, it lasts a
 * drawn time, or less if dawn or rain comes. Its pools, picks, spots and
 * Pokémon are {@link EventSpawns}' (natural species of each group's types,
 * under the natural spawns' rules, noted as {@link SpawnSource#AURORA}). The
 * frozen group takes uncommon species too, because Cobblemon has almost no
 * rare Ice species (rare-only always brought Glaceon); the other group,
 * with many rare Psychic and Fairy species, takes rare and ultra-rare only.
 *
 * <p>The pick is the player's own: the species they got at their previous
 * aurora, in that group, is left out (unless nothing else is left), and
 * species they already caught in the Safari weigh
 * {@code spawns.caughtSpeciesWeight}, as in the natural spawns. The previous
 * gifts are kept in memory, so a server restart forgets them.
 *
 * <p>The promise: one Pokémon per player, group and aurora, kept by player
 * UUID for the whole aurora, so leaving and coming back neither loses it nor
 * earns another. A failed try (no fitting spot, chunk not loaded) is retried
 * every {@link #RETRY_SECONDS} until it works or the aurora ends; a player
 * keeps a group's right while away from where it lives. Once the Pokémon
 * has appeared, caught or not, that promise is kept.
 *
 * <p>Glowing: Cobblemon clears every effect of a Pokémon when it sets the
 * entity up, a tick after it appears, which wiped the glow at once. The
 * aurora's Pokémon are followed while they should glow, and the effect is
 * put back with its remaining time whenever it is gone.
 *
 * <p>The shiny boost is a modification function on Cobblemon's
 * {@code SHINY_CHANCE_CALCULATION}, registered once ({@link #register}): any
 * Pokémon rolled for a player standing in the Safari during an aurora (wild
 * spawns, specials, swarms, the events' own) gets its odds multiplied.
 * Cobblemon may roll on its spawn threads: it only reads volatile state.
 *
 * <p>Server thread, but for the shiny boost. Cost: once a second, a time,
 * weather and clock read, and one entity lookup per aurora Pokémon still
 * glowing; during an aurora, every {@link #RETRY_SECONDS}, per player still
 * owed a Pokémon, one biome read, then one heightmap read and a few block
 * reads in a loaded chunk; per Pokémon rolled, one comparison.
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

    /**
     * The aurora's two groups of Pokémon, each with its own pool, promise
     * and previous gift per player: a player is owed one of each.
     */
    private enum Group {
        /** The frozen lands: the aurora's frozenHabitats and where snow falls. */
        FROZEN(EventSpawns.Weights.WITH_UNCOMMON),
        /** Everywhere else in the Safari. */
        ELSEWHERE(EventSpawns.Weights.RARE_FINDS);

        /** Rarities this group draws from. */
        final EventSpawns.Weights weights;
        /** Its species, drawn when the aurora starts; empty when none fits. */
        List<HabitatEntry> pool = List.of();
        /** Players whose Pokémon of this group has appeared during the current aurora. */
        final Set<UUID> served = new HashSet<>();
        /** Species each player got of this group at their latest aurora, left out of their next pick. */
        final Map<UUID, String> lastGift = new HashMap<>();

        Group(EventSpawns.Weights weights) {
            this.weights = weights;
        }
    }

    /** Seconds since the last round of tries. */
    private static int secondsSinceTry;
    /** The shiny boost of the running aurora, 1 when none; read by Cobblemon's shiny roll, maybe off-thread. */
    private static volatile float shinyBoost = 1.0F;
    /** Aurora Pokémon that should still glow, by entity UUID, to the game time their glow ends. */
    private static final Map<UUID, Long> GLOWING_UNTIL = new HashMap<>();

    private Aurora() {
    }

    /** Forgets everything; for a server start, as single player reuses this class. */
    static void reset() {
        EVENT.reset();
        for (Group group : Group.values()) {
            group.pool = List.of();
            group.served.clear();
            group.lastGift.clear();
        }
        secondsSinceTry = 0;
        shinyBoost = 1.0F;
        GLOWING_UNTIL.clear();
    }

    /** Whether an aurora is on in the Safari right now (as of the last second's check). */
    public static boolean isActive() {
        return EVENT.isActive();
    }

    /**
     * Hooks the shiny boost into Cobblemon's shiny roll. Call once at mod
     * start; it does nothing outside an aurora.
     */
    public static void register() {
        CobblemonEvents.SHINY_CHANCE_CALCULATION.subscribe(Priority.NORMAL, Aurora::onShinyChance);
    }

    /** "1 in 1": the best shiny rate there is; a boosted rate is never set below it. */
    private static final float MIN_SHINY_RATE = 1.0F;

    /** Adds the aurora's boost to one shiny roll; it only acts during an aurora, see {@link #boostShinyRate}. */
    private static void onShinyChance(ShinyChanceCalculationEvent event) {
        event.addModificationFunction((rate, player, pokemon) -> boostShinyRate(rate, player, pokemon));
    }

    /**
     * The shiny rate ("1 in N") of a Pokémon rolled for a player, divided by
     * the aurora's multiplier when that player stands in the Safari during
     * one. A Pokémon rolled with no player (a few of Cobblemon's own paths)
     * cannot be placed and keeps its odds. Never below 1 ("1 in 1", always
     * shiny): the multiplier has no upper bound, and a rate under 1 is not a
     * chance any more, so Cobblemon's roll would be left to how it reads one.
     */
    private static float boostShinyRate(float rate, @Nullable ServerPlayer player, Pokemon pokemon) {
        float boost = shinyBoost;
        if (boost <= 1.0F || player == null || !SafariDimensions.isSafari(player.level())) {
            return rate;
        }
        return Math.max(MIN_SHINY_RATE, rate / boost);
    }

    /**
     * Starts, ends and announces the aurora, and brings each player their
     * Pokémon while it lasts. Called once a second by {@link SafariHappenings}.
     *
     * @param safari the Safari level, null when it is not loaded
     */
    static void tick(MinecraftServer server, @Nullable ServerLevel safari) {
        SafariConfig.Aurora config = SafariConfigManager.get().events.aurora;
        WeatherEvent.Change change = EVENT.tick(safari, config.enabled, config.chance,
                config.minDurationMinutes, config.maxDurationMinutes, () -> startMessage(config), Aurora::endMessage);
        if (change == WeatherEvent.Change.STARTED) {
            Group.FROZEN.pool = EventSpawns.drawPool(server, config.frozenTypes, config.excludeList,
                    SpawnSource.AURORA, Group.FROZEN.weights);
            Group.ELSEWHERE.pool = EventSpawns.drawPool(server, config.otherTypes, config.excludeList,
                    SpawnSource.AURORA, Group.ELSEWHERE.weights);
            for (Group group : Group.values()) {
                group.served.clear();
            }
            secondsSinceTry = 0;
            CobblemonSafari.LOGGER.info("Aurora pools: {} frozen, {} elsewhere",
                    Group.FROZEN.pool.size(), Group.ELSEWHERE.pool.size());
        } else if (change == WeatherEvent.Change.ENDED) {
            for (Group group : Group.values()) {
                group.pool = List.of();
                group.served.clear();
            }
        }
        // Read on every roll: kept in step with the event and the config each second.
        shinyBoost = EVENT.isActive() ? (float) config.shinyChanceMultiplier : 1.0F;
        // Before the aurora's own checks: a glow outlasts the aurora that brought it.
        if (safari != null) {
            keepGlowing(safari);
        }
        if (!EVENT.isActive() || safari == null || ++secondsSinceTry < RETRY_SECONDS) {
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
            if (SafariSessionManager.getSession(player) == null) {
                continue;
            }
            // The group of where the player stands: its Pokémon comes where it lives.
            Group group = isFrozenLand(safari, player.blockPosition(), config) ? Group.FROZEN : Group.ELSEWHERE;
            if (!group.pool.isEmpty() && !group.served.contains(player.getUUID())) {
                tryBring(safari, player, group, config, random);
            }
        }
    }

    /** Whether a spot is on the frozen lands: one of the aurora's frozenHabitats, or where snow falls. */
    private static boolean isFrozenLand(ServerLevel level, BlockPos pos, SafariConfig.Aurora config) {
        Holder<Biome> biome = level.getBiome(pos);
        for (String id : config.frozenHabitats) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location != null && biome.unwrapKey().map(key -> key.location().equals(location)).orElse(false)) {
                return true;
            }
        }
        // Snow rather than rain there: cold biomes, and high ground cold enough, whatever the relief.
        return biome.value().getPrecipitationAt(pos) == Biome.Precipitation.SNOW;
    }

    /** One try at bringing a player their Pokémon of a group; marks that promise kept when it appears. */
    private static void tryBring(ServerLevel level, ServerPlayer player, Group group, SafariConfig.Aurora config,
                                 RandomSource random) {
        // On ground of the same group: an Ice Pokémon on the snow, a Psychic one off it.
        BlockPos spot = EventSpawns.spotNear(level, player, MIN_DISTANCE, MAX_DISTANCE,
                pos -> isFrozenLand(level, pos, config) == (group == Group.FROZEN), random);
        if (spot == null) {
            return;
        }
        HabitatEntry entry = pickFor(level.getServer(), player, group, random);
        if (entry == null) {
            return;
        }
        PokemonEntity pokemon = EventSpawns.spawn(level, player, entry, spot, AURORA_TAG, SpawnSource.AURORA, random);
        if (pokemon == null) {
            return;
        }
        group.served.add(player.getUUID());
        group.lastGift.put(player.getUUID(), entry.species().getResourceIdentifier().toString());
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
     * The player's pick in a group: not the species of their previous aurora in it, and their
     * already caught species less often; when that leaves nothing, any
     * species of the pool still allowed.
     */
    @Nullable
    private static HabitatEntry pickFor(MinecraftServer server, ServerPlayer player, Group group,
                                       RandomSource random) {
        String last = group.lastGift.get(player.getUUID());
        Set<String> caught = SafariDex.caughtBy(server, player);
        double caughtWeight = SafariConfigManager.get().spawns.caughtSpeciesWeight;
        HabitatEntry entry = EventSpawns.pick(server, group.pool, SpawnSource.AURORA, group.weights,
                candidate -> {
                    String id = candidate.species().getResourceIdentifier().toString();
                    if (id.equals(last)) {
                        return 0.0D;
                    }
                    return caught.contains(id) ? caughtWeight : 1.0D;
                }, random);
        // Only the previous gift left (or every species caught at weight 0): better that than nothing.
        return entry != null ? entry
                : EventSpawns.pick(server, group.pool, SpawnSource.AURORA, group.weights, random);
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
        return Component.translatable(key("events.aurora.announce"), SafariHappenings.typeNames(config.frozenTypes),
                SafariHappenings.typeNames(config.otherTypes), formatMultiplier(config.shinyChanceMultiplier))
                .withStyle(ChatFormatting.GREEN);
    }

    /** "4" or "2.5": the multiplier as the announcement shows it, without a useless ".0". */
    private static String formatMultiplier(double multiplier) {
        return multiplier == Math.rint(multiplier) ? Long.toString((long) multiplier)
                : String.format(Locale.ROOT, "%.1f", multiplier);
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
