package dev.minegoon.actions;

import static dev.minegoon.world.Traits.PASSABLE;
import static dev.minegoon.world.Traits.STANDABLE;
import static dev.minegoon.world.Traits.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.minegoon.world.Pos;
import dev.minegoon.world.path.Move;
import dev.minegoon.world.path.Step;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaceTest {

    private static final int RUN = 3;

    @Test
    void holdsTheJumpOnOpenGround() {
        assertEquals(Pace.Kind.OPEN, Pace.kind(line(6, Move.TRAVERSE, 1, 0), 0, RUN, flat(false)));
    }

    @Test
    void tapsTheJumpUnderATwoBlockCeiling() {
        assertEquals(Pace.Kind.LOW, Pace.kind(line(6, Move.TRAVERSE, 1, 0), 0, RUN, flat(true)));
    }

    @Test
    void tapsRatherThanHoldsWhenTheTunnelStartsInsideTheRun() {
        List<Step> steps = line(6, Move.TRAVERSE, 1, 0);
        assertEquals(Pace.Kind.LOW, Pace.kind(steps, 0, RUN, (x, y, z) -> {
            if (y < 0) {
                return STANDABLE;
            }
            return y >= 2 && x >= 3 ? STANDABLE : PASSABLE;
        }));
    }

    @Test
    void tapsThroughAShortTunnelButDoesNotHoldAShortOpenJump() {
        assertEquals(Pace.Kind.LOW, Pace.kind(line(2, Move.TRAVERSE, 1, 0), 0, RUN, flat(true)));
        assertEquals(Pace.Kind.NONE, Pace.kind(line(2, Move.TRAVERSE, 1, 0), 0, RUN, flat(false)));
        assertEquals(Pace.Kind.NONE, Pace.kind(line(1, Move.TRAVERSE, 1, 0), 0, RUN, flat(true)));
    }

    @Test
    void doesNotJumpIntoATurn() {
        List<Step> steps = line(6, Move.TRAVERSE, 1, 0);
        steps.set(1, new Step(Move.TRAVERSE, new Pos(1, 0, 0), new Pos(1, 0, 1), 1, false));
        assertEquals(Pace.Kind.NONE, Pace.kind(steps, 0, RUN, flat(true)));
    }

    @Test
    void doesNotJumpWithNoFloorNoHeadroomOrAnUnknownCeiling() {
        List<Step> steps = line(6, Move.TRAVERSE, 1, 0);
        assertEquals(Pace.Kind.NONE, Pace.kind(steps, 0, RUN, (x, y, z) -> {
            if (y < 0) {
                return x == 2 ? PASSABLE : STANDABLE;
            }
            return PASSABLE;
        }));
        assertEquals(Pace.Kind.NONE, Pace.kind(steps, 0, RUN, (x, y, z) -> y == 1 ? STANDABLE : y < 0 ? STANDABLE : PASSABLE));
        assertEquals(Pace.Kind.NONE, Pace.kind(steps, 0, RUN, (x, y, z) -> y >= 2 ? UNKNOWN : y < 0 ? STANDABLE : PASSABLE));
    }

    @Test
    void holdsTheJumpOnAStraightDiagonal() {
        assertEquals(Pace.Kind.OPEN, Pace.kind(line(6, Move.DIAGONAL, 1, 1), 0, RUN, flat(false)));
    }

    private static Pace.World flat(boolean ceiling) {
        return (x, y, z) -> {
            if (y < 0) {
                return STANDABLE;
            }
            return ceiling && y >= 2 ? STANDABLE : PASSABLE;
        };
    }

    private static List<Step> line(int count, Move move, int dx, int dz) {
        List<Step> steps = new ArrayList<>();
        int x = 0;
        int z = 0;
        for (int i = 0; i < count; i++) {
            int nextX = x + dx;
            int nextZ = z + dz;
            steps.add(new Step(move, new Pos(x, 0, z), new Pos(nextX, 0, nextZ), 1, false));
            x = nextX;
            z = nextZ;
        }
        return steps;
    }
}
