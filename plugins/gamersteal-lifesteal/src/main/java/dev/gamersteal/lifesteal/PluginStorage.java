package dev.gamersteal.lifesteal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PluginStorage {
    private final Path databasePath;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "GamerStealLifeSteal-SQLite");
        thread.setDaemon(true);
        return thread;
    });
    private Connection connection;

    PluginStorage(Path databasePath) {
        this.databasePath = databasePath;
    }

    CompletableFuture<Void> initialize() {
        return submit(() -> {
            Path parent = databasePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS players (
                            uuid TEXT PRIMARY KEY,
                            last_name TEXT NOT NULL,
                            hearts INTEGER NOT NULL,
                            kills INTEGER NOT NULL DEFAULT 0,
                            deaths INTEGER NOT NULL DEFAULT 0,
                            eliminated_until INTEGER NOT NULL DEFAULT 0,
                            pending_reset INTEGER NOT NULL DEFAULT 0
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS pair_cooldowns (
                            pair_key TEXT PRIMARY KEY,
                            expires_at INTEGER NOT NULL
                        )
                        """);
            }
            return null;
        });
    }

    CompletableFuture<Snapshot> loadSnapshot() {
        return submit(() -> {
            Map<UUID, PlayerData> players = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT uuid, last_name, hearts, kills, deaths, eliminated_until, pending_reset
                         FROM players
                         """)) {
                while (rows.next()) {
                    UUID uuid = UUID.fromString(rows.getString("uuid"));
                    players.put(uuid, new PlayerData(
                            uuid,
                            rows.getString("last_name"),
                            rows.getInt("hearts"),
                            rows.getInt("kills"),
                            rows.getInt("deaths"),
                            rows.getLong("eliminated_until"),
                            rows.getInt("pending_reset") != 0
                    ));
                }
            }

            Map<String, Long> cooldowns = new HashMap<>();
            long now = System.currentTimeMillis();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pair_key, expires_at FROM pair_cooldowns WHERE expires_at > ?")) {
                statement.setLong(1, now);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        cooldowns.put(rows.getString("pair_key"), rows.getLong("expires_at"));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM pair_cooldowns WHERE expires_at <= ?")) {
                statement.setLong(1, now);
                statement.executeUpdate();
            }
            return new Snapshot(Map.copyOf(players), Map.copyOf(cooldowns));
        });
    }

    CompletableFuture<PlayerData> loadOrCreatePlayer(UUID uuid, String name, int startingHearts) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT OR IGNORE INTO players
                        (uuid, last_name, hearts, kills, deaths, eliminated_until, pending_reset)
                    VALUES (?, ?, ?, 0, 0, 0, 0)
                    """)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, name);
                statement.setInt(3, startingHearts);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE players SET last_name = ? WHERE uuid = ?")) {
                statement.setString(1, name);
                statement.setString(2, uuid.toString());
                statement.executeUpdate();
            }
            return findPlayer(uuid);
        });
    }

    CompletableFuture<Void> savePlayer(PlayerData player) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO players
                        (uuid, last_name, hearts, kills, deaths, eliminated_until, pending_reset)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET
                        last_name = excluded.last_name,
                        hearts = excluded.hearts,
                        kills = excluded.kills,
                        deaths = excluded.deaths,
                        eliminated_until = excluded.eliminated_until,
                        pending_reset = excluded.pending_reset
                    """)) {
                statement.setString(1, player.uuid().toString());
                statement.setString(2, player.lastKnownName());
                statement.setInt(3, player.hearts());
                statement.setInt(4, player.kills());
                statement.setInt(5, player.deaths());
                statement.setLong(6, player.eliminatedUntilMillis());
                statement.setInt(7, player.pendingReset() ? 1 : 0);
                statement.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Void> saveCooldown(String pairKey, long expiresAtMillis) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO pair_cooldowns (pair_key, expires_at)
                    VALUES (?, ?)
                    ON CONFLICT(pair_key) DO UPDATE SET expires_at = excluded.expires_at
                    """)) {
                statement.setString(1, pairKey);
                statement.setLong(2, expiresAtMillis);
                statement.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Void> close() {
        CompletableFuture<Void> closed = submit(() -> {
            if (connection != null) {
                connection.close();
                connection = null;
            }
            return null;
        });
        closed.whenComplete((ignored, error) -> executor.shutdown());
        return closed;
    }

    private PlayerData findPlayer(UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT last_name, hearts, kills, deaths, eliminated_until, pending_reset
                FROM players WHERE uuid = ?
                """)) {
            statement.setString(1, uuid.toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Player row disappeared after insert for UUID " + uuid);
                }
                return new PlayerData(
                        uuid,
                        row.getString("last_name"),
                        row.getInt("hearts"),
                        row.getInt("kills"),
                        row.getInt("deaths"),
                        row.getLong("eliminated_until"),
                        row.getInt("pending_reset") != 0
                );
            }
        }
    }

    private <T> CompletableFuture<T> submit(SqlOperation<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.run();
            } catch (SQLException | IOException | ClassNotFoundException exception) {
                throw new StorageException(exception);
            }
        }, executor);
    }

    record Snapshot(Map<UUID, PlayerData> players, Map<String, Long> cooldowns) {
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run() throws SQLException, IOException, ClassNotFoundException;
    }

    static final class StorageException extends RuntimeException {
        StorageException(Throwable cause) {
            super(cause);
        }
    }
}
