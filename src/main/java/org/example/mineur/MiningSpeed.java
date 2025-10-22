package org.example.mineur;

/**
 * Controls the number of ticks spent in each mining animation stage.
 */
public enum MiningSpeed {
    SLOW(10),
    NORMAL(5),
    FAST(2);

    public final int ticksPerStage;

    MiningSpeed(int ticksPerStage) {
        this.ticksPerStage = ticksPerStage;
    }
}
