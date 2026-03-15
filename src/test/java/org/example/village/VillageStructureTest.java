package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageStructureTest {

    @Test
    void wallHasCrenellationsGatehouseAndTowers() {
        Map<String, Material> blocks = new HashMap<>();
        Queue<Runnable> queue = new LinkedList<>();
        TerrainManager.SetBlock setBlock = (x, y, z, m) -> blocks.put(x + ":" + y + ":" + z, m);

        WallBuilder.build(new Location(null, 0, 64, 0), 10, 10, 64, Material.STONE_BRICKS, queue, setBlock);
        queue.forEach(Runnable::run);

        assertTrue(blocks.values().contains(Material.LANTERN));
        assertTrue(blocks.values().contains(Material.RED_BANNER));
        assertTrue(blocks.values().contains(Material.DARK_OAK_STAIRS));
        assertTrue(blocks.keySet().stream().anyMatch(k -> Integer.parseInt(k.split(":")[1]) >= 73));
    }

    @Test
    void churchContainsGlassAltarSeatsAndSteepRoof() {
        Map<String, Material> blocks = new HashMap<>();
        TerrainManager.SetBlock setBlock = (x, y, z, m) -> blocks.put(x + ":" + y + ":" + z, m);

        VillageLayoutPlan.LotPlan lot = new VillageLayoutPlan.LotPlan(
                0, 2, 0, 0,
                VillageLayoutPlan.LotRole.CHURCH,
                BlockFace.SOUTH,
                -6, -8, 13, 17,
                0, 1,
                null,
                2,
                3,
                true
        );

        SpecialBuildings.buildChurch(null, lot, 64, setBlock).forEach(Runnable::run);

        assertTrue(blocks.values().contains(Material.BLUE_STAINED_GLASS_PANE));
        assertTrue(blocks.values().contains(Material.QUARTZ_BLOCK));
        assertTrue(blocks.values().contains(Material.SPRUCE_STAIRS));
        assertTrue(blocks.values().contains(Material.DARK_OAK_STAIRS));
        assertTrue(blocks.values().contains(Material.GOLD_BLOCK));
    }

    @Test
    void forgeContainsWorkshopCoreAndChimney() {
        Map<String, Material> blocks = new HashMap<>();
        TerrainManager.SetBlock setBlock = (x, y, z, m) -> blocks.put(x + ":" + y + ":" + z, m);

        VillageLayoutPlan.LotPlan lot = new VillageLayoutPlan.LotPlan(
                3, 2, 0, 0,
                VillageLayoutPlan.LotRole.FORGE,
                BlockFace.NORTH,
                -5, -4, 12, 10,
                0, -1,
                null,
                1,
                2,
                true
        );

        SpecialBuildings.buildForge(null, lot, 64, setBlock).forEach(Runnable::run);

        assertTrue(blocks.values().contains(Material.BLAST_FURNACE));
        assertTrue(blocks.values().contains(Material.ANVIL));
        assertTrue(blocks.values().contains(Material.SMITHING_TABLE));
        assertTrue(blocks.values().contains(Material.BRICKS));
        assertTrue(blocks.values().contains(Material.CAMPFIRE));
        assertTrue(blocks.values().contains(Material.CHEST));
    }

    @Test
    void houseBuilderPlacesMedievalRoofWindowsDoorAndYard() {
        Map<String, Material> blocks = new HashMap<>();
        TerrainManager.SetBlock setBlock = (x, y, z, m) -> blocks.put(x + ":" + y + ":" + z, m);

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
                1, 1, 0, 0,
                VillageLayoutPlan.LotRole.HOUSE_TWO_STORY,
                BlockFace.SOUTH,
                -3, -10, 7, 9,
                0, 1,
                spec,
                1,
                3,
                false
        );

        HouseBuilder.buildHouse(null, lot, 64, setBlock, new Random(1)).forEach(Runnable::run);
        EnumSet<Material> values = EnumSet.copyOf(blocks.values());

        assertTrue(values.contains(Material.SPRUCE_STAIRS));
        assertTrue(values.contains(Material.SPRUCE_SLAB));
        assertTrue(values.contains(Material.GLASS_PANE));
        assertTrue(values.contains(Material.SPRUCE_DOOR));
        assertTrue(values.contains(Material.LANTERN));
        assertTrue(values.contains(Material.RED_BANNER) || values.contains(Material.YELLOW_BANNER));
        assertTrue(values.contains(Material.POPPY) || values.contains(Material.BLUE_ORCHID));
    }

    @Test
    void marketAndGreenLotsAddOutdoorDetails() {
        Map<String, Material> blocks = new HashMap<>();
        TerrainManager.SetBlock setBlock = (x, y, z, m) -> blocks.put(x + ":" + y + ":" + z, m);

        VillageLayoutPlan.LotPlan marketLot = new VillageLayoutPlan.LotPlan(
                2, 2, 0, 8,
                VillageLayoutPlan.LotRole.MARKET,
                BlockFace.NORTH,
                -3, 5, 7, 7,
                0, 4,
                null,
                0,
                2,
                true
        );
        VillageLayoutPlan.LotPlan greenLot = new VillageLayoutPlan.LotPlan(
                0, 0, 12, -10,
                VillageLayoutPlan.LotRole.GREEN,
                BlockFace.SOUTH,
                8, -14, 8, 8,
                12, -9,
                null,
                1,
                2,
                false
        );

        SpecialBuildings.buildMarketStall(null, marketLot, 64, setBlock, new Random(2)).forEach(Runnable::run);
        SpecialBuildings.buildGreenLot(greenLot, 65, setBlock, VillageLayoutPlan.LandmarkType.CHERRY).forEach(Runnable::run);

        EnumSet<Material> values = EnumSet.copyOf(blocks.values());
        assertTrue(values.contains(Material.RED_WOOL) || values.contains(Material.YELLOW_WOOL));
        assertTrue(values.contains(Material.CHEST));
        assertTrue(values.contains(Material.CHERRY_LEAVES));
        assertTrue(values.contains(Material.MOSS_BLOCK));
    }
}
