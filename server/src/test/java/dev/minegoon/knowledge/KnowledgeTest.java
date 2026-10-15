package dev.minegoon.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minegoon.knowledge.Knowledge.Find;
import dev.minegoon.knowledge.Knowledge.Way;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class KnowledgeTest {

    @Test
    void theShippedFileReadsCleanly() throws IOException {
        Knowledge knowledge = shipped();
        assertEquals(List.of(), knowledge.problems(biome -> true, thing -> true));
        Find iron = knowledge.find(List.of("iron_ore"));
        assertEquals(List.of(Way.CLIFF, Way.PEAK, Way.CAVE, Way.DIG), iron.ways());
        assertEquals(14, iron.best());
        assertEquals("deep", knowledge.find(List.of("diamond_ore")).trip());
        assertTrue(knowledge.structureNames().contains("village"));
    }

    @Test
    void deepslateOresShareTheirStoneEntry() throws IOException {
        Knowledge knowledge = shipped();
        assertEquals("diamond_ore", knowledge.canonical("deepslate_diamond_ore"));
        assertEquals("deepslate", knowledge.canonical("deepslate"));
        assertEquals(knowledge.find(List.of("diamond_ore")), knowledge.find(List.of("deepslate_diamond_ore")));
    }

    @Test
    void mergedFindsKeepTheFirstNamesOrder() {
        Knowledge knowledge = Knowledge.read(new StringReader("""
            find:
              a: {y: [0, 10], best: 5, ways: [cave, dig], biomes: [plains]}
              b: {y: [-20, 4], ways: [cliff, cave], biomes: [desert, plains], trip: deep}
            trips:
              deep: {shield: 1}
            """));
        Find both = knowledge.find(List.of("a", "b"));
        assertEquals(List.of(Way.CAVE, Way.DIG, Way.CLIFF), both.ways());
        assertEquals(-20, both.low());
        assertEquals(10, both.high());
        assertEquals(5, both.best());
        assertEquals(List.of("plains", "desert"), both.biomes());
        assertEquals("deep", both.trip());
        assertEquals(Find.ANYWHERE, knowledge.find(List.of("nothing")));
        assertNull(Find.ANYWHERE.best());
    }

    @Test
    void reportsTyposInsteadOfIgnoringThem() {
        Knowledge knowledge = Knowledge.read(new StringReader("""
            find:
              cow: {ways: [roam, fly], biomes: [plians], visit: [castle], trip: far}
            """));
        List<String> problems = knowledge.problems(biome -> biome.equals("plains"), thing -> thing.equals("cow"));
        assertEquals(List.of("cow: unknown way fly", "unknown trip far", "cow: unknown biome plians",
            "cow: unknown structure castle"), problems);
    }

    private static Knowledge shipped() throws IOException {
        try (Reader yaml = new InputStreamReader(Objects.requireNonNull(
                KnowledgeTest.class.getResourceAsStream("/knowledge.yml")), StandardCharsets.UTF_8)) {
            return Knowledge.read(yaml);
        }
    }
}
