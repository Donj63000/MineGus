package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
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
        VillageLayoutPlan layout = emptyLayout(100, -40, 20);
        VillageKnightSettings settings = settings(
                1,
                4,
                0,
                0,
                0,
                0,
                false,
                0
        );

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
        assertEquals(
                VillageKnightGarrison.CURRENT_PLAN_VERSION,
                first.planVersion()
        );
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

    @Test
    void createsTwoFixedGuardsForEveryConfiguredStrategicArea() {
        World world = walkableWorld(
                UUID.randomUUID(),
                y -> y == 64 || y == 73
        );
        Location center = new Location(world, 0.0D, 64.0D, 0.0D);
        VillageKnightGarrison garrison = VillageKnightPlanner.plan(
                17,
                center,
                emptyLayout(0, 0, 24),
                Map.of(),
                24,
                24,
                64,
                settings(0, 4, 2, 2, 2, 2, false, 0)
        );

        assertNotNull(garrison);
        assertEquals(8, garrison.unitCount());
        assertEquals(8, garrison.routes().size());
        assertEquals(
                2L,
                countRoutes(
                        garrison,
                        VillageKnightGarrison.RouteKind.GATE_SENTRY
                )
        );
        assertEquals(
                2L,
                countRoutes(
                        garrison,
                        VillageKnightGarrison.RouteKind.PLAZA_SENTRY
                )
        );
        assertEquals(
                2L,
                countRoutes(
                        garrison,
                        VillageKnightGarrison.RouteKind.KEEP_ENTRANCE_SENTRY
                )
        );
        assertEquals(
                2L,
                countRoutes(
                        garrison,
                        VillageKnightGarrison.RouteKind.KEEP_INTERIOR_SENTRY
                )
        );
        assertTrue(garrison.routes().stream()
                .allMatch(route -> route.waypoints().size() == 1));
    }

    @Test
    void keepsUsableRampartSectionsWhenGateOpeningsBreakTheRing() {
        UUID worldId = UUID.randomUUID();
        Block solid = block(Material.STONE, false);
        Block air = block(Material.AIR, true);
        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldId);
        when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    int x = invocation.getArgument(0);
                    int y = invocation.getArgument(1);
                    int z = invocation.getArgument(2);
                    if (y != 73) {
                        return air;
                    }

                    /*
                     * Deux ouvertures de sept blocs simulent le châtelet et le
                     * raccord du donjon. Le reste de la courtine est praticable.
                     */
                    boolean gateGap = z == 21 && Math.abs(x) <= 3;
                    boolean keepGap = z == -21 && Math.abs(x) <= 3;
                    return gateGap || keepGap ? air : solid;
                });

        VillageKnightGarrison garrison = VillageKnightPlanner.plan(
                21,
                new Location(world, 0.0D, 64.0D, 0.0D),
                emptyLayout(0, 0, 20),
                Map.of(),
                20,
                20,
                64,
                settings(0, 4, 0, 0, 0, 0, true, 4)
        );

        assertNotNull(garrison);
        assertEquals(4, garrison.unitCount());
        assertEquals(4L, countRoutes(
                garrison,
                VillageKnightGarrison.RouteKind.RAMPART
        ));
        assertTrue(garrison.rampartPlanComplete());
        assertTrue(garrison.routes().stream()
                .flatMap(route -> route.waypoints().stream())
                .allMatch(point -> point.y() == 74));
        assertFalse(garrison.routes().stream()
                .flatMap(route -> route.waypoints().stream())
                .anyMatch(point ->
                        (point.z() == 21 || point.z() == -21)
                                && Math.abs(point.x()) <= 3
                ));
    }

    @Test
    void createsExactlyFourFixedTreasuryGuardsFromVillageAnchors() {
        World world = walkableWorld(
                UUID.randomUUID(),
                y -> y == 44
        );
        Location center = new Location(world, 0.0D, 64.0D, 0.0D);
        Map<String, Location> anchors = Map.of(
                "treasury-guard-0",
                new Location(world, -2.5D, 45.0D, 8.5D),
                "treasury-guard-1",
                new Location(world, 3.5D, 45.0D, 8.5D),
                "treasury-guard-2",
                new Location(world, -2.5D, 45.0D, -7.5D),
                "treasury-guard-3",
                new Location(world, 3.5D, 45.0D, -7.5D)
        );

        VillageKnightGarrison garrison = VillageKnightPlanner.plan(
                42,
                center,
                emptyLayout(0, 0, 24),
                anchors,
                24,
                24,
                64,
                settings(0, 4, 0, 0, 0, 0, false, 0)
        );

        assertNotNull(garrison);
        assertEquals(4, garrison.unitCount());
        assertEquals(4L, countRoutes(
                garrison,
                VillageKnightGarrison.RouteKind.TREASURY_SENTRY
        ));
        assertTrue(garrison.routes().stream()
                .allMatch(route -> route.kind().isSentry()
                        && route.waypoints().size() == 1));
    }

    @Test
    void upgradesLegacyStrategicRoutesToExplicitRoles() {
        UUID worldId = UUID.randomUUID();
        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldId);
        when(world.getName()).thenReturn("world");

        VillageKnightGarrison.BlockPoint point =
                new VillageKnightGarrison.BlockPoint(0, 65, 0);
        VillageKnightGarrison.PatrolRoute oldRoute =
                new VillageKnightGarrison.PatrolRoute(
                        0,
                        VillageKnightGarrison.RouteKind.STRATEGIC,
                        List.of(point),
                        0,
                        0L
                );
        VillageKnightGarrison.KnightSlot oldSlot =
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
                5,
                worldId,
                "world",
                point,
                24,
                24,
                1L,
                false,
                List.of(oldRoute),
                List.of(oldSlot)
        );

        VillageKnightGarrison upgraded =
                VillageKnightPlanner.upgradeLegacy(
                        legacy,
                        world,
                        settings(0, 4, 2, 2, 2, 2, false, 0)
                );

        assertNotNull(upgraded);
        assertEquals(
                VillageKnightGarrison.CURRENT_PLAN_VERSION,
                upgraded.planVersion()
        );
        assertFalse(upgraded.requiresPlanMigration());
        assertFalse(upgraded.rampartPlanComplete());
        assertEquals(8, upgraded.unitCount());
        assertEquals(2L, countRoutes(
                upgraded,
                VillageKnightGarrison.RouteKind.GATE_SENTRY
        ));
        assertEquals(2L, countRoutes(
                upgraded,
                VillageKnightGarrison.RouteKind.PLAZA_SENTRY
        ));
        assertEquals(2L, countRoutes(
                upgraded,
                VillageKnightGarrison.RouteKind.KEEP_ENTRANCE_SENTRY
        ));
        assertEquals(2L, countRoutes(
                upgraded,
                VillageKnightGarrison.RouteKind.KEEP_INTERIOR_SENTRY
        ));
    }

    private static long countRoutes(
            VillageKnightGarrison garrison,
            VillageKnightGarrison.RouteKind kind) {
        return garrison.routes().stream()
                .filter(route -> route.kind() == kind)
                .count();
    }

    private static VillageLayoutPlan emptyLayout(
            int centerX,
            int centerZ,
            int radius) {
        return new VillageLayoutPlan(
                List.of(),
                List.of(),
                List.of(),
                new VillageLayoutPlan.Bounds(
                        centerX - radius,
                        centerX + radius,
                        centerZ - radius,
                        centerZ + radius
                ),
                Map.of(),
                0,
                List.of()
        );
    }

    private static World walkableWorld(
            UUID worldId,
            IntPredicate solidY) {
        World world = mock(World.class);
        Block solid = block(Material.STONE, false);
        Block air = block(Material.AIR, true);
        when(world.getUID()).thenReturn(worldId);
        when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt()))
                .thenAnswer(invocation ->
                        solidY.test(invocation.getArgument(1))
                                ? solid
                                : air
                );
        return world;
    }

    private static Block block(Material material, boolean passable) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(material);
        when(block.isPassable()).thenReturn(passable);
        when(block.isLiquid()).thenReturn(false);
        return block;
    }

    private static VillageKnightSettings settings(
            int urbanPatrols,
            int soldiersPerPatrol,
            int gate,
            int plaza,
            int keepEntrance,
            int keepInterior,
            boolean archersEnabled,
            int archers) {
        return new VillageKnightSettings(
                true,
                urbanPatrols,
                soldiersPerPatrol,
                gate,
                plaza,
                keepEntrance,
                keepInterior,
                archersEnabled,
                archers,
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
                30,
                2,
                40,
                10.0D,
                2.0D,
                16.0D,
                2.5D,
                8.0D,
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
