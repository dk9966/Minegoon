package dev.minegoon.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.minegoon.knowledge.Knowledge.Find;
import dev.minegoon.knowledge.Knowledge.SearchOrders;
import dev.minegoon.knowledge.Knowledge.Way;
import dev.minegoon.knowledge.Lessons.Lesson;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchOrdersTest {

    private static final Knowledge KNOWLEDGE = Knowledge.read(new StringReader("""
        find:
          diamond_ore: {y: [-64, 16], best: -58, ways: [cave, cavern], trip: deep}
          sheep: {ways: [roam], biomes: [meadow]}
        trips:
          deep: {shield: 1}
        """));

    @TempDir
    Path folder;

    @Test
    void ordersGoBeforeLessonsAndKnowledge() {
        Lessons lessons = new Lessons(folder.resolve("lessons.json"));
        lessons.learn("diamond_ore", new Lesson(List.of(Way.CLIFF), List.of(), List.of(), "try cliffs", 0, 0));
        Find find = search(new SearchOrders(List.of(Way.DIG), List.of()), lessons, "diamond_ore");
        assertEquals(List.of(Way.DIG, Way.CLIFF, Way.CAVE, Way.CAVERN), find.ways());
    }

    @Test
    void ordersKeepTheHeightsAndTrip() {
        Find find = search(new SearchOrders(List.of(Way.DIG), List.of()), new Lessons(folder.resolve("l.json")),
            "diamond_ore");
        assertEquals(-64, find.low());
        assertEquals(16, find.high());
        assertEquals(-58, find.best());
        assertEquals("deep", find.trip());
    }

    @Test
    void aBiomeOrderMeansRoamingThereFirst() {
        Lessons lessons = new Lessons(folder.resolve("lessons.json"));
        Find find = search(new SearchOrders(List.of(), List.of("plains")), lessons, "sheep");
        assertEquals(List.of(Way.ROAM), find.ways());
        assertEquals(List.of("plains", "meadow"), find.biomes());
    }

    @Test
    void digComesOnlyFromAnOrder() {
        Lessons lessons = new Lessons(folder.resolve("lessons.json"));
        lessons.learn("diamond_ore", new Lesson(List.of(Way.DIG, Way.CAVE), List.of(), List.of(), "dig", 0, 0));
        assertFalse(search(SearchOrders.NONE, lessons, "diamond_ore").ways().contains(Way.DIG));
        assertEquals(Way.DIG, search(new SearchOrders(List.of(Way.DIG), List.of()), lessons, "diamond_ore")
            .ways().getFirst());
    }

    @Test
    void noOrdersChangeNothing() {
        Find base = KNOWLEDGE.find(List.of("diamond_ore"));
        assertSame(base, SearchOrders.NONE.over(base));
    }

    /** The same merge as the prospector's search. */
    private static Find search(SearchOrders orders, Lessons lessons, String thing) {
        return orders.over(lessons.apply(thing, KNOWLEDGE.find(List.of(thing))));
    }
}
