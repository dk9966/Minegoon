package dev.minegoon.jobs;

import dev.minegoon.Database;
import dev.minegoon.ItemSet;
import dev.minegoon.MainThread.Cancelled;
import dev.minegoon.actions.ActionFailed;
import dev.minegoon.actions.Body;
import dev.minegoon.actions.Crafter;
import dev.minegoon.actions.Hands;
import dev.minegoon.models.Advisor;
import dev.minegoon.planner.Cookbook;
import dev.minegoon.planner.Planner;
import dev.minegoon.safety.Zones;
import dev.minegoon.trips.Farmer;
import dev.minegoon.trips.Fighter;
import dev.minegoon.trips.Filler;
import dev.minegoon.trips.Gatherer;
import dev.minegoon.trips.Homecoming;
import dev.minegoon.trips.Prospector;
import dev.minegoon.trips.Retreat;
import dev.minegoon.trips.Sleeper;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

/**
 * Runs the helper's work, one thing at a time, on a single worker thread.
 * Tasks go job by job off the board; every job is checked by the inspector.
 *
 * A task does not give up because it is hard. A failed job is retried, then
 * replanned from the inventory, then planned another way when one exists, and
 * the cycle starts again. When a search keeps coming up empty the tutor may ask
 * a model for advice once, and the answer becomes a lesson. A task only stops
 * when a player stops it, or when no plan for it exists at all.
 */
public final class Foreman {

    /** The trips and actions the foreman hands work to. */
    public record Crew(Gatherer gatherer, Crafter crafter, Homecoming homecoming, Retreat retreat, Farmer farmer,
                       Filler filler, Sleeper sleeper, Fighter fighter, Prospector prospector) {
    }

    private static final int SETTLE_AFTER = 4;
    private static final int SETTLE_TICKS = 20;
    private static final long RECOVER_WINDOW_MS = 4 * 60_000;
    private static final int RESPAWN_WAIT_TICKS = 100;

    private final Body body;
    private final JobBoard board;
    private final Inspector inspector;
    private final Planner planner;
    private final Crew crew;
    private final Tutor tutor;
    private final Zones zones;
    private final Results results;
    private final Database db;
    private final Logger log;
    private final Set<Long> supplied = new HashSet<>();
    private final Stall stall = new Stall();
    private Thread worker;
    private boolean feeding;
    private boolean replacingTool;
    private boolean rearming;
    private boolean bedding;
    private boolean sheltered;

    public Foreman(Body body, JobBoard board, Inspector inspector, Planner planner, Crew crew, Tutor tutor,
                   Zones zones, Results results, Database db, Logger log) {
        this.body = body;
        this.board = board;
        this.inspector = inspector;
        this.planner = planner;
        this.crew = crew;
        this.tutor = tutor;
        this.zones = zones;
        this.results = results;
        this.db = db;
        this.log = log;
    }

    public synchronized void get(UUID owner, String ownerName, Location home, Material goal, int count) {
        board.current().ifPresent(old -> finish(old.id(), Task.Status.CANCELLED, "replaced by a new request"));
        resetSupplies();
        tutor.newTask();
        long id = board.createTask(owner, ownerName, goal, count, home);
        launch("task-" + id, () -> {
            say("On it: " + count + " " + ItemSet.key(goal) + ".");
            if (feasible(id) && replan(id, Set.of())) {
                work(id);
            }
        });
    }

    /**
     * Every so often while a task runs: says what it is doing, asks the model
     * for an idea when nothing has moved for a while, and gives up after longer.
     * Not on the worker thread.
     */
    public void watch() {
        Optional<Task> active = board.current().filter(task -> task.status() == Task.Status.ACTIVE);
        if (active.isEmpty()) {
            stall.idle();
            return;
        }
        Task task = active.get();
        int done = (int) board.jobs(task.id()).stream().filter(job -> job.status() == Job.Status.DONE).count();
        int have = inspector.have(task.goalItems());
        switch (stall.check(task.id(), done, have, System.currentTimeMillis())) {
            case REPORT -> say("Still on " + task.describe() + ": "
                + board.next(task.id()).map(this::progress).orElse("finishing up") + ".");
            case ASK -> board.next(task.id()).ifPresent(job -> tutor.consult(crew.prospector().subject(job.step()),
                    "nothing has moved in 10 minutes on " + job.step().describe(), () -> board.countModelCall(task.id()))
                .ifPresent(note -> say("New idea: " + note)));
            case GIVE_UP -> giveUp(task);
            case NOTHING -> {
            }
        }
    }

