package dev.minegoon.trips;

import dev.minegoon.actions.Hands;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Which tool the helper hits with. Hunting a cow, pig, sheep, or chicken is
 * scored by how long a full-charge swing takes to deal 10 damage, a cow's
 * health: a netherite axe kills in one hit and beats a faster sword that needs
 * two. A fight, where the target has more health, uses damage per second
 * instead. Either way a carried tool is put in the hand. Numbers are Java
 * Edition attack damage and attack speed, from the item pages on the Minecraft
 * wiki; copper tools deal the same damage as stone.
 */
final class Arms {

    /** Health of a cow or a pig, the animals a meal is usually hunted from. */
    static final double MEAL = 10;
    private static final int OFFHAND = -1;

    private enum Kind { SWORD, AXE, PICK, SHOVEL, TRIDENT }

    private record Stats(Kind kind, double damage, double speed) {
    }

    private static final Map<Material, Stats> TOOLS = new EnumMap<>(Material.class);

    static {
        sword(Material.NETHERITE_SWORD, 8);
        sword(Material.DIAMOND_SWORD, 7);
        sword(Material.IRON_SWORD, 6);
        sword(Material.COPPER_SWORD, 5);
        sword(Material.STONE_SWORD, 5);
        sword(Material.GOLDEN_SWORD, 4);
        sword(Material.WOODEN_SWORD, 4);
        axe(Material.NETHERITE_AXE, 10, 1.0);
        axe(Material.DIAMOND_AXE, 9, 1.0);
        axe(Material.IRON_AXE, 9, 0.9);
        axe(Material.COPPER_AXE, 9, 0.8);
        axe(Material.STONE_AXE, 9, 0.8);
        axe(Material.GOLDEN_AXE, 7, 1.0);
        axe(Material.WOODEN_AXE, 7, 0.8);
        pick(Material.NETHERITE_PICKAXE, 6);
        pick(Material.DIAMOND_PICKAXE, 5);
        pick(Material.IRON_PICKAXE, 4);
        pick(Material.COPPER_PICKAXE, 3);
        pick(Material.STONE_PICKAXE, 3);
        pick(Material.GOLDEN_PICKAXE, 2);
        pick(Material.WOODEN_PICKAXE, 2);
        shovel(Material.NETHERITE_SHOVEL, 6.5);
        shovel(Material.DIAMOND_SHOVEL, 5.5);
        shovel(Material.IRON_SHOVEL, 4.5);
        shovel(Material.COPPER_SHOVEL, 3.5);
        shovel(Material.STONE_SHOVEL, 3.5);
        shovel(Material.GOLDEN_SHOVEL, 2.5);
        shovel(Material.WOODEN_SHOVEL, 2.5);
        TOOLS.put(Material.TRIDENT, new Stats(Kind.TRIDENT, 9, 1.1));
    }

    private Arms() {
    }

    /** Seconds of cooldown between the first full swing and the one that finishes a meal. */
    static double timeToKill(double damage, double speed) {
        if (damage <= 0 || speed <= 0) {
            return Double.MAX_VALUE;
        }
        int hits = (int) Math.ceil(MEAL / damage - 1e-9);
        return hits <= 1 ? 0 : (hits - 1) / speed;
    }

    /** Damage per second of a sword or an axe, or 0 when {@code material} is not one. */
    static double meleeDps(Material material) {
        if (!melee(material)) {
            return 0;
        }
        Stats stats = TOOLS.get(material);
        return stats.damage * stats.speed;
    }

    /** The hunting tool in {@code carried} that finishes a meal soonest, or null. */
    static Material bestHunt(List<Material> carried) {
        Material best = null;
        double bestTime = Double.MAX_VALUE;
        double bestDamage = -1;
        double bestSpeed = -1;
        for (Material material : carried) {
            Stats stats = TOOLS.get(material);
            if (stats == null) {
                continue;
            }
            double time = timeToKill(stats.damage, stats.speed);
            if (prefers(time, stats.damage, stats.speed, bestTime, bestDamage, bestSpeed)) {
                best = material;
                bestTime = time;
                bestDamage = stats.damage;
                bestSpeed = stats.speed;
            }
        }
        return best;
    }

    /** Swords and axes, highest damage per second first. */
    static List<Material> meleeBestFirst() {
        List<Material> weapons = new ArrayList<>();
        TOOLS.forEach((material, stats) -> {
            if (melee(material)) {
                weapons.add(material);
            }
        });
        weapons.sort(Comparator.comparingDouble(Arms::meleeDps).reversed());
        return List.copyOf(weapons);
    }

    /**
     * Puts the best hunting tool in the main hand. True when that changed what
     * was held, which resets the attack cooldown, so the caller waits a swing.
     */
    static boolean holdForHunt(Player player) {
        return hold(player, material -> TOOLS.containsKey(material), true);
    }

