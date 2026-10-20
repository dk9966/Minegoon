package dev.minegoon.world.path;

import static dev.minegoon.world.Traits.PASSABLE;
import static dev.minegoon.world.Traits.REPLACEABLE;
import static dev.minegoon.world.Traits.WATER;
import static dev.minegoon.world.path.Grid.AIR;
import static dev.minegoon.world.path.Grid.STONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.minegoon.world.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

class AirTest {

    private static final int POOL = PASSABLE | WATER | REPLACEABLE;
    private static final Pos DEEP = new Pos(0, -5, 0);

    @Test
    void risesStraightUpThroughOpenWater() {
        List<Pos> way = Air.way(pool(), DEEP);
        assertEquals(List.of(new Pos(0, -4, 0), new Pos(0, -3, 0), new Pos(0, -2, 0), new Pos(0, -1, 0)), way);
    }

    @Test
    void needsNoSwimWhenTheHeadIsOut() {
        assertTrue(Air.way(pool(), new Pos(0, -1, 0)).isEmpty());
    }

    @Test
    void swimsUnderALidToTheOpening() {
        List<Pos> way = Air.way(pool().fill(-3, -1, -3, 2, -1, 3, STONE), DEEP);
        assertEquals(new Pos(3, -1, 0), way.getLast());
        assertEquals(7, way.size());
    }

    @Test
    void findsAPocketOfAirUnderTheLid() {
        Grid grid = pool().fill(-3, -1, -3, 3, -1, 3, STONE).fill(-2, -2, 0, -2, -2, 0, AIR);
        assertEquals(new Pos(-2, -3, 0), Air.way(grid, DEEP).getLast());
    }

    @Test
    void hasNoWayWhenTheWaterIsSealed() {
        assertNull(Air.way(pool().fill(-3, -1, -3, 3, -1, 3, STONE), DEEP));
    }

    private static Grid pool() {
        return new Grid().fill(-3, -6, -3, 3, -1, 3, POOL);
    }
}
