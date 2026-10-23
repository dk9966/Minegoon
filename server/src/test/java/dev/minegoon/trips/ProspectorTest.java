package dev.minegoon.trips;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProspectorTest {

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
}
