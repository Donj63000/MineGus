package org.example.mineur;

/**
 * Controls the baseline speed of the mining loop.
 */
public enum MiningSpeed {
    SLOW(10),
    NORMAL(5),
    FAST(2);

    public final int ticksPerStage;

    MiningSpeed(int ticksPerStage) {
        this.ticksPerStage = ticksPerStage;
    }

    public double progressPerTick(double miningSpeedMultiplier) {
        double safeMultiplier = Math.max(0.01D, miningSpeedMultiplier);
        return safeMultiplier / ticksPerStage;
    }
}
