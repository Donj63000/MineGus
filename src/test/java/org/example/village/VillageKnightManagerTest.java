package org.example.village;

import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VillageKnightManagerTest {

    @Test
    void safelyRejectsAnIncompleteEntityWithoutPersistentData() {
        Entity entity = mock(Entity.class);
        when(entity.getPersistentDataContainer()).thenReturn(null);
        when(entity.getScoreboardTags()).thenReturn(Set.of());

        assertFalse(VillageKnightManager.isVillageKnight(entity));
    }

    @Test
    void acceptsTheScoreboardFallbackWithoutPersistentData() {
        Entity entity = mock(Entity.class);
        when(entity.getPersistentDataContainer()).thenReturn(null);
        when(entity.getScoreboardTags()).thenReturn(
                Set.of("MG_VILLAGE_KNIGHT")
        );

        assertTrue(VillageKnightManager.isVillageKnight(entity));
    }
}
