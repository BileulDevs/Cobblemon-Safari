package dev.darcosse.common.cobblemonsafari.network;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Server-to-client orders driving the cinematic of a rest house trip
 * (teleport.RestHouseTravel).
 *
 * <ul>
 *   <li>START opens it, with the scene to play and the destination's name.
 *   For a {@link Scene#FLIGHT} it also carries the first shot.</li>
 *   <li>SHOT (flight only) points the game camera at an actor from a fixed
 *   spot, or fades to black between the departure and the arrival
 *   (actor -1).</li>
 *   <li>END closes it with a fade, whether the player was moved or the trip
 *   failed (the reason comes as an action bar message).</li>
 * </ul>
 * The client cannot tell on its own when the destination is ready, nor
 * where the actors fly: only the server knows.
 *
 * <p>The name travels as translation keys rather than a component, so the
 * payload needs no registry access and each player reads it in their own
 * language. Every field is written for every kind, to keep one layout.
 *
 * @param kind         what this order does
 * @param scene        what the cinematic shows; read on START only
 * @param biomeKey     translation key of the destination's biome; "" when not START
 * @param directionKey translation key of its compass direction, or "" when the biome is enough
 * @param timeoutTicks how long the client keeps the cinematic open without hearing back, in
 *                     ticks; a lost END (server restarted, connection hiccup) must not trap the
 *                     player behind it. 0 when not START
 * @param actorId      network id of the entity the camera follows; -1 for black (and when unused)
 * @param camera       where the camera stands for this shot; zero when unused
 * @param look         what it looks at until the actor reaches the client; zero when unused
 */
public record SafariTravelPayload(Kind kind, Scene scene, String biomeKey, String directionKey, int timeoutTicks,
                                  int actorId, Vec3 camera, Vec3 look) implements CustomPacketPayload {

    /** What a payload asks of the client. Sent as its ordinal: append, never reorder. */
    public enum Kind {
        START, SHOT, END
    }

    /** The picture of a trip. Sent as its ordinal: append, never reorder. */
    public enum Scene {
        /** The painted Safari landscape panning between black bands (guide, PANORAMA style). */
        LANDSCAPE,
        /** The pixel-art escape from a cave on a bird's back (Escape Rope). */
        CAVE,
        /** The world itself, filmed by the game camera while the double flies (guide, FLIGHT style). */
        FLIGHT
    }

    /**
     * Wire format version, checked before anything else is read. The format
     * is positional: adding, removing or reordering a field (or changing what
     * one means) without raising this makes a peer of another build read
     * garbage without an error. Raise it on every change to write/read.
     *
     * <p>History: 1 had starting, biomeKey, directionKey, timeoutTicks. 2
     * replaced starting by kind and added scene, actorId, camera and look.
     */
    public static final int WIRE_VERSION = 2;

    /**
     * Longest translation key accepted, in characters. Biome keys are short;
     * the bound keeps a forged packet from making the client allocate a huge
     * string.
     */
    private static final int MAX_KEY_LENGTH = 256;

    /** The id meaning "no actor": the shot is black. Entity ids are never negative. */
    public static final int NO_ACTOR = -1;

    public static final CustomPacketPayload.Type<SafariTravelPayload> TYPE =
            new CustomPacketPayload.Type<>(CobblemonSafari.id("travel"));

    public static final StreamCodec<FriendlyByteBuf, SafariTravelPayload> CODEC =
            StreamCodec.of((FriendlyByteBuf buf, SafariTravelPayload payload) -> payload.write(buf),
                    SafariTravelPayload::read);

    /** Opens a painted cinematic (landscape or cave) toward a destination. */
    public static SafariTravelPayload start(Scene scene, String biomeKey, String directionKey, int timeoutTicks) {
        return new SafariTravelPayload(Kind.START, scene, biomeKey, directionKey, timeoutTicks, NO_ACTOR, Vec3.ZERO,
                Vec3.ZERO);
    }

    /** Opens the flight cinematic on its first shot. */
    public static SafariTravelPayload startFlight(String biomeKey, String directionKey, int timeoutTicks,
                                                  int actorId, Vec3 camera, Vec3 look) {
        return new SafariTravelPayload(Kind.START, Scene.FLIGHT, biomeKey, directionKey, timeoutTicks, actorId,
                camera, look);
    }

    /** Points the flight camera at an actor. */
    public static SafariTravelPayload shot(int actorId, Vec3 camera, Vec3 look) {
        return new SafariTravelPayload(Kind.SHOT, Scene.FLIGHT, "", "", 0, actorId, camera, look);
    }

    /** Fades the flight cinematic to black, between two shots. */
    public static SafariTravelPayload black() {
        return shot(NO_ACTOR, Vec3.ZERO, Vec3.ZERO);
    }

    /** Closes the cinematic. */
    public static SafariTravelPayload end() {
        return new SafariTravelPayload(Kind.END, Scene.LANDSCAPE, "", "", 0, NO_ACTOR, Vec3.ZERO, Vec3.ZERO);
    }

    /** MUST list the same fields in the same order as {@link #read}. */
    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(WIRE_VERSION);
        buf.writeByte(kind.ordinal());
        buf.writeByte(scene.ordinal());
        buf.writeUtf(biomeKey, MAX_KEY_LENGTH);
        buf.writeUtf(directionKey, MAX_KEY_LENGTH);
        buf.writeVarInt(timeoutTicks);
        buf.writeInt(actorId);
        writeVec(buf, camera);
        writeVec(buf, look);
    }

    /** MUST list the same fields in the same order as {@link #write}. */
    private static SafariTravelPayload read(FriendlyByteBuf buf) {
        int version = buf.readVarInt();
        if (version != WIRE_VERSION) {
            throw new IllegalStateException("Cobblemon Safari: travel wire version mismatch (got " + version
                    + ", expected " + WIRE_VERSION + "). Server and client run different versions of the mod.");
        }
        Kind kind = constant(Kind.values(), buf.readByte(), "kind");
        Scene scene = constant(Scene.values(), buf.readByte(), "scene");
        String biomeKey = buf.readUtf(MAX_KEY_LENGTH);
        String directionKey = buf.readUtf(MAX_KEY_LENGTH);
        int timeoutTicks = buf.readVarInt();
        int actorId = buf.readInt();
        Vec3 camera = readVec(buf);
        Vec3 look = readVec(buf);
        return new SafariTravelPayload(kind, scene, biomeKey, directionKey, timeoutTicks, actorId, camera, look);
    }

    /** A position as three doubles: a shot's camera must not snap to a block grid. */
    private static void writeVec(FriendlyByteBuf buf, Vec3 vec) {
        buf.writeDouble(vec.x);
        buf.writeDouble(vec.y);
        buf.writeDouble(vec.z);
    }

    /** Reads what {@link #writeVec} wrote. */
    private static Vec3 readVec(FriendlyByteBuf buf) {
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        return new Vec3(x, y, z);
    }

    /** The constant of an ordinal, or an error naming the field: a bad byte is another build. */
    private static <E extends Enum<E>> E constant(E[] values, int ordinal, String field) {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalStateException("Cobblemon Safari: travel payload has an unknown " + field + " (" + ordinal
                    + "). Server and client run different versions of the mod.");
        }
        return values[ordinal];
    }

    /** Type under which each loader registers this payload. */
    @Override
    public CustomPacketPayload.Type<SafariTravelPayload> type() {
        return TYPE;
    }
}
