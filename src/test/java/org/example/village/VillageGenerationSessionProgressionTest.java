package org.example.village;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VillageGenerationSessionProgressionTest {

    @Test
    void firstArtificialStateIsPreservedForRollback() {
        World world = mock(World.class);
        BlockData first = mock(BlockData.class);
        BlockData firstCopy = mock(BlockData.class);
        BlockData second = mock(BlockData.class);
        BlockData secondCopy = mock(BlockData.class);
        when(first.clone()).thenReturn(firstCopy);
        when(second.clone()).thenReturn(secondCopy);

        VillageGenerationSession session =
                new VillageGenerationSession(42);
        Location location = new Location(world, 10.8D, 64.0D, -3.2D);

        session.rememberOriginal(location, first, true);
        session.rememberOriginal(location, second, false);

        assertEquals(1, session.getOriginalBlocks().size());
        assertTrue(session.wasOriginalArtificial(location));
        assertFalse(session.wasOriginalArtificial(
                new Location(world, 11.0D, 64.0D, -3.0D)));
    }
}
