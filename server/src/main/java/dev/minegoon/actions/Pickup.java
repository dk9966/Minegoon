package dev.minegoon.actions;

import dev.minegoon.world.Goal;
import dev.minegoon.world.Pos;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

/** Walks over loose items so the helper picks them up the normal way. */
public final class Pickup {

    private static final int MAX_TRIPS = 12;

    private final Body body;
    private final Mover mover;

    public Pickup(Body body, Mover mover) {
        this.body = body;
        this.mover = mover;
    }

    /** Returns how many item stacks it walked to. */
    public int collect(Pos center, double radius, Checkpoint checkpoint) {
        int visited = 0;
        for (int trip = 0; trip < MAX_TRIPS; trip++) {
            Optional<Pos> item = body.main().call(() -> nearestItem(center, radius));
            if (item.isEmpty() || !mover.walk(Goal.near(item.get(), 1.2), checkpoint)) {
                break;
            }
            body.main().sleep(4);
            visited++;
        }
        return visited;
    }

    private Optional<Pos> nearestItem(Pos center, double radius) {
        Player player = body.player();
        Location around = center.feet(player.getWorld());
        UUID self = player.getUniqueId();
        return player.getWorld().getNearbyEntitiesByType(Item.class, around, radius).stream()
            .filter(item -> item.canPlayerPickup() && (item.getOwner() == null || item.getOwner().equals(self)))
            .min(Comparator.comparingDouble(item -> item.getLocation().distanceSquared(player.getLocation())))
            .map(item -> Pos.of(item.getLocation()));
    }
}
