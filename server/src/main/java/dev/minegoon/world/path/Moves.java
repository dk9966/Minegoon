package dev.minegoon.world.path;

import static dev.minegoon.world.Traits.CLIMBABLE;
import static dev.minegoon.world.Traits.HARMFUL;
import static dev.minegoon.world.Traits.LOOSE;
import static dev.minegoon.world.Traits.PASSABLE;
import static dev.minegoon.world.Traits.REPLACEABLE;
import static dev.minegoon.world.Traits.STANDABLE;
import static dev.minegoon.world.Traits.UNKNOWN;
import static dev.minegoon.world.Traits.WATER;
import static dev.minegoon.world.Traits.has;

import dev.minegoon.world.Clearance;
import dev.minegoon.world.Terrain;

/**
 * Every move the body can make from one feet position, and what each costs.
 * Two rules hold for all of them: the head never ends up under water, and the
 * body never goes into lava, fire, or anything else that hurts.
 */
final class Moves {

    static final int SAFE_DROP = 3;
    static final int BUCKET_DROP = 20;
    private static final int WATER_DROP = 40;
    private static final int LONGEST_JUMP = 4;
    private static final double INF = Clearance.IMPOSSIBLE;
    private static final int[][] STRAIGHT = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** Takes each move found; {@code placed} says the move puts a block under the feet it ends at. */
    @FunctionalInterface
    interface Sink {
        void offer(Move move, int x, int y, int z, double cost, boolean placed);
    }

    private final Terrain terrain;
    private final Kit kit;
    private final double run;

    Moves(Terrain terrain, Kit kit) {
        this.terrain = terrain;
        this.kit = kit;
        this.run = kit.sprint() ? Costs.SPRINT : Costs.WALK;
    }

    /** {@code onPlaced}: the block under these feet is one the route itself put down. */
    void expand(int x, int y, int z, boolean onPlaced, Sink sink) {
        int here = traits(x, y, z);
        boolean grounded = onPlaced || standable(x, y - 1, z);
        boolean swimming = has(here, WATER) && !grounded;
        boolean climbing = has(here, CLIMBABLE);
        if (!grounded && !swimming && !climbing) {
            return;
        }
        boolean wading = has(here, WATER);
        for (int[] d : STRAIGHT) {
            traverse(x, y, z, d[0], d[1], grounded, wading, sink);
            ascend(x, y, z, d[0], d[1], grounded, wading, sink);
            if (grounded) {
                descend(x, y, z, d[0], d[1], sink);
                if (!wading) {
                    parkour(x, y, z, d[0], d[1], sink);
                }
            }
        }
        if (grounded && !wading) {
            for (int[] d : DIAGONALS) {
                diagonal(x, y, z, d[0], d[1], sink);
            }
        }
        up(x, y, z, here, grounded, sink);
        if (!onPlaced) {
            down(x, y, z, here, grounded, sink);
        }
    }

    /** Walks, wades, or swims one block; bridges a missing floor with a placed block. */
    private void traverse(int x, int y, int z, int dx, int dz, boolean grounded, boolean wading, Sink sink) {
        int nx = x + dx;
        int nz = z + dz;
        double clear = open(nx, y, nz) + head(nx, y + 1, nz);
        if (clear == INF) {
            return;
        }
        int feet = traits(nx, y, nz);
        boolean wet = wading || has(feet, WATER);
        // A vine with nothing solid under it is not a floor. Walking into one climbs, and the step never finishes.
        if (standable(nx, y - 1, nz)) {
            double move = wet ? Costs.WATER : clear > 0 ? Costs.WALK : run;
            sink.offer(Move.TRAVERSE, nx, y, nz, move + clear, false);
        } else if (has(feet, WATER)) {
            sink.offer(Move.SWIM, nx, y, nz, Costs.WATER + clear, false);
        } else if (grounded && placeable(nx, y - 1, nz)) {
            sink.offer(Move.TRAVERSE, nx, y, nz, Costs.WALK + Costs.PLACE + clear, true);
        }
    }

