package dev.minegoon.world.path;

import dev.minegoon.world.Goal;
import dev.minegoon.world.Pos;
import dev.minegoon.world.Terrain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* over feet positions with typed {@link Move}s costed in game ticks, the way
 * Baritone does it. A search is bounded by time. When it runs out before the
 * goal, it returns a partial route: of the nodes that would be best under a few
 * different weightings of distance-left against cost-so-far, the first one that
 * got meaningfully far from the start. Cells of a previous route cost half, so a
 * replan does not flip between two routes that are nearly as good.
 */
public final class Pathfinder {

    private static final double[] WEIGHTS = {1.5, 2, 2.5, 3, 4, 5, 10};
    private static final double MIN_PARTIAL = 5;
    private static final double FAVORED = 0.5;
    private static final double MIN_IMPROVEMENT = 0.01;
    private static final int MAX_NODES = 400_000;
    private static final int CLOCK_EVERY = 64;
    /** Keeps searching this many times longer while it has no partial route worth taking. */
    private static final int FAILURE_STRETCH = 4;

    private static final class Node {
        final Pos pos;
        final double estimate;
        double cost = Double.POSITIVE_INFINITY;
        Node parent;
        Move move;
        double stepCost;
        boolean onPlaced;

        Node(Pos pos, double estimate) {
            this.pos = pos;
            this.estimate = estimate;
        }
    }

    /** A node as it was when queued; stale once a cheaper way to the node is found. */
    private record Queued(Node node, double score) implements Comparable<Queued> {
        @Override
        public int compareTo(Queued other) {
            return Double.compare(score, other.score);
        }
    }

    private final Terrain terrain;
    private final Goal goal;
    private final Set<Pos> favored;
    private final Moves moves;
    private final Map<Long, Node> nodes = new HashMap<>();
    private final PriorityQueue<Queued> open = new PriorityQueue<>();

    private Pathfinder(Terrain terrain, Kit kit, Goal goal, Set<Pos> favored) {
        this.terrain = terrain;
        this.goal = goal;
        this.favored = favored;
        this.moves = new Moves(terrain, kit);
    }

    /** Returns null when not a single move toward the goal is possible. */
    public static Path find(Terrain terrain, Kit kit, Pos start, Goal goal, Set<Pos> favored, long millis) {
        return new Pathfinder(terrain, kit, goal, favored).search(start, millis);
    }

    private Path search(Pos start, long millis) {
        long began = System.nanoTime();
        Node first = node(start);
        first.cost = 0;
        open.add(new Queued(first, first.estimate));
        Node[] best = new Node[WEIGHTS.length];
        double[] bestScore = new double[WEIGHTS.length];
        Arrays.fill(best, first);
        Arrays.fill(bestScore, first.estimate);
        int expanded = 0;
        while (!open.isEmpty() && nodes.size() < MAX_NODES) {
            if (++expanded % CLOCK_EVERY == 0) {
                long spent = (System.nanoTime() - began) / 1_000_000;
                if (spent > millis * FAILURE_STRETCH || spent > millis && partial(start, best) != null) {
                    break;
                }
            }
            Queued queued = open.poll();
            Node current = queued.node();
            if (queued.score() > current.cost + current.estimate + MIN_IMPROVEMENT) {
                continue;
            }
            if (goal.reached(terrain.blocks(), current.pos)) {
                return trace(start, current, true);
            }
            for (int i = 0; i < WEIGHTS.length; i++) {
                double score = current.estimate + current.cost / WEIGHTS[i];
                if (score < bestScore[i] - MIN_IMPROVEMENT) {
                    bestScore[i] = score;
                    best[i] = current;
                }
            }
            Pos p = current.pos;
            moves.expand(p.x(), p.y(), p.z(), current.onPlaced,
                (move, x, y, z, stepCost, placed) -> offer(current, move, x, y, z, stepCost, placed));
        }
        Node partial = partial(start, best);
        if (partial == null) {
            partial = farthest(start, best);
        }
        return partial == first ? null : trace(start, partial, false);
    }

    private void offer(Node from, Move move, int x, int y, int z, double stepCost, boolean placed) {
        Pos pos = new Pos(x, y, z);
        if (favored.contains(pos)) {
            stepCost *= FAVORED;
        }
        Node next = node(pos);
        double cost = from.cost + stepCost;
        if (cost < next.cost - MIN_IMPROVEMENT) {
            next.cost = cost;
            next.parent = from;
            next.move = move;
            next.stepCost = stepCost;
            next.onPlaced = placed;
            open.add(new Queued(next, cost + next.estimate));
        }
    }

    /** The first weighting's best node that is at least {@link #MIN_PARTIAL} blocks from the start. */
    private static Node partial(Pos start, Node[] best) {
        for (Node node : best) {
            if (node.pos.distanceSquared(start) > MIN_PARTIAL * MIN_PARTIAL) {
                return node;
            }
        }
        return null;
    }

    private static Node farthest(Pos start, Node[] best) {
        Node farthest = best[0];
        for (Node node : best) {
            if (node.pos.distanceSquared(start) > farthest.pos.distanceSquared(start)) {
                farthest = node;
            }
        }
        return farthest;
    }

    private Node node(Pos pos) {
        return nodes.computeIfAbsent(pos.key(), key -> new Node(pos, goal.estimate(pos)));
    }

    private static Path trace(Pos start, Node end, boolean complete) {
        List<Step> steps = new ArrayList<>();
        for (Node node = end; node.parent != null; node = node.parent) {
            steps.add(new Step(node.move, node.parent.pos, node.pos, node.stepCost, node.onPlaced));
        }
        Collections.reverse(steps);
        return new Path(start, List.copyOf(steps), complete);
    }
}
