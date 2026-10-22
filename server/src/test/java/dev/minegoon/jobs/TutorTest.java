package dev.minegoon.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.minegoon.knowledge.Knowledge;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class TutorTest {

    private static final Knowledge KNOWLEDGE = Knowledge.read(new StringReader("""
        find:
          iron_ore: {ways: [cave], trip: mine}
          diamond_ore: {ways: [cave], trip: deep}
        trips:
          mine: {stone_pickaxe: 2}
          deep: {iron_pickaxe: 2, "bread^": [4, 0.5]}
        """));

    private final Tutor tutor = new Tutor(KNOWLEDGE, null, null, null, null, null, null, biome -> true, null);

    @Test
    void theFirstTripPacksWhatOnlyTheSurfaceHasForTheTripsAfterIt() {
        List<Tutor.Shortfall> missing = tutor.missingSupplies(new Tutor.Trip("iron_ore", 3),
            List.of(new Tutor.Trip("diamond_ore", 4)), true, Map.of());
        assertEquals(List.of(new Tutor.Shortfall(Set.of(Material.STONE_PICKAXE), 2, false),
            new Tutor.Shortfall(Set.of(Material.BREAD), 6, false)), missing);
    }

    @Test
    void laterTripsDoNotClimbBackUpForWhatWasUsed() {
        List<Tutor.Shortfall> missing = tutor.missingSupplies(new Tutor.Trip("diamond_ore", 4), List.of(), false,
            Map.of(Material.BREAD, 1));
        assertEquals(List.of(new Tutor.Shortfall(Set.of(Material.IRON_PICKAXE), 2, false)), missing);
    }
}
