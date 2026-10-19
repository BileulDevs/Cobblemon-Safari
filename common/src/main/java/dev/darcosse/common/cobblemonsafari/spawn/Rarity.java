package dev.darcosse.common.cobblemonsafari.spawn;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * How rare a Pokémon is in the Safari, mirroring Cobblemon's spawn buckets so
 * that "rare" means the same thing here as in the rest of the game.
 *
 * <p>Declared from the most to the least common: comparisons rely on the order.
 */
public enum Rarity {
    COMMON("common", ChatFormatting.WHITE),
    UNCOMMON("uncommon", ChatFormatting.GREEN),
    RARE("rare", ChatFormatting.AQUA),
    ULTRA_RARE("ultra-rare", ChatFormatting.LIGHT_PURPLE);

    /** Cobblemon spawn bucket name this rarity stands for. */
    public final String bucket;
    /** Colour of the rarity name on the wall screens. */
    public final ChatFormatting color;

    Rarity(String bucket, ChatFormatting color) {
        this.bucket = bucket;
        this.color = color;
    }

    /**
     * Rarity of a Cobblemon spawn bucket. Buckets added by datapacks are
     * unknown here and count as rare: guessing "common" would let an exotic
     * custom bucket crowd out the real specials.
     */
    public static Rarity fromBucket(String bucketName) {
        for (Rarity rarity : values()) {
            if (rarity.bucket.equals(bucketName)) {
                return rarity;
            }
        }
        return RARE;
    }

    /** Player-facing name, coloured. */
    public Component displayName() {
        return Component.translatable(CobblemonSafari.MOD_ID + ".rarity." + name().toLowerCase(java.util.Locale.ROOT))
                .withStyle(color);
    }
}
