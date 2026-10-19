package dev.darcosse.common.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server-to-client lifetime Safari record of the receiving player, for the
 * STATS page of the wall screens. Sent on join, after each catch, at the end
 * of each game and whenever the player's points change.
 *
 * <p>Carries counts only, not the species list: the screen shows how many
 * species, and the list can grow to hundreds of ids sent after every catch.
 *
 * @param sessions      completed Safari games
 * @param catches       Pokémon caught inside the Safari
 * @param shinies       shiny Pokémon caught inside the Safari
 * @param bestSession   most Pokémon caught in one game
 * @param secondsHunted time spent hunting, in seconds (ticks would overflow an int sooner)
 * @param species       distinct species caught
 * @param points        current Safari points balance
 */
public record SafariStatsPayload(int sessions, int catches, int shinies, int bestSession, long secondsHunted,
                                 int species, int points) implements CustomPacketPayload {

    /**
     * Wire format version, checked before anything else is read. The format
     * is positional: adding, removing or reordering a field (or changing what
     * one means) without raising this makes a peer of another build read
     * garbage without an error. Raise it on every change to write/read.
     */
    public static final int WIRE_VERSION = 1;

    public static final CustomPacketPayload.Type<SafariStatsPayload> TYPE =
            new CustomPacketPayload.Type<>(CobblemonSafari.id("stats"));

    public static final StreamCodec<FriendlyByteBuf, SafariStatsPayload> CODEC =
            StreamCodec.of((FriendlyByteBuf buf, SafariStatsPayload payload) -> payload.write(buf),
                    SafariStatsPayload::read);

    /** MUST list the same fields in the same order as {@link #read}. */
    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(WIRE_VERSION);
        buf.writeVarInt(sessions);
        buf.writeVarInt(catches);
        buf.writeVarInt(shinies);
        buf.writeVarInt(bestSession);
        buf.writeVarLong(secondsHunted);
        buf.writeVarInt(species);
        buf.writeVarInt(points);
    }

    /** MUST list the same fields in the same order as {@link #write}. */
    private static SafariStatsPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != WIRE_VERSION) {
            throw new IllegalStateException("Cobblemon Safari: stats wire version mismatch (got " + version
                    + ", expected " + WIRE_VERSION + "). Server and client run different versions of the mod.");
        }
        int sessions = buf.readVarInt();
        int catches = buf.readVarInt();
        int shinies = buf.readVarInt();
        int bestSession = buf.readVarInt();
        long secondsHunted = buf.readVarLong();
        int species = buf.readVarInt();
        int points = buf.readVarInt();
        return new SafariStatsPayload(sessions, catches, shinies, bestSession, secondsHunted, species, points);
    }

    /** Type under which each loader registers this payload. */
    @Override
    public CustomPacketPayload.Type<SafariStatsPayload> type() {
        return TYPE;
    }
}
