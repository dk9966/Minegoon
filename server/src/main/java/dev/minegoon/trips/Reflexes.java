package dev.minegoon.trips;

import dev.minegoon.actions.Hands;
import dev.minegoon.actions.Look;
import dev.minegoon.spawn.FakePlayer;
import dev.minegoon.spawn.FakePlayer.Controls;
import dev.minegoon.spawn.Helper;
import dev.minegoon.world.Blocks;
import dev.minegoon.world.Pos;
import java.util.Comparator;
import java.util.function.BooleanSupplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * What the helper does on its own every tick, whatever job it is on: empty a
 * water bucket under itself before a fall lands, climb out of lava, eat when
 * hungry, and hit monsters that come close. Eating uses the off hand so the
 * tool in the main hand stays put. Eating and hitting wait while a real fight
 * has the body; falls and lava do not. Main thread only.
 */
public final class Reflexes {

    static final int EAT_BELOW = 15;
    private static final double FIGHT_RANGE = 3;
    private static final float READY = 0.9f;
    /** Falls up to this far do no damage. */
    private static final double SAFE_FALL = 3;
    /** Feet this close above the ground at most: the eyes can still reach the block to pour on. */
    private static final double POUR_HEIGHT = 2.8;
    private static final int DRESS_EVERY = 20;
    private static final int[][] AROUND = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final Helper helper;
    private final BooleanSupplier fighting;
    private Pos poured;

    public Reflexes(Helper helper, BooleanSupplier fighting) {
        this.helper = helper;
        this.fighting = fighting;
    }

    public void tick() {
        Player player = helper.player();
        if (player == null || player.isDead()) {
            poured = null;
            return;
        }
        if (player.isInLava()) {
            escapeLava(player);
            return;
        }
        if (player.getTicksLived() % DRESS_EVERY == 0) {
            Wardrobe.dress(player);
        }
        if (clutch(player) || fighting.getAsBoolean()) {
            return;
        }
        eat(player, EAT_BELOW);
        fight(player);
    }

    /** Starts a bite when the food bar is under {@code below}. False when there is nothing to eat. */
    static boolean eat(Player player, int below) {
        if (player.hasActiveItem() || player.getFoodLevel() >= below) {
            return true;
        }
        PlayerInventory inventory = player.getInventory();
        if (!Food.edible(inventory.getItemInOffHand().getType())) {
            int slot = Food.bestSlot(player);
            if (slot < 0) {
                return false;
            }
            ItemStack food = inventory.getItem(slot);
            inventory.setItem(slot, inventory.getItemInOffHand());
            inventory.setItemInOffHand(food);
        }
        player.startUsingItem(EquipmentSlot.OFF_HAND);
        return true;
    }

    /**
     * Pours water where a hurtful fall is about to land, looking straight down,
     * and once landed scoops it back up. True while it has the hands.
     */
    private boolean clutch(Player player) {
        boolean landed = FakePlayer.grounded(player) || player.isInWater();
        if (poured != null) {
            if (!landed) {
                return true;
            }
            if (Hands.hold(player, Material.BUCKET)) {
                FakePlayer.face(player, player.getLocation().getYaw(), 90);
                FakePlayer.use(player);
            }
            poured = null;
            return true;
        }
        if (landed || player.getFallDistance() <= SAFE_FALL || player.getVelocity().getY() >= 0
            || !player.getInventory().contains(Material.WATER_BUCKET)) {
            return false;
        }
        Location at = player.getLocation();
        World world = player.getWorld();
        int floor = at.getBlockY() - 1;
        while (floor > at.getBlockY() - 4 && Blocks.passable(world.getType(at.getBlockX(), floor, at.getBlockZ()))) {
            floor--;
        }
        Material ground = world.getType(at.getBlockX(), floor, at.getBlockZ());
        double height = at.getY() - (floor + 1);
        if (Blocks.passable(ground) || Blocks.water(ground) || height > POUR_HEIGHT
            || player.getFallDistance() + height <= SAFE_FALL) {
            return false;
        }
        Hands.hold(player, Material.WATER_BUCKET);
        FakePlayer.face(player, at.getYaw(), 90);
        if (FakePlayer.use(player)) {
            poured = new Pos(at.getBlockX(), floor + 1, at.getBlockZ());
        }
        return true;
    }

    /** Swims up and toward the nearest column with solid, lava-free footing. */
    private void escapeLava(Player player) {
        Location at = player.getLocation();
        World world = player.getWorld();
        int y = at.getBlockY();
        for (int[] d : AROUND) {
            int x = at.getBlockX() + d[0];
            int z = at.getBlockZ() + d[1];
            for (int up = 0; up <= 1; up++) {
                if (Blocks.ground(world.getType(x, y + up - 1, z)) && Blocks.passable(world.getType(x, y + up, z))
                    && Blocks.passable(world.getType(x, y + up + 1, z))) {
                    Look.toward(player, x + 0.5, z + 0.5);
                    helper.override(new Controls(1, 0, true, false));
                    return;
                }
            }
        }
        helper.override(new Controls(0, 0, true, false));
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
