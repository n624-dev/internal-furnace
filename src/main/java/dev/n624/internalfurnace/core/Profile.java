package dev.n624.internalfurnace.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Immutable progression snapshot. Replacing it is the purchase commit point. */
public record Profile(int rank, Map<Upgrade, Integer> levels, long revision) {
    public Profile {
        if (rank < 0 || rank > 7 || revision < 0) throw new IllegalArgumentException("Invalid progression");
        EnumMap<Upgrade, Integer> copy = new EnumMap<>(Upgrade.class);
        copy.putAll(levels);
        for (Upgrade u : Upgrade.values()) {
            int level = copy.getOrDefault(u, 0);
            u.value(level);
            if (rank == 0 && level != 0) throw new IllegalArgumentException("Locked profile has upgrades");
            copy.put(u, level);
        }
        levels = Collections.unmodifiableMap(copy);
    }
    public static Profile locked() { return new Profile(0, Map.of(), 0); }
    public int level(Upgrade u) { return levels.get(u); }
    public int value(Upgrade u) { return u.value(level(u)); }
    public Profile advance(String track) {
        if ("rank".equals(track)) return new Profile(rank + 1, levels, Math.addExact(revision, 1));
        if (rank == 0) throw new IllegalArgumentException("Furnace is locked");
        Upgrade u = Upgrade.byId(track);
        EnumMap<Upgrade, Integer> copy = new EnumMap<>(levels);
        copy.put(u, level(u) + 1);
        return new Profile(rank, copy, Math.addExact(revision, 1));
    }
    public int next(String track) { return "rank".equals(track) ? rank + 1 : level(Upgrade.byId(track)) + 1; }
}
