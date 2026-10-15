package dev.minegoon.actions;

import dev.minegoon.ItemSet;
import dev.minegoon.spawn.FakePlayer;
import dev.minegoon.world.BlockReader;
import dev.minegoon.world.Blocks;
import dev.minegoon.world.Pos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

/** Puts a block down near the helper and remembers it as the helper's own. */
public final class Placer {

    /** Common blocks a route may spend on bridges and pillars, cheapest to come by first. */
    public static final List<Material> THROWAWAY = List.of(
        Material.COBBLESTONE, Material.COBBLED_DEEPSLATE, Material.DIRT, Material.NETHERRACK);

    private static final int SPREAD = 3;
    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

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

    /** How many blocks a route may place. Main thread. */
    public static int throwaways(Player player) {
        int count = 0;
        for (Material material : THROWAWAY) {
            count += Hands.count(player, ItemSet.of(material));
        }
        return count;
    }

    /**
     * Puts a throwaway block into {@code cell} the way a player does: against a
     * face of a solid neighbour, under the block's own collision rules. False
     * when there is nothing to place, nothing to place against, or the game
     * refuses, say because the body is in the way. Main thread.
     */
    public boolean placeAt(Pos cell) {
        Player player = body.player();
        Material block = THROWAWAY.stream().filter(material -> Hands.hold(player, material)).findFirst().orElse(null);
        if (block == null) {
            return false;
        }
        World world = player.getWorld();
        for (int[] face : FACES) {
            Block support = world.getBlockAt(cell.x() + face[0], cell.y() + face[1], cell.z() + face[2]);
            if (!support.getType().isSolid()) {
                continue;
            }
            BlockFace toward = support.getFace(cell.block(world));
            if (toward != null && FakePlayer.useOn(player, support, toward) && !Blocks.passable(cell.block(world).getType())) {
                body.policy().recordPlaced(cell.block(world));
                player.swingMainHand();
                return true;
            }
        }
        return false;
    }

    /**
     * The nearest solid block in the placement neighborhood that is in the way of
     * putting something down, and that the helper is allowed to dig. Null if the
     * problem is not a block we can clear.
     */
    public Pos blockInTheWay() {
        Player player = body.player();
        BlockReader live = body.live();
        for (Pos spot : nearbySpots(Pos.of(player.getLocation()))) {
            if (fits(live, spot)) {
                continue;
            }
            Material there = live.type(spot.x(), spot.y(), spot.z());
            if (Blocks.passable(there) || Blocks.fluid(there)) {
                continue;
            }
            if (!Blocks.ground(live.type(spot.x(), spot.y() - 1, spot.z()))) {
                continue;
            }
            if (!body.policy().mayBreak(live, spot.x(), spot.y(), spot.z())) {
                continue;
            }
            return spot;
        }
        return null;
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

    /**
     * A free cell, or water, next to a solid face to place against, the way a
     * player can place on the side of a wall and not only on the floor.
     */
    private static boolean fits(BlockReader live, Pos pos) {
        if (!Blocks.passable(live.type(pos.x(), pos.y(), pos.z()))) {
            return false;
        }
        for (int[] face : FACES) {
            if (live.type(pos.x() + face[0], pos.y() + face[1], pos.z() + face[2]).isSolid()) {
                return true;
            }
        }
        return false;
    }
}
