package dev.minegoon.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.minegoon.jobs.Stall.Turn;
import org.junit.jupiter.api.Test;

class StallTest {

    private static final long MINUTE = 60_000L;

    @Test
    void reportsEveryTenMinutesWhileWorkGoesOn() {
        Stall stall = new Stall();
        assertEquals(Turn.NOTHING, stall.check(1, 0, 0, 0));
        assertEquals(Turn.NOTHING, stall.check(1, 1, 0, 5 * MINUTE));
        assertEquals(Turn.REPORT, stall.check(1, 2, 0, 10 * MINUTE));
        assertEquals(Turn.NOTHING, stall.check(1, 3, 0, 15 * MINUTE));
        assertEquals(Turn.REPORT, stall.check(1, 3, 4, 20 * MINUTE));
    }

    @Test
    void asksOnceThenGivesUpWithoutProgress() {
        Stall stall = new Stall();
        stall.check(1, 0, 0, 0);
        assertEquals(Turn.ASK, stall.check(1, 0, 0, 10 * MINUTE));
        assertEquals(Turn.REPORT, stall.check(1, 0, 0, 11 * MINUTE));
        assertEquals(Turn.NOTHING, stall.check(1, 0, 0, 20 * MINUTE));
        assertEquals(Turn.GIVE_UP, stall.check(1, 0, 0, 30 * MINUTE));
    }

    @Test
    void progressPutsOffGivingUp() {
        Stall stall = new Stall();
        stall.check(1, 0, 0, 0);
        stall.check(1, 0, 1, 25 * MINUTE);
        assertEquals(Turn.ASK, stall.check(1, 0, 1, 35 * MINUTE));
        assertEquals(Turn.GIVE_UP, stall.check(1, 0, 1, 55 * MINUTE));
    }

    @Test
    void startsOverAfterAPause() {
        Stall stall = new Stall();
        stall.check(1, 0, 0, 0);
        stall.idle();
        assertEquals(Turn.NOTHING, stall.check(1, 0, 0, 40 * MINUTE));
    }

    @Test
    void startsOverForANewTask() {
        Stall stall = new Stall();
        stall.check(1, 0, 0, 0);
        assertEquals(Turn.NOTHING, stall.check(2, 0, 0, 40 * MINUTE));
    }
}
