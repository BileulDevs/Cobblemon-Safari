package dev.darcosse.common.cobblemonsafari.mixin;

import dev.darcosse.common.cobblemonsafari.worldgen.SafariStructureFilter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuses, in the Safari, each start of a structure not tagged
 * {@code cobblemon_safari:allowed_in_safari} that shares its structure set
 * with allowed ones ({@link SafariStructureFilter#refuses(
 * net.minecraft.core.Holder, BiomeSource)}). Sets with no allowed structure
 * never get this far: ChunkGeneratorStructureStateMixin drops them.
 *
 * <p>{@code tryGenerateStructure} is where the generator tries one
 * structure of a set in a chunk; answering "no" there is what it does
 * itself for a structure that does not fit, so it goes on with the set's
 * other structures. Other dimensions are untouched.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorStructureMixin {

    @Shadow
    public abstract BiomeSource getBiomeSource();

    /** Refuses a tagged structure's start in the Safari, before vanilla tries to place it. */
    @Inject(method = "tryGenerateStructure", at = @At("HEAD"), cancellable = true)
    private void cobblemonSafari$refuseInSafari(StructureSet.StructureSelectionEntry entry,
                                               StructureManager structureManager, RegistryAccess registryAccess,
                                               RandomState random, StructureTemplateManager templates, long seed,
                                               ChunkAccess chunk, ChunkPos chunkPos, SectionPos sectionPos,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (SafariStructureFilter.refuses(entry.structure(), getBiomeSource())) {
            cir.setReturnValue(false);
        }
    }
}