    /** Puts the best sword or axe in the main hand. True when the hand changed. */
    static boolean holdBestMelee(Player player) {
        return hold(player, Arms::melee, false);
    }

    /** A sword or an axe is somewhere in the inventory, including the off hand. */
    static boolean carryingMelee(Player player) {
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && melee(stack.getType())) {
                return true;
            }
        }
        return melee(player.getInventory().getItemInOffHand().getType());
    }

    private static boolean hold(Player player, Predicate<Material> allowed, boolean forHunt) {
        PlayerInventory inventory = player.getInventory();
        int held = inventory.getHeldItemSlot();
        int bestSlot = Integer.MIN_VALUE;
        double bestTime = Double.MAX_VALUE;
        double bestDamage = -1;
        double bestSpeed = -1;
        double bestDps = -1;
        ItemStack[] storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            Rank rank = rank(storage[slot], allowed);
            if (rank == null) {
                continue;
            }
            if (better(forHunt, rank, bestTime, bestDamage, bestSpeed, bestDps)
                || tied(forHunt, rank, bestTime, bestDamage, bestSpeed, bestDps) && slot == held) {
                bestSlot = slot;
                bestTime = rank.time;
                bestDamage = rank.damage;
                bestSpeed = rank.speed;
                bestDps = rank.dps;
            }
        }
        Rank off = rank(inventory.getItemInOffHand(), allowed);
        if (off != null && (bestSlot == Integer.MIN_VALUE
            || better(forHunt, off, bestTime, bestDamage, bestSpeed, bestDps))) {
            bestSlot = OFFHAND;
        }
        if (bestSlot == Integer.MIN_VALUE || bestSlot == held) {
            return false;
        }
        Material before = inventory.getItemInMainHand().getType();
        int sharp = inventory.getItemInMainHand().getEnchantmentLevel(Enchantment.SHARPNESS);
        if (bestSlot == OFFHAND) {
            ItemStack main = inventory.getItemInMainHand();
            inventory.setItemInMainHand(inventory.getItemInOffHand());
            inventory.setItemInOffHand(main.getType().isAir() ? null : main);
        } else {
            Hands.hold(player, bestSlot);
        }
        ItemStack after = inventory.getItemInMainHand();
        return after.getType() != before || after.getEnchantmentLevel(Enchantment.SHARPNESS) != sharp;
    }

    private record Rank(double damage, double speed, double time, double dps) {
    }

    private static Rank rank(ItemStack stack, Predicate<Material> allowed) {
        if (stack == null || stack.getType().isAir() || !allowed.test(stack.getType())) {
            return null;
        }
        Stats stats = TOOLS.get(stack.getType());
        if (stats == null) {
            return null;
        }
        int sharp = stack.getEnchantmentLevel(Enchantment.SHARPNESS);
        double damage = stats.damage + (sharp == 0 ? 0 : 0.5 * sharp + 0.5);
        return new Rank(damage, stats.speed, timeToKill(damage, stats.speed), damage * stats.speed);
    }

    private static boolean better(boolean forHunt, Rank rank, double time, double damage, double speed, double dps) {
        if (forHunt) {
            return prefers(rank.time, rank.damage, rank.speed, time, damage, speed);
        }
        if (rank.dps != dps) {
            return rank.dps > dps;
        }
        return rank.damage > damage;
    }

    private static boolean tied(boolean forHunt, Rank rank, double time, double damage, double speed, double dps) {
        if (forHunt) {
            return rank.time == time && rank.damage == damage && rank.speed == speed;
        }
        return rank.dps == dps && rank.damage == damage;
    }

    /** Lower time first, then the heavier hit, then the faster swing. */
    private static boolean prefers(double time, double damage, double speed, double bestTime, double bestDamage,
                                   double bestSpeed) {
        if (time != bestTime) {
            return time < bestTime;
        }
        if (damage != bestDamage) {
            return damage > bestDamage;
        }
        return speed > bestSpeed;
    }

    private static boolean melee(Material material) {
        Stats stats = TOOLS.get(material);
        return stats != null && (stats.kind == Kind.SWORD || stats.kind == Kind.AXE);
    }

    private static void sword(Material material, double damage) {
        TOOLS.put(material, new Stats(Kind.SWORD, damage, 1.6));
    }

    private static void axe(Material material, double damage, double speed) {
        TOOLS.put(material, new Stats(Kind.AXE, damage, speed));
    }

    private static void pick(Material material, double damage) {
        TOOLS.put(material, new Stats(Kind.PICK, damage, 1.2));
    }

    private static void shovel(Material material, double damage) {
        TOOLS.put(material, new Stats(Kind.SHOVEL, damage, 1.0));
    }
}
