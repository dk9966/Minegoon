package dev.minegoon.world;

import org.bukkit.Location;

/**
 * What a pair of eyes can see. A block counts only when one of its faces is
 * open and the look from the eyes to that opening stays in passable blocks.
 * Looks walk the exact cells a line passes through, so a far look costs about
 * one check per block of distance.
 */
public final class Sight {

    static final int[][] FACES = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private Sight() {
    }

    public record Eye(double x, double y, double z) {
        public static Eye of(Location at) {
            return new Eye(at.getX(), at.getY(), at.getZ());
        }
    }

    /** True when the eyes can see an open face of this block. */
    public static boolean visible(BlockReader world, Eye eye, int x, int y, int z) {
        for (int[] face : FACES) {
            int nx = x + face[0];
            int ny = y + face[1];
            int nz = z + face[2];
            if (!Blocks.passable(world.type(nx, ny, nz))) {
                continue;
            }
            if (clear(world, eye.x(), eye.y(), eye.z(), nx + 0.5, ny + 0.5, nz + 0.5)) {
                return true;
            }
        }
        return false;
    }

    /** True when the eyes can see into this open cell, such as the mouth of a cave. */
    public static boolean seesInto(BlockReader world, Eye eye, int x, int y, int z) {
        return Blocks.passable(world.type(x, y, z)) && clear(world, eye.x(), eye.y(), eye.z(), x + 0.5, y + 0.5, z + 0.5);
    }

    /** An open face, before paying for a line of sight. Buried ore fails here. */
    public static boolean open(BlockReader world, int x, int y, int z) {
        for (int[] face : FACES) {
            if (Blocks.passable(world.type(x + face[0], y + face[1], z + face[2]))) {
                return true;
            }
        }
        return false;
    }

    /** The segment stays in passable blocks. */
    public static boolean clear(BlockReader world, double x0, double y0, double z0, double x1, double y1, double z1) {
        return walk(world, x0, y0, z0, x1, y1, z1, false, 0, 0, 0);
    }

    /**
     * Same walk, but the cell of {@code block} does not count. Reaching to mine
     * aims at the block's middle, which is solid.
     */
    public static boolean clearIgnoring(BlockReader world, double x0, double y0, double z0,
                                        double x1, double y1, double z1, int blockX, int blockY, int blockZ) {
        return walk(world, x0, y0, z0, x1, y1, z1, true, blockX, blockY, blockZ);
    }

    /** Visits every cell the segment crosses after the one it starts in. */
    private static boolean walk(BlockReader world, double x0, double y0, double z0,
                                double x1, double y1, double z1,
                                boolean ignore, int blockX, int blockY, int blockZ) {
        int x = (int) Math.floor(x0);
        int y = (int) Math.floor(y0);
        int z = (int) Math.floor(z0);
        int endX = (int) Math.floor(x1);
        int endY = (int) Math.floor(y1);
        int endZ = (int) Math.floor(z1);
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        int stepX = endX > x ? 1 : endX < x ? -1 : 0;
        int stepY = endY > y ? 1 : endY < y ? -1 : 0;
        int stepZ = endZ > z ? 1 : endZ < z ? -1 : 0;
        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double maxX = stepX == 0 ? Double.POSITIVE_INFINITY : deltaX * (stepX > 0 ? x + 1 - x0 : x0 - x);
        double maxY = stepY == 0 ? Double.POSITIVE_INFINITY : deltaY * (stepY > 0 ? y + 1 - y0 : y0 - y);
        double maxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : deltaZ * (stepZ > 0 ? z + 1 - z0 : z0 - z);
        int budget = Math.abs(endX - x) + Math.abs(endY - y) + Math.abs(endZ - z);
        for (int step = 0; step < budget; step++) {
            if (maxX < maxY && maxX < maxZ) {
                x += stepX;
                maxX += deltaX;
            } else if (maxY < maxZ) {
                y += stepY;
                maxY += deltaY;
            } else {
                z += stepZ;
                maxZ += deltaZ;
            }
            if (ignore && x == blockX && y == blockY && z == blockZ) {
                continue;
            }
            if (!Blocks.passable(world.type(x, y, z))) {
                return false;
            }
        }
        return true;
    }
}
