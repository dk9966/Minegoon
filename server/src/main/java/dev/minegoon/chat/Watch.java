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
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Opt-in spectator follow. A player says watch and sees through the helper's
 * eyes, including underground, or says chase and is carried along behind and
 * above it, looking at it. free, or sneaking, lets go of the helper and leaves
 * them in spectator where the camera was. back puts them where they were before
 * any of it, in the game mode they had. Nothing pulls a player in on its own.
 *
 * <p>The chase view spectates an invisible armor stand that the server moves.
 * The client glides an entity between the places it is sent to, but jumps a
 * player that is teleported, so moving the player itself shakes the screen.
 */
public final class Watch implements Listener {

    /** How far behind and above the helper's eyes the chase camera sits. */
    private static final double BEHIND = 4.5;
    private static final double ABOVE = 1.5;
    /** Kept between the camera and a wall, so it never sits inside rock. */
    private static final double CLEARANCE = 0.4;
    /** Share of the way to where it should be that the camera moves each tick. Lower is gentler and laggier. */
    private static final double EASE = 0.12;
    private static final double TURN_EASE = 0.04;
    /** Slower than this, in blocks a tick, the helper is standing and the camera keeps its side. */
    private static final double WALKING = 0.08;
    /** Moves smaller than these are not sent, so a still helper means a still screen. */
    private static final double NUDGE = 0.03;
    private static final float NUDGE_DEGREES = 0.4f;
    /** Further than this from where it should be, the camera jumps there, as after a respawn or a long fall. */
    private static final double SNAP = 24;

    private final Plugin plugin;
    private final Helper helper;
    private final Map<UUID, Seat> seats = new ConcurrentHashMap<>();

    private enum View { EYES, CHASE, FREE }

    private static final class Seat {
        final Location returnTo;
        final GameMode mode;
        View view;
        ArmorStand rig;
        Location camera;
        Location helperWas;
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
        sit(id, View.EYES, "You're watching through my eyes. Say chase to see me from behind, sneak or say free to "
            + "stop and look around there, or say back to return.");
    }

    public void chase(UUID id) {
        sit(id, View.CHASE, "You're following me from behind. Sneak or say free to stop and look around there, "
            + "or say back to return.");
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
            if (view == View.EYES) {
                unrig(seat);
            }
            follow(player, seat);
            tell(player, message);
        });
    }

    /** Lets go of the helper, staying in spectator where the camera is now. */
    public void free(UUID id) {
        onMain(() -> {
            Player player = player(id);
            if (player == null) {
                return;
            }
            Seat seat = seats.get(player.getUniqueId());
            if (seat == null || seat.view == View.FREE) {
                tell(player, "You're not following me. Say chase or watch to follow me.");
                return;
            }
            letGo(player, seat);
        });
    }

    /** Sneaking is how the game stops spectating, so it lets go here too instead of being undone next tick. */
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        Seat seat = seats.get(event.getPlayer().getUniqueId());
        if (event.isSneaking() && seat != null && seat.view != View.FREE) {
            letGo(event.getPlayer(), seat);
        }
    }

    private void letGo(Player player, Seat seat) {
        Location here = seat.view == View.CHASE && seat.camera != null
            ? seat.camera.clone()
            : helper.player() != null ? helper.player().getEyeLocation() : player.getLocation();
        player.setSpectatorTarget(null);
        unrig(seat);
        seat.view = View.FREE;
        player.teleport(here);
        tell(player, "You're free to fly around here. Say chase or watch to follow me again, "
            + "or back to go back to where you started.");
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

    /** Moves every chase camera, and puts each view back on the helper if a death or a scroll-away dropped it. */
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
        if (seat.view == View.FREE || agent == null || agent.isDead()) {
            return;
        }
        if (player.getGameMode() != GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SPECTATOR);
        }
        if (seat.view == View.CHASE) {
            ArmorStand rig = behind(seat, agent);
            if (player.getSpectatorTarget() == null || !player.getSpectatorTarget().equals(rig)) {
                player.setSpectatorTarget(rig);
            }
            return;
        }
        if (!(player.getSpectatorTarget() instanceof Player target)
            || !target.getUniqueId().equals(agent.getUniqueId())) {
            player.setSpectatorTarget(agent);
        }
    }

    /**
     * Behind the way the helper walks, turning after it slowly, so the many
     * glances a search makes do not swing the camera. A wall between the
     * helper and that spot pulls the camera in, the way the game's own
     * third-person view does. Returns the stand the player watches from.
     */
    private ArmorStand behind(Seat seat, Player agent) {
        Location eye = agent.getEyeLocation();
        boolean fresh = seat.rig == null || !seat.rig.isValid() || seat.camera == null
            || !seat.camera.getWorld().equals(eye.getWorld()) || seat.camera.distance(eye) > SNAP;
        if (fresh) {
            unrig(seat);
            seat.yaw = eye.getYaw();
        } else {
            Vector walked = agent.getLocation().toVector().subtract(seat.helperWas.toVector()).setY(0);
            if (walked.length() > WALKING) {
                float heading = (float) Math.toDegrees(Math.atan2(-walked.getX(), walked.getZ()));
                seat.yaw += (float) (TURN_EASE * wrap(heading - seat.yaw));
            }
        }
        seat.helperWas = agent.getLocation();

        double radians = Math.toRadians(seat.yaw);
        Vector back = new Vector(Math.sin(radians), 0, -Math.cos(radians)).multiply(BEHIND).setY(ABOVE);
        double reach = back.length();
        Vector way = back.clone().normalize();
        RayTraceResult wall = eye.getWorld().rayTraceBlocks(eye, way, reach, FluidCollisionMode.NEVER, true);
        double free = wall == null || wall.getHitPosition() == null
            ? reach
            : Math.max(0.5, wall.getHitPosition().distance(eye.toVector()) - CLEARANCE);
        Location wanted = eye.clone().add(way.multiply(free));

        Location camera;
        if (fresh) {
            camera = wanted;
        } else {
            Vector toward = wanted.toVector().subtract(seat.camera.toVector());
            camera = seat.camera.clone().add(toward.multiply(wall != null ? Math.max(EASE, 0.5) : EASE));
        }
        Vector look = eye.toVector().subtract(camera.toVector());
        camera.setDirection(look.lengthSquared() < 1e-6 ? eye.getDirection() : look);

        if (fresh) {
            seat.rig = rig(camera);
        } else if (camera.distance(seat.camera) > NUDGE
            || Math.abs(wrap(camera.getYaw() - seat.camera.getYaw())) > NUDGE_DEGREES
            || Math.abs(camera.getPitch() - seat.camera.getPitch()) > NUDGE_DEGREES) {
            seat.rig.teleport(standAt(camera, seat.rig));
        } else {
            return seat.rig;
        }
        seat.camera = camera;
        return seat.rig;
    }

    private static ArmorStand rig(Location camera) {
        return camera.getWorld().spawn(camera, ArmorStand.class, stand -> {
            stand.setMarker(true);
            stand.setInvisible(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setPersistent(false);
            stand.setCollidable(false);
            stand.teleport(standAt(camera, stand));
        });
    }

    /** Where the stand goes so that its eyes, which the player sees from, are at the camera. */
    private static Location standAt(Location camera, ArmorStand stand) {
        return camera.clone().subtract(0, stand.getEyeHeight(), 0);
    }

    private static void unrig(Seat seat) {
        if (seat.rig != null) {
            seat.rig.remove();
        }
        seat.rig = null;
        seat.camera = null;
        seat.helperWas = null;
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
        unrig(seat);
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
