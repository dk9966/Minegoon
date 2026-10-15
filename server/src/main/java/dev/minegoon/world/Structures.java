package dev.minegoon.world;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.minegoon.knowledge.Knowledge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;

/**
 * Recognises structures from blocks in sight that rarely appear anywhere else:
 * a bell and a composter mean a village, rails and cobwebs a mineshaft. It is
 * one block search for every mark at once, so it costs about the same as
 * looking for a single ore. Every structure recognised is remembered in a
 * file, so one seen once can be walked back to from out of sight.
 */
public final class Structures {

    private static final int TOGETHER = 24;
    private static final int MAX_MARKS = 256;

    public record Sighting(String name, Pos at) {
    }

    private record Kind(String name, int need, Map<Material, Integer> marks) {
    }

    private static final Gson GSON = new Gson();

    private final Vision vision;
    private final List<Kind> kinds = new ArrayList<>();
    private final Path file;
    private final Logger log;
    private final List<Sighting> remembered = new ArrayList<>();

    public Structures(Vision vision, List<Knowledge.Structure> structures, Path file, Logger log) {
        this.vision = vision;
        this.file = file;
        this.log = log;
        load();
        for (Knowledge.Structure structure : structures) {
            Map<Material, Integer> marks = new EnumMap<>(Material.class);
            structure.marks().forEach((name, weight) -> {
                Material block = Material.matchMaterial(name);
                if (block == null || !block.isBlock()) {
                    log.warning("Structure " + structure.name() + " has an unknown mark block: " + name);
                } else {
                    marks.put(block, weight);
                }
            });
            kinds.add(new Kind(structure.name(), structure.need(), marks));
        }
    }

    /** Structures in sight, nearest first, limited to {@code wanted} names when that is not empty. */
    public List<Sighting> inSight(Set<String> wanted) {
        List<Kind> looking = kinds.stream().filter(kind -> wanted.isEmpty() || wanted.contains(kind.name())).toList();
        Set<Material> marks = EnumSet.noneOf(Material.class);
        looking.forEach(kind -> marks.addAll(kind.marks().keySet()));
        if (marks.isEmpty()) {
            return List.of();
        }
        List<Pos> seen = vision.blocks(marks, MAX_MARKS, candidate -> true);
        if (seen.isEmpty()) {
            return List.of();
        }
        WorldView view = vision.around();
        List<Material> types = seen.stream().map(pos -> view.type(pos.x(), pos.y(), pos.z())).toList();
        List<Sighting> sightings = new ArrayList<>();
        for (Kind kind : looking) {
            for (int i = 0; i < seen.size(); i++) {
                Pos at = seen.get(i);
                if (!kind.marks().containsKey(types.get(i)) || near(sightings, kind.name(), at)) {
                    continue;
                }
                if (weightAround(kind, at, seen, types) >= kind.need()) {
                    sightings.add(new Sighting(kind.name(), at));
                }
            }
        }
        remember(sightings);
        return sightings;
    }

    /** Structures seen before, in sight or not, nearest to {@code from} first. */
    public synchronized List<Sighting> remembered(Set<String> wanted, Pos from, int within) {
        return remembered.stream()
            .filter(sighting -> wanted.contains(sighting.name()) && sighting.at().distanceSquared(from) <= within * within)
            .sorted(Comparator.comparingDouble(sighting -> sighting.at().distanceSquared(from)))
            .toList();
    }

    private synchronized void remember(List<Sighting> sightings) {
        List<Sighting> fresh = sightings.stream()
            .filter(sighting -> !near(remembered, sighting.name(), sighting.at())).toList();
        if (fresh.isEmpty()) {
            return;
        }
        remembered.addAll(fresh);
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(remembered));
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warning("Could not save " + file + ": " + e.getMessage());
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<Sighting> saved = GSON.fromJson(Files.readString(file), new TypeToken<List<Sighting>>() { }.getType());
            if (saved != null) {
                remembered.addAll(saved);
            }
        } catch (IOException | RuntimeException e) {
            log.warning("Could not read " + file + ": " + e.getMessage());
        }
    }

    /** Each kind of mark counts once, so a pile of obsidian from a lava pool is not a portal. */
    private static int weightAround(Kind kind, Pos at, List<Pos> seen, List<Material> types) {
        Set<Material> found = EnumSet.noneOf(Material.class);
        for (int i = 0; i < seen.size(); i++) {
            if (kind.marks().containsKey(types.get(i)) && seen.get(i).distanceSquared(at) <= TOGETHER * TOGETHER) {
                found.add(types.get(i));
            }
        }
        return found.stream().mapToInt(kind.marks()::get).sum();
    }

    private static boolean near(List<Sighting> sightings, String name, Pos at) {
        return sightings.stream()
            .anyMatch(sighting -> sighting.name().equals(name) && sighting.at().distanceSquared(at) <= TOGETHER * TOGETHER);
    }
}
