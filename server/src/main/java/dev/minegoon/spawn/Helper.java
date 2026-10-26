package dev.minegoon.spawn;

import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

/** The helper's presence in the world: spawning, its survival tick, dying, and speaking. */
public final class Helper implements Listener {

    private static final double RESET_CLEAR_RADIUS = 16;
    private static final FakePlayer.Controls HOLD_ON = new FakePlayer.Controls(0, 0, false, false, true);
    private static final FakePlayer.Controls TREAD_WATER = new FakePlayer.Controls(0, 0, true, false);

    private final Plugin plugin;
    private final String name;
    private final String worldName;
    private Player player;
    /** This tick's keys, or null when nothing drove. */
    private FakePlayer.Controls controls;
    private boolean overridden;
    private Consumer<Location> onDeath = location -> { };

    public Helper(Plugin plugin, String name, String worldName) {
        this.plugin = plugin;
        this.name = name;
        this.worldName = worldName;
    }

    public void spawn() {
        player = FakePlayer.spawn(name, spawnPoint());
        player.setSleepingIgnored(true);
        say("Hi, I'm here. Say !help.");
    }

    /** Practice runs only: back to spawn with an empty inventory, full health, and no loose items around. */
    public void reset() {
        Location spawn = spawnPoint();
        controls = null;
        spawn.getWorld().getNearbyEntitiesByType(Item.class, spawn, RESET_CLEAR_RADIUS).forEach(Item::remove);
        player.getInventory().clear();
        player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        player.setFoodLevel(20);
        player.setSaturation(5);
        player.setFireTicks(0);
        player.teleport(spawn);
    }

    /** Morning, clear sky, on this helper's world only. */
    public void morning() {
        World world = world();
        world.setTime(1000);
        world.setStorm(false);
        world.setThundering(false);
        world.setWeatherDuration(0);
        world.setClearWeatherDuration(20 * 60 * 60);
    }

    /** Removes hostile mobs in this world. Animals stay: once gone, they hardly ever come back. */
    public void clearMobs() {
        world().getEntities().stream().filter(Enemy.class::isInstance).forEach(Entity::remove);
    }

    public Location spawnPoint() {
        World world = world();
        return world.getHighestBlockAt(world.getSpawnLocation(), HeightMap.MOTION_BLOCKING_NO_LEAVES).getLocation()
            .add(0.5, 1, 0.5);
    }

    private World world() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            throw new IllegalStateException("World " + worldName + " is not loaded");
        }
        return world;
    }

    public void despawn() {
        if (online()) {
            FakePlayer.despawn(player);
        }
        player = null;
    }

    /**
     * Keys are held for one tick only, so a worker that stops driving leaves the
     * helper standing still. On a ladder or vine, standing still means sneaking,
     * or it would slide down while it mines or waits; under water it means
     * swimming up, or it would sink and drown. Driving with no keys lets go, the
     * way a climb down does.
     */
    public void tick() {
        if (online() && !player.isDead()) {
            FakePlayer.tick(player, controls != null ? controls : idle());
        }
        controls = null;
        overridden = false;
    }

    private FakePlayer.Controls idle() {
        if (FakePlayer.climbing(player)) {
            return HOLD_ON;
        }
        return player.isUnderWater() ? TREAD_WATER : FakePlayer.Controls.STILL;
    }

    /** Holds these keys for the next tick of movement. Main thread. */
    public void drive(FakePlayer.Controls controls) {
        if (!overridden) {
            this.controls = controls;
        }
    }

    /** Like {@link #drive}, but a reflex's keys win over a worker's for the tick. Main thread. */
    public void override(FakePlayer.Controls controls) {
        this.controls = controls;
        overridden = true;
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
        controls = null;
        onDeath.accept(event.getPlayer().getLocation());
        Bukkit.getScheduler().runTask(plugin, () -> player.spigot().respawn());
    }

    /** Back in its own world: the server would send it to the main one unless its bed is here. */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (is(event.getPlayer()) && !event.getRespawnLocation().getWorld().getName().equals(worldName)) {
            event.setRespawnLocation(spawnPoint());
        }
    }
}
