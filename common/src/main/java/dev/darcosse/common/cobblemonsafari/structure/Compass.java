package dev.darcosse.common.cobblemonsafari.structure;

import dev.darcosse.common.cobblemonsafari.park.ParkGeometry;

import java.util.Locale;

/**
 * The eight directions of the compass, as seen from the centre of the
 * park: how the Safari Guide names the rest houses (the north house, the
 * south-east house...). Names stay the same in every language and on every
 * map, whatever the spot each house was built at.
 *
 * <p>The eight anchors of the houses (ParkGeometry.REST_HOUSES) are 45
 * degrees apart around the centre, and a house moves at most a few dozen
 * blocks from its anchor, so each falls in its own sector.
 *
 * <p>{@link #id()} is part of the datapack contract: it appears in the
 * guide's dialogue function names and translation keys.
 */
public enum Compass {
    NORTH, NORTH_EAST, EAST, SOUTH_EAST, SOUTH, SOUTH_WEST, WEST, NORTH_WEST;

    /** Width of one sector, in degrees. */
    private static final double SECTOR_DEGREES = 360.0D / 8;

    /** Lower-case id, as dialogue functions and translation keys write it ("north_east"). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The sector a column lies in, seen from the centre of the park. */
    public static Compass of(int x, int z) {
        double dx = x - ParkGeometry.CENTER_X;
        double dz = z - ParkGeometry.CENTER_Z;
        // Minecraft's north is -Z and east +X: atan2(east, north) gives the
        // compass bearing, 0 at north, growing clockwise.
        double bearing = Math.toDegrees(Math.atan2(dx, -dz));
        int sector = (int) Math.round(bearing / SECTOR_DEGREES);
        return values()[Math.floorMod(sector, values().length)];
    }
}
