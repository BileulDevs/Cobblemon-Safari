package dev.darcosse.common.cobblemonsafari.shop;

import dev.darcosse.common.cobblemonsafari.points.SafariPointsIcon;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.config.SafariShopConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariShopConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.gate.SafariGate;
import dev.darcosse.common.cobblemonsafari.item.SafariItems;
import dev.darcosse.common.cobblemonsafari.loan.EscapeRopes;
import dev.darcosse.common.cobblemonsafari.network.SafariNetwork;
import dev.darcosse.common.cobblemonsafari.network.SafariShopBuyPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariShopPayload;
import dev.darcosse.common.cobblemonsafari.points.SafariPoints;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Safari Warden's shop: Pokémon and items bought with Safari points,
 * listed in {@code config/cobblemon_safari/shop.json}, plus random Pokémon
 * that change with each rotation ({@link RotatingShop}). Opened from the
 * NPCs' dialogues ({@code q.player.safari_shop()}), or by operators with
 * {@code /safari shop}; the screen itself is client-side
 * ({@code SafariShopScreen}) and only sends purchase requests.
 * Server side.
 */
public final class SafariShop {

    /** Vanilla "gamemaster" level, as in SafariCommands: operators may shop anywhere. */
    private static final int OP_LEVEL = 2;

    /** Shop entries already reported as broken, to log each once. */
    private static final Set<String> REPORTED = new HashSet<>();

    /** Milliseconds in an hour, for cooldownHours. */
    private static final long MILLIS_PER_HOUR = 3_600_000L;

    private SafariShop() {
    }

    /**
     * Opens the shop screen on the player's client.
     *
     * @return false (with a message) when the shop is switched off
     */
    public static boolean open(ServerPlayer player) {
        if (!SafariShopConfigManager.get().enabled) {
            player.displayClientMessage(Component.translatable(key("message.shop_closed")), true);
            return false;
        }
        SafariNetwork.sendShop(player, snapshot(player, false));
        return true;
    }

    /**
     * A purchase request from the shop screen. Every condition is checked
     * again here: the screen may be stale, and a modified client could send
     * anything. The screen is refreshed whatever the outcome, so it never
     * keeps showing a wrong balance or a vanished entry.
     */
    public static void handleBuy(ServerPlayer player, SafariShopBuyPayload request) {
        List<SafariShopConfig.Entry> entries = catalogue(player);
        int fixed = SafariShopConfigManager.get().entries.size();
        if (!SafariShopConfigManager.get().enabled) {
            player.displayClientMessage(Component.translatable(key("message.shop_closed")), true);
        } else if (!canShopHere(player)) {
            player.displayClientMessage(Component.translatable(key("command.shop.too_far")), true);
        } else if (request.index() < 0 || request.index() >= entries.size()
                || entries.get(request.index()).price != request.expectedPrice()
                || (request.index() >= fixed && request.rotation() != RotatingShop.period())) {
            // The list or a price changed since the screen opened (/safari reload),
            // or the random Pokémon were redrawn: the same index and price may
            // now be another species, which the buyer never saw.
            player.displayClientMessage(Component.translatable(key("message.shop_changed")), true);
        } else {
            buy(player, entries.get(request.index()), RotatingShop.period());
        }
        SafariNetwork.sendShop(player, snapshot(player, true));
    }

    /**
     * What the screen shows: every entry with what the client needs to draw
     * it, and the player's points. Builds one preview Pokémon per Pokémon
     * entry; only when the shop is opened or refreshed.
     */
    private static SafariShopPayload snapshot(ServerPlayer player, boolean refresh) {
        List<SafariShopPayload.Tab> tabs = new ArrayList<>();
        for (SafariShopConfig.Tab tab : SafariShopConfigManager.get().tabs) {
            tabs.add(new SafariShopPayload.Tab(tab.id, tab.name, icon(tab)));
        }
        List<SafariShopPayload.Entry> out = new ArrayList<>();
        // Period read before the list: if a rotation starts in between, the
        // screen holds an older period and a purchase is refused, never wrong.
        long rotation = RotatingShop.period();
        for (SafariShopConfig.Entry entry : catalogue(player)) {
            out.add(describe(entry, costs(entry), remaining(player, entry, rotation), waitSeconds(player, entry)));
        }
        return new SafariShopPayload(refresh, SafariPoints.balance(player), rotation, tabs, out);
    }

