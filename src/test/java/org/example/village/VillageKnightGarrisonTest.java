package org.example.village;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageKnightGarrisonTest {

    @Test
    void sanitizesRoutesAndMaintainsStableSlotIdentity() {
        VillageKnightGarrison.BlockPoint first =
                new VillageKnightGarrison.BlockPoint(10, 65, 20);
        VillageKnightGarrison.BlockPoint second =
                new VillageKnightGarrison.BlockPoint(14, 65, 20);
        VillageKnightGarrison.PatrolRoute route =
                new VillageKnightGarrison.PatrolRoute(
                        7,
                        VillageKnightGarrison.RouteKind.URBAN,
                        List.of(first, first, second),
                        -1,
                        -10L
                );
        UUID slotId = UUID.randomUUID();
        VillageKnightGarrison.KnightSlot slot =
                new VillageKnightGarrison.KnightSlot(
                        slotId,
                        7,
                        VillageKnightGarrison.UnitKind.SWORDSMAN,
                        -4,
                        null,
                        -1L,
                        first
                );

        VillageKnightGarrison garrison = new VillageKnightGarrison(
                12,
                UUID.randomUUID(),
                "world",
                first,
                1,
                2,
                -1L,
                List.of(route),
                List.of(slot)
        );

        assertEquals(
                VillageKnightGarrison.CURRENT_PLAN_VERSION,
                garrison.planVersion()
        );
        assertFalse(garrison.requiresPlanMigration());
        assertTrue(garrison.rampartPlanComplete());
        assertEquals(List.of(first, second), route.waypoints());
        assertEquals(1, route.waypointIndex());
        assertEquals(0L, route.lastAdvanceAtMillis());
        route.advance(42L);
        assertEquals(0, route.waypointIndex());
        assertEquals(42L, route.lastAdvanceAtMillis());

        assertEquals(0, slot.formationIndex());
        assertEquals(0L, slot.respawnAtMillis());
        assertSame(slot, garrison.slot(slotId));
        assertSame(route, garrison.route(7));
        assertEquals(1, garrison.unitCount());
        assertEquals(4, garrison.radiusX());
        assertEquals(4, garrison.radiusZ());
        assertEquals(0L, garrison.createdAtMillis());
        assertThrows(
                UnsupportedOperationException.class,
                () -> garrison.routes().clear()
        );
    }

    @Test
    void identifiesLegacyPlansAndClassifiesRouteResponsibilities() {
        VillageKnightGarrison.BlockPoint point =
                new VillageKnightGarrison.BlockPoint(0, 65, 0);
        VillageKnightGarrison.PatrolRoute route =
                new VillageKnightGarrison.PatrolRoute(
                        0,
                        VillageKnightGarrison.RouteKind.STRATEGIC,
                        List.of(point),
                        0,
                        0L
                );
        VillageKnightGarrison.KnightSlot slot =
                new VillageKnightGarrison.KnightSlot(
                        UUID.randomUUID(),
                        0,
                        VillageKnightGarrison.UnitKind.SWORDSMAN,
                        0,
                        null,
                        0L,
                        point
                );
        VillageKnightGarrison legacy = new VillageKnightGarrison(
                VillageKnightGarrison.LEGACY_PLAN_VERSION,
                1,
                UUID.randomUUID(),
                "world",
                point,
                16,
                16,
                1L,
                false,
                List.of(route),
                List.of(slot)
        );

        assertTrue(legacy.requiresPlanMigration());
        assertFalse(legacy.rampartPlanComplete());
        assertTrue(VillageKnightGarrison.RouteKind.STRATEGIC.isSentry());
        assertTrue(VillageKnightGarrison.RouteKind.GATE_SENTRY.isSentry());
        assertTrue(VillageKnightGarrison.RouteKind.URBAN.isPatrol());
        assertTrue(VillageKnightGarrison.RouteKind.RAMPART.isPatrol());
        assertFalse(
                VillageKnightGarrison.RouteKind.KEEP_INTERIOR_SENTRY
                        .isPatrol()
        );
    }

    @Test
    void rejectsSlotsThatReferenceAnUnknownRoute() {
        VillageKnightGarrison.PatrolRoute route =
                new VillageKnightGarrison.PatrolRoute(
                        1,
                        VillageKnightGarrison.RouteKind.STRATEGIC,
                        List.of(new VillageKnightGarrison.BlockPoint(0, 64, 0)),
                        0,
                        0L
                );
        VillageKnightGarrison.KnightSlot invalidSlot =
                new VillageKnightGarrison.KnightSlot(
                        UUID.randomUUID(),
                        2,
                        VillageKnightGarrison.UnitKind.ARCHER,
                        0,
                        null,
                        0L,
                        null
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> new VillageKnightGarrison(
                        1,
                        UUID.randomUUID(),
                        "world",
                        new VillageKnightGarrison.BlockPoint(0, 64, 0),
                        16,
                        16,
                        1L,
                        List.of(route),
                        List.of(invalidSlot)
                )
        );
    }
}
