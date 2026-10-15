package dev.minegoon.jobs;

import com.google.gson.Gson;
import dev.minegoon.Database;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Location;
import org.bukkit.Material;

/** Tasks and their jobs, stored in SQLite so work survives restarts. */
public final class JobBoard {

    private static final String TASK = """
        SELECT id, owner, owner_name, goal, count, status, reason, world, home_x, home_y, home_z,
               avoid, deaths, model_calls, created_at
        FROM tasks
        """;
    private static final String JOB = "SELECT id, task_id, position, kind, spec, status, attempts, baseline, last_error FROM jobs ";
    private static final String UNFINISHED = "status IN ('OPEN', 'ACTIVE')";

    private final Database db;
    private final Gson gson = new Gson();

    public JobBoard(Database db) {
        this.db = db;
    }

    public long createTask(UUID owner, String ownerName, Material goal, int count, Location home) {
        return db.insert("""
            INSERT INTO tasks (owner, owner_name, goal, count, status, world, home_x, home_y, home_z, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, owner == null ? null : owner.toString(), ownerName, goal.name(), count, Task.Status.ACTIVE.name(),
            home.getWorld().getName(), home.getX(), home.getY(), home.getZ(), System.currentTimeMillis());
    }

    public Optional<Task> task(long id) {
        return db.first(TASK + "WHERE id = ?", this::task, id);
    }

    /** The task being worked on or paused, if any. */
    public Optional<Task> current() {
        return db.first(TASK + "WHERE status IN ('ACTIVE', 'PAUSED') ORDER BY id DESC", this::task);
    }

    public Optional<Task> latest() {
        return db.first(TASK + "ORDER BY id DESC", this::task);
    }

    public List<Task> recent(int limit) {
        return db.query(TASK + "ORDER BY id DESC LIMIT ?", this::task, limit);
    }

    public void setStatus(long taskId, Task.Status status, String reason) {
        db.update("UPDATE tasks SET status = ?, reason = ?, finished_at = ? WHERE id = ?",
            status.name(), reason, status.finished() ? System.currentTimeMillis() : null, taskId);
    }

    public void setAvoid(long taskId, Set<String> avoid) {
        db.update("UPDATE tasks SET avoid = ? WHERE id = ?", String.join(",", avoid), taskId);
    }

    public void countDeath(long taskId) {
        db.update("UPDATE tasks SET deaths = deaths + 1 WHERE id = ?", taskId);
    }

    public void countModelCall(long taskId) {
        db.update("UPDATE tasks SET model_calls = model_calls + 1 WHERE id = ?", taskId);
    }

    public List<Job> jobs(long taskId) {
        return db.query(JOB + "WHERE task_id = ? ORDER BY position", this::job, taskId);
    }

    public Optional<Job> next(long taskId) {
        return db.first(JOB + "WHERE task_id = ? AND " + UNFINISHED + " ORDER BY position", this::job, taskId);
    }

    /** Swaps every unfinished job for a fresh plan, placed after the finished ones. */
    public synchronized void replaceUnfinished(long taskId, List<Step> steps) {
        db.transaction(() -> {
            db.update("DELETE FROM jobs WHERE task_id = ? AND " + UNFINISHED, taskId);
            int[] position = {db.first("SELECT COALESCE(MAX(position), 0) AS last FROM jobs WHERE task_id = ?",
                row -> row.getInt("last"), taskId).orElse(0)};
            for (Step step : steps) {
                insert(taskId, ++position[0], step);
            }
        });
    }

    /** Inserts steps so the first one runs next, ahead of whatever was unfinished. */
    public synchronized void addAllFront(long taskId, List<Step> steps) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            addFront(taskId, steps.get(i));
        }
    }

    public synchronized void addFront(long taskId, Step step) {
        int position = db.first("SELECT COALESCE(MIN(position), 1) AS first FROM jobs WHERE task_id = ? AND " + UNFINISHED,
            row -> row.getInt("first"), taskId).orElse(1);
        insert(taskId, position - 1, step);
    }

    /** Starts the clock on a job: remembers how much of its item there was before it began. */
    public Job activate(Job job, int baseline) {
        db.update("UPDATE jobs SET status = 'ACTIVE', baseline = ? WHERE id = ?", baseline, job.id());
        return new Job(job.id(), job.taskId(), job.position(), job.step(), Job.Status.ACTIVE, job.attempts(),
            baseline, job.lastError());
    }

    public void complete(Job job) {
        db.update("UPDATE jobs SET status = 'DONE' WHERE id = ?", job.id());
    }

    /** Returns how many times this job has now failed. */
    public int fail(Job job, String error) {
        db.update("UPDATE jobs SET attempts = attempts + 1, last_error = ? WHERE id = ?", error, job.id());
        return job.attempts() + 1;
    }

    public void block(Job job, String error) {
        db.update("UPDATE jobs SET status = 'BLOCKED', last_error = ? WHERE id = ?", error, job.id());
    }

    /** After a replan, the same work keeps its failure count, so a stuck job still ends up blocked. */
    public void carryAttempts(long taskId, Step failed, int attempts, String error) {
        db.query(JOB + "WHERE task_id = ? AND " + UNFINISHED + " ORDER BY position", this::job, taskId).stream()
            .filter(job -> job.sameWorkAs(failed))
            .findFirst()
            .ifPresent(job -> db.update("UPDATE jobs SET attempts = ?, last_error = ? WHERE id = ?",
                attempts, error, job.id()));
    }

    private void insert(long taskId, int position, Step step) {
        db.insert("INSERT INTO jobs (task_id, position, kind, spec, status) VALUES (?, ?, ?, ?, 'OPEN')",
            taskId, position, step.getClass().getSimpleName(), gson.toJson(step));
    }

    private Task task(ResultSet row) throws SQLException {
        String owner = row.getString("owner");
        String avoid = row.getString("avoid");
        return new Task(row.getLong("id"), owner == null ? null : UUID.fromString(owner), row.getString("owner_name"),
            Material.valueOf(row.getString("goal")), row.getInt("count"), Task.Status.valueOf(row.getString("status")),
            row.getString("reason"), row.getString("world"),
            row.getDouble("home_x"), row.getDouble("home_y"), row.getDouble("home_z"),
            avoid.isEmpty() ? Set.of() : Arrays.stream(avoid.split(",")).collect(Collectors.toUnmodifiableSet()),
            row.getInt("deaths"), row.getInt("model_calls"), row.getLong("created_at"));
    }

    private Job job(ResultSet row) throws SQLException {
        Step step = gson.fromJson(row.getString("spec"), Step.KINDS.get(row.getString("kind")));
        Integer baseline = row.getObject("baseline") == null ? null : row.getInt("baseline");
        return new Job(row.getLong("id"), row.getLong("task_id"), row.getInt("position"), step,
            Job.Status.valueOf(row.getString("status")), row.getInt("attempts"), baseline, row.getString("last_error"));
    }
}
