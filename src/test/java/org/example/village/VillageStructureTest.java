package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageStructureTest {

    @Test
    void wallHasWalkableGatehouseSupportedLightsAndRaisedPortcullis() {
        Map<String, Material> blocks = new HashMap<>();
        Queue<Runnable> queue = new LinkedList<>();
        TerrainManager.SetBlock setBlock =
                (x, y, z, material) -> blocks.put(key(x, y, z), material);

        WallBuilder.build(
                new Location(null, 0, 64, 0),
                10,
                10,
                64,
                Material.STONE_BRICKS,
                queue,
                setBlock
        );
        queue.forEach(Runnable::run);

        int[] bounds = WallBuilder.outerBounds(
                new Location(null, 0, 64, 0),
                10,
                10
        );
        assertEquals(-16, bounds[0]);
        assertEquals(16, bounds[1]);
        assertEquals(-16, bounds[2]);
        assertEquals(16, bounds[3]);
        assertEquals(Material.DARK_OAK_STAIRS,
                blocks.get(key(-16, 78, -16)));

        assertTrue(blocks.values().contains(Material.LANTERN));
        assertTrue(blocks.values().contains(Material.CHAIN));
        assertTrue(blocks.values().contains(Material.DARK_OAK_FENCE));
        assertTrue(blocks.values().contains(Material.RED_WALL_BANNER));
        assertTrue(blocks.values().contains(Material.DARK_OAK_STAIRS));
        assertTrue(blocks.values().contains(Material.STONE_BRICK_SLAB));
        assertTrue(blocks.values().contains(Material.IRON_BARS));

        /*
         * Avec rz=10 et une épaisseur de trois blocs, le châtelet est centré
         * sur z=11. Le passage conserve cinq blocs libres sur toute sa
         * profondeur et la herse reste entièrement relevée.
         */
        for (int y = 65; y <= 69; y++) {
            assertEquals(Material.AIR, blocks.get(key(0, y, 11)));
        }
        assertEquals(Material.CHISELED_STONE_BRICKS,
                blocks.get(key(0, 70, 15)));
        assertEquals(Material.IRON_BARS,
                blocks.get(key(0, 71, 14)));

        // Les tours et leurs épis dominent nettement les courtines.
        assertTrue(blocks.keySet().stream()
                .anyMatch(position -> yOf(position) >= 86));
        assertTrue(blocks.values().contains(Material.DEEPSLATE_BRICKS));
        assertTrue(blocks.values().contains(Material.GRAY_STAINED_GLASS));
    }

    @Test
    void medievalPaletteMatchesAccentWoodFamily() {
        VillageStyle.Palette darkOakPalette =
                VillageStyle.medievalPalette(Material.DARK_OAK_PLANKS);
        VillageStyle.Palette sprucePalette =
                VillageStyle.medievalPalette(Material.SPRUCE_PLANKS);

        assertEquals(Material.DARK_OAK_DOOR, darkOakPalette.door());
        assertEquals(Material.DARK_OAK_TRAPDOOR, darkOakPalette.shutter());
        assertEquals(Material.DARK_OAK_FENCE, darkOakPalette.fence());
        assertEquals(Material.DARK_OAK_STAIRS, darkOakPalette.roofStairs());
        assertEquals(Material.DARK_OAK_SLAB, darkOakPalette.roofSlab());
        assertEquals(Material.STRIPPED_DARK_OAK_LOG, darkOakPalette.timber());
        assertEquals(Material.SPRUCE_PLANKS, darkOakPalette.floor());

        assertEquals(Material.SPRUCE_DOOR, sprucePalette.door());
        assertEquals(Material.SPRUCE_TRAPDOOR, sprucePalette.shutter());
        assertEquals(Material.SPRUCE_FENCE, sprucePalette.fence());
        assertEquals(Material.OAK_PLANKS, sprucePalette.floor());
        assertEquals(Material.GLASS, sprucePalette.window());
        assertEquals(Material.GLASS, darkOakPalette.window());
    }

    @Test
    void gableRoofHasFilledEndsAndNoFloatingRidge() {
        Map<String, Material> blocks = new HashMap<>();
        List<Runnable> tasks = new ArrayList<>();
        TerrainManager.SetBlock setBlock =
                (x, y, z, material) -> blocks.put(key(x, y, z), material);

        VillageRoofBuilder.buildGable(
                tasks,
                null,
                setBlock,
                -3,
                3,
                -4,
                4,
                70,
                BlockFace.SOUTH,
                Material.SPRUCE_STAIRS,
                Material.SPRUCE_SLAB,
                Material.WHITE_TERRACOTTA,
                Material.STRIPPED_SPRUCE_LOG,
                Material.GLASS
        );
        tasks.forEach(Runnable::run);

        assertEquals(Material.SPRUCE_SLAB, blocks.get(key(0, 75, 0)));
        assertEquals(Material.SPRUCE_PLANKS, blocks.get(key(0, 74, 0)));
        assertEquals(Material.GLASS, blocks.get(key(0, 71, -4)));
        assertEquals(Material.GLASS, blocks.get(key(0, 71, 4)));
        assertFalse(blocks.keySet().stream()
                .anyMatch(position -> yOf(position) > 75));

        /*
         * Chaque niveau du noyau est plein entre ses deux rampants. Ces blocs
         * empêchent le ciel d'apparaître entre deux rangées d'escaliers.
         */
        for (int layer = 0; layer <= 4; layer++) {
            assertTrue(blocks.containsKey(key(0, 70 + layer, 0)));
        }
    }

    @Test
    void hipRoofUsesSolidShrinkingCoreAndSupportedCap() {
        Map<String, Material> blocks = new HashMap<>();
        List<Runnable> tasks = new ArrayList<>();
        TerrainManager.SetBlock setBlock =
                (x, y, z, material) -> blocks.put(key(x, y, z), material);

        VillageRoofBuilder.buildHip(
                tasks,
                null,
                setBlock,
                -2,
                2,
                -2,
                2,
                70,
                Material.DARK_OAK_PLANKS,
                Material.DARK_OAK_STAIRS,
                Material.DARK_OAK_SLAB
        );
        tasks.forEach(Runnable::run);

        for (int layer = 0; layer <= 3; layer++) {
            int min = -3 + layer;
            int max = 3 - layer;
            for (int x = min; x <= max; x++) {
                for (int z = min; z <= max; z++) {
                    assertTrue(
                            blocks.containsKey(key(x, 70 + layer, z)),
                            "Trou détecté dans le noyau de toiture en croupe"
                    );
                }
            }
        }

        assertEquals(Material.DARK_OAK_PLANKS,
                blocks.get(key(0, 73, 0)));
        assertEquals(Material.DARK_OAK_SLAB,
                blocks.get(key(0, 74, 0)));
    }

    @Test
    void churchContainsGlassAltarSeatsAndClosedSteepRoof() {
        Map<String, Material> blocks = new HashMap<>();
        TerrainManager.SetBlock setBlock =
                (x, y, z, material) -> blocks.put(key(x, y, z), material);

        VillageLayoutPlan.LotPlan lot = new VillageLayoutPlan.LotPlan(
                0,
                2,
                0,
                0,
                VillageLayoutPlan.LotRole.CHURCH,
                BlockFace.SOUTH,
                -6,
                -8,
                13,
                17,
                0,
                1,
                null,
                2,
                3,
                true
        );

        SpecialBuildings.buildChurch(null, lot, 64, setBlock)
                .forEach(Runnable::run);

        assertTrue(blocks.values().contains(Material.BLUE_STAINED_GLASS));
        assertFalse(blocks.values().stream()
                .anyMatch(material -> material.name().endsWith("_GLASS_PANE")));
        assertTrue(blocks.values().contains(Material.QUARTZ_BLOCK));
        assertTrue(blocks.values().contains(Material.SPRUCE_STAIRS));
        assertTrue(blocks.values().contains(Material.DARK_OAK_STAIRS));
        assertTrue(blocks.values().contains(Material.DARK_OAK_SLAB));
        assertTrue(blocks.values().contains(Material.GOLD_BLOCK));
    }

    @Test
    void forgeContainsWorkshopCoreChimneyAndOutdoorWorkArea() {
        Map<String, Material> blocks = new HashMap<>();
        TerrainManager.SetBlock setBlock =
                (x, y, z, material) -> blocks.put(key(x, y, z), material);

        VillageLayoutPlan.LotPlan lot = new VillageLayoutPlan.LotPlan(
                3,
                2,
                0,
                0,
                VillageLayoutPlan.LotRole.FORGE,
                BlockFace.WEST,
                -6,
                -5,
                13,
                11,
                -10,
                0,
                null,
                1,
                3,
                true
        );

        SpecialBuildings.buildForge(null, lot, 64, setBlock)
                .forEach(Runnable::run);

        assertTrue(blocks.values().contains(Material.BLAST_FURNACE));
        assertTrue(blocks.values().contains(Material.ANVIL));
        assertTrue(blocks.values().contains(Material.SMITHING_TABLE));
        assertTrue(blocks.values().contains(Material.GRINDSTONE));
        assertTrue(blocks.values().contains(Material.BRICKS));
        assertTrue(blocks.values().contains(Material.CAMPFIRE));
        assertTrue(blocks.values().contains(Material.CHEST));
        assertTrue(blocks.values().contains(Material.SPRUCE_SLAB));
    }

    @Test
    void houseBuilderPlacesClosedRoofWindowsDoorAndDetailedYard() {
        Map<String, Material> blocks = new HashMap<>();
        TerrainManager.SetBlock setBlock =
                (x, y, z, material) -> blocks.put(key(x, y, z), material);

        VillageLayoutPlan.HouseSpec spec = new VillageLayoutPlan.HouseSpec(
                VillageLayoutPlan.HouseArchetype.TOWNHOUSE,
                7,
                9,
                6,
                true,
                VillageLayoutPlan.RoofStyle.GABLE,
                2,
                0,
                Material.SPRUCE_PLANKS,
                1,
                2,
                true,
                true,
                1,
                VillageLayoutPlan.YardStyle.FLOWERS
        );
        VillageLayoutPlan.LotPlan lot = new VillageLayoutPlan.LotPlan(
                1,
                1,
                0,
                0,
                VillageLayoutPlan.LotRole.HOUSE_TWO_STORY,
                BlockFace.SOUTH,
                -3,
                -10,
                7,
                9,
                0,
                1,
                spec,
                1,
                3,
                false
        );

        HouseBuilder.buildHouse(null, lot, 64, setBlock, new Random(1))
                .forEach(Runnable::run);
        EnumSet<Material> values = EnumSet.copyOf(blocks.values());

        assertTrue(values.contains(Material.SPRUCE_STAIRS));
        assertTrue(values.contains(Material.SPRUCE_SLAB));
        assertTrue(values.contains(Material.GLASS));
        assertFalse(values.stream()
                .anyMatch(material -> material.name().endsWith("_GLASS_PANE")));
        assertTrue(values.contains(Material.SPRUCE_DOOR));
        assertTrue(values.contains(Material.LANTERN));
        assertTrue(values.contains(Material.RED_WALL_BANNER)
                || values.contains(Material.YELLOW_WALL_BANNER));
        assertTrue(values.contains(Material.POPPY)
                || values.contains(Material.BLUE_ORCHID));
    }

    @Test
    void familyHouseWingExtendsOutsideMainVolumeWithoutOverwritingIt() {
        Map<String, Material> blocks = new HashMap<>();
        TerrainManager.SetBlock setBlock =
                (x, y, z, material) -> blocks.put(key(x, y, z), material);

        VillageLayoutPlan.HouseSpec spec = new VillageLayoutPlan.HouseSpec(
                VillageLayoutPlan.HouseArchetype.FAMILY_HOUSE,
                10,
                9,
                5,
                false,
                VillageLayoutPlan.RoofStyle.GABLE,
                2,
                0,
                Material.SPRUCE_PLANKS,
                2,
                0,
                true,
                false,
                0,
                VillageLayoutPlan.YardStyle.KITCHEN_GARDEN
        );
        VillageLayoutPlan.LotPlan lot = new VillageLayoutPlan.LotPlan(
                0,
                0,
                0,
                0,
                VillageLayoutPlan.LotRole.HOUSE_SINGLE,
                BlockFace.SOUTH,
                -5,
                -4,
                10,
                9,
                0,
                8,
                spec,
                0,
                3,
                false
        );

        HouseBuilder.buildHouse(null, lot, 64, setBlock, new Random(4))
                .forEach(Runnable::run);

        assertTrue(lot.hasWing());
        assertTrue(lot.reservedMaxX() > lot.maxX());
        assertTrue(blocks.entrySet().stream().anyMatch(entry ->
                xOf(entry.getKey()) > lot.maxX() + 1
                        && (entry.getValue() == Material.SPRUCE_STAIRS
                        || entry.getValue() == Material.SPRUCE_SLAB)
        ));
        // Le raccord entre les deux volumes comporte une ouverture praticable.
        assertEquals(Material.AIR, blocks.get(key(lot.maxX(), 65, -3)));
    }

    @Test
    void marketAndGreenLotsAddOutdoorDetails() {
        Map<String, Material> blocks = new HashMap<>();
        TerrainManager.SetBlock setBlock =
                (x, y, z, material) -> blocks.put(key(x, y, z), material);

        VillageLayoutPlan.LotPlan marketLot = new VillageLayoutPlan.LotPlan(
                2,
                2,
                0,
                8,
                VillageLayoutPlan.LotRole.MARKET,
                BlockFace.NORTH,
                -3,
                5,
                7,
                7,
                0,
                4,
                null,
                0,
                2,
                true
        );
        VillageLayoutPlan.LotPlan greenLot = new VillageLayoutPlan.LotPlan(
                0,
                0,
                12,
                -10,
                VillageLayoutPlan.LotRole.GREEN,
                BlockFace.SOUTH,
                8,
                -14,
                8,
                8,
                12,
                -9,
                null,
                1,
                2,
                false
        );

        SpecialBuildings.buildMarketStall(
                null,
                marketLot,
                64,
                setBlock,
                new Random(2)
        ).forEach(Runnable::run);
        SpecialBuildings.buildGreenLot(
                greenLot,
                65,
                setBlock,
                VillageLayoutPlan.LandmarkType.CHERRY
        ).forEach(Runnable::run);

        EnumSet<Material> values = EnumSet.copyOf(blocks.values());
        assertTrue(values.contains(Material.RED_WOOL)
                || values.contains(Material.GREEN_WOOL));
        assertTrue(values.contains(Material.WHITE_WOOL));
        assertTrue(values.contains(Material.CHEST));
        assertTrue(values.contains(Material.CHERRY_LEAVES));
        assertTrue(values.contains(Material.MOSS_BLOCK));
    }

    @Test
    void decorativeLayerKeepsDistrictFeaturesEvenInDenseLayouts() {
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
        Random random = new Random(1);
        VillageLayoutPlan layout = VillageLayoutPlanner.plan(
                new Location(null, 0, 64, 0),
                settings,
                random
        );
        Map<String, Material> blocks = new HashMap<>();
        TerrainManager.SetBlock setBlock =
                (x, y, z, material) -> blocks.put(key(x, y, z), material);

        VillageDecorationBuilder.build(
                null,
                layout,
                settings,
                64,
                setBlock,
                random
        ).forEach(Runnable::run);

        EnumSet<Material> values = EnumSet.copyOf(blocks.values());
        assertTrue(values.contains(Material.MOSSY_COBBLESTONE_WALL));
        assertTrue(values.contains(Material.IRON_BARS));
        assertTrue(values.contains(Material.BEE_NEST));
        assertTrue(values.contains(Material.RAW_IRON_BLOCK));
        assertTrue(values.contains(Material.COAL_BLOCK));
        assertTrue(values.contains(Material.OAK_LEAVES));
    }

    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    private static int xOf(String key) {
        return Integer.parseInt(key.split(":")[0]);
    }

    private static int yOf(String key) {
        return Integer.parseInt(key.split(":")[1]);
    }
}
