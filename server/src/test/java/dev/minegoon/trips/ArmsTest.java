package dev.minegoon.trips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class ArmsTest {

    @Test
    void aOneHitKillBeatsAFasterSwordThatNeedsTwo() {
        assertEquals(0, Arms.timeToKill(10, 1));
        assertTrue(Arms.timeToKill(8, 1.6) > 0);
        assertEquals(Material.NETHERITE_AXE, Arms.bestHunt(List.of(Material.NETHERITE_SWORD, Material.NETHERITE_AXE)));
    }

    @Test
    void huntingTakesTheToolThatFinishesACowSoonest() {
        assertEquals(Material.STONE_SWORD, Arms.bestHunt(List.of(Material.STONE_AXE, Material.STONE_SWORD)));
        assertEquals(Material.COPPER_SWORD, Arms.bestHunt(List.of(Material.COPPER_SWORD, Material.STONE_PICKAXE)));
        assertEquals(Material.IRON_PICKAXE, Arms.bestHunt(List.of(Material.WOODEN_PICKAXE, Material.IRON_PICKAXE)));
        assertEquals(Material.DIAMOND_PICKAXE,
            Arms.bestHunt(List.of(Material.STONE_AXE, Material.DIAMOND_PICKAXE)));
    }

    @Test
    void nothingToHitWithStaysEmpty() {
        assertNull(Arms.bestHunt(List.of(Material.BREAD, Material.OAK_LOG)));
        assertNull(Arms.bestHunt(List.of()));
    }

    @Test
    void aFightPrefersDamagePerSecondAndKnowsCopper() {
        List<Material> weapons = Arms.meleeBestFirst();
        assertEquals(Material.NETHERITE_SWORD, weapons.getFirst());
        assertTrue(weapons.indexOf(Material.DIAMOND_SWORD) < weapons.indexOf(Material.NETHERITE_AXE));
        assertTrue(weapons.contains(Material.COPPER_SWORD));
        assertTrue(weapons.contains(Material.COPPER_AXE));
        assertTrue(Arms.meleeDps(Material.COPPER_SWORD) > Arms.meleeDps(Material.STONE_AXE));
        assertEquals(0, Arms.meleeDps(Material.IRON_PICKAXE));
    }
}
