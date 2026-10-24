package dev.minegoon.trips;

import dev.minegoon.MainThread;
import dev.minegoon.actions.Checkpoint;
import dev.minegoon.spawn.Helper;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import dev.minegoon.world.Blocks;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;

/**
 * The rules trips check between moves. Most trouble is handled on the spot:
 * anything attacking is run from (see {@link Fighter#defend}), low health is waited out while eating, and a
 * full inventory drops its junk. What needs other work first is thrown as
 * {@link Needed}: no food left, no room even after dropping junk, or the last
 * tool for the job about to break.
 */
public final class Retreat implements Checkpoint {

    private static final double LOW_HEALTH = 10;
    private static final double HEALED = 16;
    static final int REGENERATES_AT = 18;
    private static final int HEAL_SECONDS = 90;
    private static final int HUNGRY = 6;
    private static final int MEAL = 10;
    private static final double WORN = 0.05;
    private static final int NEVER = 32767;
    private static final Set<Material> JUNK = EnumSet.of(
        Material.DIRT, Material.GRAVEL, Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.TUFF,
        Material.CALCITE, Material.DRIPSTONE_BLOCK, Material.POINTED_DRIPSTONE, Material.NETHERRACK,
        Material.COBBLESTONE, Material.COBBLED_DEEPSLATE,
        Material.ROTTEN_FLESH, Material.POISONOUS_POTATO, Material.SPIDER_EYE);

    public enum Reason { HUNGRY, FULL, TOOL, NIGHT }

    public static final class Needed extends RuntimeException {
        private final Reason reason;

