package dev.minegoon.safety;

import dev.minegoon.Database;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Circles on the map, full height. Protect zones can never be broken into.
 * Allow zones lift the player-made rule for one task.
 */
public final class Zones {

    public enum Kind { PROTECT, ALLOW }

    public record Zone(long id, Kind kind, String world, int x, int z, int radius, Long taskId) {
        boolean contains(String world, int x, int z) {
            long dx = x - this.x;
            long dz = z - this.z;
            return this.world.equals(world) && dx * dx + dz * dz <= (long) radius * radius;
        }
    }

    private final Database db;
    private final List<Zone> zones = new CopyOnWriteArrayList<>();

    public Zones(Database db) {
        this.db = db;
        zones.addAll(db.query("SELECT id, kind, world, x, z, radius, task_id FROM zones", row -> new Zone(
            row.getLong("id"), Kind.valueOf(row.getString("kind")), row.getString("world"),
            row.getInt("x"), row.getInt("z"), row.getInt("radius"),
            row.getObject("task_id") == null ? null : row.getLong("task_id"))));
    }

    public Zone add(Kind kind, String world, int x, int z, int radius, Long taskId) {
        long id = db.insert("INSERT INTO zones (kind, world, x, z, radius, task_id) VALUES (?, ?, ?, ?, ?, ?)",
            kind.name(), world, x, z, radius, taskId);
        Zone zone = new Zone(id, kind, world, x, z, radius, taskId);
        zones.add(zone);
        return zone;
    }

    /** Allow zones only live as long as their task. */
    public void endTask(long taskId) {
        db.update("DELETE FROM zones WHERE task_id = ?", taskId);
        zones.removeIf(zone -> zone.taskId() != null && zone.taskId() == taskId);
    }

    public boolean protects(String world, int x, int z) {
        return zones.stream().anyMatch(zone -> zone.kind() == Kind.PROTECT && zone.contains(world, x, z));
    }

    public boolean allows(String world, int x, int z) {
        return zones.stream().anyMatch(zone -> zone.kind() == Kind.ALLOW && zone.contains(world, x, z));
    }

    public long count(Kind kind) {
        return zones.stream().filter(zone -> zone.kind() == kind).count();
    }
}
