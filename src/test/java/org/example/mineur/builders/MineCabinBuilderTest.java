package org.example.mineur.builders;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.example.mineur.builders.MineCabinBuilder.BlockPos;
import org.example.mineur.builders.MineCabinBuilder.ChestPair;
import org.example.mineur.builders.MineCabinBuilder.Plan;
import org.example.mineur.builders.MineCabinBuilder.Settings;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineCabinBuilderTest {

    private static final Settings DEFAULTS = new Settings(
            5,
            15,
            17,
            5,
            16,
            6,
            12_000
    );

    @Test
    void createsSixteenIsolatedDoubleChests() {
        Plan plan = MineCabinBuilder.createPlan(100, 64, -40, 9, 11, DEFAULTS);

        assertEquals(16, plan.chestPairs().size());
        assertEquals(32, plan.chestBlockCount());

        Set<BlockPos> chestBlocks = new HashSet<>(plan.chestPositions());
        assertEquals(32, chestBlocks.size(), "Chaque moitié de coffre doit être unique");

        for (ChestPair pair : plan.chestPairs()) {
            assertTrue(isHorizontal(pair.facing()));
            assertEquals(1, manhattan(pair.first(), pair.second()));
            assertEquals(Material.CHEST, plan.materialAt(pair.first()));
            assertEquals(Material.CHEST, plan.materialAt(pair.second()));

            /*
             * Une moitié ne doit toucher que son partenaire. Cette contrainte
             * empêche Minecraft de former un coffre triple ou de fusionner deux
             * rangées décoratives.
             */
            assertEquals(1, adjacentChestCount(pair.first(), chestBlocks));
            assertEquals(1, adjacentChestCount(pair.second(), chestBlocks));
        }
    }

    @Test
    void keepsFourPillarsOnTheScaledOuterFrame() {
        Plan plan = MineCabinBuilder.createPlan(0, 70, 0, 32, 24, DEFAULTS);
        int groundY = 71;
        int topY = plan.deckY() + 1;

        int[][] corners = {
                {plan.frameMinX(), plan.frameMinZ()},
                {plan.frameMaxX(), plan.frameMinZ()},
                {plan.frameMinX(), plan.frameMaxZ()},
                {plan.frameMaxX(), plan.frameMaxZ()}
        };
        for (int[] corner : corners) {
            assertEquals(
                    Material.STRIPPED_DARK_OAK_LOG,
                    plan.materialAt(new BlockPos(corner[0], groundY, corner[1]))
            );
            assertEquals(
                    Material.STRIPPED_DARK_OAK_LOG,
                    plan.materialAt(new BlockPos(corner[0], topY, corner[1]))
            );
        }

        assertTrue(plan.frameMaxX() - plan.frameMinX() + 1 >= 36);
        assertTrue(plan.frameMaxZ() - plan.frameMinZ() + 1 >= 28);
    }


    @Test
    void laddersFaceOutwardAndRemainSupportedByTheirPillars() {
        Plan plan = MineCabinBuilder.createPlan(20, 64, 30, 18, 14, DEFAULTS);
        int bottomY = plan.baseY() + 2;
        int topY = plan.deckY() + 1;

        int[][] northCorners = {
                {plan.frameMinX(), plan.frameMinZ()},
                {plan.frameMaxX(), plan.frameMinZ()}
        };
        int[][] southCorners = {
                {plan.frameMinX(), plan.frameMaxZ()},
                {plan.frameMaxX(), plan.frameMaxZ()}
        };

        assertSupportedLadders(plan, northCorners, -1, BlockFace.NORTH, bottomY, topY);
        assertSupportedLadders(plan, southCorners, 1, BlockFace.SOUTH, bottomY, topY);
    }

    @Test
    void scalesCabinThenCapsClosedRoomForLargeQuarry() {
        Plan small = MineCabinBuilder.createPlan(0, 64, 0, 1, 1, DEFAULTS);
        Plan medium = MineCabinBuilder.createPlan(0, 64, 0, 10, 10, DEFAULTS);
        Plan large = MineCabinBuilder.createPlan(0, 64, 0, 64, 64, DEFAULTS);

        int smallCabin = small.cabinMaxX() - small.cabinMinX() + 1;
        int mediumCabin = medium.cabinMaxX() - medium.cabinMinX() + 1;
        int largeCabin = large.cabinMaxX() - large.cabinMinX() + 1;

        assertEquals(15, smallCabin);
        assertEquals(17, mediumCabin);
        assertEquals(17, largeCabin);
        assertTrue(large.frameMaxX() - large.frameMinX()
                > medium.frameMaxX() - medium.frameMinX());

        int centerX = (small.cabinMinX() + small.cabinMaxX()) / 2;
        assertEquals(
                Material.SPRUCE_DOOR,
                small.materialAt(new BlockPos(centerX, small.deckY() + 1, small.cabinMinZ()))
        );
        assertEquals(
                Material.SPRUCE_DOOR,
                small.materialAt(new BlockPos(centerX, small.deckY() + 1, small.cabinMaxZ()))
        );
    }

    @Test
    void keepsFullStorageCapacityForAsymmetricMines() {
        int[][] dimensions = {
                {1, 64},
                {64, 1},
                {1, 10},
                {10, 1},
                {15, 3},
                {3, 15}
        };

        for (int[] dimension : dimensions) {
            Plan plan = MineCabinBuilder.createPlan(
                    -25,
                    64,
                    80,
                    dimension[0],
                    dimension[1],
                    DEFAULTS
            );

            int cabinWidth = plan.cabinMaxX() - plan.cabinMinX() + 1;
            int cabinLength = plan.cabinMaxZ() - plan.cabinMinZ() + 1;
            assertEquals(
                    cabinWidth,
                    cabinLength,
                    "Le pavillon doit rester carré pour " + dimension[0] + "x" + dimension[1]
            );
            assertEquals(
                    16,
                    plan.chestPairs().size(),
                    "La capacité annoncée doit être conservée pour "
                            + dimension[0] + "x" + dimension[1]
            );
        }
    }

    @Test
    void keepsPlanInsideConfiguredBudget() {
        Plan plan = MineCabinBuilder.createPlan(0, 64, 0, 64, 64, DEFAULTS);

        assertTrue(plan.touchedBlockCount() <= DEFAULTS.maximumPlannedBlocks());
        assertNotNull(plan.bounds());
        assertTrue(plan.bounds().contains(
                new BlockPos(plan.frameMinX(), plan.deckY(), plan.frameMinZ())
        ));
    }

    @Test
    void includesTheExpectedLuxuryMineShaftDetails() {
        Plan plan = MineCabinBuilder.createPlan(40, 72, -20, 12, 9, DEFAULTS);

        /*
         * Ces seuils protègent les éléments visuels structurants sans figer
         * chaque bloc de décoration : le plan peut évoluer tout en conservant
         * une vraie cabane éclairée, vitrée et accessible.
         */
        assertTrue(countMaterial(plan, Material.LIGHT_GRAY_STAINED_GLASS_PANE) >= 20);
        assertTrue(countMaterial(plan, Material.LANTERN) >= 8);
        assertEquals(4, countMaterial(plan, Material.SPRUCE_DOOR));
        assertTrue(countMaterial(plan, Material.DARK_OAK_STAIRS) >= 100);
        assertTrue(countMaterial(plan, Material.CHAIN) >= 4);
        assertTrue(
                countMaterial(plan, Material.LADDER)
                        >= 4 * DEFAULTS.platformHeight(),
                "Chaque pied doit proposer une montée complète vers la plateforme"
        );
    }

    @Test
    void closesEveryRoofStairWithAContinuousTimberShell() {
        Plan plan = MineCabinBuilder.createPlan(40, 72, -20, 12, 9, DEFAULTS);
        int roofBaseY = plan.deckY() + DEFAULTS.wallHeight() + 1;
        int checkedRoofStairs = 0;

        for (BlockPos position : plan.placedPositions()) {
            if (position.y() < roofBaseY
                    || plan.materialAt(position) != Material.DARK_OAK_STAIRS) {
                continue;
            }

            checkedRoofStairs++;
            assertEquals(
                    Material.SPRUCE_PLANKS,
                    plan.materialAt(position.offset(0, -1, 0)),
                    "Chaque escalier du toit doit masquer sa diagonale depuis l'intérieur"
            );
        }

        assertTrue(checkedRoofStairs >= 100, "La toiture principale doit rester complète");
    }

    @Test
    void providesWideLitStairsAlignedWithBothCabinDoors() {
        Plan plan = MineCabinBuilder.createPlan(20, 64, 30, 18, 14, DEFAULTS);
        int groundY = plan.baseY() + 1;
        int rise = plan.deckY() - groundY;
        int centerX = (plan.cabinMinX() + plan.cabinMaxX()) / 2;

        assertMainStaircase(
                plan,
                centerX,
                groundY,
                rise,
                plan.frameMinZ() - rise,
                1,
                BlockFace.SOUTH
        );
        assertMainStaircase(
                plan,
                centerX,
                groundY,
                rise,
                plan.frameMaxZ() + rise,
                -1,
                BlockFace.NORTH
        );

        assertTrue(
                countMaterial(plan, Material.LANTERN) >= 12,
                "Les deux accès principaux doivent rester clairement éclairés"
        );
    }

    @Test
    void alignsTheChestRoomHatchWithThePersistentShaftColumn() {
        Plan plan = MineCabinBuilder.createPlan(40, 72, -20, 12, 9, DEFAULTS);
        MineShaftColumnBuilder.Layout shaft = plan.shaftLayout();

        assertEquals(40, shaft.mineMinX());
        assertEquals(51, shaft.mineMaxX());
        assertEquals(-20, shaft.mineMinZ());
        assertEquals(-12, shaft.mineMaxZ());

        BlockPos hatch = new BlockPos(shaft.ladderX(), plan.deckY(), shaft.ladderZ());
        assertEquals(Material.SPRUCE_TRAPDOOR, plan.materialAt(hatch));
        assertEquals(shaft.supportDirection(), plan.facingAt(hatch));

        for (int y = plan.baseY() + 1; y < plan.deckY(); y++) {
            BlockPos ladder = new BlockPos(shaft.ladderX(), y, shaft.ladderZ());
            BlockPos support = new BlockPos(shaft.supportX(), y, shaft.supportZ());

            assertEquals(Material.LADDER, plan.materialAt(ladder));
            assertEquals(shaft.ladderFacing(), plan.facingAt(ladder));
            assertEquals(Material.STRIPPED_DARK_OAK_LOG, plan.materialAt(support));
        }

        assertEquals(
                Material.STRIPPED_DARK_OAK_LOG,
                plan.materialAt(new BlockPos(
                        shaft.supportX(),
                        plan.deckY(),
                        shaft.supportZ()
                ))
        );

        /*
         * Le garde-corps occupe un carré 3x3 au centre. Aucun coffre ne doit
         * donc empiéter sur l'accès, même si la capacité configurée est pleine.
         */
        for (BlockPos chest : plan.chestPositions()) {
            long distance = Math.max(
                    Math.abs((long) chest.x() - shaft.ladderX()),
                    Math.abs((long) chest.z() - shaft.ladderZ())
            );
            assertTrue(distance > 1L, "Un coffre bloque le garde-corps du puits");
        }

        assertEquals(1, countMaterial(plan, Material.SPRUCE_TRAPDOOR));
        assertTrue(countMaterial(plan, Material.SPRUCE_FENCE_GATE) >= 5);
    }

    @Test
    void rejectsCoordinatesThatWouldOverflowInclusiveLoops() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MineCabinBuilder.createPlan(
                        Integer.MAX_VALUE - 2,
                        64,
                        0,
                        1,
                        1,
                        DEFAULTS
                )
        );
    }

    @Test
    void rejectsVerticalCoordinatesThatWouldOverflowRoofOrFoundations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MineCabinBuilder.createPlan(
                        0,
                        Integer.MAX_VALUE - DEFAULTS.platformHeight(),
                        0,
                        1,
                        1,
                        DEFAULTS
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> MineCabinBuilder.createPlan(
                        0,
                        Integer.MIN_VALUE + DEFAULTS.maximumFoundationDepth() - 1,
                        0,
                        1,
                        1,
                        DEFAULTS
                )
        );
    }


    private int countMaterial(Plan plan, Material material) {
        return (int) plan.placedPositions().stream()
                .filter(position -> material == plan.materialAt(position))
                .count();
    }

    private void assertSupportedLadders(Plan plan,
                                        int[][] corners,
                                        int outwardZ,
                                        BlockFace facing,
                                        int bottomY,
                                        int topY) {
        for (int[] corner : corners) {
            int ladderZ = corner[1] + outwardZ;
            for (int y = bottomY; y <= topY; y++) {
                BlockPos ladder = new BlockPos(corner[0], y, ladderZ);
                assertEquals(Material.LADDER, plan.materialAt(ladder));
                assertEquals(facing, plan.facingAt(ladder));

                /*
                 * Le support se trouve derrière la face visible de l'échelle.
                 * Il doit correspondre exactement au pilier vertical.
                 */
                BlockPos support = ladder.offset(
                        -facing.getModX(),
                        0,
                        -facing.getModZ()
                );
                assertEquals(
                        Material.STRIPPED_DARK_OAK_LOG,
                        plan.materialAt(support)
                );
            }
        }
    }

    private void assertMainStaircase(Plan plan,
                                     int centerX,
                                     int groundY,
                                     int rise,
                                     int bottomZ,
                                     int directionZ,
                                     BlockFace facing) {
        for (int step = 0; step < rise; step++) {
            int y = groundY + step;
            int z = bottomZ + directionZ * step;

            for (int dx = -2; dx <= 2; dx++) {
                BlockPos position = new BlockPos(centerX + dx, y, z);
                Material expected = Math.abs(dx) <= 1
                        ? Material.SPRUCE_STAIRS
                        : Material.DARK_OAK_STAIRS;
                assertEquals(expected, plan.materialAt(position));
                assertEquals(facing, plan.facingAt(position));
            }
        }

        int landingZ = bottomZ - directionZ;
        for (int dx = -1; dx <= 1; dx++) {
            assertEquals(
                    Material.SPRUCE_PLANKS,
                    plan.materialAt(new BlockPos(centerX + dx, groundY, landingZ))
            );
        }
    }

    private boolean isHorizontal(BlockFace face) {
        return face == BlockFace.NORTH
                || face == BlockFace.SOUTH
                || face == BlockFace.EAST
                || face == BlockFace.WEST;
    }

    private int manhattan(BlockPos first, BlockPos second) {
        return Math.abs(first.x() - second.x())
                + Math.abs(first.y() - second.y())
                + Math.abs(first.z() - second.z());
    }

    private int adjacentChestCount(BlockPos origin, Set<BlockPos> chestBlocks) {
        int count = 0;
        for (BlockFace face : Set.of(
                BlockFace.NORTH,
                BlockFace.SOUTH,
                BlockFace.EAST,
                BlockFace.WEST
        )) {
            BlockPos adjacent = origin.offset(face.getModX(), 0, face.getModZ());
            if (chestBlocks.contains(adjacent)) {
                count++;
            }
        }
        return count;
    }
}
