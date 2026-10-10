package dev.minegoon.world;

import dev.minegoon.MainThread;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/**
 * An immutable copy of the loaded chunks in an area, safe to read from any
 * thread. Searches run against this so the server thread never stalls.
 */
public final class WorldView implements BlockReader {

    private static final int CHUNKS_PER_TICK = 16;
    private static final BlockData BARRIER = Material.BARRIER.createBlockData();

    private final String world;
    private final int minY;
    private final int maxY;
    private final Map<Long, ChunkSnapshot> chunks;

    private WorldView(String world, int minY, int maxY, Map<Long, ChunkSnapshot> chunks) {
        this.world = world;
        this.minY = minY;
        this.maxY = maxY;
        this.chunks = chunks;
    }

    /** Copies every loaded chunk touching the block rectangle, a few chunks per tick. */
    public static WorldView capture(MainThread main, World world, int minX, int minZ, int maxX, int maxZ) {
        List<long[]> wanted = new ArrayList<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                wanted.add(new long[] {cx, cz});
            }
        }
        Map<Long, ChunkSnapshot> chunks = new HashMap<>();
        for (int from = 0; from < wanted.size(); from += CHUNKS_PER_TICK) {
            List<long[]> batch = wanted.subList(from, Math.min(wanted.size(), from + CHUNKS_PER_TICK));
            chunks.putAll(main.call(() -> {
                Map<Long, ChunkSnapshot> copied = new HashMap<>();
                for (long[] chunk : batch) {
                    int cx = (int) chunk[0];
                    int cz = (int) chunk[1];
                    if (world.isChunkLoaded(cx, cz)) {
                        copied.put(key(cx, cz), world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false));
                    }
                }
                return copied;
            }));
        }
        return new WorldView(world.getName(), world.getMinHeight(), world.getMaxHeight(), chunks);
    }

    @Override
    public String world() {
        return world;
    }

    @Override
    public Material type(int x, int y, int z) {
        ChunkSnapshot chunk = chunk(x, y, z);
        return chunk == null ? Material.BARRIER : chunk.getBlockType(x & 15, y, z & 15);
    }

    @Override
    public BlockData data(int x, int y, int z) {
        ChunkSnapshot chunk = chunk(x, y, z);
        return chunk == null ? BARRIER : chunk.getBlockData(x & 15, y, z & 15);
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public boolean covers(int x, int z) {
        return chunks.containsKey(key(x >> 4, z >> 4));
    }

    private ChunkSnapshot chunk(int x, int y, int z) {
        if (y < minY || y >= maxY) {
            return null;
        }
        return chunks.get(key(x >> 4, z >> 4));
    }

    private static long key(long cx, long cz) {
        return (cx << 32) ^ (cz & 0xffffffffL);
    }
}
