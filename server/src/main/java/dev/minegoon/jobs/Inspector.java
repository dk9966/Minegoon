package dev.minegoon.jobs;

import dev.minegoon.ItemSet;
import dev.minegoon.MainThread;
import dev.minegoon.actions.Hands;
import dev.minegoon.spawn.Helper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

/**
 * Checks each job against the game itself, never against what the helper
 * believes it did. A job is done only when the inventory shows it.
 */
public final class Inspector {

    private static final double RECOVER_RADIUS = 8;

    private final MainThread main;
    private final Helper helper;

    public Inspector(MainThread main, Helper helper) {
        this.main = main;
        this.helper = helper;
    }

    public int baseline(Step step) {
        return step.counted() == null ? 0 : have(step.counted());
    }

    public boolean done(Job job) {
        int start = job.baseline();
        return switch (job.step()) {
            case Step.Gather gather -> have(gather.item()) >= start + gather.count();
            case Step.Hunt hunt -> have(hunt.item()) >= start + hunt.count();
            case Step.Craft craft -> have(craft.output()) >= start + craft.times() * craft.yield();
            case Step.Smelt smelt -> have(smelt.output()) >= start + smelt.count();
            case Step.Deliver deliver -> have(deliver.item()) <= start - deliver.count();
            case Step.Recover recover -> System.currentTimeMillis() > recover.until() || nothingLeft(recover);
        };
    }

    /** How many of the item the helper holds right now. */
    public int have(ItemSet items) {
        return main.call(() -> {
            Player player = helper.player();
            return player == null ? 0 : Hands.count(player, items);
        });
    }

    private boolean nothingLeft(Step.Recover spot) {
        return main.call(() -> {
            World world = Bukkit.getWorld(spot.world());
            return world == null || world.getNearbyEntitiesByType(Item.class,
                new Location(world, spot.x() + 0.5, spot.y(), spot.z() + 0.5), RECOVER_RADIUS).isEmpty();
        });
    }
}
