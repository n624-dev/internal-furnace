package dev.n624.internalfurnace.core;

/** Bounded drag planner: mode 0 distributes evenly, 1 places one, 2 fills in creative. */
public final class DragDistribution {
    private DragDistribution() {}
    public record Plan(int[] added, int remaining) {
        public Plan { added = added.clone(); }
        @Override public int[] added() { return added.clone(); }
    }
    public static Plan plan(int count, int[] free, int mode, boolean creative) {
        if (count < 0 || count > 64 || free == null || free.length > 99 || mode < 0 || mode > 2
                || (mode == 2 && !creative)) throw new IllegalArgumentException("Invalid drag operation");
        int[] result = new int[free.length];
        int remaining = count;
        for (int capacity : free) if (capacity < 0 || capacity > 64)
            throw new IllegalArgumentException("Invalid slot capacity");
        if (free.length == 0) return new Plan(result, remaining);
        int each = mode == 0 ? count / free.length : mode == 1 ? 1 : 64;
        for (int i = 0; i < free.length; i++) {
            result[i] = Math.min(each, free[i]);
            if (mode != 2) { result[i] = Math.min(result[i], remaining); remaining -= result[i]; }
        }
        return new Plan(result, remaining);
    }
}
