package dev.minegoon.spawn;

import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;

/** The helper's presence in the world: spawning, its survival tick, dying, and speaking. */
public final class Helper implements Listener {

    private static final double RESET_CLEAR_RADIUS = 16;

    private final Plugin plugin;
    private final String name;
    private Player player;
    private Consumer<Location> onDeath = location -> { };

    public Helper(Plugin plugin, String name) {
        this.plugin = plugin;
        this.name = name;
    }

    public void spawn() {
        player = FakePlayer.spawn(name, spawnPoint());
        player.setSleepingIgnored(true);
        say("Hi, I'm here. Try !get 5 diamond, or !help.");
    }

    /** Practice runs only: back to spawn with an empty inventory, full health, and no loose items around. */
    public void reset() {
        Location spawn = spawnPoint();
        spawn.getWorld().getNearbyEntitiesByType(Item.class, spawn, RESET_CLEAR_RADIUS).forEach(Item::remove);
        player.getInventory().clear();
        player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(5);
        player.setFireTicks(0);
        player.teleport(spawn);
    }

    public Location spawnPoint() {
        World world = Bukkit.getWorlds().getFirst();
        return world.getHighestBlockAt(world.getSpawnLocation()).getLocation().add(0.5, 1, 0.5);
    }

    public void despawn() {
        if (online()) {
            FakePlayer.despawn(player);
        }
        player = null;
    }

    public void tick() {
        if (online() && !player.isDead()) {
            FakePlayer.tick(player);
        }
    }

    public Player player() {
        return player;
    }

    public String name() {
        return name;
    }

    public boolean online() {
        return player != null && player.isOnline();
    }

    public boolean is(Entity entity) {
        return player != null && entity.getUniqueId().equals(player.getUniqueId());
    }

    public void setOnGround(boolean onGround) {
        FakePlayer.setOnGround(player, onGround);
    }

    public void say(String text) {
        Bukkit.broadcast(Component.text("<" + name + "> " + text));
    }

    /** Called the moment the helper dies, with where it died. It respawns on the next tick. */
    public void onDeath(Consumer<Location> handler) {
        onDeath = handler;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!is(event.getPlayer())) {
            return;
        }
        onDeath.accept(event.getPlayer().getLocation());
        Bukkit.getScheduler().runTask(plugin, () -> player.spigot().respawn());
    }
}
