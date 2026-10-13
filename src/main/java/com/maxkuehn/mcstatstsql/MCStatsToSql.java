package com.maxkuehn.mcstatstsql;

import com.maxkuehn.mcstatstsql.db.AbstractSqlStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MCStats-to-SQL — writes all Minecraft player statistics to SQL.
 *
 * <p>Two ingestion paths feed one store:</p>
 * <ol>
 *   <li><b>Harvest</b> — every {@code flush_interval_seconds} the vanilla
 *       {@code world/stats/} and {@code world/advancements/} files are
 *       read and mirrored into the database (full history incl. pre-mod
 *       progress).</li>
 *   <li><b>Events</b> — PvP kills, deaths and sessions are captured live
 *       via Fabric API events and appended to the {@code events} table.</li>
 * </ol>
 */
public class MCStatsToSql implements ModInitializer {

    public static final Logger LOGGER =
            LoggerFactory.getLogger("mcstats-to-sql");

    private static final LevelResource STATS_DIR_RES =
            new LevelResource("stats");
    private static final LevelResource ADVANCEMENTS_DIR_RES =
            new LevelResource("advancements");

    private volatile AbstractSqlStore store;
    private volatile MinecraftServer server;
    private final ConcurrentLinkedQueue<Runnable> pendingDbWrites =
            new ConcurrentLinkedQueue<>();
    private ScheduledExecutorService harvestScheduler;

    private ModConfig config;

