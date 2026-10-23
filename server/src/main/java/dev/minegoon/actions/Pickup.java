package dev.minegoon.actions;

import dev.minegoon.world.Blocks;
import dev.minegoon.world.Goal;
import dev.minegoon.world.Pos;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

/**
 * Walks over loose items so the helper picks them up the normal way. The pickup
 * box is the body plus one block out, which does not always reach a drop sitting
 * on the far side of the next block, so a trip has to end standing on the drop.
 */
public final class Pickup {

    private static final int MAX_TRIPS = 12;
    /** Under one block: the feet block beside a drop is distance 1, and that is not on it. */
    private static final double ON_TOP = 0.5;
    /** How long a fresh drop may take to finish its pickup delay and land. */
    private static final int SETTLE_TICKS = 30;
    /** A delay this high never counts down. Junk thrown away on purpose uses it. */
    private static final int NEVER = 32767;
    private static final int PAUSE_TICKS = 4;
    private static final double STILL = 1.0E-4;

    private final Body body;
    private final Mover mover;

    public Pickup(Body body, Mover mover) {
        this.body = body;
        this.mover = mover;
    }

    /** Returns how many item stacks it walked to. */
    public int collect(Pos center, double radius, Checkpoint checkpoint) {
        Set<UUID> skipped = new HashSet<>();
        int visited = 0;
        int settling = 0;
        for (int trips = 0; trips < MAX_TRIPS; ) {
            checkpoint.check();
            boolean impatient = settling >= SETTLE_TICKS;
            Found found = body.main().call(() -> look(center, radius, skipped, impatient));
            if (found.drop() == null) {
                if (!found.waiting() || settling >= SETTLE_TICKS) {
                    break;
                }
                body.main().sleep(1);
                settling++;
                continue;
            }
            settling = 0;
            Drop drop = found.drop();
            if (!mover.walk(Goal.near(drop.stand(), ON_TOP), checkpoint)) {
                skipped.add(drop.id());
                continue;
            }
            trips++;
            body.main().sleep(PAUSE_TICKS);
            switch (body.main().call(() -> result(drop))) {
                case TOOK -> visited++;
                case STUCK -> skipped.add(drop.id());
                case MOVED -> {
                    // It slid off the block we stood on. The next look walks to where it is now.
                }
            }
        }
        return visited;
    }

    /** The closest drop that is ready, or a note that something nearby is still landing. Main thread. */
    private Found look(Pos center, double radius, Set<UUID> skipped, boolean impatient) {
        Player player = body.player();
        Location around = center.feet(player.getWorld());
        Item best = null;
        double bestDistance = Double.MAX_VALUE;
        boolean waiting = false;
        for (Item item : player.getWorld().getNearbyEntitiesByType(Item.class, around, radius)) {
            if (skipped.contains(item.getUniqueId()) || !takeable(item, player)) {
                continue;
            }
            boolean ready = item.getPickupDelay() == 0 && (impatient || settled(item));
            if (!ready) {
                waiting = true;
                continue;
            }
            double distance = item.getLocation().distanceSquared(player.getLocation());
            if (distance < bestDistance) {
                best = item;
                bestDistance = distance;
            }
        }
        if (best != null) {
            return new Found(new Drop(best.getUniqueId(), stand(best)), false);
        }
        return new Found(null, waiting);
    }

    private static boolean takeable(Item item, Player self) {
        UUID owner = item.getOwner();
        return item.getPickupDelay() < NEVER && (owner == null || owner.equals(self.getUniqueId()));
    }

    private static boolean settled(Item item) {
        return item.isOnGround() || item.isInWater() || item.getVelocity().lengthSquared() < STILL;
    }

    /** The feet block of the drop: the block it is in, or the one above when that block is solid. */
    private static Pos stand(Item item) {
        Location at = item.getLocation();
        Pos in = Pos.of(at);
        return Blocks.passable(at.getBlock().getType()) ? in : in.offset(0, 1, 0);
    }

    /** What happened to the stack we just stood on. Main thread. */
    private Result result(Drop drop) {
        if (!(body.player().getWorld().getEntity(drop.id()) instanceof Item item) || !item.isValid()) {
            return Result.TOOK;
        }
        return drop.stand().equals(stand(item)) ? Result.STUCK : Result.MOVED;
    }

    private record Drop(UUID id, Pos stand) {
    }

    private record Found(Drop drop, boolean waiting) {
    }

    private enum Result { TOOK, MOVED, STUCK }
}
