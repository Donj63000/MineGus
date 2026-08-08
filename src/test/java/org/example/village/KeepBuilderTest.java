package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeepBuilderTest {

    private static final int BASE_Y = 64;

    @Test
    void keepReplacesNorthCurtainAndKeepsBothWallWalkConnectionsOpen() {
        Map<String, Material> blocks = new HashMap<>();
        Queue<Runnable> queue = new LinkedList<>();
        TerrainManager.SetBlock setBlock =
                (x, y, z, material) -> blocks.put(key(x, y, z), material);
        Location center = new Location(null, 0, BASE_Y, 0);

        WallBuilder.build(
                center,
                10,
                10,
                BASE_Y,
                Material.STONE_BRICKS,
                queue,
                setBlock
        );
        KeepBuilder.build(
                center,
                10,
                BASE_Y,
                Material.STONE_BRICKS,
                queue,
                setBlock
        );
        drain(queue);

        /*
         * La courtine subsiste juste avant les tours du donjon. Les cellules
         * suivantes appartiennent ensuite aux deux passages intégrés.
         */
        assertFalse(isAir(blocks, -12, BASE_Y + 9, -11));
        assertFalse(isAir(blocks, 12, BASE_Y + 9, -11));

        for (int x = -11; x <= -5; x++) {
            assertFalse(isAir(blocks, x, BASE_Y + 9, -11));
            for (int y = BASE_Y + 10; y <= BASE_Y + 13; y++) {
                assertTrue(isAir(blocks, x, y, -11));
            }
        }
        for (int x = 5; x <= 11; x++) {
            assertFalse(isAir(blocks, x, BASE_Y + 9, -11));
            for (int y = BASE_Y + 10; y <= BASE_Y + 13; y++) {
                assertTrue(isAir(blocks, x, y, -11));
            }
        }

        /*
         * L'ancien mur central a bien été absorbé : le rez-de-chaussée est
         * ouvert à cet emplacement, tandis que son sol reste porteur.
         */
        assertTrue(isAir(blocks, 0, BASE_Y + 1, -12));
        assertFalse(isAir(blocks, 0, BASE_Y, -12));
    }

    @Test
    void keepHasUsableEntranceRaisedPortcullisAndFurnishedFloors() {
        Map<String, Material> blocks = buildKeep();

        // Ouverture de cinq blocs de haut tournée vers la cour au sud.
        for (int y = BASE_Y + 1; y <= BASE_Y + 5; y++) {
            assertTrue(isAir(blocks, -1, y, -9));
            assertTrue(isAir(blocks, 0, y, -9));
        }
        assertEquals(
                Material.CHISELED_STONE_BRICKS,
                blocks.get(key(-1, BASE_Y + 6, -9))
        );
        assertEquals(
                Material.IRON_BARS,
                blocks.get(key(-1, BASE_Y + 7, -9))
        );

        // Les deux battants intérieurs ferment le vestibule sans bloquer l'arc.
        assertEquals(
                Material.DARK_OAK_DOOR,
                blocks.get(key(-1, BASE_Y + 1, -11))
        );
        assertEquals(
                Material.DARK_OAK_DOOR,
                blocks.get(key(0, BASE_Y + 2, -11))
        );

        // Trois planchers et la terrasse restent continus hors des escaliers.
        assertFalse(isAir(blocks, 5, BASE_Y + 9, -16));
        assertFalse(isAir(blocks, 5, BASE_Y + 16, -16));
        assertFalse(isAir(blocks, 5, BASE_Y + 22, -16));

        assertTrue(blocks.values().contains(Material.SMITHING_TABLE));
        assertTrue(blocks.values().contains(Material.ANVIL));
        assertTrue(blocks.values().contains(Material.CARTOGRAPHY_TABLE));
        assertTrue(blocks.values().contains(Material.BOOKSHELF));
        assertTrue(blocks.values().contains(Material.LECTERN));
        assertTrue(blocks.values().contains(Material.RED_BED));
        assertTrue(blocks.values().contains(Material.LANTERN));
        assertTrue(blocks.values().contains(Material.RED_WALL_BANNER));
        assertTrue(blocks.values().contains(Material.LIGHTNING_ROD));
    }

    @Test
    void everyKeepFloorAndNorthWestTowerTopAreReachableByStairs() {
        Map<String, Material> blocks = buildKeep();

        assertFlight(
                blocks,
                3,
                -12,
                BASE_Y + 1,
                9,
                2,
                0,
                -1,
                1,
                0,
                Material.DARK_OAK_STAIRS
        );
        assertFlight(
                blocks,
                -4,
                -20,
                BASE_Y + 10,
                7,
                2,
                0,
                1,
                -1,
                0,
                Material.DARK_OAK_STAIRS
        );
        assertFlight(
                blocks,
                0,
                -13,
                BASE_Y + 17,
                6,
                2,
                0,
                -1,
                1,
                0,
                Material.STONE_BRICK_STAIRS
        );
        assertFlight(
                blocks,
                -9,
                -21,
                BASE_Y + 23,
                4,
                2,
                0,
                -1,
                1,
                0,
                Material.STONE_BRICK_STAIRS
        );

        // Bloc de palier situé après la dernière marche de chaque volée.
        assertFalse(isAir(blocks, 3, BASE_Y + 9, -21));
        assertFalse(isAir(blocks, -4, BASE_Y + 16, -13));
        assertFalse(isAir(blocks, 0, BASE_Y + 22, -19));
        assertFalse(isAir(blocks, -9, BASE_Y + 26, -25));
    }

    @Test
    void lateralFlankingTowersDoNotBreakTheWallWalk() {
        Map<String, Material> blocks = new HashMap<>();
        Queue<Runnable> queue = new LinkedList<>();
        TerrainManager.SetBlock setBlock =
                (x, y, z, material) -> blocks.put(key(x, y, z), material);

        WallBuilder.build(
                new Location(null, 0, BASE_Y, 0),
                10,
                10,
                BASE_Y,
                Material.STONE_BRICKS,
                queue,
                setBlock
        );
        drain(queue);

        for (int towerX : new int[]{-12, 12}) {
            int inward = towerX < 0 ? 1 : -1;
            for (int doorZ : new int[]{-3, 3}) {
                for (int depth = 0; depth < 3; depth++) {
                    int x = towerX + inward * depth;
                    assertFalse(isAir(blocks, x, BASE_Y + 9, doorZ));
                    for (int y = BASE_Y + 10; y <= BASE_Y + 12; y++) {
                        assertTrue(isAir(blocks, x, y, doorZ));
                    }
                }
            }
        }
    }

    @Test
    void fortressBoundsIncludeKeepOppositeSouthGate() {
        Location center = new Location(null, 0, BASE_Y, 0);

        assertEquals(7, KeepBuilder.minimumWallGap());
        assertEquals(9, WallBuilder.WALL_HEIGHT);
        assertEquals(4, KeepBuilder.foundationDepth());
        assertEquals(31, KeepBuilder.maximumRelativeHeight());

        int[] keepBounds = KeepBuilder.outerBounds(center, 10);
        assertEquals(-13, keepBounds[0]);
        assertEquals(13, keepBounds[1]);
        assertEquals(-28, keepBounds[2]);
        assertEquals(-4, keepBounds[3]);

        int[] fortressBounds = WallBuilder.outerBounds(center, 10, 10);
        assertEquals(-16, fortressBounds[0]);
        assertEquals(16, fortressBounds[1]);
        assertEquals(-28, fortressBounds[2]);
        assertEquals(16, fortressBounds[3]);

        Location keepAnchor = KeepBuilder.keepAnchor(center, 10, BASE_Y);
        Location gateAnchor = WallBuilder.gateAnchor(center, 10, 10, BASE_Y);
        assertTrue(keepAnchor.getBlockZ() < center.getBlockZ());
        assertTrue(gateAnchor.getBlockZ() > center.getBlockZ());
    }

    private static Map<String, Material> buildKeep() {
        Map<String, Material> blocks = new HashMap<>();
        Queue<Runnable> queue = new LinkedList<>();
        KeepBuilder.build(
                new Location(null, 0, BASE_Y, 0),
                10,
                BASE_Y,
                Material.STONE_BRICKS,
                queue,
                (x, y, z, material) -> blocks.put(key(x, y, z), material)
        );
        drain(queue);
        return blocks;
    }

    private static void assertFlight(Map<String, Material> blocks,
                                     int startX,
                                     int startZ,
                                     int startY,
                                     int steps,
                                     int width,
                                     int directionX,
                                     int directionZ,
                                     int lateralX,
                                     int lateralZ,
                                     Material expectedMaterial) {
        for (int step = 0; step < steps; step++) {
            for (int side = 0; side < width; side++) {
                int x = startX + directionX * step + lateralX * side;
                int y = startY + step;
                int z = startZ + directionZ * step + lateralZ * side;

                assertEquals(
                        expectedMaterial,
                        blocks.get(key(x, y, z)),
                        "Marche absente en " + x + "," + y + "," + z
                );
                assertTrue(
                        isAir(blocks, x, y + 1, z),
                        "Premier bloc de tête obstrué en "
                                + x + "," + (y + 1) + "," + z
                );
                assertTrue(
                        isAir(blocks, x, y + 2, z),
                        "Second bloc de tête obstrué en "
                                + x + "," + (y + 2) + "," + z
                );
            }
        }
    }

    private static void drain(Queue<Runnable> queue) {
        while (!queue.isEmpty()) {
            Runnable action = queue.poll();
            if (action != null) {
                action.run();
            }
        }
    }

    private static boolean isAir(Map<String, Material> blocks,
                                 int x,
                                 int y,
                                 int z) {
        Material material = blocks.get(key(x, y, z));
        return material == null || material.isAir();
    }

    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }
}
