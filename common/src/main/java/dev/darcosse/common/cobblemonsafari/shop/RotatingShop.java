package dev.darcosse.common.cobblemonsafari.shop;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.evolution.PreEvolution;
import com.cobblemon.mod.common.api.pokemon.labels.CobblemonPokemonLabels;
import com.cobblemon.mod.common.pokemon.Species;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariShopConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariShopConfigManager;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedRotation;
import dev.darcosse.common.cobblemonsafari.spawn.HabitatEntry;
import dev.darcosse.common.cobblemonsafari.spawn.Rarity;
import dev.darcosse.common.cobblemonsafari.spawn.SwarmRules;
import dev.darcosse.common.cobblemonsafari.world.SafariWorldState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * The shop's random Pokémon ({@code rotatingPokemon} of a tab or section):
 * species that live in the Safari, drawn anew with each rotation of the
 * specials and sold after the shop's fixed offers. {@code count} ordinary
 * ones, by rarity weight, plus the legendary-class ones each allowed
 * category adds on top.
 *
 * <p>Deterministic like the specials: same world, same rotation, same data,
 * same Pokémon, for every player and across restarts. Cached until the
 * rotation, the specials' data or the shop file changes. A Pokémon the shop
 * file already sells (same species and form) is never drawn, so it is not
 * listed twice; another form of that species still can be. Server side.
 */
public final class RotatingShop {

    /** Mixed into the seed so the shop never draws in step with the specials. */
    private static final long SHOP_SEED_MIX = 0xC2B2AE3D27D4EB4FL;

    /**
     * Price multiplier by evolution stage, applied to the rarity's base
     * price: first stage, second stage, third stage and beyond. A Pokémon's
     * spawn bucket says how often it appears, not how strong it is (Cobblemon
     * files many final evolutions as common), so the stage carries the
     * strength. Kept gentle so a third stage of a rare species stays within
     * a few hours of hunting. Raising the later values makes evolved Pokémon
     * a bigger purchase; at 1 they cost the same as their first stage again.
     */
    private static final double[] STAGE_MULTIPLIERS = {1.0, 2.0, 3.0};

    /**
     * Stage priced for a species that neither evolves nor evolves from
     * anything (Tauros, Heracross...): fully grown at once, so priced like
     * the end of a two-stage line rather than like a first stage.
     */
    private static final int STANDALONE_STAGE = 1;

    /**
     * Prices are rounded to this many points, so the cards read 650 rather
     * than 625. Larger steps hide the multipliers' detail; 1 shows exact
     * products.
     */
    private static final int PRICE_STEP = 50;

    /**
     * Level used when Cobblemon's spawn data gives none for a species. The
     * random Pokémon normally come at the lowest level they spawn at in the
     * Safari, so an evolved one is not sold below its evolution level.
     */
    private static final int FALLBACK_LEVEL = 15;

    /** Lowest and highest level Cobblemon accepts by default; a datapack level outside is clamped. */
    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 100;

    @Nullable
    private static List<SafariShopConfig.Entry> cached;
    private static long cachedPeriod = Long.MIN_VALUE;
    private static long cachedRevision = Long.MIN_VALUE;
    @Nullable
    private static SafariShopConfig cachedConfig;

    private RotatingShop() {
    }

    /** Index of the rotation the random Pokémon belong to; the shop screen sends it back with a purchase. */
    public static long period() {
        return FeaturedRotation.period();
    }

    /**
     * The random Pokémon of the current rotation, as shop offers, shelf by
     * shelf; empty when no shelf draws any or while Cobblemon's data is not
     * loaded yet.
     */
    public static List<SafariShopConfig.Entry> entries(MinecraftServer server) {
        SafariShopConfig config = SafariShopConfigManager.get();
        long period = period();
        // today() first: it may start a new draw of the specials, which moves the revision.
        List<HabitatEntry> specials = FeaturedRotation.today(server);
        long revision = FeaturedRotation.revision();
        if (cached != null && period == cachedPeriod && revision == cachedRevision && config == cachedConfig) {
            return cached;
        }
        Map<ResourceLocation, HabitatEntry> index = FeaturedRotation.index(server);
        if (index.isEmpty()) {
            // Not cached: Cobblemon is still loading, the next opening retries.
            return List.of();
        }
        long salt = SafariWorldState.get(server).rotationSalt();
        Set<String> sold = fixedForms(config);
        List<SafariShopConfig.Entry> drawn = new ArrayList<>();
        for (SafariShopConfigManager.ShelfRef ref : SafariShopConfigManager.shelves()) {
            SafariShopConfig.RotatingPokemon rotating = ref.shelf().rotatingPokemon;
            if (rotating == null || !rotating.enabled) {
                continue;
            }
            // Each shelf its own seed: two shelves with the same rules still differ.
            long seed = salt ^ (period * SHOP_SEED_MIX) ^ (ref.tab() + "/" + ref.section()).hashCode();
            drawn.addAll(draw(index, specials, sold, rotating, ref, new Random(seed)));
        }
        cached = List.copyOf(drawn);
        cachedPeriod = period;
        cachedRevision = revision;
        cachedConfig = config;
        CobblemonSafari.LOGGER.info("Safari shop Pokémon for rotation {}: {}", period,
                drawn.stream().map(entry -> entry.pokemon).toList());
        return cached;
    }

