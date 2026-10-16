package dev.minegoon.jobs;

import com.google.gson.Gson;
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

/** One JSON line per finished task, for practice runs to score. */
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

    void record(Task task) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("task", task.id());
        line.put("goal", task.describe());
        line.put("status", task.status().name().toLowerCase(Locale.ROOT));
        line.put("reason", task.reason());
        line.put("seconds", (System.currentTimeMillis() - task.createdAt()) / 1000);
        line.put("deaths", task.deaths());
        line.put("model_calls", task.modelCalls());
        line.put("guard_catches", guardCatches.getAndSet(0));
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
