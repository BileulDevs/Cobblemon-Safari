package dev.darcosse.common.cobblemonsafari.structure;

import dev.darcosse.common.cobblemonsafari.park.ParkGeometry;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * The shape of the chamber dug around an underground rest house: which
 * columns it covers, where its floor and ceiling are, and the box it fits in.
 *
 * <p>The house stands on a flat plaza beside its cavern's passage, door
 * turned toward it. In front of the door the chamber reaches across the
 * whole band the passage may wind through, so the two always meet; there the
 * floor follows the passage's slope (the passage climbs about a block every
 * four) and blends into the plaza beyond the band. Without that slope the
 * plaza would sit up to four blocks above or below the passage where it
 * enters the chamber's sides.
 *
 * <p>So that it reads as a cave and not as a dug box: its outline wobbles
 * (the walls stand {@link #EDGE_WOBBLE} blocks in or out of a rectangle),
 * its floor is rough everywhere but on the plaza ({@link #FLOOR_ROUGHNESS}),
 * and its ceiling is a rough dome. The shapes come from simplex noise seeded
 * by the house's id: the same on every map, which is fine since the cavern
 * around it changes.
 *
 * <p>Pure geometry, no world access: shared by the build
 * ({@link RestHouseBuilder}) and the house queries ({@link RestHouses}),
 * which must agree on it. Cheap: a few noise samples per call.
 */
public final class CaveChamber {

    /**
     * How far the walls stray in from the chamber's box, at most twice this,
     * following noise. Higher gives a more ragged outline; the margins below
     * grow with it so the plaza is never cut.
     */
    private static final int EDGE_WOBBLE = 3;

    /** Wavelength of the wall's wobble, in blocks. Shorter gives a jagged wall, longer a few wide bays. */
    private static final double EDGE_SCALE = 9.0D;

    /** Flat ground kept around the house's walls and steps, in blocks: the plaza. */
    private static final int PLAZA_MARGIN = 3;

    /**
     * Blocks over which the floor's roughness fades in beyond the plaza.
     * Shorter puts a bump right at the plaza's edge (at 4, some edges had
     * two-block steps).
     */
    private static final int ROUGHNESS_FADE = 6;

    /**
     * Height of the floor's bumps and hollows, in blocks each way. Higher
     * gives two-block steps a player cannot walk up (2 gave a dozen per
     * chamber); 0 brings back the regular steps of the bare slope.
     */
    private static final double FLOOR_ROUGHNESS = 1.5D;

    /** Wavelength of the floor's bumps, in blocks. Short wavelengths make one-block pits. */
    private static final double FLOOR_SCALE = 16.0D;

    /** Height of the ceiling's bumps, in blocks each way. */
    private static final double CEILING_ROUGHNESS = 3.0D;

    /** Wavelength of the ceiling's bumps, in blocks. */
    private static final double CEILING_SCALE = 8.0D;

    /**
     * Wavelength of the floor's patches of other rock or of clay, in blocks
     * ({@link #patchAt}). Shorter scatters single blocks.
     */
    private static final double PATCH_SCALE = 6.0D;

    /**
     * Blocks of chamber on each side of the house, along the passage: the
     * plaza plus room for the wall's wobble. Each block more digs about 70
     * columns.
     */
    private static final int SIDE_MARGIN = PLAZA_MARGIN + 2 * EDGE_WOBBLE;

    /** Blocks of chamber behind the house's back wall: the plaza and room for the wall's wobble. */
    private static final int BACK_MARGIN = PLAZA_MARGIN + 2 * EDGE_WOBBLE;

    /**
     * Blocks from the plaza's edge over which the floor goes from level to
     * the passage's slope. Shorter makes a steeper bank along the plaza's
     * sides (at 6, two-block steps); longer leaves less of the passage's
     * band on the slope.
     */
    static final int RAMP_BLEND = 10;

    /**
     * Air above the floor along the chamber's walls. With {@link #DOME_RISE}
     * this gives the height in the middle; lower leaves no room to walk
     * along the walls.
     */
    private static final int WALL_HEADROOM = 8;

    /** Extra height gained from the walls inwards, one block per block, up to this. */
    private static final int DOME_RISE = 6;

    /**
     * Air above the plaza over the house and one block around it: the
     * template's height (11) plus a gap under the rock, so the roof never
     * touches it.
     */
    private static final int HOUSE_HEADROOM = 14;

    /** Seed salts of the four noises, so they do not share their shapes. */
    private static final long EDGE_SALT = 0x51A7E1L;
    private static final long FLOOR_SALT = 0x5F1008L;
    private static final long CEILING_SALT = 0xC311L;
    private static final long PATCH_SALT = 0x9A7C4L;

    /** The house this chamber surrounds. */
    public final ParkGeometry.CaveHouse house;
    /** Where the house's door opens: toward the passage. */
    public final Direction facing;
    /** +1 when the house lies south of its passage (+z), -1 when north. */
    private final int side;
    /** The house's own columns: minX, minZ, maxX, maxZ. */
    private final int[] houseRect;
    /** The chamber's box, inclusive: no column outside it is dug. */
    public final int minX;
    public final int minZ;
    public final int maxX;
    public final int maxZ;
    private final SimplexNoise edgeNoise;
    private final SimplexNoise floorNoise;
    private final SimplexNoise ceilingNoise;
    private final SimplexNoise patchNoise;

    /**
     * The chamber of an underground rest house.
     *
     * @param house     the house, as generated
     * @param houseRect the house's columns, {minX, minZ, maxX, maxZ}, as the builder places them
     */
    CaveChamber(ParkGeometry.CaveHouse house, int[] houseRect) {
        this.house = house;
        this.side = house.z() >= house.passageZ() ? 1 : -1;
        this.facing = side > 0 ? Direction.NORTH : Direction.SOUTH;
        this.houseRect = houseRect;
        this.minX = houseRect[0] - SIDE_MARGIN;
        this.maxX = houseRect[2] + SIDE_MARGIN;
        // Past the far side of the band by the wobble too, so a wall standing in still leaves the band dug.
        int farEdge = house.passageZ() - side * (house.passageReach() + 2 * EDGE_WOBBLE);
        int backEdge = side > 0 ? houseRect[3] + BACK_MARGIN : houseRect[1] - BACK_MARGIN;
        this.minZ = Math.min(farEdge, backEdge);
        this.maxZ = Math.max(farEdge, backEdge);
        long seed = house.id().hashCode();
        this.edgeNoise = new SimplexNoise(new LegacyRandomSource(seed ^ EDGE_SALT));
        this.floorNoise = new SimplexNoise(new LegacyRandomSource(seed ^ FLOOR_SALT));
        this.ceilingNoise = new SimplexNoise(new LegacyRandomSource(seed ^ CEILING_SALT));
        this.patchNoise = new SimplexNoise(new LegacyRandomSource(seed ^ PATCH_SALT));
    }

    /** The door's direction of an underground house: toward its passage. */
    static Direction facingOf(ParkGeometry.CaveHouse house) {
        return house.z() >= house.passageZ() ? Direction.NORTH : Direction.SOUTH;
    }

    /**
     * Whether a column is part of the chamber: inside its box and inside its
     * wobbling wall. The plaza always is (see {@link #SIDE_MARGIN}).
     */
    public boolean contains(int x, int z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) {
            return false;
        }
        int fromBox = Math.min(Math.min(x - minX, maxX - x), Math.min(z - minZ, maxZ - z));
        double inset = EDGE_WOBBLE * (1.0D + edgeNoise.getValue(x / EDGE_SCALE, z / EDGE_SCALE));
        return fromBox >= inset;
    }

    /**
     * Whether a block is part of the chamber's air: above its floor and up
     * to its ceiling, in a column it covers. Used to tell its own blocks from
     * the rock and water around it.
     */
    public boolean holdsAir(int x, int y, int z) {
        return contains(x, z) && y > floorAt(x, z) && y <= ceilingAt(x, z);
    }

    /** Middle column of the chamber, x: where a chunk ticket covering it all is centred. */
    public int centreX() {
        return (minX + maxX) / 2;
    }

    /** Middle column of the chamber, z. */
    public int centreZ() {
        return (minZ + maxZ) / 2;
    }

    /**
     * Height of the floor's top block at a column of the chamber: the plaza
     * (the house's ground layer) near the house, the passage's slope across
     * its band, a blend between them, roughened everywhere but on the plaza.
     */
    public int floorAt(int x, int z) {
        if (onPlaza(x, z)) {
            // Exactly level: the blend toward the slope would tilt its front corners.
            return house.groundY();
        }
        double ramp = house.groundY() + house.passageRise() * (x - house.x());
        // Level at the plaza's edge, the passage's slope RAMP_BLEND blocks
        // away: blending from the plaza rather than from the band leaves no
        // step where they meet.
        int fromPlaza = fromPlaza(x, z);
        double level = Mth.smoothstep(Mth.clamp(1.0D - fromPlaza / (double) RAMP_BLEND, 0.0D, 1.0D));
        double floor = Mth.lerp(level, ramp, house.groundY());
        double rough = Mth.clamp(fromPlaza / (double) ROUGHNESS_FADE, 0.0D, 1.0D);
        floor += rough * FLOOR_ROUGHNESS * floorNoise.getValue(x / FLOOR_SCALE, z / FLOOR_SCALE);
        return (int) Math.round(floor);
    }

    /** Height of the highest air block of the chamber at a column. */
    public int ceilingAt(int x, int z) {
        int fromWall = Math.min(Math.min(x - minX, maxX - x), Math.min(z - minZ, maxZ - z));
        double ceiling = floorAt(x, z) + WALL_HEADROOM + Math.min(Math.max(fromWall, 0), DOME_RISE)
                + CEILING_ROUGHNESS * ceilingNoise.getValue(x / CEILING_SCALE, z / CEILING_SCALE);
        if (underHouse(x, z)) {
            ceiling = Math.max(ceiling, house.groundY() + HOUSE_HEADROOM);
        }
        return (int) Math.round(ceiling);
    }

    /** Whether a column is under the house itself (walls, steps and the ring cleared around them). */
    public boolean underHouse(int x, int z) {
        return x >= houseRect[0] - 1 && x <= houseRect[2] + 1 && z >= houseRect[1] - 1 && z <= houseRect[3] + 1;
    }

    /** Whether a column is on the house or its flat plaza, where nothing is roughened or scattered. */
    public boolean onPlaza(int x, int z) {
        return fromPlaza(x, z) <= 0;
    }

    /**
     * Which patch of floor a column lies in, from -1 to 1: the build turns
     * the high end into one rock and the low end into another, so the floor
     * is not a single block.
     */
    public double patchAt(int x, int z) {
        return patchNoise.getValue(x / PATCH_SCALE, z / PATCH_SCALE);
    }

    /** Highest block the chamber may change: its tallest possible ceiling, for vertical checks. */
    public int topY() {
        return house.groundY() + Math.max(HOUSE_HEADROOM,
                WALL_HEADROOM + DOME_RISE + rampSpan() + (int) Math.ceil(FLOOR_ROUGHNESS + CEILING_ROUGHNESS));
    }

    /** Lowest floor the chamber may have. */
    public int bottomY() {
        return house.groundY() - rampSpan() - (int) Math.ceil(FLOOR_ROUGHNESS);
    }

    /** Blocks from the plaza's edge to a column, 0 or less on the plaza. */
    private int fromPlaza(int x, int z) {
        int dx = Math.max(Math.max(houseRect[0] - PLAZA_MARGIN - x, x - houseRect[2] - PLAZA_MARGIN), 0);
        int dz = Math.max(Math.max(houseRect[1] - PLAZA_MARGIN - z, z - houseRect[3] - PLAZA_MARGIN), 0);
        return Math.max(dx, dz);
    }

    /** How far the slope's ends stray from the plaza's height, in blocks, rounded up. */
    private int rampSpan() {
        return (int) Math.ceil(Math.abs(house.passageRise()) * Math.max(house.x() - minX, maxX - house.x()));
    }
}
