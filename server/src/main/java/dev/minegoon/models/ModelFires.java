package dev.minegoon.models;

import dev.minegoon.models.ModelClient.Usage;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * One line each time a model answers. The planner is the big model. The desk,
 * during chat and during a mission that is going to plan, and the advisor are
 * the small one. The line is the same in the server log and in results.jsonl.
 * Players in the game see a chat line when a call goes out and when it answers.
 */
public final class ModelFires {

    /** Swallows the record. Tests that do not score model calls use this. */
    public static final ModelFires NONE = new ModelFires(fire -> { }, null);

    public record Fire(boolean big, String role, String asked, String answered, String what,
                       int promptTokens, int completionTokens, double cost, long at) {
    }

    private final Consumer<Fire> sink;
    private final Logger log;
    private final Consumer<String> chat;

    public ModelFires(Consumer<Fire> sink, Logger log) {
        this(sink, log, line -> { });
    }

    public ModelFires(Consumer<Fire> sink, Logger log, Consumer<String> chat) {
        this.sink = sink;
        this.log = log;
        this.chat = chat;
    }

    /** Just before a call goes out to {@code model}. */
    public void calling(boolean big, String role, String model) {
        chat.accept("[" + size(big) + " model] Calling " + model + " as the " + role + "...");
    }

    /** {@code asked} is the model we called. {@code usage} is who actually answered, when the call says. */
    public void fired(boolean big, String role, String asked, Usage usage, String what) {
        String answered = usage == null || usage.model() == null || usage.model().isBlank() ? asked : usage.model();
        int prompt = usage == null ? 0 : usage.promptTokens();
        int completion = usage == null ? 0 : usage.completionTokens();
        double cost = usage == null ? 0 : usage.cost();
        Fire fire = new Fire(big, role, asked, answered, what, prompt, completion, cost, System.currentTimeMillis());
        if (log != null) {
            log.info("model fire: " + size(big) + " " + role
                + " asked=" + asked + " answered=" + answered + " what=" + what
                + " tokens=" + prompt + "+" + completion + " cost=" + cost);
        }
        chat.accept("[" + size(big) + " model] " + answered + " answered: " + what
            + " (" + prompt + "+" + completion + " tokens)");
        sink.accept(fire);
    }

    private static String size(boolean big) {
        return big ? "big" : "small";
    }
}
