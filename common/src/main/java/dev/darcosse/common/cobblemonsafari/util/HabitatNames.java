package dev.darcosse.common.cobblemonsafari.util;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Short names of the Safari's habitats ("Dunes" rather than "Safari
 * Dunes"), for the places where the reader is already in the Safari: the
 * guide's specials list and travel menu, the wall screens.
 *
 * <p>The biome's own name ({@code biome.cobblemon_safari.<path>}) keeps its
 * "Safari": it is what the F3 screen, Jade and other mods show, where a bare
 * "Dunes" could be mistaken for another mod's biome. The short ones are
 * separate keys, {@code cobblemon_safari.habitat.<path>}. A biome of another
 * namespace (a datapack habitat) has no short key and keeps its own name.
 *
 * <p>Both sides: only builds keys, which each client resolves in its language.
 */
public final class HabitatNames {

    /** Prefix of the short names in the language files. */
    private static final String SHORT_KEY_PREFIX = CobblemonSafari.MOD_ID + ".habitat.";

    private HabitatNames() {
    }

    /** Translation key of a habitat's short name, or of its biome name when the mod has no short one. */
    public static String key(ResourceLocation biome) {
        if (CobblemonSafari.MOD_ID.equals(biome.getNamespace())) {
            return SHORT_KEY_PREFIX + biome.getPath();
        }
        return Util.makeDescriptionId("biome", biome);
    }

    /** A habitat's short name, resolved by the reader's client. */
    public static Component name(ResourceLocation biome) {
        return Component.translatable(key(biome));
    }
}
