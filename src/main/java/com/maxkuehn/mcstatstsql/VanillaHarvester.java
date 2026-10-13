package com.maxkuehn.mcstatstsql;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Periodic harvester reading the canonical vanilla data files:
 *
 * <ul>
 *   <li>{@code world/stats/<uuid>.json} — every vanilla statistic</li>
 *   <li>{@code world/advancements/<uuid>.json} — granted advancements</li>
 * </ul>
 *
 * <p>This mirrors what the vanilla server itself persists, so the SQL
 * database is a faithful projection of the game's own accounting —
 * including stats earned before the mod was installed.</p>
 */
public final class VanillaHarvester {

    private final Path statsDir;
    private final Path advancementsDir;
    private final StatsSink sink;

    public interface StatsSink {

        void playerSeen(UUID uuid, String name);

        void statsLoaded(UUID uuid, Map<String, Long> flatStats);

        void advancementsLoaded(UUID uuid, Set<String> ids);
    }

    public VanillaHarvester(Path statsDir, Path advancementsDir, StatsSink sink) {
        this.statsDir = statsDir;
        this.advancementsDir = advancementsDir;
        this.sink = sink;
    }

    /**
     * Runs one harvest pass. Unknown-uuid files are skipped; IO errors
     * are swallowed per-file so one corrupt file never stalls the loop.
     *
     * @return number of player files processed
     */
    public int harvestOnce(UserNameResolver resolver) {
        if (!Files.isDirectory(statsDir)) {
            return 0;
        }
        int count = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(statsDir, "*.json")) {
            for (Path p : ds) {
                String fname = p.getFileName().toString();
                String stem = fname.substring(0, fname.length() - ".json".length());
                UUID uuid;
                try {
                    uuid = UUID.fromString(stem);
                } catch (IllegalArgumentException e) {
                    continue; // not a player stat file
                }
                try {
                    JsonObject root = JsonParser.parseReader(
                            Files.newBufferedReader(p)).getAsJsonObject();
                    Map<String, Long> flat = flattenStats(root);
                    Set<String> advs = loadAdvancements(uuid);
                    String name = resolver.nameOf(uuid);
                    if (name != null) {
                        sink.playerSeen(uuid, name);
                    }
                    sink.statsLoaded(uuid, flat);
                    sink.advancementsLoaded(uuid, advs);
                    count++;
                } catch (IOException | IllegalStateException e) {
                    MCStatsToSql.LOGGER.warn(
                            "skipping unreadable stat file {}: {}", fname, e.getMessage());
                }
            }
        } catch (IOException e) {
            MCStatsToSql.LOGGER.warn("stats directory scan failed: {}", e.getMessage());
        }
        return count;
    }

    /**
     * Flattens the vanilla stat JSON into dotted keys.
     *
     * <p>The canonical vanilla format nests once:
     * {@code {"stats":{"custom:minecraft:jump":12,"mined:minecraft:stone":3}}}.
     * A doubly-nested variant ({@code {"stats":{"custom":{"minecraft:jump":12}}}})
     * is accepted too for robustness.</p>
     */
    static Map<String, Long> flattenStats(JsonObject root) {
        JsonObject stats = root.has("stats") && root.get("stats").isJsonObject()
                ? root.getAsJsonObject("stats") : root;
        Map<String, Long> flat = new HashMap<>();
        for (var e : stats.entrySet()) {
            String key = e.getKey();               // "custom:minecraft:jump"
            if (e.getValue().isJsonObject()) {     // nested variant
                for (var inner : e.getValue().getAsJsonObject().entrySet()) {
                    addFlat(flat, key + ":" + inner.getKey(), inner.getValue());
                }
            } else {
                addFlat(flat, key, e.getValue());
            }
        }
        return flat;
    }

    private static void addFlat(Map<String, Long> flat, String key, JsonElement v) {
        if (!v.isJsonPrimitive()) {
            return;
        }
        try {
            long n = v.getAsLong();
            if (n != 0) {
                flat.put(key, n);
            }
        } catch (NumberFormatException ignored) {
            // unexpected shape — skip the single entry
        }
    }

    private Set<String> loadAdvancements(UUID uuid) {
        Set<String> ids = new HashSet<>();
        Path f = advancementsDir.resolve(uuid + ".json");
        if (!Files.isRegularFile(f)) {
            return ids;
        }
        try {
            JsonObject root = JsonParser.parseReader(
                    Files.newBufferedReader(f)).getAsJsonObject();
            for (var e : root.entrySet()) {
                JsonObject criteriaObj = e.getValue().isJsonObject()
                        ? e.getValue().getAsJsonObject() : null;
                boolean done = criteriaObj != null
                        && criteriaObj.has("done")
                        && criteriaObj.get("done").isJsonPrimitive()
                        && criteriaObj.getAsJsonPrimitive("done").isBoolean()
                        && criteriaObj.getAsJsonPrimitive("done").getAsBoolean();
                if (done) {
                    ids.add(e.getKey());
                }
            }
        } catch (IOException | IllegalStateException e) {
            MCStatsToSql.LOGGER.warn("unreadable advancement file for {}: {}",
                    uuid, e.getMessage());
        }
        return ids;
    }

    /** Resolves player names for harvested uuids. */
    public interface UserNameResolver {

        String nameOf(UUID uuid);
    }
}
