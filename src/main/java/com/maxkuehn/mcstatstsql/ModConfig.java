package com.maxkuehn.mcstatstsql;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mod configuration, loaded from {@code config/mcstats-to-sql.json}.
 *
 * <p>The file is created with defaults on first launch; the server
 * operator edits it and restarts.</p>
 */
public final class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** "sqlite" or "mysql" (covers MariaDB). */
    public String database_type = "sqlite";

    /** Local SQLite settings (used when database_type = "sqlite"). */
    public Sqlite sqlite = new Sqlite();

    /** Remote MySQL/MariaDB settings (used when database_type = "mysql"). */
    public Mysql mysql = new Mysql();

    /** Seconds between harvest/flush cycles. */
    public int flush_interval_seconds = 60;

    /** Record PvP kills (killer <-> victim pairs) as events. */
    public boolean track_pvp = true;

    /** Record every player death with cause as events. */
    public boolean track_deaths = true;

    /** Record join/leave sessions. */
    public boolean track_sessions = true;

    public static final class Sqlite {
        /** Path relative to the server working directory. */
        public String file = "mcstats-to-sql.sqlite";
    }

    public static final class Mysql {
        public String host = "127.0.0.1";
        public int port = 3306;
        public String database = "mcstats";
        public String username = "";
        public String password = "";
        /** Enable TLS for connections crossing untrusted networks. */
        public boolean ssl = false;
    }

    private ModConfig() {
    }

    /**
     * Loads the config, writing the default file first if absent.
     */
    public static ModConfig load() throws IOException {
        Path dir = FabricLoader.getInstance().getConfigDir();
        Path file = dir.resolve("mcstats-to-sql.json");
        ModConfig cfg;
        if (Files.exists(file)) {
            cfg = GSON.fromJson(Files.readString(file), ModConfig.class);
            if (cfg == null) {
                throw new IOException("config file parsed to null: " + file);
            }
        } else {
            cfg = new ModConfig();
        }
        // Always rewrite so new fields become visible after upgrades.
        Files.writeString(file, GSON.toJson(cfg));
        cfg.validate();
        return cfg;
    }

    private void validate() {
        if (database_type == null
                || !(database_type.equalsIgnoreCase("sqlite")
                     || database_type.equalsIgnoreCase("mysql"))) {
            throw new IllegalArgumentException(
                    "config: database_type must be 'sqlite' or 'mysql'");
        }
        if (flush_interval_seconds < 5) {
            flush_interval_seconds = 5;
        }
        if (database_type.equalsIgnoreCase("mysql")) {
            if (mysql.host == null || mysql.host.isBlank()
                    || mysql.username == null || mysql.username.isBlank()
                    || mysql.database == null || mysql.database.isBlank()) {
                throw new IllegalArgumentException(
                        "config: mysql host/username/database are required");
            }
        }
    }
}
