package dev.darcosse.common.cobblemonsafari.dimension;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import dev.darcosse.common.cobblemonsafari.config.SafariConfigManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Single place that answers "is this the Safari?".
 *
 * <p>The dimension itself is declared in data
 * ({@code data/cobblemon_safari/dimension/safari.json}); no Java registration is
 * needed on either loader. Which dimension counts as the Safari comes from the
 * config so a server can point it at a hand-built map.
 */
public final class SafariDimensions {

    /**
     * Id of the client-side dimension effects, referenced by the "effects" field
     * of {@code dimension_type/safari.json}. Must match DIMENSION_EFFECTS in the
     * worldgen generator.
     */
    public static final ResourceLocation EFFECTS_ID = CobblemonSafari.id("safari");

    private SafariDimensions() {
    }

    /** Key of the dimension currently acting as the Safari. */
    public static ResourceKey<Level> key() {
        return SafariConfigManager.dimension();
    }

    /** Whether the given level is the Safari. */
    public static boolean isSafari(Level level) {
        return level.dimension().equals(key());
    }

    /**
     * The Safari level, or null when the configured dimension does not exist
     * (typo in the config, or the datapack providing it was removed).
     */
    @Nullable
    public static ServerLevel level(MinecraftServer server) {
        return server.getLevel(key());
    }
}
