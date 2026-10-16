package dev.minegoon.actions;

import static dev.minegoon.world.Traits.CLIMBABLE;
import static dev.minegoon.world.Traits.STANDABLE;
import static dev.minegoon.world.Traits.has;

import dev.minegoon.spawn.FakePlayer;
import dev.minegoon.world.BlockReader;
import dev.minegoon.world.Pos;
import dev.minegoon.world.Traits;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/**
 * The feet block of a body that is held up. A body is 0.6 wide, so it can rest
 * on the edge of a block beside it with nothing under its centre. Holding on at
 * the top of a vine or ladder, it hovers just above the climbable block, in a
 * cell with nothing under it. A route has to start from the block that is
 * holding it up, or no move from there makes sense.
 */
final class Footing {

    private static final double EDGE = 1e-4;
    private static final double HOVER = 0.3;

    private Footing() {
    }

    /** Main thread. */
    static Pos of(Player player, BlockReader live) {
        Location at = player.getLocation();
        Pos centre = Pos.standing(at);
        if (!FakePlayer.grounded(player)) {
            Pos below = centre.offset(0, -1, 0);
            boolean hovering = at.getY() - centre.y() < HOVER
                && has(traits(live, below), CLIMBABLE) && !has(traits(live, centre), CLIMBABLE);
            return hovering ? below : centre;
        }
        if (standable(live, centre)) {
            return centre;
        }
        BoundingBox box = player.getBoundingBox();
        Pos holding = centre;
        double nearest = Double.MAX_VALUE;
        for (int x = floor(box.getMinX()); x <= floor(box.getMaxX() - EDGE); x++) {
            for (int z = floor(box.getMinZ()); z <= floor(box.getMaxZ() - EDGE); z++) {
                Pos column = new Pos(x, centre.y(), z);
                double away = Math.hypot(x + 0.5 - at.getX(), z + 0.5 - at.getZ());
                if (away < nearest && standable(live, column)) {
                    nearest = away;
                    holding = column;
                }
            }
        }
        return holding;
    }

    private static boolean standable(BlockReader live, Pos feet) {
        return has(traits(live, feet.offset(0, -1, 0)), STANDABLE);
    }

    private static int traits(BlockReader live, Pos cell) {
        return Traits.of(live.type(cell.x(), cell.y(), cell.z()));
    }

    private static int floor(double value) {
        return (int) Math.floor(value + EDGE);
    }
}
