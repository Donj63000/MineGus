package org.example.village;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VillageKnightSettingsTest {

    @Test
    void clampsUnsafeValuesAndKeepsDependentRadiiConsistent() {
        VillageKnightSettings settings = new VillageKnightSettings(
                true,
                99,
                1,
                99,
                true,
                99,
                1,
                1,
                0,
                Double.NaN,
                3.0D,
                2.0D,
                -5.0D,
                0,
                Double.POSITIVE_INFINITY,
                0.0D,
                1,
                1,
                3,
                0.0D,
                Double.NaN,
                5.0D,
                0.0D,
                -1.0D,
                2.0D,
                0.0D
        );

        assertEquals(6, settings.urbanPatrolCount());
        assertEquals(3, settings.soldiersPerPatrol());
        assertEquals(8, settings.strategicGuardCount());
        assertEquals(8, settings.wallArcherCount());
        assertEquals(5, settings.respawnDelaySeconds());
        assertEquals(5, settings.maintenancePeriodTicks());
        assertEquals(1, settings.scansPerCycle());
        assertEquals(4.0D, settings.detectionRadius());
        assertEquals(4.0D, settings.assistanceRadius());
        assertEquals(4.0D, settings.combatLeashRadius());
        assertEquals(0.0D, settings.protectionMargin());
        assertEquals(1, settings.maxRespondersPerThreat());
        assertEquals(0.20D, settings.patrolSpeed());
        assertEquals(1.0D, settings.waypointArrivalRadius());
        assertEquals(5, settings.routeAdvanceTimeoutSeconds());
        assertEquals(2, settings.missingEntityLoadedChecks());
        assertEquals(5, settings.stuckSeconds());
        assertEquals(4.0D, settings.maxHealth());
        assertEquals(1.0D, settings.meleeDamageCap());
        assertEquals(5.0D, settings.arrowDamageCap());
        assertEquals(0.10D, settings.movementSpeed());
        assertEquals(4.0D, settings.followRange());
        assertEquals(1.0D, settings.knockbackResistance());
        assertEquals(1.0D, settings.incomingHostileDamageMultiplier());
        assertEquals(34, settings.expectedUnitCount());
        assertEquals(5_000L, settings.respawnDelayMillis());
        assertEquals(5_000L, settings.routeAdvanceTimeoutMillis());
        assertEquals(5_000L, settings.stuckMillis());
    }

    @Test
    void excludesConfiguredArchersWhenRampartArchersAreDisabled() {
        VillageKnightSettings settings = new VillageKnightSettings(
                true,
                2,
                4,
                3,
                false,
                8,
                60,
                10,
                4,
                24.0D,
                36.0D,
                48.0D,
                16.0D,
                3,
                0.85D,
                2.75D,
                25,
                3,
                20,
                26.0D,
                6.0D,
                5.0D,
                0.27D,
                40.0D,
                0.25D,
                2.75D
        );

        assertEquals(11, settings.expectedUnitCount());
    }
}
