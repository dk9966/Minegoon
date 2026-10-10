package dev.minegoon.trips;

import dev.minegoon.MainThread;
import dev.minegoon.actions.Checkpoint;
import dev.minegoon.spawn.Helper;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

/**
 * The rules for when a trip should end early and the helper should come home.
 * Trips call {@link #check()} between moves; it throws {@link Needed}.
 */
public final class Retreat implements Checkpoint {

    private static final double LOW_HEALTH = 10;
    private static final long LOW_HEALTH_GRACE_MS = 5_000;
    private static final int HUNGRY = 6;
    private static final double WORN = 0.05;

    private final MainThread main;
    private final Helper helper;
    private long lowHealthSince;

    public Retreat(MainThread main, Helper helper) {
        this.main = main;
        this.helper = helper;
    }

    public static final class Needed extends RuntimeException {
        public Needed(String reason) {
            super(reason);
        }
    }

    @Override
    public void check() {
        String reason = main.call(this::reason);
        if (reason != null) {
            throw new Needed(reason);
        }
    }

    /** Also retreats when every tool that passes {@code needed} is about to break. */
    public Checkpoint guarding(Predicate<ItemStack> needed) {
        return () -> {
            check();
            if (main.call(() -> lastToolWorn(helper.player(), needed))) {
                throw new Needed("my last tool for this is about to break");
            }
        };
    }

    private String reason() {
        Player player = helper.player();
        if (player == null || player.isDead()) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (player.getHealth() >= LOW_HEALTH) {
            lowHealthSince = 0;
        } else if (lowHealthSince == 0) {
            lowHealthSince = now;
        } else if (now - lowHealthSince > LOW_HEALTH_GRACE_MS) {
            lowHealthSince = 0;
            return "my health stayed low";
        }
        if (player.getFoodLevel() <= HUNGRY && !Food.any(player)) {
            return "I ran out of food";
        }
        if (player.getInventory().firstEmpty() == -1) {
            return "my inventory is full";
        }
        return null;
    }

    private static boolean lastToolWorn(Player player, Predicate<ItemStack> needed) {
        boolean any = false;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || !needed.test(stack)) {
                continue;
            }
            any = true;
            int max = stack.getType().getMaxDurability();
            int damage = stack.getItemMeta() instanceof Damageable meta ? meta.getDamage() : 0;
            if (max == 0 || max - damage > max * WORN) {
                return false;
            }
        }
        return any;
    }
}
