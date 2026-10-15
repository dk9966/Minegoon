package dev.minegoon.actions;

import static dev.minegoon.world.Traits.CLIMBABLE;
import static dev.minegoon.world.Traits.HARMFUL;
import static dev.minegoon.world.Traits.PASSABLE;
import static dev.minegoon.world.Traits.REPLACEABLE;
import static dev.minegoon.world.Traits.STANDABLE;
import static dev.minegoon.world.Traits.UNKNOWN;
import static dev.minegoon.world.Traits.WATER;
import static dev.minegoon.world.Traits.has;

import dev.minegoon.spawn.FakePlayer;
import dev.minegoon.spawn.FakePlayer.Controls;
import dev.minegoon.world.BlockReader;
import dev.minegoon.world.Clearance;
import dev.minegoon.world.Pos;
import dev.minegoon.world.Traits;
import dev.minegoon.world.path.Move;
import dev.minegoon.world.path.Path;
import dev.minegoon.world.path.Step;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Drives the body along a route with the keys a player would press, one game
 * tick at a time: forward, sprint and jump, head turned the way it goes. Blocks
 * in the way are dug, and missing floors placed, from the live world just before
 * the step that needs them. Every tick it works out which step the body is on
 * from where the body really is, so being pushed back or carried ahead does not
 * derail it. It gives up, for a replan, when the body is somewhere the route
 * does not go, or a step runs well over its planned ticks.
 */
public final class PathExecutor {

    /** Ticks of route left when the next leg of a long walk starts planning. */
    public static final double PLAN_AHEAD = 150;

    private static final Logger LOG = Logger.getLogger("Minegoon");
    private static final int LOOK_AHEAD = 4;
    private static final int LOOK_BACK = 3;
    private static final int OFF_ROUTE_TICKS = 40;
    private static final double OFF_ROUTE_FAR = 3;
    private static final int STEP_SLACK = 100;
    private static final int MAX_DIGS = 12;
    private static final double CENTERED = 0.3;
    private static final double ARRIVED = 0.1;
    /** Closer than this to a centre it must stop on, the body creeps so it does not overshoot. */
    private static final double SLOW_DOWN = 0.6;
    /** A running jump leaves from this far past the centre of the last block: the edge, with a tick to spare. */
    private static final double TAKE_OFF = 0.45;
    private static final double ASCEND_JUMP = 1.2;
    /** Feet this far above the block's floor clear the block about to be placed under them. */
    private static final double PILLAR_PLACE = 1.02;
    private static final float CREEP = 0.4f;
    /** Steps in a straight line ahead, with headroom, before a sprint turns into sprint-jumping. */
    private static final int RUN = 3;

    private sealed interface Order {
    }

    private record Go() implements Order {
    }

    private record Dig(Pos cell) implements Order {
    }

    private record Done() implements Order {
    }

    private record Failed(String why) implements Order {
    }

    private static final Order GO = new Go();
    private static final Order DONE = new Done();
    /** The checkpoint runs at every step, and at least this often while a step drags on. */
    private static final int CHECK_TICKS = 20;

    private final Body body;
    private final Miner miner;
    private final Placer placer;
    /** Cells the live world would not let a route open, though the snapshot it was planned on did. */
    private final Set<Pos> refused = ConcurrentHashMap.newKeySet();

    public PathExecutor(Body body, Miner miner, Placer placer) {
        this.body = body;
        this.miner = miner;
        this.placer = placer;
    }

    /** {@code digging}, with every refused cell closed, so the next route goes round them. */
    public Clearance closing(Clearance digging) {
        return (world, x, y, z) -> !refused.isEmpty() && refused.contains(new Pos(x, y, z))
            ? Clearance.IMPOSSIBLE : digging.cost(world, x, y, z);
    }

    /** Forgets the refused cells, for a walk somewhere new. */
    public void reopen() {
        refused.clear();
    }

    /**
     * Follows the route to its end. False when it had to give up. {@code nearEnd}
     * runs once, on this thread, when less than {@link #PLAN_AHEAD} ticks are left.
     * Worker thread.
     */
    public boolean follow(Path path, Checkpoint checkpoint, Runnable nearEnd) {
        if (path.steps().isEmpty()) {
            return true;
        }
        Run run = new Run(path.steps());
        Runnable pending = nearEnd;
        int sinceCheck = 0;
        while (true) {
            switch (body.main().call(run::tick)) {
                case Go go -> {
                }
                case Dig dig -> {
                    Receipt receipt = miner.mine(dig.cell());
                    if (!receipt.confirmed()) {
                        refused.add(dig.cell());
                        return gaveUp(receipt.toString());
                    }
                }
                case Done done -> {
                    return true;
                }
                case Failed failed -> {
                    return gaveUp(failed.why());
                }
            }
            if (run.advanced || ++sinceCheck >= CHECK_TICKS) {
                checkpoint.check();
                sinceCheck = 0;
            }
            if (pending != null && run.remaining() < PLAN_AHEAD) {
                pending.run();
                pending = null;
            }
        }
    }

