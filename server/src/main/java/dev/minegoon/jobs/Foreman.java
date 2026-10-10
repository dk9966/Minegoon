package dev.minegoon.jobs;

import dev.minegoon.Database;
import dev.minegoon.ItemSet;
import dev.minegoon.MainThread.Cancelled;
import dev.minegoon.actions.ActionFailed;
import dev.minegoon.actions.Body;
import dev.minegoon.actions.Crafter;
import dev.minegoon.models.Unstuck;
import dev.minegoon.planner.Planner;
import dev.minegoon.safety.Zones;
import dev.minegoon.trips.Gatherer;
import dev.minegoon.trips.Homecoming;
import dev.minegoon.trips.Retreat;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

/**
 * Runs the helper's work, one thing at a time, on a single worker thread.
 * Tasks go job by job off the board; every job is checked by the inspector.
 * A job that fails twice gets one model call to pick a way out, and a job
 * that fails three times is blocked and reported.
 */
public final class Foreman {

    /** The trips and actions the foreman hands work to. */
    public record Crew(Gatherer gatherer, Crafter crafter, Homecoming homecoming, Retreat retreat) {
    }

    private static final int UNSTUCK_AFTER = 2;
    private static final int BLOCK_AFTER = 3;
    private static final int MAX_DEATHS = 3;
    private static final long RECOVER_WINDOW_MS = 4 * 60_000;
    private static final int RESPAWN_WAIT_TICKS = 100;

    private final Body body;
    private final JobBoard board;
    private final Inspector inspector;
    private final Planner planner;
    private final Crew crew;
    private final Unstuck unstuck;
    private final Zones zones;
    private final Results results;
    private final Database db;
    private final Logger log;
    private Thread worker;

    public Foreman(Body body, JobBoard board, Inspector inspector, Planner planner, Crew crew, Unstuck unstuck,
                   Zones zones, Results results, Database db, Logger log) {
        this.body = body;
        this.board = board;
        this.inspector = inspector;
        this.planner = planner;
        this.crew = crew;
        this.unstuck = unstuck;
        this.zones = zones;
        this.results = results;
        this.db = db;
        this.log = log;
    }

