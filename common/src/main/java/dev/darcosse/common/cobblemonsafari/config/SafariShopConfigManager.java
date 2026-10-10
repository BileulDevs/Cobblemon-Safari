package dev.darcosse.common.cobblemonsafari.config;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the shop file: loading, validation, rewriting fixed values. Loaded
 * together with the main config, so {@code /safari reload} covers both.
 */
public final class SafariShopConfigManager {

    private static final String FILE_NAME = CobblemonSafari.MOD_ID + "_shop.json";

    /** Version written by this build. */
    public static final int CURRENT_VERSION = 1;

    /**
     * Largest stack an item entry may give. Beyond a full stack the surplus is
     * dropped at the player's feet, which the shop is not meant to do.
     */
    private static final int MAX_COUNT = 64;

    private static final JsonConfigFile<SafariShopConfig> FILE =
            new JsonConfigFile<>(FILE_NAME, SafariShopConfig.class, SafariShopConfig::new);

    private static SafariShopConfig config = new SafariShopConfig();

    private SafariShopConfigManager() {
    }

    /** Reads the file, drops broken entries, and rewrites it if anything was fixed. */
    public static void load() {
        SafariShopConfig loaded = FILE.read();
        if (validate(loaded)) {
            FILE.write(loaded);
        }
        config = loaded;
    }

    /** Current shop. Treat as read-only. */
    public static SafariShopConfig get() {
        return config;
    }

    /**
     * Removes entries that could never be sold and clamps the rest.
     *
     * <p>Item ids are only checked for syntax here: the config loads during
     * mod init, before other mods have registered their items. An id that
     * still names nothing when the shop opens is shown as unavailable
     * ({@link #itemExists}) rather than deleted from the file.
     *
     * @return true if the file must be rewritten
     */
    private static boolean validate(SafariShopConfig c) {
        boolean corrected = false;
        if (c.configVersion != CURRENT_VERSION) {
            c.configVersion = CURRENT_VERSION;
            corrected = true;
        }
        if (c.entries == null) {
            c.entries = new SafariShopConfig().entries;
            return true;
        }
        List<SafariShopConfig.Entry> kept = new ArrayList<>();
        for (SafariShopConfig.Entry entry : c.entries) {
            if (entry == null) {
                corrected = true;
                continue;
            }
            if (entry.price < 0) {
                CobblemonSafari.LOGGER.warn("Shop entry with a negative price set to 0: {} {}",
                        entry.item, entry.pokemon);
                entry.price = 0;
                corrected = true;
            }
            if (entry.isPokemon()) {
                if (entry.pokemon == null || entry.pokemon.isBlank()) {
                    CobblemonSafari.LOGGER.warn("Removing a shop Pokémon entry with no properties");
                    corrected = true;
                    continue;
                }
            } else {
                if (!SafariShopConfig.Entry.TYPE_ITEM.equals(entry.type)) {
                    CobblemonSafari.LOGGER.warn("Unknown shop entry type '{}', treated as an item", entry.type);
                    entry.type = SafariShopConfig.Entry.TYPE_ITEM;
                    corrected = true;
                }
                if (entry.item == null || ResourceLocation.tryParse(entry.item) == null) {
                    CobblemonSafari.LOGGER.warn("Removing a shop item entry with an invalid id '{}'", entry.item);
                    corrected = true;
                    continue;
                }
                if (entry.count < 1 || entry.count > MAX_COUNT) {
                    entry.count = Math.max(1, Math.min(MAX_COUNT, entry.count));
                    corrected = true;
                }
            }
            kept.add(entry);
        }
        c.entries = kept;
        return corrected;
    }

    /** Whether an item entry names an item that exists right now. */
    public static boolean itemExists(SafariShopConfig.Entry entry) {
        ResourceLocation id = ResourceLocation.tryParse(entry.item);
        return id != null && BuiltInRegistries.ITEM.containsKey(id);
    }
}
