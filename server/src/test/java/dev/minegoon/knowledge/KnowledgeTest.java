package dev.minegoon.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(List.of(Way.CLIFF, Way.PEAK, Way.CAVE, Way.CAVERN), iron.ways());
        assertEquals(14, iron.best());
        assertEquals("deep", knowledge.find(List.of("diamond_ore")).trip());
        assertTrue(knowledge.structureNames().contains("village"));
    }

    @Test
    void oresAreFoundInCavesNotByStripMining() throws IOException {
        Knowledge knowledge = shipped();
        for (String ore : List.of("coal_ore", "copper_ore", "iron_ore", "lapis_ore", "gold_ore", "redstone_ore",
                "diamond_ore", "emerald_ore", "amethyst_cluster")) {
            assertFalse(knowledge.find(List.of(ore)).ways().contains(Way.DIG), ore);
        }
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
    void tripSuppliesGrowWithWhatTheTripIsAfter() {
        Knowledge knowledge = Knowledge.read(new StringReader("""
            trips:
              deep: {shield: 1, "bread|cooked_beef": [6, 0.5]}
            """));
        List<Knowledge.Supply> deep = knowledge.trip("deep");
        assertEquals(1, deep.get(0).needed(24));
        assertEquals(List.of("bread", "cooked_beef"), deep.get(1).items());
        assertEquals(7, deep.get(1).needed(1));
        assertEquals(18, deep.get(1).needed(24));
    }

    @Test
    void theDeepTripPacksForTheWholeTripWithNoStaircasePicks() throws IOException {
        List<Knowledge.Supply> deep = shipped().trip("deep");
        assertTrue(deep.stream().noneMatch(supply -> supply.items().equals(List.of("stone_pickaxe"))));
        assertTrue(deep.stream().anyMatch(supply -> supply.items().contains("water_bucket")));
        assertTrue(deep.stream().anyMatch(supply -> supply.items().contains("oak_log")));
        Knowledge.Supply food = deep.stream().filter(supply -> supply.items().contains("cooked_beef")).findFirst()
            .orElseThrow();
        assertTrue(food.needed(24) > food.needed(1));
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
