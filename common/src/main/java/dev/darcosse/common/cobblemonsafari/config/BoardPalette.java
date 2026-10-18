package dev.darcosse.common.cobblemonsafari.config;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.spawn.Rarity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * The wall screens' colours, parsed once from {@link SafariClientConfig.BoardColors}
 * into ARGB ints so the renderer never parses strings per frame.
 *
 * <p>Free of client-only imports: it only reads the client config file.
 */
public record BoardPalette(int bezel, int background, int header, int title, int cell, int text, int muted,
                           int star, int liveOn, int liveOff, int barBackground, int barRotation,
                           int barSessionOk, int barSessionLow, int barSessionCritical,
                           int rarityCommon, int rarityUncommon, int rarityRare, int rarityUltraRare,
                           int scanline, int shimmer, int flash) {

    /** Hex digits of a colour without alpha, which is then opaque. */
    private static final int RGB_DIGITS = 6;
    /** Hex digits of a colour with alpha. */
    private static final int ARGB_DIGITS = 8;
    private static final int OPAQUE = 0xFF000000;
    private static final int HEX = 16;

    /** Parses every colour; call {@link #repair} first so none is invalid. */
    public static BoardPalette from(SafariClientConfig.BoardColors b) {
        return new BoardPalette(parse(b.bezel), parse(b.background), parse(b.header), parse(b.title),
                parse(b.cell), parse(b.text), parse(b.muted), parse(b.star), parse(b.liveOn), parse(b.liveOff),
                parse(b.barBackground), parse(b.barRotation), parse(b.barSessionOk), parse(b.barSessionLow),
                parse(b.barSessionCritical), parse(b.rarityCommon), parse(b.rarityUncommon), parse(b.rarityRare),
                parse(b.rarityUltraRare), parse(b.scanline), parse(b.shimmer), parse(b.flash));
    }

    /** Colour of a rarity tier on the board. */
    public int rarity(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> rarityCommon;
            case UNCOMMON -> rarityUncommon;
            case RARE -> rarityRare;
            case ULTRA_RARE -> rarityUltraRare;
        };
    }

    /**
     * Replaces every unreadable colour by its default, logging each one.
     * Walks the fields by reflection so a colour added to the config section
     * can never be forgotten here.
     *
     * @return true if anything was replaced, i.e. the file must be rewritten
     */
    static boolean repair(SafariClientConfig.BoardColors board, String fileName) {
        SafariClientConfig.BoardColors defaults = new SafariClientConfig.BoardColors();
        boolean corrected = false;
        for (Field field : SafariClientConfig.BoardColors.class.getFields()) {
            if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                String value = (String) field.get(board);
                if (value == null || !isValid(value)) {
                    String fallback = (String) field.get(defaults);
                    CobblemonSafari.LOGGER.warn("Invalid colour board.colors.{}={} in {}, using {}",
                            field.getName(), value, fileName, fallback);
                    field.set(board, fallback);
                    corrected = true;
                }
            } catch (IllegalAccessException e) {
                // Public fields of a public class: cannot happen, but never crash the client over colours.
                CobblemonSafari.LOGGER.error("Could not check colour board.colors.{}", field.getName(), e);
            }
        }
        return corrected;
    }

    /** Whether a string is {@code #RRGGBB} or {@code #AARRGGBB} (the # is optional). */
    private static boolean isValid(String value) {
        String hex = digits(value);
        if (hex.length() != RGB_DIGITS && hex.length() != ARGB_DIGITS) {
            return false;
        }
        for (int i = 0; i < hex.length(); i++) {
            if (Character.digit(hex.charAt(i), HEX) < 0) {
                return false;
            }
        }
        return true;
    }

    /** ARGB value of a valid colour string; six digits mean opaque. */
    private static int parse(String value) {
        String hex = digits(value);
        int parsed = (int) Long.parseLong(hex, HEX);
        return hex.length() == RGB_DIGITS ? OPAQUE | parsed : parsed;
    }

    /** The hex digits of a colour string, without the leading # and surrounding spaces. */
    private static String digits(String value) {
        String trimmed = value.trim().toUpperCase(Locale.ROOT);
        return trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
    }
}
