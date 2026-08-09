package org.example.tutorial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TutorialWorldGeneratorTest {

    @Test
    void completeTourAreaIsAStablePlain() {
        long seed = 987_654_321L;

        for (int x = -90; x <= 300; x += 7) {
            for (int z = -100; z <= 100; z += 7) {
                assertTrue(TutorialWorldGenerator.isPlainCoordinate(x, z));
                assertEquals(
                        TutorialWorldGenerator.GROUND_Y,
                        TutorialWorldGenerator.surfaceY(seed, x, z)
                );
            }
        }
    }

    @Test
    void distantTerrainCreatesVisibleCliffs() {
        long seed = 42L;

        int edge = TutorialWorldGenerator.surfaceY(seed, 300, 0);
        int nearTransition =
                TutorialWorldGenerator.surfaceY(seed, 320, 0);
        int horizon =
                TutorialWorldGenerator.surfaceY(seed, 390, 0);

        assertEquals(TutorialWorldGenerator.GROUND_Y, edge);
        assertTrue(nearTransition >= edge);
        assertTrue(
                horizon >= TutorialWorldGenerator.GROUND_Y + 20,
                "Les falaises lointaines doivent dépasser franchement la plaine."
        );
    }

    @Test
    void generatedHeightIsDeterministic() {
        long seed = 123L;
        int first = TutorialWorldGenerator.surfaceY(seed, 415, -170);

        for (int i = 0; i < 20; i++) {
            assertEquals(
                    first,
                    TutorialWorldGenerator.surfaceY(seed, 415, -170)
            );
        }
    }
}
