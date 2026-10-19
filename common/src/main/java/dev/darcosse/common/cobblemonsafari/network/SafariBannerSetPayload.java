package dev.darcosse.common.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client-to-server request to show another picture on a Safari Banner, sent
 * by the design picker on every click so the banner changes live.
 *
 * <p>The design stays raw text here; the server validates it
 * ({@code BannerDesigns.parse}) and re-checks reach and rights, ignoring
 * anything no honest client sends.
 *
 * @param pos    the banner the picker was opened on
 * @param design the chosen design id, as text
 */
public record SafariBannerSetPayload(BlockPos pos, String design) implements CustomPacketPayload {

    /**
     * Wire format version, checked before anything else is read. The format
     * is positional: adding, removing or reordering a field (or changing what
     * one means) without raising this makes a peer of another build read
     * garbage without an error. Raise it on every change to write/read.
     */
    public static final int WIRE_VERSION = 1;

    /** Longest design text read; matches the server's own bound (BannerDesigns.MAX_ID_LENGTH). */
    private static final int MAX_DESIGN_LENGTH = 128;

    public static final CustomPacketPayload.Type<SafariBannerSetPayload> TYPE =
            new CustomPacketPayload.Type<>(CobblemonSafari.id("banner_set"));

    public static final StreamCodec<FriendlyByteBuf, SafariBannerSetPayload> CODEC =
            StreamCodec.of((FriendlyByteBuf buf, SafariBannerSetPayload payload) -> payload.write(buf),
                    SafariBannerSetPayload::read);

    /** MUST list the same fields in the same order as {@link #read}. */
    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(WIRE_VERSION);
        buf.writeInt(pos.getX());
        buf.writeInt(pos.getY());
        buf.writeInt(pos.getZ());
        buf.writeUtf(design, MAX_DESIGN_LENGTH);
    }

    /** MUST list the same fields in the same order as {@link #write}. */
    private static SafariBannerSetPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != WIRE_VERSION) {
            throw new IllegalStateException("Cobblemon Safari: banner set wire version mismatch (got "
                    + version + ", expected " + WIRE_VERSION + ").");
        }
        BlockPos pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        String design = buf.readUtf(MAX_DESIGN_LENGTH);
        return new SafariBannerSetPayload(pos, design);
    }

    /** Type under which each loader registers this payload. */
    @Override
    public CustomPacketPayload.Type<SafariBannerSetPayload> type() {
        return TYPE;
    }
}
