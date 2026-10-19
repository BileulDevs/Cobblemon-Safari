package dev.darcosse.common.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.spawn.Rarity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client snapshot of the special Pokémon of the day, for the
 * wall screens.
 *
 * <p>The server owns the draw; clients only display it. Sent on join, when a
 * rotation starts, and after a data or config reload changed the list.
 *
 * @param enabled              whether the featured rotation is turned on in the server config
 * @param millisUntilRotation  time left in the current rotation when the snapshot was built;
 *                             relative, because server and client clocks need not agree
 * @param rotationLengthMillis length of a whole rotation, for the progress bar
 * @param entries              the specials, most common first
 */
public record SafariFeaturedPayload(boolean enabled, long millisUntilRotation, long rotationLengthMillis,
                                    List<Entry> entries) implements CustomPacketPayload {

    /**
     * Wire format version, checked before anything else is read. The format
     * is positional: adding, removing or reordering a field (or changing what
     * one means) without raising this makes a peer of another build read
     * garbage without an error. Raise it on every change to write/read.
     */
    public static final int WIRE_VERSION = 1;

    /**
     * Upper bound on entries read from the wire. Far above any sane slot
     * configuration; protects the client from allocating a list sized by a
     * corrupted varint.
     */
    private static final int MAX_ENTRIES = 64;

    /** Same idea for the aspects of one entry. */
    private static final int MAX_ASPECTS = 32;

    public static final CustomPacketPayload.Type<SafariFeaturedPayload> TYPE =
            new CustomPacketPayload.Type<>(CobblemonSafari.id("featured"));

    public static final StreamCodec<FriendlyByteBuf, SafariFeaturedPayload> CODEC =
            StreamCodec.of((FriendlyByteBuf buf, SafariFeaturedPayload payload) -> payload.write(buf),
                    SafariFeaturedPayload::read);

    /**
     * One special as the client needs it: enough to draw the model and the
     * labels, nothing the client could not be trusted with.
     *
     * @param species  Cobblemon species id; the client resolves it in its synced species registry
     * @param aspects  aspects of the spawn template (regional form, gender...), to pick the right model
     * @param rarity   rarity tier
     * @param habitat  main Safari biome, or null when unknown
     * @param special  whether it is legendary-class, shown with a star
     * @param minLevel lowest natural level, 0 when unknown
     * @param maxLevel highest natural level, 0 when unknown
     */
    public record Entry(ResourceLocation species, List<String> aspects, Rarity rarity,
                        @Nullable ResourceLocation habitat, boolean special, int minLevel, int maxLevel) {
    }

    /** MUST list the same fields in the same order as {@link #read}. */
    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(WIRE_VERSION);
        buf.writeBoolean(enabled);
        buf.writeVarLong(millisUntilRotation);
        buf.writeVarLong(rotationLengthMillis);
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeResourceLocation(entry.species());
            buf.writeVarInt(entry.aspects().size());
            for (String aspect : entry.aspects()) {
                buf.writeUtf(aspect);
            }
            buf.writeVarInt(entry.rarity().ordinal());
            buf.writeBoolean(entry.habitat() != null);
            if (entry.habitat() != null) {
                buf.writeResourceLocation(entry.habitat());
            }
            buf.writeBoolean(entry.special());
            buf.writeVarInt(entry.minLevel());
            buf.writeVarInt(entry.maxLevel());
        }
    }

    /**
     * MUST list the same fields in the same order as {@link #write}.
     *
     * @throws IllegalStateException on a version mismatch or out-of-range data;
     *         refusing is the point, reading on would display nonsense
     */
    private static SafariFeaturedPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != WIRE_VERSION) {
            throw new IllegalStateException("Cobblemon Safari: featured wire version mismatch (got " + version
                    + ", expected " + WIRE_VERSION + "). Server and client run different versions of the mod.");
        }
        boolean enabled = buf.readBoolean();
        long millisUntilRotation = buf.readVarLong();
        long rotationLengthMillis = buf.readVarLong();
        int count = checkedCount(buf.readVarInt(), MAX_ENTRIES, "entries");
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ResourceLocation species = buf.readResourceLocation();
            int aspectCount = checkedCount(buf.readVarInt(), MAX_ASPECTS, "aspects");
            List<String> aspects = new ArrayList<>(aspectCount);
            for (int a = 0; a < aspectCount; a++) {
                aspects.add(buf.readUtf());
            }
            int rarityIndex = buf.readVarInt();
            if (rarityIndex < 0 || rarityIndex >= Rarity.values().length) {
                throw new IllegalStateException("Cobblemon Safari: unknown rarity index " + rarityIndex);
            }
            ResourceLocation habitat = buf.readBoolean() ? buf.readResourceLocation() : null;
            boolean special = buf.readBoolean();
            int minLevel = buf.readVarInt();
            int maxLevel = buf.readVarInt();
            entries.add(new Entry(species, List.copyOf(aspects), Rarity.values()[rarityIndex], habitat, special,
                    minLevel, maxLevel));
        }
        return new SafariFeaturedPayload(enabled, millisUntilRotation, rotationLengthMillis, List.copyOf(entries));
    }

    /** Rejects a list length outside {@code [0, max]}. */
    private static int checkedCount(int count, int max, String what) {
        if (count < 0 || count > max) {
            throw new IllegalStateException("Cobblemon Safari: featured payload has " + count + " " + what
                    + " (max " + max + ")");
        }
        return count;
    }

    /** Type under which each loader registers this payload. */
    @Override
    public CustomPacketPayload.Type<SafariFeaturedPayload> type() {
        return TYPE;
    }
}
