package dev.darcosse.common.cobblemonsafari.worldgen;

/**
 * How hilly the island is generated ({@code reset.relief} in server.json):
 * how far the land rises above the sea, and how sharp its peaks are.
 *
 * <p>Read by {@link ReliefFunction} inside the terrain's density functions
 * (worldgen/density_function/safari/offset.json and jaggedness.json): the
 * land's height above sea level is multiplied by {@link #height}, the
 * mountain peaks by {@link #jaggedness}. The sea floor and the coastline are
 * left as they are, so the island keeps its size and shape.
 *
 * <p>The names are the config's values: renaming one makes every file that
 * uses it fall back to the default.
 */
public enum IslandRelief {
    /**
     * Low and gentle: the land a few blocks above the sea, no peaks. At 0.35
     * the central plateau sits about ten blocks above the water; lower and
     * whole habitats sink into the lagoon and the rivers flood them.
     */
    FLAT(0.35, 0.0),
    /** The island as it has always been generated (1.0 changes nothing). */
    ROLLING(1.0, 1.0),
    /**
     * High and steep: about 1.6 times the height, peaks almost twice as
     * sharp. Higher and the summit's peaks reach the build limit's thin air
     * while the rest houses' search finds fewer even spots.
     */
    MOUNTAINOUS(1.6, 1.8);

    /** Multiplies the land's height above sea level. */
    final double height;
    /** Multiplies the jaggedness of the peaks. */
    final double jaggedness;

    IslandRelief(double height, double jaggedness) {
        this.height = height;
        this.jaggedness = jaggedness;
    }

    /** Lowest value a parameter takes across every relief, for the density function's bounds. */
    static double min(ReliefFunction.Parameter parameter) {
        double min = Double.MAX_VALUE;
        for (IslandRelief relief : values()) {
            min = Math.min(min, relief.value(parameter));
        }
        return min;
    }

    /** Highest value a parameter takes across every relief. */
    static double max(ReliefFunction.Parameter parameter) {
        double max = -Double.MAX_VALUE;
        for (IslandRelief relief : values()) {
            max = Math.max(max, relief.value(parameter));
        }
        return max;
    }

    /** This relief's multiplier for a parameter. */
    double value(ReliefFunction.Parameter parameter) {
        return switch (parameter) {
            case HEIGHT -> height;
            case JAGGEDNESS -> jaggedness;
        };
    }
}
