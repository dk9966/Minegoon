package dev.minegoon.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.minegoon.chat.Effects.Active;
import dev.minegoon.chat.Effects.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

class EffectsTest {

    @Test
    void none() {
        assertEquals("Nothing is affecting me.", Effects.describe(List.of()));
    }

    @Test
    void namesTheLevelAndHowLong() {
        assertEquals("Poison II for 15 seconds.", Effects.describe(List.of(effect("poison", 1, 20 * 15, Kind.HARMFUL))));
        assertEquals("Regeneration for 1 minute 5 seconds.",
            Effects.describe(List.of(effect("regeneration", 0, 20 * 65, Kind.BENEFICIAL))));
        assertEquals("Speed for a moment.", Effects.describe(List.of(effect("speed", 0, 10, Kind.BENEFICIAL))));
        assertEquals("Night vision, not wearing off.",
            Effects.describe(List.of(new Active("night vision", 0, -1, true, Kind.BENEFICIAL))));
        assertEquals("Strength X for 1 hour.",
            Effects.describe(List.of(effect("strength", 9, 20 * 3600, Kind.BENEFICIAL))));
        assertEquals("Haste 11 for 2 minutes.",
            Effects.describe(List.of(effect("haste", 10, 20 * 120, Kind.BENEFICIAL))));
    }

    @Test
    void harmfulOnesComeFirst() {
        List<Active> effects = List.of(
            effect("speed", 0, 20 * 5, Kind.BENEFICIAL),
            effect("glowing", 0, 20 * 60, Kind.NEUTRAL),
            effect("wither", 0, 20 * 30, Kind.HARMFUL),
            effect("poison", 0, 20 * 10, Kind.HARMFUL));
        assertEquals("Poison for 10 seconds, wither for 30 seconds, glowing for 1 minute, and speed for 5 seconds.",
            Effects.describe(effects));
    }

    @Test
    void anEffectThatDoesNotEndSortsLastInItsKind() {
        List<Active> effects = List.of(
            new Active("regeneration", 0, -1, true, Kind.BENEFICIAL),
            effect("poison", 0, 20 * 8, Kind.HARMFUL));
        assertEquals("Poison for 8 seconds and regeneration, not wearing off.", Effects.describe(effects));
    }

    private static Active effect(String name, int amplifier, int ticks, Kind kind) {
        return new Active(name, amplifier, ticks, false, kind);
    }
}
