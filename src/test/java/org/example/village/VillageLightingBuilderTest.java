package org.example.village;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageLightingBuilderTest {

    @Test
    void decorativeLightsStayInsideWallAndOutsideReservedInfrastructure() {
        VillageLayoutSettings settings = new VillageLayoutSettings(
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
        Location center = new Location(null, 0, 64, 0);
        VillageLayoutPlan layout = VillageLayoutPlanner.plan(
                center,
                settings,
                new Random(17)
        );
        VillageLayoutPlan.Bounds bounds = layout.bounds();
        int rx = Math.max(
                Math.abs(bounds.minX() - center.getBlockX()),
                Math.abs(bounds.maxX() - center.getBlockX())
        ) + 7;
        int rz = Math.max(
                Math.abs(bounds.minZ() - center.getBlockZ()),
                Math.abs(bounds.maxZ() - center.getBlockZ())
        ) + 7;

        List<VillageLightingBuilder.DecorativeLightAnchor> anchors =
                VillageLightingBuilder.planDecorativeLights(
                        center,
                        layout,
                        rx,
                        rz,
                        14
                );

        long wellLights = anchors.stream()
                .filter(anchor -> anchor.kind()
                        == VillageLightingBuilder.DecorativeLightKind.WELL_HANGING)
                .count();
        long perimeterLights = anchors.stream()
                .filter(anchor -> anchor.kind()
                        == VillageLightingBuilder.DecorativeLightKind.PERIMETER_BOLLARD)
                .count();

        assertEquals(4, wellLights);
        assertTrue(perimeterLights >= 4);

        Set<String> positions = new HashSet<>();
        for (VillageLightingBuilder.DecorativeLightAnchor anchor : anchors) {
            assertTrue(positions.add(anchor.x() + ":" + anchor.z()));

            if (anchor.kind()
                    != VillageLightingBuilder.DecorativeLightKind.PERIMETER_BOLLARD) {
                continue;
            }

            assertTrue(anchor.x() > center.getBlockX() - rx);
            assertTrue(anchor.x() < center.getBlockX() + rx);
            assertTrue(anchor.z() > center.getBlockZ() - rz);
            assertTrue(anchor.z() < center.getBlockZ() + rz);

            // Le parvis du portail sud reste praticable.
            assertFalse(
                    anchor.z() >= center.getBlockZ() + rz - 6
                            && Math.abs(anchor.x() - center.getBlockX()) <= 6
            );

            // Le porche et les quatre tours du donjon restent libres.
            assertFalse(KeepBuilder.reservesGround(
                    center.getBlockX(),
                    center.getBlockZ(),
                    rz,
                    anchor.x(),
                    anchor.z(),
                    2
            ));

            for (VillageLayoutPlan.LotPlan lot : layout.lots()) {
                boolean overlaps = anchor.x() >= lot.siteMinX() - 2
                        && anchor.x() <= lot.siteMaxX() + 2
                        && anchor.z() >= lot.siteMinZ() - 2
                        && anchor.z() <= lot.siteMaxZ() + 2;
                assertFalse(overlaps);
            }
            for (VillageLayoutPlan.StreetPlan street : layout.streets()) {
                assertFalse(street.contains(anchor.x(), anchor.z(), 2));
            }
        }
    }

    @Test
    void scanTilesCoverEveryCellExactlyOnce() {
        int minX = -19;
        int maxX = 23;
        int minZ = -17;
        int maxZ = 21;
        int tileSize = 7;
        List<VillageLightingBuilder.ScanTile> tiles =
                VillageLightingBuilder.createScanTiles(
                        minX,
                        maxX,
                        minZ,
                        maxZ,
                        tileSize
                );

        Map<String, Integer> coverage = new HashMap<>();
        for (VillageLightingBuilder.ScanTile tile : tiles) {
            assertTrue(tile.maxX() - tile.minX() + 1 <= tileSize);
            assertTrue(tile.maxZ() - tile.minZ() + 1 <= tileSize);
            for (int x = tile.minX(); x <= tile.maxX(); x++) {
                for (int z = tile.minZ(); z <= tile.maxZ(); z++) {
                    assertTrue(tile.contains(x, z));
                    coverage.merge(x + ":" + z, 1, Integer::sum);
                }
            }
        }

        int expectedCells = (maxX - minX + 1) * (maxZ - minZ + 1);
        assertEquals(expectedCells, coverage.size());
        assertTrue(coverage.values().stream().allMatch(count -> count == 1));
    }

    @Test
    void defaultLightLevelCoversOneSevenBlockTileWithSafetyMargin() {
        assertEquals(
                7,
                VillageLightingBuilder.conservativeCoverageRadius(12, 2)
        );
        assertEquals(
                0,
                VillageLightingBuilder.conservativeCoverageRadius(4, 1)
        );
    }
}
