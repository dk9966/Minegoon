package dev.minegoon.jobs;

import com.google.gson.Gson;
import dev.minegoon.models.ModelFires;
import dev.minegoon.trips.Fighter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** One JSON line per finished task or mission and per front desk turn, for practice runs to score. */
public final class Results {

    private final Path file;
    private final Gson gson = new Gson();
    private final AtomicInteger guardCatches = new AtomicInteger();

    public Results(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** A break the helper tried that the last-line guard had to cancel. Should stay at zero. */
    public void guardCaught() {
        guardCatches.incrementAndGet();
    }

    /** One practice fight. */
    public void spar(Fighter.Tally tally) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("spar", tally);
        append(line);
    }

    /** One practice walk: where from and to, where it ended (null if it died), and how many ticks it took. */
    public record Trek(boolean reached, boolean died, String from, String to, String end, double blocks, long ticks) {
    }

    public void trek(Trek trek) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("trek", trek);
        append(line);
    }

    /** One front desk turn: the tool it picked, whether the game confirmed it, what the helper said, and the cost. */
    public record Desk(String tool, String args, boolean ok, String said, double cost) {
    }

    public void desk(Desk desk) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("desk", desk);
        append(line);
    }

    /** One answer from the big planner or from a small desk or advice call. */
    public void modelFire(ModelFires.Fire fire) {
        Map<String, Object> line = new LinkedHashMap<>();
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("size", fire.big() ? "big" : "small");
        call.put("role", fire.role());
        call.put("asked", fire.asked());
        call.put("answered", fire.answered());
        call.put("what", fire.what());
        call.put("prompt_tokens", fire.promptTokens());
        call.put("completion_tokens", fire.completionTokens());
        call.put("cost", fire.cost());
        call.put("at", fire.at());
        line.put("model_fire", call);
        append(line);
    }

    void record(Task task) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("task", task.id());
        line.put("mission_id", task.missionId());
        line.put("goal", task.describe());
        line.put("status", task.status().name().toLowerCase(Locale.ROOT));
        line.put("reason", task.reason());
        line.put("seconds", (System.currentTimeMillis() - task.createdAt()) / 1000);
        line.put("deaths", task.deaths());
        line.put("model_calls", task.modelCalls());
        line.put("guard_catches", guardCatches.getAndSet(0));
        append(line);
    }

    /** A mission that ended: how, what it cost, and each call with whether the game confirmed it. */
    public void record(Mission mission) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("mission", mission.id());
        line.put("request", mission.request());
        line.put("status", mission.status().name().toLowerCase(Locale.ROOT));
        line.put("reason", mission.reason());
        line.put("seconds", (System.currentTimeMillis() - mission.createdAt()) / 1000);
        line.put("turns", mission.turns());
        line.put("model_calls", mission.modelCalls());
        line.put("strong_calls", mission.strongCalls());
        line.put("cost", mission.cost());
        line.put("calls", mission.log().stream().filter(Mission.Line::isCall).map(call -> Map.of(
            "tool", call.tool(), "args", call.args().toString(),
            "ok", call.receipt() != null && call.receipt().confirmed())).toList());
        append(line);
    }

    private synchronized void append(Map<String, Object> line) {
        try {
            Files.writeString(file, gson.toJson(line) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
