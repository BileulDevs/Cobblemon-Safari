package dev.darcosse.common.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server-to-client order to open or close the loading cinematic of a rest
 * house trip (teleport.RestHouseTravel).
 *
 * <p>START opens it with the destination's name; END closes it with a fade,
 * whether the player was moved or the trip failed (the reason comes as an
 * action bar message). The client cannot tell on its own when the
 * destination is ready: only the server builds and loads it.
 *
 * <p>The name travels as translation keys rather than a component, so the
 * payload needs no registry access and each player reads it in their own
 * language.
 *
 * @param starting     true to open, false to close
 * @param biomeKey     translation key of the destination's biome; "" when closing
 * @param directionKey translation key of its compass direction, or "" when the biome is enough
 * @param timeoutTicks how long the client keeps the cinematic open without hearing back, in
 *                     ticks; a lost END (server restarted, connection hiccup) must not trap the
 *                     player behind it. 0 when closing
 */
public record SafariTravelPayload(boolean starting, String biomeKey, String directionKey, int timeoutTicks)
        implements CustomPacketPayload {

    /**
     * Wire format version, checked before anything else is read. The format
     * is positional: adding, removing or reordering a field (or changing what
     * one means) without raising this makes a peer of another build read
     * garbage without an error. Raise it on every change to write/read.
     */
    public static final int WIRE_VERSION = 1;

    /**
     * Longest translation key accepted, in characters. Biome keys are short;
     * the bound keeps a forged packet from making the client allocate a huge
     * string.
     */
    private static final int MAX_KEY_LENGTH = 256;

    public static final CustomPacketPayload.Type<SafariTravelPayload> TYPE =
            new CustomPacketPayload.Type<>(CobblemonSafari.id("travel"));

    public static final StreamCodec<FriendlyByteBuf, SafariTravelPayload> CODEC =
            StreamCodec.of((FriendlyByteBuf buf, SafariTravelPayload payload) -> payload.write(buf),
                    SafariTravelPayload::read);

    /** Opens the cinematic toward a destination. */
    public static SafariTravelPayload start(String biomeKey, String directionKey, int timeoutTicks) {
        return new SafariTravelPayload(true, biomeKey, directionKey, timeoutTicks);
    }

    /** Closes the cinematic. */
    public static SafariTravelPayload end() {
        return new SafariTravelPayload(false, "", "", 0);
    }

    /** MUST list the same fields in the same order as {@link #read}. */
    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(WIRE_VERSION);
        buf.writeBoolean(starting);
        buf.writeUtf(biomeKey, MAX_KEY_LENGTH);
        buf.writeUtf(directionKey, MAX_KEY_LENGTH);
        buf.writeVarInt(timeoutTicks);
    }

    /** MUST list the same fields in the same order as {@link #write}. */
    private static SafariTravelPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != WIRE_VERSION) {
            throw new IllegalStateException("Cobblemon Safari: travel wire version mismatch (got " + version
                    + ", expected " + WIRE_VERSION + "). Server and client run different versions of the mod.");
        }
        boolean starting = buf.readBoolean();
        String biomeKey = buf.readUtf(MAX_KEY_LENGTH);
        String directionKey = buf.readUtf(MAX_KEY_LENGTH);
        int timeoutTicks = buf.readVarInt();
        return new SafariTravelPayload(starting, biomeKey, directionKey, timeoutTicks);
    }

    /** Type under which each loader registers this payload. */
    @Override
    public CustomPacketPayload.Type<SafariTravelPayload> type() {
        return TYPE;
    }
}
