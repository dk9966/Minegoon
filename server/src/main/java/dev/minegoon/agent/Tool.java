package dev.minegoon.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.minegoon.actions.Receipt;

/**
 * One ability the model may call: its name, what it is for and when not to use
 * it, its arguments as JSON schema, and what it does. {@code C} is what the
 * tool works for: a mission, or a player at the front desk.
 */
public record Tool<C>(String name, String description, JsonObject parameters, Action<C> action) {

    private static final String NO_ARGUMENTS = """
        {"type": "object", "properties": {}}""";

    @FunctionalInterface
    public interface Action<C> {
        Receipt run(Args args, C context);
    }

    public static <C> Tool<C> of(String name, String description, String parameters, Action<C> action) {
        return new Tool<>(name, description, JsonParser.parseString(parameters).getAsJsonObject(), action);
    }

    public static <C> Tool<C> of(String name, String description, Action<C> action) {
        return of(name, description, NO_ARGUMENTS, action);
    }

    /** The function definition OpenRouter takes. */
    JsonObject spec() {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", parameters);
        JsonObject spec = new JsonObject();
        spec.addProperty("type", "function");
        spec.add("function", function);
        return spec;
    }
}
