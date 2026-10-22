package dev.minegoon.jobs;

import dev.minegoon.MainThread;
import dev.minegoon.knowledge.Knowledge;
import dev.minegoon.knowledge.Lessons;
import dev.minegoon.models.Advisor;
import dev.minegoon.world.Structures;
import dev.minegoon.world.Vision;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Keeps the knowledge honest. When what the helper knows has run out, it asks
 * the model once per thing per task, saves the answer as a lesson on trial, and
 * later marks the lesson as having worked or missed. It also says what to carry
 * before a trip the knowledge marks as dangerous.
 */
public final class Tutor {

    private final Knowledge knowledge;
    private final Lessons lessons;
    private final Advisor advisor;
    private final Vision vision;
    private final Structures structures;
    private final MainThread main;
    private final Supplier<Player> player;
    private final Predicate<String> knownBiome;
    private final Logger log;
    private final Set<String> askedThisTask = new HashSet<>();

    public Tutor(Knowledge knowledge, Lessons lessons, Advisor advisor, Vision vision, Structures structures,
                 MainThread main, Supplier<Player> player, Predicate<String> knownBiome, Logger log) {
        this.knowledge = knowledge;
        this.lessons = lessons;
        this.advisor = advisor;
        this.vision = vision;
        this.structures = structures;
        this.main = main;
        this.player = player;
        this.knownBiome = knownBiome;
        this.log = log;
    }

    /** A fresh task may ask about everything again. */
    public void newTask() {
        askedThisTask.clear();
    }

    /**
     * Asks for advice about what a step looks for, at most once per thing per
     * task and never about something a kept lesson already covers. Returns the
     * lesson's note when a new lesson was saved. {@code asking} runs just before
     * a model is called. Worker thread.
     */
    public Optional<String> consult(String subject, String problem, Runnable asking) {
        if (subject == null || !advisor.enabled() || !lessons.open(subject) || !askedThisTask.add(subject)) {
            return Optional.empty();
        }
        asking.run();
        Knowledge.Find known = lessons.apply(subject, knowledge.find(List.of(subject)));
        Advisor.Gap gap = new Advisor.Gap(subject, describe(known), vision.biomeHere(),
            vision.biomes().stream().map(Vision.Patch::biome).toList(),
            structures.inSight(Set.of()).stream().map(Structures.Sighting::name).distinct().toList(),
            main.call(() -> player.get().getLocation().getBlockY()), problem);
        Optional<Lessons.Lesson> lesson = advisor.advise(gap, knownBiome, knowledge.structureNames());
        lesson.ifPresent(advice -> log.info("Lesson for " + subject + ": " + advice));
        return lesson.filter(advice -> lessons.learn(subject, advice)).map(Lessons.Lesson::note);
    }

    /** The things in {@code subjects} that neither the knowledge file nor a lesson covers. */
    public List<String> unknown(Collection<String> subjects) {
        return subjects.stream().filter(Objects::nonNull).distinct()
            .filter(subject -> !knowledge.knows(subject) && lessons.get(subject).isEmpty()).toList();
    }

    /**
     * Asks the model whether a task the helper has no plan or knowledge for can
     * be done at all. A "yes" that says where to look is saved as a lesson about
     * {@code subject} and about each block it says drops it. Empty without a
     * model. Worker thread.
     */
    public Optional<Advisor.Verdict> assess(String task, String subject, String problem, Runnable asking) {
        if (!advisor.enabled() || !askedThisTask.add(subject)) {
            return Optional.empty();
        }
        asking.run();
        Advisor.Gap gap = new Advisor.Gap(subject, "nothing", vision.biomeHere(),
            vision.biomes().stream().map(Vision.Patch::biome).toList(),
            structures.inSight(Set.of()).stream().map(Structures.Sighting::name).distinct().toList(),
            main.call(() -> player.get().getLocation().getBlockY()), "asked for " + task + "; " + problem);
        Optional<Advisor.Verdict> verdict = advisor.judge(gap, knownBiome, knowledge.structureNames());
        verdict.ifPresent(answer -> log.info("Verdict on " + task + ": " + answer));
        verdict.ifPresent(answer -> answer.lesson().ifPresent(lesson -> {
            lessons.learn(subject, lesson);
            answer.blocks().forEach(block -> lessons.learn(block.getKey().value(), lesson));
        }));
        return verdict;
    }

    /** The search for this thing found it, so a lesson on trial earned its place. */
    public void worked(String subject) {
        if (subject != null && lessons.get(subject).filter(lesson -> !lesson.kept()).isPresent()) {
            lessons.worked(subject);
        }
    }

    public void missed(String subject) {
        if (subject != null) {
            lessons.missed(subject);
        }
    }

    /** A supply the helper is short of: any of {@code items}, {@code count} in all. */
    public record Shortfall(Set<Material> items, int count, boolean optional) {
    }

    /** The supplies for going after {@code amount} of this thing that the helper is short of. */
    public List<Shortfall> missingSupplies(String subject, int amount, Map<Material, Integer> inventory) {
        String trip = subject == null ? null : knowledge.find(List.of(subject)).trip();
        List<Shortfall> missing = new ArrayList<>();
        for (Knowledge.Supply supply : knowledge.trip(trip)) {
            Set<Material> items = EnumSet.noneOf(Material.class);
            supply.items().forEach(name -> Optional.ofNullable(Material.matchMaterial(name)).ifPresent(items::add));
            int held = items.stream().mapToInt(item -> inventory.getOrDefault(item, 0)).sum();
            int needed = supply.needed(amount);
            if (!items.isEmpty() && held < needed) {
                missing.add(new Shortfall(items, needed, supply.optional()));
            }
        }
        return missing;
    }

    private static String describe(Knowledge.Find find) {
        String ways = find.ways().isEmpty() ? "no moves listed"
            : String.join(", ", find.ways().stream().map(Knowledge.Way::key).toList());
        String height = find.best() == null ? "" : ", best at y=" + find.best();
        String biomes = find.biomes().isEmpty() ? "" : ", biomes " + String.join(", ", find.biomes());
        return ways + height + biomes;
    }
}
