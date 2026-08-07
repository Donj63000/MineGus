package org.example.village;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;

import java.util.List;

/**
 * Générateur partagé des couvertures architecturales.
 *
 * <p>Les anciennes implémentations ne dessinaient que deux lignes d'escaliers
 * et laissaient l'intérieur des toits ouvert. Cette classe construit une coque
 * complète, remplit les pignons et garantit un faîtage continu, quelle que soit
 * l'orientation de la façade.</p>
 */
public final class VillageRoofBuilder {

    private VillageRoofBuilder() {}

    public static void buildGable(List<Runnable> tasks,
                                  World world,
                                  TerrainManager.SetBlock sb,
                                  int minX,
                                  int maxX,
                                  int minZ,
                                  int maxZ,
                                  int roofY,
                                  BlockFace facade,
                                  Material stairMaterial,
                                  Material slabMaterial,
                                  Material gableFill,
                                  Material gableBeam,
                                  Material atticWindow) {
        boolean ridgeAlongZ = facade == BlockFace.NORTH || facade == BlockFace.SOUTH;
        int crossSpan = ridgeAlongZ ? maxX - minX + 3 : maxZ - minZ + 3;
        int slopeLayers = Math.max(1, crossSpan / 2);

        for (int layer = 0; layer < slopeLayers; layer++) {
            int y = roofY + layer;
            if (ridgeAlongZ) {
                int westX = minX - 1 + layer;
                int eastX = maxX + 1 - layer;
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    if (westX < eastX) {
                        stair(tasks, world, sb, westX, y, z, stairMaterial, BlockFace.WEST);
                        stair(tasks, world, sb, eastX, y, z, stairMaterial, BlockFace.EAST);
                    } else {
                        slab(tasks, world, sb, westX, y, z, slabMaterial, Slab.Type.TOP);
                    }
                }
            } else {
                int northZ = minZ - 1 + layer;
                int southZ = maxZ + 1 - layer;
                for (int x = minX - 1; x <= maxX + 1; x++) {
                    if (northZ < southZ) {
                        stair(tasks, world, sb, x, y, northZ, stairMaterial, BlockFace.NORTH);
                        stair(tasks, world, sb, x, y, southZ, stairMaterial, BlockFace.SOUTH);
                    } else {
                        slab(tasks, world, sb, x, y, northZ, slabMaterial, Slab.Type.TOP);
                    }
                }
            }
        }

