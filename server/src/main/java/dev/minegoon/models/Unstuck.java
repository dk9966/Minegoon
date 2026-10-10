package dev.minegoon.models;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Called once when a job has failed twice. Gets a short account of what went
 * wrong and picks from a fixed menu. Without a key, or if the call fails, it
 * replans from what the helper is holding, which fixes most lost-tool cases.
 */
public final class Unstuck {

    public enum Choice { RETRY, EXPLORE, REPLAN, OTHER_WAY, GIVE_UP }

    public record Decision(Choice choice, String reason, boolean askedModel) {
    }

    private static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "choice": {
              "type": "string",
              "enum": ["retry", "explore", "replan", "other_way", "give_up"]
            },
            "reason": {"type": "string", "description": "One short sentence"}
          },
          "required": ["choice", "reason"],
          "additionalProperties": false
        }
        """;

    private static final String SYSTEM = """
        You help a Minecraft helper player that is stuck on one step of a task. Pick one option:
        retry: the failure looks like bad luck, try the same step again.
        explore: nothing suitable is nearby, walk somewhere new first.
        replan: the inventory no longer matches the plan (lost tool, missing ingredient), plan again from it.
        other_way: this source of the item keeps failing, plan again without it.
        give_up: the step cannot work here, stop and tell the player.
        """;

    private final OpenRouter router;
    private final String model;
    private final Logger log;

    public Unstuck(OpenRouter router, String model, Logger log) {
        this.router = router;
        this.model = model;
        this.log = log;
    }

    public Decision decide(String briefing) {
        if (!router.enabled()) {
            return new Decision(Choice.REPLAN, "no model configured", false);
        }
        try {
            JsonObject answer = router.ask(model, SYSTEM, briefing, "unstuck_choice", SCHEMA);
            Choice choice = Choice.valueOf(answer.get("choice").getAsString().toUpperCase(Locale.ROOT));
            return new Decision(choice, answer.get("reason").getAsString(), true);
        } catch (IOException | RuntimeException e) {
            log.warning("Unstuck call failed, replanning instead: " + e.getMessage());
            return new Decision(Choice.REPLAN, "the model call failed", true);
        }
    }
}
