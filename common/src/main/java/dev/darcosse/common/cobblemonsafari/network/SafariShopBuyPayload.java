package dev.darcosse.common.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client-to-server purchase request from the shop screen.
 *
 * <p>Carries the price the player saw: if an operator changed the shop in the
 * meantime, the server refuses rather than charging a price the player never
 * agreed to. Everything else is re-checked on the server.
 *
 * @param index         position of the entry in the shop list
 * @param expectedPrice price shown on the screen
 */
public record SafariShopBuyPayload(int index, int expectedPrice) implements CustomPacketPayload {

    /** Wire format version. Bump on EVERY change to write/read. v1: first version. */
    public static final int WIRE_VERSION = 1;

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
    }

    /** MUST list the same fields in the same order as {@link #write}. */
    private static SafariShopBuyPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != WIRE_VERSION) {
            throw new IllegalStateException("Cobblemon Safari: shop purchase wire version mismatch (got " + version
                    + ", expected " + WIRE_VERSION + ").");
        }
        return new SafariShopBuyPayload(buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public CustomPacketPayload.Type<SafariShopBuyPayload> type() {
        return TYPE;
    }
}
