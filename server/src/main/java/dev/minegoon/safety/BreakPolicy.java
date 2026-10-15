package dev.minegoon.safety;

import dev.minegoon.Database;
import dev.minegoon.world.BlockReader;
import dev.minegoon.world.Blocks;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;

/**
 * Decides, for every block, whether the helper may break it. The navigator,
 * the miner, and a last-line event guard all ask this one class.
 */
public final class BreakPolicy {

    private static final int TREE_REACH = 6;
    /** As far up a trunk as the leaves are looked for. Giant jungle trunks run about 30 logs. */
    private static final int TRUNK = 32;

    private final Database db;
    private final Zones zones;
    private final Set<String> placed = ConcurrentHashMap.newKeySet();

    public BreakPolicy(Database db, Zones zones) {
        this.db = db;
        this.zones = zones;
        placed.addAll(db.query("SELECT world, x, y, z FROM placed_blocks",
            row -> key(row.getString("world"), row.getInt("x"), row.getInt("y"), row.getInt("z"))));
    }

    public boolean mayBreak(BlockReader world, int x, int y, int z) {
        Material material = world.type(x, y, z);
        if (material.isAir() || material.getHardness() < 0 || Blocks.fluid(material)) {
            return false;
        }
        if (zones.protects(world.world(), x, z)) {
            return false;
        }
        if (placed.contains(key(world.world(), x, y, z)) || zones.allows(world.world(), x, z)) {
            return true;
        }
        if (PlayerMade.is(material)) {
            return false;
        }
        if (Tag.LEAVES.isTagged(material)) {
            return !(world.data(x, y, z) instanceof Leaves leaves && leaves.isPersistent());
        }
        if (Tag.LOGS.isTagged(material)) {
            return partOfTree(world, x, y, z);
        }
        return true;
    }

    /** Remembers blocks the helper put down itself, such as its crafting table, so it may take them back. */
    public void recordPlaced(Block block) {
        String key = key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        if (placed.add(key)) {
            db.update("INSERT OR IGNORE INTO placed_blocks (world, x, y, z) VALUES (?, ?, ?, ?)",
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }
    }

    public boolean placedByHelper(Block block) {
        return placed.contains(key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
    }

    public void forgetPlaced(Block block) {
        if (placed.remove(key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()))) {
            db.update("DELETE FROM placed_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?",
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }
    }

    /**
     * A log counts as a tree only if leaves that grew naturally sit near the top
     * of its trunk, the logs straight above it. Log cabins have none.
     */
    private static boolean partOfTree(BlockReader world, int x, int y, int z) {
        int top = y;
        while (top - y < TRUNK && Tag.LOGS.isTagged(world.type(x, top + 1, z))) {
            top++;
        }
        for (int level = top + TREE_REACH; level >= y; level--) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (world.data(x + dx, level, z + dz) instanceof Leaves leaves && !leaves.isPersistent()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String key(String world, int x, int y, int z) {
        return world + ':' + x + ':' + y + ':' + z;
    }
}
