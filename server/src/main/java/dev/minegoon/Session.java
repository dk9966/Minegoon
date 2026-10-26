package dev.minegoon;

import dev.minegoon.actions.Body;
import dev.minegoon.agent.DeskTools;
import dev.minegoon.agent.DoneWhen;
import dev.minegoon.agent.FrontDesk;
import dev.minegoon.agent.MissionRunner;
import dev.minegoon.agent.MissionTools;
import dev.minegoon.agent.Senses;
import dev.minegoon.actions.Crafter;
import dev.minegoon.actions.Miner;
import dev.minegoon.actions.Mover;
import dev.minegoon.actions.Pickup;
import dev.minegoon.actions.Placer;
import dev.minegoon.chat.AdminCommand;
import dev.minegoon.chat.Commands;
import dev.minegoon.chat.Effects;
import dev.minegoon.chat.Peek;
import dev.minegoon.chat.Watch;
import dev.minegoon.jobs.Foreman;
import dev.minegoon.jobs.Inspector;
import dev.minegoon.jobs.JobBoard;
import dev.minegoon.jobs.MissionBoard;
import dev.minegoon.jobs.Results;
import dev.minegoon.jobs.Tutor;
import dev.minegoon.knowledge.Knowledge;
import dev.minegoon.knowledge.Lessons;
import dev.minegoon.models.Advisor;
import dev.minegoon.models.ModelClient;
import dev.minegoon.models.ModelFires;
import dev.minegoon.planner.Cookbook;
import dev.minegoon.planner.Planner;
import dev.minegoon.safety.BreakGuard;
import dev.minegoon.safety.BreakPolicy;
import dev.minegoon.safety.Zones;
import dev.minegoon.spawn.Helper;
import dev.minegoon.trips.Bestiary;
import dev.minegoon.trips.Farmer;
import dev.minegoon.trips.Fighter;
import dev.minegoon.trips.Filler;
import dev.minegoon.trips.Gatherer;
import dev.minegoon.trips.Homecoming;
import dev.minegoon.trips.Prospector;
import dev.minegoon.trips.Reflexes;
import dev.minegoon.trips.Retreat;
import dev.minegoon.trips.Sleeper;
import dev.minegoon.trips.Tunneler;
import dev.minegoon.world.Goal;
import dev.minegoon.world.Names;
import dev.minegoon.world.Pos;
import dev.minegoon.world.Structures;
import dev.minegoon.world.Vision;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * One helper and the objects that exist only for it. A normal server has one.
 * A lab server has one per world, so several trips share the Java process and
 * still dig separate ground.
 */
public final class Session {

    private static final double SPAR_DISTANCE = 8;
    /** How often a running task is checked for progress. */
    private static final int WATCH_TICKS = 20 * 20;

    /** What every helper on the server uses alike. Lessons are shared, so one helper's lesson helps all. */
    public record Shared(Settings settings, MainThread main, ModelClient router, Cookbook cookbook,
                         Knowledge knowledge, Lessons lessons) {
    }

    private final Plugin plugin;
    private final Shared shared;
    private final Settings settings;
    private final MainThread main;
    private final String worldName;
    private final String helperName;
    private final Path folder;
    private final boolean lane;
    private final List<Listener> listeners = new ArrayList<>();

    private Database db;
    private Helper helper;
    /** The server tick of the helper's last death. */
    private volatile int diedAt = Integer.MIN_VALUE;
    private Prospector prospector;
    private Mover mover;
    private Retreat retreat;
    private Fighter fighter;
    private Results results;
    private Foreman foreman;
    private Watch watch;
    private Peek peek;
    private Reflexes reflexes;
    private Commands commands;
    private FrontDesk desk;
    private AdminCommand admin;

    private Session(Plugin plugin, Shared shared, String worldName, String helperName, Path folder, boolean lane) {
        this.plugin = plugin;
        this.shared = shared;
        this.settings = shared.settings();
        this.main = shared.main();
        this.worldName = worldName;
        this.helperName = helperName;
        this.folder = folder;
        this.lane = lane;
    }

