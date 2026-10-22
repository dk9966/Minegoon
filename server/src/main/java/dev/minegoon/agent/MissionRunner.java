package dev.minegoon.agent;

import com.google.gson.JsonArray;
import dev.minegoon.ItemSet;
import dev.minegoon.MainThread.Cancelled;
import dev.minegoon.Settings;
import dev.minegoon.actions.Receipt;
import dev.minegoon.jobs.Mission;
import dev.minegoon.jobs.Mission.Check;
import dev.minegoon.jobs.Mission.Line;
import dev.minegoon.jobs.MissionBoard;
import dev.minegoon.jobs.Task;
import dev.minegoon.models.ModelClient;
import dev.minegoon.models.ModelClient.ToolCall;
import dev.minegoon.models.ModelFires;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Works a mission through on the foreman's worker thread, one tool call per
 * turn. Each turn the model gets a fresh, short briefing: the request, the
 * plan, one line per call so far with its receipt, and the helper's state
 * now. There is no chat history to reread, so a late turn costs what an early
 * one does, and a mission carries on after a restart from what is saved.
 *
 * The planner model writes the plan and takes every turn after something went
 * wrong or changed. The cheap model takes the turns where things go to plan.
 */
public final class MissionRunner {

    static final String WRITE_PLAN = "write_plan";
    static final String FINISH = "finish";
    static final String GIVE_UP = "give_up";
    private static final Set<String> PLANNING = Set.of(WRITE_PLAN, GIVE_UP);
    private static final int MODEL_TRIES = 3;
    private static final int NEAR_BY_DEFAULT = 4;
    private static final int MAX_NEAR = 64;

    private static final String SYSTEM = """
        You control %s, a helper player in Minecraft Java 26.3 survival, to carry out one player's request.
        Each turn you call exactly one tool. The helper does it, and you read a receipt: what the game shows
        happened. Receipts are the truth, not what was hoped.
        First write a short plan with write_plan: the steps, and done_when checks the game can test once the
        whole request is done (the helper has N of an item, or is within R blocks of a player). Choose checks
        that all hold at the end, after anything is handed over.
        get_items does long work on its own: it plans, gathers, crafts, smelts, and fights for everything an
        item needs, and packs what a dangerous trip takes before it goes. Ask it for the item the player wants,
        not for the parts, with one exception: when several items need the same material from a long trip, such
        as diamonds for a set of diamond armor, first ask for all of that material in one call, so the helper
        goes down once, then ask for each item.
        Give get_items a search only when the player said how or where to look, and use the dig way only when
        they asked for strip or branch mining.
        When a step fails, read why, then try another way or write a new plan.
        Call finish with a one-sentence summary once the request is done; it checks done_when first. Call
        give_up with the reason when the request cannot be done in survival or keeps failing. Both are said in
        chat for you, so use say only for something the player needs to hear now.
        """;

    private final ModelClient model;
    private final Settings.Agent agent;
    private final MissionBoard missions;
    private final Toolbox<Mission> toolbox;
    private final BiPredicate<Check, Mission> met;
    private final Consumer<String> say;
    private final LongSupplier clock;
    private final String helperName;
    private final ModelFires fires;

    /**
     * {@code tools} are what the helper can do; the runner adds write_plan,
     * finish, and give_up. {@code met} tells whether a check holds in the game.
     */
    public MissionRunner(ModelClient model, Settings.Agent agent, MissionBoard missions, List<Tool<Mission>> tools,
                         Supplier<String> state, BiPredicate<Check, Mission> met, Consumer<String> say,
                         LongSupplier clock, String helperName, ModelFires fires) {
        this.model = model;
        this.agent = agent;
        this.missions = missions;
        List<Tool<Mission>> all = new ArrayList<>(List.of(writePlan(), finish(), giveUp()));
        all.addAll(tools);
        this.toolbox = new Toolbox<>(all, state);
        this.met = met;
        this.say = say;
        this.clock = clock;
        this.helperName = helperName;
        this.fires = fires;
    }

    /** Runs a mission until it ends, pauses, or the worker is interrupted. */
    public void run(long id) {
        try {
            carryOn(mission(id));
            while (turn(mission(id))) {
                // Each turn reads the mission afresh.
            }
        } catch (Cancelled stopped) {
            throw stopped;
        } catch (RuntimeException broken) {
            end(id, Task.Status.BLOCKED, "something went wrong on my side: " + broken.getMessage());
            throw broken;
        }
    }

