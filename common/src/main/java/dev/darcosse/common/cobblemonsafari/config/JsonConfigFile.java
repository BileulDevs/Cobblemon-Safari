package dev.darcosse.common.cobblemonsafari.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
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

/**
 * Reads and writes one JSON configuration file in the mod's folder of the
 * loader's config directory ({@code config/cobblemon_safari/}).
 *
 * <p>Shared by the server, shop and client configurations so that all follow
 * the same recovery policy: a missing, empty or malformed file never prevents
 * the game from starting, it falls back to defaults and says so in the log.
 */
final class JsonConfigFile<T> {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    /** Path of the file under the config directory, with forward slashes (Java accepts them on Windows too). */
    private final String fileName;
    private final Class<T> type;
    private final Supplier<T> defaults;
    /**
     * Whether the last {@link #read()} could not parse the file. While set,
     * {@link #write} does nothing: the managers validate what read returned
     * (the defaults, then) and would otherwise "fix" and save them over the
     * file, destroying the admin's edits along with their typo.
     */
    private boolean unreadable;

    /** Describes a config file; nothing is read until {@link #read()}. */
    JsonConfigFile(String fileName, Class<T> type, Supplier<T> defaults) {
        this.fileName = fileName;
        this.type = type;
        this.defaults = defaults;
    }

    /**
     * Loads the file, creating it with defaults when absent.
     *
     * <p>The file is NOT rewritten on a parse error, neither here nor by a
     * later {@link #write} of the defaults: overwriting it would destroy the
     * admin's edits along with the typo that broke them.
     */
    T read() {
        Path path = path();
        unreadable = false;
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
            T parsed = GSON.fromJson(tree, type);
            return parsed != null ? parsed : defaults.get();
        } catch (IOException | JsonParseException | IllegalStateException e) {
            // IllegalStateException: a value of the wrong JSON type (an object
            // where a list is expected) must not stop the game from starting.
            CobblemonSafari.LOGGER.error("Could not read config/{}, using defaults until it is fixed: {}",
                    fileName, e.getMessage());
            unreadable = true;
            return defaults.get();
        }
    }

    /**
     * Writes the given instance, logging instead of throwing on I/O failure.
     * Does nothing while the file on disk could not be parsed (see
     * {@link #unreadable}); the next successful read lifts that.
     */
    void write(T value) {
        if (unreadable) {
            return;
        }
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

    /** Absolute path of the file, resolved against the loader's config directory on each call. */
    private Path path() {
        return Platform.get().getConfigDir().resolve(fileName);
    }
}
