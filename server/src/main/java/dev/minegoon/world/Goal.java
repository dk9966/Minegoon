package dev.minegoon.world;

/** Where a walk should end. Positions are the feet block of the body. */
public interface Goal {

    double EYE_HEIGHT = 1.62;
    double REACH = 4.5;

    boolean reached(BlockReader world, Pos feet);

    /** Rough cost to finish from here, in the same ticks the pathfinder counts. */
    double estimate(Pos feet);

    /** The point the search aims its world snapshot at. */
    Pos focus();

    static Goal near(Pos target, double range) {
        return new Goal() {
            @Override
            public boolean reached(BlockReader world, Pos feet) {
                return feet.distanceSquared(target) <= range * range;
            }

            @Override
            public double estimate(Pos feet) {
                return Pathfinder.estimate(feet, target);
            }

            @Override
            public Pos focus() {
                return target;
            }
        };
    }

    /** Close enough to hit the block with a clear line of sight, the way a player must. */
    static Goal reach(Pos block) {
        return new Goal() {
            @Override
            public boolean reached(BlockReader world, Pos feet) {
                double ex = feet.x() + 0.5;
                double ey = feet.y() + EYE_HEIGHT;
                double ez = feet.z() + 0.5;
                double dx = block.x() + 0.5 - ex;
                double dy = block.y() + 0.5 - ey;
                double dz = block.z() + 0.5 - ez;
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                return distance <= REACH && clearSight(world, ex, ey, ez, dx, dy, dz, distance);
            }

            private boolean clearSight(BlockReader world, double ex, double ey, double ez,
                                       double dx, double dy, double dz, double distance) {
                for (double t = 0.2; t < distance; t += 0.2) {
                    int x = (int) Math.floor(ex + dx * t / distance);
                    int y = (int) Math.floor(ey + dy * t / distance);
                    int z = (int) Math.floor(ez + dz * t / distance);
                    if ((x != block.x() || y != block.y() || z != block.z()) && !Blocks.passable(world.type(x, y, z))) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public double estimate(Pos feet) {
                return Pathfinder.estimate(feet, block);
            }

            @Override
            public Pos focus() {
                return block;
            }
        };
    }

    /** Anywhere at least {@code distance} blocks from the origin along a compass direction. */
    static Goal away(Pos origin, int dirX, int dirZ, int distance) {
        return new Goal() {
            @Override
            public boolean reached(BlockReader world, Pos feet) {
                return progress(feet) >= distance;
            }

            @Override
            public double estimate(Pos feet) {
                return Math.max(0, distance - progress(feet)) * Pathfinder.WALK;
            }

            @Override
            public Pos focus() {
                return origin.offset(dirX * distance, 0, dirZ * distance);
            }

            private int progress(Pos feet) {
                return (feet.x() - origin.x()) * dirX + (feet.z() - origin.z()) * dirZ;
            }
        };
    }
}
