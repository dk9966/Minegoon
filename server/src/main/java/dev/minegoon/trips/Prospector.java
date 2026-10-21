package dev.minegoon.trips;

import dev.minegoon.actions.ActionFailed;
import dev.minegoon.actions.Checkpoint;
import dev.minegoon.actions.Mover;
import dev.minegoon.jobs.Step;
import dev.minegoon.knowledge.Knowledge;
import dev.minegoon.knowledge.Knowledge.Find;
import dev.minegoon.knowledge.Knowledge.SearchOrders;
import dev.minegoon.knowledge.Knowledge.Way;
import dev.minegoon.knowledge.Lessons;
import dev.minegoon.world.Goal;
import dev.minegoon.world.Pos;
import dev.minegoon.world.Structures;
import dev.minegoon.world.Vision;
import dev.minegoon.world.WorldView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.EntityType;

/**
 * Takes the helper somewhere worth looking from, one move at a time, using the
 * moves the knowledge lists for the thing it wants. After the last move it
 * walks toward ground it has not seen and starts the list again, so a search
 * never runs out. The ground it has already looked at, and the places it has
 * gone to look from, are remembered for the whole session.
 */
public final class Prospector {

    private static final int[][] COMPASS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
    private static final int FRONTIER = 48;
    private static final int BEEN_THERE = 8;
    /** Ending a walk this close to a place to look from is as good as getting there. */
    private static final int CLOSE_ENOUGH = 8;
    private static final int UNREACHED_NEAR = 3;
    private static final int MISSES_HERE = 3;
    private static final int PEAK_ABOVE = 10;
    /** How far above the best height a cave is still worth walking through. */
    private static final int CAVERN_ABOVE = 16;
    /** How far a structure seen before is worth walking back to. */
    private static final int REMEMBERED_WITHIN = 1024;
    private static final int BURIED = 8;
    private static final int STAIRS = 16;
    private static final int TUNNEL = 16;
    private static final int TUNNELS_PER_PASS = 4;
    private static final int TARGETS = 8;
    private static final int DRY_PASSES = 2;
    /** The knowledge entry for ground a seed can be planted in. */
    public static final String SOIL = "soil";

    private final Knowledge knowledge;
    private final Lessons lessons;
    private final Mover mover;
    private final Vision vision;
    private final Structures structures;
    private final Tunneler tunneler;
    private final String who;
    private final Logger log;
    private final Set<Long> seen = new HashSet<>();
    private final Map<String, List<Pos>> visits = new HashMap<>();
    private int heading = -1;
    private SearchOrders orders = SearchOrders.NONE;

    public Prospector(Knowledge knowledge, Lessons lessons, Mover mover, Vision vision, Structures structures,
                      Tunneler tunneler, String who, Logger log) {
        this.knowledge = knowledge;
        this.lessons = lessons;
        this.mover = mover;
        this.vision = vision;
        this.structures = structures;
        this.tunneler = tunneler;
        this.who = who;
        this.log = log;
    }

    /**
     * A search came up empty {@link #DRY_PASSES} times over. Not the end: the
     * foreman can try another way to get the item, or ask for advice, and look again.
     */
    public static final class Dry extends ActionFailed {
        public Dry(String message) {
            super(message);
        }
    }

    /** The search for what a step is after, named the way a player would say it: iron ore, cow. */
    public Search search(Step step) {
        List<String> names = subjects(step);
        List<String> spoken = names.stream().map(knowledge::canonical).distinct()
            .map(name -> name.replace('_', ' ')).toList();
        return search(spoken.isEmpty() ? step.describe() : String.join(" or ", spoken), names);
    }

    public Search search(String what, List<String> names) {
        String subject = names.isEmpty() ? what : knowledge.canonical(names.getFirst());
        return new Search(what, orders.over(lessons.apply(subject, knowledge.find(names))));
    }

    /** What the player asked for about how to search, until the task ends. */
    public void orders(SearchOrders orders) {
        this.orders = orders;
    }

    /** The knowledge entry that speaks for a step, or null for steps that do not search. */
    public String subject(Step step) {
        List<String> names = subjects(step);
        return names.isEmpty() ? null : knowledge.canonical(names.getFirst());
    }

