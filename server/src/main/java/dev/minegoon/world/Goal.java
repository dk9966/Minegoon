package dev.minegoon.world;

import dev.minegoon.world.path.Costs;

/** Where a walk should end. Positions are the feet block of the body. */
public interface Goal {

    double EYE_HEIGHT = 1.62;
    double REACH = 4.5;

    boolean reached(BlockReader world, Pos feet);

    /** Rough cost to finish from here, in the same ticks the route search counts. */
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
                return Costs.estimate(feet, target);
            }

            @Override
            public Pos focus() {
                return target;
            }
        };
    }

    /** Within {@code range} of a column at any height. {@code surface} is where the search looks. */
    static Goal column(Pos surface, double range) {
        return new Goal() {
            @Override
            public boolean reached(BlockReader world, Pos feet) {
                double dx = feet.x() - surface.x();
                double dz = feet.z() - surface.z();
                return dx * dx + dz * dz <= range * range;
            }

            @Override
            public double estimate(Pos feet) {
                return Costs.estimate(feet, new Pos(surface.x(), feet.y(), surface.z()));
            }

            @Override
            public Pos focus() {
                return surface;
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
                double tx = block.x() + 0.5;
                double ty = block.y() + 0.5;
                double tz = block.z() + 0.5;
                double distance = Math.sqrt((tx - ex) * (tx - ex) + (ty - ey) * (ty - ey) + (tz - ez) * (tz - ez));
                return distance <= REACH
                    && Sight.clearIgnoring(world, ex, ey, ez, tx, ty, tz, block.x(), block.y(), block.z());
            }

            @Override
            public double estimate(Pos feet) {
                return Costs.estimate(feet, block);
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
                return Math.max(0, distance - progress(feet)) * Costs.SPRINT;
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
