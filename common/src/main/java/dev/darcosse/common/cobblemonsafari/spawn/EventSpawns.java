package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.darcosse.common.cobblemonsafari.park.ParkBoundary;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import dev.darcosse.common.cobblemonsafari.util.Surface;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * What the events that bring their own Pokémon (the storm, the aurora) share:
 * their species pool, the pick, the search for a spot near a player, and the
 * creation of the Pokémon.
 *
 * <p>Their Pokémon do not come through Cobblemon's spawner: their species
 * mostly live elsewhere, and Cobblemon would refuse them where the event
 * wants them. The pool is the Safari's own natural species
 * ({@link FeaturedRotation#index}) of the event's types, in the rarities the
 * event weighs ({@link Weights}), under the natural spawns' rules ({@code spawns}: excluded species,
 * legendary-class categories and their caps) plus the event's exclude list.
 * Each Pokémon is noted under the event's {@link SpawnSource} in
 * {@link SpecialSpawnCounter}: the natural caps apply to the event's own
 * count, its capture never uses up a natural slot, and the event's mark can
 * tell it apart.
 *
 * <p>Server thread only.
 */
final class EventSpawns {

    /**
     * How likely each rarity is in an event's pick, against the others; 0
     * leaves it out of the pool. Common species never come: the events are
     * about finds.
     */
    record Weights(int uncommon, int rare, int ultraRare) {

        /**
         * Rare and ultra-rare, three to one: for types with many rare
         * species (Electric, Dragon). Equal makes the ultra-rare as common
         * as the rare; more makes it a real find.
         */
        static final Weights RARE_FINDS = new Weights(0, 3, 1);

        /**
         * Uncommon too, five uncommon for three rare and one ultra-rare: for
         * types with almost no rare species in Cobblemon's data (Ice: only
         * Glaceon and Lapras), where rare-only would always bring the same.
         */
        static final Weights WITH_UNCOMMON = new Weights(5, 3, 1);

        /** Weight of a rarity; 0 for those left out. */
        int of(@Nullable Rarity rarity) {
            if (rarity == null) {
                return 0;
            }
            return switch (rarity) {
                case UNCOMMON -> uncommon;
                case RARE -> rare;
                case ULTRA_RARE -> ultraRare;
                default -> 0;
            };
        }
    }

    /** Blocks kept clear around rest houses, so no event Pokémon lands on a roof. */
    private static final int HOUSE_MARGIN = 3;

    /** Level range when the species' natural levels are unknown. */
    private static final int FALLBACK_MIN_LEVEL = 20;
    private static final int FALLBACK_MAX_LEVEL = 40;

    /** Height of the "already around this player" box; covers cliffs without scanning the whole column. */
    private static final double NEARBY_HALF_HEIGHT = 48.0D;

    private EventSpawns() {
    }

    /**
     * An event's species: the Safari's natural species with one of
     * {@code types}, of a rarity {@code weights} keeps, allowed for
     * {@code source} and not in {@code excludeList}. Drawn once per event:
     * the index walk is a few thousand lookups.
     */
    static List<HabitatEntry> drawPool(MinecraftServer server, List<String> types, List<String> excludeList,
                                       SpawnSource source, Weights weights) {
        Set<String> wanted = new HashSet<>();
        for (String type : types) {
            wanted.add(type.toLowerCase(Locale.ROOT));
        }
        List<SwarmRules.Exclusion> exclusions = SwarmRules.parseExclusions(excludeList);
        List<HabitatEntry> entries = new ArrayList<>();
        for (Map.Entry<ResourceLocation, HabitatEntry> indexed : FeaturedRotation.index(server).entrySet()) {
            HabitatEntry entry = indexed.getValue();
            // Natural species only: a species placed by its types alone does not live in the Safari.
            if (!entry.natural() || weights.of(entry.rarity()) <= 0 || !hasType(entry, wanted)
                    || !SpeciesRules.isAllowed(entry.species(), source)
                    || SwarmRules.isExcluded(exclusions, indexed.getKey().toString(), properties(entry))) {
                continue;
            }
            entries.add(entry);
        }
        return List.copyOf(entries);
    }

    /**
     * Weighted pick in a pool by rarity, leaving out species that used up
     * their appearances through {@code source} this rotation; null when none
     * is left.
     */
    @Nullable
    static HabitatEntry pick(MinecraftServer server, List<HabitatEntry> pool, SpawnSource source, Weights weights,
                             RandomSource random) {
        return pick(server, pool, source, weights, entry -> 1.0D, random);
    }

    /**
     * Weighted pick in a pool by rarity times {@code bias} (a species'
     * extra factor for this pick: 0 leaves it out), leaving out species that
     * used up their appearances through {@code source} this rotation; null
     * when none is left.
     */
    @Nullable
    static HabitatEntry pick(MinecraftServer server, List<HabitatEntry> pool, SpawnSource source, Weights weights,
                             ToDoubleFunction<HabitatEntry> bias, RandomSource random) {
        List<HabitatEntry> candidates = new ArrayList<>();
        List<Double> chances = new ArrayList<>();
        double total = 0.0D;
        for (HabitatEntry entry : pool) {
            double chance = weights.of(entry.rarity()) * bias.applyAsDouble(entry);
            if (chance > 0.0D && SpecialSpawnCounter.canSpawn(server, entry.species(), source)) {
                candidates.add(entry);
                chances.add(chance);
                total += chance;
            }
        }
        if (total <= 0.0D) {
            return null;
        }
        double roll = random.nextDouble() * total;
        for (int i = 0; i < candidates.size(); i++) {
            roll -= chances.get(i);
            if (roll < 0.0D) {
                return candidates.get(i);
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /** Event Pokémon carrying {@code tag} within {@code radius} blocks of a player. */
    static int countNear(ServerLevel level, ServerPlayer player, String tag, int radius) {
        AABB around = player.getBoundingBox().inflate(radius, NEARBY_HALF_HEIGHT, radius);
        return level.getEntitiesOfClass(PokemonEntity.class, around, entity -> entity.getTags().contains(tag)).size();
    }

    /**
     * A random spot between {@code minDistance} and {@code maxDistance} blocks
     * from a player: in the park, off the rest houses, in a loaded chunk, dry
     * ground under the open sky, and accepted by {@code zone}. Null when that
     * one try fails; callers try again later rather than looping, so a bad
     * spot costs one try.
     */
    @Nullable
    static BlockPos spotNear(ServerLevel level, ServerPlayer player, int minDistance, int maxDistance,
                             Predicate<BlockPos> zone, RandomSource random) {
        double angle = random.nextDouble() * Math.PI * 2.0D;
        double distance = minDistance + random.nextDouble() * (maxDistance - minDistance);
        int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
        int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
        if (ParkBoundary.isOutside(x, z) || RestHouses.isNearSurfaceHouse(x, z, HOUSE_MARGIN)) {
            return null;
        }
        // Only loaded chunks: generating terrain for a spawn would stall the tick.
        BlockPos feet = Surface.loaded(level, x, z);
        if (feet == null) {
            return null;
        }
        BlockState below = level.getBlockState(feet.below());
        if (below.isAir() || !below.getFluidState().isEmpty() || !level.getBlockState(feet).isAir()
                || !level.getBlockState(feet.above()).isAir() || !level.canSeeSky(feet) || !zone.test(feet)) {
            return null;
        }
        return feet;
    }

    /**
     * Creates and places a Pokémon of the entry at a spot, as the specials'
     * spawner does, tags it and notes it under {@code source}; null when
     * another mod vetoed the entity.
     */
    @Nullable
    static PokemonEntity spawn(ServerLevel level, ServerPlayer player, HabitatEntry entry, BlockPos pos, String tag,
                               SpawnSource source, RandomSource random) {
        PokemonProperties properties = properties(entry);
        properties.setLevel(level(entry, random));
        // The player is passed so shiny-chance modifiers such as a Shiny Charm apply.
        PokemonEntity pokemonEntity = properties.createEntity(level, player);
        pokemonEntity.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, random.nextFloat() * 360.0F, 0.0F);
        pokemonEntity.addTag(tag);
        // Noted only once really added: another mod may veto the entity.
        if (!level.addFreshEntity(pokemonEntity)) {
            return null;
        }
        SpecialSpawnCounter.recordSpawn(level.getServer(), entry.species(), source,
                pokemonEntity.getUUID(), pokemonEntity.getPokemon().getUuid(), pos);
        return pokemonEntity;
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

    /** The species' most common natural spawn (form, aspects), as the specials start from. */
    private static PokemonProperties properties(HabitatEntry entry) {
        PokemonProperties properties = entry.template() != null ? entry.template().copy() : new PokemonProperties();
        properties.setSpecies(entry.species().getResourceIdentifier().toString());
        return properties;
    }

    /** A level within the species' natural range in the Safari, or the fallback when unknown. */
    private static int level(HabitatEntry entry, RandomSource random) {
        int min = entry.minLevel() > 0 ? entry.minLevel() : FALLBACK_MIN_LEVEL;
        int max = entry.maxLevel() >= min ? entry.maxLevel() : Math.max(min, FALLBACK_MAX_LEVEL);
        return min + random.nextInt(max - min + 1);
    }
}
