package net.clanimg.plotsGUI.cache;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.clanimg.plotsGUI.config.Settings;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared MySQL cache of plot data. Only the PlotsGUI instance running on plotsquared_server-name
 * writes to it (via {@link #fullReplace}, {@link #refresh} and {@link #replaceOwner}); every other
 * instance only reads it (via {@link #plotsOf}).
 */
public final class PlotCacheStore implements AutoCloseable {

    public record PlotRecord(String area, String plotId, UUID owner, List<String> trusted, List<String> members,
                              long createdAtMillis) {
    }

    private final HikariDataSource dataSource;
    private final String table;

    /** With database.enable: false in config.yml, every operation below becomes a harmless no-op. */
    public PlotCacheStore(Settings.Database config) {
        this.table = config.tablePrefix() + "plots";
        if (!config.enabled()) {
            this.dataSource = null;
            return;
        }
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:mysql://" + config.host() + ":" + config.port() + "/" + config.name()
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8");
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.poolSize());
        hikari.setConnectionTimeout(config.timeoutMillis());
        hikari.setPoolName("PlotsGUI-MySQL");
        this.dataSource = new HikariDataSource(hikari);
    }

    public void init() throws SQLException {
        if (dataSource == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      owner_uuid CHAR(36) NOT NULL,
                      area VARCHAR(64) NOT NULL,
                      plot_id VARCHAR(32) NOT NULL,
                      plot_number INT NOT NULL,
                      trusted TEXT,
                      members TEXT,
                      created_at BIGINT NOT NULL,
                      updated_at BIGINT NOT NULL,
                      UNIQUE KEY uniq_plot (area, plot_id),
                      KEY idx_owner (owner_uuid)
                    )
                    """.formatted(table));
        }
    }

    public List<CachedPlot> plotsOf(UUID owner) throws SQLException {
        if (dataSource == null) {
            return List.of();
        }
        String sql = "SELECT area, plot_id, plot_number, trusted, members, created_at FROM " + table
                + " WHERE owner_uuid = ? ORDER BY plot_number ASC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner.toString());
            try (ResultSet rs = statement.executeQuery()) {
                List<CachedPlot> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new CachedPlot(
                            owner,
                            rs.getString("area"),
                            rs.getString("plot_id"),
                            rs.getInt("plot_number"),
                            splitCsv(rs.getString("trusted")),
                            splitCsv(rs.getString("members")),
                            rs.getLong("created_at")
                    ));
                }
                return result;
            }
        }
    }

    /** Full rescan: replaces the entire cache with the given plots. */
    public void fullReplace(List<PlotRecord> plots) throws SQLException {
        withTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM " + table);
            }
            insertAll(connection, plots);
        });
    }

    /** Replaces every plot currently on record for one owner (e.g. after a targeted API re-read). */
    public void replaceOwner(UUID owner, List<PlotRecord> plots) throws SQLException {
        withTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE owner_uuid = ?")) {
                delete.setString(1, owner.toString());
                delete.executeUpdate();
            }
            insertAll(connection, plots);
        });
    }

    /** Event-driven refresh of one plot: upserts it if still owned, deletes it otherwise, then renumbers. */
    public void refresh(String area, String plotId, PlotRecord current) throws SQLException {
        withTransaction(connection -> {
            UUID previousOwner = findOwner(connection, area, plotId);
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE area = ? AND plot_id = ?")) {
                delete.setString(1, area);
                delete.setString(2, plotId);
                delete.executeUpdate();
            }
            if (current != null && current.owner() != null) {
                insertAll(connection, List.of(current));
            }
            if (previousOwner != null) {
                renumber(connection, previousOwner);
            }
        });
    }

    private interface TxWork {
        void run(Connection connection) throws SQLException;
    }

    private void withTransaction(TxWork work) throws SQLException {
        if (dataSource == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                work.run(connection);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private UUID findOwner(Connection connection, String area, String plotId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner_uuid FROM " + table + " WHERE area = ? AND plot_id = ?")) {
            statement.setString(1, area);
            statement.setString(2, plotId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? UUID.fromString(rs.getString(1)) : null;
            }
        }
    }

    private void insertAll(Connection connection, List<PlotRecord> plots) throws SQLException {
        String sql = "INSERT INTO " + table
                + " (owner_uuid, area, plot_id, plot_number, trusted, members, created_at, updated_at)"
                + " VALUES (?, ?, ?, 0, ?, ?, ?, ?)";
        long now = Instant.now().toEpochMilli();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (PlotRecord plot : plots) {
                if (plot.owner() == null) {
                    continue;
                }
                statement.setString(1, plot.owner().toString());
                statement.setString(2, plot.area());
                statement.setString(3, plot.plotId());
                statement.setString(4, String.join(",", plot.trusted()));
                statement.setString(5, String.join(",", plot.members()));
                statement.setLong(6, plot.createdAtMillis());
                statement.setLong(7, now);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        for (UUID owner : plots.stream().map(PlotRecord::owner).filter(Objects::nonNull).distinct().toList()) {
            renumber(connection, owner);
        }
    }

    private void renumber(Connection connection, UUID owner) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM " + table + " WHERE owner_uuid = ? ORDER BY created_at ASC")) {
            statement.setString(1, owner.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + table + " SET plot_number = ? WHERE id = ?")) {
            for (int i = 0; i < ids.size(); i++) {
                update.setInt(1, i + 1);
                update.setLong(2, ids.get(i));
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(","));
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
