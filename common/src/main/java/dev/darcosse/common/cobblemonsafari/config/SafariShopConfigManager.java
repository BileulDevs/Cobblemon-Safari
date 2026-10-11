package dev.darcosse.common.cobblemonsafari.config;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns the shop file: loading, validation, rewriting fixed values. Loaded
 * together with the main config, so {@code /safari reload} covers both.
 */
public final class SafariShopConfigManager {

    private static final String FILE_NAME = CobblemonSafari.MOD_ID + "_shop.json";

    /** Version written by this build. */
    public static final int CURRENT_VERSION = 2;

    /**
     * Largest stack an item entry may give. Beyond a full stack the surplus is
     * dropped at the player's feet, which the shop is not meant to do.
     */
    private static final int MAX_COUNT = 64;

    /** Item ids ending with this go to the Poké Balls tab by default. */
    private static final String BALL_SUFFIX = "_ball";

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
        if (c.tabs == null || c.tabs.isEmpty()) {
            // Version 1 files have no tabs: they get the default ones.
            c.tabs = new SafariShopConfig().tabs;
            corrected = true;
        }
        corrected |= validateTabs(c);
        if (c.entries == null) {
            c.entries = new SafariShopConfig().entries;
            assignTabs(c);
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
                // Item fields mean nothing here: cleared so the file does not show them.
                if (entry.item != null || entry.count != null) {
                    entry.item = null;
                    entry.count = null;
                    corrected = true;
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
                if (entry.count == null) {
                    entry.count = 1;
                    corrected = true;
                } else if (entry.count < 1 || entry.count > MAX_COUNT) {
                    entry.count = Math.max(1, Math.min(MAX_COUNT, entry.count));
                    corrected = true;
                }
                // The Pokémon field means nothing here: cleared so the file does not show it.
                if (entry.pokemon != null) {
                    entry.pokemon = null;
                    corrected = true;
                }
            }
            kept.add(entry);
        }
        c.entries = kept;
        corrected |= assignTabs(c);
        return corrected;
    }

    /**
     * Drops tabs without an id and duplicate ids (the first wins); fixes null
     * names and icons.
     *
     * @return true if anything changed
     */
    private static boolean validateTabs(SafariShopConfig c) {
        boolean corrected = false;
        Set<String> seen = new HashSet<>();
        List<SafariShopConfig.Tab> kept = new ArrayList<>();
        for (SafariShopConfig.Tab tab : c.tabs) {
            if (tab == null || tab.id == null || tab.id.isBlank() || !seen.add(tab.id)) {
                CobblemonSafari.LOGGER.warn("Removing a shop tab with no id or a duplicate id: {}",
                        tab == null ? null : tab.id);
                corrected = true;
                continue;
            }
            if (tab.name == null) {
                tab.name = "";
                corrected = true;
            }
            if (tab.icon == null) {
                tab.icon = "";
                corrected = true;
            }
            kept.add(tab);
        }
        if (kept.isEmpty()) {
            kept = new SafariShopConfig().tabs;
            corrected = true;
        }
        c.tabs = kept;
        return corrected;
    }

    /**
     * Gives every entry a tab that exists: its own if valid, otherwise the
     * one its type suggests, otherwise the first tab. Written back to the
     * file so the admin sees where each entry went.
     *
     * @return true if any entry changed tab
     */
    private static boolean assignTabs(SafariShopConfig c) {
        Set<String> ids = new HashSet<>();
        for (SafariShopConfig.Tab tab : c.tabs) {
            ids.add(tab.id);
        }
        boolean corrected = false;
        for (SafariShopConfig.Entry entry : c.entries) {
            if (entry.tab != null && ids.contains(entry.tab)) {
                continue;
            }
            if (entry.tab != null && !entry.tab.isEmpty()) {
                CobblemonSafari.LOGGER.warn("Shop entry {}{} names an unknown tab '{}'; moved",
                        entry.item, entry.pokemon, entry.tab);
            }
            String suggested = suggestedTab(entry);
            entry.tab = ids.contains(suggested) ? suggested : c.tabs.get(0).id;
            corrected = true;
        }
        return corrected;
    }

    /** The built-in tab matching an entry's type. */
    private static String suggestedTab(SafariShopConfig.Entry entry) {
        if (entry.isPokemon()) {
            return SafariShopConfig.Tab.POKEMON;
        }
        ResourceLocation id = ResourceLocation.tryParse(entry.item);
        return id != null && id.getPath().endsWith(BALL_SUFFIX) ? SafariShopConfig.Tab.BALLS : SafariShopConfig.Tab.ITEMS;
    }

    /** Index of a tab id in the tab list, or 0 when missing (cannot happen after validation). */
    public static int tabIndex(String id) {
        List<SafariShopConfig.Tab> tabs = config.tabs;
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).id.equals(id)) {
                return i;
            }
        }
        return 0;
    }

    /** Whether an item entry names an item that exists right now. */
    public static boolean itemExists(SafariShopConfig.Entry entry) {
        ResourceLocation id = ResourceLocation.tryParse(entry.item);
        return id != null && BuiltInRegistries.ITEM.containsKey(id);
    }
}
