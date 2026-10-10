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
    private static final int MAX_LEGS = 6;
    private static final int PICKUP_DELAY = 20;

    private final Body body;
    private final Mover mover;
    private final Pickup pickup;

    public Homecoming(Body body, Mover mover, Pickup pickup) {
        this.body = body;
        this.mover = mover;
        this.pickup = pickup;
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

    /** Walks back to where it died and picks up whatever is still lying there. */
    public void recover(Step.Recover spot, Checkpoint checkpoint) {
        Pos where = new Pos(spot.x(), spot.y(), spot.z());
        boolean sameWorld = body.main().call(() -> body.world().getName().equals(spot.world()));
        if (!sameWorld || !mover.walk(Goal.near(where, 2), checkpoint)) {
            throw new ActionFailed("I can't get back to where I died");
        }
        pickup.collect(where, 8, checkpoint);
    }

    /**
     * Walks to the player, following if they move, or to the fallback spot when
     * they are away. Returns where it ended up handing things over.
     */
    public Location goBack(UUID player, Location fallback) {
        for (int leg = 0; leg < MAX_LEGS; leg++) {
            Location target = body.main().call(() -> whereIs(player, fallback));
            if (target == null) {
                throw new ActionFailed("I can't find you in this world");
            }
            mover.walk(Goal.near(Pos.of(target), ARRIVED - 0.5), Checkpoint.NONE);
            boolean arrived = body.main().call(() -> {
                Location now = whereIs(player, fallback);
                return now != null && body.player().getLocation().distance(now) <= ARRIVED;
            });
            if (arrived) {
                return target;
            }
        }
        throw new ActionFailed("I couldn't find a way back");
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
