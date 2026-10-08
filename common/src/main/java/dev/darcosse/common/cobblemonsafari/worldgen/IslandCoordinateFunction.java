package dev.darcosse.common.cobblemonsafari.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Density function exposing the block position relative to the island centre:
 * distance or offsets normalised by the island radius, or the unit direction
 * from the centre (which drives the sector layout of habitats).
 *
 * <p>Vanilla density functions have no access to X or Z (only
 * {@code y_clamped_gradient} for Y), so a finite, laid-out island cannot be
 * expressed in pure data. This single function fills that gap; everything else
 * (coastline noise, habitat layout) stays in JSON built by the worldgen
 * generator, so tuning the island never requires recompiling.
 *
 * <p>JSON example:
 * <pre>{"type": "cobblemon_safari:island_coordinate", "mode": "radial", "radius": 288}</pre>
 *
 * @param mode    which coordinate to expose
 * @param centerX island centre, X
 * @param centerZ island centre, Z
 * @param radius  distance mapped to 1.0
 * @param limit   absolute cap of the output; keeps min/max finite so vanilla
 *                can still reason about value ranges (it uses them to skip work)
 */
public record IslandCoordinateFunction(Mode mode, double centerX, double centerZ, double radius, double limit)
        implements DensityFunction.SimpleFunction {

    /**
     * Default cap. Four radii is far beyond any playable area; the value only
     * matters for open sea, where every habitat is already resolved.
     */
    private static final double DEFAULT_LIMIT = 4.0;

    /** Smallest radius accepted; below it the function degenerates into a step. */
    private static final double MIN_RADIUS = 1.0;

    /**
     * Softening of the direction modes near the centre, as a fraction of the
     * radius. The direction of the exact centre is undefined; softening makes
     * it fade to 0 over this distance instead of flipping sign between two
     * adjacent columns. The centre is under the summit, where direction does
     * not select the biome anyway. Larger values blur sector borders near the
     * centre; 0 brings back the singularity.
     */
    private static final double DIRECTION_SOFTENING = 0.05;

    public static final MapCodec<IslandCoordinateFunction> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Mode.CODEC.fieldOf("mode").forGetter(IslandCoordinateFunction::mode),
            Codec.DOUBLE.optionalFieldOf("center_x", 0.0).forGetter(IslandCoordinateFunction::centerX),
            Codec.DOUBLE.optionalFieldOf("center_z", 0.0).forGetter(IslandCoordinateFunction::centerZ),
            Codec.doubleRange(MIN_RADIUS, Double.MAX_VALUE).fieldOf("radius").forGetter(IslandCoordinateFunction::radius),
            Codec.doubleRange(0.0, Double.MAX_VALUE).optionalFieldOf("limit", DEFAULT_LIMIT)
                    .forGetter(IslandCoordinateFunction::limit)
    ).apply(instance, IslandCoordinateFunction::new));

    public static final KeyDispatchDataCodec<IslandCoordinateFunction> CODEC = KeyDispatchDataCodec.of(MAP_CODEC);

    /** Distance or signed offset from the centre, in radii, capped to {@code limit}. */
    @Override
    public double compute(FunctionContext context) {
        double dx = context.blockX() - centerX;
        double dz = context.blockZ() - centerZ;
        return switch (mode) {
            case RADIAL -> Math.min(limit, Math.sqrt(dx * dx + dz * dz) / radius);
            case X -> Mth.clamp(dx / radius, -limit, limit);
            case Z -> Mth.clamp(dz / radius, -limit, limit);
            case DIRECTION_X -> dx / softenedDistance(dx, dz);
            case DIRECTION_Z -> dz / softenedDistance(dx, dz);
        };
    }

    private double softenedDistance(double dx, double dz) {
        double softening = radius * DIRECTION_SOFTENING;
        return Math.sqrt(dx * dx + dz * dz + softening * softening);
    }

    /** Lower bound of {@link #compute}. */
    @Override
    public double minValue() {
        return switch (mode) {
            case RADIAL -> 0.0;
            case X, Z -> -limit;
            // A unit-vector component; limit does not apply.
            case DIRECTION_X, DIRECTION_Z -> -1.0;
        };
    }

    /** Upper bound of {@link #compute}. */
    @Override
    public double maxValue() {
        return switch (mode) {
            case RADIAL, X, Z -> limit;
            case DIRECTION_X, DIRECTION_Z -> 1.0;
        };
    }

    /** Codec used when the function is serialised back to data. */
    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    /**
     * Coordinate exposed by the function. Serialised names are the JSON
     * contract: renaming one breaks every file that uses it.
     */
    public enum Mode implements StringRepresentable {
        /** Distance from the centre: 0 at the centre, 1 at {@code radius}. */
        RADIAL("radial"),
        /** Signed east-west offset: -1 at the west edge, +1 at the east edge. */
        X("x"),
        /** Signed north-south offset: -1 at the north edge, +1 at the south edge. */
        Z("z"),
        /** East-west component of the unit direction from the centre: the cosine of the bearing. */
        DIRECTION_X("direction_x"),
        /** North-south component of the unit direction from the centre: the sine of the bearing. */
        DIRECTION_Z("direction_z");

        public static final Codec<Mode> CODEC = StringRepresentable.fromEnum(Mode::values);

        private final String serializedName;

        Mode(String serializedName) {
            this.serializedName = serializedName;
        }

        /** Name used in JSON. */
        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }
}
