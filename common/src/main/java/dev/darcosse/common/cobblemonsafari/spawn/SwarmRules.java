package dev.darcosse.common.cobblemonsafari.spawn;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The filters of {@code events.swarm} on what may swarm: the allowed buckets
 * ({@code buckets}) and their weights ({@code weights}) and the excluded
 * Pokémon ({@code excludeList}). The legendary-class categories
 * ({@code rarities}) go through {@link SpeciesRules} as {@link SpawnSource#SWARM}.
 *
 * <p>Exclusions are matched form by form, on the spawn details themselves:
 * {@code "vulpix alolan"} removes the details that spawn Alolan Vulpix and
 * leaves those of Kantonian Vulpix, while {@code "vulpix"} removes both. Both
 * sides go through Cobblemon's own property parser, so whatever it turns into
 * an aspect (regional flags, species features such as {@code striped=white})
 * is compared the same way on both.
 */
public final class SwarmRules {

    /**
     * The values {@code events.swarm.buckets} accepts, as written in the
     * config. Cobblemon's bucket names, the same spelling as the other rarity
     * lists of the config (missions' {@code rarities}, the shop's tiers).
     */
    public static final String COMMON = "common";
    public static final String UNCOMMON = "uncommon";
    public static final String RARE = "rare";
    public static final String ULTRA_RARE = "ultra-rare";

    private SwarmRules() {
    }

    /**
     * The config spelling of a rarity, forgiving about case and separators
     * ("Ultra-Rare", "ultra_rare" and "ultrarare", the spelling of the 1.4.1
     * development builds, all give "ultra-rare"); null when it names no rarity.
     */
    @Nullable
    public static String canonicalRarity(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String compact = raw.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
        return switch (compact) {
            case COMMON, UNCOMMON, RARE -> compact;
            case "ultrarare" -> ULTRA_RARE;
            default -> null;
        };
    }

    /** The rarities a list of config values allows; values naming no rarity are ignored. */
    public static Set<Rarity> allowedBuckets(List<String> values) {
        Set<Rarity> allowed = EnumSet.noneOf(Rarity.class);
        for (String value : values) {
            String canonical = canonicalRarity(value);
            if (canonical == null) {
                continue;
            }
            allowed.add(switch (canonical) {
                case COMMON -> Rarity.COMMON;
                case UNCOMMON -> Rarity.UNCOMMON;
                case RARE -> Rarity.RARE;
                default -> Rarity.ULTRA_RARE;
            });
        }
        return allowed;
    }

    /** The draw weight of a bucket, from {@code events.swarm.weights}. */
    public static int weightOf(Rarity rarity, SafariConfig.SpawnWeights weights) {
        return switch (rarity) {
            case COMMON -> weights.common;
            case UNCOMMON -> weights.uncommon;
            case RARE -> weights.rare;
            case ULTRA_RARE -> weights.ultraRare;
        };
    }

    /**
     * One parsed entry of {@code excludeList}.
     *
     * @param speciesId the species, normalised ("cobblemon:vulpix")
     * @param form      the form named with {@code form=}, or null
     * @param aspects   the aspects Cobblemon derives from the entry ("alolan"...); empty for a bare species
     * @param raw       the entry as written, for the log
     */
    public record Exclusion(String speciesId, @Nullable String form, Set<String> aspects, String raw) {
    }

    /**
     * Parses {@code excludeList}. Needs Cobblemon's species loaded: the parser
     * only recognises a species it knows. Entries it cannot use are logged and
     * skipped, never widened: an entry whose qualifier was not understood
     * would otherwise exclude every form of the species.
     */
    public static List<Exclusion> parseExclusions(List<String> entries) {
        List<Exclusion> parsed = new ArrayList<>(entries.size());
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            PokemonProperties properties;
            try {
                properties = PokemonProperties.Companion.parse(raw.trim());
            } catch (RuntimeException e) {
                CobblemonSafari.LOGGER.warn("events.swarm.excludeList: cannot read \"{}\" ({}), ignored", raw, e.getMessage());
                continue;
            }
            String species = properties.getSpecies();
            if (species == null || species.isBlank() || "random".equals(species)) {
                CobblemonSafari.LOGGER.warn("events.swarm.excludeList: \"{}\" names no known species, ignored", raw);
                continue;
            }
            boolean qualified = raw.trim().indexOf(' ') >= 0;
            boolean understood = properties.getForm() != null || !properties.getAspects().isEmpty()
                    || !properties.getCustomProperties().isEmpty();
            if (qualified && !understood) {
                CobblemonSafari.LOGGER.warn("events.swarm.excludeList: the form in \"{}\" is not one Cobblemon knows, ignored"
                        + " (write the species alone to exclude all its forms)", raw);
                continue;
            }
            parsed.add(new Exclusion(SpeciesRules.normalizeId(species), properties.getForm(),
                    Set.copyOf(properties.getAspects()), raw));
        }
        return List.copyOf(parsed);
    }

    /**
     * Whether a spawn detail's Pokémon is excluded: same species, and every
     * aspect (and the form, if any) the entry asks for is on the detail. A
     * bare species asks for nothing more, so it excludes every form.
     *
     * <p>Limit: a detail that only gets its form when the Pokémon is created
     * ({@code region_bias=hisui}) carries no form aspect yet; such a form can
     * only be excluded with its species.
     *
     * @param speciesId the detail's species, normalised
     * @param pokemon   the detail's Pokémon properties
     */
    public static boolean isExcluded(List<Exclusion> exclusions, String speciesId, PokemonProperties pokemon) {
        return matches(exclusions, speciesId, pokemon.getForm(), pokemon.getAspects());
    }

    /**
     * Whether an existing Pokémon is of an excluded form, by the same rule as
     * the spawn details; for what is decided after the spawn (the swarm mark).
     */
    public static boolean isExcluded(List<Exclusion> exclusions, Pokemon pokemon) {
        return matches(exclusions, pokemon.getSpecies().getResourceIdentifier().toString(),
                pokemon.getForm().getName(), pokemon.getAspects());
    }

    /** The matching rule shared by spawn details and Pokémon. */
    private static boolean matches(List<Exclusion> exclusions, String speciesId, @Nullable String form,
                                   Set<String> aspects) {
        for (Exclusion exclusion : exclusions) {
            if (!exclusion.speciesId().equals(speciesId)) {
                continue;
            }
            if (exclusion.form() != null) {
                boolean sameForm = (form != null && form.equalsIgnoreCase(exclusion.form()))
                        || aspects.contains(exclusion.form().toLowerCase(Locale.ROOT));
                if (!sameForm) {
                    continue;
                }
            }
            if (aspects.containsAll(exclusion.aspects())) {
                return true;
            }
        }
        return false;
    }
}
