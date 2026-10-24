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
 * Whether a sprint on flat ground should leave the ground.
 *
 * <p>The efficient way to travel is to sprint-jump the whole way, turns included.
 * An open ceiling holds the jump, so the body leaves the ground again on the
 * tick it lands. A two-block ceiling is faster still, but only when the jump is
 * tapped every {@link #HEADHITTER} ticks. Holding the key there meets the ceiling
 * at once and the run stutters instead of picking up speed.
 */
final class Pace {

    /** Ticks between jumps under a two-block ceiling. */
    static final int HEADHITTER = 3;

    enum Kind {
        /** No sprint-jump: the landing is missing, the ceiling is unknown, or this is a stop, a fall, or a timed leap. */
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
     * The current flat step, when another step follows it. The last step is a stop,
     * and a running jump there overshoots. A parkour leap and a long fall time
     * themselves. One low ceiling on this step or the next makes the jump a tap,
     * so a held jump never starts in the block before a tunnel.
     */
    static Kind kind(List<Step> steps, int index, World world) {
        if (index < 0 || index + 1 >= steps.size()) {
            return Kind.NONE;
        }
        Step step = steps.get(index);
        Step next = steps.get(index + 1);
        if (!flat(step) || !jumpable(world, step) || timed(next)) {
            return Kind.NONE;
        }
        boolean low = low(world, step);
        if (flat(next)) {
            if (!jumpable(world, next)) {
                return Kind.NONE;
            }
            low |= low(world, next);
        }
        return low ? Kind.LOW : Kind.OPEN;
    }

    private static boolean flat(Step step) {
        return (step.move() == Move.TRAVERSE || step.move() == Move.DIAGONAL) && step.from().y() == step.to().y();
    }

    /** A leap whose takeoff is its own step. Jumping early drops the body short of it. */
    private static boolean timed(Step step) {
        return step.move() == Move.PARKOUR || step.move() == Move.FALL;
    }

    private static boolean jumpable(World world, Step step) {
        return floor(world, step.to()) && head(world, step.from()) && head(world, step.to())
            && roof(world, step.from()) != Roof.UNKNOWN && roof(world, step.to()) != Roof.UNKNOWN;
    }

    private static boolean low(World world, Step step) {
        return roof(world, step.from()) == Roof.LOW || roof(world, step.to()) == Roof.LOW;
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
