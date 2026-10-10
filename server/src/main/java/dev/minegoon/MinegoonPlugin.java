package dev.minegoon;

import dev.minegoon.actions.Body;
import dev.minegoon.actions.Crafter;
import dev.minegoon.actions.Miner;
import dev.minegoon.actions.Mover;
import dev.minegoon.actions.Pickup;
import dev.minegoon.actions.Placer;
import dev.minegoon.chat.AdminCommand;
import dev.minegoon.chat.ChatListener;
import dev.minegoon.chat.Commands;
import dev.minegoon.jobs.Foreman;
import dev.minegoon.jobs.Inspector;
import dev.minegoon.jobs.JobBoard;
import dev.minegoon.jobs.Results;
import dev.minegoon.models.IntentParser;
import dev.minegoon.models.OpenRouter;
import dev.minegoon.models.Unstuck;
import dev.minegoon.planner.Cookbook;
import dev.minegoon.planner.Planner;
import dev.minegoon.safety.BreakGuard;
import dev.minegoon.safety.BreakPolicy;
import dev.minegoon.safety.Zones;
import dev.minegoon.spawn.Helper;
import dev.minegoon.trips.Gatherer;
import dev.minegoon.trips.Homecoming;
import dev.minegoon.trips.Reflexes;
import dev.minegoon.trips.Retreat;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class MinegoonPlugin extends JavaPlugin {

    private Database db;
    private Helper helper;
    private Foreman foreman;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Settings settings = Settings.from(getConfig());
        Path folder = getDataFolder().toPath();
        try {
            db = new Database(folder.resolve("minegoon.db"));
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Could not open the database", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        MainThread main = new MainThread(this);
        Zones zones = new Zones(db);
        BreakPolicy policy = new BreakPolicy(db, zones);
        helper = new Helper(this, settings.helperName());
        Body body = new Body(main, helper, policy);

        Miner miner = new Miner(body);
        Mover mover = new Mover(body, miner);
        Pickup pickup = new Pickup(body, mover);
        Retreat retreat = new Retreat(main, helper);
        Foreman.Crew crew = new Foreman.Crew(
            new Gatherer(body, mover, miner, pickup, retreat, settings.scanRadius()),
            new Crafter(body, miner, new Placer(body), pickup),
            new Homecoming(body, mover, pickup),
            retreat);

        Cookbook cookbook = Cookbook.load();
        getLogger().info("Cookbook knows a survival way to get " + cookbook.knownItems() + " items.");
        OpenRouter router = new OpenRouter(settings.apiKey(), settings.fallbackModel());
        Results results = new Results(folder.resolve("results.jsonl"));
        foreman = new Foreman(body, new JobBoard(db), new Inspector(main, helper), new Planner(cookbook), crew,
            new Unstuck(router, settings.unstuckModel(), getLogger()), zones, results, db, getLogger());
        helper.onDeath(foreman::onDeath);

        Commands commands = new Commands(helper, foreman, zones);
        IntentParser parser = new IntentParser(router, settings.parseModel(), settings.helperName());
        var plugins = getServer().getPluginManager();
        plugins.registerEvents(helper, this);
        plugins.registerEvents(new ChatListener(main, helper, commands, parser, getLogger()), this);
        plugins.registerEvents(new BreakGuard(helper, policy, results::guardCaught, getLogger()), this);
        AdminCommand admin = new AdminCommand(helper, commands, foreman, settings.lab());
        PluginCommand command = Objects.requireNonNull(getCommand("minegoon"));
        command.setExecutor(admin);
        command.setTabCompleter(admin);

        Reflexes reflexes = new Reflexes(helper);
        getServer().getScheduler().runTask(this, () -> {
            helper.spawn();
            foreman.resumeAfterRestart();
        });
        getServer().getScheduler().runTaskTimer(this, () -> {
            helper.tick();
            reflexes.tick();
        }, 1, 1);
        if (!router.enabled()) {
            getLogger().info("No OpenRouter key set: free-text chat is off, ! commands still work.");
        }
    }

    @Override
    public void onDisable() {
        if (foreman != null) {
            foreman.shutdown();
        }
        if (helper != null) {
            helper.despawn();
        }
        if (db != null) {
            try {
                db.close();
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "Could not close the database", e);
            }
        }
    }
}
