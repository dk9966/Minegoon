package dev.minegoon.chat;

import dev.minegoon.spawn.Helper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Opt-in spectator follow. A player says watch and sees through the helper's
 * eyes, including underground, or says chase and is carried along behind and
 * above it, looking at it. back puts them where they were, in the game mode
 * they had. Nothing pulls a player in on its own.
 */
public final class Watch implements Listener {

    /** How far behind and above the helper's eyes the chase camera sits. */
    private static final double BEHIND = 4.5;
    private static final double ABOVE = 1.5;
    /** Kept between the camera and a wall, so it never sits inside rock. */
    private static final double CLEARANCE = 0.4;
    /** Share of the way to where it should be that the camera moves each tick. Lower is smoother and laggier. */
    private static final double EASE = 0.35;
    private static final double TURN_EASE = 0.12;
    /** Further than this from where it should be, the camera jumps there, as after a respawn or a long fall. */
    private static final double SNAP = 24;

    private final Plugin plugin;
    private final Helper helper;
    private final Map<UUID, Seat> seats = new ConcurrentHashMap<>();

    private enum View { EYES, CHASE }

    private static final class Seat {
        final Location returnTo;
        final GameMode mode;
        View view;
        Location camera;
        float yaw;

        Seat(Location returnTo, GameMode mode, View view) {
            this.returnTo = returnTo;
            this.mode = mode;
            this.view = view;
        }
    }

    public Watch(Plugin plugin, Helper helper) {
        this.plugin = plugin;
        this.helper = helper;
    }

    public void start(UUID id) {
        sit(id, View.EYES, "You're watching through my eyes. Say chase to see me from behind, or back to return.");
    }

    public void chase(UUID id) {
        sit(id, View.CHASE, "You're following me from behind. Say watch to see through my eyes, or back to return.");
    }

    private void sit(UUID id, View view, String message) {
        onMain(() -> {
            Player player = player(id);
            if (player == null) {
                return;
            }
            if (!helper.online() || helper.player().isDead()) {
                tell(player, "I'm not standing in the world right now.");
                return;
            }
            Seat seat = seats.computeIfAbsent(player.getUniqueId(),
                key -> new Seat(player.getLocation().clone(), player.getGameMode(), view));
            seat.view = view;
            seat.camera = null;
            follow(player, seat);
            tell(player, message);
        });
    }

    public void stop(UUID id) {
        onMain(() -> {
            Player player = player(id);
            if (player == null) {
                return;
            }
            release(player, true);
        });
    }

    /** Moves every chase camera, and puts the eyes view back on the helper if a death or a scroll-away dropped it. */
    public void tick() {
        Player agent = helper.player();
        if (agent == null || agent.isDead()) {
            return;
        }
        for (Map.Entry<UUID, Seat> entry : List.copyOf(seats.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                follow(player, entry.getValue());
            }
        }
    }

    public void releaseAll() {
        for (UUID id : List.copyOf(seats.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                release(player, false);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        release(event.getPlayer(), false);
    }

    private void follow(Player player, Seat seat) {
        Player agent = helper.player();
        if (agent == null || agent.isDead()) {
            return;
        }
        if (player.getGameMode() != GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SPECTATOR);
        }
        if (seat.view == View.CHASE) {
            if (player.getSpectatorTarget() != null) {
                player.setSpectatorTarget(null);
            }
            behind(player, seat, agent);
            return;
        }
        if (!(player.getSpectatorTarget() instanceof Player target)
            || !target.getUniqueId().equals(agent.getUniqueId())) {
            player.setSpectatorTarget(agent);
        }
    }

    /**
     * Behind the way the helper faces, turning after it slowly so a glance
     * aside does not swing the camera. A wall between the helper and that
     * spot pulls the camera in, the way the game's own third-person view does.
     */
    private void behind(Player player, Seat seat, Player agent) {
        Location eye = agent.getEyeLocation();
        boolean fresh = seat.camera == null || !seat.camera.getWorld().equals(eye.getWorld())
            || seat.camera.distance(eye) > SNAP;
        seat.yaw = fresh ? eye.getYaw() : seat.yaw + (float) (TURN_EASE * wrap(eye.getYaw() - seat.yaw));

        double radians = Math.toRadians(seat.yaw);
        Vector back = new Vector(Math.sin(radians), 0, -Math.cos(radians)).multiply(BEHIND).setY(ABOVE);
        double reach = back.length();
        Vector way = back.clone().normalize();
        RayTraceResult wall = eye.getWorld().rayTraceBlocks(eye, way, reach, FluidCollisionMode.NEVER, true);
        double free = wall == null || wall.getHitPosition() == null
            ? reach
            : Math.max(0.5, wall.getHitPosition().distance(eye.toVector()) - CLEARANCE);
        Location wanted = eye.clone().add(way.multiply(free));

        Location camera = fresh ? wanted : seat.camera.clone().add(wanted.toVector().subtract(seat.camera.toVector())
            .multiply(EASE));
        if (wall != null) {
            camera = wanted;
        }
        Vector look = eye.toVector().subtract(camera.toVector());
        camera.setDirection(look.lengthSquared() < 1e-6 ? eye.getDirection() : look);
        seat.camera = camera;
        player.teleport(camera);
    }

    private static float wrap(float degrees) {
        float d = degrees % 360;
        if (d > 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }

    private void release(Player player, boolean tell) {
        Seat seat = seats.remove(player.getUniqueId());
        if (seat == null) {
            if (tell) {
                tell(player, "You're not watching. Say watch to see through my eyes, or chase to follow me.");
            }
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setSpectatorTarget(null);
        }
        player.teleport(seat.returnTo);
        player.setGameMode(seat.mode);
        if (tell) {
            tell(player, "Put you back.");
        }
    }

    private Player player(UUID id) {
        Player player = id == null ? null : Bukkit.getPlayer(id);
        if (player == null) {
            helper.say("Watching only works for a player in the world.");
            return null;
        }
        if (helper.is(player)) {
            return null;
        }
        return player;
    }

    private void tell(Player player, String text) {
        player.sendMessage(Component.text("<" + helper.name() + "> " + text));
    }

    private void onMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