    @Override
    public void onInitialize() {
        try {
            config = ModConfig.load();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "mcstats-to-sql: cannot load/create config", e);
        }
        LOGGER.info("MCStats-to-SQL initialized (backend={}, flush={}s)",
                config.database_type, config.flush_interval_seconds);

        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);
    }

    /* ------------------------------------------------------------ */

    private void onServerStarted(MinecraftServer srv) {
        this.server = srv;
        try {
            store = AbstractSqlStore.open(config);
            store.init();
        } catch (RuntimeException e) {
            LOGGER.error("mcstats-to-sql: DATABASE UNAVAILABLE — harvesting "
                    + "disabled, events dropped. Fix config and restart. "
                    + "Reason: {}", e.getMessage());
            store = null;
        }

        VanillaHarvester harvester = new VanillaHarvester(
                srv.getWorldPath(STATS_DIR_RES),
                srv.getWorldPath(ADVANCEMENTS_DIR_RES),
                new Sink());

        // Own scheduler thread: vanilla PAUSES ticking when nobody is
        // online ("Server empty for 60 seconds, pausing"), so a tick-bound
        // harvest would stall on empty servers. The harvester only reads
        // immutable JSON files, so running outside the tick loop is safe.
        harvestScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcstats-to-sql-harvest");
            t.setDaemon(true);
            return t;
        });
        int periodSec = Math.max(5, config.flush_interval_seconds);
        harvestScheduler.scheduleAtFixedRate(() -> {
            drainPendingWrites();
            int n = harvester.harvestOnce(this::resolveName);
            if (n > 0) {
                LOGGER.info("mcstats-to-sql: harvested {} player stat file(s)", n);
            }
        }, periodSec, periodSec, TimeUnit.SECONDS);

        if (config.track_sessions) {
            registerSessionEvents();
        }
        if (config.track_pvp || config.track_deaths) {
            registerCombatEvents();
        }
    }

    private void onServerStopped(MinecraftServer srv) {
        if (harvestScheduler != null) {
            harvestScheduler.shutdownNow();
            harvestScheduler = null;
        }
        drainPendingWrites();
        if (store != null) {
            store.close();
            store = null;
        }
    }

    /* ------------------------------------------------------------ */

    private String resolveName(UUID uuid) {
        MinecraftServer s = server;
        if (s == null) {
            return null;
        }
        ServerPlayer p = s.getPlayerList().getPlayer(uuid);
        if (p != null) {
            return p.getGameProfile().name();
        }
        return s.services().nameToIdCache().get(uuid)
                .map(NameAndId::name)
                .orElse(null);
    }

    /* ------------------------------------------------------------ */

    private void registerSessionEvents() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) ->
                enqueueJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) ->
                enqueueLeave(handler.getPlayer()));
    }

    private void enqueueJoin(ServerPlayer p) {
        if (!config.track_sessions) {
            return;
        }
        String uuid = p.getUUID().toString();
        String name = p.getGameProfile().name();
        long now = System.currentTimeMillis() / 1000L;
        enqueue(() -> {
            store.upsertPlayer(uuid, name, now);
            store.insertEvent(now, "join", uuid, null, name);
        });
    }

    private void enqueueLeave(ServerPlayer p) {
        if (!config.track_sessions) {
            return;
        }
        String uuid = p.getUUID().toString();
        long now = System.currentTimeMillis() / 1000L;
        enqueue(() -> store.insertEvent(now, "leave", uuid, null, null));
    }

    /* ------------------------------------------------------------ */

    private void registerCombatEvents() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(entity instanceof ServerPlayer victim)) {
                return;
            }
            long now = System.currentTimeMillis() / 1000L;
            String victimUuid = victim.getUUID().toString();

            if (config.track_deaths) {
                String cause = safeCause(damageSource);
                enqueue(() -> store.insertEvent(
                        now, "death", victimUuid, null, cause));
            }

            if (config.track_pvp) {
                Player killer = killerOf(damageSource);
                if (killer != null && !killer.getUUID().equals(victim.getUUID())) {
                    String killerUuid = killer.getUUID().toString();
                    String weapon = weaponOf(killer);
                    enqueue(() -> store.insertEvent(
                            now, "pvp_kill", killerUuid, victimUuid, weapon));
                }
            }
        });
    }

    /**
     * Direct attacker first (melee hit); otherwise the causing entity
     * (arrow shooter, wolf owner, ...) unwrapped to its owning player.
     */
    private static Player killerOf(DamageSource src) {
        if (src.getDirectEntity() instanceof Player direct) {
            return direct;
        }
        if (src.getEntity() instanceof Player causing) {
            return causing;
        }
        return null;
    }

    private static String weaponOf(Player killer) {
        ItemStack main = killer.getMainHandItem();
        if (main.isEmpty()) {
            return "hands";
        }
        String id = main.getItem().toString();
        return id.length() > 200 ? id.substring(0, 200) : id;
    }

    private static String safeCause(DamageSource src) {
        try {
            String id = src.typeHolder().unwrapKey()
                    .map(k -> k.identifier().toString())
                    .orElseGet(src::getMsgId);
            return id.length() > 200 ? id.substring(0, 200) : id;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /* ------------------------------------------------------------ */

    private void enqueue(Runnable write) {
        pendingDbWrites.add(write);
    }

    private void drainPendingWrites() {
        Runnable r;
        while ((r = pendingDbWrites.poll()) != null) {
            AbstractSqlStore st = store;
            if (st == null) {
                continue; // DB down: drop rather than block the tick thread
            }
            try {
                r.run();
            } catch (RuntimeException e) {
                LOGGER.warn("mcstats-to-sql: write failed: {}", e.getMessage());
            }
        }
    }

    /** Bridges harvester callbacks into database writes. */
    private final class Sink implements VanillaHarvester.StatsSink {

        @Override
        public void playerSeen(UUID uuid, String name) {
            enqueue(() -> store.upsertPlayer(uuid.toString(), name,
                    System.currentTimeMillis() / 1000L));
        }

        @Override
        public void statsLoaded(UUID uuid, Map<String, Long> flatStats) {
            enqueue(() -> store.replaceStats(uuid.toString(), flatStats));
        }

        @Override
        public void advancementsLoaded(UUID uuid, java.util.Set<String> ids) {
            enqueue(() -> store.replaceAdvancements(uuid.toString(), ids));
        }
    }
}