    public synchronized void get(UUID owner, String ownerName, Location home, Material goal, int count) {
        board.current().ifPresent(old -> finish(old.id(), Task.Status.CANCELLED, "replaced by a new request"));
        long id = board.createTask(owner, ownerName, goal, count, home);
        launch("task-" + id, () -> {
            say("On it: " + count + " " + ItemSet.key(goal) + ".");
            if (replan(id, Set.of())) {
                work(id);
            }
        });
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
            say("Paused. Say !resume when you want me to carry on.");
        } else {
            say("I'm not working on anything.");
        }
    }

    public synchronized void resume() {
        board.current().filter(task -> task.status() == Task.Status.PAUSED).ifPresentOrElse(task -> {
            board.setStatus(task.id(), Task.Status.ACTIVE, null);
            say("Carrying on with " + task.describe() + ".");
            launch("task-" + task.id(), () -> work(task.id()));
        }, () -> say("Nothing is paused."));
    }

    /** Picks up an active task after a server restart. */
    public synchronized void resumeAfterRestart() {
        board.current().filter(task -> task.status() == Task.Status.ACTIVE)
            .ifPresent(task -> launch("task-" + task.id(), () -> {
                awaitStanding();
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
        if (task.deaths() + 1 >= MAX_DEATHS) {
            finish(task.id(), Task.Status.BLOCKED, "died " + MAX_DEATHS + " times");
            say("I died " + MAX_DEATHS + " times on this one, so I stopped. Something out there is too dangerous.");
            return;
        }
        board.addFront(task.id(), recover);
        launch("task-" + task.id(), () -> {
            awaitStanding();
            say("I died. Going back for my things first.");
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

    private void work(long taskId) {
        while (true) {
            Task task = board.task(taskId).orElseThrow();
            if (task.status() != Task.Status.ACTIVE) {
                return;
            }
            Optional<Job> next = board.next(taskId);
            if (next.isEmpty()) {
                finish(taskId, Task.Status.DONE, null);
                return;
            }
            Job job = next.get();
            if (job.baseline() == null) {
                job = board.activate(job, inspector.baseline(job.step()));
            } else if (inspector.done(job)) {
                completed(task, job);
                continue;
            }
            boolean keepGoing;
            try {
                run(task, job);
                if (inspector.done(job)) {
                    completed(task, job);
                    continue;
                }
                keepGoing = failed(task, job, "the work ran but the inventory doesn't show it");
            } catch (Retreat.Needed retreat) {
                comeBack(task, retreat.getMessage());
                return;
            } catch (ActionFailed failure) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new Cancelled();
                }
                keepGoing = failed(task, job, failure.getMessage());
            }
            if (!keepGoing) {
                return;
            }
        }
    }

    private void run(Task task, Job job) {
        int start = job.baseline();
        switch (job.step()) {
            case Step.Gather gather -> crew.gatherer().gather(gather, start + gather.count());
            case Step.Hunt hunt -> crew.gatherer().hunt(hunt, start + hunt.count());
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

    /** Records a failure and decides what happens next. Returns false when the task has stopped. */
    private boolean failed(Task task, Job job, String problem) {
        int attempts = board.fail(job, problem);
        db.log(task.id(), "failed", job.step().describe() + ": " + problem);
        if (attempts >= BLOCK_AFTER) {
            block(task, job, problem);
            return false;
        }
        if (attempts < UNSTUCK_AFTER) {
            return true;
        }
        Unstuck.Decision decision = unstuck.decide(briefing(task, job, problem));
        if (decision.askedModel()) {
            board.countModelCall(task.id());
        }
        db.log(task.id(), "unstuck", decision.choice() + ": " + decision.reason());
        return switch (decision.choice()) {
            case RETRY -> true;
            case EXPLORE -> {
                explore();
                yield true;
            }
            case REPLAN -> replanKeeping(task, job, attempts, problem, Set.of());
            case OTHER_WAY -> replanKeeping(task, job, attempts, problem, Planner.waysBehind(job.step()));
            case GIVE_UP -> {
                block(task, job, problem);
                yield false;
            }
        };
    }

    private void explore() {
        try {
            crew.gatherer().explore(crew.retreat());
        } catch (ActionFailed | Retreat.Needed ignored) {
            // The job itself will run into the same problem and report it.
        }
    }

    private boolean replanKeeping(Task task, Job job, int attempts, String problem, Set<String> avoidMore) {
        Set<String> avoid = new HashSet<>(task.avoid());
        avoid.addAll(avoidMore);
        if (!replan(task.id(), avoid)) {
            return false;
        }
        board.carryAttempts(task.id(), job.step(), attempts, problem);
        return true;
    }

    /** Plans the task again from what the helper is holding now. Returns false if no plan exists. */
    private boolean replan(long taskId, Set<String> avoid) {
        Task task = board.task(taskId).orElseThrow();
        Map<Material, Integer> inventory = body.main().call(this::inventory);
        try {
            List<Step> steps = planner.plan(task.goalItems(), task.count(), inventory, avoid);
            board.setAvoid(taskId, avoid);
            board.replaceUnfinished(taskId, steps);
            db.log(taskId, "plan", steps.stream().map(Step::describe).collect(Collectors.joining("; ")));
            return true;
        } catch (Planner.Unplannable e) {
            finish(taskId, Task.Status.BLOCKED, e.getMessage());
            say("I can't get " + task.describe() + ": " + e.getMessage() + ".");
            return false;
        }
    }

    private void block(Task task, Job job, String problem) {
        board.block(job, problem);
        finish(task.id(), Task.Status.BLOCKED, job.step().describe() + ": " + problem);
        say("I'm stuck trying to " + job.step().describe() + ": " + problem + ". I stopped that task.");
    }

    private void comeBack(Task task, String reason) {
        board.setStatus(task.id(), Task.Status.PAUSED, reason);
        say("Coming back, " + reason + ".");
        crew.homecoming().goBack(task.owner(), task.home());
        int have = inspector.have(task.goalItems());
        say("I'm back with " + have + " of " + task.describe() + ". Say !resume to send me out again, or !give.");
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

    private String briefing(Task task, Job job, String problem) {
        String plan = board.jobs(task.id()).stream()
            .map(j -> j.status().name().toLowerCase(Locale.ROOT) + ": " + j.step().describe())
            .collect(Collectors.joining("\n"));
        String held = body.main().call(this::inventory).entrySet().stream()
            .map(entry -> entry.getValue() + " " + ItemSet.key(entry.getKey()))
            .collect(Collectors.joining(", "));
        return """
            Task: get %s.
            Failing step (failed twice): %s
            Last error: %s
            Plan:
            %s
            Inventory: %s
            %s
            """.formatted(task.describe(), job.step().describe(), problem, plan, held.isEmpty() ? "empty" : held, vitals());
    }

    private String progress(Job job) {
        if (job.baseline() == null || job.step().counted() == null) {
            return job.step().describe();
        }
        int amount = switch (job.step()) {
            case Step.Gather gather -> gather.count();
            case Step.Hunt hunt -> hunt.count();
            case Step.Craft craft -> craft.times() * craft.yield();
            case Step.Smelt smelt -> smelt.count();
            case Step.Deliver deliver -> deliver.count();
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
