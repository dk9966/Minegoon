package dev.minegoon.actions;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Turning the helper's head. The server only accepts a player's rotation through a teleport. */
public final class Look {

    private Look() {
    }

    public static void at(Player player, Location target) {
        Location eye = player.getEyeLocation();
        Location turned = player.getLocation().setDirection(target.toVector().subtract(eye.toVector()));
        player.teleport(turned);
    }
}
