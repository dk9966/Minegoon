package dev.minegoon.world;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

public record Pos(int x, int y, int z) {

    public static Pos of(Location location) {
        return new Pos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /**
     * The feet block of a body at this location. A little slack makes standing on
     * farmland or a path, which are not quite a full block tall, count as on top.
     */
    public static Pos standing(Location at) {
        return new Pos(at.getBlockX(), (int) Math.floor(at.getY() + 0.2), at.getBlockZ());
    }

    public static Pos of(Block block) {
        return new Pos(block.getX(), block.getY(), block.getZ());
    }

    public Pos offset(int dx, int dy, int dz) {
        return new Pos(x + dx, y + dy, z + dz);
    }

    public Block block(World world) {
        return world.getBlockAt(x, y, z);
    }

    /** Standing position: centred on the block, feet on its floor. */
    public Location feet(World world) {
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    public double distanceSquared(Pos other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public int manhattan(Pos other) {
        return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
    }

    /** Packs the position into one long, for maps keyed by position. */
    public long key() {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }
}
