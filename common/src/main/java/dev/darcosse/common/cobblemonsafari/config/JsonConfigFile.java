package dev.darcosse.common.cobblemonsafari.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.platform.Platform;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Reads and writes one JSON configuration file in the mod's folder of the
 * loader's config directory ({@code config/cobblemon_safari/}).
 *
 * <p>Shared by the server and client configurations so that both follow the same
 * recovery policy: a missing, empty or malformed file never prevents the game
 * from starting, it falls back to defaults and says so in the log.
 */
final class JsonConfigFile<T> {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** Path of the file under the config directory, with forward slashes (Java accepts them on Windows too). */
    private final String fileName;
    /**
     * Where the file lived before the mod's config folder existed, directly
     * in the config directory. Moved into the folder on first read, so
     * existing servers keep their settings.
     */
    private final String legacyFileName;
    private final Class<T> type;
    private final Supplier<T> defaults;
    /**
     * Rewrites the raw JSON of an old file before Gson maps it. Needed when
     * a key moves or changes type: Gson silently ignores a key it does not
     * know, so a moved setting would be lost instead of migrated.
     */
    private final UnaryOperator<JsonObject> upgrader;

    /** Describes a config file whose keys never move; nothing is read until {@link #read()}. */
    JsonConfigFile(String fileName, String legacyFileName, Class<T> type, Supplier<T> defaults) {
        this(fileName, legacyFileName, type, defaults, UnaryOperator.identity());
    }

    /**
     * Describes a config file with a raw-JSON migration step, applied to
     * every file read (it must leave current files unchanged).
     */
    JsonConfigFile(String fileName, String legacyFileName, Class<T> type, Supplier<T> defaults,
                   UnaryOperator<JsonObject> upgrader) {
        this.fileName = fileName;
        this.legacyFileName = legacyFileName;
        this.type = type;
        this.defaults = defaults;
        this.upgrader = upgrader;
    }

    /**
     * Loads the file, creating it with defaults when absent.
     *
     * <p>The file is NOT rewritten on a parse error: overwriting it would destroy
     * the admin's edits along with the typo that broke them.
     */
    T read() {
        Path path = path();
        moveLegacyFile(path);
        if (!Files.exists(path)) {
            T fresh = defaults.get();
            write(fresh);
            return fresh;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement tree = JsonParser.parseReader(reader);
            if (!tree.isJsonObject()) {
                // An empty file, or one containing literally "null".
                return defaults.get();
            }
            T parsed = GSON.fromJson(upgrader.apply(tree.getAsJsonObject()), type);
            return parsed != null ? parsed : defaults.get();
        } catch (IOException | JsonParseException | IllegalStateException e) {
            // IllegalStateException: a key the upgrader expects holds another JSON type.
            CobblemonSafari.LOGGER.error("Could not read config/{}, using defaults until it is fixed: {}",
                    fileName, e.getMessage());
            return defaults.get();
        }
    }

    /** Writes the given instance, logging instead of throwing on I/O failure. */
    void write(T value) {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(value, writer);
            }
        } catch (IOException e) {
            CobblemonSafari.LOGGER.error("Could not write config/{}: {}", fileName, e.getMessage());
        }
    }

    private Path path() {
        return Platform.get().getConfigDir().resolve(fileName);
    }

    /**
     * Moves the file from its old place into the mod's folder, when only the
     * old one exists. On failure (permissions, a locked file on Windows) the
     * old file is left alone and logged: the defaults are then written to the
     * new place, and the admin can move their file by hand.
     */
    private void moveLegacyFile(Path path) {
        Path legacy = Platform.get().getConfigDir().resolve(legacyFileName);
        if (Files.exists(path) || !Files.exists(legacy)) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Files.move(legacy, path);
            CobblemonSafari.LOGGER.info("Moved config/{} to config/{}", legacyFileName, fileName);
        } catch (IOException e) {
            CobblemonSafari.LOGGER.error("Could not move config/{} to config/{}, move it by hand: {}",
                    legacyFileName, fileName, e.getMessage());
        }
    }
}
