package com.maxkuehn.mcstatstsql.db;

import com.maxkuehn.mcstatstsql.ModConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Shared schema and statement plumbing for both SQL backends.
 *
 * <p>Schema (identical on SQLite and MySQL so consumers can treat both
 * alike):</p>
 * <ul>
 *   <li>{@code players} — uuid, current name, first/last seen</li>
 *   <li>{@code stats} — one row per (player, stat, value); full mirror of
 *       the vanilla {@code stats/<uuid>.json} file, replaced wholesale on
 *       every harvest</li>
 *   <li>{@code advancements} — one row per (player, advancement namespace:id)</li>
 *   <li>{@code events} — append-only stream: pvp_kill, death, join, leave</li>
 * </ul>
 */
public abstract class AbstractSqlStore implements AutoCloseable {

    protected Connection conn;

    /** DDL executed with the backend-appropriate upsert dialect. */
    protected abstract String upsertPrefix();

    /** SQLite uses "?" params; MySQL/MariaDB uses the same '?' positions. */

    public void init() {
        try {
            Statement st = conn.createStatement();
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS players (
                      uuid CHAR(36) PRIMARY KEY,
                      name VARCHAR(32) NOT NULL,
                      first_seen BIGINT NOT NULL,
                      last_seen BIGINT NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS stats (
                      player_uuid CHAR(36) NOT NULL,
                      stat VARCHAR(128) NOT NULL,
                      value BIGINT NOT NULL,
                      PRIMARY KEY (player_uuid, stat)
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS advancements (
                      player_uuid CHAR(36) NOT NULL,
                      advancement VARCHAR(256) NOT NULL,
                      PRIMARY KEY (player_uuid, advancement)
                    )""");
            st.executeUpdate(eventsDdl());
            st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_events_ts ON events (ts)");
            st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_events_kind ON events (kind)");
            st.close();
        } catch (SQLException e) {
            throw new IllegalStateException("schema init failed", e);
        }
    }

    /**
     * The events table uses an auto-increment primary key; the spelling
     * differs between SQLite and MySQL/MariaDB, and SQLite has no ENUM.
     */
    private String eventsDdl() {
        String idPk = isMysql()
                ? "id INTEGER PRIMARY KEY AUTO_INCREMENT"
                : "id INTEGER PRIMARY KEY AUTOINCREMENT";
        String kind = isMysql()
                ? "kind ENUM('pvp_kill','death','join','leave') NOT NULL"
                : "kind VARCHAR(16) NOT NULL CHECK (kind IN "
                  + "('pvp_kill','death','join','leave'))";
        return "CREATE TABLE IF NOT EXISTS events ("
                + idPk + ", "
                + "ts BIGINT NOT NULL, "
                + kind + ", "
                + "player_uuid CHAR(36) NOT NULL, "
                + "opponent_uuid CHAR(36) NULL, "
                + "detail VARCHAR(256) NULL)";
    }

    /** Whether this store talks MySQL/MariaDB dialect. */
    protected abstract boolean isMysql();

    /* -------------------- players -------------------- */

    public void upsertPlayer(String uuid, String name, long epochSec) {
        try {
            PreparedStatement ps = conn.prepareStatement(upsertPrefix()
                    + " players(uuid,name,first_seen,last_seen) VALUES(?,?,?,?) "
                    + conflictClause("players"));
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setLong(3, epochSec);
            ps.setLong(4, epochSec);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            throw new IllegalStateException("player upsert failed", e);
        }
    }

    /* -------------------- stats (bulk replace) -------------------- */

    /**
     * Replaces the full stat set of one player inside a transaction.
     *
     * @param stats flattened vanilla stat map: "custom.minecraft.jump" ->
     *              value, "mined.minecraft.stone" -> value, ...
     */
    public void replaceStats(String uuid, java.util.Map<String, Long> stats) {
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement del = conn.prepareStatement(
                         "DELETE FROM stats WHERE player_uuid=?")) {
                del.setString(1, uuid);
                del.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement(
                         "INSERT INTO stats(player_uuid,stat,value) VALUES(?,?,?)")) {
                for (var e : stats.entrySet()) {
                    ins.setString(1, uuid);
                    ins.setString(2, e.getKey());
                    ins.setLong(3, e.getValue());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
            }
            throw new IllegalStateException("stats replace failed", e);
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    /* -------------------- advancements (bulk replace) -------------------- */

    public void replaceAdvancements(String uuid, java.util.Set<String> ids) {
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement del = conn.prepareStatement(
                         "DELETE FROM advancements WHERE player_uuid=?")) {
                del.setString(1, uuid);
                del.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement(
                         "INSERT INTO advancements(player_uuid,advancement) VALUES(?,?)")) {
                for (String id : ids) {
                    ins.setString(1, uuid);
                    ins.setString(2, id);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
            }
            throw new IllegalStateException("advancements replace failed", e);
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    /* -------------------- events (append-only) -------------------- */

    public void insertEvent(long tsSec, String kind, String playerUuid,
                            String opponentUuid, String detail) {
        try {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO events(ts,kind,player_uuid,opponent_uuid,detail) "
                    + "VALUES(?,?,?,?,?)");
            ps.setLong(1, tsSec);
            ps.setString(2, kind);
            ps.setString(3, playerUuid);
            ps.setString(4, opponentUuid);
            ps.setString(5, detail);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            throw new IllegalStateException("event insert failed", e);
        }
    }

    /** ON DUPLICATE KEY fragment for the active backend. */
    protected abstract String conflictClause(String table);

    @Override
    public void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException ignored) {
        }
    }

    /** Factory resolving the configured backend. */
    public static AbstractSqlStore open(ModConfig cfg) {
        if (cfg.database_type.equalsIgnoreCase("sqlite")) {
            return SqliteStore.open(cfg);
        }
        return MySqlStore.open(cfg);
    }
}
