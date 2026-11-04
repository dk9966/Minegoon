package dev.minegoon.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minegoon.ItemSet;
import dev.minegoon.jobs.Step;
import dev.minegoon.knowledge.Knowledge.Offer;
import dev.minegoon.knowledge.Notices.Sight;
import dev.minegoon.knowledge.Notices.Spot;
import dev.minegoon.knowledge.Rulings.Ruling;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

class NoticesTest {

    private static final List<Offer> OFFERS = List.of(
        new Offer("village", "structure", List.of("wheat", "carrot", "potato", "beetroot"),
            List.of("food", "wheat", "bread"), "harvest", 9, "script", ""),
        new Offer("iron_golem", "mob", List.of("iron_ingot"),
            List.of("iron_ingot", "raw_iron", "iron_pickaxe"), "hunt", 3, "ask", "It hits for 15."));

    private static final Sight NOWHERE = new Sight(Set.of(), Map.of());

    @Test
    void aVillageIsHarvestedWhenFoodIsShort() {
        Spot spot = notice(Set.of("food"), new Sight(Set.of("village"), Map.of()), Set.of(), Set.of()).orElseThrow();
        Notices.Spot.Act act = assertInstanceOf(Notices.Spot.Act.class, spot);
        assertEquals("village", act.offer());
        assertFalse(act.remembered());
        Step.Gather gather = assertInstanceOf(Step.Gather.class, act.step());
        assertTrue(gather.ripe());
        assertEquals(9, gather.count());
        assertTrue(gather.blocks().contains(Material.WHEAT));
        assertTrue(gather.blocks().contains(Material.CARROTS));
        assertTrue(act.say().contains("village"));
    }

    @Test
    void aFullPackWalksPastAVillage() {
        assertTrue(notice(Set.of("diamond"), new Sight(Set.of("village"), Map.of()), Set.of(), Set.of()).isEmpty());
    }

    @Test
    void theStepAlreadyHarvestingCropsIsLeftAlone() {
        Step.Gather wheat = new Step.Gather(ItemSet.of(Material.WHEAT), Set.of(Material.WHEAT), 4, true);
        assertTrue(Notices.busy(wheat).contains("wheat"));
        assertTrue(notice(Set.of("food"), new Sight(Set.of("village"), Map.of()), Notices.busy(wheat), Set.of())
            .isEmpty());
    }

    @Test
    void anOfferSettledThisTaskIsNotTakenAgain() {
        assertTrue(notice(Set.of("food"), new Sight(Set.of("village"), Map.of()), Set.of(), Set.of("village")).isEmpty());
    }

    @Test
    void aGolemIsAQuestionOnlyWhenTheFightIsWinnable() {
        Spot ask = notice(Set.of("iron_pickaxe"), new Sight(Set.of(), Map.of("iron_golem", true)), Set.of(), Set.of())
            .orElseThrow();
        Notices.Spot.Ask card = assertInstanceOf(Notices.Spot.Ask.class, ask);
        assertTrue(card.card().contains("hits for 15"));
        assertTrue(card.card().contains("iron_pickaxe"));
        Step.Hunt hunt = assertInstanceOf(Step.Hunt.class, card.step());
        assertTrue(hunt.mobs().contains(EntityType.IRON_GOLEM));
        assertEquals(3, hunt.count());

        assertTrue(notice(Set.of("iron_pickaxe"), new Sight(Set.of(), Map.of("iron_golem", false)), Set.of(), Set.of())
            .isEmpty());
    }

    @Test
    void aTakeThatWorkedBeforeIsDoneWithoutAsking() {
        Ruling kept = new Ruling("the sword was enough", 1, 0);
        Spot spot = notice(Set.of("iron_ingot"), new Sight(Set.of(), Map.of("iron_golem", true)), Set.of(), Set.of(),
            name -> Optional.of(kept)).orElseThrow();
        Notices.Spot.Act act = assertInstanceOf(Notices.Spot.Act.class, spot);
        assertTrue(act.remembered());
        assertTrue(act.say().contains("worked before"));
    }

    @Test
    void aRememberedFightIsSkippedWhenItWouldLose() {
        Ruling kept = new Ruling("the sword was enough", 1, 0);
        assertTrue(notice(Set.of("iron_ingot"), new Sight(Set.of(), Map.of("iron_golem", false)), Set.of(), Set.of(),
            name -> Optional.of(kept)).isEmpty());
    }

    @Test
    void cropsComeBeforeAFightWhenBothWouldHelp() {
        Sight both = new Sight(Set.of("village"), Map.of("iron_golem", true));
        Spot spot = notice(Set.of("food", "iron_pickaxe"), both, Set.of(), Set.of()).orElseThrow();
        assertEquals("village", spot.offer());
        assertInstanceOf(Notices.Spot.Act.class, spot);
    }

    @Test
    void nothingInSightChangesNothing() {
        assertTrue(notice(Set.of("food", "iron_pickaxe"), NOWHERE, Set.of(), Set.of()).isEmpty());
    }

    private static Optional<Spot> notice(Set<String> needs, Sight sight, Set<String> busy, Set<String> settled) {
        return notice(needs, sight, busy, settled, name -> Optional.empty());
    }

    private static Optional<Spot> notice(Set<String> needs, Sight sight, Set<String> busy, Set<String> settled,
                                         java.util.function.Function<String, Optional<Ruling>> rulings) {
        return Notices.spot(OFFERS, needs, sight, busy, settled, rulings, "health 20/20, food 18, iron_sword, shield");
    }
}
