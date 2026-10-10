package dev.minegoon.models;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Asks a model on OpenRouter for a JSON answer that follows a schema. The
 * fallback model is tried by OpenRouter itself when the first one errors.
 */
public final class OpenRouter {

    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/chat/completions");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Gson gson = new Gson();
    private final String apiKey;
    private final String fallbackModel;

    public OpenRouter(String apiKey, String fallbackModel) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.fallbackModel = fallbackModel;
    }

    public boolean enabled() {
        return !apiKey.isEmpty();
    }

    public JsonObject ask(String model, String system, String user, String schemaName, String schema)
            throws IOException {
        JsonObject format = new JsonObject();
        format.addProperty("name", schemaName);
        format.addProperty("strict", true);
        format.add("schema", JsonParser.parseString(schema));

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_schema");
        responseFormat.add("json_schema", format);

        JsonObject provider = new JsonObject();
        provider.addProperty("require_parameters", true);

        JsonObject body = new JsonObject();
        body.add("models", gson.toJsonTree(List.of(model, fallbackModel)));
        body.add("messages", messages(system, user));
        body.add("response_format", responseFormat);
        body.add("provider", provider);
        body.addProperty("temperature", 0);

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .header("X-Title", "Minegoon")
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
            .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("OpenRouter answered " + response.statusCode() + ": " + response.body());
            }
            String content = JsonParser.parseString(response.body()).getAsJsonObject()
                .getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
            return JsonParser.parseString(content).getAsJsonObject();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (RuntimeException e) {
            throw new IOException("unreadable answer from OpenRouter", e);
        }
    }

    private static JsonArray messages(String system, String user) {
        JsonArray messages = new JsonArray();
        messages.add(message("system", system));
        messages.add(message("user", user));
        return messages;
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }
}