    /**
     * Everything for sale, in the order the screen and purchases index it:
     * the fixed entries of the shop file, then the random Pokémon, items and
     * balls of the rotation. Prices are resolved here, the one place every
     * reader of a price (screen, purchase check, payment, limits) goes through, so
     * {@code "auto"} never reaches them as a number.
     */
    private static List<SafariShopConfig.Entry> catalogue(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        List<SafariShopConfig.Entry> fixed = SafariShopConfigManager.get().entries;
        List<SafariShopConfig.Entry> rotating = RotatingShop.entries(server);
        List<SafariShopConfig.Entry> all = new ArrayList<>(fixed.size() + rotating.size());
        for (SafariShopConfig.Entry entry : fixed) {
            all.add(entry.hasAutoPrice() ? entry.withPrice(autoPrice(server, entry)) : entry);
        }
        all.addAll(rotating);
        all.addAll(RotatingItems.entries(server));
        // Last, so the indices of everything else do not depend on where the shop is.
        SafariShopConfig.Entry rope = escapeRopeEntry(player);
        if (rope != null) {
            all.add(rope);
        }
        return all;
    }

    /**
     * A free Escape Rope, for a player who lost or dropped the one lent on
     * entry. Only in the Safari's shops (the rest houses): ropes are taken
     * back outside the Safari every second, so one taken at a gate would
     * vanish at once. Built in memory, never written to the shop file;
     * refused at purchase to a player who already carries one.
     */
    @Nullable
    private static SafariShopConfig.Entry escapeRopeEntry(ServerPlayer player) {
        if (!SafariConfigManager.get().escapeRope.enabled || !SafariDimensions.isSafari(player.level())) {
            return null;
        }
        return SafariShopConfig.Entry.rotatingItem(BuiltInRegistries.ITEM.getKey(SafariItems.escapeRope()).toString(),
                1, 0, SafariShopConfig.Tab.ITEMS);
    }

    /** Whether an entry gives the Escape Rope. */
    private static boolean isEscapeRope(SafariShopConfig.Entry entry) {
        return !entry.isPokemon()
                && BuiltInRegistries.ITEM.getKey(SafariItems.escapeRope()).toString().equals(entry.item);
    }

    /**
     * The computed price of a Pokémon entry priced {@code "auto"}. Builds the
     * Pokémon once to learn its species (the properties may name it by any
     * alias Cobblemon accepts); when that fails the entry is listed as
     * unavailable anyway, so its price does not matter and is 0.
     */
    private static int autoPrice(MinecraftServer server, SafariShopConfig.Entry entry) {
        Pokemon pokemon = preview(entry);
        return pokemon == null ? 0 : RotatingShop.autoPrice(server, pokemon.getSpecies());
    }

    /**
     * Whether a player may use the shop where they stand: operators anywhere,
     * others at a Safari Gate (the warden) or in a rest house of the Safari
     * (the clerk). Checked when the shop opens and again at each purchase.
     */
    public static boolean canShopHere(ServerPlayer player) {
        if (player.hasPermissions(OP_LEVEL) || SafariGate.isNearGate(player)) {
            return true;
        }
        // Margin 0: the house and its plaza, i.e. within reach of the clerk's counter.
        return SafariDimensions.isSafari(player.level())
                && RestHouses.isNear(player.getBlockX(), player.getBlockZ(), 0);
    }

