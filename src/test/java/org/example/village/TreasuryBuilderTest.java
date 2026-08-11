package org.example.village;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TreasuryBuilderTest {

    @Test
    void plansSixtyDistinctChestsAndFourFixedGuardPosts() {
        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);

        Queue<Runnable> queue = new LinkedList<>();
        TreasuryBuilder.Plan plan = TreasuryBuilder.build(
                new Location(world, 0.0D, 64.0D, 0.0D),
                24,
                64,
                queue,
                (x, y, z, material) -> {
                    // Le test valide le plan ; les actions Bukkit sont différées.
                }
        );

        assertNotNull(plan);
        assertEquals(60, plan.containers().size());
        assertEquals(
                60,
                new HashSet<>(plan.containers()).size(),
                "Chaque coffre fiscal doit posséder une coordonnée unique."
        );
        assertEquals(4, plan.guardAnchors().size());
        assertEquals(2, plan.doors().size());
        assertFalse(queue.isEmpty());
        assertTrue(plan.protectedZones().stream()
                .anyMatch(zone -> zone.intersects(plan.roomBounds())));
        assertTrue(plan.roomBounds().contains(plan.ledger().x(),
                plan.ledger().y(), plan.ledger().z()));
    }

    @Test
    void rejectsAWorldThatIsNotDeepEnough() {
        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(50);
        when(world.getMaxHeight()).thenReturn(320);

        assertFalse(TreasuryBuilder.fitsVertically(world, 64));
    }

    @Test
    void rejectsAWorldWithoutEnoughHeightForTheAccessArch() {
        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(68);

        assertFalse(TreasuryBuilder.fitsVertically(world, 64));
    }
}
