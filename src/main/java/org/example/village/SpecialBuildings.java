package org.example.village;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.example.village.VillageLayoutPlan.LandmarkType;
import static org.example.village.VillageLayoutPlan.LotPlan;

/**
 * Bâtiments spéciaux du village.
 *
 * Ces bâtiments servent de repères visuels forts pour éviter l'effet
 * "copier/coller". Chacun reçoit une silhouette plus marquée et un
 * aménagement interne simple mais crédible.
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
        int towerX = minX + 1;
        int towerZ = maxZ - 5;
        int naveTop = baseY + 8;

        // Fondation et sol intérieur.
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                place(tasks, sb, x, baseY - 1, z, (x + z) % 3 == 0 ? Material.STONE_BRICKS : Material.MOSSY_STONE_BRICKS);
                boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
                boolean aisle = Math.abs(x - centerX) <= 1;
                place(tasks, sb, x, baseY, z, edge ? Material.POLISHED_ANDESITE : aisle ? Material.SMOOTH_STONE : Material.STONE_BRICKS);
            }
        }

        // Murs de la nef.
        for (int y = baseY + 1; y <= naveTop; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean shell = x == minX || x == maxX || z == minZ || z == maxZ;
                    if (!shell) {
                        continue;
                    }
                    boolean frontDoor = z == maxZ && Math.abs(x - centerX) <= 1 && y <= baseY + 2;
                    boolean stained = (x == minX || x == maxX)
                            && y >= baseY + 2 && y <= baseY + 5
                            && ((z == minZ + 4) || (z == maxZ - 4) || (z == centerZ));
                    if (frontDoor) {
                        continue;
                    }
                    place(tasks, sb, x, y, z, stained ? Material.BLUE_STAINED_GLASS_PANE : Material.STONE_BRICKS);
                }
            }
        }

        // Contreforts latéraux.
        for (int z = minZ + 3; z <= maxZ - 3; z += 3) {
            for (int y = baseY + 1; y <= baseY + 5; y++) {
                place(tasks, sb, minX - 1, y, z, Material.STONE_BRICK_WALL);
                place(tasks, sb, maxX + 1, y, z, Material.STONE_BRICK_WALL);
            }
        }

        // Clocher.
        for (int y = baseY + 1; y <= baseY + 12; y++) {
            for (int x = towerX; x <= towerX + 4; x++) {
                for (int z = towerZ; z <= towerZ + 4; z++) {
                    boolean shell = x == towerX || x == towerX + 4 || z == towerZ || z == towerZ + 4;
                    if (!shell) {
                        continue;
                    }
                    boolean opening = y >= baseY + 8 && y <= baseY + 10 && (x == towerX + 2 || z == towerZ + 2);
                    if (!opening) {
                        place(tasks, sb, x, y, z, y > baseY + 9 ? Material.COBBLESTONE : Material.STONE_BRICKS);
                    }
                }
            }
        }

        buildGable(tasks, world, sb, minX, maxX, minZ, maxZ, naveTop + 1, Material.DARK_OAK_STAIRS);
        buildTowerRoof(tasks, world, sb, towerX + 2, towerZ + 2, baseY + 13);

        // Porte principale.
        place(tasks, sb, centerX, baseY + 1, maxZ, Material.SPRUCE_DOOR);
        place(tasks, sb, centerX, baseY + 2, maxZ, Material.SPRUCE_DOOR);
        tasks.add(() -> VillageStyle.setDoor(world, centerX, baseY + 1, maxZ, Material.SPRUCE_DOOR, BlockFace.SOUTH, Bisected.Half.BOTTOM));
        tasks.add(() -> VillageStyle.setDoor(world, centerX, baseY + 2, maxZ, Material.SPRUCE_DOOR, BlockFace.SOUTH, Bisected.Half.TOP));

        // Escaliers d'entrée.
        stair(tasks, world, sb, centerX, baseY, maxZ + 1, Material.STONE_BRICK_STAIRS, BlockFace.SOUTH);
        stair(tasks, world, sb, centerX - 1, baseY, maxZ + 1, Material.STONE_BRICK_STAIRS, BlockFace.SOUTH);
        stair(tasks, world, sb, centerX + 1, baseY, maxZ + 1, Material.STONE_BRICK_STAIRS, BlockFace.SOUTH);

        // Bancs intérieurs.
        for (int z = minZ + 4; z <= maxZ - 4; z += 2) {
            stair(tasks, world, sb, centerX - 3, baseY + 1, z, Material.SPRUCE_STAIRS, BlockFace.EAST);
            stair(tasks, world, sb, centerX - 2, baseY + 1, z, Material.SPRUCE_STAIRS, BlockFace.EAST);
            stair(tasks, world, sb, centerX + 2, baseY + 1, z, Material.SPRUCE_STAIRS, BlockFace.WEST);
            stair(tasks, world, sb, centerX + 3, baseY + 1, z, Material.SPRUCE_STAIRS, BlockFace.WEST);
        }

        // Autel / chœur.
        place(tasks, sb, centerX, baseY + 1, minZ + 2, Material.QUARTZ_BLOCK);
        place(tasks, sb, centerX - 1, baseY + 1, minZ + 2, Material.QUARTZ_BLOCK);
        place(tasks, sb, centerX + 1, baseY + 1, minZ + 2, Material.QUARTZ_BLOCK);
        place(tasks, sb, centerX, baseY + 2, minZ + 2, Material.CANDLE);
        place(tasks, sb, centerX - 1, baseY + 2, minZ + 2, Material.CANDLE);
        place(tasks, sb, centerX + 1, baseY + 2, minZ + 2, Material.CANDLE);
        place(tasks, sb, centerX, baseY + 3, minZ + 1, Material.GOLD_BLOCK);
        place(tasks, sb, centerX, baseY + 4, minZ + 1, Material.LANTERN);
        place(tasks, sb, centerX, baseY + 5, minZ + 1, Material.CHAIN);

        // Lustre au centre de la nef.
        place(tasks, sb, centerX, baseY + 6, centerZ, Material.CHAIN);
        place(tasks, sb, centerX, baseY + 5, centerZ, Material.LANTERN);
        place(tasks, sb, centerX - 1, baseY + 5, centerZ, Material.LANTERN);
        place(tasks, sb, centerX + 1, baseY + 5, centerZ, Material.LANTERN);

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

        // Sol et fondations.
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                place(tasks, sb, x, baseY - 1, z, Material.COBBLESTONE);
                boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
                place(tasks, sb, x, baseY, z, edge ? Material.STONE_BRICKS : ((x + z) % 2 == 0 ? Material.COBBLED_DEEPSLATE : Material.GRAVEL));
            }
        }

        // Murs avec large ouverture frontale.
        for (int y = baseY + 1; y <= baseY + 5; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean shell = x == minX || x == maxX || z == minZ || z == maxZ;
                    boolean frontOpen = x == minX && Math.abs(z - centerZ) <= 1 && y <= baseY + 3;
                    boolean sideOpen = z == maxZ && x >= centerX - 2 && x <= centerX + 1 && y <= baseY + 3;
                    if (shell && !frontOpen && !sideOpen) {
                        place(tasks, sb, x, y, z, y == baseY + 5 ? Material.STRIPPED_SPRUCE_LOG : Material.STONE_BRICKS);
                    }
                }
            }
        }

        // Toit principal.
        for (int x = minX; x <= maxX; x++) {
            stair(tasks, world, sb, x, baseY + 6, minZ - 1, Material.SPRUCE_STAIRS, BlockFace.NORTH);
            stair(tasks, world, sb, x, baseY + 6, maxZ + 1, Material.SPRUCE_STAIRS, BlockFace.SOUTH);
        }
        for (int z = minZ; z <= maxZ; z++) {
            slab(tasks, world, sb, centerX, baseY + 7, z, Material.SPRUCE_SLAB, Slab.Type.TOP);
        }

        // Appentis latéral / atelier extérieur.
        buildLeanTo(tasks, world, sb, maxX - 4, maxZ, baseY + 4, BlockFace.SOUTH);
        place(tasks, sb, maxX - 2, baseY + 1, maxZ - 1, Material.ANVIL);
        place(tasks, sb, maxX - 3, baseY + 1, maxZ - 1, Material.GRINDSTONE);
        place(tasks, sb, maxX - 4, baseY + 1, maxZ - 1, Material.BARREL);

        // Poste de travail intérieur.
        place(tasks, sb, centerX, baseY + 1, minZ + 2, Material.BLAST_FURNACE);
        place(tasks, sb, centerX + 1, baseY + 1, minZ + 2, Material.SMITHING_TABLE);
        place(tasks, sb, centerX - 1, baseY + 1, minZ + 2, Material.ANVIL);
        place(tasks, sb, minX + 2, baseY + 1, maxZ - 2, Material.BARREL);
        place(tasks, sb, minX + 3, baseY + 1, maxZ - 2, Material.CHEST);
        place(tasks, sb, minX + 4, baseY + 1, maxZ - 2, Material.LAVA_CAULDRON);

        // Cheminée plus marquée.
        for (int y = baseY + 1; y <= baseY + 8; y++) {
            place(tasks, sb, maxX - 1, y, minZ + 1, y % 2 == 0 ? Material.COBBLESTONE : Material.BRICKS);
        }
        place(tasks, sb, maxX - 1, baseY + 9, minZ + 1, Material.CAMPFIRE);
        place(tasks, sb, maxX - 1, baseY + 10, minZ + 1, Material.IRON_BARS);

        // Cour de forge devant.
        for (int x = minX - 3; x <= minX - 1; x++) {
            for (int z = centerZ - 2; z <= centerZ + 2; z++) {
                place(tasks, sb, x, baseY, z, (x + z) % 2 == 0 ? Material.GRAVEL : Material.COARSE_DIRT);
            }
        }
        place(tasks, sb, minX - 2, baseY + 1, centerZ - 1, Material.ANVIL);
        place(tasks, sb, minX - 3, baseY + 1, centerZ, Material.BARREL);
        place(tasks, sb, minX - 1, baseY + 1, centerZ + 1, Material.HAY_BLOCK);
        return tasks;
    }

    public static List<Runnable> buildMarketStall(World world, LotPlan lot, int baseY, TerrainManager.SetBlock sb, Random rng) {
        List<Runnable> tasks = new ArrayList<>();
        int minX = lot.buildX();
        int minZ = lot.buildZ();
        int maxX = minX + lot.footprintWidth() - 1;
        int maxZ = minZ + lot.footprintDepth() - 1;
        Random random = rng != null ? rng : new Random();

        Material[] stripe = random.nextBoolean()
                ? new Material[]{Material.RED_WOOL, Material.WHITE_WOOL}
                : new Material[]{Material.GREEN_WOOL, Material.WHITE_WOOL};

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                place(tasks, sb, x, baseY - 1, z, Material.COBBLESTONE);
                place(tasks, sb, x, baseY, z, (x + z) % 2 == 0 ? Material.SPRUCE_PLANKS : Material.OAK_PLANKS);
            }
        }

        // Poteaux.
        for (int x : new int[]{minX, maxX}) {
            for (int z : new int[]{minZ, maxZ}) {
                for (int y = baseY + 1; y <= baseY + 3; y++) {
                    place(tasks, sb, x, y, z, Material.SPRUCE_LOG);
                }
            }
        }

        // Toile rayée.
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                place(tasks, sb, x, baseY + 4, z, stripe[Math.abs(x - minX + z - minZ) % stripe.length]);
            }
        }

        // Comptoir et marchandises.
        place(tasks, sb, minX + 1, baseY + 1, maxZ - 1, Material.BARREL);
        place(tasks, sb, minX + 2, baseY + 1, maxZ - 1, Material.CHEST);
        place(tasks, sb, maxX - 1, baseY + 1, maxZ - 1, random.nextBoolean() ? Material.PUMPKIN : Material.MELON);
        place(tasks, sb, maxX - 2, baseY + 1, maxZ - 1, Material.HAY_BLOCK);
        place(tasks, sb, (minX + maxX) / 2, baseY + 1, minZ + 1, random.nextBoolean() ? Material.CAKE : Material.BARREL);
        place(tasks, sb, (minX + maxX) / 2, baseY + 2, minZ + 1, Material.LANTERN);
        place(tasks, sb, (minX + maxX) / 2, baseY + 3, minZ + 1, Material.CHAIN);
        return tasks;
    }

    public static List<Runnable> buildGreenLot(LotPlan lot, int baseY, TerrainManager.SetBlock sb, LandmarkType type) {
        List<Runnable> tasks = new ArrayList<>();
        for (int x = lot.minX(); x <= lot.maxX(); x++) {
            for (int z = lot.minZ(); z <= lot.maxZ(); z++) {
                boolean edge = x == lot.minX() || x == lot.maxX() || z == lot.minZ() || z == lot.maxZ();
                boolean cross = x == lot.centerX() || z == lot.centerZ();
                place(tasks, sb, x, baseY, z, edge ? Material.MOSS_BLOCK : cross ? Material.PACKED_MUD : Material.GRASS_BLOCK);
            }
        }
        tasks.addAll(buildLandmark(type, lot.centerX(), baseY + 1, lot.centerZ(), sb));
        place(tasks, sb, lot.minX(), baseY + 1, lot.minZ(), Material.LANTERN);
        place(tasks, sb, lot.maxX(), baseY + 1, lot.maxZ(), Material.LANTERN);
        place(tasks, sb, lot.minX(), baseY + 1, lot.maxZ(), Material.LANTERN);
        place(tasks, sb, lot.maxX(), baseY + 1, lot.minZ(), Material.LANTERN);
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

        // Coin stockage.
        place(tasks, sb, lot.minX() + 1, baseY + 1, lot.minZ() + 1, Material.BARREL);
        place(tasks, sb, lot.minX() + 2, baseY + 1, lot.minZ() + 1, Material.CHEST);
        place(tasks, sb, lot.minX() + 1, baseY + 1, lot.minZ() + 2, Material.HAY_BLOCK);

        // Petit chariot/atelier.
        int wx = lot.centerX();
        int wz = lot.centerZ();
        place(tasks, sb, wx - 1, baseY + 1, wz, Material.BARREL);
        place(tasks, sb, wx + 1, baseY + 1, wz, Material.BARREL);
        place(tasks, sb, wx, baseY + 1, wz, Material.CHEST);
        stair(tasks, world, sb, wx - 1, baseY + 1, wz - 1, Material.OAK_STAIRS, BlockFace.SOUTH);
        stair(tasks, world, sb, wx + 1, baseY + 1, wz - 1, Material.OAK_STAIRS, BlockFace.SOUTH);
        place(tasks, sb, wx, baseY + 2, wz, Material.SPRUCE_TRAPDOOR);

        // Atelier ouvert.
        place(tasks, sb, lot.maxX() - 1, baseY + 1, lot.maxZ() - 1, Material.CRAFTING_TABLE);
        place(tasks, sb, lot.maxX() - 2, baseY + 1, lot.maxZ() - 1, Material.SMITHING_TABLE);
        place(tasks, sb, lot.maxX() - 3, baseY + 1, lot.maxZ() - 1, Material.GRINDSTONE);
        place(tasks, sb, lot.centerX(), baseY + 1, lot.maxZ() - 2, Material.CAMPFIRE);
        return tasks;
    }

    public static List<Runnable> buildDecorLot(int baseX, int baseY, int baseZ,
                                               TerrainManager.SetBlock sb,
                                               Random rng) {
        Random random = rng != null ? rng : new Random();
        LandmarkType type = LandmarkType.values()[random.nextInt(LandmarkType.values().length)];
        List<Runnable> tasks = new ArrayList<>(buildLandmark(type, baseX + 3, baseY, baseZ + 3, sb));
        if (random.nextBoolean()) {
            place(tasks, sb, baseX + 1, baseY, baseZ + 1, Material.GRAVEL);
            place(tasks, sb, baseX + 1, baseY + 1, baseZ + 1, Material.BARREL);
            place(tasks, sb, baseX + 2, baseY + 1, baseZ + 1, Material.CHEST);
        }
        return tasks;
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
        place(tasks, sb, x - 1, y + 1, z, Material.STONE_BRICK_WALL);
        place(tasks, sb, x + 1, y + 1, z, Material.STONE_BRICK_WALL);
        return tasks;
    }

    private static List<Runnable> garden(int x, int y, int z, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                place(tasks, sb, x + dx, y, z + dz, edge ? Material.COBBLESTONE_WALL : Material.MOSS_BLOCK);
            }
        }
        place(tasks, sb, x, y + 1, z, Material.FLOWERING_AZALEA);
        place(tasks, sb, x - 1, y + 1, z, Material.PEONY);
        place(tasks, sb, x + 1, y + 1, z, Material.LILAC);
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
        place(tasks, sb, x - 1, y + 1, z + 2, Material.PINK_PETALS);
        place(tasks, sb, x + 1, y + 1, z - 2, Material.PINK_PETALS);
        return tasks;
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
            slab(tasks, world, sb, x, y + 3, (minZ + maxZ) / 2, Material.DARK_OAK_SLAB, Slab.Type.TOP);
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
        slab(tasks, world, sb, centerX, y + 2, centerZ, Material.DARK_OAK_SLAB, Slab.Type.TOP);
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
        tasks.add(() -> VillageStyle.setStair(world, x, y, z, material, facing, Stairs.Half.BOTTOM, Stairs.Shape.STRAIGHT));
    }

    private static void slab(List<Runnable> tasks,
                             World world,
                             TerrainManager.SetBlock sb,
                             int x,
                             int y,
                             int z,
                             Material material,
                             Slab.Type type) {
        place(tasks, sb, x, y, z, material);
        tasks.add(() -> VillageStyle.setSlab(world, x, y, z, material, type));
    }

    private static void place(List<Runnable> tasks, TerrainManager.SetBlock sb, int x, int y, int z, Material material) {
        tasks.add(() -> sb.set(x, y, z, material));
    }
}
