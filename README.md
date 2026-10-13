# MCStats-to-SQL

A server-side Fabric mod for Minecraft that writes **all player statistics**
to a SQL database — either a **remote MySQL/MariaDB** server or a **local
SQLite file**. Built for dashboards, websites and leaderboards.

## What it captures

| Table | Content | Source |
|---|---|---|
| `players` | UUID, current name, first/last seen | harvest + sessions |
| `stats` | Every vanilla statistic per player (custom, mined, crafted, used, broken, picked_up, dropped, killed, killed_by, entities bred, distances, ...) — full history incl. progress made **before** the mod was installed | `world/stats/<uuid>.json` |
| `advancements` | Granted advancements per player | `world/advancements/<uuid>.json` |
| `events` | Append-only stream: `pvp_kill` (**killer → victim** pairs incl. weapon), `death` (cause), `join`, `leave` | live Fabric API events |

Because the mod harvests the vanilla files themselves, switching to it is
lossless: everything the server has counted so far flows into the database
on the first cycle.

## Requirements

- Minecraft **26.3** server (Fabric Loader ≥ 0.19.2, Java 25)
- Fabric API
- Either a reachable MySQL/MariaDB instance or nothing (SQLite mode)

## Installation

1. Drop the released `mcstats-to-sql-<version>.jar` into `mods/`.
2. Start the server once — `config/mcstats-to-sql.json` is created.
3. Edit the config (below), restart.

### Local SQLite (zero setup)

```json
{
  "database_type": "sqlite",
  "sqlite": { "file": "mcstats-to-sql.sqlite" }
}
```

The file is created next to the server jar. Back it up with your world.

### Remote MySQL / MariaDB

```json
{
  "database_type": "mysql",
  "mysql": {
    "host": "10.252.1.1",
    "port": 3306,
    "database": "mcstats",
    "username": "mcstats_writer",
    "password": "...",
    "ssl": false
  }
}
```

**Security advice:** grant the DB user only `INSERT, SELECT, UPDATE, DELETE`
on its single database. The mod never needs `DROP`, `ALTER` or `CREATE`
beyond the first start (schema creation happens once).

Example grant:

```sql
CREATE DATABASE mcstats CHARACTER SET utf8mb4;
CREATE USER 'mcstats_writer'@'%' IDENTIFIED BY 'strong-password';
GRANT INSERT, SELECT, UPDATE, DELETE, CREATE TEMPORARY TABLES ON mcstats.*
  TO 'mcstats_writer'@'%';
```

(The `%` host should ideally be narrowed to your server's IP.)

## Configuration reference

| Field | Default | Meaning |
|---|---|---|
| `database_type` | `sqlite` | `sqlite` or `mysql` |
| `flush_interval_seconds` | `60` | Seconds between harvest cycles (min 5) |
| `track_pvp` | `true` | Record killer↔victim pairs |
| `track_deaths` | `true` | Record deaths with cause |
| `track_sessions` | `true` | Record joins/leaves |

## Behavior notes

- **Non-blocking:** database writes happen on the server thread between
  ticks in small drained batches; harvest parsing is cheap (few KB JSON).
- **Failure mode:** if the database is unreachable at startup, the mod logs
  an error and keeps the server running normally — stats events are
  dropped, harvesting resumes when the config is fixed and the server
  restarted.
- **Full replace:** each harvest replaces a player's `stats` /
  `advancements` rows wholesale, so removing a stat in-game is reflected
  in SQL too. `events` are strictly append-only.

## Building from source

```bash
./gradlew build          # jar lands in build/libs/
```

Requires JDK 25. Uses official Mojang mappings (Yarn is not published for
Minecraft 26.x).

## License

MIT — see [LICENSE](LICENSE).
