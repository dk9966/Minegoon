package dev.minegoon;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    private final Connection connection;

    public Database(Path file) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            for (String table : SCHEMA.split(";")) {
                if (!table.isBlank()) {
                    statement.execute(table);
                }
            }
        }
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
