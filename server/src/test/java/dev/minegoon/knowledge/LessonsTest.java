package dev.minegoon.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minegoon.knowledge.Knowledge.Find;
import dev.minegoon.knowledge.Knowledge.Way;
import dev.minegoon.knowledge.Lessons.Lesson;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LessonsTest {

    @TempDir
    Path folder;

    @Test
    void aLessonOnTrialIsDroppedAfterMissingTwice() {
        Lessons lessons = new Lessons(folder.resolve("lessons.json"));
        assertTrue(lessons.learn("cow", advice("look in plains")));
        lessons.missed("cow");
        assertTrue(lessons.get("cow").isPresent());
        lessons.missed("cow");
        assertTrue(lessons.get("cow").isEmpty());
    }

    @Test
    void aLessonThatWorkedIsKeptAndNotReplaced() {
        Lessons lessons = new Lessons(folder.resolve("lessons.json"));
        lessons.learn("cow", advice("look in plains"));
        lessons.worked("cow");
        lessons.missed("cow");
        lessons.missed("cow");
        assertFalse(lessons.open("cow"));
        assertFalse(lessons.learn("cow", advice("look in the sky")));
        assertEquals("look in plains", lessons.get("cow").orElseThrow().note());
    }

    @Test
    void lessonsSurviveARestart() {
        Path file = folder.resolve("lessons.json");
        Lessons first = new Lessons(file);
        first.learn("cow", advice("look in plains"));
        first.worked("cow");
        Lesson saved = new Lessons(file).get("cow").orElseThrow();
        assertEquals(List.of(Way.ROAM), saved.ways());
        assertEquals(1, saved.wins());
    }

    @Test
    void theLessonGoesBeforeTheKnowledge() {
        Lessons lessons = new Lessons(folder.resolve("lessons.json"));
        lessons.learn("cow", advice("look in plains"));
        Find base = new Find(List.of(Way.CAVE), 0, 10, 5, List.of("desert"), List.of(), null);
        Find applied = lessons.apply("cow", base);
        assertEquals(List.of(Way.ROAM, Way.CAVE), applied.ways());
        assertEquals(List.of("plains", "desert"), applied.biomes());
        assertEquals(5, applied.best());
    }

    @Test
    void aLessonNeverAddsStripMining() {
        Lessons lessons = new Lessons(folder.resolve("lessons.json"));
        lessons.learn("diamond_ore", new Lesson(List.of(Way.CAVE, Way.DIG), List.of(), List.of(), "dig", 0, 0));
        Find base = new Find(List.of(Way.CAVERN), -64, 16, -59, List.of(), List.of(), null);
        assertEquals(List.of(Way.CAVE, Way.CAVERN), lessons.apply("diamond_ore", base).ways());
    }

    @Test
    void aFullFileDropsALessonOnTrialFirst() {
        Lessons lessons = new Lessons(folder.resolve("lessons.json"));
        lessons.learn("kept", advice("worked once"));
        lessons.worked("kept");
        for (int i = 1; i < Lessons.CAP; i++) {
            lessons.learn("trial" + i, advice("maybe"));
        }
        assertEquals(Lessons.CAP, lessons.size());
        lessons.learn("new", advice("newest"));
        assertEquals(Lessons.CAP, lessons.size());
        assertTrue(lessons.get("kept").isPresent());
        assertTrue(lessons.get("new").isPresent());
    }

    @Test
    void adviceWithoutMovesIsRefused() {
        Lessons lessons = new Lessons(folder.resolve("lessons.json"));
        assertFalse(lessons.learn("cow", new Lesson(List.of(), List.of(), List.of(), "nothing", 0, 0)));
        assertEquals(0, lessons.size());
    }

    private static Lesson advice(String note) {
        return new Lesson(List.of(Way.ROAM), List.of("plains"), List.of(), note, 0, 0);
    }
}
