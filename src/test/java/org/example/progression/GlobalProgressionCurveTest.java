package org.example.progression;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.example.MinePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalProgressionCurveTest {

    private ServerMock server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            MockBukkit.unmock();
        }
    }

    @Test
    void curveStopsAtOneHundredAndEachLevelUpAddsOnePercent() {
        server = MockBukkit.mock();
        MinePlugin plugin = MockBukkit.load(MinePlugin.class);
        GlobalProgressionManager progression =
                plugin.getGlobalProgressionManager();

        assertNotNull(progression);
        assertEquals(100, progression.getMaxLevel());

        assertEquals(0L, progression.getTotalXpForLevel(1));
        assertEquals(200L, progression.getTotalXpForLevel(2));
        assertEquals(454L, progression.getTotalXpForLevel(3));
        assertEquals(1_536_546L,
                progression.getTotalXpForLevel(100));

        assertEquals(0.0D,
                progression.getHealthBonusPercentForLevel(1));
        assertEquals(1.0D,
                progression.getOxygenBonusPercentForLevel(2));
        assertEquals(49.0D,
                progression.getDamageBonusPercentForLevel(50));
        assertEquals(99.0D,
                progression.getMiningSpeedBonusPercentForLevel(100));
    }

    @Test
    void oxygenAttributeTargetsTheConfiguredDurationBonus() {
        assertEquals(
                0.0D,
                GlobalProgressionManager.oxygenAttributeForDurationBonus(0.0D),
                1.0E-12D);
        assertEquals(
                0.01D,
                GlobalProgressionManager.oxygenAttributeForDurationBonus(0.01D),
                1.0E-12D);
        assertEquals(
                0.99D,
                GlobalProgressionManager.oxygenAttributeForDurationBonus(0.99D),
                1.0E-12D);
    }
}