    /** The item drawn on a tab; empty when the config names none or an unknown item. */
    private static ItemStack icon(SafariShopConfig.Tab tab) {
        ResourceLocation id = ResourceLocation.tryParse(tab.icon);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(id));
    }

    /**
     * One entry as the client sees it; unavailable when the item or Pokémon
     * cannot be built, or when one of its cost items does not exist.
     *
     * @param costs     its cost items, null when one of them does not exist
     * @param remaining purchases left to the player this rotation, or UNLIMITED
     * @param wait      seconds before the player may buy it again, 0 when they may now
     */
    private static SafariShopPayload.Entry describe(SafariShopConfig.Entry entry,
                                                    @Nullable List<SafariShopPayload.Cost> costs, int remaining,
                                                    int wait) {
        int tab = SafariShopConfigManager.tabIndex(entry.tab);
        List<SafariShopPayload.Cost> shownCosts = costs == null ? List.of() : costs;
        if (costs != null && entry.isPokemon()) {
            Pokemon pokemon = preview(entry);
            if (pokemon != null) {
                return new SafariShopPayload.Entry(tab, SafariShopPayload.KIND_POKEMON, entry.price, shownCosts,
                        remaining, wait, ItemStack.EMPTY, pokemon.getSpecies().getResourceIdentifier(),
                        List.copyOf(pokemon.getAspects()), pokemon.getLevel(), pokemon.getShiny());
            }
        } else if (costs != null && SafariShopConfigManager.itemExists(entry)) {
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(entry.item)), entry.count);
            return new SafariShopPayload.Entry(tab, SafariShopPayload.KIND_ITEM, entry.price, shownCosts, remaining,
                    wait, stack, null, List.of(), 0, false);
        }
        return new SafariShopPayload.Entry(tab, SafariShopPayload.KIND_UNAVAILABLE, entry.price, shownCosts, remaining,
                wait, ItemStack.EMPTY, null, List.of(), 0, false);
    }

    /**
     * The items an entry takes, resolved; an empty list for a plain purchase,
     * null when a cost names an item or tag that does not exist (or an empty
     * tag): such an entry cannot be bought.
     */
    @Nullable
    private static List<SafariShopPayload.Cost> costs(SafariShopConfig.Entry entry) {
        if (!entry.hasCost()) {
            return List.of();
        }
        List<SafariShopPayload.Cost> out = new ArrayList<>(entry.cost.size());
        for (SafariShopConfig.Cost cost : entry.cost) {
            List<ItemStack> items = accepted(cost);
            if (items.isEmpty()) {
                report(cost.item, null);
                return null;
            }
            Component name = cost.isTag() ? tagName(cost.id()) : items.get(0).getHoverName();
            out.add(new SafariShopPayload.Cost(items, cost.count, name));
        }
        return out;
    }

    /** The items a cost accepts, as stacks of one: the item, or every item of the tag. Empty when unknown. */
    private static List<ItemStack> accepted(SafariShopConfig.Cost cost) {
        ResourceLocation id = cost.id();
        if (id == null) {
            return List.of();
        }
        if (cost.isTag()) {
            List<ItemStack> items = new ArrayList<>();
            BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, id))
                    .ifPresent(tag -> tag.forEach(holder -> items.add(new ItemStack(holder.value()))));
            return items;
        }
        return BuiltInRegistries.ITEM.containsKey(id) ? List.of(new ItemStack(BuiltInRegistries.ITEM.get(id))) : List.of();
    }

    /**
     * A tag's player-facing name: the mod's translation
     * {@code cobblemon_safari.tag.<namespace>.<path>} when there is one,
     * else the tag id, which is at least exact.
     */
    private static Component tagName(ResourceLocation tag) {
        return Component.translatableWithFallback(
                CobblemonSafari.MOD_ID + ".tag." + tag.getNamespace() + "." + tag.getPath().replace('/', '.'),
                "#" + tag);
    }

    /** Purchases of an entry the player has left this rotation, or UNLIMITED; 0 while its wait runs. */
    private static int remaining(ServerPlayer player, SafariShopConfig.Entry entry, long rotation) {
        if (waitSeconds(player, entry) > 0) {
            return 0;
        }
        if (entry.limitPerRotation == null) {
            return SafariShopPayload.UNLIMITED;
        }
        int bought = ShopLimitStore.get(player.getServer()).bought(player.getUUID(), rotation, limitKey(entry));
        return Math.max(0, entry.limitPerRotation - bought);
    }

    /**
     * Seconds before the player may buy an entry with a wait again, rounded
     * up; 0 when it has no wait or the wait is over. Real time, so the wait
     * runs while the player or the server is offline.
     */
    private static int waitSeconds(ServerPlayer player, SafariShopConfig.Entry entry) {
        if (entry.cooldownHours == null) {
            return 0;
        }
        long last = ShopLimitStore.get(player.getServer()).lastPurchase(player.getUUID(), limitKey(entry));
        long left = last + entry.cooldownHours * MILLIS_PER_HOUR - System.currentTimeMillis();
        // A clock set back after a purchase could make the wait longer than
        // the entry's own: never more than that.
        left = Math.min(left, entry.cooldownHours * MILLIS_PER_HOUR);
        return left <= 0 ? 0 : (int) ((left + 999L) / 1000L);
    }

    /**
     * "5 h 12" or "38 min": a wait as the player reads it, rounded up to the
     * minute. The shop screen formats the same keys the same way.
     */
    private static Component waitText(int seconds) {
        int minutes = Math.max(1, (seconds + 59) / 60);
        if (minutes < 60) {
            return Component.translatable(key("shop.wait_minutes"), minutes);
        }
        return Component.translatable(key("shop.wait_hours"), minutes / 60, String.format("%02d", minutes % 60));
    }

    /**
     * What identifies an entry for its purchase limit: its content, not its
     * position, so reordering the shop file keeps the counts, while changing
     * what it sells or asks for starts them afresh.
     */
    static String limitKey(SafariShopConfig.Entry entry) {
        StringBuilder key = new StringBuilder(entry.isPokemon() ? "pokemon:" + entry.pokemon
                : "item:" + entry.item + "*" + entry.count);
        key.append('|').append(entry.price);
        if (entry.hasCost()) {
            for (SafariShopConfig.Cost cost : entry.cost) {
                key.append('|').append(cost.item).append('*').append(cost.count);
            }
        }
        return key.toString();
    }

    /** Whether a stack is one of the items a cost accepts. */
    private static boolean matches(SafariShopPayload.Cost cost, ItemStack stack) {
        for (ItemStack item : cost.items()) {
            if (stack.is(item.getItem())) {
                return true;
            }
        }
        return false;
    }

    /** How many of a cost's items the player carries, anywhere in the inventory (counted, nothing removed). */
    private static int carried(ServerPlayer player, SafariShopPayload.Cost cost) {
        // Max count 0: count only. The crafting grid is included, as in /clear.
        return player.getInventory().clearOrCountMatchingItems(stack -> matches(cost, stack), 0,
                player.inventoryMenu.getCraftSlots());
    }

    /**
     * Removes a cost's items from the player's inventory, whichever of the
     * accepted items come first (a tag cost takes a mix). Only call after
     * {@link #carried} checked them.
     *
     * @return the stacks taken, to give back if the purchase fails
     */
    private static List<ItemStack> take(ServerPlayer player, SafariShopPayload.Cost cost) {
        List<ItemStack> taken = new ArrayList<>();
        int left = cost.count();
        for (ItemStack item : cost.items()) {
            if (left == 0) {
                break;
            }
            int removed = player.getInventory().clearOrCountMatchingItems(stack -> stack.is(item.getItem()), left,
                    player.inventoryMenu.getCraftSlots());
            if (removed > 0) {
                taken.add(item.copyWithCount(removed));
                left -= removed;
            }
        }
        player.containerMenu.broadcastChanges();
        return taken;
    }

    /** Gives back items taken for a purchase that could not be delivered. */
    private static void giveBack(ServerPlayer player, List<ItemStack> taken) {
        for (ItemStack stack : taken) {
            int left = stack.getCount();
            int stackSize = stack.getMaxStackSize();
            while (left > 0) {
                int count = Math.min(left, stackSize);
                PlayerExtensionsKt.giveOrDropItemStack(player, stack.copyWithCount(count), true);
                left -= count;
            }
        }
    }

    /**
     * Buys one entry: checks the purchase limit and the items asked, takes
     * the points and the items, then hands over the goods. Points and items
     * are given back if the goods cannot be delivered (broken entry, party
     * and PC both full).
     */
    private static void buy(ServerPlayer player, SafariShopConfig.Entry entry, long rotation) {
        List<SafariShopPayload.Cost> costs = costs(entry);
        if (costs == null) {
            player.displayClientMessage(Component.translatable(key("message.shop_failed")), true);
            return;
        }
        if (isEscapeRope(entry) && EscapeRopes.count(player) > 0) {
            // One rope is all a hunter needs; more would only clutter the inventory.
            player.displayClientMessage(Component.translatable(key("shop.rope_already")), true);
            return;
        }
        int wait = waitSeconds(player, entry);
        if (wait > 0) {
            player.displayClientMessage(Component.translatable(key("shop.wait_needed"), waitText(wait)), true);
            return;
        }
        if (remaining(player, entry, rotation) == 0) {
            player.displayClientMessage(Component.translatable(key("shop.limit_reached")), true);
            return;
        }
        for (SafariShopPayload.Cost cost : costs) {
            if (carried(player, cost) < cost.count()) {
                player.displayClientMessage(Component.translatable(key("shop.missing_items"),
                        cost.count(), cost.name()), true);
                return;
            }
        }
        if (!SafariPoints.spend(player, entry.price)) {
            player.displayClientMessage(Component.translatable(key("shop.not_enough")), true);
            return;
        }
        List<ItemStack> taken = new ArrayList<>();
        for (SafariShopPayload.Cost cost : costs) {
            taken.addAll(take(player, cost));
        }
        Component bought = entry.isPokemon() ? givePokemon(player, entry) : giveItem(player, entry);
        if (bought == null) {
            SafariPoints.add(player, entry.price);
            giveBack(player, taken);
            player.displayClientMessage(Component.translatable(key("message.shop_failed")), true);
            return;
        }
        if (entry.limitPerRotation != null) {
            ShopLimitStore.get(player.getServer()).record(player.getUUID(), rotation, limitKey(entry));
        }
        if (entry.cooldownHours != null) {
            ShopLimitStore.get(player.getServer()).recordPurchaseTime(player.getUUID(), limitKey(entry),
                    System.currentTimeMillis());
        }
        if (costs.isEmpty() && entry.price == 0) {
            player.sendSystemMessage(Component.translatable(key("message.shop_claimed"), bought));
        } else if (costs.isEmpty()) {
            player.sendSystemMessage(Component.translatable(key("message.shop_bought"), bought,
                    SafariPointsIcon.amount(entry.price), SafariPointsIcon.amount(SafariPoints.balance(player))));
        } else {
            player.sendSystemMessage(Component.translatable(key("message.shop_traded"), describeCosts(costs), bought));
        }
    }

    /** "16 × Red Apricorn, 1 × Fire Stone", for the trade message. */
    private static Component describeCosts(List<SafariShopPayload.Cost> costs) {
        MutableComponent text = Component.empty();
        for (int i = 0; i < costs.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(Component.literal(costs.get(i).count() + " × ").append(costs.get(i).name()));
        }
        return text;
    }

    /**
     * A Pokémon built from an entry, for display only, or null if the
     * properties cannot make one. Built anew each time so the shop shows the
     * same thing the buyer gets (random parts aside).
     */
    @Nullable
    private static Pokemon preview(SafariShopConfig.Entry entry) {
        try {
            return PokemonProperties.Companion.parse(entry.pokemon).create();
        } catch (RuntimeException e) {
            report(entry.pokemon, e);
            return null;
        }
    }

    /** Creates the Pokémon for the buyer and adds it to their party (or PC). Null on failure. */
    @Nullable
    private static Component givePokemon(ServerPlayer player, SafariShopConfig.Entry entry) {
        Pokemon pokemon;
        try {
            // Passing the player lets Cobblemon apply shiny-chance modifiers and set the trainer.
            pokemon = PokemonProperties.Companion.parse(entry.pokemon).create(player);
        } catch (RuntimeException e) {
            report(entry.pokemon, e);
            return null;
        }
        // Cobblemon's party store sends the Pokémon to the PC when the party is full.
        if (!Cobblemon.INSTANCE.getStorage().getParty(player).add(pokemon)) {
            return null;
        }
        return pokemon.getSpecies().getTranslatedName();
    }

    /** Gives the item stack, dropping what does not fit. Null when the item does not exist. */
    @Nullable
    private static Component giveItem(ServerPlayer player, SafariShopConfig.Entry entry) {
        if (!SafariShopConfigManager.itemExists(entry)) {
            report(entry.item, null);
            return null;
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(entry.item)), entry.count);
        Component name = Component.literal(entry.count + " × ").append(stack.getHoverName());
        PlayerExtensionsKt.giveOrDropItemStack(player, stack, true);
        return name;
    }

    /** Logs a broken entry once. */
    private static void report(String value, @Nullable Exception cause) {
        if (REPORTED.add(value)) {
            CobblemonSafari.LOGGER.warn("Shop entry '{}' cannot be sold, check config/cobblemon_safari/shop.json{}",
                    value, cause == null ? "" : ": " + cause.getMessage());
        }
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
