package dev.minegoon.actions;

import dev.minegoon.world.Blocks;
import dev.minegoon.world.Goal;
import dev.minegoon.world.Pathfinder;
import dev.minegoon.world.Pathfinder.Path;
import dev.minegoon.world.Pos;
import dev.minegoon.world.WorldView;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;

/**
 * Walks the helper to a goal at walking speed. Routes are planned on snapshots
 * in legs of at most {@link #LEG} blocks; anything in the way is checked and
 * dug out live, just before each step.
 */
public final class Mover {

    private static final int BUDGET = 30_000;
    private static final int LEG = 80;
    private static final int MARGIN = 20;
    private static final int MAX_LEGS = 40;
    private static final int MAX_SETBACKS = 4;
    private static final int SAFE_FALL = 3;

    private final Body body;
    private final Miner miner;

    public Mover(Body body, Miner miner) {
        this.body = body;
        this.miner = miner;
    }

    /** Returns false when the goal cannot be reached. */
    public boolean walk(Goal goal, Checkpoint checkpoint) {
        int setbacks = 0;
        for (int leg = 0; leg < MAX_LEGS && setbacks < MAX_SETBACKS; leg++) {
            checkpoint.check();
            Pos start = settle();
            if (body.main().call(() -> goal.reached(body.live(), start))) {
                return true;
            }
            Path path = plan(start, goal);
            if (path == null || !follow(path, checkpoint) || path.end().equals(start)) {
                setbacks++;
            }
        }
        return body.main().call(() -> goal.reached(body.live(), feet()));
    }

    public Pos feet() {
        return body.main().call(() -> Pos.of(body.player().getLocation()));
    }

    private Path plan(Pos start, Goal goal) {
        World world = body.main().call(body::world);
        Pos focus = clamp(start, goal.focus());
        WorldView view = WorldView.capture(body.main(), world,
            Math.min(start.x(), focus.x()) - MARGIN, Math.min(start.z(), focus.z()) - MARGIN,
            Math.max(start.x(), focus.x()) + MARGIN, Math.max(start.z(), focus.z()) + MARGIN);
        Digging digging = body.main().call(() -> Digging.of(body.player(), body.policy()));
        return Pathfinder.find(view, start, goal, digging, BUDGET);
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

    private boolean follow(Path path, Checkpoint checkpoint) {
        List<Pos> steps = path.steps();
        for (int i = 1; i < steps.size(); i++) {
            checkpoint.check();
            if (!step(steps.get(i - 1), steps.get(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean step(Pos from, Pos to) {
        int rise = to.y() - from.y();
        for (Pos cell : cellsToClear(from, to)) {
            boolean open = body.main().call(() -> Blocks.passable(body.live().type(cell.x(), cell.y(), cell.z())));
            if (!open && (rise < -1 || !miner.mine(cell).confirmed())) {
                return false;
            }
        }
        boolean standable = body.main().call(() -> Blocks.ground(body.live().type(to.x(), to.y() - 1, to.z())));
        if (!standable) {
            return false;
        }
        glide(from, to);
        return true;
    }

    /** Head-height first, so nothing falls onto the helper while it digs. */
    private static List<Pos> cellsToClear(Pos from, Pos to) {
        List<Pos> cells = new ArrayList<>();
        int rise = to.y() - from.y();
        if (rise > 0) {
            cells.add(from.offset(0, 2, 0));
        }
        int top = Math.max(from.y(), to.y()) + 1;
        for (int y = top; y >= to.y(); y--) {
            cells.add(new Pos(to.x(), y, to.z()));
        }
        return cells;
    }

    private void glide(Pos from, Pos to) {
        int rise = to.y() - from.y();
        int ticks = (int) Math.ceil(Pathfinder.WALK) + Math.abs(rise);
        float yaw = (float) Math.toDegrees(Math.atan2(-(to.x() - from.x()), to.z() - from.z()));
        for (int tick = 1; tick <= ticks; tick++) {
            double t = (double) tick / ticks;
            double across = rise > 0 ? Math.max(0, (t - 0.4) / 0.6) : rise < 0 ? Math.min(1, t / 0.5) : t;
            double up = rise > 0 ? Math.min(1, t / 0.4) : rise < 0 ? Math.max(0, (t - 0.5) / 0.5) : 0;
            double x = from.x() + 0.5 + (to.x() - from.x()) * across;
            double y = from.y() + rise * up;
            double z = from.z() + 0.5 + (to.z() - from.z()) * across;
            body.main().run(() -> {
                Player player = body.player();
                player.teleport(new Location(player.getWorld(), x, y, z, yaw, 0));
            });
        }
        body.main().run(() -> body.helper().setOnGround(true));
    }

    /** Drops the helper onto solid ground if it is floating, with fall damage like anyone else. */
    public Pos settle() {
        return body.main().call(() -> {
            Player player = body.player();
            Location at = player.getLocation();
            Pos feet = Pos.of(at);
            var live = body.live();
            if (Blocks.ground(live.type(feet.x(), feet.y() - 1, feet.z()))) {
                body.helper().setOnGround(true);
                return feet;
            }
            int floor = feet.y() - 1;
            while (floor > player.getWorld().getMinHeight() && Blocks.passable(live.type(feet.x(), floor, feet.z()))) {
                floor--;
            }
            Pos landing = new Pos(feet.x(), floor + 1, feet.z());
            player.teleport(landing.feet(player.getWorld()).setDirection(at.getDirection()));
            int fell = feet.y() - landing.y();
            if (fell > SAFE_FALL) {
                player.damage(fell - SAFE_FALL, DamageSource.builder(DamageType.FALL).build());
            }
            body.helper().setOnGround(true);
            return landing;
        });
    }
}
