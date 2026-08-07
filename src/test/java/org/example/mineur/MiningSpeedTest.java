package org.example.mineur;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiningSpeedTest {

    @Test
    void progressPerTickScalesWithPresetAndLevelMultiplier() {
        assertEquals(0.5D, MiningSpeed.FAST.progressPerTick(1.0D), 0.0001D);
        assertEquals(0.995D, MiningSpeed.FAST.progressPerTick(1.99D), 0.0001D);
        assertEquals(0.2D, MiningSpeed.NORMAL.progressPerTick(1.0D), 0.0001D);
        assertEquals(0.1D, MiningSpeed.SLOW.progressPerTick(1.0D), 0.0001D);
    }

    @Test
    void progressPerTickFallsBackForNonFiniteMultipliers() {
        assertEquals(0.2D, MiningSpeed.NORMAL.progressPerTick(Double.NaN), 0.0001D);
        assertEquals(0.2D, MiningSpeed.NORMAL.progressPerTick(Double.POSITIVE_INFINITY), 0.0001D);
        assertEquals(0.01D, MiningSpeed.NORMAL.progressPerTick(-10.0D), 0.0001D);
    }
}
