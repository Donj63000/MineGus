package org.example.progression;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PlacedBlockTrackerTest {

    @Test
    void positionEncodingSeparatesLocalCoordinatesAndNegativeHeights() {
        Set<Integer> positions = new HashSet<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                positions.add(PlacedBlockTracker.encodePosition(
                        x,
                        -64,
                        z));
                positions.add(PlacedBlockTracker.encodePosition(
                        x,
                        319,
                        z));
            }
        }

        assertEquals(512, positions.size());
        assertNotEquals(
                PlacedBlockTracker.encodePosition(0, -64, 0),
                PlacedBlockTracker.encodePosition(0, 319, 0));
    }
}
