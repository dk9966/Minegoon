package dev.minegoon.world.path;

import static dev.minegoon.world.Traits.PASSABLE;
import static dev.minegoon.world.Traits.REPLACEABLE;
import static dev.minegoon.world.Traits.STANDABLE;
import static dev.minegoon.world.Traits.UNKNOWN;

import dev.minegoon.world.BlockReader;
import dev.minegoon.world.Clearance;
import dev.minegoon.world.Pos;
import dev.minegoon.world.Terrain;
import java.util.HashMap;
import java.util.Map;

/** Stone below y 0 and air above, 40 blocks each way, and nothing known beyond. */
final class Grid implements Terrain {

    static final int AIR = PASSABLE | REPLACEABLE;
    static final int STONE = STANDABLE;
    private static final int SIZE = 40;
    private final Map<Pos, Integer> cells = new HashMap<>();
    private double digTicks = Clearance.IMPOSSIBLE;

    Grid fill(int x1, int y1, int z1, int x2, int y2, int z2, int traits) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    cells.put(new Pos(x, y, z), traits);
                }
            }
        }
        return this;
    }

    Grid digging(double ticks) {
        digTicks = ticks;
        return this;
    }

    @Override
    public int traits(int x, int y, int z) {
        if (Math.abs(x) > SIZE || Math.abs(z) > SIZE || y < -30 || y > 30) {
            return UNKNOWN;
        }
        return cells.getOrDefault(new Pos(x, y, z), y < 0 ? STONE : AIR);
    }

    @Override
    public double breakTicks(int x, int y, int z) {
        return digTicks;
    }

    @Override
    public BlockReader blocks() {
        return null;
    }
}
