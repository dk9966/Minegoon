package dev.minegoon.world;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/** Read-only block access, backed either by the live world or by chunk snapshots. */
public interface BlockReader {

    String world();

    /** Unknown or unloaded positions read as {@link Material#BARRIER}: not walkable, not breakable. */
    Material type(int x, int y, int z);

    BlockData data(int x, int y, int z);

    /** Main thread only. */
    static BlockReader live(World world) {
        return new BlockReader() {
            @Override
            public String world() {
                return world.getName();
            }

            @Override
            public Material type(int x, int y, int z) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    return Material.BARRIER;
                }
                return world.getType(x, y, z);
            }

            @Override
            public BlockData data(int x, int y, int z) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    return Material.BARRIER.createBlockData();
                }
                return world.getBlockData(x, y, z);
            }
        };
    }
}