    /** What a step looks for, in the game's names. */
    public static List<String> subjects(Step step) {
        return switch (step) {
            case Step.Gather gather -> gather.blocks().stream().sorted().map(block -> block.getKey().value()).toList();
            case Step.Hunt hunt -> hunt.mobs().stream().sorted().map(Prospector::key).toList();
            case Step.Fill fill -> List.of(fill.fluid().getKey().value());
            case Step.Grow grow -> List.of(SOIL);
            case Step.Meal meal -> Gatherer.MEALS.stream().map(Prospector::key).toList();
            default -> List.of();
        };
    }

    public static String key(EntityType type) {
        return type.getKey().value();
    }

    /**
     * One search: where it is in the list of moves. Where it has already gone to
     * look from is shared with every earlier search for the same thing, so a
     * search started over does not walk back to the same places.
     */
    public final class Search {
        private final String what;
        private final Find find;
        private final List<Pos> been;
        private final List<Pos> unreached = new ArrayList<>();
        private int missesHere;
        private int rung;
        private int movesOnRung;
        private int dryPasses;
        private int places;

        private Search(String what, Find find) {
            this.what = what;
            this.find = find;
            this.been = visits.computeIfAbsent(what, key -> new ArrayList<>());
        }

        /** Places looked from so far. */
        public int places() {
            return places;
        }

        /** Something turned up, so the search is not dry. */
        public void found() {
            dryPasses = 0;
            missesHere = 0;
        }

        /**
         * A target in sight the helper could not get to. Its neighbours are
         * likely out of reach too, such as the rest of a lake, so they are
         * passed over as well. After a few misses in a row, it looks from
         * somewhere else.
         */
        public void missed(Pos target, Checkpoint checkpoint) {
            unreached.add(target);
            if (++missesHere >= MISSES_HERE) {
                missesHere = 0;
                next(checkpoint);
            }
        }

        /** Not next to a target the helper already failed to get to. */
        public boolean reachable(Pos target) {
            return unreached.stream().noneMatch(pos -> pos.distanceSquared(target) <= UNREACHED_NEAR * UNREACHED_NEAR);
        }

        /**
         * Moves to the next place worth looking from. Throws {@link Dry} once every
         * move came up empty {@link #DRY_PASSES} times in a row.
         */
        public void next(Checkpoint checkpoint) {
            if (dryPasses >= DRY_PASSES) {
                dryPasses = 0;
                throw new Dry("I found no " + what + " after looking from " + places + " places");
            }
            remember();
            while (rung < find.ways().size()) {
                Way way = find.ways().get(rung);
                if (movesOnRung < limit(way) && move(way, checkpoint)) {
                    movesOnRung++;
                    places++;
                    log.info(who + " looking for " + what + ": " + way.key() + " to " + mover.feet());
                    return;
                }
                rung++;
                movesOnRung = 0;
            }
            if (descend(checkpoint)) {
                places++;
                log.info(who + " looking for " + what + ": stairs to " + mover.feet());
                rung = 0;
                movesOnRung = 0;
                return;
            }
            frontier(checkpoint);
            log.info(who + " looking for " + what + ": new ground at " + mover.feet());
            places++;
            dryPasses++;
            rung = 0;
            movesOnRung = 0;
        }

        private int limit(Way way) {
            return switch (way) {
                case CLIFF, PEAK, ROAM, VISIT -> 3;
                case CAVE, CAVERN -> 6;
                case DIG -> TUNNELS_PER_PASS;
            };
        }

        private boolean move(Way way, Checkpoint checkpoint) {
            return switch (way) {
                case CLIFF -> walkToFirst(vision.cliffs(TARGETS).stream().map(Vision.Cliff::foot).toList(), checkpoint);
                case PEAK -> peak(checkpoint);
                case CAVE -> cave(checkpoint);
                case CAVERN -> cavern(checkpoint);
                case DIG -> dig(checkpoint);
                case ROAM -> roam(checkpoint);
                case VISIT -> visit(checkpoint);
            };
        }

        /** To a listed structure in sight, or else one seen before. */
        private boolean visit(Checkpoint checkpoint) {
            Set<String> wanted = Set.copyOf(find.visit());
            List<Structures.Sighting> sightings = structures.inSight(wanted);
            if (sightings.isEmpty()) {
                sightings = structures.remembered(wanted, mover.feet(), REMEMBERED_WITHIN);
            }
            return walkToFirst(sightings.stream().map(Structures.Sighting::at).toList(), checkpoint);
        }