        /*
         * Le faîtage remplace la dernière rangée de pente au même niveau.
         * L'ancienne coordonnée {@code roofY + slopeLayers} le faisait flotter
         * un demi-bloc au-dessus de la couverture.
         */
        buildRidge(tasks, world, sb, minX, maxX, minZ, maxZ,
                roofY + slopeLayers - 1, ridgeAlongZ, slabMaterial);
        fillGableEnds(tasks, sb, minX, maxX, minZ, maxZ, roofY,
                slopeLayers, ridgeAlongZ, gableFill, gableBeam, atticWindow);
    }

    public static void buildHip(List<Runnable> tasks,
                                World world,
                                TerrainManager.SetBlock sb,
                                int minX,
                                int maxX,
                                int minZ,
                                int maxZ,
                                int roofY,
                                Material stairMaterial,
                                Material slabMaterial) {
        int layer = 0;
        int ringMinX = minX - 1;
        int ringMaxX = maxX + 1;
        int ringMinZ = minZ - 1;
        int ringMaxZ = maxZ + 1;

        while (ringMinX <= ringMaxX && ringMinZ <= ringMaxZ) {
            int y = roofY + layer;
            int width = ringMaxX - ringMinX + 1;
            int depth = ringMaxZ - ringMinZ + 1;

            if (width <= 2 || depth <= 2) {
                for (int x = ringMinX; x <= ringMaxX; x++) {
                    for (int z = ringMinZ; z <= ringMaxZ; z++) {
                        slab(tasks, world, sb, x, y, z, slabMaterial, Slab.Type.TOP);
                    }
                }
                break;
            }

            for (int x = ringMinX; x <= ringMaxX; x++) {
                stair(tasks, world, sb, x, y, ringMinZ, stairMaterial, BlockFace.NORTH);
                stair(tasks, world, sb, x, y, ringMaxZ, stairMaterial, BlockFace.SOUTH);
            }
            for (int z = ringMinZ + 1; z <= ringMaxZ - 1; z++) {
                stair(tasks, world, sb, ringMinX, y, z, stairMaterial, BlockFace.WEST);
                stair(tasks, world, sb, ringMaxX, y, z, stairMaterial, BlockFace.EAST);
            }

            ringMinX++;
            ringMaxX--;
            ringMinZ++;
            ringMaxZ--;
            layer++;
        }
    }

    private static void buildRidge(List<Runnable> tasks,
                                   World world,
                                   TerrainManager.SetBlock sb,
                                   int minX,
                                   int maxX,
                                   int minZ,
                                   int maxZ,
                                   int y,
                                   boolean ridgeAlongZ,
                                   Material slabMaterial) {
        if (ridgeAlongZ) {
            int ridgeA = (minX + maxX) / 2;
            int ridgeB = (minX + maxX + 1) / 2;
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                slab(tasks, world, sb, ridgeA, y, z, slabMaterial, Slab.Type.TOP);
                if (ridgeB != ridgeA) {
                    slab(tasks, world, sb, ridgeB, y, z, slabMaterial, Slab.Type.TOP);
                }
            }
        } else {
            int ridgeA = (minZ + maxZ) / 2;
            int ridgeB = (minZ + maxZ + 1) / 2;
            for (int x = minX - 1; x <= maxX + 1; x++) {
                slab(tasks, world, sb, x, y, ridgeA, slabMaterial, Slab.Type.TOP);
                if (ridgeB != ridgeA) {
                    slab(tasks, world, sb, x, y, ridgeB, slabMaterial, Slab.Type.TOP);
                }
            }
        }
    }

    private static void fillGableEnds(List<Runnable> tasks,
                                      TerrainManager.SetBlock sb,
                                      int minX,
                                      int maxX,
                                      int minZ,
                                      int maxZ,
                                      int roofY,
                                      int slopeLayers,
                                      boolean ridgeAlongZ,
                                      Material fill,
                                      Material beam,
                                      Material window) {
        int crossMin = ridgeAlongZ ? minX : minZ;
        int crossMax = ridgeAlongZ ? maxX : maxZ;
        int firstEnd = ridgeAlongZ ? minZ : minX;
        int secondEnd = ridgeAlongZ ? maxZ : maxX;

        for (int level = 0; level < slopeLayers; level++) {
            int rowMin = crossMin + level;
            int rowMax = crossMax - level;
            if (rowMin > rowMax) {
                break;
            }
            int y = roofY + level;
            for (int cross = rowMin; cross <= rowMax; cross++) {
                boolean structural = cross == rowMin
                        || cross == rowMax
                        || (cross == (crossMin + crossMax) / 2 && level % 2 == 0);
                Material material = structural ? beam : fill;
                placeAtEnd(tasks, sb, ridgeAlongZ, cross, y, firstEnd, material);
                placeAtEnd(tasks, sb, ridgeAlongZ, cross, y, secondEnd, material);
            }
        }

        // Petite fenêtre de grenier centrée, uniquement si le pignon est assez large.
        if (window != null && crossMax - crossMin >= 5 && slopeLayers >= 3) {
            int center = (crossMin + crossMax) / 2;
            int windowY = roofY + 1;
            placeAtEnd(tasks, sb, ridgeAlongZ, center, windowY, firstEnd, window);
            placeAtEnd(tasks, sb, ridgeAlongZ, center, windowY, secondEnd, window);
        }
    }

    private static void placeAtEnd(List<Runnable> tasks,
                                   TerrainManager.SetBlock sb,
                                   boolean ridgeAlongZ,
                                   int cross,
                                   int y,
                                   int end,
                                   Material material) {
        if (ridgeAlongZ) {
            place(tasks, sb, cross, y, end, material);
        } else {
            place(tasks, sb, end, y, cross, material);
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
        tasks.add(() -> VillageStyle.setStair(
                world, x, y, z, material, facing,
                Stairs.Half.BOTTOM, Stairs.Shape.STRAIGHT));
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

    private static void place(List<Runnable> tasks,
                              TerrainManager.SetBlock sb,
                              int x,
                              int y,
                              int z,
                              Material material) {
        tasks.add(() -> sb.set(x, y, z, material));
    }
}
