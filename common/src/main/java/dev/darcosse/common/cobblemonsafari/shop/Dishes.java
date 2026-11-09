package dev.darcosse.common.cobblemonsafari.shop;

import com.cobblemon.mod.common.api.cooking.Seasonings;
import com.cobblemon.mod.common.item.crafting.SeasoningProcessor;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariShopConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ready-made dishes for the shop: a Poké Snack or Poké Cake stack carrying
 * what its seasonings give, as if it came out of the Poké Pot.
 *
 * <p>Built with Cobblemon's own seasoning processors, the ones its Poké Pot
 * recipes run on their result, so a bought snack baits exactly like a cooked
 * one. Nothing is consumed: the seasonings are only read. Built server
 * side; {@link #isDish} is also used by the client.
 */
public final class Dishes {

    /**
     * Processors of each dish when the offer names none: those of its Poké
     * Pot recipe in Cobblemon's data (poke_snack.json, poke_cake.json).
     * Without "spawn_bait" a snack would look seasoned but lure nothing
     * special; the cake's recipe has no bait processor of its own.
     */
    private static final Map<String, List<String>> RECIPE_PROCESSORS = Map.of(
            "cobblemon:poke_snack", List.of("food_colour", "ingredient", "spawn_bait"),
            "cobblemon:poke_cake", List.of("food_colour", "ingredient"));

    /** Processors of a dish missing from {@link #RECIPE_PROCESSORS}: the parts every dish recipe shares. */
    private static final List<String> DEFAULT_PROCESSORS = List.of("food_colour", "ingredient");

    /** Values already reported as broken, to log each once. */
    private static final Set<String> REPORTED = new HashSet<>();

    private Dishes() {
    }

    /**
     * Whether a stack is one of Cobblemon's Poké Pot dishes (Poké Snack,
     * Poké Cake). Judged by item id on purpose: the client only has the
     * stack, not the offer, and a dish with no seasoning is still a dish.
     * A config selling a dish on another base is not recognised.
     */
    public static boolean isDish(ItemStack stack) {
        return !stack.isEmpty()
                && RECIPE_PROCESSORS.containsKey(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    /**
     * The dish as a stack of {@code count}, or an empty stack when its base
     * or one of its seasonings is not an item right now (another mod
     * missing): the shop then lists the offer as unavailable.
     */
    public static ItemStack build(SafariShopConfig.Snack snack, int count) {
        ResourceLocation baseId = ResourceLocation.tryParse(snack.base);
        if (baseId == null || !BuiltInRegistries.ITEM.containsKey(baseId)) {
            report(snack.base, "is not an item");
            return ItemStack.EMPTY;
        }
        List<ItemStack> seasonings = new ArrayList<>(snack.seasonings.size());
        for (String id : snack.seasonings) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) {
                report(id, "is not an item");
                return ItemStack.EMPTY;
            }
            ItemStack seasoning = new ItemStack(BuiltInRegistries.ITEM.get(location));
            if (!isSeasoning(seasoning)) {
                // Still sold: the dish simply gets nothing from it, as in the Poké Pot.
                report(id, "is not a Cobblemon seasoning and adds nothing");
            }
            seasonings.add(seasoning);
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(baseId), count);
        if (seasonings.isEmpty()) {
            return stack;
        }
        List<String> processors = snack.processors != null ? snack.processors
                : RECIPE_PROCESSORS.getOrDefault(baseId.toString(), DEFAULT_PROCESSORS);
        Map<String, SeasoningProcessor> known = SeasoningProcessor.Companion.getProcessors();
        for (String type : processors) {
            SeasoningProcessor processor = known.get(type);
            if (processor == null) {
                report(type, "is not a Cobblemon seasoning processor");
                continue;
            }
            processor.apply(stack, seasonings);
        }
        return stack;
    }

    /** Whether Cobblemon knows the item as a seasoning (it has seasoning data). */
    public static boolean isSeasoning(ItemStack stack) {
        return Seasonings.INSTANCE.getFromItemStack(stack) != null;
    }

    private static void report(String value, String problem) {
        if (REPORTED.add(value + problem)) {
            CobblemonSafari.LOGGER.warn("Shop dish: '{}' {}, check config/cobblemon_safari/shop.json", value, problem);
        }
    }
}