    private synchronized void giveUp(Task task) {
        launch("give-up", () -> {
            String reason = "no progress in " + Stall.GIVE_UP_MS / 60_000 + " minutes";
            finish(task.id(), Task.Status.BLOCKED, reason);
            say("I've made no progress on " + task.describe() + " in " + Stall.GIVE_UP_MS / 60_000
                + " minutes, so I'm stopping.");
        });
    }

    /**
     * When the planner has no way to the goal, or the knowledge file says nothing
     * about something the plan has to find, asks the model whether the task can
     * be done at all. A no stops it with the reason. A yes that names the blocks
     * an unknown item drops from teaches the planner to mine them. True to go ahead.
     */
    private boolean feasible(long taskId) {
        Task task = board.task(taskId).orElseThrow();
        String subject;
        String problem;
        ItemSet unsourced = null;
        try {
            List<Step> steps = planner.plan(task.goalItems(), task.count(), body.main().call(this::inventory), Set.of());
            List<String> unknown = tutor.unknown(steps.stream().filter(Foreman::inTheWorld)
                .map(step -> crew.prospector().subject(step)).toList());
            if (unknown.isEmpty()) {
                return true;
            }
            subject = unknown.getFirst();
            problem = "the plan needs " + subject + ", and nothing is known about where to find it";
        } catch (Planner.Unplannable none) {
            subject = ItemSet.key(task.goal());
            problem = "no recipe or source is known: " + none.getMessage();
            unsourced = none.unknown().orElse(null);
        }
        Optional<Advisor.Verdict> verdict = tutor.assess(task.describe(), subject, problem,
            () -> board.countModelCall(taskId));
        if (verdict.isEmpty()) {
            return true;
        }
        Advisor.Verdict answer = verdict.get();
        if (!answer.possible()) {
            finish(taskId, Task.Status.BLOCKED, answer.reason());
            say("I can't get " + task.describe() + ": " + answer.reason());
            return false;
        }
        say(answer.reason());
        if (unsourced != null && !answer.blocks().isEmpty()) {
            planner.learn(unsourced, answer.blocks());
        }
        return true;
    }

    public synchronized void stop() {
        halt();
        board.current().ifPresentOrElse(task -> {
            finish(task.id(), Task.Status.CANCELLED, "stopped by a player");
            say("Stopped working on " + task.describe() + ".");
        }, () -> say("I'm not working on anything."));
    }

    public synchronized void pause() {
        if (pauseActive("paused by a player")) {
            say("Paused. Say resume when you want me to carry on.");
        } else {
            say("I'm not working on anything.");
        }
    }

    public synchronized void resume() {
        board.current().filter(task -> task.status() == Task.Status.PAUSED).ifPresentOrElse(task -> {
            board.setStatus(task.id(), Task.Status.ACTIVE, null);
            resetSupplies();
            say("Carrying on with " + task.describe() + ".");
            launch("task-" + task.id(), () -> work(task.id()));
        }, () -> say("Nothing is paused."));
    }

    /** Picks up an active task after a server restart. */
    public synchronized void resumeAfterRestart() {
        board.current().filter(task -> task.status() == Task.Status.ACTIVE)
            .ifPresent(task -> launch("task-" + task.id(), () -> {
                awaitStanding();
                resetSupplies();
                say("Picking up where I left off: " + task.describe() + ".");
                work(task.id());
            }));
    }

    public synchronized void come(UUID player) {
        pauseActive("called over by a player");
        launch("come", () -> {
            crew.homecoming().comeTo(player);
            say("Here.");
        });
    }

    public synchronized void give(UUID player) {
        pauseActive("handing items over");
        Optional<ItemSet> goal = board.latest().map(Task::goalItems);
        launch("give", () -> {
            int handed = goal.map(items -> crew.homecoming().deliver(player, null, items, Integer.MAX_VALUE)).orElse(0);
            say(handed == 0 ? "I have nothing to hand over." : "Here you go: " + handed + " " + goal.get().name() + ".");
        });
    }

