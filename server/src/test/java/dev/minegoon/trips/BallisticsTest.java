package dev.minegoon.trips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BallisticsTest {

    @Test
    void aimsAboveATargetLevelWithTheBow() {
        double pitch = Ballistics.pitch(20, 0);
        assertTrue(pitch < 0, "aims upward");
        assertEquals(0, Ballistics.heightAt(Math.toRadians(-pitch), 20), 0.05);
    }

    @Test
    void aimsHigherTheFurtherTheTarget() {
        assertTrue(Ballistics.pitch(40, 0) < Ballistics.pitch(15, 0));
    }

    @Test
    void reachesARaisedTarget() {
        double pitch = Ballistics.pitch(25, 6);
        assertEquals(6, Ballistics.heightAt(Math.toRadians(-pitch), 25), 0.05);
    }

    @Test
    void seesAWallInTheWayOfTheCurve() {
        double angle = Math.toRadians(-Ballistics.pitch(30, 0));
        assertTrue(Ballistics.clear(angle, 30, (along, height) -> true));
        assertTrue(!Ballistics.clear(angle, 30, (along, height) -> along < 14 || along > 16));
        assertTrue(Ballistics.clear(angle, 30, (along, height) -> height > -0.5));
    }

    @Test
    void countsFlightTime() {
        assertEquals(1, Ballistics.ticks(3));
        assertTrue(Ballistics.ticks(30) > 10);
    }
}
