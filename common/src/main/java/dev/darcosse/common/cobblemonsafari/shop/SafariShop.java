package dev.darcosse.common.cobblemonsafari.shop;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariShopConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariShopConfigManager;
import dev.darcosse.common.cobblemonsafari.gate.SafariGate;
import dev.darcosse.common.cobblemonsafari.network.SafariNetwork;
import dev.darcosse.common.cobblemonsafari.network.SafariShopBuyPayload;
import dev.darcosse.common.cobblemonsafari.network.SafariShopPayload;
import dev.darcosse.common.cobblemonsafari.points.SafariPoints;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Safari Warden's shop: Pokémon and items bought with Safari points,
 * listed in {@code config/cobblemon_safari_shop.json}. Opened from the
 * warden's dialogue (through {@code /safari shop}); the screen itself is
 * client-side ({@code SafariShopScreen}) and only sends purchase requests.
 * Server side.
 */
public final class SafariShop {

    /** Vanilla "gamemaster" level, as in SafariCommands: operators may shop away from a gate. */
    private static final int OP_LEVEL = 2;

    /** Shop entries already reported as broken, to log each once. */
    private static final Set<String> REPORTED = new HashSet<>();

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
        List<SafariShopConfig.Entry> entries = SafariShopConfigManager.get().entries;
        if (!SafariShopConfigManager.get().enabled) {
            player.displayClientMessage(Component.translatable(key("message.shop_closed")), true);
        } else if (!player.hasPermissions(OP_LEVEL) && !SafariGate.isNearGate(player)) {
            player.displayClientMessage(Component.translatable(key("command.shop.too_far")), true);
        } else if (request.index() < 0 || request.index() >= entries.size()
                || entries.get(request.index()).price != request.expectedPrice()) {
            // The list or a price changed since the screen opened (/safari reload).
            player.displayClientMessage(Component.translatable(key("message.shop_changed")), true);
        } else {
            buy(player, entries.get(request.index()));
        }
        SafariNetwork.sendShop(player, snapshot(player, true));
    }

    /**
     * What the screen shows: every entry with what the client needs to draw
     * it, and the player's points. Builds one preview Pokémon per Pokémon
     * entry; only when the shop is opened or refreshed.
     */
    private static SafariShopPayload snapshot(ServerPlayer player, boolean refresh) {
        List<SafariShopPayload.Entry> out = new ArrayList<>();
        for (SafariShopConfig.Entry entry : SafariShopConfigManager.get().entries) {
            out.add(describe(entry));
        }
        return new SafariShopPayload(refresh, SafariPoints.balance(player), out);
    }

    /** One entry as the client sees it; unavailable when the item or Pokémon cannot be built. */
    private static SafariShopPayload.Entry describe(SafariShopConfig.Entry entry) {
        if (entry.isPokemon()) {
            Pokemon pokemon = preview(entry);
            if (pokemon != null) {
                return new SafariShopPayload.Entry(SafariShopPayload.KIND_POKEMON, entry.price, ItemStack.EMPTY,
                        pokemon.getSpecies().getResourceIdentifier(), List.copyOf(pokemon.getAspects()),
                        pokemon.getLevel(), pokemon.getShiny());
            }
        } else if (SafariShopConfigManager.itemExists(entry)) {
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(entry.item)), entry.count);
            return new SafariShopPayload.Entry(SafariShopPayload.KIND_ITEM, entry.price, stack, null, List.of(),
                    0, false);
        }
        return new SafariShopPayload.Entry(SafariShopPayload.KIND_UNAVAILABLE, entry.price, ItemStack.EMPTY, null,
                List.of(), 0, false);
    }

    /**
     * Buys one entry: takes the points, then hands over the goods. Points are
     * given back if the goods cannot be delivered (broken entry, party and PC
     * both full).
     */
    private static void buy(ServerPlayer player, SafariShopConfig.Entry entry) {
        if (!SafariPoints.spend(player, entry.price)) {
            player.displayClientMessage(Component.translatable(key("shop.not_enough")), true);
            return;
        }
        Component bought = entry.isPokemon() ? givePokemon(player, entry) : giveItem(player, entry);
        if (bought == null) {
            SafariPoints.add(player, entry.price);
            player.displayClientMessage(Component.translatable(key("message.shop_failed")), true);
            return;
        }
        player.sendSystemMessage(Component.translatable(key("message.shop_bought"), bought, entry.price,
                SafariPoints.balance(player)));
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
            CobblemonSafari.LOGGER.warn("Shop entry '{}' cannot be sold, check config/cobblemon_safari_shop.json{}",
                    value, cause == null ? "" : ": " + cause.getMessage());
        }
    }

    /** Builds a translation key in the mod namespace. */
    private static String key(String path) {
        return CobblemonSafari.MOD_ID + "." + path;
    }
}
