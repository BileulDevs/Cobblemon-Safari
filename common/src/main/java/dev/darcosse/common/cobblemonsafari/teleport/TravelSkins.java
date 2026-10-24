package dev.darcosse.common.cobblemonsafari.teleport;

import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.cobblemon.mod.common.entity.npc.NPCPlayerModelType;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The skins worn by the players' doubles in the flight cinematic, fetched
 * once per player and server run.
 *
 * <p>Why not Cobblemon's own {@code NPCEntity.loadTexture(URI)} on the skin
 * URL: it downloads on the calling thread, and the server thread would
 * stall for the whole download. Here the download runs on the game's I/O
 * pool into a temporary file, and {@code loadTexture} is then given that
 * local file on the server thread, a read of a few kilobytes. The skin
 * reaches the clients through the NPC's synced data, like any NPC skin.
 *
 * <p>The skin URL comes from the player's own profile, signed by Mojang
 * when they logged in: no lookup by name. An offline-mode server has no
 * skin to give; the double then wears the traveller NPC's default look.
 * Server side.
 */
final class TravelSkins {

    /**
     * Longest wait for Mojang's texture server, in milliseconds, to connect
     * and then to read. A slow answer only delays the skin, never the trip.
     */
    private static final int TIMEOUT_MS = 5_000;

    /**
     * Largest skin accepted, in bytes. A 64 x 64 PNG weighs a few kilobytes;
     * the bound stops a broken answer from filling the memory, and the bytes
     * are synced to every client that sees the double.
     */
    private static final int MAX_SKIN_BYTES = 64 * 1024;

    /** A downloaded skin: the local copy and the arm model it was drawn for. */
    private record Skin(Path file, NPCPlayerModelType model) {
    }

    /** Skins by player: done, under way, or empty when the player has none. */
    private static final Map<UUID, CompletableFuture<Optional<Skin>>> SKINS = new ConcurrentHashMap<>();

    private TravelSkins() {
    }

    /**
     * Dresses the double in the player's skin, now if it is already known,
     * as soon as it arrives otherwise. Does nothing when the player has no
     * skin, or when the double is gone by then. Cost on the server thread:
     * a map lookup, then a small file read when the skin is applied.
     */
    static void dress(MinecraftServer server, ServerPlayer player, NPCEntity npc) {
        skinOf(server, player).thenAccept(skin -> skin.ifPresent(found -> server.execute(() -> {
            if (!npc.isRemoved()) {
                npc.loadTexture(found.file().toUri(), found.model());
            }
        })));
    }

    /** Starts fetching the player's skin, unless known or under way. */
    private static CompletableFuture<Optional<Skin>> skinOf(MinecraftServer server, ServerPlayer player) {
        UUID id = player.getUUID();
        CompletableFuture<Optional<Skin>> known = SKINS.get(id);
        if (known != null) {
            return known;
        }
        // Read on the server thread: the profile is not meant to be shared.
        MinecraftProfileTextures textures = server.getSessionService().getTextures(player.getGameProfile());
        MinecraftProfileTexture skin = textures.skin();
        if (skin == null) {
            CompletableFuture<Optional<Skin>> none = CompletableFuture.completedFuture(Optional.empty());
            SKINS.put(id, none);
            return none;
        }
        String url = skin.getUrl();
        NPCPlayerModelType model = "slim".equalsIgnoreCase(skin.getMetadata("model"))
                ? NPCPlayerModelType.SLIM : NPCPlayerModelType.DEFAULT;
        CompletableFuture<Optional<Skin>> fetch = CompletableFuture.supplyAsync(() -> download(url, model),
                Util.ioPool());
        SKINS.put(id, fetch);
        // A failure is not kept: the next trip tries again.
        fetch.thenAccept(result -> {
            if (result.isEmpty()) {
                SKINS.remove(id, fetch);
            }
        });
        return fetch;
    }

    /** Downloads a skin into a temporary file. On the I/O pool. */
    private static Optional<Skin> download(String url, NPCPlayerModelType model) {
        try {
            URLConnection connection = URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            byte[] bytes;
            try (InputStream in = connection.getInputStream()) {
                bytes = in.readNBytes(MAX_SKIN_BYTES + 1);
            }
            if (bytes.length > MAX_SKIN_BYTES) {
                CobblemonSafari.LOGGER.warn("Skin at {} is larger than {} bytes; the double keeps its default look",
                        url, MAX_SKIN_BYTES);
                return Optional.empty();
            }
            Path file = Files.createTempFile(CobblemonSafari.MOD_ID + "_skin", ".png");
            file.toFile().deleteOnExit();
            Files.write(file, bytes);
            return Optional.of(new Skin(file, model));
        } catch (IOException | IllegalArgumentException e) {
            CobblemonSafari.LOGGER.warn("Could not download the skin at {}: {}", url, e.toString());
            return Optional.empty();
        }
    }

    /** Forgets the skins; call when a server starts, as single player reuses this class. */
    static void reset() {
        SKINS.clear();
    }
}
