package dev.minegoon.actions;

import dev.minegoon.safety.BreakPolicy;
import dev.minegoon.world.BlockReader;
import dev.minegoon.world.Blocks;
import dev.minegoon.world.Clearance;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Costs of digging through blocks with the tools the helper is carrying, for
 * route planning. Built on the main thread, then read on the search thread.
 */
public final class Digging implements Clearance {

    private static final ItemStack BARE_HAND = ItemStack.empty();
    /** Blocks at least this hard are rock: a route goes round them through open caves when it can. */
    private static final float ROCK = 1;
    /**
     * About fifty blocks of sprinting for each block of rock, so a walk does
     * not turn into a tunnel or a shaft: it digs only a notch where going
     * round is very much longer, such as out of a pit.
     */
    private static final double ROCK_DETOUR = 180;
    /** Dirt, sand and gravel: soft, but a run of them dug out is still a tunnel through a hill. */
    private static final float EARTH = 0.5f;
    private static final double EARTH_DETOUR = 40;

    private final List<ItemStack> tools;
    private final BreakPolicy policy;
    private final Map<BlockData, Double> cache = new HashMap<>();

    private Digging(List<ItemStack> tools, BreakPolicy policy) {
        this.tools = tools;
        this.policy = policy;
    }

    /** Main thread. */
    public static Digging of(Player player, BreakPolicy policy) {
        return new Digging(Hands.tools(player), policy);
    }

    @Override
    public double cost(BlockReader world, int x, int y, int z) {
        Material material = world.type(x, y, z);
        if (Blocks.passable(material)) {
            return 0;
        }
        if (!policy.mayBreak(world, x, y, z) || floodsIfOpened(world, x, y, z)) {
            return IMPOSSIBLE;
        }
        return cache.computeIfAbsent(world.data(x, y, z), data -> {
            double best = ticks(data, BARE_HAND);
            for (ItemStack tool : tools) {
                best = Math.min(best, ticks(data, tool));
            }
            float hardness = data.getMaterial().getHardness();
            return hardness >= ROCK ? best * 2 + ROCK_DETOUR : hardness >= EARTH ? best + EARTH_DETOUR : best;
        });
    }

    /** Lava or water next to this block would pour in once it is gone. */
    public static boolean floodsIfOpened(BlockReader world, int x, int y, int z) {
        return Blocks.fluid(world.type(x, y + 1, z))
            || Blocks.fluid(world.type(x + 1, y, z))
            || Blocks.fluid(world.type(x - 1, y, z))
            || Blocks.fluid(world.type(x, y, z + 1))
            || Blocks.fluid(world.type(x, y, z - 1));
    }

    /** Lava next to this block. Water the body is already in does not count. */
    public static boolean lavaBeside(BlockReader world, int x, int y, int z) {
        return world.type(x, y + 1, z) == Material.LAVA
            || world.type(x + 1, y, z) == Material.LAVA
            || world.type(x - 1, y, z) == Material.LAVA
            || world.type(x, y, z + 1) == Material.LAVA
            || world.type(x, y, z - 1) == Material.LAVA;
    }

    /** Vanilla's break time for a grounded player holding {@code tool}. */
    public static double ticks(BlockData block, ItemStack tool) {
        float hardness = block.getMaterial().getHardness();
        if (hardness < 0) {
            return IMPOSSIBLE;
        }
        if (hardness == 0) {
            return 1;
        }
        boolean harvests = !block.requiresCorrectToolForDrops() || block.isPreferredTool(tool);
        double perTick = block.getDestroySpeed(tool, true) / hardness / (harvests ? 30 : 100);
        return Math.max(1, Math.ceil(1 / perTick));
    }
}