    private boolean gaveUp(String why) {
        LOG.info(body.helper().name() + " route: " + why);
        return false;
    }

    /** One route being driven. Every method but {@link #remaining} runs on the main thread. */
    private final class Run {
        private final List<Step> steps;
        private final double[] after;
        private int index;
        private int stepTicks;
        private int offRoute;
        private int digs;
        boolean advanced;

        Run(List<Step> steps) {
            this.steps = steps;
            this.after = new double[steps.size() + 1];
            for (int i = steps.size() - 1; i >= 0; i--) {
                after[i] = after[i + 1] + steps.get(i).cost();
            }
        }

        double remaining() {
            return after[index];
        }

        Order tick() {
            Player player = body.player();
            Location at = player.getLocation();
            Pos feet = feet(at, player);
            advanced = false;
            if (sync(feet)) {
                offRoute = 0;
            } else if (++offRoute > OFF_ROUTE_TICKS || away(at) > OFF_ROUTE_FAR) {
                return new Failed("off the route on " + steps.get(index) + ", " + state(player));
            }
            while (finished(steps.get(index), next(index), feet, at, player)) {
                moveTo(index + 1);
                if (index == steps.size()) {
                    return DONE;
                }
            }
            Step step = steps.get(index);
            if (++stepTicks > step.cost() + STEP_SLACK) {
                return new Failed("too long on " + step + ", " + state(player));
            }
            Order prepared = prepare(step, feet, at, player);
            if (prepared != null) {
                return prepared;
            }
            body.helper().drive(controls(step, next(index), at, player));
            return GO;
        }

        /** The feet block. A swimming body bobs below the surface it swims along, so one block low still counts. */
        private Pos feet(Location at, Player player) {
            Pos feet = Pos.standing(at);
            Pos above = feet.offset(0, 1, 0);
            Step step = steps.get(index);
            return player.isInWater() && !step.allows(feet) && step.allows(above) ? above : feet;
        }

        /** Where the body is and what is around it, for a give-up message someone can act on. */
        private String state(Player player) {
            Location at = player.getLocation();
            Step step = steps.get(index);
            BlockReader live = body.live();
            Pos to = step.to();
            Vector velocity = player.getVelocity();
            return String.format("body at %.2f %.2f %.2f facing %.0f moving %.2f %.2f %.2f%s%s%s, to %s/%s over %s",
                at.getX(), at.getY(), at.getZ(), at.getYaw(), velocity.getX(), velocity.getY(), velocity.getZ(),
                FakePlayer.grounded(player) ? " grounded" : "", player.isInWater() ? " wet" : "",
                FakePlayer.climbing(player) ? " climbing" : "", name(live, to), name(live, to.offset(0, 1, 0)),
                name(live, to.offset(0, -1, 0)));
        }

        /** Finds the step the body is on, a few back or ahead of the last one. */
        private boolean sync(Pos feet) {
            if (steps.get(index).allows(feet)) {
                return true;
            }
            for (int j = index + 1; j < Math.min(steps.size(), index + 1 + LOOK_AHEAD); j++) {
                if (steps.get(j).allows(feet)) {
                    moveTo(j);
                    return true;
                }
            }
            for (int j = index - 1; j >= Math.max(0, index - LOOK_BACK); j--) {
                if (steps.get(j).allows(feet)) {
                    moveTo(j);
                    return true;
                }
            }
            return false;
        }

        private void moveTo(int step) {
            advanced |= step > index;
            index = step;
            stepTicks = 0;
            digs = 0;
        }

        private Step next(int i) {
            return i + 1 < steps.size() ? steps.get(i + 1) : null;
        }

        private double away(Location at) {
            Pos to = steps.get(index).to();
            return Math.hypot(to.x() + 0.5 - at.getX(), to.z() + 0.5 - at.getZ());
        }

        /** In the step's end block and steady there; centred too, unless the route goes straight on. */
        private boolean finished(Step step, Step next, Pos feet, Location at, Player player) {
            if (!feet.equals(step.to()) || !settled(player)) {
                return false;
            }
            return !needsCentre(next) || offCentre(step.to(), at) <= CENTERED;
        }

