package dev.darcosse.common.cobblemonsafari.util;

import dev.darcosse.common.cobblemonsafari.biome.SafariBiomeTags;
import dev.darcosse.common.cobblemonsafari.dimension.SafariDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Whether someone is in a cave of the Safari: what decides whether the rest
 * house trips and the Escape Rope go underground.
 *
 * <p>Two conditions, both required: the biome at the eyes is one of the
 * Safari's cave biomes ({@code #cobblemon_safari:safari_cave}), and no sky
 * is seen from there. The biome alone would also catch a player standing in
 * a cave's mouth under the open sky; the sky alone would catch one under a
 * tree, an overhang or a rest house's roof.
 */
public final class SafariCaves {

    private SafariCaves() {
    }

    /**
     * Whether an entity stands in a cave of the Safari. Two block reads
     * (biome and sky light); not meant for a per-tick loop over many entities.
     */
    public static boolean isInCave(Entity entity) {
        Level level = entity.level();
        if (!SafariDimensions.isSafari(level)) {
            return false;
        }
        BlockPos eyes = BlockPos.containing(entity.getEyePosition());
        return level.getBiome(eyes).is(SafariBiomeTags.CAVE) && !level.canSeeSky(eyes);
    }
}
