package dev.minegoon.actions;

import dev.minegoon.ItemSet;
import dev.minegoon.world.BlockReader;
import dev.minegoon.world.Blocks;
import dev.minegoon.world.Pos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** Puts a block down near the helper and remembers it as the helper's own. */
public final class Placer {

    private static final int SPREAD = 2;

    private final Body body;

    public Placer(Body body) {
        this.body = body;
    }

    public Pos placeBeside(Material material) {
        return body.main().call(() -> {
            Player player = body.player();
            ItemSet item = ItemSet.of(material);
            if (Hands.count(player, item) == 0) {
                throw new ActionFailed("has no " + item.name() + " to place");
            }
            Pos feet = Pos.of(player.getLocation());
            BlockReader live = body.live();
            Pos spot = nearbySpots(feet).stream()
                .filter(pos -> fits(live, pos))
                .findFirst()
                .orElseThrow(() -> new ActionFailed("no room to place " + item.name()));
            Block block = spot.block(player.getWorld());
            block.setType(material);
            Hands.remove(player, item, 1);
            body.policy().recordPlaced(block);
            player.swingMainHand();
            return spot;
        });
    }

    /** Cells around the helper, nearest first, leaving out the two its body fills. */
    private static List<Pos> nearbySpots(Pos feet) {
        List<Pos> spots = new ArrayList<>();
        for (int dx = -SPREAD; dx <= SPREAD; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -SPREAD; dz <= SPREAD; dz++) {
                    if (dx != 0 || dz != 0) {
                        spots.add(feet.offset(dx, dy, dz));
                    }
                }
            }
        }
        spots.sort(Comparator.comparingInt(pos -> pos.manhattan(feet)));
        return spots;
    }

    private static boolean fits(BlockReader live, Pos pos) {
        Material there = live.type(pos.x(), pos.y(), pos.z());
        return Blocks.passable(there) && !Blocks.fluid(there)
            && Blocks.ground(live.type(pos.x(), pos.y() - 1, pos.z()));
    }
}
