package dev.minegoon.trips;

import dev.minegoon.actions.Look;
import dev.minegoon.spawn.Helper;
import java.util.Comparator;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * What the helper does on its own every tick, whatever job it is on: eat when
 * hungry and hit monsters that come close. Eating uses the off hand so the
 * tool in the main hand stays put. Main thread only.
 */
public final class Reflexes {

    private static final int EAT_BELOW = 15;
    private static final double FIGHT_RANGE = 3;
    private static final float READY = 0.9f;

    private final Helper helper;

    public Reflexes(Helper helper) {
        this.helper = helper;
    }

    public void tick() {
        Player player = helper.player();
        if (player == null || player.isDead()) {
            return;
        }
        eat(player);
        fight(player);
    }

    private static void eat(Player player) {
        if (player.hasActiveItem() || player.getFoodLevel() >= EAT_BELOW) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        if (!Food.edible(inventory.getItemInOffHand().getType())) {
            int slot = Food.bestSlot(player);
            if (slot < 0) {
                return;
            }
            ItemStack food = inventory.getItem(slot);
            inventory.setItem(slot, inventory.getItemInOffHand());
            inventory.setItemInOffHand(food);
        }
        player.startUsingItem(EquipmentSlot.OFF_HAND);
    }

    private static void fight(Player player) {
        if (player.getAttackCooldown() < READY) {
            return;
        }
        player.getNearbyEntities(FIGHT_RANGE, FIGHT_RANGE, FIGHT_RANGE).stream()
            .filter(entity -> entity instanceof Enemy && entity instanceof LivingEntity && !entity.isDead())
            .map(LivingEntity.class::cast)
            .filter(player::hasLineOfSight)
            .min(Comparator.comparingDouble(enemy -> enemy.getLocation().distanceSquared(player.getLocation())))
            .ifPresent(enemy -> {
                Look.at(player, enemy.getEyeLocation());
                player.attack(enemy);
                player.swingMainHand();
            });
    }
}
