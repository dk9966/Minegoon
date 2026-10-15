package dev.minegoon.chat;

import dev.minegoon.spawn.Helper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Opt-in spectator follow. A player says watch and sees through the helper's
 * eyes, including underground. back puts them where they were, in the
 * game mode they had. Nothing pulls a player in on its own.
 */
public final class Watch implements Listener {

    private final Plugin plugin;
    private final Helper helper;
    private final Map<UUID, Seat> seats = new ConcurrentHashMap<>();

    private record Seat(Location returnTo, GameMode mode) {
    }

    public Watch(Plugin plugin, Helper helper) {
        this.plugin = plugin;
        this.helper = helper;
    }

    public void start(UUID id) {
        onMain(() -> {
            Player player = player(id);
            if (player == null) {
                return;
            }
            if (!helper.online() || helper.player().isDead()) {
                tell(player, "I'm not standing in the world right now.");
                return;
            }
            seats.putIfAbsent(player.getUniqueId(), new Seat(player.getLocation().clone(), player.getGameMode()));
            follow(player);
            tell(player, "You're watching through my eyes. Say back when you want to return.");
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

    /** Puts the camera back on the helper if a death or a scroll-away dropped it. */
    public void tick() {
        Player agent = helper.player();
        if (agent == null || agent.isDead()) {
            return;
        }
        for (UUID id : List.copyOf(seats.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                follow(player);
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

    private void follow(Player player) {
        Player agent = helper.player();
        if (agent == null || agent.isDead()) {
            return;
        }
        if (player.getGameMode() != GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SPECTATOR);
        }
        if (!(player.getSpectatorTarget() instanceof Player target)
            || !target.getUniqueId().equals(agent.getUniqueId())) {
            player.setSpectatorTarget(agent);
        }
    }

    private void release(Player player, boolean tell) {
        Seat seat = seats.remove(player.getUniqueId());
        if (seat == null) {
            if (tell) {
                tell(player, "You're not watching. Say watch to follow me.");
            }
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setSpectatorTarget(null);
        }
        player.teleport(seat.returnTo());
        player.setGameMode(seat.mode());
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
