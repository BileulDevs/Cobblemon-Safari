package dev.darcosse.common.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.bounty.Bounty;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client missions of the day and the receiving player's progress,
 * for the BOUNTIES page of the wall screens. Sent on join, when the missions
 * change, and when the player's progress moves.
 *
 * @param enabled      whether missions are switched on; when false the board skips their page
 * @param bounties     today's missions, the same for everyone
 * @param progress     this player's catches toward each mission, same order and size as {@code bounties}
 * @param allDoneBonus points paid once every mission is done
 */
public record SafariBountiesPayload(boolean enabled, List<Bounty> bounties, List<Integer> progress,
                                    int allDoneBonus) implements CustomPacketPayload {

    /**
     * Wire format version, checked before anything else is read. The format
     * is positional: adding, removing or reordering a field (or changing what
     * one means) without raising this makes a peer of another build read
     * garbage without an error. Raise it on every change to write/read.
     */
    public static final int WIRE_VERSION = 1;

    /** Guard against corrupted or hostile packets announcing huge lists. */
    private static final int MAX_BOUNTIES = 64;

    public static final CustomPacketPayload.Type<SafariBountiesPayload> TYPE =
            new CustomPacketPayload.Type<>(CobblemonSafari.id("bounties"));

    public static final StreamCodec<FriendlyByteBuf, SafariBountiesPayload> CODEC =
            StreamCodec.of((FriendlyByteBuf buf, SafariBountiesPayload payload) -> payload.write(buf),
                    SafariBountiesPayload::read);

    /** MUST list the same fields in the same order as {@link #read}. */
    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(WIRE_VERSION);
        buf.writeBoolean(enabled);
        buf.writeVarInt(bounties.size());
        for (int i = 0; i < bounties.size(); i++) {
            Bounty bounty = bounties.get(i);
            buf.writeVarInt(bounty.kind().ordinal());
            buf.writeUtf(bounty.target());
            buf.writeVarInt(bounty.count());
            buf.writeVarInt(bounty.reward());
            buf.writeVarInt(progress.get(i));
        }
        buf.writeVarInt(allDoneBonus);
    }

    /** MUST list the same fields in the same order as {@link #write}. */
    private static SafariBountiesPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != WIRE_VERSION) {
            throw new IllegalStateException("Cobblemon Safari: bounties wire version mismatch (got " + version
                    + ", expected " + WIRE_VERSION + "). Server and client run different versions of the mod.");
        }
        boolean enabled = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_BOUNTIES) {
            throw new IllegalStateException("Cobblemon Safari: invalid bounty count " + count);
        }
        Bounty.Kind[] kinds = Bounty.Kind.values();
        List<Bounty> bounties = new ArrayList<>(count);
        List<Integer> progress = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int kind = buf.readVarInt();
            if (kind < 0 || kind >= kinds.length) {
                throw new IllegalStateException("Cobblemon Safari: unknown bounty kind " + kind);
            }
            bounties.add(new Bounty(kinds[kind], buf.readUtf(), buf.readVarInt(), buf.readVarInt()));
            progress.add(buf.readVarInt());
        }
        int allDoneBonus = buf.readVarInt();
        return new SafariBountiesPayload(enabled, List.copyOf(bounties), List.copyOf(progress), allDoneBonus);
    }

    /** Type under which each loader registers this payload. */
    @Override
    public CustomPacketPayload.Type<SafariBountiesPayload> type() {
        return TYPE;
    }
}
