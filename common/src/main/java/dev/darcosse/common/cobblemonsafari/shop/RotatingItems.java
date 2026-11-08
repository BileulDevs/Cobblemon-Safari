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
import net.minecraft.world.item.ItemStack;
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
import java.util.TreeSet;

/**
 * The shop's drawn goods: the {@code rotatingItems} and
 * {@code rotatingSnacks} of every tab and section of the shop file, drawn
 * anew with each rotation and sold after the fixed offers, each on the shelf
 * that asked for it.
 *
 * <p>Deterministic like the random Pokémon: same world, same rotation, same
 * data, same goods, for every player and across restarts. Cached until the
 * rotation, the data (a datapack reload moves the specials' revision) or the
 * shop file changes. Server side.
 */
public final class RotatingItems {

    /**
     * Mixed into the seeds so the goods, the Pokémon and the specials never
     * draw in step. Any distinct constants work; changing one changes that
     * draw for the current rotation.
     */
    private static final long ITEMS_SEED_MIX = 0x9E3779B97F4A7C15L;
    private static final long SNACKS_SEED_MIX = 0xBF58476D1CE4E5B9L;

    @Nullable
    private static List<SafariShopConfig.Entry> cached;
    private static long cachedPeriod = Long.MIN_VALUE;
    private static long cachedRevision = Long.MIN_VALUE;
    @Nullable
    private static SafariShopConfig cachedConfig;

    private RotatingItems() {
    }

    /** The drawn items then the drawn dishes of every shelf, in shelf order, as shop offers. */
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
        for (SafariShopConfigManager.ShelfRef ref : SafariShopConfigManager.shelves()) {
            // Each shelf its own seed: two shelves with the same rules still differ.
            long shelfMix = (ref.tab() + "/" + ref.section()).hashCode();
            if (ref.shelf().rotatingItems != null) {
                drawn.addAll(draw(ref, ref.shelf().rotatingItems, fixed,
                        salt ^ (period * ITEMS_SEED_MIX) ^ shelfMix));
            }
            if (ref.shelf().rotatingSnacks != null) {
                drawn.addAll(drawSnacks(ref, ref.shelf().rotatingSnacks,
                        new Random(salt ^ (period * SNACKS_SEED_MIX) ^ shelfMix)));
            }
        }
        cached = List.copyOf(drawn);
        cachedPeriod = period;
        cachedRevision = revision;
        cachedConfig = config;
        CobblemonSafari.LOGGER.info("Safari shop goods for rotation {}: {}", period,
                drawn.stream().map(entry -> entry.isSnack() ? entry.snack.base + entry.snack.seasonings : entry.item)
                        .toList());
        return cached;
    }

    /**
     * Draws one shelf's items: its {@link SafariShopConfig.RotatingGoods#always}
     * items, then {@code count} items at random among those its price rules
     * cover, leaving out excluded items and those the fixed offers sell.
     */
    private static List<SafariShopConfig.Entry> draw(SafariShopConfigManager.ShelfRef ref,
                                                     SafariShopConfig.RotatingGoods goods, Set<ResourceLocation> fixed,
                                                     long seed) {
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
                CobblemonSafari.LOGGER.warn("rotatingItems.always of tab '{}' lists '{}', which no price rule covers"
                        + " or which does not exist", ref.tab(), text);
                continue;
            }
            if (taken.add(id)) {
                out.add(SafariShopConfig.Entry.drawnItem(id.toString(), rule.count, rule.price, goods.limit,
                        ref.tab(), ref.section()));
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
            out.add(SafariShopConfig.Entry.drawnItem(id.toString(), rule.count, rule.price, goods.limit,
                    ref.tab(), ref.section()));
        }
        return out;
    }

    /**
     * Draws one shelf's dishes: {@code count} dishes of the shelf's base,
     * each with between {@code min} and {@code max} different seasonings from
     * the pool. Only pool items Cobblemon knows as seasonings are used, so a
     * tag holding other items does not make plain dishes priced as seasoned.
     */
    private static List<SafariShopConfig.Entry> drawSnacks(SafariShopConfigManager.ShelfRef ref,
                                                           SafariShopConfig.RotatingSnacks snacks, Random random) {
        if (!snacks.enabled || snacks.count == 0) {
            return List.of();
        }
        // TreeSet order: stable before shuffling, as for the items.
        Set<ResourceLocation> sorted = new TreeSet<>(resolve(snacks.seasonings.pool));
        List<ResourceLocation> pool = new ArrayList<>();
        for (ResourceLocation id : sorted) {
            if (Dishes.isSeasoning(new ItemStack(BuiltInRegistries.ITEM.get(id)))) {
                pool.add(id);
            }
        }
        List<SafariShopConfig.Entry> out = new ArrayList<>(snacks.count);
        for (int i = 0; i < snacks.count; i++) {
            int wanted = snacks.seasonings.min + random.nextInt(snacks.seasonings.max - snacks.seasonings.min + 1);
            List<ResourceLocation> shuffled = new ArrayList<>(pool);
            Collections.shuffle(shuffled, random);
            List<ResourceLocation> chosen = shuffled.subList(0, Math.min(wanted, shuffled.size()));
            SafariShopConfig.Snack snack = new SafariShopConfig.Snack();
            snack.base = snacks.base;
            snack.seasonings = new ArrayList<>(chosen.stream().map(ResourceLocation::toString).toList());
            snack.processors = snacks.processors;
            int price = snacks.price.base + snacks.price.perSeasoning * chosen.size();
            out.add(SafariShopConfig.Entry.drawnSnack(snack, price, snacks.limit, ref.tab(), ref.section()));
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
    static Set<ResourceLocation> resolve(List<String> texts) {
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

    /** Items the fixed offers already sell, so the draw does not list them twice. */
    private static Set<ResourceLocation> fixedItems(SafariShopConfig config) {
        Set<ResourceLocation> out = new HashSet<>();
        for (SafariShopConfig.Entry entry : config.entries) {
            if (entry.item != null) {
                ResourceLocation id = ResourceLocation.tryParse(entry.item);
                if (id != null) {
                    out.add(id);
                }
            }
        }
        return out;
    }
}