        /**
         * Still well above the best height after the listed moves. Another
         * staircase, not a side tunnel: the ore is not up here.
         */
        private boolean descend(Checkpoint checkpoint) {
            if (find.best() == null || !tunneler.armed()) {
                return false;
            }
            Pos feet = mover.feet();
            if (feet.y() <= find.best() + BURIED) {
                return false;
            }
            return tunneler.down(Math.max(find.best(), feet.y() - STAIRS), STAIRS, checkpoint);
        }

        /**
         * Down into a cave in sight that is actually deeper, but not below where
         * the thing stops showing up. A shallow mouth does not count: with no
         * deep opening, stairs go toward the best height instead of wandering
         * the surface. Far below the best height, stairs go back up to it.
         * That is a staircase, not a strip mine.
         */
        private boolean cave(Checkpoint checkpoint) {
            Pos feet = mover.feet();
            if (find.best() != null && feet.y() < find.best() - CAVERN_ABOVE && tunneler.armed()
                    && tunneler.up(Math.min(find.best(), feet.y() + STAIRS), STAIRS, checkpoint)) {
                return true;
            }
            if (find.best() != null && feet.y() <= find.best() + 2) {
                return false;
            }
            List<Pos> openings = vision.caves(feet.y() - 3, TARGETS).stream()
                .filter(pos -> pos.y() >= find.low())
                .toList();
            List<Pos> deeper = openings.stream().filter(pos -> pos.y() <= feet.y() - 8).toList();
            if (walkToFirst(deeper, checkpoint)) {
                return true;
            }
            if (find.best() != null && feet.y() > find.best() + 8 && tunneler.armed()
                && tunneler.down(Math.max(find.best(), feet.y() - STAIRS), STAIRS, checkpoint)) {
                return true;
            }
            return walkToFirst(openings, checkpoint);
        }

        /** High ground, while still on the surface. Climbing back out of a hole does not find more ore. */
        private boolean peak(Checkpoint checkpoint) {
            Pos feet = mover.feet();
            if (surface(feet) - feet.y() > BURIED) {
                return false;
            }
            if (find.best() != null && feet.y() <= find.best() + CAVERN_ABOVE) {
                return false;
            }
            return walkToFirst(vision.peaks(feet.y() + PEAK_ABOVE, TARGETS), checkpoint);
        }

        /**
         * Through the cave the helper is in, to the floor in sight furthest from
         * every place it has looked from. Only a large cave lets the eyes reach
         * far, and its far end shows the most new wall. It does not climb above
         * where it stands unless that is still close to the best height.
         */
        private boolean cavern(Checkpoint checkpoint) {
            Pos feet = mover.feet();
            if (find.best() == null || surface(feet) - feet.y() <= BURIED) {
                return false;
            }
            List<Pos> lookouts = lookouts();
            int high = Math.max(feet.y(), find.best() + CAVERN_ABOVE);
            return walkToFirst(vision.floors(find.low(), high, at -> nearest(lookouts, at), TARGETS), checkpoint);
        }

        /** Stairs down to the best height, then tunnels there. Not by hand. */
        private boolean dig(Checkpoint checkpoint) {
            if (!tunneler.armed()) {
                return false;
            }
            int best = find.best() != null ? find.best() : mover.feet().y();
            if (mover.feet().y() > best) {
                return tunneler.down(best, STAIRS, checkpoint);
            }
            if (movesOnRung > 0) {
                tunneler.turn();
            }
            return tunneler.ahead(TUNNEL, checkpoint);
        }

        /**
         * To the nearest ground in sight of a listed biome that is half a sight
         * from every place already looked from, so the walk shows new ground,
         * also when the helper is already in that biome.
         */
        private boolean roam(Checkpoint checkpoint) {
            if (find.biomes().isEmpty()) {
                return false;
            }
            List<Pos> lookouts = lookouts();
            double apart = Math.pow(vision.radius() / 2.0, 2);
            List<Pos> patches = vision.patches().stream()
                .filter(patch -> find.biomes().contains(patch.biome()))
                .map(Vision.Patch::at)
                .filter(at -> nearest(lookouts, at) >= apart)
                .limit(TARGETS)
                .toList();
            return walkToFirst(patches, checkpoint);
        }

        /** Every place this search has looked from, and here. */
        private List<Pos> lookouts() {
            List<Pos> places = new ArrayList<>(been);
            places.add(mover.feet());
            return places;
        }

