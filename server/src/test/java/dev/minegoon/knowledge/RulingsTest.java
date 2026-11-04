package dev.minegoon.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minegoon.knowledge.Rulings.Ruling;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RulingsTest {

    @TempDir
    Path folder;

    @Test
    void aTakeThatPaidOffIsKeptAndSurvivesARestart() {
        Path file = folder.resolve("rulings.json");
        Rulings rulings = new Rulings(file);
        rulings.worked("iron_golem", "the sword was enough");
        Ruling kept = new Rulings(file).get("iron_golem").orElseThrow();
        assertTrue(kept.kept());
        assertEquals("the sword was enough", kept.note());
        assertEquals(1, kept.wins());
    }

    @Test
    void twoMissesDropATakeThatUsedToWork() {
        Rulings rulings = new Rulings(folder.resolve("rulings.json"));
        rulings.worked("iron_golem", "the sword was enough");
        rulings.missed("iron_golem");
        assertTrue(rulings.get("iron_golem").orElseThrow().kept());
        rulings.missed("iron_golem");
        assertTrue(rulings.get("iron_golem").isEmpty());
    }

    @Test
    void aMissWithNothingRememberedIsIgnored() {
        Rulings rulings = new Rulings(folder.resolve("rulings.json"));
        rulings.missed("iron_golem");
        assertEquals(0, rulings.size());
    }

    @Test
    void aLaterWinDoesNotReplaceTheNote() {
        Rulings rulings = new Rulings(folder.resolve("rulings.json"));
        rulings.worked("iron_golem", "the sword was enough");
        rulings.worked("iron_golem", "a different reason");
        Ruling kept = rulings.get("iron_golem").orElseThrow();
        assertEquals("the sword was enough", kept.note());
        assertEquals(2, kept.wins());
    }
}
