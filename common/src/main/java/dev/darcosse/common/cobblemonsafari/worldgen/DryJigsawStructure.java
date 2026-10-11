package dev.darcosse.common.cobblemonsafari.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

import java.util.Optional;

/**
 * A jigsaw structure that refuses wet or steep ground: the Safari Gate in the
 * overworld, the Safari's ruins and towers.
 *
 * <p>Vanilla's jigsaw structure only looks at the biome. A plains or forest
 * biome still has lakes, river banks and flooded hollows, and a structure
 * projected onto the water surface ends up floating in a lake. This type
 * wraps an ordinary jigsaw structure ({@code jigsaw}, any jigsaw JSON), lets
 * it pick its position as usual, then checks the ground the generator will
 * produce around that position, from the noise and before any chunk exists:
 * no water anywhere on the footprint, and limited relief. When the check
 * fails, the structure is simply not generated at that spot.
 *
 * <p>Registered under {@link SafariWorldgen#DRY_JIGSAW}; the outer JSON holds
 * the settings Minecraft uses (biomes, step, terrain adaptation), the inner
 * one only the jigsaw parameters.
 */
public final class DryJigsawStructure extends Structure {

    /**
     * Relief allowed when the JSON does not say, in blocks: the difference
     * between the highest and lowest ground under the footprint. The beard
     * terrain adaptation evens out a few blocks; beyond that a building
     * hangs over a slope or digs into a hill.
     */
    private static final int DEFAULT_MAX_RELIEF = 4;

    /** Bounds accepted from the JSON. A wider footprint costs more height samples (quadratically). */
    private static final int MAX_FOOTPRINT_RADIUS = 48;
    private static final int MAX_RELIEF_LIMIT = 64;

    /**
     * Spacing of the height samples over the footprint, in blocks. A pond
     * narrower than this can slip between two samples; smaller is safer and
     * costs more noise evaluations per candidate.
     */
    private static final int SAMPLE_STEP = 3;

    public static final MapCodec<DryJigsawStructure> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            settingsCodec(instance),
            Structure.DIRECT_CODEC.fieldOf("jigsaw").forGetter(structure -> structure.jigsaw),
            Codec.intRange(1, MAX_FOOTPRINT_RADIUS).fieldOf("footprint_radius")
                    .forGetter(structure -> structure.footprintRadius),
            Codec.intRange(0, MAX_RELIEF_LIMIT).optionalFieldOf("max_relief", DEFAULT_MAX_RELIEF)
                    .forGetter(structure -> structure.maxRelief)
    ).apply(instance, DryJigsawStructure::new));

    /** The wrapped structure, which places the pieces. */
    private final Structure jigsaw;
    /** Half the side of the square checked around the structure's centre, in blocks. */
    private final int footprintRadius;
    private final int maxRelief;

    /** Built by the codec from the data pack. */
    private DryJigsawStructure(StructureSettings settings, Structure jigsaw, int footprintRadius, int maxRelief) {
        super(settings);
        this.jigsaw = jigsaw;
        this.footprintRadius = footprintRadius;
        this.maxRelief = maxRelief;
    }

    /**
     * The wrapped structure's position and pieces, if the ground around that
     * position is dry and even enough. Runs during structure placement, once
     * per candidate chunk: about 50 height evaluations for a radius of 8.
     */
    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        Optional<GenerationStub> stub = jigsaw.findValidGenerationPoint(context);
        if (stub.isEmpty() || !isDryAndEven(context, stub.get().position())) {
            return Optional.empty();
        }
        return stub;
    }

    /** Whether the footprint around a centre has no standing water and at most {@link #maxRelief} of relief. */
    private boolean isDryAndEven(GenerationContext context, BlockPos centre) {
        ChunkGenerator generator = context.chunkGenerator();
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int dx = -footprintRadius; dx <= footprintRadius; dx += SAMPLE_STEP) {
            for (int dz = -footprintRadius; dz <= footprintRadius; dz += SAMPLE_STEP) {
                int x = centre.getX() + dx;
                int z = centre.getZ() + dz;
                // WORLD_SURFACE_WG counts fluids, OCEAN_FLOOR_WG does not:
                // a difference means water standing on the ground there.
                int surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                        context.heightAccessor(), context.randomState());
                int ground = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG,
                        context.heightAccessor(), context.randomState());
                if (surface != ground) {
                    return false;
                }
                low = Math.min(low, ground);
                high = Math.max(high, ground);
                if (high - low > maxRelief) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public StructureType<?> type() {
        return SafariWorldgen.dryJigsawType();
    }
}
