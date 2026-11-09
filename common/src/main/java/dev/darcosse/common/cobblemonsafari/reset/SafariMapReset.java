package dev.darcosse.common.cobblemonsafari.reset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfig;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.teleport.SafariTeleporter;
import dev.darcosse.common.cobblemonsafari.worldgen.IslandRelief;
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

import org.jetbrains.annotations.Nullable;

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
    /** Relief of the map on disk, read by the terrain while it generates (ReliefFunction). */
    private static volatile IslandRelief relief = IslandRelief.ROLLING;
    /** Whether this server run started on a freshly reset map. */
    private static boolean resetThisRun;

    /**
     * Day the current map belongs to, as in the state file; "" when a reset
     * was asked for. Kept in memory so the live reset's once-a-minute check
     * reads no file.
     */
    private static volatile String mapDay = "";
    /** Players already moved to the arrival point after a reset, during this run. */
    private static final Set<UUID> RELOCATED = new HashSet<>();

    /** What the state file stores. Field names are the file's keys. */
    private static final class State {
        /** Map day ("2026-10-08"); empty forces a reset at the next start. */
        String day = "";
        long salt;
        /**
         * Relief the map was generated with. Absent in files written before
         * 1.3.0, whose maps all have the one relief there was: ROLLING.
         */
        IslandRelief relief;
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

    /**
     * Relief of the map on disk; changes only when the map is reset, so
     * that the chunks generated later match the ones already there.
     */
    public static IslandRelief relief() {
        return relief;
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
        Path folder = DimensionType.getStorageFolder(SafariDimensions.key(), worldRoot);

        // Before any new move aside: its own background deletion must not get a second thread.
        deleteLeftovers(folder);
        State state = read(stateFile);
        if (state == null) {
            // No state file yet (a new world): keep the map as generated, start counting from today.
            state = new State();
            state.day = today;
            state.salt = 0L;
            // A world from before the state file may already hold a map, generated ROLLING.
            state.relief = Files.isDirectory(folder) ? IslandRelief.ROLLING : config.relief;
            write(stateFile, state);
        } else if (config.enabled && !today.equals(state.day)) {
            // Moved aside and deleted in the background: the server starts without waiting for
            // hundreds of megabytes to go. Deleted in place when the move fails.
            Path moved = moveAside(folder);
            boolean cleared = moved != null || deleteFolder(folder);
            if (moved != null && !moved.equals(folder)) {
                deleteInBackground(moved);
            }
            if (!cleared) {
                CobblemonSafari.LOGGER.error("The Safari map is NOT reset this time: {} could not be removed", folder);
            } else {
                state.day = today;
                state.salt = newSalt();
                state.relief = config.relief;
                write(stateFile, state);
                resetThisRun = true;
                CobblemonSafari.LOGGER.info("Safari map reset for {}: {} cleared, new terrain will be generated ({})",
                        today, folder, config.relief);
            }
        }
        if (state.relief == null) {
            state.relief = IslandRelief.ROLLING;
            write(stateFile, state);
        }
        if (state.relief != config.relief) {
            CobblemonSafari.LOGGER.info("Safari relief {} applies from the next map; this one keeps {}"
                    + " (/safari reset to change it at the next start)", config.relief, state.relief);
        }
        salt = state.salt;
        relief = state.relief;
        mapDay = state.day;
    }

    /**
     * Marks the map as due for a reset, whatever the day: at the next start,
     * and in LIVE mode as soon as the live reset can ({@link LiveReset}).
     * Used by the operator command; the running map is not touched here.
     */
    public static boolean scheduleReset(MinecraftServer server) {
        Path stateFile = server.getWorldPath(LevelResource.ROOT).resolve(STATE_FILE);
        State state = read(stateFile);
        if (state == null) {
            state = new State();
            state.salt = salt;
            state.relief = relief;
        }
        state.day = "";
        if (!write(stateFile, state)) {
            return false;
        }
        mapDay = "";
        return true;
    }

    /**
     * Whether a reset is due: resets on, and the map's day is not today (a
     * new day since it was made, or {@link #scheduleReset}). Reads the clock
     * and the time zone; no file.
     */
    public static boolean isDue() {
        SafariConfig.Reset config = SafariConfigManager.get().reset;
        return config.enabled && !today(config).equals(mapDay);
    }

    /** The Safari's folder in this world; it may not exist yet. */
    static Path folder(MinecraftServer server) {
        return DimensionType.getStorageFolder(SafariDimensions.key(), server.getWorldPath(LevelResource.ROOT));
    }

    /**
     * Starts a new map for a live reset, once the old level is closed and its
     * folder moved away: a new salt and today's day, written to the state
     * file, and the relief of the config. The new level reads them when it is
     * built. Players who logged out inside the old map are moved to the
     * arrival when they come back, as after a reset at start.
     *
     * @return false when the state file could not be written; the old salt is then kept
     */
    static boolean beginNewMap(MinecraftServer server) {
        SafariConfig.Reset config = SafariConfigManager.get().reset;
        State state = new State();
        state.day = today(config);
        state.salt = newSalt();
        state.relief = config.relief;
        if (!write(server.getWorldPath(LevelResource.ROOT).resolve(STATE_FILE), state)) {
            return false;
        }
        salt = state.salt;
        relief = state.relief;
        mapDay = state.day;
        resetThisRun = true;
        RELOCATED.clear();
        return true;
    }

    /** Suffix of an old map's folder while it is deleted in the background; followed by the time. */
    private static final String OLD_FOLDER_SUFFIX = ".old-";

    /**
     * Moves the map's folder aside, so the next level starts empty and the
     * old files can be deleted at leisure. A rename, instant on one disk.
     *
     * @return where it went (the folder itself when there was none), null when it could not be moved
     */
    @Nullable
    static Path moveAside(Path folder) {
        if (!Files.exists(folder)) {
            return folder;
        }
        Path target = folder.resolveSibling(folder.getFileName() + OLD_FOLDER_SUFFIX + System.currentTimeMillis());
        try {
            return Files.move(folder, target);
        } catch (IOException e) {
            CobblemonSafari.LOGGER.error("Could not move {} aside (a file still open?)", folder, e);
            return null;
        }
    }

    /**
     * Deletes, in the background, old maps a stop interrupted before their
     * deletion was over (the deletion thread does not hold the server).
     */
    private static void deleteLeftovers(Path folder) {
        Path parent = folder.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }
        String prefix = folder.getFileName() + OLD_FOLDER_SUFFIX;
        try (Stream<Path> siblings = Files.list(parent)) {
            siblings.filter(path -> path.getFileName().toString().startsWith(prefix) && Files.isDirectory(path))
                    .forEach(SafariMapReset::deleteInBackground);
        } catch (IOException e) {
            CobblemonSafari.LOGGER.warn("Could not look for old Safari maps in {}", parent, e);
        }
    }

    /**
     * Deletes a folder off the server thread: hundreds of megabytes of region
     * files would stall the tick. The folder was renamed first, so nothing
     * of the new map writes into it.
     */
    static void deleteInBackground(Path folder) {
        Thread thread = new Thread(() -> {
            if (deleteFolder(folder)) {
                CobblemonSafari.LOGGER.info("Old Safari map deleted: {}", folder);
            }
        }, "Cobblemon Safari map deletion");
        // A daemon: a server stopping mid-deletion must not wait for it; what is left is deleted at the next start.
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
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
            CobblemonSafari.LOGGER.error("Could not delete {}", folder, e);
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
