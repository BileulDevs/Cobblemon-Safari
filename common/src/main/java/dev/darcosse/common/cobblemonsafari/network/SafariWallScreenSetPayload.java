package dev.darcosse.common.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client-to-server request to change a Safari Wall Screen group, sent by the
 * settings screen on every change so the block behind it updates live.
 *
 * <p>The content stays a raw ordinal here: the server validates it (an
 * unknown value is a forged or mismatched packet and is ignored there, not
 * thrown here, so one bad packet cannot disconnect the sender with a decode
 * error that hides the cause). Everything else is re-checked on the server
 * too: rights, reach, range.
 *
 * @param pos            the screen the settings were opened on
 * @param contentOrdinal ordinal of the wanted {@code WallScreenContent}
 * @param tilt           wanted tilt, in signed steps (negative faces the ceiling)
 */
public record SafariWallScreenSetPayload(BlockPos pos, int contentOrdinal, int tilt) implements CustomPacketPayload {

    /**
     * Wire format version. Bump on EVERY change to write/read. v1: first
     * version. v2: tilt is signed (negative faces the ceiling); same fields,
     * new meaning, so a v1 peer must be refused.
     */
    public static final int WIRE_VERSION = 2;

    public static final CustomPacketPayload.Type<SafariWallScreenSetPayload> TYPE =
            new CustomPacketPayload.Type<>(CobblemonSafari.id("wall_screen_set"));

    public static final StreamCodec<FriendlyByteBuf, SafariWallScreenSetPayload> CODEC =
            StreamCodec.of((FriendlyByteBuf buf, SafariWallScreenSetPayload payload) -> payload.write(buf),
                    SafariWallScreenSetPayload::read);

    /** MUST list the same fields in the same order as {@link #read}. */
    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(WIRE_VERSION);
        buf.writeInt(pos.getX());
        buf.writeInt(pos.getY());
        buf.writeInt(pos.getZ());
        buf.writeVarInt(contentOrdinal);
        buf.writeVarInt(tilt);
    }

    /** MUST list the same fields in the same order as {@link #write}. */
    private static SafariWallScreenSetPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != WIRE_VERSION) {
            throw new IllegalStateException("Cobblemon Safari: wall screen settings wire version mismatch (got "
                    + version + ", expected " + WIRE_VERSION + ").");
        }
        BlockPos pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        int contentOrdinal = buf.readVarInt();
        int tilt = buf.readVarInt();
        return new SafariWallScreenSetPayload(pos, contentOrdinal, tilt);
    }

    @Override
    public CustomPacketPayload.Type<SafariWallScreenSetPayload> type() {
        return TYPE;
    }
}
