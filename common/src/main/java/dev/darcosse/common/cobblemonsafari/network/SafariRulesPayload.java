package dev.darcosse.common.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client copy of the rules the client needs to anticipate.
 *
 * <p>Why the client needs them at all: when a Poké Ball is used or a block is
 * clicked, the client acts locally before the server answers, and on Fabric a
 * client-side refusal does not even reach the server. Without these rules the
 * client would show one ball less, or a ghost block, until the next sync. The
 * server still re-checks everything; these values are never trusted.
 *
 * @param dimension                  id of the dimension acting as the Safari, for the HUD
 * @param allowedBalls               Poké Ball ids usable inside
 * @param protectBlocks              whether the terrain cannot be changed
 * @param exemptCreativeAndSpectator whether creative/spectator players ignore terrain protection
 */
public record SafariRulesPayload(ResourceLocation dimension, List<ResourceLocation> allowedBalls,
                                 boolean protectBlocks, boolean exemptCreativeAndSpectator)
        implements CustomPacketPayload {

    /**
     * Wire format version. Bump on EVERY change to write/read.
     * v2: added protectBlocks and exemptCreativeAndSpectator.
     */
    public static final int WIRE_VERSION = 2;

    /**
     * Upper bound on the list size accepted when reading. A corrupted or hostile
     * packet could otherwise announce billions of entries and allocate them.
     */
    private static final int MAX_BALLS = 1024;

    public static final CustomPacketPayload.Type<SafariRulesPayload> TYPE =
            new CustomPacketPayload.Type<>(CobblemonSafari.id("rules"));

    public static final StreamCodec<FriendlyByteBuf, SafariRulesPayload> CODEC =
            StreamCodec.of((FriendlyByteBuf buf, SafariRulesPayload payload) -> payload.write(buf),
                    SafariRulesPayload::read);

    /** MUST list the same fields in the same order as {@link #read}. */
    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(WIRE_VERSION);
        buf.writeResourceLocation(dimension);
        buf.writeVarInt(allowedBalls.size());
        for (ResourceLocation ball : allowedBalls) {
            buf.writeResourceLocation(ball);
        }
        buf.writeBoolean(protectBlocks);
        buf.writeBoolean(exemptCreativeAndSpectator);
    }

    /** MUST list the same fields in the same order as {@link #write}. */
    private static SafariRulesPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != WIRE_VERSION) {
            throw new IllegalStateException("Cobblemon Safari: rules wire version mismatch (got " + version
                    + ", expected " + WIRE_VERSION + "). Server and client run different versions of the mod.");
        }
        ResourceLocation dimension = buf.readResourceLocation();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_BALLS) {
            throw new IllegalStateException("Cobblemon Safari: invalid allowed ball count " + count);
        }
        List<ResourceLocation> balls = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            balls.add(buf.readResourceLocation());
        }
        boolean protectBlocks = buf.readBoolean();
        boolean exemptCreativeAndSpectator = buf.readBoolean();
        return new SafariRulesPayload(dimension, List.copyOf(balls), protectBlocks, exemptCreativeAndSpectator);
    }

    @Override
    public CustomPacketPayload.Type<SafariRulesPayload> type() {
        return TYPE;
    }
}
