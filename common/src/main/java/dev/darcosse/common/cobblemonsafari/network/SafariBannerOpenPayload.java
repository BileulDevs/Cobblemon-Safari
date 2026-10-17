package dev.darcosse.common.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server-to-client order to open the design picker of a Safari Banner.
 *
 * <p>Sent by the server, like the wall screen settings, because only the
 * server knows whether this player may change this banner. The current
 * design travels with it so the picker opens on it.
 *
 * @param pos    the banner that was clicked
 * @param design its current design id, as text
 */
public record SafariBannerOpenPayload(BlockPos pos, String design) implements CustomPacketPayload {

    /** Wire format version. Bump on EVERY change to write/read. v1: first version. */
    public static final int WIRE_VERSION = 1;

    /** Longest design text read; matches the server's own bound (BannerDesigns.MAX_ID_LENGTH). */
    private static final int MAX_DESIGN_LENGTH = 128;

    public static final CustomPacketPayload.Type<SafariBannerOpenPayload> TYPE =
            new CustomPacketPayload.Type<>(CobblemonSafari.id("banner_open"));

    public static final StreamCodec<FriendlyByteBuf, SafariBannerOpenPayload> CODEC =
            StreamCodec.of((FriendlyByteBuf buf, SafariBannerOpenPayload payload) -> payload.write(buf),
                    SafariBannerOpenPayload::read);

    /** MUST list the same fields in the same order as {@link #read}. */
    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(WIRE_VERSION);
        buf.writeInt(pos.getX());
        buf.writeInt(pos.getY());
        buf.writeInt(pos.getZ());
        buf.writeUtf(design, MAX_DESIGN_LENGTH);
    }

    /** MUST list the same fields in the same order as {@link #write}. */
    private static SafariBannerOpenPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != WIRE_VERSION) {
            throw new IllegalStateException("Cobblemon Safari: banner open wire version mismatch (got "
                    + version + ", expected " + WIRE_VERSION + "). Server and client run different versions of the mod.");
        }
        BlockPos pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        String design = buf.readUtf(MAX_DESIGN_LENGTH);
        return new SafariBannerOpenPayload(pos, design);
    }

    @Override
    public CustomPacketPayload.Type<SafariBannerOpenPayload> type() {
        return TYPE;
    }
}
