package dev.n624.internalfurnace.core;

/** Fixed-point recipe XP attribution survives output splits, merges and restarts. */
public final class ExperienceLedger {
    public static final long UNIT = 1_000_000;
    private ExperienceLedger() {}
    public static long recipe(float experience) {
        if (!Float.isFinite(experience) || experience < 0 || experience > 10000)
            throw new IllegalArgumentException("Invalid recipe XP");
        return Math.round((double) experience * UNIT);
    }
    public static long split(long credit, int taken, int countBefore) {
        if (credit < 0 || credit > 1_000_000_000_000L || countBefore < 1 || countBefore > 64
                || taken < 0 || taken > countBefore) throw new IllegalArgumentException("Invalid XP transfer");
        return Math.multiplyExact(credit, taken) / countBefore;
    }
}
