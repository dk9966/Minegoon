package dev.minegoon.models;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * Asks a model on OpenRouter for a JSON answer that follows a schema, or for
 * one tool call. The fallback model is tried by OpenRouter itself when the
 * first one errors.
 */
public final class OpenRouter implements ModelClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Gson gson = new Gson();
    private final String apiKey;
    private final URI endpoint;
    private final String fallbackModel;

    public OpenRouter(String apiKey, URI endpoint, String fallbackModel) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.endpoint = endpoint;
        this.fallbackModel = fallbackModel;
    }

    @Override
    public boolean enabled() {
        return !apiKey.isEmpty();
    }

    @Override
    public JsonObject ask(String model, String system, String user, String schemaName, String schema)
            throws IOException {
        JsonObject format = new JsonObject();
        format.addProperty("name", schemaName);
        format.addProperty("strict", true);
        format.add("schema", JsonParser.parseString(schema));

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_schema");
        responseFormat.add("json_schema", format);

        JsonObject body = body(model, system, user);
        body.add("response_format", responseFormat);
        return send(body, answer -> JsonParser.parseString(reply(answer).get("content").getAsString())
            .getAsJsonObject());
    }

    @Override
    public ToolCall pick(String model, String system, String briefing, JsonArray tools) throws IOException {
        JsonObject body = body(model, system, briefing);
        body.add("tools", tools);
        body.addProperty("tool_choice", "required");
        body.addProperty("parallel_tool_calls", false);
        return send(body, answer -> {
            JsonObject function = reply(answer).getAsJsonArray("tool_calls").get(0).getAsJsonObject()
                .getAsJsonObject("function");
            return new ToolCall(function.get("name").getAsString(), arguments(function.get("arguments")),
                usage(answer));
        });
    }

    private JsonObject body(String model, String system, String user) {
        JsonObject provider = new JsonObject();
        provider.addProperty("require_parameters", true);

        JsonObject body = new JsonObject();
        body.add("models", gson.toJsonTree(List.of(model, fallbackModel)));
        body.add("messages", messages(system, user));
        body.add("provider", provider);
        body.addProperty("temperature", 0);
        return body;
    }

    private <T> T send(JsonObject body, Function<JsonObject, T> read) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
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
            return read.apply(JsonParser.parseString(response.body()).getAsJsonObject());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (RuntimeException e) {
            throw new IOException("unreadable answer from OpenRouter", e);
        }
    }

    private static JsonObject reply(JsonObject answer) {
        return answer.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
    }

    /** Arguments come as a JSON string, empty for a tool that takes none. */
    private static JsonObject arguments(JsonElement raw) {
        String text = raw == null || raw.isJsonNull() ? "" : raw.getAsString();
        return text.isBlank() ? new JsonObject() : JsonParser.parseString(text).getAsJsonObject();
    }

    private static Usage usage(JsonObject answer) {
        JsonObject usage = answer.has("usage") ? answer.getAsJsonObject("usage") : new JsonObject();
        return new Usage(answer.has("model") ? answer.get("model").getAsString() : "",
            number(usage, "prompt_tokens").intValue(), number(usage, "completion_tokens").intValue(),
            number(usage, "cost").doubleValue());
    }

    private static Number number(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? 0 : value.getAsNumber();
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
