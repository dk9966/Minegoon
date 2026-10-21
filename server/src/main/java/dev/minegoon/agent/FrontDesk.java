package dev.minegoon.agent;

import dev.minegoon.actions.Receipt;
import dev.minegoon.chat.Commands.Speaker;
import dev.minegoon.jobs.Foreman;
import dev.minegoon.jobs.Mission;
import dev.minegoon.jobs.MissionBoard;
import dev.minegoon.jobs.Results;
import dev.minegoon.models.ModelClient;
import dev.minegoon.models.ModelClient.ToolCall;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Reads one sentence aimed at the helper, such as "Builder, what are you
 * carrying?", with one cheap model turn: answers it, acts on it at once, or
 * starts or changes a mission. A sentence during a mission is briefed with the
 * mission's plan and its latest lines. A ! line and the plain brakes (stop,
 * pause, resume, come) never come here.
 */
public final class FrontDesk {

    private static final int RECENT_LINES = 3;

    private static final String SYSTEM = """
        You read Minecraft chat aimed at %s, a helper player in Minecraft Java 26.3 survival, and pick one tool.
        Questions and greetings: answer, from what the briefing says. What it is carrying: inventory. What is
        around: look_around. How the work is going: status.
        A request for some of one item and nothing more: get ("a few" is 3, "some" is 8, "a stack" is 64).
        Anything else the helper must do, such as several items, a way or place to search, strip mining, or
        coming back after: start_mission, with the request restated in full.
        While a mission runs, a message that adds to it or changes it: amend_mission. Dropping it: command stop.
        Something survival cannot give, such as bedrock or a command block: answer, and say why.
        """;

    private final ModelClient model;
    private final String deskModel;
    private final Toolbox<Speaker> toolbox;
    private final MissionBoard missions;
    private final Foreman foreman;
    private final Results results;
    private final Consumer<String> say;
    private final Logger log;
    private final String helperName;

    public FrontDesk(ModelClient model, String deskModel, List<Tool<Speaker>> tools, MissionBoard missions,
                     Foreman foreman, Results results, Consumer<String> say, Logger log, String helperName) {
        this.model = model;
        this.deskModel = deskModel;
        this.toolbox = new Toolbox<>(tools, () -> "");
        this.missions = missions;
        this.foreman = foreman;
        this.results = results;
        this.say = say;
        this.log = log;
        this.helperName = helperName;
    }

    public boolean enabled() {
        return model.enabled();
    }

    /** Not on the server thread: the model call blocks. */
    public void hear(Speaker speaker, String text) {
        Optional<Mission> mission = missions.current();
        ToolCall call;
        try {
            call = model.pick(deskModel, SYSTEM.formatted(helperName), briefing(speaker, text, mission),
                toolbox.specs());
        } catch (IOException e) {
            log.warning("Could not read chat with the model: " + e.getMessage());
            String said = "Sorry, I didn't catch that. Try get 5 diamond.";
            say.accept(said);
            results.desk(new Results.Desk(null, "{}", false, said, 0));
            return;
        }
        Receipt receipt = toolbox.run(call, speaker);
        missions.event(mission.map(Mission::id).orElse(null), "desk: " + call.name() + " " + call.arguments()
            + " -> " + (receipt.confirmed() ? "ok" : "failed") + ", %d+%d tokens, cost %.5f".formatted(
                call.usage().promptTokens(), call.usage().completionTokens(), call.usage().cost()));
        String said = receipt.confirmed() ? receipt.observed() : "Sorry, I can't: " + receipt.observed();
        if (!said.isBlank()) {
            say.accept(said);
        }
        results.desk(new Results.Desk(call.name(), call.arguments().toString(), receipt.confirmed(), said,
            call.usage().cost()));
    }

    private String briefing(Speaker speaker, String text, Optional<Mission> mission) {
        StringBuilder briefing = new StringBuilder();
        briefing.append(speaker.name()).append(" said: ").append(text).append('\n');
        briefing.append(String.join("\n", foreman.status())).append('\n');
        mission.ifPresent(running -> {
            if (running.plan() != null) {
                briefing.append("Its plan: ").append(String.join("; ", running.plan().steps())).append('\n');
            }
            List<Mission.Line> log = running.log();
            if (!log.isEmpty()) {
                briefing.append("Latest:\n").append(log.subList(Math.max(0, log.size() - RECENT_LINES), log.size())
                    .stream().map(Mission.Line::describe).collect(Collectors.joining("\n"))).append('\n');
            }
        });
        return briefing.toString();
    }
}
