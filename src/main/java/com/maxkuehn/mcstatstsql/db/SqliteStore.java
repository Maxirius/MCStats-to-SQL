package com.maxkuehn.mcstatstsql.db;

import com.maxkuehn.mcstatstsql.ModConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Local SQLite backend — zero-setup single file next to the world.
 */
public final class SqliteStore extends AbstractSqlStore {

    private SqliteStore() {
    }

    public static SqliteStore open(ModConfig cfg) {
        try {
            Class.forName("org.sqlite.JDBC");
            Path file = Path.of(cfg.sqlite.file).toAbsolutePath();
            Files.createDirectories(file.getParent());
            Connection c = DriverManager.getConnection(
                    "jdbc:sqlite:" + file);
            try (StatementProbe p = new StatementProbe(c)) {
                p.execute("PRAGMA journal_mode=WAL");
                p.execute("PRAGMA synchronous=NORMAL");
            }
            SqliteStore s = new SqliteStore();
            s.conn = c;
            return s;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "cannot open sqlite store: " + cfg.sqlite.file, e);
        }
    }

    @Override
    protected String upsertPrefix() {
        return "INSERT";
    }

    @Override
    protected boolean isMysql() {
        return false;
    }

    @Override
    protected String conflictClause(String table) {
        return "ON CONFLICT(uuid) DO UPDATE SET "
                + "name=excluded.name, last_seen=excluded.last_seen";
    }

    private record StatementProbe(Connection c) implements AutoCloseable {
        void execute(String sql) throws SQLException {
            try (var st = c.createStatement()) {
                st.execute(sql);
            }
        }

        @Override
        public void close() throws SQLException {
            // connection owned by the store
        }
    }
}