    /**
     * Showdown ids (species and form, e.g. {@code vulpixalola}) of the
     * Pokémon the shop file sells. Read from the Pokémon each entry builds,
     * because the properties may name the species and form by any alias
     * Cobblemon accepts; the level and other properties do not matter. An
     * entry that fails to build is skipped here: the shop already lists it as
     * unavailable and logs it. Built once per draw (a rotation or a shop file
     * change), not per opening.
     */
    private static Set<String> fixedForms(SafariShopConfig config) {
        Set<String> sold = new HashSet<>();
        for (SafariShopConfig.Entry entry : config.entries) {
            if (!entry.isPokemon()) {
                continue;
            }
            String form = formOf(entry.pokemon);
            if (form != null) {
                sold.add(form);
            }
        }
        return sold;
    }

    /**
     * Showdown id of the species and form a properties string builds, or null
     * when it does not build. Cobblemon's FormData compares by this same id.
     */
    @Nullable
    private static String formOf(String properties) {
        try {
            return PokemonProperties.Companion.parse(properties).create().getForm().showdownId();
        } catch (RuntimeException e) {
            // Reported by SafariShop when an entry is shown; nothing to compare.
            return null;
        }
    }

    /**
     * Whether a candidate of the draw is a Pokémon the shop file already
     * sells. Only candidates whose species id starts one of the sold ids are
     * built, so the draw builds a handful of Pokémon at most, not the whole
     * pool.
     */
    private static boolean alreadySold(HabitatEntry entry, Set<String> sold) {
        if (sold.isEmpty()) {
            return false;
        }
        String species = entry.species().showdownId();
        boolean candidate = false;
        for (String id : sold) {
            if (id.startsWith(species)) {
                candidate = true;
                break;
            }
        }
        if (!candidate) {
            return false;
        }
        String form = formOf(properties(entry, level(entry)));
        return form != null && sold.contains(form);
    }

    /**
     * Draws one shelf: {@code count} ordinary Pokémon, each slot going to a
     * rarity by weight, then up to {@code maxPerRotation} of each allowed
     * legendary-class category. Never a species and form of {@code sold}
     * (the shop file's own Pokémon), nor an excluded one.
     */
    private static List<SafariShopConfig.Entry> draw(Map<ResourceLocation, HabitatEntry> index,
                                                     List<HabitatEntry> specials, Set<String> sold,
                                                     SafariShopConfig.RotatingPokemon config,
                                                     SafariShopConfigManager.ShelfRef ref, Random random) {
        Set<Species> featured = new HashSet<>();
        if (config.excludeFeatured) {
            for (HabitatEntry special : specials) {
                featured.add(special.species());
            }
        }
        List<SwarmRules.Exclusion> exclusions = SwarmRules.parseExclusions(config.excludeList);
        // The index is sorted by species id: the pools are built in a stable
        // order before drawing, which the draw needs to be reproducible.
        List<HabitatEntry> ordinary = new ArrayList<>();
        Map<String, List<HabitatEntry>> byCategory = new LinkedHashMap<>();
        for (HabitatEntry entry : index.values()) {
            Species species = entry.species();
            if (!entry.natural() || featured.contains(species) || excluded(entry, exclusions)
                    || alreadySold(entry, sold)) {
                continue;
            }
            String category = category(species);
            if (category == null) {
                SafariShopConfig.RarityRule rule = rule(entry.rarity(), config.rarities);
                if (rule.allowed && rule.weight > 0) {
                    ordinary.add(entry);
                }
            } else if (categoriesAllow(species, config.categories)) {
                byCategory.computeIfAbsent(category, key -> new ArrayList<>()).add(entry);
            }
        }

        List<HabitatEntry> picked = weightedSample(ordinary, config.count,
                entry -> rule(entry.rarity(), config.rarities).weight, random);
        picked.sort(Comparator.comparingInt(entry -> entry.rarity().ordinal()));
        List<SafariShopConfig.Entry> out = new ArrayList<>(picked.size());
        for (HabitatEntry entry : picked) {
            int price = price(entry.rarity(), entry.species(), config.rarities);
            out.add(offer(entry, price, config.limit, config, ref, random));
        }
        // Legendary-class ones on top of count, in category order.
        for (Map.Entry<String, List<HabitatEntry>> group : byCategory.entrySet()) {
            SafariShopConfig.CategoryRule rule = categoryRule(group.getKey(), config.categories);
            List<HabitatEntry> pool = new ArrayList<>(group.getValue());
            Collections.shuffle(pool, random);
            for (HabitatEntry entry : pool.subList(0, Math.min(rule.maxPerRotation, pool.size()))) {
                SafariShopConfig.Limit limit = rule.limit != null ? rule.limit : config.limit;
                out.add(offer(entry, rule.price, limit, config, ref, random));
            }
        }
        return List.copyOf(out);
    }

