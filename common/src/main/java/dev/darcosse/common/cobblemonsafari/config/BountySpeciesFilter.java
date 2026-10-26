package dev.darcosse.common.cobblemonsafari.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Which species the missions are drawn from ({@code bounties.speciesFilter}).
 * It decides the species missions' targets, the types a type mission may
 * ask and the habitats a habitat mission may name, so none asks for a
 * Pokémon too hard to find.
 *
 * <p>A species is kept when one of its natural spawns in the Safari is of an
 * allowed rarity and passes every switch below: a species that also spawns
 * in a plain way somewhere (on dry land, in the daylight, whatever the
 * weather) stays, even if its other spawns are fished or nocturnal.
 * Legendary-class species are always left out, and so is anything
 * {@code spawns} refuses.
 */
public class BountySpeciesFilter {
    /**
     * Rarities a mission may ask, as Cobblemon spawn buckets: {@code common},
     * {@code uncommon}, {@code rare}, {@code ultra-rare}. Rarer species take
     * much longer to find within a rotation: a rare one is asked once
     * ({@code kinds.species.rareCount}); ultra-rare ones are left out by
     * default, as they can take a whole rotation to meet.
     */
    public List<String> rarities = new ArrayList<>(List.of("common", "uncommon", "rare"));
    /** Species never asked, e.g. {@code "cobblemon:ditto"}. */
    public List<String> excludedSpecies = new ArrayList<>();
    /** Types never asked by a type mission, and whose species are never asked either, e.g. {@code "dragon"}. */
    public List<String> excludedTypes = new ArrayList<>();
    /** Species met only in the water (fishing, surfacing, diving): false leaves them out. */
    public boolean allowWater = false;
    /** Species met only underground, in the caves: false leaves them out. The island's caves are easy to reach. */
    public boolean allowCaves = true;
    /** Species met only at some hours (night, dawn, dusk): false leaves them out. */
    public boolean allowTimeRestricted = false;
    /** Species met only in some weather (rain, thunder): false leaves them out. */
    public boolean allowWeatherRestricted = false;
}
