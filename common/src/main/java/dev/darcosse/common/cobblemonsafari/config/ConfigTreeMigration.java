package dev.darcosse.common.cobblemonsafari.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;

import java.util.Map;

/**
 * Moves and renames keys in a config file's JSON tree before Gson binds it
 * to the config classes.
 *
 * <p>Needed for any change of a key's place or name: once bound, a key the
 * classes no longer have is gone, so it can only be carried over while the
 * file is still a tree. Every move works on what the file holds, not on its
 * version: a key that is not there is skipped, so running the same steps on
 * a file already converted, or written by hand half old and half new,
 * changes nothing it should not. Each move is logged, so an admin can see
 * where a setting went.
 *
 * <p>Paths are dotted ({@code "ticket.maxCreditSeconds"}); keys never
 * contain dots in these files.
 */
final class ConfigTreeMigration {

    /** For the default values written into objects a move has to create. */
    private static final Gson GSON = new Gson();

    private final String fileName;
    private final JsonObject root;

    /**
     * @param fileName config file name, for the log
     * @param root     the file's tree, changed in place
     */
    ConfigTreeMigration(String fileName, JsonObject root) {
        this.fileName = fileName;
        this.root = root;
    }

    /** The file's {@code configVersion}, or {@code fallback} when it has none or it is not a number. */
    int version(int fallback) {
        JsonElement version = root.get("configVersion");
        if (version instanceof JsonPrimitive primitive && primitive.isNumber()) {
            return primitive.getAsInt();
        }
        return fallback;
    }

    /** Whether the tree has a value at this path. */
    boolean has(String path) {
        return get(path) != null;
    }

    /** The value at this path, or null. */
    JsonElement get(String path) {
        JsonObject parent = existingParent(path);
        return parent == null ? null : parent.get(leaf(path));
    }

    /**
     * The object at this path, created (with any missing parent) when absent.
     * A non-object found on the way is replaced: the binding would have
     * rejected it anyway, and validation puts the defaults back.
     */
    JsonObject object(String path) {
        JsonObject current = root;
        if (path.isEmpty()) {
            return current;
        }
        for (String key : path.split("\\.")) {
            JsonObject next;
            if (current.get(key) instanceof JsonObject object) {
                next = object;
            } else {
                next = new JsonObject();
                current.add(key, next);
            }
            current = next;
        }
        return current;
    }

    /**
     * The object at this path, created from {@code defaults} (serialised
     * with its field values) when absent, so a move into it does not leave
     * its other keys to the plain constructor of a class whose defaults
     * come from elsewhere (BountyKindSettings).
     */
    JsonObject objectOr(String path, Object defaults) {
        if (!(get(path) instanceof JsonObject)) {
            JsonObject parent = object(parentPath(path));
            parent.add(leaf(path), GSON.toJsonTree(defaults));
        }
        return object(path);
    }

    /**
     * Moves the value at {@code from} to {@code to}, creating the objects on
     * the way. When both exist, the new place wins: the file was already
     * partly converted (by hand, or by a build in between), and its new key
     * is the one the admin edited last.
     */
    void move(String from, String to) {
        JsonObject sourceParent = existingParent(from);
        if (sourceParent == null || !sourceParent.has(leaf(from))) {
            return;
        }
        JsonElement value = sourceParent.remove(leaf(from));
        JsonObject targetParent = object(parentPath(to));
        if (targetParent.has(leaf(to))) {
            CobblemonSafari.LOGGER.warn("{}: both {} and {} are set; keeping {} and dropping {}",
                    fileName, from, to, to, from);
            return;
        }
        targetParent.add(leaf(to), value);
        CobblemonSafari.LOGGER.info("{}: moved {} to {}", fileName, from, to);
    }

    /** Sets a value, logging why; for conversions that change a value rather than its place. */
    void set(String path, JsonElement value, String why) {
        object(parentPath(path)).add(leaf(path), value);
        CobblemonSafari.LOGGER.info("{}: set {} ({})", fileName, path, why);
    }

    /** Removes a value, logging why, when there is one. */
    void remove(String path, String why) {
        JsonObject parent = existingParent(path);
        if (parent != null && parent.remove(leaf(path)) != null) {
            CobblemonSafari.LOGGER.info("{}: removed {} ({})", fileName, path, why);
        }
    }

    /** Removes the object at this path if every key was moved out of it. */
    void removeIfEmpty(String path) {
        JsonObject parent = existingParent(path);
        if (parent != null && parent.get(leaf(path)) instanceof JsonObject object && object.isEmpty()) {
            parent.remove(leaf(path));
        }
    }

    /** Runs {@link #move} on every key of the object at {@code path}, renaming {@code from} to {@code to}. */
    void renameInEach(String path, String from, String to) {
        if (!(get(path) instanceof JsonObject object)) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue() instanceof JsonObject) {
                move(path + "." + entry.getKey() + "." + from, path + "." + entry.getKey() + "." + to);
            }
        }
    }

    /** The object holding the last key of the path, or null when one on the way is missing. */
    private JsonObject existingParent(String path) {
        JsonObject current = root;
        String[] keys = path.split("\\.");
        for (int i = 0; i < keys.length - 1; i++) {
            if (!(current.get(keys[i]) instanceof JsonObject object)) {
                return null;
            }
            current = object;
        }
        return current;
    }

    /** The path without its last key; empty for a top-level key. */
    private static String parentPath(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(0, dot);
    }

    /** The last key of the path. */
    private static String leaf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }
}
