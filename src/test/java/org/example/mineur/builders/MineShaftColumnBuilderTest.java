package org.example.mineur.builders;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MineShaftColumnBuilderTest {

    @Test
    void choosesADeterministicCentralLayoutWithRoomForLighting() {
        MineShaftColumnBuilder.Layout layout =
                MineShaftColumnBuilder.createLayout(-4, 11, 9, 7);

        assertEquals(0, layout.ladderX());
        assertEquals(14, layout.ladderZ());
        assertEquals(BlockFace.EAST, layout.supportDirection());
        assertEquals(BlockFace.WEST, layout.ladderFacing());
        assertEquals(1, layout.supportX());
        assertEquals(14, layout.supportZ());

        assertTrue(layout.sideLantern());
        assertEquals(1, layout.lightX());
        assertEquals(15, layout.lightZ());
        assertEquals(Axis.Z, layout.lightBeamAxis());
        assertTrue(layout.contains(layout.ladderX(), layout.ladderZ()));
        assertTrue(layout.contains(layout.supportX(), layout.supportZ()));
        assertTrue(layout.contains(layout.lightX(), layout.lightZ()));
    }

    @Test
    void keepsTheLadderInsideEveryNarrowSelection() {
        int[][] dimensions = {
                {1, 1},
                {1, 64},
                {64, 1},
                {2, 1},
                {1, 2},
                {2, 2}
        };

        for (int[] dimension : dimensions) {
            MineShaftColumnBuilder.Layout layout =
                    MineShaftColumnBuilder.createLayout(
                            100,
                            -40,
                            dimension[0],
                            dimension[1]
                    );

            assertTrue(layout.contains(layout.ladderX(), layout.ladderZ()));
            assertEquals(
                    1L,
                    Math.abs((long) layout.supportX() - layout.ladderX())
                            + Math.abs((long) layout.supportZ() - layout.ladderZ())
            );

            if ((long) dimension[0] * dimension[1] > 1L) {
                assertTrue(
                        layout.contains(layout.supportX(), layout.supportZ()),
                        "Le poteau doit rester dans toute sélection de plus d'un bloc"
                );
            }
        }

        MineShaftColumnBuilder.Layout single =
                MineShaftColumnBuilder.createLayout(100, -40, 1, 1);
        assertFalse(single.contains(single.supportX(), single.supportZ()));
        assertFalse(single.sideLantern());
    }

    @Test
    void clampsTheLightIntervalAndUsesStableDepthMarkers() {
        assertEquals(3, MineShaftColumnBuilder.normalizeLightInterval(-20));
        assertEquals(5, MineShaftColumnBuilder.normalizeLightInterval(5));
        assertEquals(16, MineShaftColumnBuilder.normalizeLightInterval(100));

        assertFalse(MineShaftColumnBuilder.isLightLevel(64, 64, 5));
        assertFalse(MineShaftColumnBuilder.isLightLevel(64, 63, 5));
        assertTrue(MineShaftColumnBuilder.isLightLevel(64, 59, 5));
        assertTrue(MineShaftColumnBuilder.isLightLevel(64, 54, 5));
    }

    @Test
    void neverOverwritesNaturalRockBeforeItsTurnIsMined() {
        MineShaftColumnBuilder.Layout layout =
                MineShaftColumnBuilder.createLayout(0, 0, 5, 5);
        FakeBlocks fake = new FakeBlocks();
        fake.set(
                layout.supportX(),
                63,
                layout.supportZ(),
                Material.STONE
        );

        List<PlacedBlock> placements = new ArrayList<>();
        MineShaftColumnBuilder.maintainLayer(
                fake.world(),
                layout,
                64,
                63,
                5,
                fake.handler(placements)
        );

        assertTrue(
                placements.isEmpty(),
                "Le poteau et l'échelle doivent attendre l'excavation du support"
        );
        assertEquals(
                Material.STONE,
                fake.typeAt(layout.supportX(), 63, layout.supportZ())
        );
    }

    @Test
    void buildsTimberLadderAndSuspendedLightOnlyInsideExcavatedAir() {
        MineShaftColumnBuilder.Layout layout =
                MineShaftColumnBuilder.createLayout(0, 0, 5, 5);
        FakeBlocks fake = new FakeBlocks();
        List<PlacedBlock> placements = new ArrayList<>();

        MineShaftColumnBuilder.maintainLayer(
                fake.world(),
                layout,
                64,
                59,
                5,
                fake.handler(placements)
        );

        assertEquals(
                Material.STRIPPED_DARK_OAK_LOG,
                fake.typeAt(layout.supportX(), 59, layout.supportZ())
        );
        assertEquals(
                Material.LADDER,
                fake.typeAt(layout.ladderX(), 59, layout.ladderZ())
        );
        assertTrue(layout.sideLantern());
        assertEquals(
                Material.STRIPPED_SPRUCE_LOG,
                fake.typeAt(layout.lightX(), 60, layout.lightZ())
        );
        assertEquals(
                Material.LANTERN,
                fake.typeAt(layout.lightX(), 59, layout.lightZ())
        );

        assertEquals(
                List.of(
                        Material.STRIPPED_DARK_OAK_LOG,
                        Material.LADDER,
                        Material.STRIPPED_SPRUCE_LOG,
                        Material.LANTERN
                ),
                placements.stream().map(PlacedBlock::material).toList()
        );
    }

    @Test
    void usesAnIntegratedLightForAShaftTooNarrowForALanternBracket() {
        MineShaftColumnBuilder.Layout layout =
                MineShaftColumnBuilder.createLayout(8, 12, 1, 4);
        FakeBlocks fake = new FakeBlocks();
        List<PlacedBlock> placements = new ArrayList<>();

        MineShaftColumnBuilder.maintainLayer(
                fake.world(),
                layout,
                40,
                35,
                5,
                fake.handler(placements)
        );

        assertFalse(layout.sideLantern());
        assertEquals(
                Material.SHROOMLIGHT,
                fake.typeAt(layout.supportX(), 35, layout.supportZ())
        );
        assertEquals(
                Material.LADDER,
                fake.typeAt(layout.ladderX(), 35, layout.ladderZ())
        );
        assertEquals(2, placements.size());
    }

    @Test
    void rejectsInvalidOrOverflowingDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MineShaftColumnBuilder.createLayout(0, 0, 0, 1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> MineShaftColumnBuilder.createLayout(
                        Integer.MAX_VALUE,
                        0,
                        2,
                        1
                )
        );
    }

    private record Coordinate(int x, int y, int z) {
    }

    private record PlacedBlock(Coordinate coordinate, Material material) {
    }

    /**
     * Petit monde simulé ciblé : chaque bloc possède une matière mutable afin
     * de reproduire le contrat du PlacementHandler sans démarrer un serveur.
     */
    private static final class FakeBlocks {
        private final Map<Coordinate, AtomicReference<Material>> types = new HashMap<>();
        private final Map<Coordinate, Block> blocks = new HashMap<>();
        private final World world;

        private FakeBlocks() {
            world = mock(World.class);
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(320);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> blockAt(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2)
            ));
        }

        private World world() {
            return world;
        }

        private void set(int x, int y, int z, Material material) {
            referenceAt(new Coordinate(x, y, z)).set(material);
        }

        private Material typeAt(int x, int y, int z) {
            return referenceAt(new Coordinate(x, y, z)).get();
        }

        private MineShaftColumnBuilder.PlacementHandler handler(
                List<PlacedBlock> placements
        ) {
            return (block, material, dataConfigurer) -> {
                Coordinate coordinate = new Coordinate(
                        block.getX(),
                        block.getY(),
                        block.getZ()
                );
                Material current = referenceAt(coordinate).get();
                if (!current.isAir()
                        && !MineShaftColumnBuilder.isManagedMaterial(current)) {
                    return false;
                }

                referenceAt(coordinate).set(material);
                placements.add(new PlacedBlock(coordinate, material));
                return true;
            };
        }

        private Block blockAt(int x, int y, int z) {
            Coordinate coordinate = new Coordinate(x, y, z);
            return blocks.computeIfAbsent(coordinate, ignored -> {
                Block block = mock(Block.class);
                when(block.getX()).thenReturn(x);
                when(block.getY()).thenReturn(y);
                when(block.getZ()).thenReturn(z);
                when(block.getType()).thenAnswer(
                        invocation -> referenceAt(coordinate).get()
                );
                return block;
            });
        }

        private AtomicReference<Material> referenceAt(Coordinate coordinate) {
            return types.computeIfAbsent(
                    coordinate,
                    ignored -> new AtomicReference<>(Material.AIR)
            );
        }
    }
}
