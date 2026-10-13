package com.maxkuehn.mcstatstsql.db;

import com.maxkuehn.mcstatstsql.ModConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Remote MySQL / MariaDB backend.
 *
 * <p>Credentials live in the config file — the README instructs operators
 * to use a dedicated user with INSERT/SELECT/UPDATE/DELETE grants on one
 * database only.</p>
 */
public final class MySqlStore extends AbstractSqlStore {

    private MySqlStore() {
    }

    public static MySqlStore open(ModConfig cfg) {
        ModConfig.Mysql m = cfg.mysql;
        try {
            Class.forName("org.mariadb.jdbc.Driver");
            String url = "jdbc:mariadb://" + m.host + ":" + m.port + "/"
                    + m.database
                    + "?useUnicode=true&characterEncoding=utf8"
                    + (m.ssl ? "&sslMode=VERIFY_CA" : "&sslMode=DISABLED");
            Properties props = new Properties();
            props.setProperty("user", m.username);
            props.setProperty("password", m.password == null ? "" : m.password);
            props.setProperty("autoReconnect", "true");
            props.setProperty("maxReconnects", "5");
            Connection c = DriverManager.getConnection(url, props);
            MySqlStore s = new MySqlStore();
            s.conn = c;
            return s;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "cannot connect to mysql/mariadb " + m.host + ":"
                    + m.port + "/" + m.database, e);
        }
    }

    @Override
    protected String upsertPrefix() {
        return "INSERT";
    }

    @Override
    protected boolean isMysql() {
        return true;
    }

    @Override
    protected String conflictClause(String table) {
        return "ON DUPLICATE KEY UPDATE "
                + "name=VALUES(name), last_seen=VALUES(last_seen)";
    }
}