    /** Jumps onto the next column one up, placing the block to land on if there is room for one. */
    private void ascend(int x, int y, int z, int dx, int dz, boolean grounded, boolean wading, Sink sink) {
        int nx = x + dx;
        int nz = z + dz;
        double clear = open(x, y + 2, z) + open(nx, y + 1, nz) + head(nx, y + 2, nz);
        if (clear == INF) {
            return;
        }
        double place = 0;
        if (!standable(nx, y, nz)) {
            if (!grounded || !placeable(nx, y, nz) || !standable(nx, y - 1, nz)) {
                return;
            }
            place = Costs.PLACE;
        }
        double move = wading ? Costs.WATER + Costs.JUMP : Math.max(Costs.JUMP, Costs.WALK) + Costs.JUMP_PENALTY;
        sink.offer(Move.ASCEND, nx, y + 1, nz, move + place + clear, place > 0);
    }

    /**
     * Walks off one block down, or drops further: up to {@link #SAFE_DROP} blocks onto
     * ground, any height into water, and up to {@link #BUCKET_DROP} with a bucket to land in.
     */
    private void descend(int x, int y, int z, int dx, int dz, Sink sink) {
        int nx = x + dx;
        int nz = z + dz;
        double clear = open(nx, y + 1, nz) + head(nx, y, nz);
        if (clear == INF) {
            return;
        }
        if (standable(nx, y - 2, nz)) {
            double feet = open(nx, y - 1, nz);
            if (feet != INF) {
                double move = Costs.WALK_OFF_EDGE + Math.max(Costs.fall(1), Costs.CENTER_AFTER_FALL);
                sink.offer(Move.DESCEND, nx, y - 1, nz, move + clear + feet, false);
            }
            return;
        }
        for (int f = y - 1; y - f <= WATER_DROP; f--) {
            int cell = traits(nx, f, nz);
            int drop = y - f;
            if (has(cell, WATER)) {
                sink.offer(Move.FALL, nx, f, nz, Costs.WALK_OFF_EDGE + Costs.fall(drop) + clear, false);
                return;
            }
            if (!has(cell, PASSABLE)) {
                return;
            }
            if (standable(nx, f - 1, nz)) {
                boolean bucket = drop > SAFE_DROP;
                if (bucket && (!kit.bucket() || drop > BUCKET_DROP)) {
                    return;
                }
                double move = Costs.WALK_OFF_EDGE + Costs.fall(drop) + Costs.CENTER_AFTER_FALL;
                sink.offer(Move.FALL, nx, f, nz, move + clear + (bucket ? Costs.PLACE : 0), false);
                return;
            }
        }
    }

    /**
     * A running jump over one to three missing blocks, landing level, or one
     * block up over one or two. Only over gaps the body would survive falling into.
     */
    private void parkour(int x, int y, int z, int dx, int dz, Sink sink) {
        int gx = x + dx;
        int gz = z + dz;
        if (!clear(x, y + 2, z) || standable(gx, y - 1, gz) || !overGap(gx, y, gz)
            || !clear(gx, y, gz) || !clear(gx, y + 1, gz) || !clear(gx, y + 2, gz)) {
            return;
        }
        for (int distance = 2; distance <= LONGEST_JUMP; distance++) {
            int gap = distance - 1;
            if (gap > 1 && !kit.sprint()) {
                return;
            }
            int lx = x + dx * distance;
            int lz = z + dz * distance;
            double cost = Costs.SPRINT * distance + Costs.JUMP_PENALTY;
            if (standable(lx, y, lz)) {
                if (gap <= 2 && clear(lx, y + 1, lz) && clear(lx, y + 2, lz)) {
                    sink.offer(Move.PARKOUR, lx, y + 1, lz, cost + Costs.JUMP, false);
                }
                return;
            }
            if (!clear(lx, y, lz) || !clear(lx, y + 1, lz) || !clear(lx, y + 2, lz)) {
                return;
            }
            if (standable(lx, y - 1, lz)) {
                sink.offer(Move.PARKOUR, lx, y, lz, cost, false);
                return;
            }
            if (!overGap(lx, y, lz)) {
                return;
            }
        }
    }

