package dev.minegoon;

import org.bukkit.configuration.file.FileConfiguration;

/** The plugin's config.yml. The API key lives only on the server, never in git. */
public record Settings(String helperName, String apiKey, String parseModel, String unstuckModel,
                       String fallbackModel, int scanRadius, boolean lab) {

    public static Settings from(FileConfiguration config) {
        return new Settings(
            config.getString("helper.name", "Builder"),
            config.getString("openrouter.api-key", ""),
            config.getString("openrouter.parse-model"),
            config.getString("openrouter.unstuck-model"),
            config.getString("openrouter.fallback-model"),
            config.getInt("trips.scan-radius", 48),
            config.getBoolean("lab", false));
    }
}