        /** Digs what is in the way and places a missing floor, while the body stands at the step's start. */
        private Order prepare(Step step, Pos feet, Location at, Player player) {
            if (!feet.equals(step.from())) {
                return null;
            }
            if (step.move() == Move.DOWNWARD && offCentre(step.from(), at) > CENTERED) {
                return null;
            }
            BlockReader live = body.live();
            for (Pos cell : step.clears()) {
                int traits = traits(live, cell);
                if (has(traits, PASSABLE)) {
                    continue;
                }
                if (has(traits, HARMFUL | UNKNOWN) || ++digs > MAX_DIGS) {
                    refused.add(cell);
                    return new Failed("cannot clear " + cell + " for " + step);
                }
                return new Dig(cell);
            }
            Pos floor = step.floor();
            if (floor != null && step.move() != Move.PILLAR && missingFloor(live, step, floor)) {
                if (!FakePlayer.grounded(player)) {
                    return GO;
                }
                if (!placer.placeAt(floor)) {
                    return new Failed("could not place a block at " + floor);
                }
            }
            return null;
        }

        private Controls controls(Step step, Step next, Location at, Player player) {
            Pos to = step.to();
            double distance = offCentre(to, at);
            boolean grounded = FakePlayer.grounded(player);
            boolean wet = player.isInWater();
            if (distance > ARRIVED) {
                Look.toward(player, to.x() + 0.5, to.z() + 0.5);
            }
            float forward = distance <= ARRIVED ? 0 : needsCentre(next) && distance < SLOW_DOWN ? CREEP : 1;
            boolean sprint = !wet && step.move().horizontal() && next != null && straightOn(step, next);
            boolean jump = wet;
            switch (step.move()) {
                case TRAVERSE, DIAGONAL, SWIM, DESCEND, FALL ->
                    jump |= grounded && (FakePlayer.bumped(player) || sprint && player.isSprinting() && openRun());
                case ASCEND -> jump |= (grounded || FakePlayer.climbing(player)) && distance < ASCEND_JUMP;
                case PARKOUR -> {
                    sprint = true;
                    jump = grounded && along(step, at) >= TAKE_OFF;
                }
                case PILLAR -> {
                    forward = grounded && distance > CENTERED ? CREEP : 0;
                    jump = forward == 0 && grounded;
                    Pos cell = step.from();
                    if (!grounded && at.getY() >= cell.y() + PILLAR_PLACE && has(traits(body.live(), cell), REPLACEABLE)) {
                        placer.placeAt(cell);
                    }
                }
                case CLIMB_UP -> {
                    forward = distance > CENTERED ? CREEP : 0;
                    jump = true;
                }
                case CLIMB_DOWN, DOWNWARD -> forward = distance > CENTERED ? CREEP : 0;
            }
            boolean hold = FakePlayer.climbing(player) && step.move().horizontal() && step.to().y() >= step.from().y()
                && !jump;
            return new Controls(forward, 0, jump, sprint && !hold, hold);
        }

        /** The next few steps run straight and level with room to jump, so sprint-jumping is safe and faster. */
        private boolean openRun() {
            if (index + RUN >= steps.size()) {
                return false;
            }
            Step first = steps.get(index);
            BlockReader live = body.live();
            for (int i = index; i <= index + RUN; i++) {
                Step step = steps.get(i);
                Pos to = step.to();
                if (step.move() != Move.TRAVERSE || !straightOn(first, step) || to.y() != first.from().y()
                    || !has(traits(live, to.offset(0, -1, 0)), STANDABLE)
                    || !has(traits(live, to.offset(0, 2, 0)), PASSABLE)
                    || !has(traits(live, step.from().offset(0, 2, 0)), PASSABLE)) {
                    return false;
                }
            }
            return true;
        }

        private boolean missingFloor(BlockReader live, Step step, Pos floor) {
            int under = traits(live, floor);
            int feet = traits(live, step.to());
            return !has(under, STANDABLE) && !has(feet, CLIMBABLE | WATER);
        }
    }

    private static boolean settled(Player player) {
        return FakePlayer.grounded(player) || player.isInWater() || FakePlayer.climbing(player);
    }

    /** The route ends here or goes up or down next, so the body must stop at the centre of the block. */
    private static boolean needsCentre(Step next) {
        return next == null || !next.move().horizontal();
    }

    private static boolean straightOn(Step step, Step next) {
        return step.move().horizontal() && next.move().horizontal()
            && step.dx() == next.dx() && step.dz() == next.dz();
    }

    private static double offCentre(Pos cell, Location at) {
        return Math.hypot(cell.x() + 0.5 - at.getX(), cell.z() + 0.5 - at.getZ());
    }

    /** How far past the centre of the step's first block the body is, along the step's direction. */
    private static double along(Step step, Location at) {
        Pos from = step.from();
        return (at.getX() - from.x() - 0.5) * step.dx() + (at.getZ() - from.z() - 0.5) * step.dz();
    }

    private static String name(BlockReader live, Pos cell) {
        return live.type(cell.x(), cell.y(), cell.z()).getKey().value();
    }

    private static int traits(BlockReader live, Pos cell) {
        return Traits.of(live.type(cell.x(), cell.y(), cell.z()));
    }
}
