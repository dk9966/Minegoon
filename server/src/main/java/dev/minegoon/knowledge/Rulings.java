package dev.minegoon.knowledge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Detours the helper has judged before, one per offer. A take starts kept: it
 * paid off, so the next time it is done without a model. Two misses drop it,
 * and the question is asked again. A skip is not stored. The kit and the goal
 * change, and a no from one trip is not a no forever.
 */
public final class Rulings {

    static final int MISSES_TO_DROP = 2;

    /** {@code wins} above zero means the take paid off at least once. */
    public record Ruling(String note, int wins, int misses) {
        public boolean kept() {
            return wins > 0;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Map<String, Ruling> rulings = new LinkedHashMap<>();

    public Rulings(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try {
                Map<String, Ruling> saved = GSON.fromJson(Files.readString(file),
                    new TypeToken<Map<String, Ruling>>() { }.getType());
                if (saved != null) {
                    rulings.putAll(saved);
                }
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Could not read " + file + ": " + e.getMessage(), e);
            }
        }
    }

    public synchronized Optional<Ruling> get(String name) {
        return Optional.ofNullable(rulings.get(name));
    }

    public synchronized int size() {
        return rulings.size();
    }

    /** The take paid off. A kept note stays; only the win count moves. */
    public synchronized void worked(String name, String note) {
        Ruling ruling = rulings.get(name);
        if (ruling != null && ruling.kept()) {
            rulings.put(name, new Ruling(ruling.note(), ruling.wins() + 1, ruling.misses()));
        } else {
            rulings.put(name, new Ruling(note, 1, ruling == null ? 0 : ruling.misses()));
        }
        save();
    }

    /** A kept take missed. Two misses drop it. Nothing is stored for a first try that missed. */
    public synchronized void missed(String name) {
        Ruling ruling = rulings.get(name);
        if (ruling == null) {
            return;
        }
        if (ruling.misses() + 1 >= MISSES_TO_DROP) {
            rulings.remove(name);
        } else {
            rulings.put(name, new Ruling(ruling.note(), ruling.wins(), ruling.misses() + 1));
        }
        save();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(rulings));
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
