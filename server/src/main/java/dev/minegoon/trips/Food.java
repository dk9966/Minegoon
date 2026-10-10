package dev.minegoon.trips;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Food the helper is willing to eat. */
final class Food {

    private static final Set<Material> REFUSED = EnumSet.of(
        Material.ROTTEN_FLESH, Material.SPIDER_EYE, Material.POISONOUS_POTATO, Material.PUFFERFISH,
        Material.CHORUS_FRUIT, Material.SUSPICIOUS_STEW, Material.GOLDEN_APPLE, Material.ENCHANTED_GOLDEN_APPLE);

    private static final Set<Material> COOKED_FIRST = EnumSet.of(
        Material.COOKED_BEEF, Material.COOKED_PORKCHOP, Material.COOKED_MUTTON, Material.COOKED_CHICKEN,
        Material.COOKED_SALMON, Material.COOKED_COD, Material.COOKED_RABBIT, Material.BREAD, Material.BAKED_POTATO);

    private Food() {
    }

    static boolean edible(Material material) {
        return material.isEdible() && !REFUSED.contains(material);
    }

    /** Slot of the best food carried, cooked first, or -1. */
    static int bestSlot(Player player) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int fallback = -1;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || !edible(stack.getType())) {
                continue;
            }
            if (COOKED_FIRST.contains(stack.getType())) {
                return slot;
            }
            if (fallback < 0) {
                fallback = slot;
            }
        }
        return fallback;
    }

    static boolean any(Player player) {
        return bestSlot(player) >= 0 || edible(player.getInventory().getItemInOffHand().getType());
    }
}
