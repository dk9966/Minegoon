package dev.minegoon.trips;

import dev.minegoon.ItemSet;
import dev.minegoon.actions.ActionFailed;
import dev.minegoon.actions.Body;
import dev.minegoon.actions.Checkpoint;
import dev.minegoon.actions.Hands;
import dev.minegoon.actions.Look;
import dev.minegoon.actions.Mover;
import dev.minegoon.actions.Pickup;
import dev.minegoon.jobs.Step;
import dev.minegoon.world.Goal;
import dev.minegoon.world.Pos;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/** Coming back: to a player, or to where a task was given, and handing items over. */
public final class Homecoming {

    private static final double ARRIVED = 3;
    private static final double CLOSE_ENOUGH = 8;
    private static final int MAX_LEGS = 6;
    private static final int CLIMB = 32;
    private static final int PICKUP_DELAY = 20;

    private final Body body;
    private final Mover mover;
    private final Tunneler tunneler;
    private final Pickup pickup;
    private final Fighter fighter;
    private final Checkpoint homeward;

    public Homecoming(Body body, Mover mover, Tunneler tunneler, Pickup pickup, Fighter fighter,
                      Checkpoint homeward) {
        this.body = body;
        this.mover = mover;
        this.tunneler = tunneler;
        this.pickup = pickup;
        this.fighter = fighter;
        this.homeward = homeward;
    }

    /**
     * Brings items to the owner if they are around, otherwise to {@code home}.
     * Returns how many were handed over.
     */
    public int deliver(UUID owner, Location home, ItemSet items, int count) {
        Location at = goBack(owner, home);
        return body.main().call(() -> {
            Player player = body.player();
            Look.at(player, at.clone().add(0, 1, 0));
            int given = 0;
            for (ItemStack stack : Hands.take(player, items, count)) {
                throwTo(player, at, stack, owner);
                given += stack.getAmount();
            }
            return given;
        });
    }

    public void comeTo(UUID player) {
        goBack(player, null);
    }

    /**
     * Walks back to where it died and picks up whatever is still lying there.
     * Whatever killed it is often still there: once a group there is too much
     * to fight, the things are left rather than dying for them again.
     */
    public void recover(Step.Recover spot, Checkpoint checkpoint) {
        Pos where = new Pos(spot.x(), spot.y(), spot.z());
        boolean sameWorld = body.main().call(() -> body.world().getName().equals(spot.world()));
        int escapes = fighter.escapes();
        Checkpoint wary = () -> {
            checkpoint.check();
            if (fighter.escapes() != escapes) {
                throw new ActionFailed("there are too many monsters where I died");
            }
        };
        if (!sameWorld || !mover.walk(Goal.near(where, 2), wary)) {
            throw new ActionFailed("I can't get back to where I died");
        }
        pickup.collect(where, 8, wary);
    }

    /**
     * Walks to the player, following if they move, or to the fallback spot when
     * they are away. From deep below, where no walk finds a way, it climbs stairs
     * toward them a stretch at a time. A spot it cannot stand next to, such as a
     * treetop, is close enough from below. Returns where it ended up handing things over.
     */
    public Location goBack(UUID player, Location fallback) {
        Location target = null;
        int legs = 0;
        while (legs < MAX_LEGS) {
            target = body.main().call(() -> whereIs(player, fallback));
            if (target == null) {
                throw new ActionFailed("I can't find you in this world");
            }
            boolean walked = mover.walk(Goal.near(Pos.of(target), ARRIVED - 0.5), homeward);
            if (within(player, fallback, ARRIVED)) {
                return target;
            }
            if (walked || !climbToward(Pos.of(target))) {
                legs++;
            }
        }
        if (body.main().call(() -> below(player, fallback))) {
            return target;
        }
        throw new ActionFailed("I couldn't find a way back");
    }

    /** Stairs up toward a spot higher than the helper, along whichever way it lies most. False when no higher. */
    private boolean climbToward(Pos target) {
        Pos feet = mover.feet();
        if (feet.y() >= target.y()) {
            return false;
        }
        int dx = target.x() - feet.x();
        int dz = target.z() - feet.z();
        if (Math.abs(dx) >= Math.abs(dz)) {
            tunneler.face(dx, 0);
        } else {
            tunneler.face(0, dz);
        }
        return tunneler.up(target.y(), CLIMB, homeward);
    }

    /**
     * Within {@link #CLOSE_ENOUGH} blocks sideways, at any height: under a
     * treetop or a ledge it cannot climb to is close enough to hand things over.
     */
    private boolean below(UUID player, Location fallback) {
        Location home = whereIs(player, fallback);
        if (home == null) {
            return false;
        }
        Location at = body.player().getLocation();
        double dx = at.getX() - home.getX();
        double dz = at.getZ() - home.getZ();
        return dx * dx + dz * dz <= CLOSE_ENOUGH * CLOSE_ENOUGH;
    }

    private boolean within(UUID player, Location fallback, double distance) {
        return body.main().call(() -> {
            Location now = whereIs(player, fallback);
            return now != null && body.player().getLocation().distance(now) <= distance;
        });
    }

    private Location whereIs(UUID id, Location fallback) {
        Player player = id == null ? null : Bukkit.getPlayer(id);
        String world = body.world().getName();
        if (player != null && player.getWorld().getName().equals(world)) {
            return player.getLocation();
        }
        return fallback != null && fallback.getWorld().getName().equals(world) ? fallback : null;
    }

    private static void throwTo(Player player, Location target, ItemStack stack, UUID owner) {
        Location from = player.getEyeLocation().subtract(0, 0.3, 0);
        Vector toward = target.toVector().subtract(from.toVector()).normalize().multiply(0.3);
        Item item = player.getWorld().dropItem(from, stack);
        item.setVelocity(toward);
        item.setPickupDelay(PICKUP_DELAY);
        if (owner != null) {
            item.setOwner(owner);
        }
    }
}
