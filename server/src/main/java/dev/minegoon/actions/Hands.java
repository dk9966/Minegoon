package dev.minegoon.actions;

import dev.minegoon.ItemSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Inventory handling. Main thread only. */
public final class Hands {

    private static final int HOTBAR = 9;
    /** Ticks a wear-only tool counts as slower than a bare hand, so it is kept for what it is for. */
    private static final double WEAR = 0.5;

    private Hands() {
    }

    public static int count(Player player, ItemSet items) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && items.contains(stack.getType())) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /** Removes up to {@code amount} matching items and returns how many were taken. */
    public static int remove(Player player, ItemSet items, int amount) {
        return take(player, items, amount).stream().mapToInt(ItemStack::getAmount).sum();
    }

    /** Removes up to {@code amount} matching items and returns them as stacks. */
    public static List<ItemStack> take(Player player, ItemSet items, int amount) {
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> taken = new ArrayList<>();
        int left = amount;
        for (int slot = 0; slot < inventory.getSize() && left > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || !items.contains(stack.getType())) {
                continue;
            }
            int portion = Math.min(left, stack.getAmount());
            taken.add(stack.asQuantity(portion));
            stack.setAmount(stack.getAmount() - portion);
            inventory.setItem(slot, stack.getAmount() == 0 ? null : stack);
            left -= portion;
        }
        return taken;
    }

    /** The material in the set the helper holds most of, or null. */
    public static Material mostOf(Player player, ItemSet items) {
        Material best = null;
        int bestCount = 0;
        for (Material material : items.members()) {
            int count = count(player, ItemSet.of(material));
            if (count > bestCount) {
                best = material;
                bestCount = count;
            }
        }
        return best;
    }

    /**
     * Puts whatever breaks this block fastest into the main hand. Iron and
     * better tools are spared when a cheaper tool also suits the block, so an
     * iron pickaxe lasts to the diamonds instead of wearing out on the stairs down.
     * A tool that would only wear out on the block, like a sword on dirt, counts
     * as a little slower than a bare hand.
     */
    public static void holdFastestFor(Player player, BlockData block) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        boolean spare = Arrays.stream(storage)
            .anyMatch(stack -> stack != null && !precious(stack) && block.isPreferredTool(stack));
        int bestSlot = inventory.getHeldItemSlot();
        double best = breakCost(block, storage[bestSlot], spare);
        for (int slot = 0; slot < storage.length; slot++) {
            double cost = breakCost(block, storage[slot], spare);
            if (cost < best) {
                bestSlot = slot;
                best = cost;
            }
        }
        hold(player, bestSlot);
    }

    private static double breakCost(BlockData block, ItemStack stack, boolean spare) {
        ItemStack tool = stack == null ? ItemStack.empty() : stack;
        if (spare && precious(tool)) {
            return Double.MAX_VALUE;
        }
        boolean wears = tool.getType().getMaxDurability() > 0 && !block.isPreferredTool(tool);
        return Digging.ticks(block, tool) + (wears ? WEAR : 0);
    }

    private static boolean precious(ItemStack stack) {
        String name = stack.getType().name();
        return name.startsWith("IRON_") || name.startsWith("GOLDEN_") || name.startsWith("DIAMOND_")
            || name.startsWith("NETHERITE_");
    }

    /** Wooden or stone pickaxes still in the inventory. The iron one is kept for ore. */
    public static int cheapPicks(Player player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && (stack.getType() == Material.WOODEN_PICKAXE
                    || stack.getType() == Material.STONE_PICKAXE)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Stone pickaxes the cobble and sticks already held can make. Cobbled
     * deepslate counts: it crafts the same tools cobblestone does.
     */
    public static int craftableStonePicks(Player player) {
        int sticks = count(player, ItemSet.of(Material.STICK));
        int cobble = count(player, ItemSet.of(Material.COBBLESTONE))
            + count(player, ItemSet.of(Material.COBBLED_DEEPSLATE));
        return Math.min(sticks / 2, cobble / 3);
    }

    /** Puts {@code material} in the main hand. Returns false when the helper has none. */
    public static boolean hold(Player player, Material material) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getStorageContents().length; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && stack.getType() == material) {
                hold(player, slot);
                return true;
            }
        }
        return false;
    }

    public static void hold(Player player, int slot) {
        PlayerInventory inventory = player.getInventory();
        if (slot < HOTBAR) {
            inventory.setHeldItemSlot(slot);
            return;
        }
        int held = inventory.getHeldItemSlot();
        ItemStack fromStorage = inventory.getItem(slot);
        inventory.setItem(slot, inventory.getItem(held));
        inventory.setItem(held, fromStorage);
    }

    /** Copies of everything that could matter for digging speed. */
    public static List<ItemStack> tools(Player player) {
        List<ItemStack> tools = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType().getMaxDurability() > 0) {
                tools.add(stack.clone());
            }
        }
        return tools;
    }

    public static boolean full(Player player) {
        return player.getInventory().firstEmpty() == -1;
    }
}
