package dev.minegoon.trips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minegoon.trips.Bestiary.Foe;
import dev.minegoon.trips.Bestiary.Strength;
import dev.minegoon.trips.Bestiary.Tactic;
import java.util.Collections;
import java.util.List;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

class BestiaryTest {

    /** Full health, an iron sword, iron armor and a shield. */
    private static final Strength GEARED = new Strength(6 * 1.6, 20 / ((1 - 0.6) * 0.6));
    /** Full health, a stone axe, no armor, no shield. */
    private static final Strength BARE = new Strength(9 * 0.8, 20);

    @Test
    void everyHostileHasATactic() {
        for (EntityType type : List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER,
                EntityType.SPIDER, EntityType.ENDERMAN, EntityType.WITCH, EntityType.WARDEN, EntityType.DROWNED,
                EntityType.CAVE_SPIDER, EntityType.SILVERFISH, EntityType.PHANTOM, EntityType.ZOMBIFIED_PIGLIN)) {
            assertTrue(Bestiary.of(type).advice().length() > 5, type.name());
        }
        assertEquals(Tactic.EXPLODES, Bestiary.of(EntityType.CREEPER).tactic());
        assertEquals(Tactic.GAZE, Bestiary.of(EntityType.ENDERMAN).tactic());
        assertEquals(Tactic.NEUTRAL, Bestiary.of(EntityType.ZOMBIFIED_PIGLIN).tactic());
        assertEquals(Tactic.FLEE, Bestiary.of(EntityType.WARDEN).tactic());
        assertTrue(Bestiary.of(EntityType.SPIDER).climbs());
    }

    @Test
    void oneZombieIsAWinEvenBare() {
        assertTrue(Bestiary.wins(BARE, List.of(foe(EntityType.ZOMBIE)), false));
    }

    @Test
    void aCrowdIsAWinOnlyWithGear() {
        List<Foe> crowd = Collections.nCopies(4, foe(EntityType.ZOMBIE));
        assertFalse(Bestiary.wins(BARE, crowd, false));
        assertTrue(Bestiary.wins(GEARED, crowd, true));
    }

    @Test
    void aWardenIsNeverAWin() {
        assertFalse(Bestiary.wins(new Strength(100, 1000), List.of(foe(EntityType.WARDEN)), true));
    }

    @Test
    void aShieldMakesCreepersSafer() {
        List<Foe> two = Collections.nCopies(2, foe(EntityType.CREEPER));
        Strength hurt = new Strength(7.2, 12);
        assertFalse(Bestiary.wins(hurt, two, false));
        assertTrue(Bestiary.wins(hurt, two, true));
    }

    private static Foe foe(EntityType type) {
        Bestiary.Kind kind = Bestiary.of(type);
        return new Foe(kind, kind.health());
    }
}