    public static Session open(Plugin plugin, Shared shared, String worldName, String helperName, boolean lane)
        throws SQLException {
        Path folder = lane
            ? plugin.getDataFolder().toPath().resolve(worldName)
            : plugin.getDataFolder().toPath();
        Session session = new Session(plugin, shared, worldName, helperName, folder, lane);
        session.start();
        return session;
    }

    public String worldName() {
        return worldName;
    }

    public String helperName() {
        return helperName;
    }

    public Helper helper() {
        return helper;
    }

    public Commands commands() {
        return commands;
    }

    public FrontDesk desk() {
        return desk;
    }

    public AdminCommand admin() {
        return admin;
    }

    public boolean online() {
        return helper != null && helper.online();
    }

    public boolean is(Player player) {
        return helper != null && helper.is(player);
    }

    public void tick() {
        if (!online()) {
            return;
        }
        helper.tick();
        reflexes.tick();
        if (Bukkit.getCurrentTick() % WATCH_TICKS == 0) {
            Thread.ofVirtual().name("minegoon-watch").start(foreman::watch);
        }
        watch.tick();
        peek.tick();
    }

    /** Stops the helper and closes its files. The world stays loaded. */
    public void shutdown() {
        if (foreman != null) {
            foreman.shutdown();
        }
        if (peek != null) {
            peek.closeAll();
        }
        if (watch != null) {
            watch.releaseAll();
        }
        if (helper != null) {
            helper.despawn();
        }
        listeners.forEach(HandlerList::unregisterAll);
        listeners.clear();
        closeDb();
        foreman = null;
        fighter = null;
        prospector = null;
        results = null;
        helper = null;
        watch = null;
        peek = null;
        reflexes = null;
        commands = null;
        desk = null;
        admin = null;
    }

    /**
     * Practice reset for one lane: empty hands, spawn, morning, and optionally
     * no mobs already standing around. The world itself is replaced separately.
     */
    public void prepare(CommandSender sender, boolean clearMobs) {
        if (!settings.lab()) {
            sender.sendMessage("Reset only works on the practice server (lab: true in config.yml).");
            return;
        }
        foreman.stop();
        helper.reset();
        helper.morning();
        prospector.forget();
        if (clearMobs) {
            helper.clearMobs();
        }
        sender.sendMessage("Helper reset to spawn.");
    }

