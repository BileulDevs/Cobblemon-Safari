package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.pokemon.evolution.PreEvolution;
import com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decides which species may appear in the Safari at all: special categories
 * (legendary, mythical, ultra beast, paradox) and the excluded list.
 *
 * <p>Applied to both Cobblemon's native spawns and the mod's special spawns,
 * so a forbidden species can never come in through either door.
 */
public final class SpeciesRules {

    /** Labels that make a species "special", whatever the config allows. */
    private static final Set<String> SPECIAL_LABELS = Set.of(
            CobblemonPokemonLabels.LEGENDARY,
            CobblemonPokemonLabels.MYTHICAL,
            CobblemonPokemonLabels.ULTRA_BEAST,
            CobblemonPokemonLabels.PARADOX);

    /**
     * Label Cobblemon's species data puts on the first stage of every starter
     * (bulbasaur, cyndaquil, rowlet...). Not among CobblemonPokemonLabels'
     * constants in 1.8.0, hence the literal. Evolved starters do not carry
     * it, see {@link #isStarterLine}.
     */
    private static final String STARTER_LABEL = "starter";

    /** Most pre-evolution links followed; a datapack loop stops here. */
    private static final int MAX_EVOLUTION_DEPTH = 4;

    private SpeciesRules() {
    }

    /**
     * Whether the species belongs to a starter's line (Cyndaquil, Quilava or
     * Typhlosion), by the label of the line's first stage: Cobblemon labels
     * only that one.
     */
    public static boolean isStarterLine(Species species) {
        Species root = species;
        for (int i = 0; i < MAX_EVOLUTION_DEPTH; i++) {
            PreEvolution pre = root.getPreEvolution();
            if (pre == null || pre.getSpecies() == null) {
                break;
            }
            root = pre.getSpecies();
        }
        return root.getLabels().contains(STARTER_LABEL) || species.getLabels().contains(STARTER_LABEL);
    }

    /** Whether the species carries one of the special labels. */
    public static boolean isSpecial(Species species) {
        for (String label : species.getLabels()) {
            if (SPECIAL_LABELS.contains(label)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the species may appear in the Safari under the current config.
     * A species in several special categories needs all of them allowed.
     */
    public static boolean isAllowed(Species species) {
        SafariConfig.Spawns config = SafariConfigManager.get().spawns;
        if (config.excludedSpecies.contains(species.getResourceIdentifier().toString())) {
            return false;
        }
        for (SafariConfig.SpecialCategory category : categoriesOf(species, config)) {
            if (!category.allowSpawn) {
                return false;
            }
        }
        return true;
    }

    /**
     * Most times the species may appear per rotation, from the categories it
     * belongs to: the lowest positive cap among them, 0 when none sets one
     * (ordinary species, or caps all lifted).
     */
    public static int spawnCap(Species species) {
        int cap = 0;
        for (SafariConfig.SpecialCategory category : categoriesOf(species, SafariConfigManager.get().spawns)) {
            if (category.maxSpawnPerPokemon > 0) {
                cap = cap == 0 ? category.maxSpawnPerPokemon : Math.min(cap, category.maxSpawnPerPokemon);
            }
        }
        return cap;
    }

    /** The config sections of the special categories the species belongs to; empty for an ordinary species. */
    private static List<SafariConfig.SpecialCategory> categoriesOf(Species species, SafariConfig.Spawns config) {
        Set<String> labels = species.getLabels();
        List<SafariConfig.SpecialCategory> categories = new ArrayList<>(SPECIAL_LABELS.size());
        if (labels.contains(CobblemonPokemonLabels.LEGENDARY)) {
            categories.add(config.legendary);
        }
        if (labels.contains(CobblemonPokemonLabels.MYTHICAL)) {
            categories.add(config.mythical);
        }
        if (labels.contains(CobblemonPokemonLabels.ULTRA_BEAST)) {
            categories.add(config.ultraBeast);
        }
        if (labels.contains(CobblemonPokemonLabels.PARADOX)) {
            categories.add(config.paradox);
        }
        return categories;
    }
}
