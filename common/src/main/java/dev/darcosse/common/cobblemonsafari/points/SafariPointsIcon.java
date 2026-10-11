package dev.darcosse.common.cobblemonsafari.points;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

/**
 * The Safari points logo, as a text glyph: a character of the mod's own font
 * ({@code assets/cobblemon_safari/font/icons.json}), so it shows anywhere
 * text does (chat, action bar, screens, the board) with no rendering code.
 *
 * <p>Safe on both sides: it only builds components. The server may put it
 * in messages, since clients always have the mod's resources.
 */
public final class SafariPointsIcon {

    /** Font holding the glyph. Part of the resource format: renaming it breaks icons.json. */
    public static final ResourceLocation FONT = CobblemonSafari.id("icons");

    /** Private-use character mapped to the icon in icons.json. */
    public static final String GLYPH = "";

    private SafariPointsIcon() {
    }

    /**
     * The icon alone. White, because a text colour tints glyphs: in a gold
     * or red price the coin would otherwise change colour.
     */
    public static MutableComponent icon() {
        return Component.literal(GLYPH).withStyle(style -> style.withFont(FONT).withColor(ChatFormatting.WHITE));
    }

    /**
     * An amount followed by the icon, e.g. "150 (coin)". Used as the argument
     * of every translated message that shows points; the number keeps the
     * colour of the surrounding text.
     */
    public static MutableComponent amount(int points) {
        return Component.literal(points + " ").append(icon());
    }
}
