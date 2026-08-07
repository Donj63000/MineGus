package org.example.village;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class VillageLayoutPlannerTest {

    private static VillageLayoutSettings settings() {
        return new VillageLayoutSettings(
                "semi_organic",
                4,
                5,
                9,
                11,
                20,
                2,
                13,
                12,
                16,
                2,
                1,
                2,
                "high"
        );
    }

    @Test
    void essentialDistrictsAndAnchorsAreAlwaysPresent() {
        World world = mock(World.class);
        VillageLayoutPlan plan = VillageLayoutPlanner.plan(
                new Location(world, 0, 64, 0),
                settings(),
                new Random(1)
        );

        EnumSet<VillageLayoutPlan.LotRole> presentRoles =
                EnumSet.noneOf(VillageLayoutPlan.LotRole.class);
        plan.lots().stream()
                .map(VillageLayoutPlan.LotPlan::role)
                .forEach(presentRoles::add);

        assertTrue(presentRoles.containsAll(EnumSet.of(
                VillageLayoutPlan.LotRole.CHURCH,
                VillageLayoutPlan.LotRole.FORGE,
                VillageLayoutPlan.LotRole.INN,
                VillageLayoutPlan.LotRole.BAKERY,
                VillageLayoutPlan.LotRole.FARM,
                VillageLayoutPlan.LotRole.PEN,
                VillageLayoutPlan.LotRole.MARKET,
                VillageLayoutPlan.LotRole.GREEN,
                VillageLayoutPlan.LotRole.DECOR,
                VillageLayoutPlan.LotRole.SERVICE_YARD
        )));
        assertTrue(plan.anchors().keySet().containsAll(Set.of(
                "center",
                "plaza",
                "gate",
                "church",
                "forge",
                "inn",
                "bakery",
                "market",
                "farm",
                "pen",
                "service_yard",
                "mayor"
        )));

        // Une ancre désigne toujours le bloc de sol ; les gestionnaires
        // d'entités appliquent eux-mêmes le décalage vertical de spawn.
        assertEquals(64, plan.anchors().get("plaza").getBlockY());
        for (VillageLayoutPlan.LotPlan lot : plan.lots()) {
            String key = switch (lot.role()) {
                case CHURCH -> "church";
                case FORGE -> "forge";
                case INN -> "inn";
                case BAKERY -> "bakery";
                case FARM -> "farm";
                case PEN -> "pen";
                case SERVICE_YARD -> "service_yard";
                default -> null;
            };
            if (key != null) {
                assertEquals(
                        64 + lot.terraceY() + 1,
                        plan.anchors().get(key).getBlockY()
                );
            }
        }
    }

    @Test
    void plannerExposesAllHouseArchetypesAcrossStableSeeds() {
        World world = mock(World.class);
        EnumSet<VillageLayoutPlan.HouseArchetype> archetypes =
                EnumSet.noneOf(VillageLayoutPlan.HouseArchetype.class);

        for (int seed = 1; seed <= 20; seed++) {
            VillageLayoutPlan plan = VillageLayoutPlanner.plan(
                    new Location(world, 0, 64, 0),
                    settings(),
                    new Random(seed)
            );
            plan.lots().stream()
                    .filter(VillageLayoutPlan.LotPlan::isHouse)
                    .map(lot -> lot.houseSpec().archetype())
                    .forEach(archetypes::add);
        }

        assertEquals(EnumSet.allOf(VillageLayoutPlan.HouseArchetype.class), archetypes);
    }

    @Test
    void lotsKeepRealRoadAccessAndArchitecturalClearance() {
        World world = mock(World.class);

        for (int seed = 0; seed < 200; seed++) {
            VillageLayoutPlan plan = VillageLayoutPlanner.plan(
                    new Location(world, 0, 64, 0),
                    settings(),
                    new Random(seed)
            );

            assertTrue(plan.houseCount() >= 12 && plan.houseCount() <= 16);
            assertEquals(3, plan.landmarks().size());
            assertTrue(plan.streets().size() >= 9);
            assertTrue(plan.lots().stream().allMatch(
                    lot -> VillageLayoutPlanner.hasRoadAccess(lot, plan.streets())
            ));

            for (int i = 0; i < plan.lots().size(); i++) {
                for (int j = i + 1; j < plan.lots().size(); j++) {
                    assertFalse(
                            plan.lots().get(i).overlapsWithGap(plan.lots().get(j), 4),
                            "Chevauchement ou recul insuffisant entre les lots "
                                    + i + " et " + j + " pour la graine " + seed
                    );
                }
            }

            assertTrue(plan.lots().stream().anyMatch(lot -> lot.terraceY() > 0));
            assertTrue(plan.bounds().maxX() > plan.bounds().minX());
            assertTrue(plan.bounds().maxZ() > plan.bounds().minZ());
        }
    }

    @Test
    void legacyConfigurationIsNormalizedWithoutInflatingTheGrid() {
        VillageLayoutSettings legacy = new VillageLayoutSettings(
                "semi_organic",
                2,
                2,
                9,
                11,
                20,
                0,
                9,
                10,
                8,
                1,
                0,
                12,
                " HIGH "
        );

        assertEquals(3, legacy.rows());
        assertEquals(3, legacy.cols());
        assertEquals(13, legacy.effectivePlazaSize());
        assertEquals(20, legacy.effectiveLotSpacing());
        assertEquals(10, legacy.houseCountMax());
        assertEquals(3, legacy.terrainMaxStep());
        assertEquals("high", legacy.decorDensity());
        assertEquals(42, legacy.decorationBudget());
        assertEquals(10, legacy.treeBudget());
    }
}
