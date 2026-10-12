package dev.darcosse.common.cobblemonsafari.reset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.teleport.SafariTeleporter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Regenerates the Safari map once a day, at the first server start of a new day.
 *
 * <p>The Safari dimension's folder is deleted before the levels load, and a new
 * "map salt" is mixed into the seed its terrain is generated with (see
 * {@code ChunkMapMixin}), so the island comes back with a different layout.
 * Doing it at start-up, never while running, means no player and no chunk of
 * the Safari is loaded while its files disappear.
 *
 * <p>State lives in {@code <world>/cobblemon_safari_map.json}, read before any
 * level exists (SavedData is not available that early). Server side only.
 */
public final class SafariMapReset {

    /** File in the world folder holding the current map day and salt. */
    private static final String STATE_FILE = CobblemonSafari.MOD_ID + "_map.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Salt of the map currently on disk; 0 means the world's own seed (the map before any reset). */
    private static volatile long salt;
    /** Whether this server run started on a freshly reset map. */
    private static boolean resetThisRun;
    /** Players already moved to the arrival point after a reset, during this run. */
    private static final Set<UUID> RELOCATED = new HashSet<>();

    /** What the state file stores. Field names are the file's keys. */
    private static final class State {
        /** Map day ("2026-10-08"); empty forces a reset at the next start. */
        String day = "";
        long salt;
    }

    private SafariMapReset() {
    }

    /**
     * Seed the Safari's chunk generator must use. Called by the chunk map of
     * every level as it is created; other levels keep the world seed.
     */
    public static long seedFor(ServerLevel level, long worldSeed) {
        return SafariDimensions.isSafari(level) ? worldSeed ^ salt : worldSeed;
    }

    /** Salt of the map on disk; changes only when the map is reset. */
    public static long salt() {
        return salt;
    }

    /**
     * Runs before the levels load: resets the map when a new day started since
     * the last reset, then publishes the salt for the chunk generator.
     */
    public static void onServerStarting(MinecraftServer server) {
        resetThisRun = false;
        RELOCATED.clear();
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path stateFile = worldRoot.resolve(STATE_FILE);
        SafariConfig.Reset config = SafariConfigManager.get().reset;
        String today = today(config);

        State state = read(stateFile);
        if (state == null) {
            // First start with this feature: keep the existing map, start counting from today.
            state = new State();
            state.day = today;
            state.salt = 0L;
            write(stateFile, state);
        } else if (config.enabled && !today.equals(state.day)) {
            Path folder = DimensionType.getStorageFolder(SafariDimensions.key(), worldRoot);
            if (deleteFolder(folder)) {
                state.day = today;
                state.salt = newSalt();
                write(stateFile, state);
                resetThisRun = true;
                CobblemonSafari.LOGGER.info("Safari map reset for {}: {} deleted, new terrain will be generated",
                        today, folder);
            }
        }
        salt = state.salt;
    }

    /**
     * Forces a reset at the next server start, whatever the day. Used by the
     * operator command; the running map is not touched.
     */
    public static boolean scheduleReset(MinecraftServer server) {
        Path stateFile = server.getWorldPath(LevelResource.ROOT).resolve(STATE_FILE);
        State state = read(stateFile);
        if (state == null) {
            state = new State();
            state.salt = salt;
        }
        state.day = "";
        return write(stateFile, state);
    }

    /**
     * Moves a player who logs in inside the Safari to the arrival point when
     * the map they left no longer exists, or when they would be stuck in a
     * block. Called at the end of the tick they joined in, never from the
     * join event itself (see SafariServerEvents.onPlayerJoin).
     */
    public static void onPlayerJoin(ServerPlayer player) {
        if (!SafariDimensions.isSafari(player.level())) {
            return;
        }
        boolean staleMap = resetThisRun && RELOCATED.add(player.getUUID());
        boolean stuck = !player.level().noCollision(player);
        if (staleMap || stuck) {
            SafariTeleporter.sendIntoSafari(player);
        }
    }

    /** The current map day in the configured time zone, shifted so the day starts at the reset hour. */
    private static String today(SafariConfig.Reset config) {
        ZoneId zone;
        try {
            zone = config.timeZone == null || config.timeZone.isBlank()
                    ? ZoneId.systemDefault() : ZoneId.of(config.timeZone);
        } catch (DateTimeException e) {
            CobblemonSafari.LOGGER.warn("Invalid reset.timeZone '{}', using the system time zone", config.timeZone);
            zone = ZoneId.systemDefault();
        }
        return ZonedDateTime.now(zone).minusHours(config.hour).toLocalDate().toString();
    }

    /** A random salt, never 0 (0 stands for the original map). */
    private static long newSalt() {
        long value;
        SecureRandom random = new SecureRandom();
        do {
            value = random.nextLong();
        } while (value == 0L);
        return value;
    }

    /**
     * Deletes the dimension folder. A partial delete would mix old chunks
     * with new terrain, so on any failure the reset is abandoned and retried
     * at the next start.
     *
     * @return true when the folder is gone (or never existed)
     */
    private static boolean deleteFolder(Path folder) {
        if (!Files.exists(folder)) {
            return true;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
            return true;
        } catch (IOException e) {
            CobblemonSafari.LOGGER.error("Could not delete {}; the Safari map is NOT reset this time", folder, e);
            return false;
        }
    }

    /** Reads the state file, or null when it is missing or unreadable. */
    private static State read(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            State state = GSON.fromJson(reader, State.class);
            if (state != null && state.day == null) {
                state.day = "";
            }
            return state;
        } catch (IOException | JsonParseException e) {
            CobblemonSafari.LOGGER.warn("Could not read {}; treating the Safari map as new", file, e);
            return null;
        }
    }

    /** Writes the state file; returns false and logs on failure. */
    private static boolean write(Path file, State state) {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(state, writer);
            return true;
        } catch (IOException e) {
            CobblemonSafari.LOGGER.error("Could not write {}", file, e);
            return false;
        }
    }
}
