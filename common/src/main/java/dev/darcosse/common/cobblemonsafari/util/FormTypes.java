package dev.darcosse.common.cobblemonsafari.util;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Species;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The types of the form a spawn or a template really makes, rather than the
 * species' standard form: an Alolan Vulpix is Ice, not Fire. The events
 * that favour types (full moon, downpour, heatwave, storm, aurora) go
 * through here so a regional form is boosted, or drawn, for its own types.
 * Both sides, no state.
 */
public final class FormTypes {

    private FormTypes() {
    }

    /**
     * Lower-case type names of the form the properties describe: the form
     * they name, else the one their aspects select (Cobblemon's own rule,
     * {@link Species#getForm}), else the standard form.
     */
    public static Set<String> of(Species species, @Nullable PokemonProperties properties) {
        Set<String> names = new HashSet<>();
        for (ElementalType type : formOf(species, properties).getTypes()) {
            names.add(type.getName().toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(names);
    }

    /** The form the properties describe; the standard one when they say nothing. */
    private static FormData formOf(Species species, @Nullable PokemonProperties properties) {
        if (properties == null) {
            return species.getStandardForm();
        }
        String form = properties.getForm();
        if (form != null && !form.isBlank()) {
            // Case-insensitive, by name or Showdown id: Cobblemon's getFormByName is exact, and the
            // properties written by hand ("form=alola") rarely match "Alola".
            for (FormData candidate : species.getForms()) {
                if (candidate.getName().equalsIgnoreCase(form)
                        || candidate.formOnlyShowdownId().equalsIgnoreCase(form)) {
                    return candidate;
                }
            }
        }
        return species.getForm(properties.getAspects());
    }
}
