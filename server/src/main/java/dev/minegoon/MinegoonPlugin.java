package dev.minegoon;

import dev.minegoon.chat.ChatListener;
import dev.minegoon.chat.LaneCommand;
import dev.minegoon.knowledge.Knowledge;
import dev.minegoon.knowledge.Lessons;
import dev.minegoon.models.OpenRouter;
import dev.minegoon.planner.Cookbook;
import dev.minegoon.trips.Prospector;
import dev.minegoon.world.Names;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.WorldCreator;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

public final class MinegoonPlugin extends JavaPlugin {

    private final List<Session> sessions = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Settings settings = Settings.from(getConfig());
        MainThread main = new MainThread(this);
        Cookbook cookbook = Cookbook.load();
        getLogger().info("Cookbook knows a survival way to get " + cookbook.knownItems() + " items.");
        OpenRouter router = new OpenRouter(settings.apiKey(), settings.apiUrl(), settings.fallbackModel());
        Knowledge knowledge = readKnowledge();
        Lessons lessons = new Lessons(getDataFolder().toPath().resolve("lessons.json"));
        getLogger().info("Knowledge loaded; " + lessons.size() + " lessons learned so far.");
        Session.Shared shared = new Session.Shared(settings, main, router, cookbook, knowledge, lessons);

        try {
            if (settings.lanes().isEmpty()) {
                String world = getServer().getWorlds().getFirst().getName();
                sessions.add(Session.open(this, shared, world, settings.helperName(), false));
            } else {
                for (String lane : settings.lanes()) {
                    if (!loadLane(lane)) {
                        getServer().getPluginManager().disablePlugin(this);
                        return;
                    }
                    String helper = settings.helperName() + "-" + lane.substring(lane.lastIndexOf('_') + 1);
                    sessions.add(Session.open(this, shared, lane, helper, true));
                }
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Could not open the database", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(new ChatListener(main, sessions), this);
        PluginCommand command = Objects.requireNonNull(getCommand("minegoon"));
        command.setExecutor(executor(settings));
        command.setTabCompleter(executor(settings));

        getServer().getScheduler().runTaskTimer(this, () -> sessions.forEach(Session::tick), 1, 1);
        if (!router.enabled()) {
            getLogger().info("No OpenRouter key set: only direct commands are read, and the helper decides without a model.");
        }
    }

    @Override
    public void onDisable() {
        sessions.forEach(Session::shutdown);
        sessions.clear();
    }

    private Knowledge readKnowledge() {
        try (Reader yaml = new InputStreamReader(Objects.requireNonNull(getResource("knowledge.yml")),
                StandardCharsets.UTF_8)) {
            Knowledge knowledge = Knowledge.read(yaml);
            knowledge.problems(Names::biome, name -> Names.thing(name) || name.equals(Prospector.SOIL))
                .forEach(problem -> getLogger().warning("knowledge.yml: " + problem));
            return knowledge;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read knowledge.yml", e);
        }
    }

    private boolean loadLane(String lane) {
        if (!Session.saved(lane)) {
            getLogger().severe("Lane world is missing: " + lane);
            return false;
        }
        if (Bukkit.getWorld(lane) == null && Bukkit.createWorld(WorldCreator.name(lane)) == null) {
            getLogger().severe("Could not load lane world " + lane);
            return false;
        }
        return true;
    }

    private TabExecutor executor(Settings settings) {
        if (settings.lanes().isEmpty()) {
            return sessions.getFirst().admin();
        }
        Map<String, Session> lanes = new LinkedHashMap<>();
        for (Session session : sessions) {
            lanes.put(session.worldName(), session);
        }
        return new LaneCommand(lanes);
    }
}
