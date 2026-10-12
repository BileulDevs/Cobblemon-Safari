package dev.darcosse.common.cobblemonsafari.dimension;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.registries.Registries;
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
 * needed on either loader. It is always the mod's own dimension: the
 * terrain generation, the daily map reset, the rest houses and the border
 * are all made for it (a "use another dimension" setting used to exist, and
 * broke all of them).
 */
public final class SafariDimensions {

    /**
     * Id of the client-side dimension effects, referenced by the "effects" field
     * of {@code dimension_type/safari.json}. Must match DIMENSION_EFFECTS in the
     * worldgen generator.
     */
    public static final ResourceLocation EFFECTS_ID = CobblemonSafari.id("safari");

    /** The Safari dimension, declared by data/cobblemon_safari/dimension/safari.json. */
    private static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, CobblemonSafari.id("safari"));

    private SafariDimensions() {
    }

    /** Key of the Safari dimension. */
    public static ResourceKey<Level> key() {
        return KEY;
    }

    /** Whether the given level is the Safari. */
    public static boolean isSafari(Level level) {
        return level.dimension().equals(key());
    }

    /**
     * The Safari level, or null when the dimension does not exist (its data
     * file was removed or broken by a data pack).
     */
    @Nullable
    public static ServerLevel level(MinecraftServer server) {
        return server.getLevel(key());
    }
}
