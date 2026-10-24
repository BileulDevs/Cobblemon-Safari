package dev.darcosse.common.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-to-server purchase request from the shop screen.
 *
 * <p>Carries the price the player saw: if an operator changed the shop in the
 * meantime, the server refuses rather than charging a price the player never
 * agreed to. Also which kind of item the player chose to give for each cost
 * that accepts several (any evolution stone...). Everything else is
 * re-checked on the server.
 *
 * <p>History: 1 had index, expectedPrice, rotation. 2 added preferred.
 *
 * @param index         position of the entry in the shop list
 * @param expectedPrice price shown on the screen
 * @param rotation      rotation the screen's random Pokémon belong to (from {@link SafariShopPayload#rotation})
 * @param preferred     for each cost of the entry, in order, the item id the player chose to give
 *                      first among the kinds a tag accepts, or "" to leave it to the server's order
 */
public record SafariShopBuyPayload(int index, int expectedPrice, long rotation, List<String> preferred)
        implements CustomPacketPayload {

    /**
     * Wire format version, checked before anything else is read. The format
     * is positional: adding, removing or reordering a field (or changing what
     * one means) without raising this makes a peer of another build read
     * garbage without an error. Raise it on every change to write/read.
     */
    public static final int WIRE_VERSION = 2;

    /**
     * Most choices read, and longest item id accepted. Far above any real
     * entry (a cost has one or two items, an id a few dozen characters);
     * the bounds keep a forged packet from making the server allocate much.
     */
    private static final int MAX_PREFERRED = 32;
    private static final int MAX_ID_LENGTH = 256;

    public static final CustomPacketPayload.Type<SafariShopBuyPayload> TYPE =
            new CustomPacketPayload.Type<>(CobblemonSafari.id("shop_buy"));

    public static final StreamCodec<FriendlyByteBuf, SafariShopBuyPayload> CODEC =
            StreamCodec.of((FriendlyByteBuf buf, SafariShopBuyPayload payload) -> payload.write(buf),
                    SafariShopBuyPayload::read);

    /** MUST list the same fields in the same order as {@link #read}. */
    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(WIRE_VERSION);
        buf.writeVarInt(index);
        buf.writeVarInt(expectedPrice);
        buf.writeVarLong(rotation);
        buf.writeVarInt(preferred.size());
        for (String id : preferred) {
            buf.writeUtf(id, MAX_ID_LENGTH);
        }
    }

    /** MUST list the same fields in the same order as {@link #write}. */
    private static SafariShopBuyPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != WIRE_VERSION) {
            throw new IllegalStateException("Cobblemon Safari: shop purchase wire version mismatch (got " + version
                    + ", expected " + WIRE_VERSION + ").");
        }
        int index = buf.readVarInt();
        int expectedPrice = buf.readVarInt();
        long rotation = buf.readVarLong();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_PREFERRED) {
            throw new IllegalStateException("Cobblemon Safari: shop purchase lists " + count + " choices");
        }
        List<String> preferred = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            preferred.add(buf.readUtf(MAX_ID_LENGTH));
        }
        return new SafariShopBuyPayload(index, expectedPrice, rotation, List.copyOf(preferred));
    }

    /** Type under which each loader registers this payload. */
    @Override
    public CustomPacketPayload.Type<SafariShopBuyPayload> type() {
        return TYPE;
    }
}
