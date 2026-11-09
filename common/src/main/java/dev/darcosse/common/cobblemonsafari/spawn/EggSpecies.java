package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.pokemon.egg.EggGroup;
import com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which Pokémon an egg of a Safari nest is: an evolution line of Cobblemon's
 * implemented species, every allowed line as likely, hatching into the line's
 * first stage. A nest's egg comes from a line living in the habitat where the
 * nest lies ({@link #drawFor}); an egg with no known place draws among every
 * line ({@link #draw}). The lines are grouped once per data reload; the
 * config's filters are applied at each draw, so a config reload acts at once.
 * Server side.
 */
public final class EggSpecies {

    /**
     * One evolution line: its first stage, what hatches, and every
     * implemented species of it, whose egg groups and labels decide whether
     * the line may be drawn.
     */
    record Line(Species root, List<Species> members) {
    }

    /** The lines, built at the first draw after a start or a data reload. */
    @Nullable
    private static volatile List<Line> lines;

    /** Egg group names already reported as unknown, so the log says it once per name. */
    private static final Set<String> WARNED_GROUPS = new HashSet<>();

    private EggSpecies() {
    }

    /** Forgets the lines; the species data may have changed. Called on data reload. */
    public static void invalidate() {
        lines = null;
    }

    /**
     * A species for an egg found at a spot: drawn among the allowed lines one
     * species of which lives in the spot's biome, on land or in the water, as
     * the specials board lists it ({@link FeaturedRotation#index}). A line
     * counts as soon as one of its stages lives there (Magmar in the volcano
     * gives a Magby egg). Null when no allowed line lives there, when the spot
     * is not in a Safari habitat, or while the habitat index is still empty.
     * Cost: one index lookup per species of each allowed line, about a
     * thousand, only when a nest is placed.
     */
    @Nullable
    public static Species drawFor(ServerLevel level, BlockPos pos, RandomSource random) {
        ResourceKey<Biome> biome = level.getBiome(pos).unwrapKey().orElse(null);
        if (biome == null) {
            return null;
        }
        Map<ResourceLocation, HabitatEntry> index = FeaturedRotation.index(level.getServer());
        return drawAmong(random, line -> livesIn(line, biome, index));
    }

    /**
     * A species for an egg with no known place (a creative egg, a nest summoned
     * outside a habitat), drawn among every line the config allows; null when
     * it allows none.
     */
    @Nullable
    public static Species draw(RandomSource random) {
        return drawAmong(random, line -> true);
    }

    /** Whether one species of the line lives in the biome, on land or in the water. */
    private static boolean livesIn(Line line, ResourceKey<Biome> biome, Map<ResourceLocation, HabitatEntry> index) {
        for (Species species : line.members()) {
            HabitatEntry entry = index.get(species.getResourceIdentifier());
            if (entry != null && (entry.landHabitats().contains(biome) || entry.waterHabitats().contains(biome))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Draws the first stage of one line among those the config allows and the
     * given test accepts, every such line as likely; null when there is none.
     */
    @Nullable
    private static Species drawAmong(RandomSource random, Predicate<Line> accepted) {
        SafariConfig config = SafariConfigManager.get();
        SafariConfig.EggNests nests = config.events.eggNests;
        Set<EggGroup> excludedGroups = parseGroups(nests.excludedEggGroups);
        Set<String> excluded = new HashSet<>();
        for (String id : nests.excludeList) {
            if (id != null && !id.isBlank()) {
                excluded.add(SpeciesRules.normalizeId(id.trim()));
            }
        }
        List<Species> candidates = new ArrayList<>();
        for (Line line : lines()) {
            String rootId = line.root().getResourceIdentifier().toString();
            // The Safari-wide exclusion holds for eggs too: a species kept out of the park stays out.
            if (excluded.contains(rootId) || config.spawns.excludedSpecies.contains(rootId)) {
                continue;
            }
            if (hasEggGroups(line, excludedGroups) && categoriesAllowed(line, nests.rarities) && accepted.test(line)) {
                candidates.add(line.root());
            }
        }
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    /**
     * Whether one species of the line has egg groups and none of them is
     * excluded. Per species rather than per line because the line's baby is
     * often Undiscovered while its adults breed (Pichu and Pikachu).
     */
    private static boolean hasEggGroups(Line line, Set<EggGroup> excludedGroups) {
        for (Species species : line.members()) {
            Set<EggGroup> groups = species.getEggGroups();
            if (!groups.isEmpty() && groups.stream().noneMatch(excludedGroups::contains)) {
                return true;
            }
        }
        return false;
    }

    /** Whether every legendary-class label found in the line is allowed. */
    private static boolean categoriesAllowed(Line line, SafariConfig.EggCategories rarities) {
        for (Species species : line.members()) {
            Set<String> labels = species.getLabels();
            if ((!rarities.legendary && labels.contains(CobblemonPokemonLabels.LEGENDARY))
                    || (!rarities.mythical && labels.contains(CobblemonPokemonLabels.MYTHICAL))
                    || (!rarities.ultraBeast && labels.contains(CobblemonPokemonLabels.ULTRA_BEAST))
                    || (!rarities.paradox && labels.contains(CobblemonPokemonLabels.PARADOX))) {
                return false;
            }
        }
        return true;
    }

    /** The config's egg group names as Cobblemon's groups; unknown names are logged once and skipped. */
    private static Set<EggGroup> parseGroups(List<String> names) {
        Set<EggGroup> groups = EnumSet.noneOf(EggGroup.class);
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            EggGroup group = EggGroup.Companion.fromIdentifier(name.trim());
            if (group != null) {
                groups.add(group);
            } else if (WARNED_GROUPS.add(name)) {
                CobblemonSafari.LOGGER.warn("events.eggNests.excludedEggGroups: \"{}\" is not an egg group, ignored", name);
            }
        }
        return groups;
    }

    /** The lines, grouped by first stage; built once per data reload. */
    private static List<Line> lines() {
        List<Line> built = lines;
        if (built == null) {
            Map<Species, List<Species>> byRoot = new LinkedHashMap<>();
            for (Species species : PokemonSpecies.INSTANCE.getImplemented()) {
                Species root = SpeciesRules.lineRoot(species);
                // A first stage Cobblemon has not implemented cannot hatch; its line waits for it.
                if (root.getImplemented()) {
                    byRoot.computeIfAbsent(root, key -> new ArrayList<>()).add(species);
                }
            }
            List<Line> grouped = new ArrayList<>(byRoot.size());
            byRoot.forEach((root, members) -> grouped.add(new Line(root, List.copyOf(members))));
            built = List.copyOf(grouped);
            lines = built;
        }
        return built;
    }
}
