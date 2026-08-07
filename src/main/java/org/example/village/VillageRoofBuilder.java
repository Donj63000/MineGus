package org.example.village;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;

import java.util.List;

/**
 * Construit les couvertures communes du village.
 *
 * <p>Une toiture Minecraft ne peut pas être considérée comme fermée lorsque
 * seules ses rangées d'escaliers visibles sont posées. Les demi-volumes des
 * escaliers, les angles et les changements de niveau laissent alors passer le
 * ciel suivant l'angle de caméra. Cette classe construit donc systématiquement
 * un noyau plein en gradins, puis remplace uniquement sa peau extérieure par
 * des escaliers et des dalles orientés vers le faîtage.</p>
 *
 * <p>Le noyau est volontairement limité au volume de la couverture : il ne
 * descend jamais dans les pièces habitables. Les pignons restent décoratifs et
 * peuvent conserver une fenêtre de grenier en bloc de verre plein.</p>
 */
public final class VillageRoofBuilder {

    private VillageRoofBuilder() {}

    /**
     * Signature historique conservée pour les appels externes.
     *
     * <p>Le bloc plein est déduit de la famille de l'escalier. Les nouveaux
     * appels internes utilisent l'autre surcharge afin de ne laisser aucune
     * ambiguïté sur le matériau du noyau.</p>
     */
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
        buildGable(
                tasks,
                world,
                sb,
                minX,
                maxX,
                minZ,
                maxZ,
                roofY,
                facade,
                solidMaterialFromStairs(stairMaterial),
                stairMaterial,
                slabMaterial,
                gableFill,
                gableBeam,
                atticWindow
        );
    }

    /**
     * Construit un toit à deux pans entièrement fermé.
     *
     * @param roofBlock bloc plein placé sous les escaliers de couverture
     */
    public static void buildGable(List<Runnable> tasks,
                                  World world,
                                  TerrainManager.SetBlock sb,
                                  int minX,
                                  int maxX,
                                  int minZ,
                                  int maxZ,
                                  int roofY,
                                  BlockFace facade,
                                  Material roofBlock,
                                  Material stairMaterial,
                                  Material slabMaterial,
                                  Material gableFill,
                                  Material gableBeam,
                                  Material atticWindow) {
        if (!valid(tasks, sb, minX, maxX, minZ, maxZ)
                || roofBlock == null
                || stairMaterial == null
                || slabMaterial == null) {
            return;
        }

        boolean ridgeAlongZ = facade == BlockFace.NORTH
                || facade == BlockFace.SOUTH;
        int crossMin = ridgeAlongZ ? minX - 1 : minZ - 1;
        int crossMax = ridgeAlongZ ? maxX + 1 : maxZ + 1;
        int axisMin = ridgeAlongZ ? minZ : minX;
        int axisMax = ridgeAlongZ ? maxZ : maxX;
        int visibleAxisMin = axisMin - 1;
        int visibleAxisMax = axisMax + 1;
        int slopeLayers = Math.max(1, (crossMax - crossMin + 2) / 2);
        int ridgeY = roofY;

        for (int layer = 0; layer < slopeLayers; layer++) {
            int lowCross = crossMin + layer;
            int highCross = crossMax - layer;
            if (lowCross > highCross) {
                break;
            }

            int y = roofY + layer;
            ridgeY = y;

            /*
             * Le rectangle plein forme une membrane continue. Les deux cases
             * de débord longitudinal ne sont pas remplies afin de ne pas cacher
             * les pignons : seules les rives visibles y sont prolongées.
             */
            fillGableCore(
                    tasks,
                    sb,
                    ridgeAlongZ,
                    lowCross,
                    highCross,
                    axisMin,
                    axisMax,
                    y,
                    roofBlock
            );

            if (highCross - lowCross >= 2) {
                BlockFace lowFacing = ridgeAlongZ
                        ? BlockFace.EAST
                        : BlockFace.SOUTH;
                BlockFace highFacing = ridgeAlongZ
                        ? BlockFace.WEST
                        : BlockFace.NORTH;

                for (int axis = visibleAxisMin; axis <= visibleAxisMax; axis++) {
                    placeCrossAxisStair(
                            tasks,
                            world,
                            sb,
                            ridgeAlongZ,
                            lowCross,
                            axis,
                            y,
                            stairMaterial,
                            lowFacing,
                            Stairs.Shape.STRAIGHT
                    );
                    placeCrossAxisStair(
                            tasks,
                            world,
                            sb,
                            ridgeAlongZ,
                            highCross,
                            axis,
                            y,
                            stairMaterial,
                            highFacing,
                            Stairs.Shape.STRAIGHT
                    );
                }
            } else {
                /*
                 * Une terminaison d'un ou deux blocs reste pleine. Deux
                 * escaliers face à face au sommet conserveraient une cavité
                 * centrale visible depuis le grenier.
                 */
                for (int cross = lowCross; cross <= highCross; cross++) {
                    for (int axis = visibleAxisMin;
                         axis <= visibleAxisMax;
                         axis++) {
                        placeCrossAxis(
                                tasks,
                                sb,
                                ridgeAlongZ,
                                cross,
                                axis,
                                y,
                                roofBlock
                        );
                    }
                }
            }
        }

        /*
         * Le faîtage est posé un demi-bloc au-dessus du noyau plein. Il ne
         * flotte donc jamais et masque la jonction entre les deux pans.
         */
        buildRidge(
                tasks,
                world,
                sb,
                minX,
                maxX,
                minZ,
                maxZ,
                ridgeY + 1,
                ridgeAlongZ,
                slabMaterial
        );

        fillGableEnds(
                tasks,
                sb,
                minX,
                maxX,
                minZ,
                maxZ,
                roofY,
                slopeLayers,
                ridgeAlongZ,
                gableFill,
                gableBeam,
                atticWindow
        );
    }

    /**
     * Signature historique conservée pour les appels externes.
     */
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
        buildHip(
                tasks,
                world,
                sb,
                minX,
                maxX,
                minZ,
                maxZ,
                roofY,
                solidMaterialFromStairs(stairMaterial),
                stairMaterial,
                slabMaterial
        );
    }

    /**
     * Construit un toit en croupe fermé, y compris dans les quatre angles.
     *
     * @param roofBlock bloc plein utilisé pour le noyau en gradins
     */
    public static void buildHip(List<Runnable> tasks,
                                World world,
                                TerrainManager.SetBlock sb,
                                int minX,
                                int maxX,
                                int minZ,
                                int maxZ,
                                int roofY,
                                Material roofBlock,
                                Material stairMaterial,
                                Material slabMaterial) {
        if (!valid(tasks, sb, minX, maxX, minZ, maxZ)
                || roofBlock == null
                || stairMaterial == null
                || slabMaterial == null) {
            return;
        }

        int ringMinX = minX - 1;
        int ringMaxX = maxX + 1;
        int ringMinZ = minZ - 1;
        int ringMaxZ = maxZ + 1;
        int layer = 0;

        while (ringMinX <= ringMaxX && ringMinZ <= ringMaxZ) {
            int y = roofY + layer;
            int width = ringMaxX - ringMinX + 1;
            int depth = ringMaxZ - ringMinZ + 1;

            /*
             * Chaque étage est d'abord une plaque pleine. La plaque supérieure
             * plus petite masque son centre ; seule la couronne d'escalier
             * demeure visible. Même avec un pack de ressources très contrasté,
             * aucun rayon de ciel ne peut apparaître entre deux couronnes.
             */
            fillRectangle(
                    tasks,
                    sb,
                    ringMinX,
                    ringMaxX,
                    ringMinZ,
                    ringMaxZ,
                    y,
                    roofBlock
            );

            if (width <= 2 || depth <= 2) {
                capHipRoof(
                        tasks,
                        world,
                        sb,
                        ringMinX,
                        ringMaxX,
                        ringMinZ,
                        ringMaxZ,
                        y + 1,
                        slabMaterial
                );
                break;
            }

            buildHipRing(
                    tasks,
                    world,
                    sb,
                    ringMinX,
                    ringMaxX,
                    ringMinZ,
                    ringMaxZ,
                    y,
                    stairMaterial
            );

            ringMinX++;
            ringMaxX--;
            ringMinZ++;
            ringMaxZ--;
            layer++;
        }
    }

    private static void buildHipRing(List<Runnable> tasks,
                                     World world,
                                     TerrainManager.SetBlock sb,
                                     int minX,
                                     int maxX,
                                     int minZ,
                                     int maxZ,
                                     int y,
                                     Material stairMaterial) {
        // Les quatre pentes regardent le centre du toit, jamais l'extérieur.
        for (int x = minX + 1; x <= maxX - 1; x++) {
            stair(
                    tasks,
                    world,
                    sb,
                    x,
                    y,
                    minZ,
                    stairMaterial,
                    BlockFace.SOUTH,
                    Stairs.Shape.STRAIGHT
            );
            stair(
                    tasks,
                    world,
                    sb,
                    x,
                    y,
                    maxZ,
                    stairMaterial,
                    BlockFace.NORTH,
                    Stairs.Shape.STRAIGHT
            );
        }
        for (int z = minZ + 1; z <= maxZ - 1; z++) {
            stair(
                    tasks,
                    world,
                    sb,
                    minX,
                    y,
                    z,
                    stairMaterial,
                    BlockFace.EAST,
                    Stairs.Shape.STRAIGHT
            );
            stair(
                    tasks,
                    world,
                    sb,
                    maxX,
                    y,
                    z,
                    stairMaterial,
                    BlockFace.WEST,
                    Stairs.Shape.STRAIGHT
            );
        }

        /*
         * Des formes d'angle explicites évitent les quatre évidements visibles
         * sur les anciennes tours. Le bloc plein déjà posé sous chaque escalier
         * reste une sécurité supplémentaire pour les packs de modèles custom.
         */
        stair(
                tasks,
                world,
                sb,
                minX,
                y,
                minZ,
                stairMaterial,
                BlockFace.SOUTH,
                Stairs.Shape.OUTER_LEFT
        );
        stair(
                tasks,
                world,
                sb,
                maxX,
                y,
                minZ,
                stairMaterial,
                BlockFace.SOUTH,
                Stairs.Shape.OUTER_RIGHT
        );
        stair(
                tasks,
                world,
                sb,
                maxX,
                y,
                maxZ,
                stairMaterial,
                BlockFace.NORTH,
                Stairs.Shape.OUTER_LEFT
        );
        stair(
                tasks,
                world,
                sb,
                minX,
                y,
                maxZ,
                stairMaterial,
                BlockFace.NORTH,
                Stairs.Shape.OUTER_RIGHT
        );
    }

    private static void capHipRoof(List<Runnable> tasks,
                                   World world,
                                   TerrainManager.SetBlock sb,
                                   int minX,
                                   int maxX,
                                   int minZ,
                                   int maxZ,
                                   int y,
                                   Material slabMaterial) {
        /*
         * Une toiture rectangulaire se termine par un petit faîtage ; une
         * toiture carrée par une calotte. Dans les deux cas les dalles reposent
         * sur la plaque pleine construite juste en dessous.
         */
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                slab(
                        tasks,
                        world,
                        sb,
                        x,
                        y,
                        z,
                        slabMaterial,
                        Slab.Type.BOTTOM
                );
            }
        }
    }

    private static void fillGableCore(List<Runnable> tasks,
                                      TerrainManager.SetBlock sb,
                                      boolean ridgeAlongZ,
                                      int crossMin,
                                      int crossMax,
                                      int axisMin,
                                      int axisMax,
                                      int y,
                                      Material roofBlock) {
        for (int cross = crossMin; cross <= crossMax; cross++) {
            for (int axis = axisMin; axis <= axisMax; axis++) {
                placeCrossAxis(
                        tasks,
                        sb,
                        ridgeAlongZ,
                        cross,
                        axis,
                        y,
                        roofBlock
                );
            }
        }
    }

    private static void fillRectangle(List<Runnable> tasks,
                                      TerrainManager.SetBlock sb,
                                      int minX,
                                      int maxX,
                                      int minZ,
                                      int maxZ,
                                      int y,
                                      Material material) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                place(tasks, sb, x, y, z, material);
            }
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
                slab(
                        tasks,
                        world,
                        sb,
                        ridgeA,
                        y,
                        z,
                        slabMaterial,
                        Slab.Type.BOTTOM
                );
                if (ridgeB != ridgeA) {
                    slab(
                            tasks,
                            world,
                            sb,
                            ridgeB,
                            y,
                            z,
                            slabMaterial,
                            Slab.Type.BOTTOM
                    );
                }
            }
        } else {
            int ridgeA = (minZ + maxZ) / 2;
            int ridgeB = (minZ + maxZ + 1) / 2;
            for (int x = minX - 1; x <= maxX + 1; x++) {
                slab(
                        tasks,
                        world,
                        sb,
                        x,
                        y,
                        ridgeA,
                        slabMaterial,
                        Slab.Type.BOTTOM
                );
                if (ridgeB != ridgeA) {
                    slab(
                            tasks,
                            world,
                            sb,
                            x,
                            y,
                            ridgeB,
                            slabMaterial,
                            Slab.Type.BOTTOM
                    );
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
        if (fill == null || beam == null) {
            return;
        }

        int crossMin = ridgeAlongZ ? minX : minZ;
        int crossMax = ridgeAlongZ ? maxX : maxZ;
        int firstEnd = ridgeAlongZ ? minZ : minX;
        int secondEnd = ridgeAlongZ ? maxZ : maxX;
        int center = (crossMin + crossMax) / 2;

        for (int level = 0; level < slopeLayers; level++) {
            int rowMin = crossMin + level;
            int rowMax = crossMax - level;
            if (rowMin > rowMax) {
                break;
            }

            int y = roofY + level;
            for (int cross = rowMin; cross <= rowMax; cross++) {
                boolean edgeBeam = cross == rowMin || cross == rowMax;
                boolean centralPost = cross == center;
                boolean tieBeam = level == 0;
                Material material = edgeBeam || centralPost || tieBeam
                        ? beam
                        : fill;

                placeAtEnd(
                        tasks,
                        sb,
                        ridgeAlongZ,
                        cross,
                        y,
                        firstEnd,
                        material
                );
                placeAtEnd(
                        tasks,
                        sb,
                        ridgeAlongZ,
                        cross,
                        y,
                        secondEnd,
                        material
                );
            }
        }

        /*
         * Le vitrage est posé en dernier afin qu'aucune poutre décorative ne
         * puisse le remplacer. Un bloc plein, et non une vitre fine, garantit
         * un raccord propre contre la terre cuite et les poutres.
         */
        if (window != null
                && crossMax - crossMin >= 5
                && slopeLayers >= 3) {
            int windowY = roofY + 1;
            placeAtEnd(
                    tasks,
                    sb,
                    ridgeAlongZ,
                    center,
                    windowY,
                    firstEnd,
                    window
            );
            placeAtEnd(
                    tasks,
                    sb,
                    ridgeAlongZ,
                    center,
                    windowY,
                    secondEnd,
                    window
            );
        }
    }

    private static void placeCrossAxisStair(List<Runnable> tasks,
                                            World world,
                                            TerrainManager.SetBlock sb,
                                            boolean ridgeAlongZ,
                                            int cross,
                                            int axis,
                                            int y,
                                            Material material,
                                            BlockFace facing,
                                            Stairs.Shape shape) {
        if (ridgeAlongZ) {
            stair(tasks, world, sb, cross, y, axis, material, facing, shape);
        } else {
            stair(tasks, world, sb, axis, y, cross, material, facing, shape);
        }
    }

    private static void placeCrossAxis(List<Runnable> tasks,
                                       TerrainManager.SetBlock sb,
                                       boolean ridgeAlongZ,
                                       int cross,
                                       int axis,
                                       int y,
                                       Material material) {
        if (ridgeAlongZ) {
            place(tasks, sb, cross, y, axis, material);
        } else {
            place(tasks, sb, axis, y, cross, material);
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
                              BlockFace facing,
                              Stairs.Shape shape) {
        place(tasks, sb, x, y, z, material);
        tasks.add(() -> VillageStyle.setStair(
                world,
                x,
                y,
                z,
                material,
                facing,
                Stairs.Half.BOTTOM,
                shape
        ));
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
        tasks.add(() -> VillageStyle.setSlab(
                world,
                x,
                y,
                z,
                material,
                type
        ));
    }

    private static void place(List<Runnable> tasks,
                              TerrainManager.SetBlock sb,
                              int x,
                              int y,
                              int z,
                              Material material) {
        tasks.add(() -> sb.set(x, y, z, material));
    }

    private static boolean valid(List<Runnable> tasks,
                                 TerrainManager.SetBlock sb,
                                 int minX,
                                 int maxX,
                                 int minZ,
                                 int maxZ) {
        return tasks != null
                && sb != null
                && minX <= maxX
                && minZ <= maxZ;
    }

    /**
     * Déduit un bloc plein à partir d'un escalier pour préserver la compatibilité
     * avec les appels historiques du projet et d'éventuelles extensions.
     */
    private static Material solidMaterialFromStairs(Material stairs) {
        if (stairs == null) {
            return Material.SPRUCE_PLANKS;
        }

        String name = stairs.name();
        String[] candidates = {
                name.replace("STONE_BRICK_STAIRS", "STONE_BRICKS"),
                name.replace("NETHER_BRICK_STAIRS", "NETHER_BRICKS"),
                name.replace("DEEPSLATE_TILE_STAIRS", "DEEPSLATE_TILES"),
                name.replace("DEEPSLATE_BRICK_STAIRS", "DEEPSLATE_BRICKS"),
                name.replace("BRICK_STAIRS", "BRICKS"),
                name.replace("QUARTZ_STAIRS", "QUARTZ_BLOCK"),
                name.replace("_STAIRS", "_PLANKS"),
                name.replace("_STAIRS", ""),
                name.equals("PURPUR_STAIRS") ? "PURPUR_BLOCK" : ""
        };

        for (String candidate : candidates) {
            /*
             * String#replace renvoie la chaîne d'origine lorsque le suffixe
             * n'est pas présent. Il ne faut surtout pas accepter alors
             * l'escalier lui-même comme matériau du noyau.
             */
            if (candidate.isBlank() || candidate.equals(name)) {
                continue;
            }
            Material material = Material.getMaterial(candidate);
            if (material != null && material.isBlock()) {
                return material;
            }
        }
        return Material.SPRUCE_PLANKS;
    }
}