    /**
     * A call that was running when the work stopped. One that was fetching a
     * task carries on with it; anything else is over, and the model reads that.
     */
    private void carryOn(Mission mission) {
        if (mission.status() != Task.Status.ACTIVE) {
            return;
        }
        mission.pending().ifPresent(line -> {
            if (line.task() == null) {
                missions.close(mission.id(), Receipt.refused(line.tool(), "Interrupted before it finished."));
            } else {
                act(mission.id(), new ToolCall(line.tool(), line.args(), null), "carried on");
            }
        });
    }

    /** One turn. False once the mission is no longer active. */
    private boolean turn(Mission mission) {
        if (mission.status() != Task.Status.ACTIVE) {
            return false;
        }
        boolean strong = mission.needsPlanner();
        Optional<String> over = overLimit(mission, strong);
        if (over.isPresent()) {
            end(mission.id(), Task.Status.BLOCKED, over.get());
            say.accept("I'm stopping " + mission.describe() + ": " + over.get() + ".");
            return false;
        }
        String chosen = strong ? agent.plannerModel() : agent.deskModel();
        String role = strong ? "planner" : "desk";
        boolean planning = mission.plan() == null;
        ToolCall call = pick(mission, chosen, planning ? toolbox.specs(PLANNING) : toolbox.specs());
        if (call == null) {
            return false;
        }
        fires.fired(strong, role, chosen, call.usage(), call.name());
        missions.counted(mission.id(), strong, call.usage().cost());
        missions.append(mission.id(), Line.call(call.name(), call.arguments()));
        String who = (strong ? "big " : "small ") + role;
        if (planning && !PLANNING.contains(call.name())) {
            close(mission.id(), call, Receipt.refused(call.name(), "Write the plan first, with write_plan."), who);
        } else {
            act(mission.id(), call, who);
        }
        return true;
    }

    /** Runs a call and saves its receipt. A finish or give_up that went through then ends the mission. */
    private void act(long id, ToolCall call, String by) {
        Receipt receipt = toolbox.run(call, mission(id));
        close(id, call, receipt, by);
        if (!receipt.confirmed()) {
            return;
        }
        switch (call.name()) {
            case FINISH -> {
                String summary = new Args(call.arguments()).text("summary");
                end(id, Task.Status.DONE, summary);
                say.accept(summary);
            }
            case GIVE_UP -> {
                String reason = new Args(call.arguments()).text("reason");
                end(id, Task.Status.BLOCKED, reason);
                say.accept("I can't do that: " + reason);
            }
            default -> {
            }
        }
    }

    private void close(long id, ToolCall call, Receipt receipt, String by) {
        missions.close(id, receipt);
        String usage = call.usage() == null ? "" : ", %d+%d tokens, cost %.5f".formatted(call.usage().promptTokens(),
            call.usage().completionTokens(), call.usage().cost());
        missions.event(id, by + ": " + call.name() + " " + shorten(call.arguments().toString()) + " -> "
            + (receipt.confirmed() ? "ok" : "failed") + usage);
    }

