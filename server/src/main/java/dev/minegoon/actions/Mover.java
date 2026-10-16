package dev.minegoon.actions;

import static dev.minegoon.world.Traits.PASSABLE;
import static dev.minegoon.world.Traits.STANDABLE;
import static dev.minegoon.world.Traits.WATER;
import static dev.minegoon.world.Traits.has;

import dev.minegoon.spawn.FakePlayer;
import dev.minegoon.spawn.FakePlayer.Controls;
import dev.minegoon.world.BlockReader;
import dev.minegoon.world.Goal;
import dev.minegoon.world.Pos;
import dev.minegoon.world.Terrain;
import dev.minegoon.world.Traits;
import dev.minegoon.world.WorldView;
import dev.minegoon.world.path.Costs;
import dev.minegoon.world.path.Kit;
import dev.minegoon.world.path.Move;
import dev.minegoon.world.path.Path;
import dev.minegoon.world.path.Pathfinder;
import dev.minegoon.world.path.Step;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Takes the helper places on its own legs. A walk is planned in legs of about
 * {@link #LEG} blocks on snapshots of the world and driven by the
 * {@link PathExecutor}. The next leg is planned while the current one is still
 * being walked, so a long trek does not pause at each leg's end. A leg that
 * fails is planned again from wherever the body stopped, preferring the cells
 * of the route it was on.
 */
public final class Mover {

    private static final Logger LOG = Logger.getLogger("Minegoon");
    private static final long SEARCH_MILLIS = 500;
    private static final int LEG = 80;
    private static final int MARGIN = 20;
    /** Legs in a row that may end no closer before the walk gives up. */
    private static final int MAX_SETBACKS = 4;
    /** A leg must bring the goal at least this many ticks closer to count as progress. */
    private static final double PROGRESS = Costs.SPRINT;
    private static final int SETTLE_TICKS = 100;
    private static final int SURFACE_TICKS = 40;

    private final Body body;
    private final Miner miner;
    private final PathExecutor executor;

    public Mover(Body body, Miner miner, Placer placer) {
        this.body = body;
        this.miner = miner;
        this.executor = new PathExecutor(body, miner, placer);
    }

    /**
     * Returns false when the goal cannot be reached. A leg that ends no closer is a
     * setback; any number of legs is fine while they keep getting closer.
     */
    public boolean walk(Goal goal, Checkpoint checkpoint) {
        executor.reopen();
        Lookahead ahead = new Lookahead(goal);
        Set<Pos> favored = Set.of();
        int setbacks = 0;
        double closest = goal.estimate(feet());
        while (setbacks < MAX_SETBACKS) {
            surface(checkpoint);
            checkpoint.check();
            Pos start = settle();
            if (reached(goal, start)) {
                return true;
            }
            Path path = ahead.take(start);
            if (path == null) {
                path = plan(start, goal, favored);
            }
            if (path != null) {
                Path walking = path;
                Runnable planNext = walking.complete() ? null : () -> ahead.begin(walking.end(), cells(walking));
                if (!executor.follow(walking, checkpoint, planNext)) {
                    favored = cells(walking);
                }
            }
            double left = goal.estimate(feet());
            if (left < closest - PROGRESS) {
                closest = left;
                setbacks = 0;
            } else {
                setbacks++;
            }
        }
        Pos end = settle();
        boolean reached = reached(goal, end);
        if (!reached) {
            String ground = body.main().call(() -> {
                BlockReader live = body.live();
                return live.type(end.x(), end.y(), end.z()) + " over " + live.type(end.x(), end.y() - 1, end.z());
            });
            LOG.info(body.helper().name() + " walk: no closer after " + MAX_SETBACKS + " legs, at " + end
                + " in " + ground + " going to " + goal.focus());
        }
        return reached;
    }

    /** One step to a neighbouring cell, digging what is in the way. False if it is not safe to stand there. */
    public boolean stepTo(Pos to) {
        Pos from = settle();
        int rise = to.y() - from.y();
        if (Math.abs(to.x() - from.x()) + Math.abs(to.z() - from.z()) != 1 || Math.abs(rise) > 1
            || !body.main().call(() -> safeToStand(body.live(), to))) {
            return false;
        }
        Move move = rise == 0 ? Move.TRAVERSE : rise > 0 ? Move.ASCEND : Move.DESCEND;
        return follow(new Step(move, from, to, Costs.WALK, false)) && feet().equals(to);
    }

    /**
     * Digs out the block underfoot and drops onto the one below it, the way off
     * a pillar with a fall on every side. False when there is no ground to land
     * on, or the helper ends up no lower.
     */
    public boolean digDown() {
        Pos feet = settle();
        Pos under = feet.offset(0, -1, 0);
        boolean diggable = body.main().call(() -> {
            BlockReader live = body.live();
            return !has(traits(live, under), PASSABLE) && has(traits(live, feet.offset(0, -2, 0)), STANDABLE);
        });
        return diggable && follow(new Step(Move.DOWNWARD, feet, under, Costs.fall(1), false)) && settle().y() < feet.y();
    }

    public Pos feet() {
        return body.main().call(() -> located(body.player()));
    }

    /** The feet block. A body bobbing just under the surface counts as swimming at it. Main thread. */
    private Pos located(Player player) {
        BlockReader live = body.live();
        if (!player.isInWater()) {
            return Footing.of(player, live);
        }
        Pos feet = Pos.standing(player.getLocation());
        Pos above = feet.offset(0, 1, 0);
        int over = traits(live, above.offset(0, 1, 0));
        boolean bobbing = has(traits(live, feet), WATER) && has(traits(live, above), WATER)
            && has(over, PASSABLE) && !has(over, WATER);
        return bobbing ? above : feet;
    }

    /** Waits for the body to come to rest on ground, in water, or on a ladder, falling the way anyone falls. */
    public Pos settle() {
        for (int tick = 0; tick < SETTLE_TICKS; tick++) {
            Pos feet = body.main().tick(() -> {
                Player player = body.player();
                boolean settled = FakePlayer.grounded(player) || player.isInWater() || FakePlayer.climbing(player);
                return settled ? located(player) : null;
            });
            if (feet != null) {
                return feet;
            }
        }
        return feet();
    }

    private boolean follow(Step step) {
        return executor.follow(new Path(step.from(), List.of(step), true), Checkpoint.NONE, null);
    }

    private boolean reached(Goal goal, Pos feet) {
        return body.main().call(() -> goal.reached(body.live(), feet));
    }

    private Path plan(Pos start, Goal goal, Set<Pos> favored) {
        World world = body.main().call(body::world);
        Pos focus = clamp(start, goal.focus());
        WorldView view = WorldView.capture(body.main(), world,
            Math.min(start.x(), focus.x()) - MARGIN, Math.min(start.z(), focus.z()) - MARGIN,
            Math.max(start.x(), focus.x()) + MARGIN, Math.max(start.z(), focus.z()) + MARGIN);
        record Means(Digging digging, Kit kit) {
        }
        Means means = body.main().call(() -> {
            Player player = body.player();
            Kit kit = new Kit(Placer.throwaways(player), player.getInventory().contains(Material.WATER_BUCKET),
                player.getFoodLevel() > 6);
            return new Means(Digging.of(player, body.policy()), kit);
        });
        return Pathfinder.find(Terrain.of(view, executor.closing(means.digging())), means.kit(), start, goal, favored,
            SEARCH_MILLIS);
    }

    private static Pos clamp(Pos start, Pos focus) {
        int dx = focus.x() - start.x();
        int dz = focus.z() - start.z();
        double distance = Math.hypot(dx, dz);
        if (distance <= LEG) {
            return focus;
        }
        double scale = LEG / distance;
        return new Pos(start.x() + (int) (dx * scale), focus.y(), start.z() + (int) (dz * scale));
    }

    private static Set<Pos> cells(Path path) {
        Set<Pos> cells = new HashSet<>();
        path.steps().forEach(step -> cells.add(step.to()));
        return cells;
    }

    /** Rises while the eyes are underwater, digging out a block above the head if one is there. */
    private void surface(Checkpoint checkpoint) {
        for (int tick = 0; tick < SURFACE_TICKS; tick++) {
            checkpoint.check();
            Pos above = body.main().tick(() -> {
                Player player = body.player();
                if (!player.isUnderWater()) {
                    return null;
                }
                body.helper().drive(new Controls(0, 0, true, false));
                return Pos.standing(player.getLocation()).offset(0, 2, 0);
            });
            if (above == null) {
                return;
            }
            boolean open = body.main().call(() -> has(traits(body.live(), above), PASSABLE));
            if (!open && !miner.mine(above).confirmed()) {
                return;
            }
        }
    }

    /** Ground or deep water to stand in, with the head out of the water. */
    private static boolean safeToStand(BlockReader live, Pos feet) {
        int floor = traits(live, feet.offset(0, -1, 0));
        boolean wet = has(traits(live, feet), WATER);
        return (has(floor, STANDABLE) || wet) && !has(traits(live, feet.offset(0, 1, 0)), WATER);
    }

    private static int traits(BlockReader live, Pos cell) {
        return Traits.of(live.type(cell.x(), cell.y(), cell.z()));
    }

    /** The next leg, planned on its own thread while this one is walked. */
    private final class Lookahead {
        private final Goal goal;
        private Pos from;
        private CompletableFuture<Path> next;

        Lookahead(Goal goal) {
            this.goal = goal;
        }

        void begin(Pos from, Set<Pos> favored) {
            this.from = from;
            this.next = CompletableFuture.supplyAsync(() -> plan(from, goal, favored),
                task -> Thread.ofVirtual().start(task));
        }

        /** The leg planned from {@code at}, or null when none was planned from there. */
        Path take(Pos at) {
            CompletableFuture<Path> planned = next;
            next = null;
            if (planned == null || !at.equals(from)) {
                return null;
            }
            try {
                return planned.join();
            } catch (CompletionException e) {
                return null;
            }
        }
    }
}
