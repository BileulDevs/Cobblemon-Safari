package dev.darcosse.common.cobblemonsafari.decor;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Builds the collision shapes of decor blocks from the boxes their models
 * are made of, drawn facing north, so that a shape never drifts from its
 * model when one of them changes.
 */
final class DecorShapes {

    /** Pixels in a block, the unit of the boxes. */
    private static final double FULL = 16.0D;

    private DecorShapes() {
    }

    /**
     * Boxes drawn facing north, turned to face another way, clockwise seen
     * from above like a block state's "y" rotation: north becomes east,
     * east becomes south. Each box is {x0, y0, z0, x1, y1, z1} in pixels.
     */
    static double[][] rotate(double[][] boxes, Direction facing) {
        int quarters = switch (facing) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
        double[][] result = new double[boxes.length][];
        for (int i = 0; i < boxes.length; i++) {
            double[] box = boxes[i].clone();
            for (int q = 0; q < quarters; q++) {
                // A quarter turn clockwise: (x, z) -> (16 - z, x).
                box = new double[]{FULL - box[5], box[1], box[0], FULL - box[2], box[4], box[3]};
            }
            result[i] = box;
        }
        return result;
    }

    /** One shape from boxes in pixels. */
    static VoxelShape union(double[][] boxes) {
        VoxelShape shape = null;
        for (double[] b : boxes) {
            VoxelShape box = Block.box(b[0], b[1], b[2], b[3], b[4], b[5]);
            // The first box seeds the union, as elsewhere in the mod, rather
            // than starting from an empty shape.
            shape = shape == null ? box : Shapes.or(shape, box);
        }
        return shape.optimize();
    }
}