        public Needed(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    private final MainThread main;
    private final Helper helper;
    private final Fighter fighter;
    private volatile boolean nightCovered;
    private volatile Set<Material> keep = Set.of();
    private volatile boolean spendingLastTool;

    public Retreat(MainThread main, Helper helper, Fighter fighter) {
        this.main = main;
        this.helper = helper;
        this.fighter = fighter;
    }

    /** Items the current work needs, so they are never dropped as junk. */
    public void keep(Set<Material> items) {
        keep = Set.copyOf(items);
    }

    @Override
    public void check() {
        steady(false);
    }

    /** Hunger does not end this trip. Used while the helper is out getting food. */
    public Checkpoint ignoringHunger() {
        return () -> steady(true);
    }

    /** Fights and heals, but needs nothing: on the way home the trip is over. */
    public Checkpoint homeward() {
        return () -> {
            fighter.defend();
            heal(true);
        };
    }

    /** While a replacement is being made, the worn tool may be used to the end to make it. */
    public void spendLastTool(boolean spending) {
        spendingLastTool = spending;
    }

    /**
     * Also stops when every tool that passes {@code needed} is about to break, or
     * the last one broke. A worn tool's last uses still go on targets already in
     * sight; it is replaced when there are none left to spend them on. Work that
     * needs no tool, such as logs, never stops for one.
     */
    public Checkpoint guarding(Predicate<ItemStack> needed, BooleanSupplier targetInSight) {
        return new Checkpoint() {
            private boolean hadTool;

            @Override
            public void check() {
                steady(false);
                if (spendingLastTool) {
                    return;
                }
                Tools tools = main.call(() -> tools(helper.player(), needed));
                hadTool |= tools != Tools.NONE;
                if (tools == Tools.NONE && hadTool || tools == Tools.WORN && !targetInSight.getAsBoolean()) {
                    throw new Needed(Reason.TOOL, "my last tool for this is about to break");
                }
            }
        };
    }

    /** The helper should go get food before the trip falls over. */
    public boolean needsMeal() {
        return main.call(() -> {
            Player player = helper.player();
            return player != null && !player.isDead() && player.getFoodLevel() <= MEAL && !Food.any(player);
        });
    }

    /**
     * When every tool that passes {@code needed} is nearly broken, the material
     * of the best one, so a replacement can be crafted. Null if any of them is fine,
     * or if the helper has none.
     */
    public Material wornTool(Predicate<ItemStack> needed) {
        return main.call(() -> {
            Player player = helper.player();
            if (player == null) {
                return null;
            }
            Material best = null;
            boolean healthy = false;
            for (ItemStack stack : player.getInventory().getStorageContents()) {
                if (stack == null || !needed.test(stack)) {
                    continue;
                }
                if (!worn(stack)) {
                    healthy = true;
                } else if (best == null || stack.getType().getMaxDurability() > best.getMaxDurability()) {
                    best = stack.getType();
                }
            }
            return healthy ? null : best;
        });
    }

    /** Tonight is taken care of: the helper is dug in or asleep, or chose to carry on. */
    public void nightCovered() {
        nightCovered = true;
    }

    private void steady(boolean hungerIsFine) {
        fighter.defend();
        heal(hungerIsFine);
        if (!hungerIsFine && main.call(this::starving)) {
            throw new Needed(Reason.HUNGRY, "I ran out of food");
        }
        if (!hungerIsFine && main.call(this::exposed)) {
            throw new Needed(Reason.NIGHT, "It's getting dark and I have no armor");
        }
        if (main.call(this::stillFull)) {
            throw new Needed(Reason.FULL, "my inventory is full of things I need");
        }
    }

    /** Waits for health to come back, fighting off anything that comes and eating to keep it coming. */
    private void heal(boolean hungerIsFine) {
        if (main.call(() -> health() >= LOW_HEALTH)) {
            return;
        }
        if (hungerIsFine && main.call(this::cannotMend)) {
            return;
        }
        helper.say("I'm hurt, so I'll stop and heal.");
        for (int second = 0; second < HEAL_SECONDS; second++) {
            main.run(this::digOut);
            fighter.defend();
            switch (main.call(this::mend)) {
                case HEALED -> {
                    return;
                }
                case STARVING -> {
                    if (!hungerIsFine) {
                        throw new Needed(Reason.HUNGRY, "I'm hurt and have nothing to eat");
                    }
                    return;
                }
                case HEALING -> {
                }
            }
            main.sleep(20);
        }
    }

    private enum Mend { HEALED, HEALING, STARVING }

    /** A body left inside a block keeps taking damage until that block is gone. */
    private void digOut() {
        Player player = helper.player();
        if (player == null || player.isDead()) {
            return;
        }
        Block feet = player.getLocation().getBlock();
        for (Block block : new Block[] {feet.getRelative(0, 1, 0), feet}) {
            if (!Blocks.passable(block.getType())) {
                player.breakBlock(block);
            }
        }
    }

    /** Health only comes back on a nearly full food bar, so keep eating while waiting. */
    private Mend mend() {
        Player player = helper.player();
        if (player == null || player.isDead() || player.getHealth() >= HEALED) {
            return Mend.HEALED;
        }
        boolean fed = Reflexes.eat(player, 20) || player.getFoodLevel() >= REGENERATES_AT;
        return fed ? Mend.HEALING : Mend.STARVING;
    }

    /** Nothing to eat and too hungry to heal on its own, so waiting brings no health back. */
    private boolean cannotMend() {
        Player player = helper.player();
        return player != null && !player.isDead() && player.getFoodLevel() < REGENERATES_AT && !Food.any(player);
    }

    private double health() {
        Player player = helper.player();
        return player == null || player.isDead() ? Double.MAX_VALUE : player.getHealth();
    }

    /**
     * Night under the open sky with no armor. Out there mobs spawn on every
     * dark patch of ground and come from all sides, more than a bare body
     * outlasts.
     */
    private boolean exposed() {
        Player player = helper.player();
        if (player == null || player.isDead() || !Sleeper.nightNow(player)) {
            nightCovered = false;
            return false;
        }
        AttributeInstance armor = player.getAttribute(Attribute.ARMOR);
        return !nightCovered && (armor == null || armor.getValue() < 1)
            && player.getEyeLocation().getBlock().getLightFromSky() > 0;
    }

    private boolean starving() {
        Player player = helper.player();
        return player != null && !player.isDead() && player.getFoodLevel() <= HUNGRY && !Food.any(player);
    }

    /** Drops junk when the inventory is full. True when that did not free a slot. */
    private boolean stillFull() {
        Player player = helper.player();
        if (player == null || player.isDead() || player.getInventory().firstEmpty() != -1) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getStorageContents().length; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && JUNK.contains(stack.getType()) && !keep.contains(stack.getType())) {
                inventory.setItem(slot, null);
                Item dropped = player.getWorld().dropItem(player.getEyeLocation(), stack);
                dropped.setPickupDelay(NEVER);
            }
        }
        return inventory.firstEmpty() == -1;
    }

    /** No tool that passes {@code needed} is in good shape: they are all worn, or there are none. */
    private enum Tools { HEALTHY, WORN, NONE }

    private static Tools tools(Player player, Predicate<ItemStack> needed) {
        Tools held = Tools.NONE;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && needed.test(stack)) {
                if (!worn(stack)) {
                    return Tools.HEALTHY;
                }
                held = Tools.WORN;
            }
        }
        return held;
    }

    private static boolean worn(ItemStack stack) {
        int max = stack.getType().getMaxDurability();
        int damage = stack.getItemMeta() instanceof Damageable meta ? meta.getDamage() : 0;
        return max > 0 && max - damage <= max * WORN;
    }
}
