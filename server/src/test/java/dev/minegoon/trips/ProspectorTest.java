package dev.minegoon.trips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minegoon.world.Pos;
import dev.minegoon.world.Vision;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProspectorTest {

    private static final Pos FEET = new Pos(0, 70, 0);

    @Test
    void offsetsPointAtTheirCompassDirection() {
        assertEquals(0, Prospector.compass(40, 0));
        assertEquals(1, Prospector.compass(30, 30));
        assertEquals(2, Prospector.compass(0, 40));
        assertEquals(4, Prospector.compass(-40, 0));
        assertEquals(6, Prospector.compass(0, -40));
        assertEquals(7, Prospector.compass(30, -30));
        assertEquals(0, Prospector.compass(40, -10));
    }

    @Test
    void diagonalsHaveNoMoreUnseenGroundThanStraightAheadOnceTheViewIsSeen() {
        int[] unseen = Prospector.unseen(22, (dx, dz) -> dx * dx + dz * dz <= 16 * 16);
        for (int direction = 1; direction < unseen.length; direction++) {
            assertTrue(Math.abs(unseen[0] - unseen[direction]) <= unseen[0] / 10,
                unseen[0] + " east against " + unseen[direction] + " in direction " + direction);
        }
    }

    @Test
    void groundAlreadySeenIsNotUnseen() {
        int[] unseen = Prospector.unseen(10, (dx, dz) -> dx > 0);
        assertEquals(0, unseen[0]);
        assertTrue(unseen[4] > 0);
    }

    @Test
    void waterIsWorthNothingAndMountainsPullADeepSearch() {
        List<Vision.Column> horizon = new ArrayList<>();
        for (int d = 32; d <= 224; d += 16) {
            horizon.add(new Vision.Column(new Pos(d, 63, 0), "ocean", true));
            horizon.add(new Vision.Column(new Pos(-d, 71, 0), "plains", false));
            horizon.add(new Vision.Column(new Pos(0, 120, d), "stony_peaks", false));
        }
        double[] deep = Prospector.land(horizon, FEET, true, false);
        assertEquals(0, deep[0]);
        assertEquals(1, deep[4]);
        assertEquals(3, deep[2]);
        assertEquals(1, deep[6], "nothing in sight that way");

        double[] shallow = Prospector.land(horizon, FEET, false, false);
        assertEquals(1, shallow[2]);

        double[] byWater = Prospector.land(horizon, FEET, false, true);
        assertEquals(1, byWater[0]);
    }

    @Test
    void aWayDownCloseByBeatsOneALittleDeeperFarOff() {
        Pos near = new Pos(20, 60, 0);
        Pos far = new Pos(200, 55, 0);
        Pos ravine = new Pos(200, 0, 0);
        List<Pos> openings = new ArrayList<>(List.of(far, ravine, near));
        openings.sort(Prospector.worthTheWalk(FEET, pos -> FEET.y() - pos.y()));
        assertEquals(List.of(ravine, near, far), openings);
    }
}
