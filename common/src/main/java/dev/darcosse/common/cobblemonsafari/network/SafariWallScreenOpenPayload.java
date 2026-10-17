package dev.darcosse.common.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.block.WallScreenContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server-to-client order to open the settings screen of a Safari Wall Screen.
 *
 * <p>Sent by the server rather than opened by the client on its own because
 * only the server knows whether this player may change this screen
 * (operator rights, Safari Gate surroundings). The current settings travel
 * with it so the screen opens on the group's real values, even if the
 * client's copy of the block lags.
 *
 * @param pos     the screen that was clicked; any block of the group works
 * @param content what the group shows now
 * @param tilt    the group's tilt, in signed steps (negative faces the ceiling)
 */
public record SafariWallScreenOpenPayload(BlockPos pos, WallScreenContent content, int tilt)
        implements CustomPacketPayload {

    /**
     * Wire format version. Bump on EVERY change to write/read. v1: first
     * version. v2: tilt is signed (negative faces the ceiling); same fields,
     * new meaning, so a v1 peer must be refused.
     */
    public static final int WIRE_VERSION = 2;

    public static final CustomPacketPayload.Type<SafariWallScreenOpenPayload> TYPE =
            new CustomPacketPayload.Type<>(CobblemonSafari.id("wall_screen_open"));

    public static final StreamCodec<FriendlyByteBuf, SafariWallScreenOpenPayload> CODEC =
            StreamCodec.of((FriendlyByteBuf buf, SafariWallScreenOpenPayload payload) -> payload.write(buf),
                    SafariWallScreenOpenPayload::read);

    /** MUST list the same fields in the same order as {@link #read}. */
    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(WIRE_VERSION);
        buf.writeInt(pos.getX());
        buf.writeInt(pos.getY());
        buf.writeInt(pos.getZ());
        buf.writeVarInt(content.ordinal());
        buf.writeVarInt(tilt);
    }

    /** MUST list the same fields in the same order as {@link #write}. */
    private static SafariWallScreenOpenPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != WIRE_VERSION) {
            throw new IllegalStateException("Cobblemon Safari: wall screen open wire version mismatch (got "
                    + version + ", expected " + WIRE_VERSION + "). Server and client run different versions of the mod.");
        }
        BlockPos pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
        int contentOrdinal = buf.readVarInt();
        WallScreenContent content = WallScreenContent.byOrdinal(contentOrdinal);
        if (content == null) {
            throw new IllegalStateException("Cobblemon Safari: unknown wall screen content " + contentOrdinal);
        }
        int tilt = buf.readVarInt();
        return new SafariWallScreenOpenPayload(pos, content, tilt);
    }

    @Override
    public CustomPacketPayload.Type<SafariWallScreenOpenPayload> type() {
        return TYPE;
    }
}
