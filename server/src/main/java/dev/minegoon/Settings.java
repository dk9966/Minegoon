package dev.minegoon;

import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;

/** The plugin's config.yml. The API key lives only on the server, never in git. */
public record Settings(String helperName, String apiKey, Agent agent, String adviceModel, String fallbackModel,
                       boolean lab, List<String> lanes) {

    /**
     * The two models a spoken request uses, and how much one mission may use.
     * The planner writes plans and handles surprises; the desk model reads chat
     * and takes the mission turns that go to plan.
     */
    public record Agent(String plannerModel, String deskModel, int maxTurns, int maxStrongCalls, int maxMinutes) {
    }

    public static Settings from(FileConfiguration config) {
        return new Settings(
            config.getString("helper.name", "Builder"),
            config.getString("openrouter.api-key", ""),
            new Agent(
                config.getString("openrouter.planner-model"),
                config.getString("openrouter.desk-model"),
                config.getInt("agent.max-turns"),
                config.getInt("agent.max-strong-calls"),
                config.getInt("agent.max-minutes")),
            config.getString("openrouter.advice-model"),
            config.getString("openrouter.fallback-model"),
            config.getBoolean("lab", false),
            List.copyOf(config.getStringList("lanes")));
    }
}
