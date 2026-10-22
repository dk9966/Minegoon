package dev.minegoon.spawn;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Keeps the helper's eyes off endermen's heads. An enderman turns on a player
 * who looks at its head from up to 64 blocks away with nothing in between
 * (https://minecraft.wiki/w/Enderman#Behavior). Every turn of the head comes
 * through here; when it would land near a calm enderman's head, the eyes tip
 * down or up instead. One that is already after the helper may be looked at:
 * the fight needs to aim.
 */
final class Gaze {

    private static final double RANGE = 64;
    /** Wider than the game's own cone, so a wobble of the head does not set one off. */
    private static final double MARGIN_DEGREES = 12;
    private static final float TIP_DEGREES = 30;

    private static int scannedTick = -1;
    private static List<Enderman> calm = List.of();

    private Gaze() {
    }

    /** The pitch to use instead of {@code pitch}, the same when no calm enderman's head is in that line. */
    static float pitch(Player player, float yaw, float pitch) {
        List<Enderman> near = calm(player);
        if (near.isEmpty()) {
            return pitch;
        }
        Location eye = player.getEyeLocation();
        Vector look = direction(yaw, pitch);
        for (Enderman enderman : near) {
            Vector to = enderman.getEyeLocation().toVector().subtract(eye.toVector());
            double distance = to.length();
            if (distance < 1e-3 || distance > RANGE) {
                continue;
            }
            double angle = Math.toDegrees(look.angle(to));
            if (angle > MARGIN_DEGREES + Math.toDegrees(Math.atan(1.0 / distance))
                || !player.hasLineOfSight(enderman)) {
                continue;
            }
            float toward = (float) -Math.toDegrees(Math.atan2(to.getY(), Math.hypot(to.getX(), to.getZ())));
            float down = toward + TIP_DEGREES;
            return down <= 90 ? down : toward - TIP_DEGREES;
        }
        return pitch;
    }

    private static List<Enderman> calm(Player player) {
        int tick = Bukkit.getCurrentTick();
        if (tick != scannedTick) {
            scannedTick = tick;
            calm = player.getNearbyEntities(RANGE, RANGE, RANGE).stream()
                .filter(entity -> entity instanceof Enderman)
                .map(Enderman.class::cast)
                .filter(enderman -> !enderman.isDead() && (enderman.getTarget() == null
                    || !enderman.getTarget().getUniqueId().equals(player.getUniqueId())))
                .toList();
        }
        return calm;
    }

    private static Vector direction(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new Vector(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }
}