    /**
     * One drawn Pokémon as an offer: its level by the draw's mode, shiny by
     * chance (and priced up for it).
     */
    private static SafariShopConfig.Entry offer(HabitatEntry entry, int basePrice,
                                                @Nullable SafariShopConfig.Limit limit,
                                                SafariShopConfig.RotatingPokemon config,
                                                SafariShopConfigManager.ShelfRef ref, Random random) {
        int level = SafariShopConfig.Level.RANGE.equals(config.level.mode)
                ? config.level.min + random.nextInt(config.level.max - config.level.min + 1)
                : level(entry);
        String properties = properties(entry, level);
        int price = basePrice;
        // Drawn even when the chance is 0, so a change of chance does not
        // shift every later draw of the rotation.
        if (random.nextDouble() < config.modifiers.shinyChance) {
            properties += " shiny=yes";
            price = round(basePrice * config.modifiers.shinyPriceMultiplier);
        }
        return SafariShopConfig.Entry.drawnPokemon(properties, price, limit, ref.tab(), ref.section());
    }

    /**
     * {@code count} entries picked without repeat, each with a chance
     * proportional to its weight (Efraimidis-Spirakis: the largest
     * {@code u^(1/w)} keys win). Deterministic for a given random source and
     * pool order.
     */
    private static List<HabitatEntry> weightedSample(List<HabitatEntry> pool, int count,
                                                     ToIntFunction<HabitatEntry> weight,
                                                     Random random) {
        List<Map.Entry<Double, HabitatEntry>> keyed = new ArrayList<>(pool.size());
        for (HabitatEntry entry : pool) {
            double key = Math.pow(random.nextDouble(), 1.0 / weight.applyAsInt(entry));
            keyed.add(Map.entry(key, entry));
        }
        keyed.sort(Map.Entry.<Double, HabitatEntry>comparingByKey().reversed());
        List<HabitatEntry> picked = new ArrayList<>(Math.min(count, keyed.size()));
        for (int i = 0; i < Math.min(count, keyed.size()); i++) {
            picked.add(keyed.get(i).getValue());
        }
        return picked;
    }

    /** Whether the excludeList leaves this Pokémon out, form by form (see {@link SwarmRules}). */
    private static boolean excluded(HabitatEntry entry, List<SwarmRules.Exclusion> exclusions) {
        if (exclusions.isEmpty()) {
            return false;
        }
        String id = entry.species().getResourceIdentifier().toString();
        PokemonProperties properties = entry.template() != null ? entry.template()
                : PokemonProperties.Companion.parse(id);
        return SwarmRules.isExcluded(exclusions, id, properties);
    }

    /**
     * The legendary-class category a species is sold under, by label in the
     * order legendary, mythical, ultra beast, paradox; null for an ordinary one.
     */
    @Nullable
    static String category(Species species) {
        Set<String> labels = species.getLabels();
        if (labels.contains(CobblemonPokemonLabels.LEGENDARY)) {
            return CobblemonPokemonLabels.LEGENDARY;
        }
        if (labels.contains(CobblemonPokemonLabels.MYTHICAL)) {
            return CobblemonPokemonLabels.MYTHICAL;
        }
        if (labels.contains(CobblemonPokemonLabels.ULTRA_BEAST)) {
            return CobblemonPokemonLabels.ULTRA_BEAST;
        }
        if (labels.contains(CobblemonPokemonLabels.PARADOX)) {
            return CobblemonPokemonLabels.PARADOX;
        }
        return null;
    }

