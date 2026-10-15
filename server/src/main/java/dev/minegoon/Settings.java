package dev.minegoon;

import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;

/** The plugin's config.yml. The API key lives only on the server, never in git. */
public record Settings(String helperName, String apiKey, String parseModel, String adviceModel,
                       String fallbackModel, boolean lab, List<String> lanes) {

    public static Settings from(FileConfiguration config) {
        return new Settings(
            config.getString("helper.name", "Builder"),
            config.getString("openrouter.api-key", ""),
            config.getString("openrouter.parse-model"),
            config.getString("openrouter.advice-model"),
            config.getString("openrouter.fallback-model"),
            config.getBoolean("lab", false),
            List.copyOf(config.getStringList("lanes")));
    }
}
