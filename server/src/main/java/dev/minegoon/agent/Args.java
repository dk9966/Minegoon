package dev.minegoon.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minegoon.ItemSet;
import dev.minegoon.chat.Commands;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;

/**
 * A tool call's arguments, read with the same checks as a typed command. An
 * argument that does not pass refuses the call, and the model reads why.
 */
public final class Args {

    /** An argument a tool cannot use. It comes back to the model as a refused receipt. */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message, null, false, false);
        }
    }

    private final JsonObject json;

    public Args(JsonObject json) {
        this.json = json == null ? new JsonObject() : json;
    }

    public boolean has(String name) {
        JsonElement value = json.get(name);
        return value != null && !value.isJsonNull();
    }

    public String text(String name) {
        String text = text(name, "");
        if (text.isBlank()) {
            throw new Refused(name + " is missing");
        }
        return text;
    }

    public String text(String name, String fallback) {
        if (!has(name)) {
            return fallback;
        }
        try {
            return json.get(name).getAsString().trim();
        } catch (RuntimeException notText) {
            throw new Refused(name + " must be text");
        }
    }

    public int number(String name, int low, int high) {
        if (!has(name)) {
            throw new Refused(name + " is missing");
        }
        int number;
        try {
            number = json.get(name).getAsInt();
        } catch (RuntimeException notANumber) {
            throw new Refused(name + " must be a whole number");
        }
        if (number < low || number > high) {
            throw new Refused(name + " must be between " + low + " and " + high);
        }
        return number;
    }

    public int number(String name, int low, int high, int fallback) {
        return has(name) ? number(name, low, high) : fallback;
    }

    /** How many of an item, up to what a typed get allows. */
    public int count(String name) {
        return number(name, 1, Commands.MAX_COUNT);
    }

    /** An item id such as oak_log, or a plain name such as "oak logs". */
    public Material item(String name) {
        String words = text(name);
        Material item = ItemSet.parse(words);
        if (item == null) {
            throw new Refused("there is no item called " + words + "; use a Minecraft item id such as oak_log");
        }
        return item;
    }

    public List<String> texts(String name) {
        if (!has(name)) {
            return List.of();
        }
        if (!json.get(name).isJsonArray()) {
            throw new Refused(name + " must be a list");
        }
        List<String> texts = new ArrayList<>();
        json.getAsJsonArray(name).forEach(element -> texts.add(element.getAsString().trim()));
        return texts;
    }

    /** A nested object, or no arguments at all when it was left out. */
    public Args object(String name) {
        if (!has(name)) {
            return new Args(new JsonObject());
        }
        if (!json.get(name).isJsonObject()) {
            throw new Refused(name + " must be an object");
        }
        return new Args(json.getAsJsonObject(name));
    }

    public List<Args> objects(String name) {
        if (!has(name)) {
            return List.of();
        }
        if (!json.get(name).isJsonArray()) {
            throw new Refused(name + " must be a list");
        }
        List<Args> objects = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray(name)) {
            if (!element.isJsonObject()) {
                throw new Refused(name + " must be a list of objects");
            }
            objects.add(new Args(element.getAsJsonObject()));
        }
        return objects;
    }
}
