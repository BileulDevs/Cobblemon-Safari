package dev.darcosse.common.cobblemonsafari.spawn;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * When and where one natural spawn of a species may happen, as Cobblemon's
 * spawn file says: the Safari biomes it accepts, land or water, and its time
 * of day, light and sky conditions. The special spawner checks these so a
 * special keeps the habits of its species (a Gastly only in the dark, a
 * nocturnal species only at night).
 *
 * <p>Specials only appear at the surface, so an underground spawn (one that
 * needs to be out of the sky) can never be met as written. {@link #of}
 * reads such a spawn as "at night" instead: the darkness it asks for, at the
 * surface. Without this a cave dweller drawn as a special would never show up.
 *
 * <p>Only the conditions that depend on the spot and the hour are kept;
 * everything else in the spawn file (weather, moon phase, structures, bait)
 * is ignored, as it always was for specials. A null bound means "no limit".
 *
 * @param biomes      Safari biomes where this spawn applies
 * @param water       whether it is a water spawn (submerged, surface, fishing)
 * @param timeRanges  day-time windows in ticks (0 to 23999), each {start, end} inclusive; empty for any time
 * @param minLight    lowest combined light where the Pokémon stands
 * @param maxLight    highest combined light
 * @param minSkyLight lowest raw sky light
 * @param maxSkyLight highest raw sky light
 * @param canSeeSky   whether the spot must (true) or must not (false) see the sky
 */
public record SpawnWindow(Set<ResourceKey<Biome>> biomes, boolean water, List<int[]> timeRanges,
                          @Nullable Integer minLight, @Nullable Integer maxLight,
                          @Nullable Integer minSkyLight, @Nullable Integer maxSkyLight,
                          @Nullable Boolean canSeeSky) {

    /** Ticks in a Minecraft day; Cobblemon compares time ranges against the day time modulo this. */
    private static final long TICKS_PER_DAY = 24000L;

    /**
     * Cobblemon's named "night" range (TimeRange "night": 12542 to 23459),
     * given to underground spawns read at the surface. Kept equal to
     * Cobblemon's so a cave dweller comes out exactly when nocturnal species do.
     */
    private static final int[] NIGHT = {12542, 23459};

    /**
     * Highest sky light an underground spawn may ask for: below this, the
     * spawn wants to be out of the sky. 8 is the line Cobblemon's own files
     * use for "outdoors" (minSkyLight 8). Raising it would turn more
     * shaded-forest spawns into night-only ones; lowering it would keep more
     * cave spawns impossible at the surface.
     */
    private static final int UNDERGROUND_MAX_SKY_LIGHT = 8;

    /**
     * Builds the window of one spawn condition, read for a surface spot.
     * An underground condition (must not see the sky, or sky light kept under
     * {@link #UNDERGROUND_MAX_SKY_LIGHT}) loses its sky and light bounds,
     * which the surface never meets, and becomes night-only unless it already
     * names its hours. Its lower light bound is kept.
     */
    public static SpawnWindow of(Set<ResourceKey<Biome>> biomes, boolean water, List<int[]> timeRanges,
                                 @Nullable Integer minLight, @Nullable Integer maxLight,
                                 @Nullable Integer minSkyLight, @Nullable Integer maxSkyLight,
                                 @Nullable Boolean canSeeSky) {
        boolean underground = Boolean.FALSE.equals(canSeeSky)
                || (maxSkyLight != null && maxSkyLight < UNDERGROUND_MAX_SKY_LIGHT);
        if (!underground) {
            return new SpawnWindow(biomes, water, timeRanges, minLight, maxLight, minSkyLight, maxSkyLight, canSeeSky);
        }
        List<int[]> hours = timeRanges.isEmpty() ? List.<int[]>of(NIGHT) : timeRanges;
        return new SpawnWindow(biomes, water, hours, minLight, null, null, null, null);
    }

    /** Whether this window applies to a biome and kind of spot. */
    public boolean covers(Holder<Biome> biome, boolean waterSpot) {
        if (water != waterSpot) {
            return false;
        }
        for (ResourceKey<Biome> key : biomes) {
            if (biome.is(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the spot and the current hour meet this window. Measured the
     * way Cobblemon's spawner measures them, at the block the Pokémon stands
     * in: combined light as {@code getMaxLocalRawBrightness} (darker at
     * night), raw sky light, and sky visibility from below water.
     *
     * <p>Cost: two light lookups and one heightmap read, once per spawn
     * attempt that reached this species.
     */
    public boolean allows(ServerLevel level, BlockPos standing) {
        if (!timeRanges.isEmpty()) {
            int time = (int) (level.getDayTime() % TICKS_PER_DAY);
            boolean inside = false;
            for (int[] range : timeRanges) {
                if (time >= range[0] && time <= range[1]) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                return false;
            }
        }
        if (minLight != null || maxLight != null) {
            int light = level.getMaxLocalRawBrightness(standing);
            if ((minLight != null && light < minLight) || (maxLight != null && light > maxLight)) {
                return false;
            }
        }
        if (minSkyLight != null || maxSkyLight != null) {
            int skyLight = level.getBrightness(LightLayer.SKY, standing);
            if ((minSkyLight != null && skyLight < minSkyLight) || (maxSkyLight != null && skyLight > maxSkyLight)) {
                return false;
            }
        }
        return canSeeSky == null || canSeeSky == level.canSeeSkyFromBelowWater(standing);
    }
}
