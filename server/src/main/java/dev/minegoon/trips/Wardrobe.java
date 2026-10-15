package dev.minegoon.trips;

import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Wears the best armor the helper carries, slot by slot, judged by the armor and toughness it gives. */
final class Wardrobe {

    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
        EquipmentSlot.FEET};

    private Wardrobe() {
    }

    static void dress(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (EquipmentSlot slot : SLOTS) {
            int best = -1;
            double bestValue = protection(inventory.getItem(slot).getType(), slot);
            for (int index = 0; index < inventory.getStorageContents().length; index++) {
                ItemStack item = inventory.getItem(index);
                double value = item == null ? 0 : protection(item.getType(), slot);
                if (value > bestValue) {
                    best = index;
                    bestValue = value;
                }
            }
            if (best >= 0) {
                ItemStack worn = inventory.getItem(slot);
                inventory.setItem(slot, inventory.getItem(best));
                inventory.setItem(best, worn.isEmpty() ? null : worn);
            }
        }
    }

    private static double protection(Material material, EquipmentSlot slot) {
        if (material.isAir() || !material.isItem() || material.getEquipmentSlot() != slot) {
            return 0;
        }
        return material.getDefaultAttributeModifiers(slot).entries().stream()
            .filter(entry -> entry.getKey() == Attribute.ARMOR || entry.getKey() == Attribute.ARMOR_TOUGHNESS)
            .mapToDouble(entry -> entry.getValue().getAmount())
            .sum();
    }
}
