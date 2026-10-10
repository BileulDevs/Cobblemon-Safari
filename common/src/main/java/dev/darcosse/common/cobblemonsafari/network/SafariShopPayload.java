package dev.darcosse.common.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.network.RegistryFriendlyByteBuf;
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
 * @param points  the player's Safari points
 * @param entries what is for sale, in config order; the index is what a purchase refers to
 */
public record SafariShopPayload(boolean refresh, int points, List<Entry> entries) implements CustomPacketPayload {

    /** Wire format version. Bump on EVERY change to write/read. v1: first version. */
    public static final int WIRE_VERSION = 1;

    /** Entry kinds on the wire. Their numbers are the format: never renumber. */
    public static final int KIND_UNAVAILABLE = 0;
    public static final int KIND_ITEM = 1;
    public static final int KIND_POKEMON = 2;

    /** Guards against corrupted or hostile packets announcing huge lists. */
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_ASPECTS = 64;

    public static final CustomPacketPayload.Type<SafariShopPayload> TYPE =
            new CustomPacketPayload.Type<>(CobblemonSafari.id("shop"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SafariShopPayload> CODEC =
            StreamCodec.of((RegistryFriendlyByteBuf buf, SafariShopPayload payload) -> payload.write(buf),
                    SafariShopPayload::read);

    /**
     * One thing for sale.
     *
     * @param kind    {@link #KIND_ITEM}, {@link #KIND_POKEMON} or {@link #KIND_UNAVAILABLE}
     * @param price   cost in Safari points
     * @param item    the stack given, for items; empty otherwise
     * @param species species id, for Pokémon; null otherwise
     * @param aspects Cobblemon aspects (forms, shiny...), for Pokémon
     * @param level   level, for Pokémon
     * @param shiny   whether the Pokémon is shiny
     */
    public record Entry(int kind, int price, ItemStack item, ResourceLocation species, List<String> aspects,
                       int level, boolean shiny) {
    }

    /** MUST list the same fields in the same order as {@link #read}. */
    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(WIRE_VERSION);
        buf.writeBoolean(refresh);
        buf.writeVarInt(points);
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeVarInt(entry.kind());
            buf.writeVarInt(entry.price());
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
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalStateException("Cobblemon Safari: invalid shop entry count " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int kind = buf.readVarInt();
            int price = buf.readVarInt();
            if (kind == KIND_ITEM) {
                entries.add(new Entry(kind, price, ItemStack.STREAM_CODEC.decode(buf), null, List.of(), 0, false));
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
                entries.add(new Entry(kind, price, ItemStack.EMPTY, species, List.copyOf(aspects), level, shiny));
            } else {
                entries.add(new Entry(KIND_UNAVAILABLE, price, ItemStack.EMPTY, null, List.of(), 0, false));
            }
        }
        return new SafariShopPayload(refresh, points, List.copyOf(entries));
    }

    @Override
    public CustomPacketPayload.Type<SafariShopPayload> type() {
        return TYPE;
    }
}
