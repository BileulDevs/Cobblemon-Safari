package dev.darcosse.common.cobblemonsafari.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
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
 * Reads and writes one JSON configuration file in the loader's config directory.
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

    private final String fileName;
    private final Class<T> type;
    private final Supplier<T> defaults;

    /** Describes a config file; nothing is read until {@link #read()}. */
    JsonConfigFile(String fileName, Class<T> type, Supplier<T> defaults) {
        this.fileName = fileName;
        this.type = type;
        this.defaults = defaults;
    }

    /**
     * Loads the file, creating it with defaults when absent.
     *
     * <p>The file is NOT rewritten on a parse error: overwriting it would destroy
     * the admin's edits along with the typo that broke them.
     */
    T read() {
        Path path = path();
        if (!Files.exists(path)) {
            T fresh = defaults.get();
            write(fresh);
            return fresh;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            T parsed = GSON.fromJson(reader, type);
            // An empty file, or one containing literally "null", parses to null.
            return parsed != null ? parsed : defaults.get();
        } catch (IOException | JsonParseException e) {
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
}
