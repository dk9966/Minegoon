package dev.minegoon.agent;

import dev.minegoon.ItemSet;
import dev.minegoon.MainThread;
import dev.minegoon.jobs.Inspector;
import dev.minegoon.jobs.Mission;
import dev.minegoon.jobs.Mission.Check;
import dev.minegoon.spawn.Helper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Tells whether a plan's check holds in the game right now. A request from
 * the console has nobody to stand near, so near the asker means near where
 * the request was made.
 */
public final class DoneWhen {

    private final MainThread main;
    private final Helper helper;
    private final Inspector inspector;

    public DoneWhen(MainThread main, Helper helper, Inspector inspector) {
        this.main = main;
        this.helper = helper;
        this.inspector = inspector;
    }

    public boolean met(Check check, Mission mission) {
        return switch (check.kind()) {
            case HAVE -> {
                Material item = ItemSet.parse(check.item());
                yield item != null && inspector.have(ItemSet.of(item)) >= check.count();
            }
            case NEAR -> main.call(() -> {
                Player self = helper.player();
                Location target = where(check.player(), mission);
                return self != null && target != null && target.getWorld() != null
                    && target.getWorld().equals(self.getWorld())
                    && self.getLocation().distance(target) <= check.within();
            });
        };
    }

    private static Location where(String name, Mission mission) {
        Player player = Bukkit.getPlayerExact(name);
        if (player != null) {
            return player.getLocation();
        }
        return mission.owner() == null && name.equals(mission.ownerName()) ? mission.home() : null;
    }
}
