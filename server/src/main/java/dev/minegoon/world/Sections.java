package dev.minegoon.world;

import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CraftBlockType;

/**
 * Which 16-block-tall slices of a chunk might hold a block, read from each
 * slice's palette instead of its 4096 cells. A search across the whole render
 * distance only walks the slices that answer yes. This and the fake player
 * are the only classes that touch server internals.
 */
public final class Sections {

    private Sections() {
    }

    /** Server blocks for a set of materials, built once per search. */
    public static Set<Block> blocks(Set<Material> materials) {
        return materials.stream()
            .filter(Material::isBlock)
            .map(CraftBlockType::bukkitToMinecraft)
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Main thread. Bit {@code i} is set when slice {@code i} (counted from the
     * bottom of the world) may contain one of the blocks. Zero for an unloaded chunk.
     */
    public static long mask(World world, int cx, int cz, Set<Block> wanted) {
        LevelChunk chunk = ((CraftWorld) world).getHandle().getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) {
            return 0;
        }
        LevelChunkSection[] sections = chunk.getSections();
        long mask = 0;
        for (int i = 0; i < sections.length && i < Long.SIZE; i++) {
            LevelChunkSection section = sections[i];
            if (section != null && !section.hasOnlyAir()
                && section.getStates().maybeHas(state -> wanted.contains(state.getBlock()))) {
                mask |= 1L << i;
            }
        }
        return mask;
    }
}
