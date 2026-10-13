package dev.darcosse.common.cobblemonsafari.block;

import net.minecraft.util.StringRepresentable;

import java.util.Locale;

/**
 * What a Safari Wall Screen shows, chosen in its settings screen.
 *
 * <p>Stored in the block state, so every player sees the same choice with no
 * payload of its own. The data shown is still each viewer's: a STATS screen
 * shows the record of whoever looks at it, a BOUNTIES screen their progress.
 *
 * <p>The ordinals are also the wire format of the wall screen payloads and
 * the serialized names are the save format: append only, never reorder or
 * rename, or screens already placed change content or reset to the default.
 */
public enum WallScreenContent implements StringRepresentable {
    /** The special Pokémon of the day, like the Safari Board. */
    FEATURED,
    /** The viewer's lifetime Safari record and points. */
    STATS,
    /** Today's missions and the viewer's progress. */
    BOUNTIES;

    /** Name used in the block state. */
    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The content with this ordinal, or null when a packet carries an
     * unknown one (another build of the mod, or a forged packet).
     */
    public static WallScreenContent byOrdinal(int ordinal) {
        WallScreenContent[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : null;
    }

    /**
     * Translation key of the content's name, as shown on the settings
     * screen's buttons, without the mod's namespace prefix.
     */
    public String translationKey() {
        return "wall_screen.content." + getSerializedName();
    }
}
