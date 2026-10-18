package dev.darcosse.common.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server-to-client snapshot of the player's own Safari clock.
 *
 * <p>The server is the authority: the client only counts down locally between
 * two snapshots to keep the display smooth, and every snapshot overwrites
 * whatever the client had computed.
 *
 * @param active         whether the player currently has a session
 * @param remainingTicks remaining time when the snapshot was taken
 * @param running        whether the clock is counting down right now; false
 *                       freezes the display (grace period, future pause mechanics)
 * @param catches        Pokémon caught so far this session
 * @param ballsLoaned    Safari Balls lent at the entrance; 0 hides the ball counter
 * @param grantedTicks   total time granted over the session, additions included;
 *                       the denominator of the wall screens' progress bar
 */
public record SafariTimerPayload(boolean active, int remainingTicks, boolean running, int catches, int ballsLoaned,
                                 int grantedTicks)
        implements CustomPacketPayload {

    /**
     * Wire format version. The format is positional: add, remove or reorder a
     * field and a client of another build reads garbage without an error.
     * Bump on EVERY change to write/read.
     * v2: added catches and ballsLoaned.
     * v3: added grantedTicks.
     */
    public static final int WIRE_VERSION = 3;

    public static final CustomPacketPayload.Type<SafariTimerPayload> TYPE =
            new CustomPacketPayload.Type<>(CobblemonSafari.id("timer"));

    public static final StreamCodec<FriendlyByteBuf, SafariTimerPayload> CODEC =
            StreamCodec.of((FriendlyByteBuf buf, SafariTimerPayload payload) -> payload.write(buf),
                    SafariTimerPayload::read);

    /** Snapshot telling the client there is no session any more. */
    public static SafariTimerPayload inactive() {
        return new SafariTimerPayload(false, 0, false, 0, 0, 0);
    }

    /** MUST list the same fields in the same order as {@link #read}. */
    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(WIRE_VERSION);
        buf.writeBoolean(active);
        buf.writeVarInt(remainingTicks);
        buf.writeBoolean(running);
        buf.writeVarInt(catches);
        buf.writeVarInt(ballsLoaned);
        buf.writeVarInt(grantedTicks);
    }

    /**
     * MUST list the same fields in the same order as {@link #write}.
     *
     * @throws IllegalStateException on a version mismatch; refusing is the point,
     *         reading on would display plausible-looking nonsense
     */
    private static SafariTimerPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != WIRE_VERSION) {
            throw new IllegalStateException("Cobblemon Safari: timer wire version mismatch (got " + version
                    + ", expected " + WIRE_VERSION + "). Server and client run different versions of the mod.");
        }
        boolean active = buf.readBoolean();
        int remainingTicks = buf.readVarInt();
        boolean running = buf.readBoolean();
        int catches = buf.readVarInt();
        int ballsLoaned = buf.readVarInt();
        int grantedTicks = buf.readVarInt();
        return new SafariTimerPayload(active, remainingTicks, running, catches, ballsLoaned, grantedTicks);
    }

    /** Payload type used by both loaders to route this packet. */
    @Override
    public CustomPacketPayload.Type<SafariTimerPayload> type() {
        return TYPE;
    }
}
