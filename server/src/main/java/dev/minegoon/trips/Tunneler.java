package dev.minegoon.trips;

import dev.minegoon.actions.Body;
import dev.minegoon.actions.Checkpoint;
import dev.minegoon.actions.Mover;
import dev.minegoon.world.Pos;
import dev.minegoon.world.Vision;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Tag;

/**
 * Digging for things that do not show at the surface, and out of places a walk
 * cannot leave. Down and up are staircases, one block along for each block of
 * height, so the way back is the same stairs. At the right height it digs a
 * two-high tunnel straight ahead. Every step opens new walls for the eyes. Lava,
 * water, and open drops stop a step, and the tunnel turns instead.
 */
public final class Tunneler {

    private static final int[][] HEADINGS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    private final Body body;
    private final Mover mover;
    private final Vision vision;
    private int heading;

    public Tunneler(Body body, Mover mover, Vision vision) {
        this.body = body;
        this.mover = mover;
        this.vision = vision;
    }

    /** Holds a pickaxe. Without one, rock comes out a block every few seconds, too slow to search by. */
    public boolean armed() {
        return body.main().call(() -> Arrays.stream(body.player().getInventory().getStorageContents())
            .anyMatch(stack -> stack != null && Tag.ITEMS_PICKAXES.isTagged(stack.getType())));
    }

    /** Stairs down toward {@code y}, at most {@code steps} steps. False when every way down is blocked. */
    public boolean down(int y, int steps, Checkpoint checkpoint) {
        return stairs(-1, y, steps, checkpoint);
    }

    /** Stairs up toward {@code y}, at most {@code steps} steps. False when every way up is blocked. */
    public boolean up(int y, int steps, Checkpoint checkpoint) {
        return stairs(1, y, steps, checkpoint);
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

    /** A tunnel along a compass direction. False when it cannot even start. */
    public boolean toward(int dirX, int dirZ, int length, Checkpoint checkpoint) {
        face(dirX, dirZ);
        return ahead(length, checkpoint);
    }

    /** Heads along a compass direction; a diagonal goes along its east-west part. */
    public void face(int dirX, int dirZ) {
        int x = Integer.signum(dirX);
        int z = x != 0 ? 0 : Integer.signum(dirZ);
        for (int i = 0; i < HEADINGS.length; i++) {
            if (HEADINGS[i][0] == x && HEADINGS[i][1] == z) {
                heading = i;
            }
        }
    }

    /** Turns left or right, so the next tunnel opens fresh rock. */
    public void turn() {
        heading = (heading + (ThreadLocalRandom.current().nextBoolean() ? 1 : 3)) % HEADINGS.length;
    }

    private boolean stairs(int rise, int y, int steps, Checkpoint checkpoint) {
        int taken = 0;
        while (taken < steps && Integer.compare(y, mover.feet().y()) == rise) {
            checkpoint.check();
            if (!step(rise)) {
                return taken > 0;
            }
            taken++;
        }
        return true;
    }

    /**
     * One step along the heading, turning when the way is unsafe. With no safe
     * way level or down in any direction, it digs down a block and tries again
     * from there.
     */
    private boolean step(int rise) {
        do {
            for (int turns = 0; turns < HEADINGS.length; turns++) {
                int[] direction = HEADINGS[heading];
                Pos next = mover.feet().offset(direction[0], rise, direction[1]);
                if (mover.stepTo(next)) {
                    vision.touched(next);
                    return true;
                }
                heading = (heading + 1) % HEADINGS.length;
            }
        } while (rise <= 0 && mover.digDown());
        return false;
    }
}