    /** One block diagonally, without digging, between corners the body can slip past. */
    private void diagonal(int x, int y, int z, int dx, int dz, Sink sink) {
        int nx = x + dx;
        int nz = z + dz;
        if (!clear(nx, y, nz) || !clear(nx, y + 1, nz) || !standable(nx, y - 1, nz)) {
            return;
        }
        boolean a = clear(nx, y, z) && clear(nx, y + 1, z);
        boolean b = clear(x, y, nz) && clear(x, y + 1, nz);
        if (!a && !b || hurts(nx, y, z) || hurts(x, y, nz)) {
            return;
        }
        sink.offer(Move.DIAGONAL, nx, y, nz, (a && b ? run : Costs.WALK) * Costs.DIAGONAL, false);
    }

    /** Up a ladder, or straight up on a block placed underfoot. */
    private void up(int x, int y, int z, int here, boolean grounded, Sink sink) {
        double head = head(x, y + 2, z);
        if (head == INF) {
            return;
        }
        if (has(traits(x, y + 1, z), CLIMBABLE)) {
            sink.offer(Move.CLIMB_UP, x, y + 1, z, Costs.LADDER_UP + head, false);
        } else if (grounded && kit.blocks() > 0 && !has(here, WATER) && has(here, REPLACEABLE)
            && !(head > 0 && has(traits(x, y + 3, z), LOOSE))) {
            sink.offer(Move.PILLAR, x, y + 1, z, Costs.JUMP + Costs.PLACE + Costs.JUMP_PENALTY + head, true);
        }
    }

    /** Down a ladder, or digging out the block underfoot onto the one below it. Never through a block the route put down. */
    private void down(int x, int y, int z, int here, boolean grounded, Sink sink) {
        if (has(traits(x, y - 1, z), CLIMBABLE)) {
            sink.offer(Move.CLIMB_DOWN, x, y - 1, z, Costs.LADDER_DOWN, false);
            return;
        }
        if (!grounded || has(here, WATER) || !standable(x, y - 2, z)) {
            return;
        }
        double floor = open(x, y - 1, z);
        if (floor != INF) {
            sink.offer(Move.DOWNWARD, x, y - 1, z, floor + Costs.fall(1), false);
        }
    }

    /** Falling into this column from level {@code y} would land safely. */
    private boolean overGap(int x, int y, int z) {
        for (int f = y - 1; y - f <= SAFE_DROP; f--) {
            int cell = traits(x, f, z);
            if (has(cell, WATER)) {
                return true;
            }
            if (!has(cell, PASSABLE)) {
                return false;
            }
            if (standable(x, f - 1, z)) {
                return true;
            }
        }
        return false;
    }

    /** Ticks to make a cell the body passes through open, or {@link #INF} for what must not be touched. */
    private double open(int x, int y, int z) {
        int cell = traits(x, y, z);
        if (has(cell, PASSABLE)) {
            return 0;
        }
        if (has(cell, UNKNOWN | HARMFUL)) {
            return INF;
        }
        double ticks = terrain.breakTicks(x, y, z);
        return ticks == INF ? INF : ticks + Costs.BREAK_EXTRA;
    }

    /** Like {@link #open}, for where the head will be: never in water. */
    private double head(int x, int y, int z) {
        return has(traits(x, y, z), WATER) ? INF : open(x, y, z);
    }

    private boolean clear(int x, int y, int z) {
        int cell = traits(x, y, z);
        return has(cell, PASSABLE) && !has(cell, WATER);
    }

    private boolean hurts(int x, int y, int z) {
        return has(traits(x, y, z) | traits(x, y + 1, z), HARMFUL);
    }

    private boolean standable(int x, int y, int z) {
        return has(traits(x, y, z), STANDABLE);
    }

    private boolean placeable(int x, int y, int z) {
        int cell = traits(x, y, z);
        return kit.blocks() > 0 && has(cell, REPLACEABLE) && !has(cell, UNKNOWN);
    }

    private int traits(int x, int y, int z) {
        return terrain.traits(x, y, z);
    }
}
