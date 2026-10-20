package dev.minegoon.world.path;

import static dev.minegoon.world.Traits.PASSABLE;
import static dev.minegoon.world.Traits.WATER;
import static dev.minegoon.world.Traits.has;

import dev.minegoon.world.Pos;
import dev.minegoon.world.Terrain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shortest swim from feet whose head is under water to feet whose head is
 * out of it, through cells the whole body fits in. Nothing is dug: a body short
 * of air has no time for that.
 */
public final class Air {

    /** Farther than this from the start, along the three axes, is not looked at. */
    static final int REACH = 12;
    /** Up first, so of two ways the same length the one with less turning is found. */
    private static final int[][] SIDES = {{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};

    private Air() {
    }

    /**
     * The feet positions to swim through, ending at one where the head breathes.
     * Empty when the head breathes already; null when no air is within reach.
     */
    public static List<Pos> way(Terrain terrain, Pos feet) {
        Map<Pos, Pos> came = new HashMap<>();
        Deque<Pos> open = new ArrayDeque<>();
        came.put(feet, feet);
        open.add(feet);
        while (!open.isEmpty()) {
            Pos at = open.poll();
            if (breathes(terrain, at)) {
                return back(came, feet, at);
            }
            for (int[] side : SIDES) {
                Pos next = at.offset(side[0], side[1], side[2]);
                if (next.manhattan(feet) <= REACH && fits(terrain, next) && came.putIfAbsent(next, at) == null) {
                    open.add(next);
                }
            }
        }
        return null;
    }

    /** Ticks the swim takes. */
    public static double ticks(List<Pos> way) {
        return way.size() * Costs.WATER;
    }

    private static boolean breathes(Terrain terrain, Pos feet) {
        int head = terrain.traits(feet.x(), feet.y() + 1, feet.z());
        return has(head, PASSABLE) && !has(head, WATER);
    }

    private static boolean fits(Terrain terrain, Pos feet) {
        return has(terrain.traits(feet.x(), feet.y(), feet.z()), PASSABLE)
            && has(terrain.traits(feet.x(), feet.y() + 1, feet.z()), PASSABLE);
    }

    private static List<Pos> back(Map<Pos, Pos> came, Pos start, Pos end) {
        List<Pos> way = new ArrayList<>();
        for (Pos at = end; !at.equals(start); at = came.get(at)) {
            way.add(at);
        }
        Collections.reverse(way);
        return way;
    }
}
