package dev.darcosse.common.cobblemonsafari.teleport;

import com.cobblemon.mod.common.api.dialogue.ActiveDialogue;
import com.cobblemon.mod.common.api.dialogue.DialogueText;
import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import dev.darcosse.common.cobblemonsafari.structure.Compass;
import dev.darcosse.common.cobblemonsafari.structure.RestHouses;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;

import java.util.Locale;

/**
 * A Cobblemon dialogue text naming the rest house of a compass direction by
 * its biome ("Jungle Safari"), for the Safari Guide's options:
 * <pre>{"type": "cobblemon_safari:rest_house_name", "direction": "north_east"}</pre>
 *
 * <p>Why a text type of its own: the houses move from map to map, so their
 * biome is only known on the server, while names must be read in each
 * player's language. Cobblemon builds the dialogue's texts on the server and
 * sends them as components; this one sends translation keys
 * (RestHouseTravel.Label), which every client translates.
 *
 * <p>Built by Cobblemon's dialogue Gson from the JSON, which fills
 * {@link #direction} and ignores the "type" key.
 */
public final class RestHouseNameText implements DialogueText {

    /** The type's id in dialogue files. Renaming it breaks every dialogue using it. */
    public static final String TYPE = CobblemonSafari.MOD_ID + ":rest_house_name";

    /** A {@link Compass#id()}, as written in the dialogue file. */
    public String direction = "";

    /** Gson's constructor. */
    public RestHouseNameText() {
    }

    /**
     * Adds the type to Cobblemon's dialogue text types. Called once from
     * the common initialisation, before any datapack (and so any dialogue)
     * loads.
     */
    public static void register() {
        DialogueText.Companion.getTypes().put(TYPE, RestHouseNameText.class);
    }

    /** The house's name, or the bare direction when there is no house there or no Safari level. */
    @Override
    public MutableComponent invoke(ActiveDialogue dialogue) {
        Compass compass = compass();
        ServerLevel safari = SafariDimensions.level(dialogue.getPlayerEntity().getServer());
        RestHouses.HouseSite site = compass == null ? null : RestHouses.siteIn(compass);
        if (safari == null || site == null) {
            return Component.literal(direction);
        }
        return RestHouseTravel.labelOf(safari, site).component().copy();
    }

    /** The direction this text names, or null when the file has a typo. */
    private Compass compass() {
        try {
            return Compass.valueOf(direction.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
