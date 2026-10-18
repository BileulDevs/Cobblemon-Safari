package dev.darcosse.common.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client contents of the warden's shop: what is for sale and the
 * player's points. Opens the shop screen, or refreshes it after a purchase.
 *
 * <p>Pokémon are sent as species, aspects, level and shininess, which is all
 * the client needs to draw the model and name it; the full properties stay on
 * the server, which builds the real Pokémon at purchase.
 *
 * @param refresh true to update an open shop screen only (after a purchase), false to open it
 * @param points   the player's Safari points
 * @param rotation rotation the random Pokémon belong to; sent back with a purchase
 * @param tabs    tabs of the screen, in display order
 * @param entries what is for sale, in config order; the index is what a purchase refers to
 */
public record SafariShopPayload(boolean refresh, int points, long rotation, List<Tab> tabs, List<Entry> entries)
        implements CustomPacketPayload {

    /**
     * Wire format version. Bump on EVERY change to write/read.
     * v1: first version. v2: tabs, and the tab index of each entry.
     * v3: rotation, after points.
     * v4: each entry's cost (items taken in exchange) and remaining purchases, after the price.
     * v5: a cost carries every item it accepts (an item tag) and its name.
     * v6: each entry's wait in seconds (cooldownHours), after the remaining purchases.
     */
    public static final int WIRE_VERSION = 6;

    /** Value of {@link Entry#remaining} for an entry without a purchase limit. */
    public static final int UNLIMITED = -1;

    /** Entry kinds on the wire. Their numbers are the format: never renumber. */
    public static final int KIND_UNAVAILABLE = 0;
    public static final int KIND_ITEM = 1;
    public static final int KIND_POKEMON = 2;

    /** Guards against corrupted or hostile packets announcing huge lists. */
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_ASPECTS = 64;
    private static final int MAX_TABS = 64;
    private static final int MAX_COSTS = 16;
    /** Items one cost may accept: a large tag (every berry is about 70). */
    private static final int MAX_COST_ITEMS = 512;

    public static final CustomPacketPayload.Type<SafariShopPayload> TYPE =
            new CustomPacketPayload.Type<>(CobblemonSafari.id("shop"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SafariShopPayload> CODEC =
            StreamCodec.of((RegistryFriendlyByteBuf buf, SafariShopPayload payload) -> payload.write(buf),
                    SafariShopPayload::read);

    /**
     * One tab of the screen.
     *
     * @param id   config id; built-in ids have a translated name
     * @param name name set in the config, shown as is; empty to use the translation
     * @param icon item drawn on the tab; may be empty
     */
    public record Tab(String id, String name, ItemStack icon) {
    }

    /**
     * One thing for sale.
     *
     * @param tab     index in {@link #tabs} of the tab showing it
     * @param kind    {@link #KIND_ITEM}, {@link #KIND_POKEMON} or {@link #KIND_UNAVAILABLE}
     * @param price   cost in Safari points
     * @param item    the stack given, for items; empty otherwise
     * @param species species id, for Pokémon; null otherwise
     * @param aspects Cobblemon aspects (forms, shiny...), for Pokémon
     * @param level     level, for Pokémon
     * @param shiny     whether the Pokémon is shiny
     * @param cost      items taken in exchange, besides the price; empty for a plain purchase
     * @param remaining purchases the player has left this rotation, or {@link #UNLIMITED}
     * @param waitSeconds seconds before the player may buy it again (an entry with a wait), 0 when they may now
     */
    public record Entry(int tab, int kind, int price, List<Cost> cost, int remaining, int waitSeconds, ItemStack item,
                        ResourceLocation species, List<String> aspects, int level, boolean shiny) {
    }

    /**
     * Items an entry takes in exchange: one item, or any mix of an item tag's.
     *
     * @param items the items accepted, as stacks of one (icons, and what the client counts)
     * @param count how many in all; separate from the stacks, which cap at the item's stack size
     * @param name  what to call them ("Apricorns" for a tag, the item's name otherwise)
     */
    public record Cost(List<ItemStack> items, int count, Component name) {
    }

    /** MUST list the same fields in the same order as {@link #read}. */
    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(WIRE_VERSION);
        buf.writeBoolean(refresh);
        buf.writeVarInt(points);
        buf.writeVarLong(rotation);
        buf.writeVarInt(tabs.size());
        for (Tab tab : tabs) {
            buf.writeUtf(tab.id());
            buf.writeUtf(tab.name());
            // OPTIONAL: the plain codec refuses empty stacks, and a tab may have no icon.
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, tab.icon());
        }
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeVarInt(entry.tab());
            buf.writeVarInt(entry.kind());
            buf.writeVarInt(entry.price());
            buf.writeVarInt(entry.cost().size());
            for (Cost cost : entry.cost()) {
                buf.writeVarInt(cost.items().size());
                for (ItemStack item : cost.items()) {
                    ItemStack.STREAM_CODEC.encode(buf, item);
                }
                buf.writeVarInt(cost.count());
                ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, cost.name());
            }
            // +1: varints are cheapest for small non-negative numbers, and UNLIMITED is -1.
            buf.writeVarInt(entry.remaining() + 1);
            buf.writeVarInt(entry.waitSeconds());
            if (entry.kind() == KIND_ITEM) {
                ItemStack.STREAM_CODEC.encode(buf, entry.item());
            } else if (entry.kind() == KIND_POKEMON) {
                buf.writeResourceLocation(entry.species());
                buf.writeVarInt(entry.aspects().size());
                for (String aspect : entry.aspects()) {
                    buf.writeUtf(aspect);
                }
                buf.writeVarInt(entry.level());
                buf.writeBoolean(entry.shiny());
            }
        }
    }

    /** MUST list the same fields in the same order as {@link #write}. */
    private static SafariShopPayload read(RegistryFriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != WIRE_VERSION) {
            throw new IllegalStateException("Cobblemon Safari: shop wire version mismatch (got " + version
                    + ", expected " + WIRE_VERSION + "). Server and client run different versions of the mod.");
        }
        boolean refresh = buf.readBoolean();
        int points = buf.readVarInt();
        long rotation = buf.readVarLong();
        int tabCount = buf.readVarInt();
        if (tabCount < 0 || tabCount > MAX_TABS) {
            throw new IllegalStateException("Cobblemon Safari: invalid shop tab count " + tabCount);
        }
        List<Tab> tabs = new ArrayList<>(tabCount);
        for (int i = 0; i < tabCount; i++) {
            tabs.add(new Tab(buf.readUtf(), buf.readUtf(), ItemStack.OPTIONAL_STREAM_CODEC.decode(buf)));
        }
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalStateException("Cobblemon Safari: invalid shop entry count " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int tab = buf.readVarInt();
            int kind = buf.readVarInt();
            int price = buf.readVarInt();
            int costCount = buf.readVarInt();
            if (costCount < 0 || costCount > MAX_COSTS) {
                throw new IllegalStateException("Cobblemon Safari: invalid shop cost count " + costCount);
            }
            List<Cost> cost = new ArrayList<>(costCount);
            for (int c = 0; c < costCount; c++) {
                int itemCount = buf.readVarInt();
                if (itemCount < 1 || itemCount > MAX_COST_ITEMS) {
                    throw new IllegalStateException("Cobblemon Safari: invalid shop cost item count " + itemCount);
                }
                List<ItemStack> items = new ArrayList<>(itemCount);
                for (int k = 0; k < itemCount; k++) {
                    items.add(ItemStack.STREAM_CODEC.decode(buf));
                }
                int amount = buf.readVarInt();
                Component name = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf);
                cost.add(new Cost(List.copyOf(items), amount, name));
            }
            int remaining = buf.readVarInt() - 1;
            int wait = buf.readVarInt();
            if (kind == KIND_ITEM) {
                entries.add(new Entry(tab, kind, price, List.copyOf(cost), remaining, wait, ItemStack.STREAM_CODEC.decode(buf),
                        null, List.of(), 0, false));
            } else if (kind == KIND_POKEMON) {
                ResourceLocation species = buf.readResourceLocation();
                int aspectCount = buf.readVarInt();
                if (aspectCount < 0 || aspectCount > MAX_ASPECTS) {
                    throw new IllegalStateException("Cobblemon Safari: invalid aspect count " + aspectCount);
                }
                List<String> aspects = new ArrayList<>(aspectCount);
                for (int a = 0; a < aspectCount; a++) {
                    aspects.add(buf.readUtf());
                }
                int level = buf.readVarInt();
                boolean shiny = buf.readBoolean();
                entries.add(new Entry(tab, kind, price, List.copyOf(cost), remaining, wait, ItemStack.EMPTY, species,
                        List.copyOf(aspects), level, shiny));
            } else {
                entries.add(new Entry(tab, KIND_UNAVAILABLE, price, List.copyOf(cost), remaining, wait, ItemStack.EMPTY, null,
                        List.of(), 0, false));
            }
        }
        return new SafariShopPayload(refresh, points, rotation, List.copyOf(tabs), List.copyOf(entries));
    }

    @Override
    public CustomPacketPayload.Type<SafariShopPayload> type() {
        return TYPE;
    }
}
