package org.example.village;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Stairs;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.example.village.VillageLayoutPlan.LandmarkType;
import static org.example.village.VillageLayoutPlan.LotPlan;

/**
 * Batiments speciaux du village.
 */
public final class SpecialBuildings {

    private SpecialBuildings() {}

    public static List<Runnable> buildChurch(World world, LotPlan lot, int baseY, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        int minX = lot.buildX();
        int minZ = lot.buildZ();
        int maxX = minX + lot.footprintWidth() - 1;
        int maxZ = minZ + lot.footprintDepth() - 1;
        int centerX = (minX + maxX) / 2;
        int centerZ = (minZ + maxZ) / 2;
        int apseZ = minZ + 2;
        int towerX = minX + 1;
        int towerZ = maxZ - 4;
        int naveTop = baseY + 7;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                place(tasks, sb, x, baseY - 1, z, Material.STONE_BRICKS);
                place(tasks, sb, x, baseY, z, (x == minX || x == maxX || z == minZ || z == maxZ)
                        ? Material.POLISHED_ANDESITE : Material.SMOOTH_STONE);
            }
        }

        for (int y = baseY + 1; y <= naveTop; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
                    if (!edge) {
                        continue;
                    }
                    boolean stained = (x == minX || x == maxX) && y >= baseY + 2 && y <= baseY + 5 && (z == minZ + 4 || z == maxZ - 4);
                    boolean frontDoor = z == maxZ && x >= centerX - 1 && x <= centerX + 1 && y <= baseY + 2;
                    if (frontDoor) {
                        continue;
                    }
                    place(tasks, sb, x, y, z, stained ? Material.BLUE_STAINED_GLASS_PANE : Material.STONE_BRICKS);
                }
            }
        }

        for (int z = minZ + 3; z <= maxZ - 4; z += 3) {
            for (int y = baseY + 1; y <= baseY + 5; y++) {
                place(tasks, sb, minX - 1, y, z, Material.STONE_BRICK_WALL);
                place(tasks, sb, maxX + 1, y, z, Material.STONE_BRICK_WALL);
            }
        }

        for (int y = baseY + 1; y <= baseY + 11; y++) {
            for (int x = towerX; x <= towerX + 3; x++) {
                for (int z = towerZ; z <= towerZ + 3; z++) {
                    boolean shell = x == towerX || x == towerX + 3 || z == towerZ || z == towerZ + 3;
                    if (shell) {
                        place(tasks, sb, x, y, z, y > baseY + 8 ? Material.COBBLESTONE : Material.STONE_BRICKS);
                    }
                }
            }
        }

        buildGable(tasks, world, sb, minX, maxX, minZ, maxZ, naveTop + 1, Material.DARK_OAK_STAIRS);
        buildTowerRoof(tasks, world, sb, towerX + 1, towerZ + 1, baseY + 12);
        buildApse(tasks, world, sb, centerX, apseZ, baseY);

        place(tasks, sb, centerX, baseY + 1, maxZ, Material.SPRUCE_DOOR);
        place(tasks, sb, centerX, baseY + 2, maxZ, Material.SPRUCE_DOOR);
        if (world != null) {
            tasks.add(() -> VillageStyle.setDoor(world, centerX, baseY + 1, maxZ, Material.SPRUCE_DOOR, BlockFace.SOUTH, org.bukkit.block.data.Bisected.Half.BOTTOM));
            tasks.add(() -> VillageStyle.setDoor(world, centerX, baseY + 2, maxZ, Material.SPRUCE_DOOR, BlockFace.SOUTH, org.bukkit.block.data.Bisected.Half.TOP));
        }

        for (int z = minZ + 4; z <= maxZ - 4; z += 2) {
            stair(tasks, world, sb, centerX - 2, baseY + 1, z, Material.SPRUCE_STAIRS, BlockFace.EAST);
            stair(tasks, world, sb, centerX + 2, baseY + 1, z, Material.SPRUCE_STAIRS, BlockFace.WEST);
        }

        place(tasks, sb, centerX, baseY + 1, apseZ + 1, Material.QUARTZ_BLOCK);
        place(tasks, sb, centerX, baseY + 2, apseZ + 1, Material.CANDLE);
        place(tasks, sb, centerX - 1, baseY + 2, apseZ + 1, Material.CANDLE);
        place(tasks, sb, centerX + 1, baseY + 2, apseZ + 1, Material.CANDLE);
        place(tasks, sb, centerX, baseY + 3, apseZ, Material.GOLD_BLOCK);
        place(tasks, sb, centerX, baseY + 4, apseZ, Material.LANTERN);
        place(tasks, sb, centerX, baseY + 6, maxZ - 5, Material.CHAIN);
        place(tasks, sb, centerX, baseY + 5, maxZ - 5, Material.LANTERN);

        return tasks;
    }

    public static List<Runnable> buildForge(World world, LotPlan lot, int baseY, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        int minX = lot.buildX();
        int minZ = lot.buildZ();
        int maxX = minX + lot.footprintWidth() - 1;
        int maxZ = minZ + lot.footprintDepth() - 1;
        int centerX = (minX + maxX) / 2;
        int centerZ = (minZ + maxZ) / 2;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                place(tasks, sb, x, baseY - 1, z, Material.COBBLESTONE);
                place(tasks, sb, x, baseY, z, (x == minX || x == maxX || z == minZ || z == maxZ)
                        ? Material.STONE_BRICKS : Material.COBBLED_DEEPSLATE);
            }
        }

        for (int y = baseY + 1; y <= baseY + 5; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean shell = x == minX || x == maxX || z == minZ || z == maxZ;
                    boolean openFront = x == minX && z >= centerZ - 1 && z <= centerZ + 1 && y <= baseY + 3;
                    boolean openSide = z == maxZ && x >= centerX - 2 && x <= centerX + 1 && y <= baseY + 3;
                    if (shell && !openFront && !openSide) {
                        place(tasks, sb, x, y, z, y == baseY + 5 ? Material.STRIPPED_SPRUCE_LOG : Material.STONE_BRICKS);
                    }
                }
            }
        }

        buildLeanTo(tasks, world, sb, maxX - 3, maxZ, baseY + 4, BlockFace.SOUTH);
        for (int x = minX; x <= maxX; x++) {
            stair(tasks, world, sb, x, baseY + 6, minZ - 1, Material.SPRUCE_STAIRS, BlockFace.NORTH);
        }
        for (int z = minZ; z <= maxZ; z++) {
            stair(tasks, world, sb, minX - 1, baseY + 6, z, Material.SPRUCE_STAIRS, BlockFace.WEST);
        }

        place(tasks, sb, centerX, baseY + 1, minZ + 2, Material.BLAST_FURNACE);
        place(tasks, sb, centerX + 1, baseY + 1, minZ + 2, Material.ANVIL);
        place(tasks, sb, centerX - 1, baseY + 1, minZ + 2, Material.SMITHING_TABLE);
        place(tasks, sb, minX + 2, baseY + 1, maxZ - 2, Material.BARREL);
        place(tasks, sb, minX + 3, baseY + 1, maxZ - 2, Material.BARREL);
        place(tasks, sb, maxX - 2, baseY + 1, maxZ - 2, Material.CAMPFIRE);
        place(tasks, sb, maxX - 3, baseY + 1, maxZ - 2, Material.CHEST);

        for (int y = baseY + 1; y <= baseY + 8; y++) {
            place(tasks, sb, maxX - 1, y, minZ + 1, Material.BRICKS);
        }
        place(tasks, sb, maxX - 1, baseY + 9, minZ + 1, Material.BRICK_SLAB);

        place(tasks, sb, minX - 2, baseY, maxZ - 1, Material.GRAVEL);
        place(tasks, sb, minX - 2, baseY + 1, maxZ - 1, Material.ANVIL);
        place(tasks, sb, minX - 3, baseY, maxZ - 2, Material.COARSE_DIRT);
        place(tasks, sb, minX - 3, baseY + 1, maxZ - 2, Material.BARREL);
        place(tasks, sb, centerX, baseY + 6, maxZ - 1, Material.CHAIN);
        place(tasks, sb, centerX, baseY + 5, maxZ - 1, Material.LANTERN);

        return tasks;
    }

    public static List<Runnable> buildMarketStall(World world, LotPlan lot, int baseY, TerrainManager.SetBlock sb, Random rng) {
        List<Runnable> tasks = new ArrayList<>();
        int minX = lot.buildX();
        int minZ = lot.buildZ();
        int maxX = minX + lot.footprintWidth() - 1;
        int maxZ = minZ + lot.footprintDepth() - 1;
        Material wool = rng != null && rng.nextBoolean() ? Material.RED_WOOL : Material.YELLOW_WOOL;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                place(tasks, sb, x, baseY - 1, z, Material.COBBLESTONE);
                place(tasks, sb, x, baseY, z, Material.SPRUCE_PLANKS);
            }
        }

        for (int x : new int[]{minX, maxX}) {
            for (int z : new int[]{minZ, maxZ}) {
                for (int y = baseY + 1; y <= baseY + 3; y++) {
                    place(tasks, sb, x, y, z, Material.SPRUCE_LOG);
                }
            }
        }

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (x == minX || x == maxX || z == minZ || z == maxZ) {
                    place(tasks, sb, x, baseY + 4, z, wool);
                } else {
                    place(tasks, sb, x, baseY + 4, z, Material.WHITE_WOOL);
                }
            }
        }

        place(tasks, sb, minX + 1, baseY + 1, maxZ - 1, Material.BARREL);
        place(tasks, sb, maxX - 1, baseY + 1, maxZ - 1, Material.CHEST);
        place(tasks, sb, (minX + maxX) / 2, baseY + 1, minZ + 1, Material.LANTERN);
        return tasks;
    }

    public static List<Runnable> buildGreenLot(LotPlan lot, int baseY, TerrainManager.SetBlock sb, LandmarkType type) {
        List<Runnable> tasks = new ArrayList<>();
        for (int x = lot.minX(); x <= lot.maxX(); x++) {
            for (int z = lot.minZ(); z <= lot.maxZ(); z++) {
                boolean edge = x == lot.minX() || x == lot.maxX() || z == lot.minZ() || z == lot.maxZ();
                place(tasks, sb, x, baseY, z, edge ? Material.MOSS_BLOCK : Material.GRASS_BLOCK);
            }
        }
        tasks.addAll(buildLandmark(type, lot.centerX(), baseY + 1, lot.centerZ(), sb));
        place(tasks, sb, lot.minX(), baseY + 1, lot.minZ(), Material.LANTERN);
        place(tasks, sb, lot.maxX(), baseY + 1, lot.maxZ(), Material.LANTERN);
        return tasks;
    }

    public static List<Runnable> buildServiceYard(World world, LotPlan lot, int baseY, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        for (int x = lot.minX(); x <= lot.maxX(); x++) {
            for (int z = lot.minZ(); z <= lot.maxZ(); z++) {
                boolean stripe = (x + z) % 3 == 0;
                place(tasks, sb, x, baseY, z, stripe ? Material.GRAVEL : Material.COARSE_DIRT);
            }
        }
        place(tasks, sb, lot.minX() + 1, baseY + 1, lot.minZ() + 1, Material.BARREL);
        place(tasks, sb, lot.minX() + 2, baseY + 1, lot.minZ() + 1, Material.CHEST);
        place(tasks, sb, lot.maxX() - 1, baseY + 1, lot.maxZ() - 1, Material.CRAFTING_TABLE);
        place(tasks, sb, lot.maxX() - 2, baseY + 1, lot.maxZ() - 1, Material.SMITHING_TABLE);
        place(tasks, sb, lot.centerX(), baseY + 1, lot.centerZ(), Material.CAMPFIRE);
        for (int y = baseY + 1; y <= baseY + 3; y++) {
            place(tasks, sb, lot.centerX() - 2, y, lot.centerZ() - 2, Material.SPRUCE_LOG);
        }
        place(tasks, sb, lot.centerX() - 3, baseY + 1, lot.centerZ() - 2, Material.OAK_FENCE);
        place(tasks, sb, lot.centerX() - 1, baseY + 1, lot.centerZ() - 2, Material.OAK_FENCE);
        return tasks;
    }

    public static List<Runnable> buildDecorLot(int baseX, int baseY, int baseZ,
                                               TerrainManager.SetBlock sb,
                                               Random rng) {
        LandmarkType type = LandmarkType.values()[rng.nextInt(LandmarkType.values().length)];
        return buildLandmark(type, baseX + 3, baseY, baseZ + 3, sb);
    }

    public static List<Runnable> buildLandmark(LandmarkType type, int x, int y, int z,
                                               TerrainManager.SetBlock sb) {
        return switch (type) {
            case STATUE -> statue(x, y, z, sb);
            case GARDEN -> garden(x, y, z, sb);
            case CHERRY -> cherry(x, y, z, sb);
        };
    }

    private static List<Runnable> statue(int x, int y, int z, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                place(tasks, sb, x + dx, y, z + dz, Material.STONE_BRICKS);
            }
        }
        place(tasks, sb, x, y + 1, z, Material.STONE_BRICK_WALL);
        place(tasks, sb, x, y + 2, z, Material.STONE_BRICK_WALL);
        place(tasks, sb, x, y + 3, z, Material.CHISELED_STONE_BRICKS);
        place(tasks, sb, x, y + 4, z, Material.LANTERN);
        return tasks;
    }

    private static List<Runnable> garden(int x, int y, int z, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                place(tasks, sb, x + dx, y, z + dz, Math.abs(dx) == 2 || Math.abs(dz) == 2 ? Material.COBBLESTONE_WALL : Material.MOSS_BLOCK);
            }
        }
        place(tasks, sb, x, y + 1, z, Material.FLOWERING_AZALEA);
        place(tasks, sb, x - 1, y + 1, z, Material.AZALEA);
        place(tasks, sb, x + 1, y + 1, z, Material.PEONY);
        place(tasks, sb, x, y + 1, z - 1, Material.LANTERN);
        return tasks;
    }

    private static List<Runnable> cherry(int x, int y, int z, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                place(tasks, sb, x + dx, y, z + dz, Material.STONE_BRICKS);
            }
        }
        for (int dy = 1; dy <= 4; dy++) {
            place(tasks, sb, x, y + dy, z, Material.CHERRY_LOG);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= 3) {
                    place(tasks, sb, x + dx, y + 4, z + dz, Material.CHERRY_LEAVES);
                }
            }
        }
        place(tasks, sb, x, y + 5, z, Material.CHERRY_LEAVES);
        return tasks;
    }

    private static void buildApse(List<Runnable> tasks,
                                  World world,
                                  TerrainManager.SetBlock sb,
                                  int centerX,
                                  int apseZ,
                                  int baseY) {
        place(tasks, sb, centerX - 1, baseY + 1, apseZ, Material.STONE_BRICKS);
        place(tasks, sb, centerX, baseY + 1, apseZ - 1, Material.STONE_BRICKS);
        place(tasks, sb, centerX + 1, baseY + 1, apseZ, Material.STONE_BRICKS);
        stair(tasks, world, sb, centerX - 1, baseY + 5, apseZ - 1, Material.DARK_OAK_STAIRS, BlockFace.NORTH);
        stair(tasks, world, sb, centerX, baseY + 6, apseZ - 2, Material.DARK_OAK_STAIRS, BlockFace.NORTH);
        stair(tasks, world, sb, centerX + 1, baseY + 5, apseZ - 1, Material.DARK_OAK_STAIRS, BlockFace.NORTH);
    }

    private static void buildGable(List<Runnable> tasks,
                                   World world,
                                   TerrainManager.SetBlock sb,
                                   int minX,
                                   int maxX,
                                   int minZ,
                                   int maxZ,
                                   int y,
                                   Material stair) {
        for (int layer = 0; layer < 3; layer++) {
            int lowZ = minZ - 1 + layer;
            int highZ = maxZ + 1 - layer;
            for (int x = minX; x <= maxX; x++) {
                stair(tasks, world, sb, x, y + layer, lowZ, stair, BlockFace.NORTH);
                stair(tasks, world, sb, x, y + layer, highZ, stair, BlockFace.SOUTH);
            }
        }
        for (int x = minX; x <= maxX; x++) {
            place(tasks, sb, x, y + 3, (minZ + maxZ) / 2, Material.DARK_OAK_SLAB);
        }
    }

    private static void buildTowerRoof(List<Runnable> tasks,
                                       World world,
                                       TerrainManager.SetBlock sb,
                                       int centerX,
                                       int centerZ,
                                       int y) {
        for (int layer = 0; layer < 2; layer++) {
            for (int x = centerX - 2 + layer; x <= centerX + 2 - layer; x++) {
                stair(tasks, world, sb, x, y + layer, centerZ - 2 + layer, Material.DARK_OAK_STAIRS, BlockFace.NORTH);
                stair(tasks, world, sb, x, y + layer, centerZ + 2 - layer, Material.DARK_OAK_STAIRS, BlockFace.SOUTH);
            }
            for (int z = centerZ - 1 + layer; z <= centerZ + 1 - layer; z++) {
                stair(tasks, world, sb, centerX - 2 + layer, y + layer, z, Material.DARK_OAK_STAIRS, BlockFace.WEST);
                stair(tasks, world, sb, centerX + 2 - layer, y + layer, z, Material.DARK_OAK_STAIRS, BlockFace.EAST);
            }
        }
        place(tasks, sb, centerX, y + 2, centerZ, Material.DARK_OAK_SLAB);
    }

    private static void buildLeanTo(List<Runnable> tasks,
                                    World world,
                                    TerrainManager.SetBlock sb,
                                    int x,
                                    int z,
                                    int y,
                                    BlockFace facing) {
        for (int dx = 0; dx < 4; dx++) {
            place(tasks, sb, x + dx, y - 1, z + 1, Material.STRIPPED_SPRUCE_LOG);
            stair(tasks, world, sb, x + dx, y, z + 1, Material.SPRUCE_STAIRS, facing);
            stair(tasks, world, sb, x + dx, y + 1, z, Material.SPRUCE_STAIRS, facing);
        }
    }

    private static void stair(List<Runnable> tasks,
                              World world,
                              TerrainManager.SetBlock sb,
                              int x,
                              int y,
                              int z,
                              Material material,
                              BlockFace facing) {
        place(tasks, sb, x, y, z, material);
        if (world != null) {
            tasks.add(() -> VillageStyle.setStair(world, x, y, z, material, facing, Stairs.Half.BOTTOM, Stairs.Shape.STRAIGHT));
        }
    }

    private static void place(List<Runnable> tasks, TerrainManager.SetBlock sb, int x, int y, int z, Material material) {
        tasks.add(() -> sb.set(x, y, z, material));
    }
}
