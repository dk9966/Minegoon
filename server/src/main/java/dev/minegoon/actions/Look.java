package dev.minegoon.actions;

import dev.minegoon.spawn.FakePlayer;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Turning the helper's head, without a teleport, so movement in progress keeps its speed. */
public final class Look {

    private Look() {
    }

    public static void at(Player player, Location target) {
        Location eye = player.getEyeLocation();
        Location turned = eye.clone().setDirection(target.toVector().subtract(eye.toVector()));
        FakePlayer.face(player, turned.getYaw(), turned.getPitch());
    }

    /** Faces a compass yaw, eyes level. */
    public static void toward(Player player, double x, double z) {
        Location at = player.getLocation();
        float yaw = (float) Math.toDegrees(Math.atan2(-(x - at.getX()), z - at.getZ()));
        FakePlayer.face(player, yaw, 0);
    }
}
