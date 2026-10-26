package dev.darcosse.common.cobblemonsafari.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.darcosse.common.cobblemonsafari.reset.SafariMapReset;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Density function giving a multiplier of the current map's relief
 * ({@link IslandRelief}), so that the terrain JSON can scale its height and
 * peaks by a server setting.
 *
 * <p>JSON: <pre>{"type": "cobblemon_safari:relief", "parameter": "height"}</pre>
 *
 * <p>Why a function and not a value written in the JSON: datapacks are
 * fixed in the jar, the relief is chosen in server.json. The relief is the
 * one of the map on disk ({@link SafariMapReset#relief()}), set before the
 * levels load and kept until the next reset, so a map is never generated
 * with two reliefs (new chunks would rise as cliffs against the old ones).
 *
 * <p>Cost: a static read per evaluation; it sits inside the terrain's 2D
 * caches (offset and jaggedness are flat_cache), so once per column.
 *
 * @param parameter which multiplier to give
 */
public record ReliefFunction(Parameter parameter) implements DensityFunction.SimpleFunction {

    public static final MapCodec<ReliefFunction> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Parameter.CODEC.fieldOf("parameter").forGetter(ReliefFunction::parameter)
    ).apply(instance, ReliefFunction::new));

    public static final KeyDispatchDataCodec<ReliefFunction> CODEC = KeyDispatchDataCodec.of(MAP_CODEC);

    /** The multiplier of the current map's relief. */
    @Override
    public double compute(FunctionContext context) {
        return SafariMapReset.relief().value(parameter);
    }

    /**
     * Lowest multiplier across every relief, not just the current one:
     * vanilla works out value ranges once, from the bounds, and they must
     * hold whatever the relief.
     */
    @Override
    public double minValue() {
        return IslandRelief.min(parameter);
    }

    /** Highest multiplier across every relief. */
    @Override
    public double maxValue() {
        return IslandRelief.max(parameter);
    }

    /** Codec used when the function is serialised back to data. */
    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    /** Which multiplier. Serialised names are the JSON contract. */
    public enum Parameter implements StringRepresentable {
        /** The land's height above sea level. */
        HEIGHT("height"),
        /** The sharpness of the peaks. */
        JAGGEDNESS("jaggedness");

        public static final Codec<Parameter> CODEC = StringRepresentable.fromEnum(Parameter::values);

        private final String serializedName;

        Parameter(String serializedName) {
            this.serializedName = serializedName;
        }

        /** Name used in JSON. */
        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }
}