        private boolean walkToFirst(List<Pos> targets, Checkpoint checkpoint) {
            for (Pos target : targets) {
                if (visited(target)) {
                    continue;
                }
                been.add(target);
                if (mover.walk(Goal.near(target, 2), checkpoint)
                    || mover.feet().distanceSquared(target) <= CLOSE_ENOUGH * CLOSE_ENOUGH) {
                    return true;
                }
            }
            return false;
        }

        private boolean visited(Pos target) {
            return been.stream().anyMatch(pos -> pos.distanceSquared(target) <= BEEN_THERE * BEEN_THERE);
        }
    }

    /**
     * Toward the compass direction with the most ground not yet looked at,
     * keeping the last heading when it is as good. Underground with a pickaxe, a
     * tunnel. When no walk gets anywhere, stairs up to the surface, then a tunnel
     * that way: rock is never a dead end. Not {@link Dry}: the thing may be out
     * there, the helper just cannot move, and nobody's advice changes that.
     */
    private void frontier(Checkpoint checkpoint) {
        Pos feet = mover.feet();
        int surface = surface(feet);
        boolean buried = surface - feet.y() > BURIED;
        if (buried && tunneler.armed()) {
            tunneler.turn();
            if (tunneler.ahead(FRONTIER, checkpoint)) {
                return;
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < COMPASS.length; i++) {
            order.add(i);
        }
        order.sort((a, b) -> Integer.compare(unseen(feet, b) + (b == heading ? 1 : 0),
            unseen(feet, a) + (a == heading ? 1 : 0)));
        for (int direction : order) {
            int[] step = COMPASS[direction];
            int distance = step[0] != 0 && step[1] != 0 ? (int) Math.round(FRONTIER * Math.sqrt(2)) : FRONTIER;
            if (mover.walk(Goal.away(feet, step[0], step[1], distance), checkpoint)) {
                heading = direction;
                remember();
                return;
            }
        }
        if (buried && tunneler.up(surface, surface - feet.y(), checkpoint)) {
            remember();
            return;
        }
        for (int direction : order) {
            int[] step = COMPASS[direction];
            if (tunneler.toward(step[0], step[1], FRONTIER, checkpoint)) {
                heading = direction;
                remember();
                return;
            }
        }
        throw new ActionFailed("I can't find a way out of here to new ground");
    }

    /** The first open block over the ground above the feet. */
    private int surface(Pos feet) {
        return ground(vision.around(), feet.x(), feet.z()) + 1;
    }

    /** Squared distance from a spot to the nearest of some places. */
    private static double nearest(List<Pos> places, Pos at) {
        return places.stream().mapToDouble(at::distanceSquared).min().orElse(Double.MAX_VALUE);
    }

    /** The top block of the ground in a column. Under a canopy of leaves and logs the helper is not buried. */
    private static int ground(WorldView view, int x, int z) {
        int y = view.highest(x, z);
        while (y > view.minY() && !earth(view.type(x, y, z))) {
            y--;
        }
        return y;
    }

    private static boolean earth(Material type) {
        return type.isSolid() && !Tag.LEAVES.isTagged(type) && !Tag.LOGS.isTagged(type);
    }

    /** Chunks in a direction, out past what is in sight now, that the helper has not looked at. */
    private int unseen(Pos feet, int direction) {
        int[] step = COMPASS[direction];
        int count = 0;
        int far = vision.radius() / 16 + 6;
        for (int reach = 2; reach <= far; reach++) {
            for (int side = -2; side <= 2; side++) {
                int cx = (feet.x() >> 4) + step[0] * reach - step[1] * side;
                int cz = (feet.z() >> 4) + step[1] * reach + step[0] * side;
                if (!seen.contains(WorldView.key(cx, cz))) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Forgets the ground looked at, for a world that was replaced. */
    public void forget() {
        seen.clear();
        visits.clear();
        heading = -1;
    }

    /** Marks every chunk in sight as looked at. */
    private void remember() {
        Pos feet = mover.feet();
        int reach = vision.radius() >> 4;
        for (int cx = -reach; cx <= reach; cx++) {
            for (int cz = -reach; cz <= reach; cz++) {
                if (cx * cx + cz * cz <= reach * reach) {
                    seen.add(WorldView.key((feet.x() >> 4) + cx, (feet.z() >> 4) + cz));
                }
            }
        }
    }
}
