package dev.minegoon.world.path;

import static dev.minegoon.world.Traits.CLIMBABLE;
import static dev.minegoon.world.Traits.HARMFUL;
import static dev.minegoon.world.Traits.LAVA;
import static dev.minegoon.world.Traits.PASSABLE;
import static dev.minegoon.world.Traits.REPLACEABLE;
import static dev.minegoon.world.Traits.WATER;
import static dev.minegoon.world.path.Grid.AIR;
import static dev.minegoon.world.path.Grid.STONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minegoon.world.Goal;
import dev.minegoon.world.Pos;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PathfinderTest {

    private static final int POOL = PASSABLE | WATER | REPLACEABLE;
    private static final int MAGMA = LAVA | HARMFUL | REPLACEABLE;
    private static final int LADDER = PASSABLE | CLIMBABLE;
    private static final int VINE = PASSABLE | CLIMBABLE | REPLACEABLE;
    private static final Pos START = new Pos(0, 0, 0);
    private static final Kit BLOCKS = new Kit(64, false, true);

    @Test
    void sprintsAcrossFlatGround() {
        Path path = find(new Grid(), Kit.BARE, new Pos(10, 0, 0));
        assertTrue(path.complete());
        assertEquals(List.of(Move.TRAVERSE), moves(path));
        assertEquals(10 * Costs.SPRINT, path.cost(), 1e-6);
    }

    @Test
    void cutsCornersDiagonally() {
        Path path = find(new Grid(), Kit.BARE, new Pos(6, 0, 6));
        assertEquals(List.of(Move.DIAGONAL), moves(path));
        assertEquals(6, path.steps().size());
    }

    @Test
    void jumpsUpAStepAndWalksOffTheOtherSide() {
        Grid grid = new Grid().fill(3, 0, -10, 3, 0, 10, STONE);
        Path path = find(grid, Kit.BARE, new Pos(6, 0, 0));
        assertTrue(moves(path).containsAll(List.of(Move.ASCEND, Move.DESCEND)));
    }

    @Test
    void pillarsUpACliffOnlyWithBlocksToPlace() {
        Grid grid = new Grid().fill(3, 0, -40, 40, 3, 40, STONE);
        Pos top = new Pos(5, 4, 0);
        assertFalse(reaches(find(grid, Kit.BARE, top)));
        Path path = find(grid, BLOCKS, top);
        assertTrue(reaches(path));
        assertTrue(moves(path).contains(Move.PILLAR));
    }

    @Test
    void climbsALadder() {
        Grid grid = new Grid().fill(3, 0, -40, 40, 5, 40, STONE).fill(2, 0, 0, 2, 5, 0, LADDER);
        Path path = find(grid, Kit.BARE, new Pos(4, 6, 0));
        assertTrue(path.complete());
        assertTrue(moves(path).contains(Move.CLIMB_UP));
    }

    @Test
    void neverClimbsDownThroughABlockItPlaced() {
        Grid grid = new Grid().fill(2, -20, -40, 8, -1, 40, AIR).fill(2, -20, 0, 2, -1, 0, VINE);
        Path path = find(grid, BLOCKS, new Pos(2, -20, 0));
        Set<Pos> placed = new HashSet<>();
        for (Step step : path.steps()) {
            assertFalse(placed.contains(step.to()), "goes into its own block at " + step.to());
            if (step.floor() != null) {
                placed.add(step.floor());
            }
        }
    }

    @Test
    void jumpsAGapItCouldSurviveFallingInto() {
        Grid grid = new Grid().fill(2, -3, -40, 4, -1, 40, AIR);
        Path path = find(grid, Kit.BARE, new Pos(8, 0, 0));
        assertTrue(moves(path).contains(Move.PARKOUR));
        assertTrue(path.steps().stream().allMatch(step -> step.to().y() == 0));
    }

    @Test
    void bridgesARavineItCannotJump() {
        Grid grid = new Grid().fill(2, -20, -40, 8, -1, 40, AIR);
        Pos across = new Pos(10, 0, 0);
        assertFalse(reaches(find(grid, Kit.BARE, across)));
        Path path = find(grid, BLOCKS, across);
        assertTrue(reaches(path));
        assertTrue(path.steps().stream().allMatch(step -> step.to().y() == 0));
    }

    @Test
    void neverStepsIntoLava() {
        Grid grid = new Grid().fill(2, -1, -3, 6, -1, 3, MAGMA);
        Path path = find(grid, Kit.BARE, new Pos(8, 0, 0));
        assertTrue(path.complete());
        for (Step step : path.steps()) {
            Pos to = step.to();
            assertFalse(to.x() >= 2 && to.x() <= 6 && Math.abs(to.z()) <= 3, "stood over lava at " + to);
        }
    }

    @Test
    void swimsWithTheHeadOut() {
        Grid grid = new Grid().fill(2, -4, -40, 8, 0, 40, POOL);
        Path path = find(grid, Kit.BARE, new Pos(10, 0, 0));
        assertTrue(path.complete());
        assertTrue(moves(path).contains(Move.SWIM));
        assertTrue(path.steps().stream().allMatch(step -> step.to().y() >= 0));
    }

    @Test
    void dropsOnlyAsFarAsIsSafeUnlessItCarriesABucket() {
        Grid grid = new Grid().fill(1, -10, -40, 40, -1, 40, AIR);
        Pos below = new Pos(4, -10, 0);
        assertFalse(reaches(find(grid, Kit.BARE, below)));
        Path path = find(grid, new Kit(0, true, true), below);
        assertTrue(reaches(path));
        assertEquals(Move.FALL, path.steps().getFirst().move());
    }

    @Test
    void digsThroughAWallItCannotGoAround() {
        Grid grid = new Grid().fill(4, 0, -40, 5, 2, 40, STONE).digging(8);
        Path path = find(grid, Kit.BARE, new Pos(8, 0, 0));
        assertTrue(path.complete());
        assertTrue(path.steps().stream().allMatch(step -> Math.abs(step.to().z()) <= 1));
    }

    @Test
    void returnsTheWayPartWhenTheGoalIsOutOfSight() {
        Path path = find(new Grid(), Kit.BARE, new Pos(500, 0, 0));
        assertNotNull(path);
        assertFalse(path.complete());
        assertTrue(path.end().x() > 20);
    }

    private static Path find(Grid grid, Kit kit, Pos goal) {
        return Pathfinder.find(grid, kit, START, Goal.near(goal, 0.5), Set.of(), 200);
    }

    private static boolean reaches(Path path) {
        return path != null && path.complete();
    }

    private static List<Move> moves(Path path) {
        return path.steps().stream().map(Step::move).distinct().toList();
    }
}