    /** Practice only: fights each creature in turn and reports how each fight went. */
    public synchronized void spar(List<UUID> opponents, Consumer<Fighter.Tally> report) {
        pauseActive("sparring");
        launch("spar", () -> {
            for (UUID opponent : opponents) {
                Fighter.Tally tally = crew.fighter().fight(opponent);
                report.accept(tally);
                if (tally.died()) {
                    return;
                }
            }
        });
    }

    /** Practice only: runs one drill in place of the current job. */
    public synchronized void drill(String name, Runnable drill) {
        pauseActive(name);
        launch(name, drill);
    }

    /** Called the moment the helper dies, before it respawns. */
    public synchronized void onDeath(Location spot) {
        halt();
        Step.Recover recover = new Step.Recover(spot.getWorld().getName(), spot.getBlockX(), spot.getBlockY(),
            spot.getBlockZ(), System.currentTimeMillis() + RECOVER_WINDOW_MS);
        Optional<Task> active = board.current().filter(task -> task.status() == Task.Status.ACTIVE);
        if (active.isEmpty()) {
            launch("recover", () -> {
                awaitStanding();
                crew.homecoming().recover(recover, crew.retreat());
            });
            return;
        }
        Task task = active.get();
        board.countDeath(task.id());
        board.addFront(task.id(), recover);
        resetSupplies();
        launch("task-" + task.id(), () -> {
            awaitStanding();
            say("I died. Going back for my things, then I'll carry on.");
            work(task.id());
        });
    }

    public Optional<Task> current() {
        return board.current();
    }

    public List<Task> recent(int limit) {
        return board.recent(limit);
    }

    /** What the helper would do for this request, given what it holds now. */
    public List<Step> preview(Material goal, int count) throws Planner.Unplannable {
        return planner.plan(ItemSet.of(goal), count, body.main().call(this::inventory), Set.of());
    }

    /** Stops the worker without changing any task, so an active task resumes after a restart. */
    public synchronized void shutdown() {
        halt();
    }

    public List<String> status() {
        Optional<Task> current = board.current();
        if (current.isEmpty()) {
            return List.of("Idle. " + vitals());
        }
        Task task = current.get();
        String state = task.status() == Task.Status.PAUSED ? "Paused (" + task.reason() + ")" : "Working";
        String step = board.next(task.id()).map(this::progress).orElse("finishing up");
        return List.of(state + " on " + task.describe() + ": " + step + ".", vitals());
    }

    private void resetSupplies() {
        feeding = false;
        replacingTool(false);
        rearming = false;
        bedding = false;
        sheltered = false;
        supplied.clear();
    }

    /**
     * Standing rules that are just how the game works: eat before you collapse,
     * replace a dying tool, carry what a dangerous trip needs, carry a sword
     * after dark, and sleep when night catches you in the open. Returns true when
     * it queued that work in front.
     */
    private boolean prepare(Task task, Job job) {
        if (job.step() instanceof Step.Meal || job.step() instanceof Step.Shelter) {
            return false;
        }
        if (!crew.retreat().needsMeal()) {
            feeding = false;
        } else if (!feeding && !task.goal().isEdible()) {
            feeding = true;
            say("I'm hungry, so I'll hunt something to eat first.");
            board.addFront(task.id(), new Step.Meal());
            return true;
        }
        if (shelter(task)) {
            return true;
        }
        if (job.step() instanceof Step.Recover) {
            return false;
        }
        Material worn = wornTool(job);
        boolean usesTool = job.step() instanceof Step.Gather || job.step() instanceof Step.Hunt;
        if (usesTool && worn == null) {
            replacingTool(false);
        } else if (worn != null && !replacingTool) {
            replacingTool(true);
            if (prepend(task, ItemSet.of(worn), held(worn) + 1,
                    "My " + ItemSet.key(worn) + " is about to break. I'll make another.")) {
                return true;
            }
            say("My " + ItemSet.key(worn) + " is about to break and I can't make another yet, so I'll use it up.");
        }
        if (supply(task, job)) {
            return true;
        }
        if (!crew.sleeper().night()) {
            rearming = false;
            bedding = false;
            return false;
        }
        if (!hasWeapon() && !rearming && !Tag.ITEMS_SWORDS.isTagged(task.goal())
            && prepend(task, ItemSet.of(Material.WOODEN_SWORD), 1, "It's night, so I'll make a sword first.")) {
            rearming = true;
            return true;
        }
        if (hasWeapon()) {
            rearming = false;
        }
        if (!crew.sleeper().nightOnSurface() || job.step() instanceof Step.Sleep || bedding
            || Tag.ITEMS_BEDS.isTagged(task.goal())) {
            return false;
        }
        bedding = true;
        if (crew.sleeper().hasBed()) {
            say("It's night up here. I'll sleep and pick this up in the morning.");
            board.addFront(task.id(), new Step.Sleep());
            return true;
        }
        List<Step> bed = provision(ItemSet.of(Material.WHITE_BED), 1);
        if (bed == null || bed.stream().anyMatch(Foreman::inTheWorld)) {
            say("It's night, and I can't make a bed from what I have, so I'll keep going.");
            return false;
        }
        bed.add(new Step.Sleep());
        say("It's night up here. I'll make a bed and sleep.");
        board.addAllFront(task.id(), bed);
        return true;
    }