    /**
     * Practice fight: puts {@code count} of a monster a short walk away, angry
     * at the helper and safe from daylight, and fights them one by one. Each fight
     * goes to results.jsonl.
     */
    public void spar(CommandSender sender, String mob, int count) {
        if (!settings.lab()) {
            sender.sendMessage("Spar only works on the practice server (lab: true in config.yml).");
            return;
        }
        if (!online()) {
            sender.sendMessage(helperName + " is not in the world.");
            return;
        }
        NamespacedKey key = NamespacedKey.fromString(mob);
        EntityType type = key == null ? null
            : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENTITY_TYPE).get(key);
        if (type == null || type.getEntityClass() == null || !Monster.class.isAssignableFrom(type.getEntityClass())) {
            sender.sendMessage("Not a monster: " + mob);
            return;
        }
        Player player = helper.player();
        List<UUID> opponents = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * i / count;
            Location at = player.getLocation().add(SPAR_DISTANCE * Math.cos(angle), 0, SPAR_DISTANCE * Math.sin(angle));
            at.setY(at.getWorld().getHighestBlockYAt(at) + 1);
            Monster monster = (Monster) at.getWorld().spawnEntity(at, type);
            switch (monster) {
                case Zombie zombie -> zombie.setShouldBurnInDay(false);
                case AbstractSkeleton skeleton -> skeleton.setShouldBurnInDay(false);
                default -> {
                }
            }
            monster.setTarget(player);
            opponents.add(monster.getUniqueId());
        }
        foreman.spar(opponents, results::spar);
        sender.sendMessage("Sparring " + count + " " + type.getKey().getKey() + ".");
    }

    /** Practice walk: goes to a block, or the surface, by its own route. How it went goes to results.jsonl. */
    public void go(CommandSender sender, int x, Integer y, int z) {
        if (!settings.lab() || !online()) {
            sender.sendMessage("Goto only works on the practice server, with the helper in the world.");
            return;
        }
        World world = helper.player().getWorld();
        Pos target = new Pos(x, y != null ? y : world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, z);
        Goal goal = y != null ? Goal.near(target, 1.5) : Goal.column(target, 1.5);
        foreman.drill("goto", () -> {
            Pos from = mover.feet();
            int began = Bukkit.getCurrentTick();
            double blocks = Math.hypot(target.x() - from.x(), target.z() - from.z());
            boolean reached = false;
            try {
                reached = mover.walk(goal, retreat);
            } finally {
                // A stopped walk leaves the thread interrupted, which would also stop the write.
                Thread.interrupted();
                boolean died = diedAt >= began;
                String end = died ? null : mover.feet().toString();
                int ended = died ? diedAt : Bukkit.getCurrentTick();
                results.trek(new Results.Trek(reached, died, from.toString(), target.toString(), end, blocks,
                    ended - began));
            }
        });
        sender.sendMessage("Going to " + target + ".");
    }

    /** Drops this lane's world so its folder can be replaced. Not used for the main world. */
    public void unload(CommandSender sender) {
        if (!lane) {
            sender.sendMessage("Could not unload " + worldName + ".");
            return;
        }
        shutdown();
        World world = Bukkit.getWorld(worldName);
        if (world != null && !Bukkit.unloadWorld(world, false)) {
            sender.sendMessage("Could not unload " + worldName + ".");
            return;
        }
        deleteState();
        sender.sendMessage("Unloaded " + worldName + ".");
    }

    /** Loads this lane's world folder and puts the helper back at spawn. */
    public void load(CommandSender sender) {
        if (!lane) {
            sender.sendMessage("Could not load " + worldName + ".");
            return;
        }
        if (Bukkit.getWorld(worldName) == null) {
            if (!saved(worldName)) {
                sender.sendMessage("World folder " + worldName + " is missing.");
                return;
            }
            World world = Bukkit.createWorld(WorldCreator.name(worldName));
            if (world == null) {
                sender.sendMessage("Could not load " + worldName + ".");
                return;
            }
        }
        if (online()) {
            sender.sendMessage("Loaded " + worldName + ".");
            return;
        }
        try {
            start();
        } catch (SQLException e) {
            sender.sendMessage("Could not load " + worldName + ".");
            plugin.getLogger().warning("Could not reopen " + worldName + ": " + e.getMessage());
            return;
        }
        helper.morning();
        sender.sendMessage("Loaded " + worldName + ".");
    }

    private void start() throws SQLException {
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new SQLException("Could not create " + folder, e);
        }
        db = new Database(folder.resolve("minegoon.db"));
        Zones zones = new Zones(db);
        BreakPolicy policy = new BreakPolicy(db, zones);
        helper = new Helper(plugin, helperName, worldName);
        Body body = new Body(main, helper, policy);

        Logger log = plugin.getLogger();
        Knowledge knowledge = shared.knowledge();
        Miner miner = new Miner(body);
        Placer placer = new Placer(body);
        mover = new Mover(body, miner, placer);
        Pickup pickup = new Pickup(body, mover);
        Vision vision = new Vision(main, helper::player, Bukkit.getViewDistance() * 16);
        Structures structures = new Structures(vision, knowledge.structures(), folder.resolve("structures.json"), log);
        Tunneler tunneler = new Tunneler(body, mover, vision);
        prospector = new Prospector(knowledge, shared.lessons(), mover, vision, structures, tunneler, helperName, log);
        fighter = new Fighter(body, mover);
        retreat = new Retreat(main, helper, fighter);
        Foreman.Crew crew = new Foreman.Crew(
            new Gatherer(body, mover, miner, pickup, retreat, vision, prospector),
            new Crafter(body, miner, placer, pickup),
            new Homecoming(body, mover, tunneler, pickup, fighter, retreat.homeward()),
            retreat,
            new Farmer(body, mover, miner, pickup, retreat, vision, prospector),
            new Filler(body, mover, retreat, vision, prospector),
            new Sleeper(body, miner, mover, placer, pickup),
            fighter,
            prospector);
        results = new Results(folder.resolve("results.jsonl"));
        ModelFires fires = new ModelFires(results::modelFire, log, helper::say);
        Tutor tutor = new Tutor(knowledge, shared.lessons(),
            new Advisor(shared.router(), settings.adviceModel(), log, fires), vision, structures, main,
            helper::player, Names::biome, log);

        MissionBoard missions = new MissionBoard(db, results::record);
        Inspector inspector = new Inspector(main, helper);
        foreman = new Foreman(body, new JobBoard(db), missions, inspector, new Planner(shared.cookbook()),
            crew, tutor, zones, results, db, log);
        Senses senses = new Senses(main, helper, vision, structures, crew.sleeper());
        MissionRunner runner = new MissionRunner(shared.router(), settings.agent(), missions,
            new MissionTools(foreman, crew, missions, inspector, vision, senses, helper, Names::biome).all(),
            senses::state, new DoneWhen(main, helper, inspector)::met, helper::say, System::currentTimeMillis,
            helperName, fires);
        foreman.runMissionsWith(runner::run);
        helper.onDeath(spot -> {
            diedAt = Bukkit.getCurrentTick();
            org.bukkit.entity.Player dead = helper.player();
            if (dead != null) {
                var cause = dead.getLastDamageCause();
                log.info(helperName + " died at " + Pos.of(spot) + " from "
                    + (cause == null ? "unknown" : cause.getCause().name().toLowerCase()) + ", fighting "
                    + fighter.engaged() + ", around: " + Bestiary.nearby(dead, 16));
            }
            foreman.onDeath(spot);
        });

        watch = new Watch(plugin, helper);
        peek = new Peek(plugin, helper);
        Effects effects = new Effects(main, helper);
        commands = new Commands(helper, foreman, zones, watch, peek, effects);
        desk = new FrontDesk(shared.router(), settings.agent().deskModel(),
            new DeskTools(commands, foreman, missions, senses, effects).all(), missions, foreman, results, fires,
            helper::say,
            log, helperName);
        listen(helper);
        listen(watch);
        listen(peek);
        listen(new BreakGuard(helper, policy, results::guardCaught, plugin.getLogger()));
        admin = new AdminCommand(helper, commands, desk, foreman, missions, peek, settings.lab());

        reflexes = new Reflexes(helper, fighter::engaged, fighter::running);
        helper.spawn();
        foreman.resumeAfterRestart();
        plugin.getLogger().info(helperName + " is in " + worldName + ".");
    }

    /**
     * Whether a world folder exists, either where it was copied in or where
     * Paper moves it on first load: the main world's dimensions folder.
     */
    public static boolean saved(String name) {
        Path container = Bukkit.getWorldContainer().toPath();
        String main = Bukkit.getWorlds().getFirst().getName();
        return Files.isDirectory(container.resolve(name))
            || Files.isDirectory(container.resolve(main).resolve("dimensions").resolve("minecraft").resolve(name));
    }

    private void listen(Listener listener) {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }

    private void closeDb() {
        if (db == null) {
            return;
        }
        try {
            db.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not close the database for " + worldName + ": " + e.getMessage());
        }
        db = null;
    }

    private void deleteState() {
        for (String name : List.of("minegoon.db", "minegoon.db-wal", "minegoon.db-shm", "results.jsonl")) {
            try {
                Files.deleteIfExists(folder.resolve(name));
            } catch (IOException e) {
                plugin.getLogger().warning("Could not delete " + name + " for " + worldName + ": " + e.getMessage());
            }
        }
    }
}
