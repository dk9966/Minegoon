package dev.minegoon.models;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;

/** A chat model that answers in a schema or picks one tool. OpenRouter in the game, a script in tests. */
public interface ModelClient {

    /** One tool the model picked, with its arguments as parsed JSON, and what the call cost. */
    record ToolCall(String name, JsonObject arguments, Usage usage) {
    }

    /** The model that answered, the tokens it read and wrote, and the cost in credits. */
    record Usage(String model, int promptTokens, int completionTokens, double cost) {
    }

    boolean enabled();

    JsonObject ask(String model, String system, String user, String schemaName, String schema) throws IOException;

    /** Exactly one call to one of {@code tools}, given as OpenRouter function definitions. */
    ToolCall pick(String model, String system, String briefing, JsonArray tools) throws IOException;
}
