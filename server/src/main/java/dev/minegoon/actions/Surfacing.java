package dev.minegoon.actions;

import dev.minegoon.spawn.FakePlayer.Controls;
import dev.minegoon.world.BlockReader;
import dev.minegoon.world.Clearance;
import dev.minegoon.world.Pos;
import dev.minegoon.world.Terrain;
import dev.minegoon.world.path.Air;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Swimming from under water to the nearest place the head is out. Main thread. */
public final class Surfacing {

    /** Closer than this to a column's centre, the 0.6-wide body rises straight up it without catching an edge. */
    private static final double CENTRED = 0.15;
    private static final Clearance NO_DIGGING = (world, x, y, z) -> Clearance.IMPOSSIBLE;

    private Surfacing() {
    }

    /** See {@link Air#way}: empty when the head is out, null when no air is in reach. */
    public static List<Pos> way(Player player) {
        Terrain live = Terrain.of(BlockReader.live(player.getWorld()), NO_DIGGING);
        return Air.way(live, Pos.standing(player.getLocation()));
    }

    /** Turns toward the next cell of {@code way} and returns the keys that swim there; straight up without one. */
    public static Controls along(Player player, List<Pos> way) {
        Location at = player.getLocation();
        Pos feet = Pos.standing(at);
        Pos next = way == null || way.isEmpty() ? feet.offset(0, 1, 0) : way.getFirst();
        double off = Math.hypot(next.x() + 0.5 - at.getX(), next.z() + 0.5 - at.getZ());
        if (off > CENTRED) {
            Look.toward(player, next.x() + 0.5, next.z() + 0.5);
        }
        return new Controls(off > CENTRED ? 1 : 0, 0, next.y() >= feet.y(), false);
    }
}
