package dev.darcosse.common.cobblemonsafari.worldgen;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.Set;

/**
 * Keeps the features that are buildings out of the Safari, but those it
 * allows: the feature side of {@link SafariStructureFilter}.
 *
 * <p>Cobblemon builds part of its ruins, habitats and fossil sites as
 * features rather than structures: each one pastes a template where its
 * placement finds room (feature type {@code cobblemon:structure}), added in
 * code to the biomes of Cobblemon's habitat tags, which the Safari's biomes
 * are bridged into for their spawns. Vanilla has two of the same kind in the
 * Safari's biomes: the desert well and the buried fossils. A feature never
 * goes through the structure code, so they are refused here, by the type of
 * their configured feature ({@link #BUILDING_FEATURE_TYPES}), unless the
 * placed feature is tagged {@link #ALLOWED_IN_SAFARI}. Terrain and plants
 * (trees, flowers, geodes, ice spikes, huge mushrooms) are not buildings and
 * are left alone.
 */
public final class SafariFeatureFilter {

    /**
     * The only building features placed in the Safari:
     * {@code data/cobblemon_safari/tags/worldgen/placed_feature/allowed_in_safari.json}.
     */
    public static final TagKey<PlacedFeature> ALLOWED_IN_SAFARI =
            TagKey.create(Registries.PLACED_FEATURE, CobblemonSafari.id("allowed_in_safari"));

    /**
     * Feature types that build something: Cobblemon's template features, and
     * vanilla's desert well and fossil. A type added here is refused in the
     * Safari unless allowed; one removed comes back everywhere it is placed.
     */
    private static final Set<ResourceLocation> BUILDING_FEATURE_TYPES = Set.of(
            ResourceLocation.fromNamespaceAndPath("cobblemon", "structure"),
            ResourceLocation.withDefaultNamespace("desert_well"),
            ResourceLocation.withDefaultNamespace("fossil"));

    private SafariFeatureFilter() {
    }

    /**
     * Whether a feature must not be placed: the generator is the Safari's,
     * the feature builds something, and it is not tagged
     * {@link #ALLOWED_IN_SAFARI}.
     *
     * <p>Called for every feature of every decorated chunk (a few dozen per
     * chunk), during world generation only, never per tick. The Safari check
     * comes first because it is cached per biome source; the type and tag
     * checks behind it are identity map reads.
     */
    public static boolean refuses(PlacedFeature feature, WorldGenLevel level, ChunkGenerator generator) {
        if (!SafariStructureFilter.isSafari(generator.getBiomeSource())) {
            return false;
        }
        Feature<?> type = feature.feature().value().feature();
        ResourceLocation typeId = BuiltInRegistries.FEATURE.getKey(type);
        if (typeId == null || !BUILDING_FEATURE_TYPES.contains(typeId)) {
            return false;
        }
        // An inline feature, not in the registry, comes back as a direct
        // holder that belongs to no tag: refused like any building.
        Holder<PlacedFeature> holder = level.registryAccess()
                .registryOrThrow(Registries.PLACED_FEATURE)
                .wrapAsHolder(feature);
        return !holder.is(ALLOWED_IN_SAFARI);
    }
}
