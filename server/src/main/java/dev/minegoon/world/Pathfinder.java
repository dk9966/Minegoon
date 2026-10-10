package dev.minegoon.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over feet positions, costed in game ticks. The body walks, steps up one
 * block, steps down one block, or drops up to three, and may dig its way through
 * if {@link Clearance} allows. Going down always happens as a staircase, never a
 * straight drop, so the same route works for coming back up.
 */
public final class Pathfinder {

    public static final double WALK = 1 / 0.2158;
    private static final double JUMP = 2;
    private static final double FALL = 1;
    private static final double DIG_GUESS = 8;
    private static final double WATER = 2 * WALK;
    private static final double GREED = 1.5;
    private static final int MAX_FALL = 3;
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Feet positions from start to end. {@code complete} is false when only the closest reachable point was found. */
    public record Path(List<Pos> steps, boolean complete) {
        public Pos end() {
            return steps.getLast();
        }
    }

    private static final class Node implements Comparable<Node> {
        final Pos pos;
        final double estimate;
        double cost;
        Node parent;
        boolean closed;

        Node(Pos pos, double estimate) {
            this.pos = pos;
            this.estimate = estimate;
        }

        double score() {
            return cost + GREED * estimate;
        }

        @Override
        public int compareTo(Node other) {
            return Double.compare(score(), other.score());
        }
    }

    private final BlockReader world;
    private final Goal goal;
    private final Clearance clearance;
    private final Map<Long, Node> nodes = new HashMap<>();
    private final PriorityQueue<Node> open = new PriorityQueue<>();

    private Pathfinder(BlockReader world, Goal goal, Clearance clearance) {
        this.world = world;
        this.goal = goal;
        this.clearance = clearance;
    }

    /** Returns null when not a single step toward the goal is possible. */
    public static Path find(BlockReader world, Pos start, Goal goal, Clearance clearance, int budget) {
        return new Pathfinder(world, goal, clearance).search(start, budget);
    }

    static double estimate(Pos from, Pos to) {
        return WALK * (Math.abs(from.x() - to.x()) + Math.abs(from.z() - to.z()))
            + DIG_GUESS * Math.abs(from.y() - to.y());
    }

    private Path search(Pos start, int budget) {
        Node first = node(start);
        first.cost = 0;
        open.add(first);
        Node best = first;
        for (int expanded = 0; !open.isEmpty() && expanded < budget; expanded++) {
            Node current = open.poll();
            if (current.closed) {
                continue;
            }
            current.closed = true;
            if (goal.reached(world, current.pos)) {
                return new Path(trace(current), true);
            }
            if (current.estimate < best.estimate) {
                best = current;
            }
            expand(current);
        }
        return best == first ? null : new Path(trace(best), false);
    }

    private void expand(Node from) {
        Pos p = from.pos;
        for (int[] direction : DIRECTIONS) {
            int x = p.x() + direction[0];
            int z = p.z() + direction[1];
            int y = p.y();

            if (ground(x, y - 1, z)) {
                offer(from, x, y, z, WALK + clear(x, y, z) + clear(x, y + 1, z));
            }
            if (ground(x, y, z)) {
                offer(from, x, y + 1, z,
                    WALK + JUMP + clear(p.x(), y + 2, p.z()) + clear(x, y + 1, z) + clear(x, y + 2, z));
            }
            if (ground(x, y - 2, z)) {
                offer(from, x, y - 1, z, WALK + clear(x, y + 1, z) + clear(x, y, z) + clear(x, y - 1, z));
            }
            dropFrom(from, x, y, z);
        }
    }

    /** Walk off an edge and fall two or three blocks without digging. */
    private void dropFrom(Node from, int x, int y, int z) {
        if (!passable(x, y + 1, z) || !passable(x, y, z) || !passable(x, y - 1, z)) {
            return;
        }
        for (int depth = 2; depth <= MAX_FALL; depth++) {
            if (!passable(x, y - depth, z)) {
                return;
            }
            if (ground(x, y - depth - 1, z)) {
                offer(from, x, y - depth, z, WALK + depth * FALL);
                return;
            }
        }
    }

    private void offer(Node from, int x, int y, int z, double stepCost) {
        if (stepCost == Clearance.IMPOSSIBLE) {
            return;
        }
        if (Blocks.water(world.type(x, y, z)) || Blocks.water(world.type(x, y + 1, z))) {
            stepCost += WATER;
        }
        Node next = node(new Pos(x, y, z));
        double cost = from.cost + stepCost;
        if (!next.closed && cost < next.cost) {
            next.cost = cost;
            next.parent = from;
            open.add(next);
        }
    }

    private Node node(Pos pos) {
        return nodes.computeIfAbsent(pos.key(), key -> {
            Node node = new Node(pos, goal.estimate(pos));
            node.cost = Double.POSITIVE_INFINITY;
            return node;
        });
    }

    private boolean ground(int x, int y, int z) {
        return Blocks.ground(world.type(x, y, z));
    }

    private boolean passable(int x, int y, int z) {
        return Blocks.passable(world.type(x, y, z));
    }

    private double clear(int x, int y, int z) {
        return clearance.cost(world, x, y, z);
    }

    private static List<Pos> trace(Node end) {
        List<Pos> steps = new ArrayList<>();
        for (Node node = end; node != null; node = node.parent) {
            steps.add(node.pos);
        }
        Collections.reverse(steps);
        return steps;
    }
}
