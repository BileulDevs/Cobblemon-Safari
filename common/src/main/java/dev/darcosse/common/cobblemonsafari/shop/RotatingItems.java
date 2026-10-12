package dev.darcosse.common.cobblemonsafari.shop;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariShopConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariShopConfigManager;
import dev.darcosse.common.cobblemonsafari.spawn.FeaturedRotation;
import dev.darcosse.common.cobblemonsafari.world.SafariWorldState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * The shop's random items and balls ({@code rotatingItems} and
 * {@code rotatingBalls} of the shop file): a few of the items the price rules
 * cover, drawn anew with each rotation and sold after the fixed entries.
 *
 * <p>Deterministic like the random Pokémon: same world, same rotation, same
 * data, same items, for every player and across restarts. Cached until the
 * rotation, the data (a datapack reload moves the specials' revision) or the
 * shop file changes. Server side.
 */
public final class RotatingItems {

    /**
     * Mixed into the seeds so the items, the balls, the Pokémon and the
     * specials never draw in step. Any distinct constants work; changing one
     * changes that draw for the current rotation.
     */
    private static final long ITEMS_SEED_MIX = 0x9E3779B97F4A7C15L;
    private static final long BALLS_SEED_MIX = 0xBF58476D1CE4E5B9L;

    @Nullable
    private static List<SafariShopConfig.Entry> cached;
    private static long cachedPeriod = Long.MIN_VALUE;
    private static long cachedRevision = Long.MIN_VALUE;
    @Nullable
    private static SafariShopConfig cachedConfig;

    private RotatingItems() {
    }

    /** The random items then the random balls of the current rotation, as shop entries. */
    public static List<SafariShopConfig.Entry> entries(MinecraftServer server) {
        SafariShopConfig config = SafariShopConfigManager.get();
        long period = RotatingShop.period();
        long revision = FeaturedRotation.revision();
        if (cached != null && period == cachedPeriod && revision == cachedRevision && config == cachedConfig) {
            return cached;
        }
        long salt = SafariWorldState.get(server).rotationSalt();
        Set<ResourceLocation> fixed = fixedItems(config);
        List<SafariShopConfig.Entry> drawn = new ArrayList<>();
        drawn.addAll(draw(config.rotatingItems, fixed, salt ^ (period * ITEMS_SEED_MIX), "rotatingItems"));
        drawn.addAll(draw(config.rotatingBalls, fixed, salt ^ (period * BALLS_SEED_MIX), "rotatingBalls"));
        cached = List.copyOf(drawn);
        cachedPeriod = period;
        cachedRevision = revision;
        cachedConfig = config;
        CobblemonSafari.LOGGER.info("Safari shop items for rotation {}: {}", period,
                drawn.stream().map(entry -> entry.item).toList());
        return cached;
    }

    /**
     * Draws one section: its {@link SafariShopConfig.RotatingGoods#always}
     * items, then {@code count} items at random among those its price rules
     * cover, leaving out excluded items and those the fixed entries sell.
     */
    private static List<SafariShopConfig.Entry> draw(SafariShopConfig.RotatingGoods goods, Set<ResourceLocation> fixed,
                                                     long seed, String name) {
        if (!goods.enabled) {
            return List.of();
        }
        Map<ResourceLocation, SafariShopConfig.PriceRule> priced = pricedItems(goods.prices);
        Set<ResourceLocation> taken = new HashSet<>(fixed);
        taken.addAll(resolve(goods.excluded));

        List<SafariShopConfig.Entry> out = new ArrayList<>();
        for (String text : goods.always) {
            ResourceLocation id = ResourceLocation.tryParse(text);
            SafariShopConfig.PriceRule rule = id == null ? null : priced.get(id);
            if (rule == null) {
                CobblemonSafari.LOGGER.warn("{}.always lists '{}', which no price rule covers or which does not exist",
                        name, text);
                continue;
            }
            if (taken.add(id)) {
                out.add(SafariShopConfig.Entry.rotatingItem(id.toString(), rule.count, rule.price, goods.tab));
            }
        }

        // Sorted by id (TreeMap): the pool is in a stable order before
        // shuffling, which the draw needs to be reproducible.
        List<ResourceLocation> pool = new ArrayList<>();
        for (ResourceLocation id : priced.keySet()) {
            if (!taken.contains(id)) {
                pool.add(id);
            }
        }
        Collections.shuffle(pool, new Random(seed));
        for (ResourceLocation id : pool.subList(0, Math.min(goods.count, pool.size()))) {
            SafariShopConfig.PriceRule rule = priced.get(id);
            out.add(SafariShopConfig.Entry.rotatingItem(id.toString(), rule.count, rule.price, goods.tab));
        }
        return out;
    }

    /**
     * Every item the rules cover, with the first rule matching it (rules are
     * in priority order: specific items before the tags that hold them).
     */
    private static Map<ResourceLocation, SafariShopConfig.PriceRule> pricedItems(List<SafariShopConfig.PriceRule> rules) {
        Map<ResourceLocation, SafariShopConfig.PriceRule> priced = new TreeMap<>();
        for (SafariShopConfig.PriceRule rule : rules) {
            for (ResourceLocation id : items(rule.item)) {
                priced.putIfAbsent(id, rule);
            }
        }
        return priced;
    }

    /** The items a list of ids and {@code #tags} names; unknown ones are skipped. */
    private static Set<ResourceLocation> resolve(List<String> texts) {
        Set<ResourceLocation> out = new HashSet<>();
        for (String text : texts) {
            out.addAll(items(text));
        }
        return out;
    }

    /**
     * The registered items an id or a {@code #tag} names, in a stable order;
     * empty when it names nothing. Tags are read from the loaded data, so a
     * datapack adding items to a Cobblemon tag adds them to the shop.
     */
    private static List<ResourceLocation> items(String text) {
        if (text == null) {
            return List.of();
        }
        boolean tag = text.startsWith(SafariShopConfig.Cost.TAG_PREFIX);
        ResourceLocation id = ResourceLocation.tryParse(tag ? text.substring(SafariShopConfig.Cost.TAG_PREFIX.length()) : text);
        if (id == null) {
            return List.of();
        }
        if (!tag) {
            return BuiltInRegistries.ITEM.containsKey(id) ? List.of(id) : List.of();
        }
        Map<ResourceLocation, Boolean> ordered = new LinkedHashMap<>();
        BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, id)).ifPresent(set -> set.forEach(holder -> {
            Item item = holder.value();
            ordered.put(BuiltInRegistries.ITEM.getKey(item), Boolean.TRUE);
        }));
        return new ArrayList<>(ordered.keySet());
    }

    /** Items the fixed entries already sell, so the draw does not list them twice. */
    private static Set<ResourceLocation> fixedItems(SafariShopConfig config) {
        Set<ResourceLocation> out = new HashSet<>();
        for (SafariShopConfig.Entry entry : config.entries) {
            if (!entry.isPokemon() && entry.item != null) {
                ResourceLocation id = ResourceLocation.tryParse(entry.item);
                if (id != null) {
                    out.add(id);
                }
            }
        }
        return out;
    }
}
