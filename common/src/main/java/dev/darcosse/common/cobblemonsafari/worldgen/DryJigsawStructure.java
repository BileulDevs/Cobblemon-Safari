package dev.darcosse.common.cobblemonsafari.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

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
 * <p>Optionally ({@code structure_clearance}, in chunks), it also keeps away
 * from every other structure of the world, vanilla or modded: a spot is
 * refused when another structure set may start a structure within that many
 * chunks (see {@link #nearOtherStructure}).
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
     * Upper bound of structure_clearance, in chunks. Each chunk of clearance
     * widens the window checked around a candidate; beyond 8 a structure
     * would hardly find room in a modded world full of structures.
     */
    private static final int MAX_CLEARANCE = 8;

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
                    .forGetter(structure -> structure.maxRelief),
            // 0, the default, checks nothing: the Safari's ruins and towers
            // live in their own dimension and do not need it.
            Codec.intRange(0, MAX_CLEARANCE).optionalFieldOf("structure_clearance", 0)
                    .forGetter(structure -> structure.structureClearance)
    ).apply(instance, DryJigsawStructure::new));

    /** The wrapped structure, which places the pieces. */
    private final Structure jigsaw;
    /** Half the side of the square checked around the structure's centre, in blocks. */
    private final int footprintRadius;
    private final int maxRelief;
    /** Chunks kept between this structure's start and any other structure's; 0 for no check. */
    private final int structureClearance;

    /** Built by the codec from the data pack. */
    private DryJigsawStructure(StructureSettings settings, Structure jigsaw, int footprintRadius, int maxRelief,
                               int structureClearance) {
        super(settings);
        this.jigsaw = jigsaw;
        this.footprintRadius = footprintRadius;
        this.maxRelief = maxRelief;
        this.structureClearance = structureClearance;
    }

    /**
     * The wrapped structure's position and pieces, if the ground around that
     * position is dry and even enough. Runs during structure placement, once
     * per candidate chunk: about 50 height evaluations for a radius of 8.
     */
    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        Optional<GenerationStub> stub = jigsaw.findValidGenerationPoint(context);
        if (stub.isEmpty() || !isDryAndEven(context, stub.get().position())
                || (structureClearance > 0 && nearOtherStructure(context))) {
            return Optional.empty();
        }
        return stub;
    }

    /**
     * Whether another structure may start within {@link #structureClearance}
     * chunks of this candidate. Structures are placed independently, set by
     * set, and none knows about the others: vanilla only offers one
     * "exclusion_zone" per set, against one other set. This asks every
     * structure set of the world instead.
     *
     * <p>Answered from each set's placement grid, the same arithmetic
     * Minecraft uses to pick the chunks where a set tries a structure, plus
     * its frequency and the biome there. The other structure may still fail
     * its own checks (terrain, its own exclusion zone), so this is on the
     * safe side: some gates are given up for a structure that would not have
     * been there. Only random_spread sets are checked: concentric rings
     * (strongholds) are underground, and other placement types are rare
     * modded ones whose grid is unknown.
     *
     * <p>Cost, only for a chunk where this structure tries to start: per
     * structure set, one to four grid evaluations and at most as many biome
     * lookups.
     */
    private boolean nearOtherStructure(GenerationContext context) {
        ChunkPos here = context.chunkPos();
        long seed = context.seed();
        for (StructureSet set : context.registryAccess().registryOrThrow(Registries.STRUCTURE_SET)) {
            if (!(set.placement() instanceof RandomSpreadStructurePlacement placement) || contains(set, this)) {
                continue;
            }
            int spacing = placement.spacing();
            // Every chunk of the window belongs to one of these grid cells;
            // each cell holds at most one candidate chunk of the set.
            for (int cellX = Math.floorDiv(here.x - structureClearance, spacing);
                 cellX <= Math.floorDiv(here.x + structureClearance, spacing); cellX++) {
                for (int cellZ = Math.floorDiv(here.z - structureClearance, spacing);
                     cellZ <= Math.floorDiv(here.z + structureClearance, spacing); cellZ++) {
                    ChunkPos candidate = placement.getPotentialStructureChunk(seed, cellX * spacing, cellZ * spacing);
                    if (Math.abs(candidate.x - here.x) <= structureClearance
                            && Math.abs(candidate.z - here.z) <= structureClearance
                            && placement.applyAdditionalChunkRestrictions(candidate.x, candidate.z, seed)
                            && acceptsBiomeAt(context, set, candidate)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Whether a structure set lists the given structure; such a set is this structure's own. */
    private static boolean contains(StructureSet set, Structure structure) {
        for (StructureSet.StructureSelectionEntry entry : set.structures()) {
            if (entry.structure().value() == structure) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any structure of the set may generate in the biome at the
     * middle of a chunk, at the surface, read the way Minecraft checks a
     * structure's biome (Structure.isValidBiome).
     */
    private static boolean acceptsBiomeAt(GenerationContext context, StructureSet set, ChunkPos chunk) {
        int x = chunk.getMiddleBlockX();
        int z = chunk.getMiddleBlockZ();
        int y = context.chunkGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                context.heightAccessor(), context.randomState());
        Holder<Biome> biome = context.biomeSource().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y),
                QuartPos.fromBlock(z), context.randomState().sampler());
        for (StructureSet.StructureSelectionEntry entry : set.structures()) {
            if (entry.structure().value().biomes().contains(biome)) {
                return true;
            }
        }
        return false;
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

    /** The registered type, the same instance as {@link SafariWorldgen#dryJigsawType()}. */
    @Override
    public StructureType<?> type() {
        return SafariWorldgen.dryJigsawType();
    }
}
