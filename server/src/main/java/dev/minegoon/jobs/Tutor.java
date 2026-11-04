package dev.minegoon.jobs;

import dev.minegoon.MainThread;
import dev.minegoon.knowledge.Knowledge;
import dev.minegoon.knowledge.Lessons;
import dev.minegoon.knowledge.Notices;
import dev.minegoon.knowledge.Rulings;
import dev.minegoon.models.Advisor;
import dev.minegoon.safety.Zones;
import dev.minegoon.trips.Bestiary;
import dev.minegoon.trips.Food;
import dev.minegoon.world.Pos;
import dev.minegoon.world.Structures;
import dev.minegoon.world.Vision;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

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
    private final Rulings rulings;
    private final Zones zones;
    private final Set<String> askedThisTask = new HashSet<>();
    /** Offers already taken or asked about during the task now running. */
    private final Set<String> settled = new HashSet<>();

    public Tutor(Knowledge knowledge, Lessons lessons, Advisor advisor, Vision vision, Structures structures,
                 MainThread main, Supplier<Player> player, Predicate<String> knownBiome, Logger log,
                 Rulings rulings, Zones zones) {
        this.knowledge = knowledge;
        this.lessons = lessons;
        this.advisor = advisor;
        this.vision = vision;
        this.structures = structures;
        this.main = main;
        this.player = player;
        this.knownBiome = knownBiome;
        this.log = log;
        this.rulings = rulings;
        this.zones = zones;
    }

    /** A fresh task may ask about everything again, and may stop for the same offer again. */
    public void newTask() {
        askedThisTask.clear();
        settled.clear();
    }

    /**
     * An offer in sight that covers {@code needs} and is not what {@code step}
     * is already doing. Empty when the world has nothing the step cannot spend,
     * or when there is no body to look with.
     */
    public Optional<Notices.Spot> notice(Set<String> needs, Step step) {
        if (vision == null || structures == null || player == null || main == null || needs.isEmpty()) {
            return Optional.empty();
        }
        String world = main.call(() -> {
            Player self = player.get();
            return self == null ? null : self.getWorld().getName();
        });
        if (world == null) {
            return Optional.empty();
        }
        Set<String> seen = new LinkedHashSet<>();
        structures.inSight(Set.of()).stream()
            .filter(sighting -> zones == null || !zones.protects(world, sighting.at().x(), sighting.at().z()))
            .map(Structures.Sighting::name)
            .forEach(seen::add);
        Map<String, Boolean> mobs = new LinkedHashMap<>();
        for (Knowledge.Offer offer : knowledge.offers()) {
            if (!offer.see().equals("mob")) {
                continue;
            }
            Optional<EntityType> type = creature(offer.name());
            if (type.isEmpty()) {
                continue;
            }
            List<UUID> ids = vision.creatures(Set.of(type.get()), this::outsideProtection, 1);
            if (!ids.isEmpty()) {
                mobs.put(offer.name(), winnable(ids.getFirst()));
            }
        }
        String situation = main.call(() -> reading(player.get()));
        return Notices.spot(knowledge.offers(), needs, new Notices.Sight(seen, mobs), Notices.busy(step), settled,
            rulings == null ? name -> Optional.empty() : rulings::get, situation);
    }

    /** This task has dealt with the offer, whether it was taken or turned down. */
    public void settle(String offer) {
        settled.add(offer);
    }

    /** The nearest sighting of a structure, for walking up to its fields. */
    public Optional<Pos> nearest(String structure) {
        if (structures == null || player == null || main == null) {
            return Optional.empty();
        }
        Pos feet = main.call(() -> Pos.of(player.get().getLocation()));
        return structures.inSight(Set.of(structure)).stream()
            .min(Comparator.comparingDouble(sighting -> sighting.at().distanceSquared(feet)))
            .map(Structures.Sighting::at);
    }

    /** Yes or no from the model. Empty without one. */
    public Optional<Advisor.Choice> consider(String card) {
        return advisor == null ? Optional.empty() : advisor.consider(card);
    }

    /** The take paid off, so next time it is done without asking. */
    public void kept(String offer, String note) {
        if (rulings != null) {
            rulings.worked(offer, note);
        }
    }

    /** A remembered take missed. Two misses and it is asked about again. */
    public void forgot(String offer) {
        if (rulings != null) {
            rulings.missed(offer);
        }
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

    /** One trip of a task: what it goes after, and how many. */
    public record Trip(String subject, int amount) {
    }

    /** The supplies for going after {@code amount} of this thing that the helper is short of. */
    public List<Shortfall> missingSupplies(String subject, int amount, Map<Material, Integer> inventory) {
        return missingSupplies(new Trip(subject, amount), List.of(), true, inventory);
    }

    /**
     * What the helper is short of for the trip it is about to make. With
     * {@code packing}, that is the first trip of the task: what is only found up
     * top is packed for this trip and every one in {@code later} too, adding
     * up, since each trip uses its own. Without it, that was packed already, and
     * whatever has been used up since is not worth a climb back up for.
     */
    public List<Shortfall> missingSupplies(Trip now, List<Trip> later, boolean packing,
                                           Map<Material, Integer> inventory) {
        Map<List<String>, Integer> needed = new LinkedHashMap<>();
        Map<List<String>, Boolean> optional = new LinkedHashMap<>();
        for (Knowledge.Supply supply : supplies(now)) {
            if (supply.upTop() && !packing) {
                continue;
            }
            needed.merge(supply.items(), supply.needed(now.amount()), Integer::sum);
            optional.merge(supply.items(), supply.optional(), Boolean::logicalAnd);
        }
        for (Trip trip : packing ? later : List.<Trip>of()) {
            for (Knowledge.Supply supply : supplies(trip)) {
                if (supply.upTop()) {
                    needed.merge(supply.items(), supply.needed(trip.amount()), Integer::sum);
                    optional.merge(supply.items(), supply.optional(), Boolean::logicalAnd);
                }
            }
        }
        List<Shortfall> missing = new ArrayList<>();
        needed.forEach((names, count) -> {
            Set<Material> items = EnumSet.noneOf(Material.class);
            names.forEach(name -> Optional.ofNullable(Material.matchMaterial(name)).ifPresent(items::add));
            int held = items.stream().mapToInt(item -> inventory.getOrDefault(item, 0)).sum();
            if (!items.isEmpty() && held < count) {
                missing.add(new Shortfall(items, count, optional.get(names)));
            }
        });
        return missing;
    }

    private List<Knowledge.Supply> supplies(Trip trip) {
        return trip.subject() == null ? List.of() : knowledge.trip(knowledge.find(List.of(trip.subject())).trip());
    }

    /** A mob standing in a protected area is someone else's. */
    private boolean outsideProtection(Entity entity) {
        return zones == null || !zones.protects(entity.getWorld().getName(), entity.getLocation().getBlockX(),
            entity.getLocation().getBlockZ());
    }

    private static Optional<EntityType> creature(String name) {
        try {
            return Optional.of(EntityType.valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /** The fight math already says whether this mob is one to start. Main thread for the second half. */
    private boolean winnable(UUID id) {
        return main.call(() -> {
            if (!(Bukkit.getEntity(id) instanceof LivingEntity foe) || foe.isDead()) {
                return false;
            }
            Player self = player.get();
            if (self == null) {
                return false;
            }
            boolean shield = self.getInventory().contains(Material.SHIELD)
                || self.getInventory().getItemInOffHand().getType() == Material.SHIELD;
            return Bestiary.wins(Bestiary.strength(self), List.of(Bestiary.Foe.of(foe)), shield);
        });
    }

    /** Health, food, and kit, in one line for the model. Main thread. */
    private static String reading(Player self) {
        if (self == null) {
            return "";
        }
        int edible = 0;
        for (ItemStack stack : self.getInventory().getStorageContents()) {
            if (stack != null && Food.edible(stack.getType())) {
                edible += stack.getAmount();
            }
        }
        boolean shield = self.getInventory().contains(Material.SHIELD)
            || self.getInventory().getItemInOffHand().getType() == Material.SHIELD;
        AttributeInstance maxHealth = self.getAttribute(Attribute.MAX_HEALTH);
        double max = maxHealth == null ? 20 : maxHealth.getValue();
        return "health " + (int) Math.ceil(self.getHealth()) + "/" + (int) Math.ceil(max)
            + ", food " + self.getFoodLevel()
            + ", holding " + self.getInventory().getItemInMainHand().getType().name().toLowerCase(Locale.ROOT)
            + (shield ? ", shield" : ", no shield")
            + ", edible " + edible;
    }

    private static String describe(Knowledge.Find find) {
        String ways = find.ways().isEmpty() ? "no moves listed"
            : String.join(", ", find.ways().stream().map(Knowledge.Way::key).toList());
        String height = find.best() == null ? "" : ", best at y=" + find.best();
        String biomes = find.biomes().isEmpty() ? "" : ", biomes " + String.join(", ", find.biomes());
        return ways + height + biomes;
    }
}
