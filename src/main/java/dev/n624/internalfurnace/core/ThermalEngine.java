package dev.n624.internalfurnace.core;

/** Exact fixed-point heat. 1000 units = one ordinary furnace burn tick. */
public final class ThermalEngine {
    public static final long UNIT = 1000;
    private long heat;
    private int cursor;
    public ThermalEngine(long heat, int cursor) {
        if (heat < 0 || heat > 51_200 * UNIT || cursor < 0 || cursor > 3)
            throw new IllegalArgumentException("Invalid heat snapshot");
        this.heat = heat; this.cursor = cursor;
    }
    public long heat() { return heat; }
    public int cursor() { return cursor; }
    public void clear() { heat = 0; }
    public static long fuelHeat(int vanillaBurnTicks, int efficiencyPermille) {
        if (vanillaBurnTicks <= 0 || vanillaBurnTicks > 1_000_000 || efficiencyPermille < 1 || efficiencyPermille > 900)
            throw new IllegalArgumentException("Invalid fuel or efficiency");
        return Math.multiplyExact((long) vanillaBurnTicks, efficiencyPermille);
    }
    public boolean canCharge(long amount, long capacity) {
        return amount > 0 && capacity > 0 && capacity <= 51_200 * UNIT && heat <= capacity && amount <= capacity - heat;
    }
    public boolean charge(long amount, long capacity) {
        if (!canCharge(amount, capacity)) return false;
        heat += amount;
        return true;
    }
    /** Required heat per recipe-progress unit; smoking/blasting must NOT halve fuel cost. */
    public enum Mode {
        SMELTING(1), SMOKING(2), BLASTING(2);
        public final int heatPerProgress;
        Mode(int heatPerProgress) { this.heatPerProgress = heatPerProgress; }
    }
    public record Work(long remainingProgress, Mode mode, boolean outputAvailable) {
        public Work {
            if (remainingProgress < 0 || remainingProgress > 1_000_000 * UNIT || mode == null)
                throw new IllegalArgumentException("Invalid work");
        }
    }
    /** Caller invokes once per online server tick. No wall-clock/offline catch-up exists. */
    public long[] advance(Work[] chambers, int speedPermille, int idleLossPermille) {
        if (chambers.length < 1 || chambers.length > 4 || speedPermille < 1 || speedPermille > 2000
                || idleLossPermille < 0 || idleLossPermille > 1000)
            throw new IllegalArgumentException("Invalid thermal parameters");
        long[] progress = new long[chambers.length];
        boolean moved = false;
        for (int n = 0; n < chambers.length; n++) {
            int i = (cursor + n) % chambers.length;
            Work w = chambers[i];
            if (w == null || !w.outputAvailable) continue;
            long step = Math.min(speedPermille, Math.min(w.remainingProgress, heat / w.mode.heatPerProgress));
            heat -= step * w.mode.heatPerProgress;
            progress[i] = step;
            moved |= step > 0;
        }
        if (moved) cursor = (cursor + 1) % chambers.length;
        // A blocked output or exhausted queue also causes idle cooling.
        if (!moved) heat = Math.max(0, heat - idleLossPermille);
        return progress;
    }
}
