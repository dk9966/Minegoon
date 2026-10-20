package dev.minegoon.jobs;

import com.google.gson.Gson;
import dev.minegoon.Database;
import dev.minegoon.actions.Receipt;
import dev.minegoon.jobs.Mission.Line;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.bukkit.Location;

/**
 * Missions and their logs, stored in SQLite so a mission survives a restart.
 * The log is one JSON line per entry. Each model turn also leaves an agent
 * event, and every mission that ends, however it ends, is handed to {@code ended}.
 */
public final class MissionBoard {

    private static final String MISSION = """
        SELECT id, owner, owner_name, request, status, reason, world, home_x, home_y, home_z, plan, log,
               model_calls, strong_calls, cost, created_at
        FROM missions
        """;

    private final Database db;
    private final Consumer<Mission> ended;
    private final Gson gson = new Gson();

    public MissionBoard(Database db, Consumer<Mission> ended) {
        this.db = db;
        this.ended = ended;
    }

    public long create(UUID owner, String ownerName, Location home, String request) {
        return db.insert("""
            INSERT INTO missions (owner, owner_name, request, status, world, home_x, home_y, home_z, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, owner == null ? null : owner.toString(), ownerName, request, Task.Status.ACTIVE.name(),
            home.getWorld().getName(), home.getX(), home.getY(), home.getZ(), System.currentTimeMillis());
    }

    public Optional<Mission> mission(long id) {
        return db.first(MISSION + "WHERE id = ?", this::mission, id);
    }

    /** The mission being worked on or paused, if any. */
    public Optional<Mission> current() {
        return db.first(MISSION + "WHERE status IN ('ACTIVE', 'PAUSED') ORDER BY id DESC", this::mission);
    }

    public List<Mission> recent(int limit) {
        return db.query(MISSION + "ORDER BY id DESC LIMIT ?", this::mission, limit);
    }

    public void setStatus(long id, Task.Status status, String reason) {
        db.update("UPDATE missions SET status = ?, reason = ?, finished_at = ? WHERE id = ?",
            status.name(), reason, status.finished() ? System.currentTimeMillis() : null, id);
        if (status.finished()) {
            mission(id).ifPresent(ended);
        }
    }

    public void setPlan(long id, Mission.Plan plan) {
        db.update("UPDATE missions SET plan = ? WHERE id = ?", gson.toJson(plan), id);
    }

    /** One model turn: which model, the tool and a short form of its arguments, the receipt, tokens and cost. */
    public void event(long id, String detail) {
        db.insert("INSERT INTO events (mission_id, kind, detail, at) VALUES (?, 'agent', ?, ?)",
            id, detail, System.currentTimeMillis());
    }

    public void counted(long id, boolean strong, double cost) {
        db.update("UPDATE missions SET model_calls = model_calls + 1, strong_calls = strong_calls + ?, "
            + "cost = cost + ? WHERE id = ?", strong ? 1 : 0, cost, id);
    }

    public void append(long id, Line line) {
        edit(id, log -> {
            log.add(line);
            return log;
        });
    }

    /** Ends the call that is still running with its receipt. */
    public void close(long id, Receipt receipt) {
        editPending(id, line -> line.closed(receipt));
    }

    /** The running call is fetching this task, so it can carry on with it after a restart or a death. */
    public void fetching(long id, long taskId) {
        editPending(id, line -> line.fetching(taskId));
    }

    private void editPending(long id, UnaryOperator<Line> change) {
        edit(id, log -> {
            for (int i = log.size() - 1; i >= 0; i--) {
                if (log.get(i).isCall()) {
                    if (log.get(i).receipt() == null) {
                        log.set(i, change.apply(log.get(i)));
                    }
                    break;
                }
            }
            return log;
        });
    }

    private synchronized void edit(long id, UnaryOperator<List<Line>> change) {
        List<Line> log = change.apply(new ArrayList<>(mission(id).orElseThrow().log()));
        db.update("UPDATE missions SET log = ? WHERE id = ?",
            String.join("\n", log.stream().map(gson::toJson).toList()), id);
    }

    private Mission mission(ResultSet row) throws SQLException {
        String owner = row.getString("owner");
        String plan = row.getString("plan");
        String log = row.getString("log");
        return new Mission(row.getLong("id"), owner == null ? null : UUID.fromString(owner),
            row.getString("owner_name"), row.getString("request"), Task.Status.valueOf(row.getString("status")),
            row.getString("reason"), row.getString("world"),
            row.getDouble("home_x"), row.getDouble("home_y"), row.getDouble("home_z"),
            plan == null ? null : gson.fromJson(plan, Mission.Plan.class),
            log.isEmpty() ? List.of() : Arrays.stream(log.split("\n")).map(line -> gson.fromJson(line, Line.class)).toList(),
            row.getInt("model_calls"), row.getInt("strong_calls"), row.getDouble("cost"), row.getLong("created_at"));
    }
}
