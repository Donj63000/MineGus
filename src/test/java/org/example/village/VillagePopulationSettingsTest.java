package org.example.village;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VillagePopulationSettingsTest {

    @Test
    void unsafeValuesAreClampedForServerSafety() {
        VillagePopulationSettings settings =
                new VillagePopulationSettings(
                        -5,
                        99,
                        1,
                        1,
                        0,
                        99,
                        1,
                        99,
                        9_999_999
                );

        assertEquals(0, settings.initialResidents());
        assertEquals(16, settings.villagerSpawnerCount());
        assertEquals(12, settings.populationCap());
        assertEquals(200, settings.minDelayTicks());
        assertEquals(200, settings.maxDelayTicks());
        assertEquals(12, settings.maxNearbyEntities());
        assertEquals(8, settings.requiredPlayerRange());
        assertEquals(6, settings.spawnRange());
        assertEquals(1_728_000, settings.lifetimeTicks());
    }

    @Test
    void capAlwaysLeavesRoomForResidentsAndNamedNpcs() {
        VillagePopulationSettings settings =
                new VillagePopulationSettings(
                        40,
                        8,
                        20,
                        1_200,
                        2_400,
                        4,
                        24,
                        3,
                        0
                );

        assertEquals(52, settings.populationCap());
    }
}
