package org.example.village;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class VillageLayoutPlannerTest {

    private static VillageLayoutSettings settings() {
        return new VillageLayoutSettings("semi_organic", 4, 5, 9, 11, 20, 2, 9, 10, 16, 2, 1, 2, "medium");
    }

    @Test
    void churchForgeAndMarketAreAlwaysPresent() {
        World world = mock(World.class);
        VillageLayoutPlan plan = VillageLayoutPlanner.plan(new Location(world, 0, 64, 0), settings(), new Random(1));

        assertTrue(plan.lots().stream().anyMatch(l -> l.role() == VillageLayoutPlan.LotRole.CHURCH));
        assertTrue(plan.lots().stream().anyMatch(l -> l.role() == VillageLayoutPlan.LotRole.FORGE));
        assertTrue(plan.lots().stream().anyMatch(l -> l.role() == VillageLayoutPlan.LotRole.MARKET));
        assertTrue(plan.anchors().containsKey("farm"));
        assertTrue(plan.anchors().containsKey("pen"));
    }

    @Test
    void plannerExposesAllHouseArchetypesAcrossStableSeeds() {
        World world = mock(World.class);
        EnumSet<VillageLayoutPlan.HouseArchetype> archetypes = EnumSet.noneOf(VillageLayoutPlan.HouseArchetype.class);
        for (int seed = 1; seed <= 20; seed++) {
            VillageLayoutPlan plan = VillageLayoutPlanner.plan(new Location(world, 0, 64, 0), settings(), new Random(seed));
            plan.lots().stream()
                    .filter(VillageLayoutPlan.LotPlan::isHouse)
                    .map(lot -> lot.houseSpec().archetype())
                    .forEach(archetypes::add);
        }
        assertEquals(EnumSet.allOf(VillageLayoutPlan.HouseArchetype.class), archetypes);
    }

    @Test
    void lotsKeepRoadAccessAndDoNotOverlap() {
        World world = mock(World.class);
        VillageLayoutPlan plan = VillageLayoutPlanner.plan(new Location(world, 0, 64, 0), settings(), new Random(7));

        assertTrue(plan.houseCount() >= 10 && plan.houseCount() <= 16);
        assertTrue(plan.landmarks().size() == 3);
        assertTrue(plan.streets().size() >= 4);
        assertTrue(plan.lots().stream().allMatch(VillageLayoutPlanner::hasRoadAccess));
        for (int i = 0; i < plan.lots().size(); i++) {
            for (int j = i + 1; j < plan.lots().size(); j++) {
                assertTrue(!plan.lots().get(i).overlapsWithGap(plan.lots().get(j), 4),
                        "Overlap ou ecart insuffisant entre lots " + i + " et " + j);
            }
        }
        assertTrue(plan.lots().stream().anyMatch(lot -> lot.terraceY() > 0));
        assertTrue(plan.bounds().maxX() > plan.bounds().minX());
    }
}