    /**
     * Night in the open with nothing to fight with, as just after a respawn: dig
     * in until morning rather than die again in the dark. Once a night.
     */
    private boolean shelter(Task task) {
        if (!crew.sleeper().nightOnSurface()) {
            sheltered = false;
            return false;
        }
        if (sheltered || hasWeapon()) {
            return false;
        }
        sheltered = true;
        say("It's night and I have nothing to fight with, so I'll dig in until morning.");
        board.addFront(task.id(), new Step.Shelter());
        return true;
    }

    /** Once per job: food, blocks, and gear before a trip the knowledge says is dangerous. */
    private boolean supply(Task task, Job job) {
        if (!inTheWorld(job.step()) || !supplied.add(job.id())) {
            return false;
        }
        String subject = crew.prospector().subject(job.step());
        List<Step> steps = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Tutor.Shortfall shortfall : tutor.missingSupplies(subject, body.main().call(this::inventory))) {
            ItemSet items = ItemSet.of(shortfall.items());
            List<Step> some = provision(items, shortfall.count());
            if (some != null && (!shortfall.optional() || some.stream().noneMatch(Foreman::inTheWorld))) {
                steps.addAll(some);
                names.add(shortfall.count() + " " + items.name());
            }
        }
        if (steps.isEmpty()) {
            return false;
        }
        say("Before I " + job.step().describe() + ", I'll make sure I have " + String.join(", ", names) + ".");
        board.addAllFront(task.id(), steps);
        return true;
    }

    /** The worn tool may be spent on making its own replacement. */
    private void replacingTool(boolean replacing) {
        replacingTool = replacing;
        crew.retreat().spendLastTool(replacing);
    }

    private Material wornTool(Job job) {
        return switch (job.step()) {
            case Step.Gather gather -> crew.gatherer().replacementFor(gather.blocks());
            case Step.Hunt hunt -> crew.retreat().wornTool(stack -> Tag.ITEMS_SWORDS.isTagged(stack.getType()));
            default -> null;
        };
    }

    /** A sword or an axe: the fighter swings either. */
    private boolean hasWeapon() {
        return body.main().call(() -> Stream.of(Tag.ITEMS_SWORDS, Tag.ITEMS_AXES)
            .flatMap(tag -> tag.getValues().stream())
            .anyMatch(weapon -> Hands.count(body.player(), ItemSet.of(weapon)) > 0));
    }

    private int held(Material item) {
        return body.main().call(() -> Hands.count(body.player(), ItemSet.of(item)));
    }

    /** Queues the steps for {@code items}, without the final hand-over. */
    private boolean prepend(Task task, ItemSet items, int count, String because) {
        List<Step> steps = provision(items, count);
        if (steps == null) {
            return false;
        }
        say(because);
        board.addAllFront(task.id(), steps);
        return true;
    }

    private List<Step> provision(ItemSet items, int count) {
        try {
            List<Step> steps = new ArrayList<>(planner.plan(items, count, body.main().call(this::inventory), Set.of()));
            steps.removeIf(step -> step instanceof Step.Deliver);
            return steps.isEmpty() ? null : steps;
        } catch (Planner.Unplannable e) {
            return null;
        }
    }

    private void work(long taskId) {
        while (true) {
            Task task = board.task(taskId).orElseThrow();
            if (task.status() != Task.Status.ACTIVE) {
                return;
            }
            Optional<Job> next = board.next(taskId);
            if (next.isEmpty()) {
                if (board.jobs(taskId).isEmpty()) {
                    finish(taskId, Task.Status.BLOCKED, "the plan was never saved");
                    say("I lost the plan for " + task.describe() + ". Ask me again.");
                } else {
                    finish(taskId, Task.Status.DONE, null);
                }
                return;
            }
            Job job = next.get();
            crew.retreat().keep(planItems(taskId));
            if (job.baseline() != null && inspector.done(job)) {
                completed(task, job);
                continue;
            }
            if (prepare(task, job)) {
                continue;
            }
            if (job.baseline() == null) {
                job = board.activate(job, inspector.baseline(job.step()));
            }
            try {
                run(task, job);
                if (inspector.done(job)) {
                    completed(task, job);
                    continue;
                }
                if (!failed(task, job, "the work ran but the inventory doesn't show it", false)) {
                    return;
                }
            } catch (Retreat.Needed needed) {
                if (!onNeed(task, needed)) {
                    return;
                }
            } catch (ActionFailed failure) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new Cancelled();
                }
                if (!failed(task, job, failure.getMessage(), failure instanceof Prospector.Dry)) {
                    return;
                }
            }
        }
    }

    private void run(Task task, Job job) {
        int start = job.baseline();
        switch (job.step()) {
            case Step.Gather gather -> crew.gatherer().gather(gather, start + gather.count());
            case Step.Hunt hunt -> crew.gatherer().hunt(hunt, start + hunt.count());
            case Step.Grow grow -> crew.farmer().grow(grow, start + grow.count());
            case Step.Fill fill -> crew.filler().fill(fill, start + fill.count());
            case Step.Meal meal -> crew.gatherer().forage();
            case Step.Sleep sleep -> crew.sleeper().sleep();
            case Step.Shelter shelter -> crew.sleeper().shelter(crew.retreat().ignoringHunger());
            case Step.Craft craft -> {
                int left = craft.times() - (inspector.have(craft.output()) - start) / craft.yield();
                if (left > 0) {
                    crew.crafter().craft(recipes(craft), craft.output(), left).orFail();
                }
            }
            case Step.Smelt smelt -> {
                int left = start + smelt.count() - inspector.have(smelt.output());
                if (left > 0) {
                    crew.crafter().smelt(smelt.input(), smelt.output(), left).orFail();
                }
            }
            case Step.Deliver deliver -> crew.homecoming().deliver(task.owner(), task.home(), deliver.item(),
                deliver.count());
            case Step.Recover recover -> crew.homecoming().recover(recover, crew.retreat());
        }
    }

    private void completed(Task task, Job job) {
        board.complete(job);
        db.log(task.id(), "done", job.step().describe());
        if (inTheWorld(job.step())) {
            tutor.worked(crew.prospector().subject(job.step()));
            board.setAvoid(task.id(), Set.of());
        }
        switch (job.step()) {
            case Step.Deliver deliver -> {
                finish(task.id(), Task.Status.DONE, null);
                say("Here you go: " + task.describe() + ".");
            }
            case Step.Recover recover -> replan(task.id(), task.avoid());
            default -> {
            }
        }
    }

    /** The trip needs something else first. Returns false when the task has stopped. */
    private boolean onNeed(Task task, Retreat.Needed needed) {
        db.log(task.id(), "need", needed.getMessage());
        return switch (needed.reason()) {
            case HUNGRY -> {
                say(needed.getMessage() + ", so I'll hunt something to eat.");
                board.addFront(task.id(), new Step.Meal());
                yield true;
            }
            case TOOL -> true;
            case FULL -> {
                comeBack(task, needed.getMessage());
                yield false;
            }
        };
    }

    /**
     * Records a failure and picks the next try: the same job again, a plan from
     * what is held now, or another way to get the item. A search that came up
     * {@code dry} may get advice first; advice is about where to look, so it
     * cannot help any other failure. Things lying where the helper died are left
     * once it cannot get there, and a craft or smelt that failed is planned again
     * at once, since trying it again with the same inventory fails the same way.
     * Returns false only when no plan exists at all.
     */
    private boolean failed(Task task, Job job, String problem, boolean dry) {
        int attempts = board.fail(job, problem);
        db.log(task.id(), "failed", job.step().describe() + ": " + problem);
        if (job.step() instanceof Step.Recover) {
            board.complete(job);
            db.log(task.id(), "retry", "left the dropped items");
            return replan(task.id(), task.avoid());
        }
        String subject = crew.prospector().subject(job.step());
        if (dry) {
            tutor.missed(subject);
            Optional<String> advice = tutor.consult(subject, problem, () -> board.countModelCall(task.id()));
            advice.ifPresent(note -> {
                db.log(task.id(), "lesson", subject + ": " + note);
                say("New idea: " + note);
            });
            if (advice.isPresent()) {
                return true;
            }
        }
        if (attempts >= SETTLE_AFTER) {
            body.main().sleep(SETTLE_TICKS * Math.min(attempts, 10));
        }
        Set<String> otherWay = new HashSet<>(task.avoid());
        otherWay.addAll(Planner.waysBehind(job.step()));
        if (dry && !otherWay.equals(task.avoid())) {
            try {
                List<Step> steps = steps(task.id(), otherWay);
                if (crew.sleeper().night() || steps.stream().noneMatch(Foreman::huntsMonsters)) {
                    adopt(task.id(), otherWay, steps);
                    db.log(task.id(), "retry", "another way: " + problem);
                    say("I'll get that another way.");
                    board.carryAttempts(task.id(), job.step(), attempts, problem);
                    return true;
                }
            } catch (Planner.Unplannable onlyWay) {
                // Keep looking the one way there is.
            }
        }
        if (attempts % 2 == 0 || job.step() instanceof Step.Craft || job.step() instanceof Step.Smelt) {
            db.log(task.id(), "retry", "replan from the inventory");
            if (!replan(task.id(), task.avoid())) {
                return false;
            }
            board.carryAttempts(task.id(), job.step(), attempts, problem);
            return true;
        }
        db.log(task.id(), "retry", "same job");
        return true;
    }

    /**
     * Plans the task again from what the helper is holding now. When leaving
     * some ways out makes it impossible, it plans with every way instead. Returns
     * false only if no plan exists even then.
     */
    private boolean replan(long taskId, Set<String> avoid) {
        try {
            plan(taskId, avoid);
            return true;
        } catch (Planner.Unplannable avoided) {
            try {
                plan(taskId, Set.of());
                return true;
            } catch (Planner.Unplannable none) {
                Task task = board.task(taskId).orElseThrow();
                finish(taskId, Task.Status.BLOCKED, none.getMessage());
                say("I can't get " + task.describe() + ": " + none.getMessage() + ".");
                return false;
            }
        }
    }

    /** Saves a plan that leaves out {@code avoid}. Nothing is saved when there is none. */
    private void plan(long taskId, Set<String> avoid) throws Planner.Unplannable {
        adopt(taskId, avoid, steps(taskId, avoid));
    }

    private List<Step> steps(long taskId, Set<String> avoid) throws Planner.Unplannable {
        Task task = board.task(taskId).orElseThrow();
        return planner.plan(task.goalItems(), task.count(), body.main().call(this::inventory), avoid);
    }

    private void adopt(long taskId, Set<String> avoid, List<Step> steps) {
        board.setAvoid(taskId, avoid);
        board.replaceUnfinished(taskId, steps);
        db.log(taskId, "plan", steps.stream().map(Step::describe).collect(Collectors.joining("; ")));
    }

    private void comeBack(Task task, String reason) {
        board.setStatus(task.id(), Task.Status.PAUSED, reason);
        say("Coming back, " + reason + ".");
        crew.homecoming().goBack(task.owner(), task.home());
        int have = inspector.have(task.goalItems());
        say("I'm back with " + have + " of " + task.describe() + ". Say resume to send me out again, or give.");
    }

    private void finish(long taskId, Task.Status status, String reason) {
        board.setStatus(taskId, status, reason);
        zones.endTask(taskId);
        board.task(taskId).ifPresent(results::record);
    }

    private boolean pauseActive(String reason) {
        Optional<Task> active = board.current().filter(task -> task.status() == Task.Status.ACTIVE);
        active.ifPresent(task -> board.setStatus(task.id(), Task.Status.PAUSED, reason));
        halt();
        return active.isPresent();
    }

    /** Starts new work once the previous worker has wound down. */
    private synchronized void launch(String name, Runnable work) {
        Thread previous = worker;
        if (previous != null) {
            previous.interrupt();
        }
        worker = Thread.ofVirtual().name("minegoon-" + name).start(() -> {
            try {
                if (previous != null) {
                    previous.join();
                }
                work.run();
            } catch (InterruptedException | Cancelled stopped) {
                // Replaced by newer work, paused, or stopped.
            } catch (ActionFailed | Retreat.Needed failure) {
                say("I couldn't do that: " + failure.getMessage() + ".");
            } catch (RuntimeException e) {
                log.log(Level.SEVERE, "Helper work failed", e);
                say("Something went wrong on my side: " + e.getMessage());
            }
        });
    }

    private synchronized void halt() {
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void awaitStanding() {
        for (int tick = 0; tick < RESPAWN_WAIT_TICKS; tick++) {
            boolean standing = body.main().call(() -> body.helper().online() && !body.helper().player().isDead());
            if (standing) {
                return;
            }
            body.main().sleep(1);
        }
        throw new ActionFailed("I didn't respawn");
    }

    private List<Recipe> recipes(Step.Craft craft) {
        return body.main().call(() -> craft.recipes().stream()
            .map(NamespacedKey::fromString)
            .filter(Objects::nonNull)
            .map(Bukkit::getRecipe)
            .filter(Objects::nonNull)
            .toList());
    }

    private Map<Material, Integer> inventory() {
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (ItemStack stack : body.player().getInventory().getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
            }
        }
        return counts;
    }

    /** Everything the unfinished jobs make or use, so none of it is dropped as junk. */
    private Set<Material> planItems(long taskId) {
        Set<Material> items = new HashSet<>();
        for (Job job : board.jobs(taskId)) {
            if (job.status() == Job.Status.DONE) {
                continue;
            }
            if (job.step().counted() != null) {
                items.addAll(job.step().counted().members());
            }
            switch (job.step()) {
                case Step.Gather gather -> items.addAll(gather.blocks());
                case Step.Smelt smelt -> items.addAll(smelt.input().members());
                case Step.Grow grow -> items.add(grow.seed());
                case Step.Fill fill -> items.add(fill.container());
                default -> {
                }
            }
        }
        return items;
    }

    /** By day, monsters are in caves, not on the ground being searched: no easier than what came up dry. */
    private static boolean huntsMonsters(Step step) {
        return step instanceof Step.Hunt hunt && Cookbook.hostile(hunt.mobs());
    }

    /** Work out in the world rather than at a crafting table or furnace. */
    private static boolean inTheWorld(Step step) {
        return step instanceof Step.Gather || step instanceof Step.Hunt || step instanceof Step.Grow
            || step instanceof Step.Fill;
    }

    private String progress(Job job) {
        if (job.baseline() == null || job.step().counted() == null) {
            return job.step().describe();
        }
        int amount = switch (job.step()) {
            case Step.Gather gather -> gather.count();
            case Step.Hunt hunt -> hunt.count();
            case Step.Grow grow -> grow.count();
            case Step.Fill fill -> fill.count();
            case Step.Craft craft -> craft.times() * craft.yield();
            case Step.Smelt smelt -> smelt.count();
            case Step.Deliver deliver -> deliver.count();
            case Step.Meal meal -> 0;
            case Step.Sleep sleep -> 0;
            case Step.Shelter shelter -> 0;
            case Step.Recover recover -> 0;
        };
        int done = Math.abs(inspector.have(job.step().counted()) - job.baseline());
        return job.step().describe() + " (" + Math.min(done, amount) + "/" + amount + ")";
    }

    private String vitals() {
        return body.main().call(() -> {
            Player player = body.helper().player();
            if (player == null) {
                return "Not spawned.";
            }
            Location at = player.getLocation();
            return "Health %.0f/20, food %d/20, at %d %d %d.".formatted(player.getHealth(), player.getFoodLevel(),
                at.getBlockX(), at.getBlockY(), at.getBlockZ());
        });
    }

    private void say(String text) {
        body.helper().say(text);
    }
}