    /** Asks the chosen model for one call, trying again on errors. Null when it cannot be reached. */
    private ToolCall pick(Mission mission, String chosen, JsonArray tools) {
        String system = SYSTEM.formatted(helperName);
        for (int attempt = 1; ; attempt++) {
            try {
                return model.pick(chosen, system, briefing(mission), tools);
            } catch (IOException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new Cancelled();
                }
                if (attempt == MODEL_TRIES) {
                    end(mission.id(), Task.Status.BLOCKED, "the model could not be reached: " + e.getMessage());
                    say.accept("I'm stopping " + mission.describe() + ": I can't reach the model.");
                    return null;
                }
            }
        }
    }

    private Optional<String> overLimit(Mission mission, boolean strong) {
        if (mission.turns() >= agent.maxTurns()) {
            return Optional.of("I used all " + agent.maxTurns() + " turns");
        }
        if (strong && mission.strongCalls() >= agent.maxStrongCalls()) {
            return Optional.of("I needed to rethink more than " + agent.maxStrongCalls() + " times");
        }
        if (clock.getAsLong() - mission.createdAt() > agent.maxMinutes() * 60_000L) {
            return Optional.of("it has taken more than " + agent.maxMinutes() + " minutes");
        }
        List<Line> calls = mission.log().stream().filter(Line::isCall).toList();
        if (calls.size() >= 2) {
            Line last = calls.getLast();
            Line before = calls.get(calls.size() - 2);
            if (last.failed() && before.failed() && last.tool().equals(before.tool())
                && last.args().equals(before.args())) {
                return Optional.of(last.tool() + " failed twice the same way: " + last.receipt().observed());
            }
        }
        return Optional.empty();
    }

    String briefing(Mission mission) {
        StringBuilder text = new StringBuilder();
        text.append("Request from ").append(mission.ownerName()).append(": ").append(mission.describe()).append('\n');
        if (mission.plan() == null) {
            text.append("Plan: none yet. Write it with write_plan, or give_up if it cannot be done in survival.\n");
        } else {
            List<String> steps = mission.plan().steps();
            text.append("Plan:\n").append(IntStream.range(0, steps.size())
                .mapToObj(i -> (i + 1) + ". " + steps.get(i)).collect(Collectors.joining("\n"))).append('\n');
            text.append("Done when: ").append(mission.plan().doneWhen().isEmpty() ? "nothing the game can check"
                : mission.plan().doneWhen().stream().map(Check::describe).collect(Collectors.joining("; ")))
                .append('\n');
        }
        if (!mission.log().isEmpty()) {
            text.append("So far:\n").append(mission.log().stream().map(Line::describe)
                .collect(Collectors.joining("\n"))).append('\n');
        }
        text.append("Now ").append(toolbox.state()).append(".\n");
        text.append("Turn ").append(mission.turns() + 1).append(" of ").append(agent.maxTurns()).append('.');
        return text.toString();
    }

    private Tool<Mission> writePlan() {
        return Tool.of(WRITE_PLAN, """
            Writes the plan, or a new one in place of the old: short steps in order, and the checks that say the \
            whole request is done.""", """
            {"type": "object", "properties": {
              "steps": {"type": "array", "items": {"type": "string"}},
              "done_when": {"type": "array", "items": {"type": "object", "properties": {
                "kind": {"type": "string", "enum": ["have", "near"],
                         "description": "have: the helper holds count of item. near: it is within blocks of player."},
                "item": {"type": "string", "description": "For have: an item id"},
                "count": {"type": "integer", "minimum": 1},
                "player": {"type": "string", "description": "For near: leave empty for the player who asked"},
                "within": {"type": "integer", "minimum": 1, "maximum": %d}
              }, "required": ["kind"]}}
            }, "required": ["steps", "done_when"]}""".formatted(MAX_NEAR), (args, mission) -> {
                List<String> steps = args.texts("steps");
                if (steps.isEmpty()) {
                    throw new Args.Refused("a plan needs at least one step");
                }
                List<Check> checks = new ArrayList<>();
                for (Args check : args.objects("done_when")) {
                    checks.add(switch (check.text("kind")) {
                        case "have" -> Check.have(ItemSet.key(check.item("item")), check.count("count"));
                        case "near" -> Check.near(check.text("player", mission.ownerName()),
                            check.number("within", 1, MAX_NEAR, NEAR_BY_DEFAULT));
                        default -> throw new Args.Refused("a check is have or near");
                    });
                }
                missions.setPlan(mission.id(), new Mission.Plan(steps, checks));
                return Receipt.confirmed(WRITE_PLAN, "Plan saved: " + steps.size() + " steps, " + checks.size()
                    + " checks.");
            });
    }

    private Tool<Mission> finish() {
        return Tool.of(FINISH, "Ends the mission as done. The game checks the plan's done_when first.", """
            {"type": "object", "properties": {"summary": {"type": "string"}}, "required": ["summary"]}""",
            (args, mission) -> {
                args.text("summary");
                List<String> unmet = mission.plan().doneWhen().stream().filter(check -> !met.test(check, mission))
                    .map(Check::describe).toList();
                return unmet.isEmpty() ? Receipt.confirmed(FINISH, "Done.")
                    : Receipt.refused(FINISH, "Not done yet: " + String.join("; ", unmet) + ".");
            });
    }

    private Tool<Mission> giveUp() {
        return Tool.of(GIVE_UP, "Ends the mission without doing it, and says why.", """
            {"type": "object", "properties": {"reason": {"type": "string"}}, "required": ["reason"]}""",
            (args, mission) -> {
                args.text("reason");
                return Receipt.confirmed(GIVE_UP, "Gave up.");
            });
    }

    private void end(long id, Task.Status status, String reason) {
        missions.setStatus(id, status, reason);
    }

    private Mission mission(long id) {
        return missions.mission(id).orElseThrow();
    }

    private static String shorten(String text) {
        return text.length() <= 160 ? text : text.substring(0, 157) + "...";
    }
}
