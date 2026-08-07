package org.example.mineur;

/**
 * Définit la cadence de base de la boucle de minage.
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
        double safeMultiplier = Double.isFinite(miningSpeedMultiplier)
                ? Math.max(0.01D, miningSpeedMultiplier)
                : 1.0D;
        return safeMultiplier / ticksPerStage;
    }
}
