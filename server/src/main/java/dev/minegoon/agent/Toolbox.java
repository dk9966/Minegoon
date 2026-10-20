package dev.minegoon.agent;

import com.google.gson.JsonArray;
import dev.minegoon.MainThread.Cancelled;
import dev.minegoon.actions.ActionFailed;
import dev.minegoon.actions.Receipt;
import dev.minegoon.models.ModelClient.ToolCall;
import dev.minegoon.trips.Retreat;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The tools a model turn picks from, and how the picked one runs. An unknown
 * tool, a bad argument, or work that failed is a refused receipt rather than an
 * error, so the model can correct itself. Every receipt ends with the helper's
 * state, when there is one to tell.
 */
public final class Toolbox<C> {

    private final Map<String, Tool<C>> tools = new LinkedHashMap<>();
    private final Supplier<String> state;

    public Toolbox(List<Tool<C>> tools, Supplier<String> state) {
        tools.forEach(tool -> {
            if (this.tools.put(tool.name(), tool) != null) {
                throw new IllegalArgumentException("two tools are called " + tool.name());
            }
        });
        this.state = state;
    }

    public JsonArray specs() {
        return specs(tools.keySet());
    }

    /** Only these tools, in the toolbox's order. */
    public JsonArray specs(Collection<String> names) {
        JsonArray specs = new JsonArray();
        tools.values().stream().filter(tool -> names.contains(tool.name())).forEach(tool -> specs.add(tool.spec()));
        return specs;
    }

    public Receipt run(ToolCall call, C context) {
        Receipt receipt = attempt(call, context);
        String now = state.get();
        String observed = now.isEmpty() ? receipt.observed() : receipt.observed() + " Now " + now + ".";
        return new Receipt(call.name(), receipt.confirmed(), observed);
    }

    public String state() {
        return state.get();
    }

    private Receipt attempt(ToolCall call, C context) {
        Tool<C> tool = tools.get(call.name());
        if (tool == null) {
            return Receipt.refused(call.name(), "there is no such tool");
        }
        try {
            return tool.action().run(new Args(call.arguments()), context);
        } catch (Args.Refused bad) {
            return Receipt.refused(call.name(), bad.getMessage());
        } catch (ActionFailed | Retreat.Needed failure) {
            if (Thread.currentThread().isInterrupted()) {
                throw new Cancelled();
            }
            return Receipt.refused(call.name(), failure.getMessage());
        }
    }
}
