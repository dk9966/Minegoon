package dev.minegoon;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The plugin's SQLite file: tasks, jobs, zones, placed blocks, and an event log. */
public final class Database implements AutoCloseable {

    private static final String SCHEMA = """
        CREATE TABLE IF NOT EXISTS tasks (
            id INTEGER PRIMARY KEY,
            owner TEXT,
            owner_name TEXT,
            goal TEXT NOT NULL,
            count INTEGER NOT NULL,
            status TEXT NOT NULL,
            reason TEXT,
            world TEXT NOT NULL,
            home_x REAL NOT NULL,
            home_y REAL NOT NULL,
            home_z REAL NOT NULL,
            avoid TEXT NOT NULL DEFAULT '',
            deaths INTEGER NOT NULL DEFAULT 0,
            model_calls INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            finished_at INTEGER
        );
        CREATE TABLE IF NOT EXISTS jobs (
            id INTEGER PRIMARY KEY,
            task_id INTEGER NOT NULL REFERENCES tasks(id),
            position INTEGER NOT NULL,
            kind TEXT NOT NULL,
            spec TEXT NOT NULL,
            status TEXT NOT NULL,
            attempts INTEGER NOT NULL DEFAULT 0,
            baseline INTEGER,
            last_error TEXT
        );
        CREATE TABLE IF NOT EXISTS zones (
            id INTEGER PRIMARY KEY,
            kind TEXT NOT NULL,
            world TEXT NOT NULL,
            x INTEGER NOT NULL,
            z INTEGER NOT NULL,
            radius INTEGER NOT NULL,
            task_id INTEGER
        );
        CREATE TABLE IF NOT EXISTS placed_blocks (
            world TEXT NOT NULL,
            x INTEGER NOT NULL,
            y INTEGER NOT NULL,
            z INTEGER NOT NULL,
            PRIMARY KEY (world, x, y, z)
        );
        CREATE TABLE IF NOT EXISTS events (
            id INTEGER PRIMARY KEY,
            task_id INTEGER,
            kind TEXT NOT NULL,
            detail TEXT NOT NULL,
            at INTEGER NOT NULL
        );
        """;

    /** A column added after its table was first made. SQLite has no ADD COLUMN IF NOT EXISTS. */
    private record Added(String table, String column, String type) {
    }

    private static final List<Added> ADDED = List.of(
        new Added("tasks", "search", "TEXT"));

    private final Connection connection;

    public Database(Path file) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement statement = connection.createStatement()) {
            // WAL plus memory-mapped IO corrupts this file on a Docker Desktop
            // bind mount. A rollback journal stays on one file and survives it.
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA mmap_size=0");
            statement.execute("PRAGMA foreign_keys=ON");
            for (String table : SCHEMA.split(";")) {
                if (!table.isBlank()) {
                    statement.execute(table);
                }
            }
            for (Added added : ADDED) {
                if (!columns(statement, added.table()).contains(added.column())) {
                    statement.execute("ALTER TABLE " + added.table() + " ADD COLUMN " + added.column() + " " + added.type());
                }
            }
        }
    }

    private static Set<String> columns(Statement statement, String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (ResultSet columns = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (columns.next()) {
                names.add(columns.getString("name"));
            }
        }
        return names;
    }

    @FunctionalInterface
    public interface Row<T> {
        T read(ResultSet row) throws SQLException;
    }

    public synchronized long insert(String sql, Object... args) {
        try (PreparedStatement statement = prepare(sql, args)) {
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    /** Several writes that must land together, or not at all. */
    public synchronized void transaction(Runnable work) {
        try {
            connection.setAutoCommit(false);
            work.run();
            connection.commit();
        } catch (RuntimeException e) {
            rollback();
            throw e;
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException("transaction", e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                throw new IllegalStateException("transaction", e);
            }
        }
    }

    public synchronized int update(String sql, Object... args) {
        try (PreparedStatement statement = prepare(sql, args)) {
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    public synchronized <T> List<T> query(String sql, Row<T> row, Object... args) {
        try (PreparedStatement statement = prepare(sql, args); ResultSet results = statement.executeQuery()) {
            List<T> rows = new ArrayList<>();
            while (results.next()) {
                rows.add(row.read(results));
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    public <T> Optional<T> first(String sql, Row<T> row, Object... args) {
        return query(sql, row, args).stream().findFirst();
    }

    public void log(Long taskId, String kind, String detail) {
        insert("INSERT INTO events (task_id, kind, detail, at) VALUES (?, ?, ?, ?)",
            taskId, kind, detail, System.currentTimeMillis());
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original failure is the one worth reporting.
        }
    }

    private PreparedStatement prepare(String sql, Object... args) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
        return statement;
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
