package dev.minegoon.knowledge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.minegoon.knowledge.Knowledge.Find;
import dev.minegoon.knowledge.Knowledge.Way;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the helper learned on top of knowledge.yml, one lesson per thing. A
 * lesson starts on trial. It is kept once a search that used it finds the thing,
 * and thrown away if it misses twice without ever working. A kept lesson is not
 * replaced, so the file stays small and only holds advice that worked.
 */
public final class Lessons {

    static final int CAP = 64;
    static final int MISSES_TO_DROP = 2;

    /** Moves to try first, and where, for one thing. */
    public record Lesson(List<Way> ways, List<String> biomes, List<String> visit, String note, int wins, int misses) {
        public boolean kept() {
            return wins > 0;
        }

        Find asFind() {
            return new Find(ways, Integer.MIN_VALUE, Integer.MAX_VALUE, null, biomes, visit, null);
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Map<String, Lesson> lessons = new LinkedHashMap<>();

    public Lessons(Path file) {
        this.file = file;
        if (Files.isRegularFile(file)) {
            try {
                Map<String, Lesson> saved = GSON.fromJson(Files.readString(file),
                    new TypeToken<Map<String, Lesson>>() { }.getType());
                if (saved != null) {
                    lessons.putAll(saved);
                }
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Could not read " + file + ": " + e.getMessage(), e);
            }
        }
    }

    public synchronized Optional<Lesson> get(String name) {
        return Optional.ofNullable(lessons.get(name));
    }

    /** The lesson's moves go first, then what the knowledge file says. */
    public synchronized Find apply(String name, Find base) {
        Lesson lesson = lessons.get(name);
        return lesson == null ? base : lesson.asFind().then(base);
    }

    /** True when advice for this thing could still change something. */
    public synchronized boolean open(String name) {
        Lesson lesson = lessons.get(name);
        return lesson == null || !lesson.kept();
    }

    /** Saves new advice on trial. A lesson that already worked is kept instead. */
    public synchronized boolean learn(String name, Lesson lesson) {
        if (!open(name) || lesson.ways().isEmpty()) {
            return false;
        }
        lessons.remove(name);
        if (lessons.size() >= CAP) {
            dropOne();
        }
        lessons.put(name, new Lesson(lesson.ways(), lesson.biomes(), lesson.visit(), lesson.note(), 0, 0));
        save();
        return true;
    }

    public synchronized void worked(String name) {
        Lesson lesson = lessons.get(name);
        if (lesson != null) {
            lessons.put(name, new Lesson(lesson.ways(), lesson.biomes(), lesson.visit(), lesson.note(),
                lesson.wins() + 1, lesson.misses()));
            save();
        }
    }

    public synchronized void missed(String name) {
        Lesson lesson = lessons.get(name);
        if (lesson == null) {
            return;
        }
        if (!lesson.kept() && lesson.misses() + 1 >= MISSES_TO_DROP) {
            lessons.remove(name);
        } else {
            lessons.put(name, new Lesson(lesson.ways(), lesson.biomes(), lesson.visit(), lesson.note(),
                lesson.wins(), lesson.misses() + 1));
        }
        save();
    }

    public synchronized int size() {
        return lessons.size();
    }

    /** Makes room: a lesson still on trial goes first, otherwise the one that worked least. */
    private void dropOne() {
        lessons.entrySet().stream()
            .min(Comparator.comparing((Map.Entry<String, Lesson> entry) -> entry.getValue().kept())
                .thenComparingInt(entry -> entry.getValue().wins()))
            .map(Map.Entry::getKey)
            .ifPresent(lessons::remove);
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(lessons));
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
