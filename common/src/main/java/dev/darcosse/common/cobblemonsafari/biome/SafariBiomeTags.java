package dev.darcosse.common.cobblemonsafari.biome;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/**
 * Biome tags of the Safari: the public contract spawn files are written against.
 *
 * <p>Must match the TAGS table of {@code tools/worldgen/generate_worldgen.py}.
 * Renaming a tag breaks every spawn file that references it, including those
 * shipped by other people's datapacks.
 *
 * <p>Separately, each Safari biome is also added to the matching Cobblemon
 * habitat tags ({@code #cobblemon:is_forest} and friends, see the bridge table
 * of the generator), so Cobblemon's native spawns reach the park. These Safari
 * tags stay the way to target the park ONLY: a spawn on
 * {@code #cobblemon_safari:safari_forest} never appears in an overworld forest.
 */
public final class SafariBiomeTags {

    /** Every Safari biome. */
    public static final TagKey<Biome> IS_SAFARI = tag("is_safari");

    /* One tag per habitat. */
    public static final TagKey<Biome> PLAINS = tag("safari_plains");
    public static final TagKey<Biome> SAVANNA = tag("safari_savanna");
    public static final TagKey<Biome> DESERT = tag("safari_desert");
    public static final TagKey<Biome> BADLANDS = tag("safari_badlands");
    public static final TagKey<Biome> JUNGLE = tag("safari_jungle");
    public static final TagKey<Biome> SWAMP = tag("safari_swamp");
    public static final TagKey<Biome> MEADOW = tag("safari_meadow");
    public static final TagKey<Biome> FOREST = tag("safari_forest");
    public static final TagKey<Biome> DEEP_FOREST = tag("safari_deep_forest");
    public static final TagKey<Biome> TAIGA = tag("safari_taiga");
    public static final TagKey<Biome> SNOW = tag("safari_snow");
    public static final TagKey<Biome> ICE = tag("safari_ice");
    public static final TagKey<Biome> ROCKY = tag("safari_rocky");
    public static final TagKey<Biome> MUSHROOM = tag("safari_mushroom");
    public static final TagKey<Biome> MOUNTAIN = tag("safari_mountain");
    public static final TagKey<Biome> VOLCANIC = tag("safari_volcanic");
    public static final TagKey<Biome> LAKE = tag("safari_lake");
    public static final TagKey<Biome> RIVER = tag("safari_river");
    public static final TagKey<Biome> BEACH = tag("safari_beach");
    public static final TagKey<Biome> CAVE = tag("safari_cave");
    public static final TagKey<Biome> LUSH_CAVE = tag("safari_lush_cave");
    /** The deep cavern under the summit (its giant cavern included). */
    public static final TagKey<Biome> DEEP_CAVE = tag("safari_deep_cave");

    /* Aggregates, for Pokémon that roam several habitats. */
    public static final TagKey<Biome> GRASSLAND = tag("safari_grassland");
    public static final TagKey<Biome> WOODED = tag("safari_wooded");
    public static final TagKey<Biome> WATER = tag("safari_water");
    public static final TagKey<Biome> WETLAND = tag("safari_wetland");
    public static final TagKey<Biome> ARID = tag("safari_arid");
    public static final TagKey<Biome> FROZEN = tag("safari_frozen");
    public static final TagKey<Biome> HIGHLAND = tag("safari_highland");

    private SafariBiomeTags() {
    }

    private static TagKey<Biome> tag(String path) {
        return TagKey.create(Registries.BIOME, CobblemonSafari.id(path));
    }
}
