package dev.darcosse.common.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client Safari Pokédex of the receiving player, for the DEX page
 * of the wall screens: per habitat, how many of its species the player has
 * caught in the Safari. Sent on join, when the player catches a species new
 * to them, and whenever the list of the Safari's species changes (data
 * reload, a late first build of the habitat index).
 *
 * <p>Counts only, not species: a habitat can hold dozens of species and the
 * page only shows progress. The species themselves are listed by
 * {@code /safari dex}, on the server.
 *
 * @param habitats the habitats holding at least one species, in the Safari's
 *                 canonical order; empty while the species are not known yet
 */
public record SafariDexPayload(List<Habitat> habitats) implements CustomPacketPayload {

    /**
     * Wire format version, checked before anything else is read. The format
     * is positional: adding, removing or reordering a field (or changing what
     * one means) without raising this makes a peer of another build read
     * garbage without an error. Raise it on every change to write/read.
     */
    public static final int WIRE_VERSION = 1;

    /**
     * Most habitats read from one packet. The Safari has 19 surface habitats;
     * the cap only stops a forged packet from allocating a huge list.
     */
    private static final int MAX_HABITATS = 64;

    public static final CustomPacketPayload.Type<SafariDexPayload> TYPE =
            new CustomPacketPayload.Type<>(CobblemonSafari.id("dex"));

    public static final StreamCodec<FriendlyByteBuf, SafariDexPayload> CODEC =
            StreamCodec.of((FriendlyByteBuf buf, SafariDexPayload payload) -> payload.write(buf),
                    SafariDexPayload::read);

    /**
     * One habitat's progress.
     *
     * @param biome  the Safari biome id; its name is the biome's translation
     * @param caught species of this habitat the player caught in the Safari
     * @param total  species whose main habitat this is
     */
    public record Habitat(ResourceLocation biome, int caught, int total) {
    }

    /** Species caught over every habitat. */
    public int caught() {
        int sum = 0;
        for (Habitat habitat : habitats) {
            sum += habitat.caught();
        }
        return sum;
    }

    /** Species over every habitat. */
    public int total() {
        int sum = 0;
        for (Habitat habitat : habitats) {
            sum += habitat.total();
        }
        return sum;
    }

    /** MUST list the same fields in the same order as {@link #read}. */
    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(WIRE_VERSION);
        buf.writeVarInt(habitats.size());
        for (Habitat habitat : habitats) {
            buf.writeResourceLocation(habitat.biome());
            buf.writeVarInt(habitat.caught());
            buf.writeVarInt(habitat.total());
        }
    }

    /** MUST list the same fields in the same order as {@link #write}. */
    private static SafariDexPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != WIRE_VERSION) {
            throw new IllegalStateException("Cobblemon Safari: dex wire version mismatch (got " + version
                    + ", expected " + WIRE_VERSION + "). Server and client run different versions of the mod.");
        }
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_HABITATS) {
            throw new IllegalStateException("Cobblemon Safari: dex payload with " + count + " habitats");
        }
        List<Habitat> habitats = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            habitats.add(new Habitat(buf.readResourceLocation(), buf.readVarInt(), buf.readVarInt()));
        }
        return new SafariDexPayload(List.copyOf(habitats));
    }

    /** Type under which each loader registers this payload. */
    @Override
    public CustomPacketPayload.Type<SafariDexPayload> type() {
        return TYPE;
    }
}
