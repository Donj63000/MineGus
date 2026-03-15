package org.example;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JobManagerProgressionTest {

    private ServerMock server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            MockBukkit.unmock();
        }
    }

    @Test
    void progressionCurveAndUnlockThresholdsMatchConfiguration() {
        server = MockBukkit.mock();
        MinePlugin plugin = MockBukkit.load(MinePlugin.class);
        JobManager jobManager = plugin.getJobManager();

        assertEquals(0L, jobManager.getTotalXpForLevel(1));
        assertEquals(200L, jobManager.getTotalXpForLevel(2));
        assertEquals(454L, jobManager.getTotalXpForLevel(3));
        assertEquals(1_536_546L, jobManager.getTotalXpForLevel(100));

        assertEquals(1, jobManager.getLevelForXp(0));
        assertEquals(1, jobManager.getLevelForXp(199));
        assertEquals(2, jobManager.getLevelForXp(200));
        assertEquals(2, jobManager.getLevelForXp(453));
        assertEquals(3, jobManager.getLevelForXp(454));
        assertEquals(100, jobManager.getLevelForXp(Long.MAX_VALUE));

        assertEquals(1, jobManager.getMaxMinesForLevel(1));
        assertEquals(1, jobManager.getMaxMinesForLevel(19));
        assertEquals(2, jobManager.getMaxMinesForLevel(20));
        assertEquals(3, jobManager.getMaxMinesForLevel(30));
        assertEquals(10, jobManager.getMaxMinesForLevel(100));

        assertEquals(0.0D, jobManager.getMiningSpeedBonusPercentForLevel(1));
        assertEquals(1.0D, jobManager.getMiningSpeedBonusPercentForLevel(2));
        assertEquals(49.0D, jobManager.getMiningSpeedBonusPercentForLevel(50));
        assertEquals(99.0D, jobManager.getMiningSpeedBonusPercentForLevel(100));
    }
}
