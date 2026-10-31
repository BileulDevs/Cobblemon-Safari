package dev.darcosse.common.cobblemonsafari.worldgen;

import dev.darcosse.common.cobblemonsafari.CobblemonSafari;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Keeps Cobblemon's template features out of the Safari's caves.
 *
 * <p>Cobblemon builds part of its ruins, habitats and fossil sites as
 * features, not structures: each one pastes a template where its placement
 * finds room. It adds them to the biomes of a Cobblemon habitat tag
 * ({@code #cobblemon:is_deep_dark}, {@code #cobblemon:is_lush},
 * {@code #cobblemon:is_dripstone}), the same tags the Safari's caves are
 * bridged into for their spawns. The deep cavern, in
 * {@code is_deep_dark}, thus received the deep crypt (deepslate walls
 * around an altar) in one chunk out of three. {@link SafariStructureFilter}
 * could not stop it: a feature never goes through the structure code.
 *
 * <p>Features tagged {@link #NOT_IN_SAFARI} are refused when the Safari's
 * chunk generator decorates a chunk. The tag is data so that a datapack
 * can extend it, like the structure tag of the same name.
 */
public final class SafariFeatureFilter {

    /** Features never placed in the Safari: {@code data/cobblemon_safari/tags/worldgen/placed_feature/not_in_safari.json}. */
    public static final TagKey<PlacedFeature> NOT_IN_SAFARI =
            TagKey.create(Registries.PLACED_FEATURE, CobblemonSafari.id("not_in_safari"));

    private SafariFeatureFilter() {
    }

    /**
     * Whether a feature must not be placed: the generator is the Safari's
     * and the feature is tagged {@link #NOT_IN_SAFARI}.
     *
     * <p>Called for every feature of every decorated chunk (a few dozen per
     * chunk), during world generation only, never per tick. The Safari check
     * comes first because it is cached per biome source; the registry lookup
     * behind the tag check is an identity map read.
     */
    public static boolean refuses(PlacedFeature feature, WorldGenLevel level, ChunkGenerator generator) {
        if (!SafariStructureFilter.isSafari(generator.getBiomeSource())) {
            return false;
        }
        // An inline feature, not in the registry, comes back as a direct
        // holder that belongs to no tag: it is kept.
        Holder<PlacedFeature> holder = level.registryAccess()
                .registryOrThrow(Registries.PLACED_FEATURE)
                .wrapAsHolder(feature);
        return holder.is(NOT_IN_SAFARI);
    }
}
