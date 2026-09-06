package dev.n624.internalfurnace.core;

import java.util.Arrays;
import java.util.Locale;

/** Stable persisted IDs: never use enum ordinals in saved data or packets. */
public enum Upgrade {
    CHAMBERS("chambers", 1, 2, 3, 4),
    SPEED("speed", 1000, 1150, 1300, 1500, 1750, 2000),
    EFFICIENCY("efficiency", 500, 575, 650, 725, 800, 850, 900),
    CAPACITY("capacity", 1600, 3200, 6400, 12800, 25600, 51200),
    INSULATION("insulation", 1000, 800, 600, 400, 250, 100),
    QUEUE("queue", 1, 3, 6, 9, 18, 27),
    FUEL_STORAGE("fuel_storage", 1, 3, 6, 9, 18),
    OUTPUT("output", 1, 3, 6, 9, 18),
    AUTO_FUEL("auto_fuel", 0, 1, 2, 3, 4),
    AUTO_INPUT("auto_input", 0, 1, 2, 3, 4, 5);

    private final String id;
    private final int[] values;
    Upgrade(String id, int... values) { this.id = id; this.values = values; }
    public String id() { return id; }
    public int maximum() { return values.length - 1; }
    public int value(int level) {
        if (level < 0 || level > maximum()) throw new IllegalArgumentException("Invalid " + id + " level");
        return values[level];
    }
    public static Upgrade byId(String id) {
        return Arrays.stream(values()).filter(u -> u.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown upgrade: " + id));
    }
    public String translationKey() { return "upgrade.internal_furnace." + id.toLowerCase(Locale.ROOT); }
}
