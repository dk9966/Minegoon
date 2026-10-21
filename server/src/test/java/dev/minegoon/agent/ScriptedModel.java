package dev.minegoon.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.minegoon.models.ModelClient;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Plays back tool calls in order, and remembers which model each turn went to and what it was told. */
final class ScriptedModel implements ModelClient {

    static final double COST = 0.001;

    private final Deque<ToolCall> script = new ArrayDeque<>();
    final List<String> models = new ArrayList<>();
    final List<String> briefings = new ArrayList<>();
    final List<JsonArray> offered = new ArrayList<>();

    ScriptedModel then(String tool, String arguments) {
        script.add(new ToolCall(tool, JsonParser.parseString(arguments).getAsJsonObject(),
            new Usage("scripted", 100, 10, COST)));
        return this;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Answer ask(String model, String system, String user, String schemaName, String schema) {
        throw new UnsupportedOperationException("the agent only picks tools");
    }

    @Override
    public ToolCall pick(String model, String system, String briefing, JsonArray tools) throws IOException {
        models.add(model);
        briefings.add(briefing);
        offered.add(tools);
        if (script.isEmpty()) {
            throw new IOException("the script ran out");
        }
        return script.poll();
    }
}
