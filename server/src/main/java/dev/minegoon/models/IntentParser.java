package dev.minegoon.models;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Optional;

/**
 * Turns free chat aimed at the helper, such as "Builder, grab me a few iron
 * ingots", into one of the ! commands. The model only picks fields; the result
 * goes through the same command handling as anything typed by hand.
 */
public final class IntentParser {

    private static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "command": {
              "type": "string",
              "enum": ["get", "come", "give", "status", "stop", "pause", "resume", "protect", "allow", "none"],
              "description": "The request, or none if the message is not a request for the helper"
            },
            "item": {
              "type": "string",
              "description": "For get: a Minecraft item id such as diamond or oak_log. Otherwise empty."
            },
            "number": {
              "type": "integer",
              "description": "For get: how many. For protect and allow: the radius in blocks. Otherwise 0."
            }
          },
          "required": ["command", "item", "number"],
          "additionalProperties": false
        }
        """;

    private static final String SYSTEM = """
        You read Minecraft chat aimed at a helper player named %s and turn it into a command.
        get: fetch items ("a few" means 3, "some" means 8, "a stack" means 64). come: walk to the speaker.
        give: hand over what it gathered. status: report progress. stop: drop the task. pause and resume.
        protect: never dig within a radius of the speaker. allow: may dig player-made blocks nearby for this task.
        Use none for greetings, questions, or anything that is not a request.
        """;

    private final OpenRouter router;
    private final String model;
    private final String helperName;

    public IntentParser(OpenRouter router, String model, String helperName) {
        this.router = router;
        this.model = model;
        this.helperName = helperName;
    }

    public boolean enabled() {
        return router.enabled();
    }

    /** A command line such as "!get 3 iron_ingot", or empty if the message asks for nothing. */
    public Optional<String> toCommand(String message) throws IOException {
        JsonObject answer = router.ask(model, SYSTEM.formatted(helperName), message, "helper_command", SCHEMA);
        String command = answer.get("command").getAsString();
        int number = answer.get("number").getAsInt();
        return switch (command) {
            case "none" -> Optional.empty();
            case "get" -> Optional.of("!get " + Math.max(1, number) + " " + answer.get("item").getAsString());
            case "protect", "allow" -> Optional.of("!" + command + " " + number);
            default -> Optional.of("!" + command);
        };
    }
}
