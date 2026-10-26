package dev.minegoon.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.minegoon.actions.ActionFailed;
import dev.minegoon.actions.Receipt;
import dev.minegoon.chat.Commands.Speaker;
import dev.minegoon.jobs.Mission;
import dev.minegoon.models.ModelClient.ToolCall;
import dev.minegoon.models.ModelClient.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolboxTest {

    private static final Set<String> TYPES = Set.of("object", "array", "string", "integer", "number", "boolean");

    /** Building the tools touches none of what they act on. */
    private static final List<Tool<Mission>> MISSION = new MissionTools(null, null, null, null, null,
        new Senses(null, null, null, null, null), null, Set.of("plains")::contains).all();
    private static final List<Tool<Speaker>> DESK = new DeskTools(null, null, null,
        new Senses(null, null, null, null, null), null).all();

    @Test
    void everySchemaIsAnObjectSchemaWhoseRequiredFieldsExist() {
        List<Tool<?>> all = new ArrayList<>(MISSION);
        all.addAll(DESK);
        for (Tool<?> tool : all) {
            assertTrue(tool.name().matches("[a-z_]+"), tool.name());
            assertFalse(tool.description().isBlank(), tool.name());
            check(tool.parameters(), tool.name());
            assertEquals("function", tool.spec().get("type").getAsString());
        }
    }

    @Test
    void digIsOfferedOnlyAsAGetItemsSearchOrder() {
        List<String> found = new ArrayList<>();
        MISSION.forEach(tool -> offers(tool.parameters(), "dig", tool.name(), found));
        DESK.forEach(tool -> offers(tool.parameters(), "dig", tool.name(), found));
        assertEquals(List.of("get_items.search.ways"), found);
    }

    @Test
    void badCountsAndItemsAreRefusedReceipts() {
        Toolbox<Mission> toolbox = new Toolbox<>(MISSION, () -> "");
        assertRefused(toolbox, "get_items", "{\"item\": \"diamond\", \"count\": 0}", "count must be between 1 and 576");
        assertRefused(toolbox, "get_items", "{\"item\": \"diamond\", \"count\": 9999}", "count must be between");
        assertRefused(toolbox, "get_items", "{\"item\": \"diamond\"}", "count is missing");
        assertRefused(toolbox, "get_items", "{\"item\": \"diamond\", \"count\": \"lots\"}", "whole number");
        assertRefused(toolbox, "get_items", "{\"item\": \"not_a_thing\", \"count\": 2}", "no item called not_a_thing");
        assertRefused(toolbox, "bring_back", "{\"count\": 2}", "item is missing");
    }

    @Test
    void searchOrdersAreCheckedLikeAdvice() {
        Toolbox<Mission> toolbox = new Toolbox<>(MISSION, () -> "");
        assertRefused(toolbox, "get_items", search("{\"ways\": [\"dig\"], \"biomes\": [\"plains\"]}"),
            "no item called not_a_thing");
        assertRefused(toolbox, "get_items", search("{\"ways\": [\"visit\"]}"), "no search move called visit");
        assertRefused(toolbox, "get_items", search("{\"ways\": [\"teleport\"]}"), "no search move called teleport");
        assertRefused(toolbox, "get_items", search("{\"biomes\": [\"moon\"]}"), "no biome called moon");
    }

    @Test
    void anUnknownToolOrAFailedActionIsARefusedReceiptWithTheState() {
        Tool<String> breaks = Tool.of("break", "always fails", (args, context) -> {
            throw new ActionFailed("the pick broke");
        });
        Tool<String> works = Tool.of("work", "always works", (args, context) -> Receipt.confirmed("work", "Done."));
        Toolbox<String> toolbox = new Toolbox<>(List.of(breaks, works), () -> "at 1 2 3");

        Receipt failed = toolbox.run(call("break", "{}"), "context");
        assertEquals(new Receipt("break", false, "the pick broke Now at 1 2 3."), failed);
        assertEquals(new Receipt("fly", false, "there is no such tool Now at 1 2 3."), toolbox.run(call("fly", "{}"), ""));
        assertEquals(new Receipt("work", true, "Done. Now at 1 2 3."), toolbox.run(call("work", "{}"), ""));
        assertEquals(1, toolbox.specs(Set.of("work")).size());
    }

    private static String search(String orders) {
        return "{\"item\": \"not_a_thing\", \"count\": 1, \"search\": " + orders + "}";
    }

    private static <C> void assertRefused(Toolbox<C> toolbox, String tool, String arguments, String because) {
        Receipt receipt = toolbox.run(call(tool, arguments), null);
        assertFalse(receipt.confirmed(), receipt.toString());
        assertTrue(receipt.observed().contains(because), receipt.toString());
    }

    private static ToolCall call(String tool, String arguments) {
        return new ToolCall(tool, JsonParser.parseString(arguments).getAsJsonObject(), new Usage("", 0, 0, 0));
    }

    /** A small JSON schema check: known types, arrays with items, required fields that exist. */
    private static void check(JsonObject schema, String where) {
        String type = schema.get("type").getAsString();
        if (!TYPES.contains(type)) {
            fail(where + " has an unknown type " + type);
        }
        if (schema.has("enum")) {
            schema.getAsJsonArray("enum").forEach(value -> assertTrue(value.getAsJsonPrimitive().isString(), where));
        }
        switch (type) {
            case "object" -> {
                JsonObject properties = schema.getAsJsonObject("properties");
                if (properties == null) {
                    fail(where + " has no properties");
                }
                if (schema.has("required")) {
                    schema.getAsJsonArray("required").forEach(name ->
                        assertTrue(properties.has(name.getAsString()), where + " requires " + name));
                }
                for (Map.Entry<String, JsonElement> property : properties.entrySet()) {
                    check(property.getValue().getAsJsonObject(), where + "." + property.getKey());
                }
            }
            case "array" -> check(schema.getAsJsonObject("items"), where);
            default -> {
            }
        }
    }

    private static void offers(JsonObject schema, String value, String where, List<String> found) {
        if (schema.has("enum")) {
            schema.getAsJsonArray("enum").forEach(option -> {
                if (option.getAsString().equals(value)) {
                    found.add(where);
                }
            });
        }
        if (schema.has("properties")) {
            schema.getAsJsonObject("properties").entrySet().forEach(property ->
                offers(property.getValue().getAsJsonObject(), value, where + "." + property.getKey(), found));
        }
        if (schema.has("items")) {
            offers(schema.getAsJsonObject("items"), value, where, found);
        }
    }
}
