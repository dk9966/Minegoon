package dev.minegoon.trips;

import dev.minegoon.actions.Checkpoint;
import dev.minegoon.actions.Mover;
import dev.minegoon.world.Pos;
import dev.minegoon.world.Vision;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Digging for things that do not show at the surface. Down is a staircase one
 * block forward for each block down, so the way back up is the same stairs.
 * At the right height it digs a two-high tunnel straight ahead. Every step
 * opens new walls for the eyes. Lava, water, and open drops stop a step, and
 * the tunnel turns instead.
 */
public final class Tunneler {

    private static final int[][] HEADINGS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    private final Mover mover;
    private final Vision vision;
    private int heading;

    public Tunneler(Mover mover, Vision vision) {
        this.mover = mover;
        this.vision = vision;
    }

    /** Stairs down toward {@code y}, at most {@code steps} steps. False when every way down is blocked. */
    public boolean down(int y, int steps, Checkpoint checkpoint) {
        int taken = 0;
        while (taken < steps && mover.feet().y() > y) {
            checkpoint.check();
            if (!step(-1)) {
                return taken > 0;
            }
            taken++;
        }
        return true;
    }

    /** A straight tunnel, at most {@code length} blocks. False when it cannot go on in any direction. */
    public boolean ahead(int length, Checkpoint checkpoint) {
        for (int dug = 0; dug < length; dug++) {
            checkpoint.check();
            if (!step(0)) {
                return dug > 0;
            }
        }
        return true;
    }

    /**
     * A tunnel along a compass direction; a diagonal goes along its east-west
     * part. False when it cannot even start.
     */
    public boolean toward(int dirX, int dirZ, int length, Checkpoint checkpoint) {
        int x = Integer.signum(dirX);
        int z = x != 0 ? 0 : Integer.signum(dirZ);
        for (int i = 0; i < HEADINGS.length; i++) {
            if (HEADINGS[i][0] == x && HEADINGS[i][1] == z) {
                heading = i;
            }
        }
        return ahead(length, checkpoint);
    }

    /** Turns left or right, so the next tunnel opens fresh rock. */
    public void turn() {
        heading = (heading + (ThreadLocalRandom.current().nextBoolean() ? 1 : 3)) % HEADINGS.length;
    }

    /**
     * One step along the heading, turning when the way is unsafe. With no safe
     * way in any direction, it digs down a block and tries again from there.
     */
    private boolean step(int down) {
        do {
            for (int turns = 0; turns < HEADINGS.length; turns++) {
                int[] direction = HEADINGS[heading];
                Pos feet = mover.feet();
                Pos next = feet.offset(direction[0], down, direction[1]);
                if (mover.stepTo(next)) {
                    vision.touched(next);
                    return true;
                }
                heading = (heading + 1) % HEADINGS.length;
            }
        } while (mover.digDown());
        return false;
    }
}
