package dev.minegoon.world;

import dev.minegoon.MainThread;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
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
        List<Long> wanted = new ArrayList<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                wanted.add(key(cx, cz));
            }
        }
        return of(world, copy(main, world, wanted, false));
    }

    /**
     * Copies the listed chunks, a few per tick. With {@code surface}, each copy
     * also keeps the height map and the biomes.
     */
    public static Map<Long, ChunkSnapshot> copy(MainThread main, World world, List<Long> keys, boolean surface) {
        Map<Long, ChunkSnapshot> copied = new HashMap<>();
        for (int from = 0; from < keys.size(); from += CHUNKS_PER_TICK) {
            List<Long> batch = keys.subList(from, Math.min(keys.size(), from + CHUNKS_PER_TICK));
            copied.putAll(main.call(() -> {
                Map<Long, ChunkSnapshot> some = new HashMap<>();
                for (long key : batch) {
                    int cx = chunkX(key);
                    int cz = chunkZ(key);
                    if (world.isChunkLoaded(cx, cz)) {
                        some.put(key, world.getChunkAt(cx, cz).getChunkSnapshot(surface, surface, false));
                    }
                }
                return some;
            }));
        }
        return copied;
    }

    public static WorldView of(World world, Map<Long, ChunkSnapshot> chunks) {
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

    /** Light from the sky in a cell, 0 to 15, whatever the time of day. 0 when not loaded. */
    public int skyLight(int x, int y, int z) {
        ChunkSnapshot chunk = chunk(x, y, z);
        return chunk == null ? 0 : chunk.getBlockSkyLight(x & 15, y, z & 15);
    }

    /** The y of the top non-air block in a column. Needs a surface copy; {@link #minY()} when unknown. */
    public int highest(int x, int z) {
        ChunkSnapshot chunk = chunks.get(key(x >> 4, z >> 4));
        return chunk == null ? minY : chunk.getHighestBlockYAt(x & 15, z & 15);
    }

    /** Needs a surface copy. Null when the column is not loaded. */
    public Biome biome(int x, int y, int z) {
        ChunkSnapshot chunk = chunk(x, y, z);
        return chunk == null ? null : chunk.getBiome(x & 15, y, z & 15);
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

    public boolean coversChunk(long key) {
        return chunks.containsKey(key);
    }

    private ChunkSnapshot chunk(int x, int y, int z) {
        if (y < minY || y >= maxY) {
            return null;
        }
        return chunks.get(key(x >> 4, z >> 4));
    }

    public static long key(long cx, long cz) {
        return (cx << 32) ^ (cz & 0xffffffffL);
    }

    public static int chunkX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkZ(long key) {
        return (int) key;
    }
}
