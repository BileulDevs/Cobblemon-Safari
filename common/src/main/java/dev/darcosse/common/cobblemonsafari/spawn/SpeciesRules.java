package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.pokemon.evolution.PreEvolution;
import com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;

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

    /** Whether the species may appear in the Safari under the current config. */
    public static boolean isAllowed(Species species) {
        SafariConfig.Spawns config = SafariConfigManager.get().spawns;
        if (config.excludedSpecies.contains(species.getResourceIdentifier().toString())) {
            return false;
        }
        Set<String> labels = species.getLabels();
        return (config.allowLegendary || !labels.contains(CobblemonPokemonLabels.LEGENDARY))
                && (config.allowMythical || !labels.contains(CobblemonPokemonLabels.MYTHICAL))
                && (config.allowUltraBeast || !labels.contains(CobblemonPokemonLabels.ULTRA_BEAST))
                && (config.allowParadox || !labels.contains(CobblemonPokemonLabels.PARADOX));
    }
}
