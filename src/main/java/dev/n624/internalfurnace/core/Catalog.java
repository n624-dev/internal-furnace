package dev.n624.internalfurnace.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Strict, reviewable cost table. No invalid row may become a free upgrade. */
public final class Catalog {
    public record Cost(String track, int level, int rank, int combatLevel, int xpLevels,
                       Map<String, Integer> items) {
        public Cost { items = Collections.unmodifiableMap(new LinkedHashMap<>(items)); }
        public String key() { return track + ":" + level; }
    }
    private final Map<String, Cost> costs;
    private Catalog(Map<String, Cost> costs) { this.costs = Collections.unmodifiableMap(costs); }
    public Map<String, Cost> entries() { return costs; }
    public Cost next(Profile profile, String track) { return costs.get(track + ":" + profile.next(track)); }

    public static Catalog read(Reader input) throws IOException {
        Map<String, Cost> rows = new LinkedHashMap<>();
        BufferedReader reader = new BufferedReader(input);
        String line;
        int number = 0;
        while ((line = reader.readLine()) != null) {
            number++;
            if (number > 200 || line.length() > 4096) throw new IOException("Cost table too large");
            if (line.isBlank() || line.startsWith("#")) continue;
            try {
                String[] parts = line.split("\\|", -1);
                if (parts.length != 6) throw new IllegalArgumentException("Expected six columns");
                String track = parts[0];
                int max = "rank".equals(track) ? 7 : Upgrade.byId(track).maximum();
                int level = integer(parts[1], 1, max);
                int rank = integer(parts[2], 0, 7);
                int combat = integer(parts[3], 1, 1000);
                int xp = integer(parts[4], 1, 1000);
                if ("rank".equals(track) ? rank != level - 1 : rank < 1)
                    throw new IllegalArgumentException("Invalid rank prerequisite");
                Map<String, Integer> items = new LinkedHashMap<>();
                for (String ingredient : parts[5].split(",", -1)) {
                    String[] pair = ingredient.split("=", -1);
                    if (pair.length != 2 || !pair[0].matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                        throw new IllegalArgumentException("Invalid ingredient");
                    if (items.putIfAbsent(pair[0], integer(pair[1], 1, 4096)) != null)
                        throw new IllegalArgumentException("Duplicate ingredient");
                }
                if (items.size() > 8 || items.containsKey("minecraft:air"))
                    throw new IllegalArgumentException("Expected at most eight non-air ingredients");
                Cost row = new Cost(track, level, rank, combat, xp, items);
                if (rows.putIfAbsent(row.key(), row) != null) throw new IllegalArgumentException("Duplicate cost");
            } catch (IllegalArgumentException e) { throw new IOException("Invalid cost row " + number, e); }
        }
        int expected = 7;
        for (Upgrade u : Upgrade.values()) expected += u.maximum();
        if (rows.size() != expected) throw new IOException("Missing costs: expected " + expected);
        Catalog catalog = new Catalog(rows);
        // Each step must be reachable without decreasing a prerequisite.
        for (String track : tracks()) {
            int max = "rank".equals(track) ? 7 : Upgrade.byId(track).maximum();
            int lastRank = 0, lastCombat = 0;
            for (int level = 1; level <= max; level++) {
                Cost c = rows.get(track + ":" + level);
                if (c == null || c.rank < lastRank || c.combatLevel < lastCombat)
                    throw new IOException("Invalid progression: " + track);
                lastRank = c.rank; lastCombat = c.combatLevel;
            }
        }
        return catalog;
    }
    private static int integer(String value, int min, int max) {
        int n = Integer.parseInt(value);
        if (n < min || n > max) throw new IllegalArgumentException("Out of range");
        return n;
    }
    public static java.util.List<String> tracks() {
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add("rank");
        for (Upgrade u : Upgrade.values()) names.add(u.id());
        return java.util.List.copyOf(names);
    }

    public record Purchase(Profile after, Map<String, Integer> consume, int xpLevels) {}
    /** Planning has no side effects. The server adapter validates a live snapshot before committing. */
    public Purchase plan(Profile current, String track, long expectedRevision, int combatLevel,
                         int xpLevels, Map<String, Integer> available) {
        if (current.revision() != expectedRevision) throw new IllegalArgumentException("Stale purchase");
        Cost cost = next(current, track);
        if (cost == null) throw new IllegalArgumentException("Already maximized");
        if (current.rank() < cost.rank || combatLevel < cost.combatLevel || xpLevels < cost.xpLevels)
            throw new IllegalArgumentException("Requirements not met");
        for (var e : cost.items.entrySet())
            if (available.getOrDefault(e.getKey(), 0) < e.getValue())
                throw new IllegalArgumentException("Missing " + e.getKey());
        return new Purchase(current.advance(track), cost.items, cost.xpLevels);
    }
}
