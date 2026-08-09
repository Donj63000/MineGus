package org.example.village;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VillageKnightPlannerTest {

    @Test
    void createsADeterministicFallbackPatrolWhenTheStreetGraphIsEmpty() {
        UUID worldId = UUID.randomUUID();
        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldId);
        when(world.getName()).thenReturn("world");
        Location center = new Location(world, 100.0D, 64.0D, -40.0D);
        VillageLayoutPlan layout = new VillageLayoutPlan(
                List.of(),
                List.of(),
                List.of(),
                new VillageLayoutPlan.Bounds(80, 120, -60, -20),
                Map.of(),
                0,
                List.of()
        );
        VillageKnightSettings settings = settingsForOnePatrol();

        VillageKnightGarrison first = VillageKnightPlanner.plan(
                9,
                center,
                layout,
                Map.of(),
                24,
                20,
                64,
                settings
        );
        VillageKnightGarrison second = VillageKnightPlanner.plan(
                9,
                center,
                layout,
                Map.of(),
                24,
                20,
                64,
                settings
        );

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(4, first.unitCount());
        assertEquals(1, first.routes().size());
        VillageKnightGarrison.PatrolRoute route = first.route(0);
        assertNotNull(route);
        assertEquals(VillageKnightGarrison.RouteKind.URBAN, route.kind());
        assertEquals(
                List.of(new VillageKnightGarrison.BlockPoint(100, 65, -40)),
                route.waypoints()
        );
        assertEquals(
                first.slots().stream()
                        .map(VillageKnightGarrison.KnightSlot::slotId)
                        .toList(),
                second.slots().stream()
                        .map(VillageKnightGarrison.KnightSlot::slotId)
                        .toList()
        );
    }

    private static VillageKnightSettings settingsForOnePatrol() {
        return new VillageKnightSettings(
                true,
                1,
                4,
                0,
                false,
                0,
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
    }
}
