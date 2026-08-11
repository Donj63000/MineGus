package org.example.mineur.builders;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.example.mineur.builders.TunnelInfrastructureBuilder.BlockPos;
import org.example.mineur.builders.TunnelInfrastructureBuilder.FluidPlan;
import org.example.mineur.builders.TunnelInfrastructureBuilder.FluidScanner;
import org.example.mineur.builders.TunnelInfrastructureBuilder.Placement;
import org.example.mineur.builders.TunnelInfrastructureBuilder.PlacementKind;
import org.example.mineur.builders.TunnelInfrastructureBuilder.Plan;
import org.example.mineur.builders.TunnelInfrastructureBuilder.Settings;
import org.example.mineur.builders.TunnelInfrastructureBuilder.Volume;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunnelInfrastructureBuilderTest {

    private static final Settings ROOM_SETTINGS =
            new Settings(3, 3, 2, 3, 4, 1_000);

    @Test
    void detectsExteriorFluidsAndPlacesSealsInsideTheTunnel() {
        Volume volume = new Volume(10, 12, 30, 32, 20, 23);
        BlockPos interiorFluid = new BlockPos(11, 31, 21);
        BlockPos leftExterior = new BlockPos(9, 31, 21);
        BlockPos rightExterior = new BlockPos(13, 30, 22);
        BlockPos ceilingExterior = new BlockPos(11, 33, 22);
        BlockPos entranceExterior = new BlockPos(11, 31, 19);
        BlockPos frontExterior = new BlockPos(11, 31, 24);
        BlockPos ignoredFloorExterior = new BlockPos(11, 29, 21);
        Set<BlockPos> fluids = Set.of(
                interiorFluid,
                leftExterior,
                rightExterior,
                ceilingExterior,
                entranceExterior,
                frontExterior,
                ignoredFloorExterior
        );

        FluidPlan plan = TunnelInfrastructureBuilder.planFluids(
                volume,
                BlockFace.SOUTH,
                true,
                fluids::contains
        );

        assertAll(
                () -> assertEquals(Set.of(interiorFluid), plan.drainedInterior()),
                () -> assertEquals(
                        Set.of(
                                new BlockPos(10, 31, 21),
                                new BlockPos(12, 30, 22),
                                new BlockPos(11, 32, 22),
                                new BlockPos(11, 31, 20)
                        ),
                        positions(plan.permanentSeals())
                ),
                () -> assertEquals(
                        Set.of(new BlockPos(11, 31, 23)),
                        positions(plan.temporarySeals())
                ),
                () -> assertTrue(plan.permanentSeals().stream().allMatch(
                        placement -> placement.material()
                                == Material.LIGHT_BLUE_STAINED_GLASS
                )),
                () -> assertTrue(plan.temporarySeals().stream().allMatch(
                        placement -> placement.material() == Material.GLASS
                )),
                () -> assertFalse(plan.allSeals().stream().anyMatch(
                        placement -> fluids.contains(placement.position())
                                && !volume.contains(placement.position())
                ))
        );

        FluidPlan openEntrance = TunnelInfrastructureBuilder.planFluids(
                volume,
                BlockFace.SOUTH,
                false,
                fluids::contains
        );
        assertFalse(positions(openEntrance.permanentSeals()).contains(
                new BlockPos(11, 31, 20)
        ));
    }

    @Test
    void rotatesEntranceAndFrontFluidGeometryInEveryCardinalDirection() {
        Volume volume = new Volume(0, 4, 5, 7, 10, 14);
        List<DirectionCase> cases = List.of(
                new DirectionCase(
                        BlockFace.SOUTH,
                        new BlockPos(2, 6, 9),
                        new BlockPos(2, 6, 10),
                        new BlockPos(2, 6, 15),
                        new BlockPos(2, 6, 14)
                ),
                new DirectionCase(
                        BlockFace.NORTH,
                        new BlockPos(2, 6, 15),
                        new BlockPos(2, 6, 14),
                        new BlockPos(2, 6, 9),
                        new BlockPos(2, 6, 10)
                ),
                new DirectionCase(
                        BlockFace.EAST,
                        new BlockPos(-1, 6, 12),
                        new BlockPos(0, 6, 12),
                        new BlockPos(5, 6, 12),
                        new BlockPos(4, 6, 12)
                ),
                new DirectionCase(
                        BlockFace.WEST,
                        new BlockPos(5, 6, 12),
                        new BlockPos(4, 6, 12),
                        new BlockPos(-1, 6, 12),
                        new BlockPos(0, 6, 12)
                )
        );

        for (DirectionCase directionCase : cases) {
            Set<BlockPos> fluids = Set.of(
                    directionCase.entranceExterior(),
                    directionCase.frontExterior()
            );
            FluidPlan plan = TunnelInfrastructureBuilder.planFluids(
                    volume,
                    directionCase.direction(),
                    true,
                    fluids::contains
            );

            assertEquals(
                    Set.of(directionCase.entranceInterior()),
                    positions(plan.permanentSeals()),
                    directionCase.direction().name()
            );
            assertEquals(
                    Set.of(directionCase.frontInterior()),
                    positions(plan.temporarySeals()),
                    directionCase.direction().name()
            );
        }
    }

    @Test
    void incrementalFluidScannerMatchesTheSynchronousPlanInEveryDirection() {
        Volume volume = new Volume(-2, 2, 10, 12, 20, 23);
        Predicate<BlockPos> fluidLookup = position -> Math.floorMod(
                position.x() * 31 + position.y() * 17 + position.z(),
                7
        ) == 0;
        Predicate<BlockPos> boundaryThreatLookup = position -> Math.floorMod(
                position.x() * 13 + position.y() * 5 + position.z() * 3,
                11
        ) <= 1;

        for (BlockFace direction : cardinalDirections()) {
            FluidPlan expected = TunnelInfrastructureBuilder.planFluids(
                    volume,
                    direction,
                    true,
                    Material.BLUE_STAINED_GLASS,
                    Material.TINTED_GLASS,
                    fluidLookup,
                    boundaryThreatLookup
            );
            FluidScanner scanner = TunnelInfrastructureBuilder.createFluidScanner(
                    volume,
                    direction,
                    true,
                    Material.BLUE_STAINED_GLASS,
                    Material.TINTED_GLASS
            );

            while (!scanner.scan(5, fluidLookup, boundaryThreatLookup)) {
                // Je force plusieurs reprises pour vérifier toutes les rotations.
            }

            assertAll(
                    direction.name(),
                    () -> assertEquals(expected, scanner.result()),
                    () -> assertTrue(scanner.isComplete()),
                    () -> assertEquals(
                            scanner.totalInspections(),
                            scanner.inspectionsCompleted()
                    )
            );
        }
    }

    @Test
    void incrementalFluidScannerNeverExceedsTheLookupBudgetOfAPass() {
        Volume volume = new Volume(0, 3, 0, 2, 0, 4);
        FluidScanner scanner = TunnelInfrastructureBuilder.createFluidScanner(
                volume,
                BlockFace.SOUTH,
                true
        );
        AtomicInteger lookups = new AtomicInteger();
        Predicate<BlockPos> lookup = position -> {
            lookups.incrementAndGet();
            return false;
        };
        int passes = 0;

        while (!scanner.isComplete()) {
            int before = lookups.get();
            boolean complete = scanner.scan(7, lookup, lookup);
            int consumed = lookups.get() - before;

            assertTrue(consumed <= 7);
            if (!complete) {
                assertEquals(7, consumed);
            }
            assertEquals(
                    lookups.get(),
                    scanner.inspectionsCompleted()
            );
            passes++;
        }

        int completedPasses = passes;
        assertAll(
                () -> assertTrue(completedPasses > 1),
                () -> assertEquals(134L, scanner.totalInspections()),
                () -> assertEquals(134, lookups.get()),
                () -> {
                    scanner.scan(7, lookup, lookup);
                    assertEquals(134, lookups.get());
                },
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> scanner.scan(0, lookup, lookup)
                )
        );
    }

    @Test
    void reportsFluidInspectionBudgetBoundariesInEveryDirection() {
        for (BlockFace direction : cardinalDirections()) {
            Volume withoutEntranceBelow = orientedVolume(
                    1, 70, 4_739, direction
            );
            Volume withoutEntranceAtLimit = orientedVolume(
                    1, 284, 1_172, direction
            );
            Volume withoutEntranceAbove = orientedVolume(
                    1, 1, 250_000, direction
            );
            Volume withEntranceBelow = orientedVolume(
                    25, 42, 861, direction
            );
            Volume withEntranceAtLimit = orientedVolume(
                    1, 371, 897, direction
            );
            Volume withEntranceAbove = orientedVolume(
                    1, 6, 52_631, direction
            );

            assertAll(
                    direction.name(),
                    () -> assertEquals(
                            999_999L,
                            TunnelInfrastructureBuilder.fluidInspectionCount(
                                    withoutEntranceBelow,
                                    direction,
                                    false
                            )
                    ),
                    () -> assertTrue(
                            TunnelInfrastructureBuilder
                                    .isFluidInspectionPlanWithinBudget(
                                            withoutEntranceBelow,
                                            direction,
                                            false
                                    ),
                            "999 999 inspections sans entrée"
                    ),
                    () -> assertEquals(
                            1_000_000L,
                            TunnelInfrastructureBuilder.fluidInspectionCount(
                                    withoutEntranceAtLimit,
                                    direction,
                                    false
                            )
                    ),
                    () -> assertTrue(
                            TunnelInfrastructureBuilder
                                    .isFluidInspectionPlanWithinBudget(
                                            withoutEntranceAtLimit,
                                            direction,
                                            false
                                    ),
                            "1 000 000 inspections sans entrée"
                    ),
                    () -> assertEquals(
                            1_000_001L,
                            TunnelInfrastructureBuilder.fluidInspectionCount(
                                    withoutEntranceAbove,
                                    direction,
                                    false
                            )
                    ),
                    () -> assertFalse(
                            TunnelInfrastructureBuilder
                                    .isFluidInspectionPlanWithinBudget(
                                            withoutEntranceAbove,
                                            direction,
                                            false
                                    ),
                            "1 000 001 inspections sans entrée"
                    ),
                    () -> assertEquals(
                            999_999L,
                            TunnelInfrastructureBuilder.fluidInspectionCount(
                                    withEntranceBelow,
                                    direction,
                                    true
                            )
                    ),
                    () -> assertTrue(
                            TunnelInfrastructureBuilder
                                    .isFluidInspectionPlanWithinBudget(
                                            withEntranceBelow,
                                            direction,
                                            true
                                    ),
                            "999 999 inspections avec entrée"
                    ),
                    () -> assertEquals(
                            1_000_000L,
                            TunnelInfrastructureBuilder.fluidInspectionCount(
                                    withEntranceAtLimit,
                                    direction,
                                    true
                            )
                    ),
                    () -> assertTrue(
                            TunnelInfrastructureBuilder
                                    .isFluidInspectionPlanWithinBudget(
                                            withEntranceAtLimit,
                                            direction,
                                            true
                                    ),
                            "1 000 000 inspections avec entrée"
                    ),
                    () -> assertEquals(
                            1_000_001L,
                            TunnelInfrastructureBuilder.fluidInspectionCount(
                                    withEntranceAbove,
                                    direction,
                                    true
                            )
                    ),
                    () -> assertFalse(
                            TunnelInfrastructureBuilder
                                    .isFluidInspectionPlanWithinBudget(
                                            withEntranceAbove,
                                            direction,
                                            true
                                    ),
                            "1 000 001 inspections avec entrée"
                    )
            );
        }
    }

    @Test
    void incrementalFluidScannerResumesAtTheExactLookupAndPublishesImmutableData() {
        Volume volume = new Volume(4, 7, 20, 22, -3, 1);
        List<String> synchronousLookups = new ArrayList<>();
        Predicate<BlockPos> synchronousFluid = position -> {
            synchronousLookups.add("fluid:" + position);
            return Math.floorMod(position.x() + position.y() + position.z(), 4)
                    == 0;
        };
        Predicate<BlockPos> synchronousThreat = position -> {
            synchronousLookups.add("threat:" + position);
            return Math.floorMod(position.x() * 3 + position.z(), 5) == 0;
        };
        FluidPlan expected = TunnelInfrastructureBuilder.planFluids(
                volume,
                BlockFace.WEST,
                true,
                synchronousFluid,
                synchronousThreat
        );

        FluidScanner scanner = TunnelInfrastructureBuilder.createFluidScanner(
                volume,
                BlockFace.WEST,
                true
        );
        List<String> incrementalLookups = new ArrayList<>();
        Predicate<BlockPos> incrementalFluid = position -> {
            incrementalLookups.add("fluid:" + position);
            return Math.floorMod(position.x() + position.y() + position.z(), 4)
                    == 0;
        };
        Predicate<BlockPos> incrementalThreat = position -> {
            incrementalLookups.add("threat:" + position);
            return Math.floorMod(position.x() * 3 + position.z(), 5) == 0;
        };
        int[] budgets = {1, 4, 2, 9, 3};
        int pass = 0;

        assertThrows(IllegalStateException.class, scanner::result);
        while (!scanner.scan(
                budgets[pass++ % budgets.length],
                incrementalFluid,
                incrementalThreat
        )) {
            // Je reprends volontairement avec des budgets irréguliers.
        }

        FluidPlan result = scanner.result();
        assertAll(
                () -> assertEquals(synchronousLookups, incrementalLookups),
                () -> assertEquals(expected, result),
                () -> assertEquals(result, scanner.result()),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> result.drainedInterior().clear()
                ),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> result.permanentSeals().clear()
                ),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> result.temporarySeals().clear()
                )
        );
    }

    @Test
    void usesFluidLookupForTheEntranceAndThreatLookupForOtherBoundaries() {
        Volume volume = new Volume(0, 2, 0, 2, 0, 2);
        BlockPos interiorFluid = new BlockPos(1, 1, 1);
        BlockPos entranceOpening = new BlockPos(1, 1, -1);
        BlockPos sideOpening = new BlockPos(-1, 1, 1);
        BlockPos frontOpening = new BlockPos(1, 1, 3);
        Set<BlockPos> threats = Set.of(
                entranceOpening,
                sideOpening,
                frontOpening
        );

        FluidPlan plan = TunnelInfrastructureBuilder.planFluids(
                volume,
                BlockFace.SOUTH,
                true,
                interiorFluid::equals,
                threats::contains
        );

        assertAll(
                () -> assertEquals(Set.of(interiorFluid), plan.drainedInterior()),
                () -> assertEquals(
                        Set.of(new BlockPos(0, 1, 1)),
                        positions(plan.permanentSeals())
                ),
                () -> assertFalse(positions(plan.permanentSeals()).contains(
                        new BlockPos(1, 1, 0)
                )),
                () -> assertEquals(
                        Set.of(new BlockPos(1, 1, 2)),
                        positions(plan.temporarySeals())
                )
        );
    }

    @Test
    void givesPermanentBoundarySealsPriorityAndNeverDuplicatesPositions() {
        Volume volume = new Volume(0, 4, 0, 2, 0, 4);

        FluidPlan fluidPlan = TunnelInfrastructureBuilder.planFluids(
                volume,
                BlockFace.SOUTH,
                false,
                position -> true
        );
        List<Placement> allSeals = fluidPlan.allSeals();

        assertAll(
                () -> assertEquals(volume.blockCount(),
                        fluidPlan.drainedInterior().size()),
                () -> assertEquals(allSeals.size(), positions(allSeals).size()),
                () -> assertTrue(disjoint(
                        positions(fluidPlan.permanentSeals()),
                        positions(fluidPlan.temporarySeals())
                )),
                () -> assertFalse(positions(fluidPlan.temporarySeals()).contains(
                        new BlockPos(0, 1, 4)
                )),
                () -> assertTrue(positions(fluidPlan.permanentSeals()).contains(
                        new BlockPos(0, 1, 4)
                ))
        );

        Plan decorationPlan = TunnelInfrastructureBuilder.planDecorations(
                volume,
                BlockFace.SOUTH,
                false,
                new Settings(2, 0, 1, 3, 3, 1_000),
                fluidPlan.permanentSeals()
        );
        assertEquals(
                decorationPlan.placements().size(),
                positions(decorationPlan.placements()).size()
        );
        assertTrue(decorationPlan.placementsOfKind(
                PlacementKind.PERMANENT_SEAL
        ).isEmpty());
        assertTrue(disjoint(
                positions(fluidPlan.permanentSeals()),
                positions(decorationPlan.placements())
        ));
    }

    @Test
    void buildsEquivalentInfrastructureInEveryCardinalDirection() {
        Volume volume = new Volume(-4, 4, 20, 22, -4, 4);
        Map<PlacementKind, Long> baseline = null;

        for (BlockFace direction : cardinalDirections()) {
            Plan plan = TunnelInfrastructureBuilder.planDecorations(
                    volume,
                    direction,
                    true,
                    ROOM_SETTINGS,
                    List.of()
            );
            Map<PlacementKind, Long> counts = countsByKind(plan);
            if (baseline == null) {
                baseline = counts;
            } else {
                assertEquals(baseline, counts, direction.name());
            }

            assertAll(
                    direction.name(),
                    () -> assertTrue(plan.placements().stream().allMatch(
                            placement -> volume.contains(placement.position())
                    )),
                    () -> assertEquals(2L, counts.get(PlacementKind.CHEST)),
                    () -> assertEquals(
                            2L,
                            counts.get(PlacementKind.WORKSTATION)
                    ),
                    () -> assertTrue(counts.get(PlacementKind.LIGHT) > 0L),
                    () -> assertTrue(counts.get(PlacementKind.PLATFORM) > 0L),
                    () -> assertEquals(
                            plan.placements().size(),
                            positions(plan.placements()).size()
                    )
            );
        }
    }

    @Test
    void keepsTheCentralAisleClearWhileAddingFramesAndRoomFurniture() {
        Volume volume = new Volume(0, 8, 10, 12, 0, 9);

        for (BlockFace direction : cardinalDirections()) {
            Plan plan = TunnelInfrastructureBuilder.planDecorations(
                    volume,
                    direction,
                    true,
                    ROOM_SETTINGS,
                    List.of()
            );

            for (Placement placement : plan.placements()) {
                long transverse = transverseIndex(
                        volume,
                        direction,
                        placement.position()
                );
                boolean blocksPassage = placement.position().y()
                        <= volume.minY() + 1
                        && transverse >= 3L
                        && transverse <= 5L;
                assertFalse(
                        blocksPassage,
                        direction + " bloque l'allée avec " + placement
                );
            }

            assertTrue(plan.placementsOfKind(PlacementKind.SUPPORT_POST)
                    .stream()
                    .allMatch(placement -> placement.material()
                            == Material.SPRUCE_FENCE));
            assertTrue(plan.placementsOfKind(PlacementKind.SUPPORT_BEAM)
                    .stream()
                    .allMatch(placement -> placement.material()
                            == Material.STRIPPED_SPRUCE_LOG));
        }
    }

    @Test
    void startsSupportFramesAtTheConfiguredOffset() {
        Volume volume = new Volume(0, 6, 10, 12, 10, 19);
        Settings settings = new Settings(3, 2, 1, 3, 3, 1_000);

        Plan plan = TunnelInfrastructureBuilder.planDecorations(
                volume,
                BlockFace.SOUTH,
                false,
                settings,
                List.of()
        );

        Set<Integer> supportDepths = new LinkedHashSet<>();
        for (Placement placement
                : plan.placementsOfKind(PlacementKind.SUPPORT_POST)) {
            supportDepths.add(placement.position().z() - volume.minZ());
        }
        assertEquals(Set.of(2, 5, 8), supportDepths);
    }

    @Test
    void keepsDecoratingWhenARequiredEntranceSealCrossesTheCentralAisle() {
        Volume volume = new Volume(0, 8, 10, 14, 0, 8);
        BlockPos floodedEntrance = new BlockPos(4, 11, -1);
        FluidPlan fluids = TunnelInfrastructureBuilder.planFluids(
                volume,
                BlockFace.SOUTH,
                true,
                floodedEntrance::equals
        );

        Plan plan = TunnelInfrastructureBuilder.planDecorations(
                volume,
                BlockFace.SOUTH,
                true,
                ROOM_SETTINGS,
                fluids.permanentSeals()
        );

        BlockPos reservedSeal = new BlockPos(4, 11, 0);
        assertAll(
                () -> assertFalse(positions(plan.placements()).contains(
                        reservedSeal
                )),
                () -> assertTrue(plan.placementsOfKind(
                        PlacementKind.PERMANENT_SEAL
                ).isEmpty()),
                () -> assertEquals(
                        2,
                        plan.placementsOfKind(PlacementKind.CHEST).size()
                )
        );
    }

    @Test
    void reservesManyPermanentSealsWithoutChargingOrReturningThem() {
        Volume volume = new Volume(0, 19, 0, 4, 0, 19);
        List<Placement> permanentSeals = new ArrayList<>();
        for (int z = 0; z <= 19 && permanentSeals.size() < 201; z++) {
            if (z == 3 || z == 8 || z == 13 || z == 18) {
                continue;
            }
            for (int x = 1; x <= 18 && permanentSeals.size() < 201; x++) {
                permanentSeals.add(new Placement(
                        new BlockPos(x, 1, z),
                        Material.LIGHT_BLUE_STAINED_GLASS,
                        PlacementKind.PERMANENT_SEAL
                ));
            }
        }

        Plan plan = TunnelInfrastructureBuilder.planDecorations(
                volume,
                BlockFace.SOUTH,
                false,
                new Settings(5, 3, 1, 3, 4, 112),
                permanentSeals
        );

        assertAll(
                () -> assertEquals(201, permanentSeals.size()),
                () -> assertEquals(112, plan.placements().size()),
                () -> assertTrue(plan.placementsOfKind(
                        PlacementKind.PERMANENT_SEAL
                ).isEmpty()),
                () -> assertTrue(disjoint(
                        positions(permanentSeals),
                        positions(plan.placements())
                ))
        );
    }

    @Test
    void equipsArrivalRoomWithSeparateChestsWorkstationsAndIntegratedLights() {
        Volume volume = new Volume(0, 8, 10, 12, 0, 9);
        Plan plan = TunnelInfrastructureBuilder.planDecorations(
                volume,
                BlockFace.SOUTH,
                true,
                ROOM_SETTINGS,
                List.of()
        );

        List<Placement> chests = plan.placementsOfKind(PlacementKind.CHEST);
        List<Placement> workstations = plan.placementsOfKind(
                PlacementKind.WORKSTATION
        );

        assertAll(
                () -> assertEquals(2, chests.size()),
                () -> assertNotEquals(
                        chests.get(0).position(),
                        chests.get(1).position()
                ),
                () -> assertTrue(horizontalDistance(
                        chests.get(0).position(),
                        chests.get(1).position()
                ) > 1L),
                () -> assertTrue(chests.stream().allMatch(
                        placement -> placement.material() == Material.CHEST
                )),
                () -> assertEquals(
                        Set.of(Material.CRAFTING_TABLE, Material.BLAST_FURNACE),
                        materials(workstations)
                ),
                () -> assertTrue(plan.placementsOfKind(PlacementKind.PLATFORM)
                        .stream()
                        .allMatch(placement -> placement.material()
                                == Material.SPRUCE_PLANKS)),
                () -> assertTrue(plan.placementsOfKind(PlacementKind.LIGHT)
                        .stream()
                        .allMatch(placement -> placement.material()
                                == Material.SHROOMLIGHT)),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> plan.placements().clear()
                )
        );
    }

    @Test
    void acceptsExplicitSealMaterialsWithoutWorldAccess() {
        Volume volume = new Volume(0, 2, 0, 2, 0, 2);
        BlockPos exterior = new BlockPos(-1, 1, 1);

        FluidPlan plan = TunnelInfrastructureBuilder.planFluids(
                volume,
                BlockFace.SOUTH,
                false,
                Material.OBSIDIAN,
                Material.BLUE_STAINED_GLASS,
                exterior::equals
        );

        assertEquals(1, plan.permanentSeals().size());
        assertEquals(Material.OBSIDIAN,
                plan.permanentSeals().get(0).material());
        assertTrue(plan.temporarySeals().isEmpty());
    }

    @Test
    void rejectsPlansThatExceedBudgetsOrIntegerCoordinates() {
        AtomicInteger fluidLookups = new AtomicInteger();
        Volume excessiveFluidVolume = new Volume(0, 999, 0, 1, 0, 999);

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TunnelInfrastructureBuilder.planFluids(
                                excessiveFluidVolume,
                                BlockFace.SOUTH,
                                false,
                                position -> {
                                    fluidLookups.incrementAndGet();
                                    return false;
                                }
                        )
                ),
                () -> assertEquals(0, fluidLookups.get()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TunnelInfrastructureBuilder.planDecorations(
                                new Volume(0, 8, 0, 2, 0, 9),
                                BlockFace.SOUTH,
                                true,
                                new Settings(1, 0, 1, 3, 4, 20),
                                List.of()
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TunnelInfrastructureBuilder.planFluids(
                                new Volume(
                                        0,
                                        0,
                                        0,
                                        0,
                                        Integer.MAX_VALUE,
                                        Integer.MAX_VALUE
                                ),
                                BlockFace.SOUTH,
                                false,
                                position -> false
                        )
                )
        );
    }

    @Test
    void returnsDefensiveImmutableCollectionsAndRejectsOutOfBoundsSeals() {
        Volume volume = new Volume(0, 4, 0, 2, 0, 4);
        FluidPlan fluids = TunnelInfrastructureBuilder.planFluids(
                volume,
                BlockFace.SOUTH,
                false,
                position -> true
        );

        assertAll(
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> fluids.drainedInterior().clear()
                ),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> fluids.permanentSeals().clear()
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TunnelInfrastructureBuilder.planDecorations(
                                volume,
                                BlockFace.SOUTH,
                                false,
                                new Settings(2, 0, 1, 3, 3, 1_000),
                                List.of(new Placement(
                                        new BlockPos(-1, 1, 1),
                                        Material.LIGHT_BLUE_STAINED_GLASS,
                                        PlacementKind.PERMANENT_SEAL
                                ))
                        )
                )
        );
    }

    @Test
    void reconstructsTheRegularCorridorHistoryInEveryDirection() {
        Volume current = new Volume(100, 109, 20, 22, 200, 209);

        assertAll(
                () -> assertEquals(
                        new Volume(100, 109, 20, 22, 200, 229),
                        TunnelInfrastructureBuilder.historicalCorridorVolume(
                                current,
                                BlockFace.NORTH,
                                3
                        )
                ),
                () -> assertEquals(
                        new Volume(100, 109, 20, 22, 180, 209),
                        TunnelInfrastructureBuilder.historicalCorridorVolume(
                                current,
                                BlockFace.SOUTH,
                                3
                        )
                ),
                () -> assertEquals(
                        new Volume(100, 129, 20, 22, 200, 209),
                        TunnelInfrastructureBuilder.historicalCorridorVolume(
                                current,
                                BlockFace.WEST,
                                3
                        )
                ),
                () -> assertEquals(
                        new Volume(80, 109, 20, 22, 200, 209),
                        TunnelInfrastructureBuilder.historicalCorridorVolume(
                                current,
                                BlockFace.EAST,
                                3
                        )
                ),
                () -> assertEquals(
                        current,
                        TunnelInfrastructureBuilder.historicalCorridorVolume(
                                current,
                                BlockFace.SOUTH,
                                1
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TunnelInfrastructureBuilder.historicalCorridorVolume(
                                current,
                                BlockFace.SOUTH,
                                0
                        )
                )
        );
    }

    private static Set<BlockPos> positions(List<Placement> placements) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        for (Placement placement : placements) {
            positions.add(placement.position());
        }
        return positions;
    }

    private static Set<Material> materials(List<Placement> placements) {
        Set<Material> materials = new HashSet<>();
        for (Placement placement : placements) {
            materials.add(placement.material());
        }
        return materials;
    }

    private static boolean disjoint(Set<BlockPos> first, Set<BlockPos> second) {
        Set<BlockPos> intersection = new HashSet<>(first);
        intersection.retainAll(second);
        return intersection.isEmpty();
    }

    private static Map<PlacementKind, Long> countsByKind(Plan plan) {
        Map<PlacementKind, Long> counts = new EnumMap<>(PlacementKind.class);
        for (PlacementKind kind : PlacementKind.values()) {
            counts.put(kind, (long) plan.placementsOfKind(kind).size());
        }
        return counts;
    }

    private static List<BlockFace> cardinalDirections() {
        return new ArrayList<>(List.of(
                BlockFace.NORTH,
                BlockFace.SOUTH,
                BlockFace.EAST,
                BlockFace.WEST
        ));
    }

    private static Volume orientedVolume(int width,
                                         int height,
                                         int length,
                                         BlockFace direction) {
        return direction == BlockFace.NORTH || direction == BlockFace.SOUTH
                ? new Volume(0, width - 1, 0, height - 1, 0, length - 1)
                : new Volume(0, length - 1, 0, height - 1, 0, width - 1);
    }

    private static long transverseIndex(Volume volume,
                                        BlockFace direction,
                                        BlockPos position) {
        return direction == BlockFace.NORTH || direction == BlockFace.SOUTH
                ? (long) position.x() - volume.minX()
                : (long) position.z() - volume.minZ();
    }

    private static long horizontalDistance(BlockPos first, BlockPos second) {
        return Math.abs((long) first.x() - second.x())
                + Math.abs((long) first.z() - second.z());
    }

    private record DirectionCase(BlockFace direction,
                                 BlockPos entranceExterior,
                                 BlockPos entranceInterior,
                                 BlockPos frontExterior,
                                 BlockPos frontInterior) {
    }
}
