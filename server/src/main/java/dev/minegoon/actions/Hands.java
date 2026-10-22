package dev.minegoon.actions;

import dev.minegoon.ItemSet;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.Tag;
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
     * Puts whatever breaks this block fastest into the main hand: the best tool
     * held is the one used, since time matters more than wear. A tool that would
     * only wear out on the block, like a sword on dirt, counts as a little slower
     * than a bare hand.
     */
    public static void holdFastestFor(Player player, BlockData block) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        int bestSlot = inventory.getHeldItemSlot();
        double best = breakCost(block, storage[bestSlot]);
        for (int slot = 0; slot < storage.length; slot++) {
            double cost = breakCost(block, storage[slot]);
            if (cost < best) {
                bestSlot = slot;
                best = cost;
            }
        }
        hold(player, bestSlot);
    }

    /**
     * A sword is a weapon: it loses two points a block and is what the helper
     * fights with, so it only cuts cobwebs. Paper calls any tool preferred on a
     * block that needs none, like leaves or dirt, so wear is judged by speed.
     */
    private static double breakCost(BlockData block, ItemStack stack) {
        ItemStack tool = stack == null ? ItemStack.empty() : stack;
        if (Tag.ITEMS_SWORDS.isTagged(tool.getType()) && block.getMaterial() != Material.COBWEB) {
            return Double.MAX_VALUE;
        }
        boolean wears = tool.getType().getMaxDurability() > 0 && block.getDestroySpeed(tool, false) <= 1;
        return Digging.ticks(block, tool) + (wears ? WEAR : 0);
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