    /** Whether every legendary-class category the species carries is allowed in the draw. */
    private static boolean categoriesAllow(Species species, SafariShopConfig.Categories categories) {
        Set<String> labels = species.getLabels();
        return (!labels.contains(CobblemonPokemonLabels.LEGENDARY) || categories.legendary.allowed)
                && (!labels.contains(CobblemonPokemonLabels.MYTHICAL) || categories.mythical.allowed)
                && (!labels.contains(CobblemonPokemonLabels.ULTRA_BEAST) || categories.ultraBeast.allowed)
                && (!labels.contains(CobblemonPokemonLabels.PARADOX) || categories.paradox.allowed);
    }

    /** The rule of a category label. */
    private static SafariShopConfig.CategoryRule categoryRule(String label, SafariShopConfig.Categories categories) {
        if (CobblemonPokemonLabels.LEGENDARY.equals(label)) {
            return categories.legendary;
        }
        if (CobblemonPokemonLabels.MYTHICAL.equals(label)) {
            return categories.mythical;
        }
        if (CobblemonPokemonLabels.ULTRA_BEAST.equals(label)) {
            return categories.ultraBeast;
        }
        return categories.paradox;
    }

    /** The rule of a rarity. */
    private static SafariShopConfig.RarityRule rule(Rarity rarity, SafariShopConfig.Rarities rarities) {
        return switch (rarity) {
            case COMMON -> rarities.common;
            case UNCOMMON -> rarities.uncommon;
            case RARE -> rarities.rare;
            case ULTRA_RARE -> rarities.ultraRare;
        };
    }

    /**
     * Properties of the Pokémon sold: its natural spawn's (so a regional form
     * found in the Safari is the one sold) at the configured level.
     */
    private static String properties(HabitatEntry entry, int level) {
        PokemonProperties template = entry.template();
        if (template == null) {
            return entry.species().getResourceIdentifier() + " level=" + level;
        }
        PokemonProperties copy = template.copy();
        copy.setLevel(level);
        return copy.asString(" ");
    }

    /**
     * Level of the Pokémon sold: the lowest level it naturally spawns at in
     * the Safari, so a Staraptor comes at the level wild ones are met, not
     * below the level a Starly evolves.
     */
    private static int level(HabitatEntry entry) {
        int level = entry.minLevel() > 0 ? entry.minLevel() : FALLBACK_LEVEL;
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }

    /**
     * The computed price of a species, for a shop offer priced {@code "auto"}:
     * the same rule as the random Pokémon of the first shelf that draws any
     * (the defaults when none does). A legendary-class species costs its
     * category's price; another species without Safari spawn data gets the
     * rarity the habitat index gives such species, so it is never sold cheap.
     */
    public static int autoPrice(MinecraftServer server, Species species) {
        SafariShopConfig.RotatingPokemon config = new SafariShopConfig.RotatingPokemon();
        for (SafariShopConfigManager.ShelfRef ref : SafariShopConfigManager.shelves()) {
            if (ref.shelf().rotatingPokemon != null) {
                config = ref.shelf().rotatingPokemon;
                break;
            }
        }
        String category = category(species);
        if (category != null) {
            return categoryRule(category, config.categories).price;
        }
        HabitatEntry entry = FeaturedRotation.index(server).get(species.getResourceIdentifier());
        Rarity rarity = entry != null ? entry.rarity() : Rarity.RARE;
        return price(rarity, species, config.rarities);
    }

    /** Price of a Pokémon: its rarity's base price times its evolution stage's multiplier, rounded. */
    private static int price(Rarity rarity, Species species, SafariShopConfig.Rarities rarities) {
        return round(rule(rarity, rarities).price * STAGE_MULTIPLIERS[stage(species)]);
    }

    /** A price rounded to {@link #PRICE_STEP}. */
    private static int round(double price) {
        return (int) (Math.round(price / PRICE_STEP) * PRICE_STEP);
    }

    /**
     * Evolution stage of a species, 0 for a first stage, capped at the last
     * multiplier. Counted by walking pre-evolutions; a species alone in its
     * line counts as {@link #STANDALONE_STAGE}.
     */
    private static int stage(Species species) {
        int last = STAGE_MULTIPLIERS.length - 1;
        int stage = 0;
        Species current = species;
        // Capped walk: a datapack could declare a pre-evolution loop.
        while (stage < last) {
            PreEvolution pre = current.getPreEvolution();
            if (pre == null || pre.getSpecies() == null || pre.getSpecies() == species) {
                break;
            }
            current = pre.getSpecies();
            stage++;
        }
        if (stage == 0 && species.getEvolutions().isEmpty()) {
            return Math.min(STANDALONE_STAGE, last);
        }
        return stage;
    }
}
