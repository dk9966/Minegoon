package dev.minegoon.actions;

import static dev.minegoon.world.Traits.PASSABLE;
import static dev.minegoon.world.Traits.STANDABLE;
import static dev.minegoon.world.Traits.UNKNOWN;
import static dev.minegoon.world.Traits.has;

import dev.minegoon.world.Pos;
import dev.minegoon.world.path.Move;
import dev.minegoon.world.path.Step;
import java.util.List;

/**
 * Whether a sprint along a flat run should leave the ground.
 *
 * <p>An open ceiling is a normal sprint-jump: the key is held, so the body
 * jumps again on the tick it lands. A two-block ceiling is faster still, but
 * only when the jump is tapped every {@link #HEADHITTER} ticks. Holding the
 * key there meets the ceiling at once and the run stutters instead of picking up speed.
 */
final class Pace {

    /** Ticks between jumps under a two-block ceiling. */
    static final int HEADHITTER = 3;

    enum Kind {
        /** No sprint-jump: the run turns, the landing is missing, or a block ahead is unknown. */
        NONE,
        /** Ceiling high enough to jump and land at full stretch. */
        OPEN,
        /** Two blocks of room. Tap the jump. */
        LOW
    }

    private enum Roof {
        OPEN, LOW, UNKNOWN
    }

    @FunctionalInterface
    interface World {
        int traits(int x, int y, int z);
    }

    private Pace() {
    }

    /**
     * {@code run} is how many steps past the current one a held jump needs.
     * Fewer than that, and a full jump would land in a turn or off the end.
     * A tap-jump under a ceiling only needs the next block. One low ceiling
     * anywhere in the stretch makes the whole stretch a tap-jump, so a full
     * jump never starts just before a tunnel.
     */
    static Kind kind(List<Step> steps, int index, int run, World world) {
        Kind full = stretch(steps, index, run, world);
        if (full != Kind.NONE) {
            return full;
        }
        if (run > 1 && stretch(steps, index, 1, world) == Kind.LOW) {
            return Kind.LOW;
        }
        return Kind.NONE;
    }

    private static Kind stretch(List<Step> steps, int index, int run, World world) {
        if (index < 0 || index + run >= steps.size()) {
            return Kind.NONE;
        }
        Step first = steps.get(index);
        if (first.move() != Move.TRAVERSE && first.move() != Move.DIAGONAL) {
            return Kind.NONE;
        }
        boolean low = false;
        for (int i = index; i <= index + run; i++) {
            Step step = steps.get(i);
            if (step.move() != first.move() || step.dx() != first.dx() || step.dz() != first.dz()
                || step.to().y() != first.from().y()) {
                return Kind.NONE;
            }
            if (!floor(world, step.to()) || !head(world, step.from()) || !head(world, step.to())) {
                return Kind.NONE;
            }
            Roof from = roof(world, step.from());
            Roof to = roof(world, step.to());
            if (from == Roof.UNKNOWN || to == Roof.UNKNOWN) {
                return Kind.NONE;
            }
            low |= from == Roof.LOW || to == Roof.LOW;
        }
        return low ? Kind.LOW : Kind.OPEN;
    }

    private static boolean floor(World world, Pos feet) {
        return has(world.traits(feet.x(), feet.y() - 1, feet.z()), STANDABLE);
    }

    private static boolean head(World world, Pos feet) {
        int block = world.traits(feet.x(), feet.y() + 1, feet.z());
        return has(block, PASSABLE) && !has(block, UNKNOWN);
    }

    /** The block two above the feet. Solid means the body has two blocks of room. */
    private static Roof roof(World world, Pos feet) {
        int block = world.traits(feet.x(), feet.y() + 2, feet.z());
        if (has(block, UNKNOWN)) {
            return Roof.UNKNOWN;
        }
        return has(block, PASSABLE) ? Roof.OPEN : Roof.LOW;
    }
}
