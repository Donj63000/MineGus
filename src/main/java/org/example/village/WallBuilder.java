package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Enceinte médiévale à échelle de village.
 *
 * <p>La muraille précédente formait un bloc de huit mètres sur trois, très
 * massif pour un petit bourg, et le corps de garde rebouchait son propre
 * passage. Cette version privilégie une enceinte de six blocs, un chemin de
 * ronde lisible, des tours couvertes sur leurs quatre côtés et un portail
 * réellement traversable.</p>
 */
public final class WallBuilder {
    private static final int WALL_HEIGHT = 6;
    private static final int WALL_THICKNESS = 2;
    private static final int GATE_HALF_WIDTH = 2;
    private static final int TOWER_RADIUS = 2;
    private static final int GATEHOUSE_HALF_WIDTH = 7;
    private static final int GATEHOUSE_HALF_DEPTH = 3;

    private WallBuilder() {}

    /**
     * Renvoie l'emprise extérieure complète, tours d'angle comprises.
     * Ordre : minX, maxX, minZ, maxZ.
     */
    public static int[] outerBounds(Location center, int rx, int rz) {
        int cx = center.getBlockX();
        int cz = center.getBlockZ();
        int outerRx = Math.max(4, rx) + WALL_THICKNESS - 1 + TOWER_RADIUS;
        int outerRz = Math.max(4, rz) + WALL_THICKNESS - 1 + TOWER_RADIUS;
        return new int[]{cx - outerRx, cx + outerRx, cz - outerRz, cz + outerRz};
    }

    /**
     * Point sûr où placer les gardes, juste à l'extérieur du portail.
     */
    public static Location gateAnchor(Location center, int rx, int rz, int baseY) {
        int outerSouth = center.getBlockZ() + Math.max(4, rz) + WALL_THICKNESS - 1;
        return new Location(
                center.getWorld(),
                center.getBlockX() + 0.5,
                baseY,
                outerSouth + GATEHOUSE_HALF_DEPTH + 2.5
        );
    }

    public static void build(Location center,
                             int rx,
                             int rz,
                             int baseY,
                             Material wallMaterial,
                             Queue<Runnable> queue,
                             TerrainManager.SetBlock setBlock) {
        if (center == null || queue == null || setBlock == null) {
            return;
        }

        World world = center.getWorld();
        int cx = center.getBlockX();
        int cz = center.getBlockZ();
        int safeRx = Math.max(4, rx);
        int safeRz = Math.max(4, rz);

        // rx/rz pointent vers la face intérieure ; l'épaisseur se développe
        // vers l'extérieur pour ne pas rogner les derniers jardins.
        int minX = cx - safeRx - WALL_THICKNESS + 1;
        int maxX = cx + safeRx + WALL_THICKNESS - 1;
        int minZ = cz - safeRz - WALL_THICKNESS + 1;
        int maxZ = cz + safeRz + WALL_THICKNESS - 1;
        int innerSouthZ = cz + safeRz;

        buildWallRing(queue, setBlock, cx, cz, minX, maxX, minZ, maxZ,
                innerSouthZ, baseY, wallMaterial);
        addWallWalk(queue, world, setBlock, minX, maxX, minZ, maxZ, baseY);
        addCrenellations(queue, setBlock, minX, maxX, minZ, maxZ, baseY);

        buildTower(queue, world, setBlock, minX, minZ, baseY);
        buildTower(queue, world, setBlock, minX, maxZ, baseY);
        buildTower(queue, world, setBlock, maxX, minZ, baseY);
        buildTower(queue, world, setBlock, maxX, maxZ, baseY);

        int gateCenterZ = (innerSouthZ + maxZ) / 2;
        buildGatehouse(queue, world, setBlock, cx, gateCenterZ, baseY);

        // Le pavage raccorde la rue intérieure au chemin extérieur. Il est
        // ajouté après la muraille afin que le passage ne soit jamais rebouché.
        buildGateApproach(queue, setBlock, cx,
                innerSouthZ - 7,
                maxZ + GATEHOUSE_HALF_DEPTH + 7,
                baseY);
    }

    private static void buildWallRing(Queue<Runnable> queue,
                                      TerrainManager.SetBlock setBlock,
                                      int cx,
                                      int cz,
                                      int minX,
                                      int maxX,
                                      int minZ,
                                      int maxZ,
                                      int innerSouthZ,
                                      int baseY,
                                      Material primary) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean ring = x <= minX + WALL_THICKNESS - 1
                        || x >= maxX - WALL_THICKNESS + 1
                        || z <= minZ + WALL_THICKNESS - 1
                        || z >= maxZ - WALL_THICKNESS + 1;
                if (!ring) {
                    continue;
                }

                // Réserve le passage sur toute l'épaisseur sud, et non
                // seulement sur sa moitié extérieure.
                boolean gatePassage = z >= innerSouthZ
                        && Math.abs(x - cx) <= GATE_HALF_WIDTH;
                if (gatePassage) {
                    continue;
                }

                int fx = x;
                int fz = z;
                queue.add(() -> setBlock.set(fx, baseY, fz,
                        patternedStone(primary, fx, baseY, fz)));

                for (int y = baseY + 1; y <= baseY + WALL_HEIGHT; y++) {
                    int fy = y;
                    Material material = patternedStone(primary, x, y, z);

                    // Une meurtrière sur la seule face extérieure allège les
                    // longues courtines sans créer un tunnel d'air traversant.
                    if (isOuterFace(x, z, minX, maxX, minZ, maxZ)
                            && y == baseY + 3
                            && Math.floorMod(axisCoordinate(x, z, minX, maxX), 9) == 4) {
                        material = Material.IRON_BARS;
                    }
                    Material finalMaterial = material;
                    queue.add(() -> setBlock.set(fx, fy, fz, finalMaterial));
                }

                if (isOuterFace(x, z, minX, maxX, minZ, maxZ)
                        && Math.floorMod(axisCoordinate(x, z, minX, maxX), 8) == 0) {
                    addButtress(queue, setBlock, x, z, cx, cz, baseY);
                }
            }
        }
    }

    private static void addButtress(Queue<Runnable> queue,
                                    TerrainManager.SetBlock setBlock,
                                    int x,
                                    int z,
                                    int cx,
                                    int cz,
                                    int baseY) {
        int dx = x < cx ? -1 : x > cx ? 1 : 0;
        int dz = z < cz ? -1 : z > cz ? 1 : 0;

        // Une face de courtine n'a qu'une normale. Lorsque les deux
        // composantes sont présentes, nous sommes dans la zone d'une tour.
        if (dx != 0 && dz != 0) {
            return;
        }
        int bx = x + dx;
        int bz = z + dz;
        queue.add(() -> setBlock.set(bx, baseY, bz, Material.COBBLESTONE));
        queue.add(() -> setBlock.set(bx, baseY + 1, bz, Material.STONE_BRICK_WALL));
        queue.add(() -> setBlock.set(bx, baseY + 2, bz, Material.STONE_BRICK_WALL));
    }

    private static void addWallWalk(Queue<Runnable> queue,
                                    World world,
                                    TerrainManager.SetBlock setBlock,
                                    int minX,
                                    int maxX,
                                    int minZ,
                                    int maxZ,
                                    int baseY) {
        int walkY = baseY + WALL_HEIGHT;
        for (int x = minX + 1; x <= maxX - 1; x++) {
            addTopSlab(queue, world, setBlock, x, walkY, minZ + 1);
            addTopSlab(queue, world, setBlock, x, walkY, maxZ - 1);
        }
        for (int z = minZ + 2; z <= maxZ - 2; z++) {
            addTopSlab(queue, world, setBlock, minX + 1, walkY, z);
            addTopSlab(queue, world, setBlock, maxX - 1, walkY, z);
        }
    }

    private static void addCrenellations(Queue<Runnable> queue,
                                         TerrainManager.SetBlock setBlock,
                                         int minX,
                                         int maxX,
                                         int minZ,
                                         int maxZ,
                                         int baseY) {
        int topY = baseY + WALL_HEIGHT + 1;
        for (int x = minX; x <= maxX; x++) {
            if (Math.floorMod(x, 2) == 0) {
                int fx = x;
                queue.add(() -> setBlock.set(fx, topY, minZ, Material.STONE_BRICKS));
                queue.add(() -> setBlock.set(fx, topY, maxZ, Material.STONE_BRICKS));
            }
        }
        for (int z = minZ + 1; z <= maxZ - 1; z++) {
            if (Math.floorMod(z, 2) == 0) {
                int fz = z;
                queue.add(() -> setBlock.set(minX, topY, fz, Material.STONE_BRICKS));
                queue.add(() -> setBlock.set(maxX, topY, fz, Material.STONE_BRICKS));
            }
        }
    }

    private static void buildTower(Queue<Runnable> queue,
                                   World world,
                                   TerrainManager.SetBlock setBlock,
                                   int centerX,
                                   int centerZ,
                                   int baseY) {
        int topY = baseY + WALL_HEIGHT + 2;
        for (int dx = -TOWER_RADIUS; dx <= TOWER_RADIUS; dx++) {
            for (int dz = -TOWER_RADIUS; dz <= TOWER_RADIUS; dz++) {
                int x = centerX + dx;
                int z = centerZ + dz;
                boolean shell = Math.abs(dx) == TOWER_RADIUS
                        || Math.abs(dz) == TOWER_RADIUS;

                queue.add(() -> setBlock.set(x, baseY, z,
                        patternedStone(Material.STONE_BRICKS, x, baseY, z)));
                if (!shell) {
                    queue.add(() -> setBlock.set(x, baseY + 1, z, Material.SPRUCE_PLANKS));
                    continue;
                }

                for (int y = baseY + 1; y <= topY; y++) {
                    int fy = y;
                    Material material = y == baseY + 3
                            && (dx == 0 || dz == 0)
                            ? Material.IRON_BARS
                            : patternedStone(Material.STONE_BRICKS, x, y, z);
                    queue.add(() -> setBlock.set(x, fy, z, material));
                }
            }
        }

        List<Runnable> roof = new ArrayList<>();
        VillageRoofBuilder.buildHip(
                roof,
                world,
                setBlock,
                centerX - TOWER_RADIUS,
                centerX + TOWER_RADIUS,
                centerZ - TOWER_RADIUS,
                centerZ + TOWER_RADIUS,
                topY + 1,
                Material.DARK_OAK_STAIRS,
                Material.DARK_OAK_SLAB
        );
        queue.addAll(roof);

        queue.add(() -> setBlock.set(centerX, baseY + 2,
                centerZ - TOWER_RADIUS, Material.LANTERN));
    }

    private static void buildGatehouse(Queue<Runnable> queue,
                                       World world,
                                       TerrainManager.SetBlock setBlock,
                                       int centerX,
                                       int centerZ,
                                       int baseY) {
        int minZ = centerZ - GATEHOUSE_HALF_DEPTH;
        int maxZ = centerZ + GATEHOUSE_HALF_DEPTH;
        int leftMinX = centerX - GATEHOUSE_HALF_WIDTH;
        int leftMaxX = centerX - GATE_HALF_WIDTH - 1;
        int rightMinX = centerX + GATE_HALF_WIDTH + 1;
        int rightMaxX = centerX + GATEHOUSE_HALF_WIDTH;
        int towerTop = baseY + WALL_HEIGHT + 3;

        buildGateTower(queue, world, setBlock,
                leftMinX, leftMaxX, minZ, maxZ, baseY, towerTop);
        buildGateTower(queue, world, setBlock,
                rightMinX, rightMaxX, minZ, maxZ, baseY, towerTop);

        // Galerie haute au-dessus de l'arche.
        for (int x = centerX - GATE_HALF_WIDTH; x <= centerX + GATE_HALF_WIDTH; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int fx = x;
                int fz = z;
                for (int y = baseY + 5; y <= baseY + WALL_HEIGHT + 2; y++) {
                    int fy = y;
                    boolean shell = y == baseY + 5
                            || y == baseY + WALL_HEIGHT + 2
                            || z == minZ
                            || z == maxZ;
                    if (shell) {
                        queue.add(() -> setBlock.set(fx, fy, fz,
                                patternedStone(Material.STONE_BRICKS, fx, fy, fz)));
                    }
                }
            }
        }

        // Arche en escalier sur les deux façades.
        for (int z : new int[]{minZ, maxZ}) {
            for (int x = centerX - GATE_HALF_WIDTH; x <= centerX + GATE_HALF_WIDTH; x++) {
                int fx = x;
                queue.add(() -> setBlock.set(fx, baseY + 5, z, Material.STONE_BRICK_STAIRS));
                queue.add(() -> VillageStyle.setStair(
                        world, fx, baseY + 5, z,
                        Material.STONE_BRICK_STAIRS,
                        z == minZ ? BlockFace.NORTH : BlockFace.SOUTH,
                        Stairs.Half.BOTTOM,
                        Stairs.Shape.STRAIGHT
                ));
            }
        }

        // La herse est relevée dans la galerie : elle reste visible sans
        // rendre l'entrée impossible à franchir.
        for (int x = centerX - GATE_HALF_WIDTH; x <= centerX + GATE_HALF_WIDTH; x++) {
            int fx = x;
            queue.add(() -> setBlock.set(fx, baseY + 5, maxZ - 1, Material.IRON_BARS));
            queue.add(() -> setBlock.set(fx, baseY + 6, maxZ - 1, Material.IRON_BARS));
        }

        // Nettoyage final du corridor sur toute la profondeur, indispensable
        // car la courtine et la galerie ont été programmées avant le portail.
        for (int x = centerX - GATE_HALF_WIDTH; x <= centerX + GATE_HALF_WIDTH; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                int fx = x;
                int fz = z;
                for (int y = baseY + 1; y <= baseY + 4; y++) {
                    int fy = y;
                    queue.add(() -> setBlock.set(fx, fy, fz, Material.AIR));
                }
            }
        }

        // Éclairage et identité du bourg. Les lanternes sont suspendues à
        // de vrais bras de potence : aucun bloc ne flotte devant la façade.
        for (int x : new int[]{leftMaxX, rightMinX}) {
            queue.add(() -> setBlock.set(x, baseY + 6, maxZ + 1, Material.DARK_OAK_FENCE));
            queue.add(() -> setBlock.set(x, baseY + 5, maxZ + 1, Material.CHAIN));
            queue.add(() -> setBlock.set(x, baseY + 4, maxZ + 1, Material.LANTERN));
        }
        queue.add(() -> setBlock.set(leftMaxX, baseY + 5, maxZ, Material.RED_WALL_BANNER));
        queue.add(() -> VillageStyle.setDirectional(
                world, leftMaxX, baseY + 5, maxZ,
                Material.RED_WALL_BANNER, BlockFace.SOUTH));
        queue.add(() -> setBlock.set(rightMinX, baseY + 5, maxZ, Material.RED_WALL_BANNER));
        queue.add(() -> VillageStyle.setDirectional(
                world, rightMinX, baseY + 5, maxZ,
                Material.RED_WALL_BANNER, BlockFace.SOUTH));
    }

    private static void buildGateTower(Queue<Runnable> queue,
                                       World world,
                                       TerrainManager.SetBlock setBlock,
                                       int minX,
                                       int maxX,
                                       int minZ,
                                       int maxZ,
                                       int baseY,
                                       int topY) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean shell = x == minX || x == maxX || z == minZ || z == maxZ;
                int fx = x;
                int fz = z;
                queue.add(() -> setBlock.set(fx, baseY, fz, Material.STONE_BRICKS));
                if (!shell) {
                    queue.add(() -> setBlock.set(fx, baseY + 1, fz, Material.SPRUCE_PLANKS));
                    continue;
                }
                for (int y = baseY + 1; y <= topY; y++) {
                    int fy = y;
                    Material material = y == baseY + 4
                            && ((x == minX || x == maxX) && z == (minZ + maxZ) / 2)
                            ? Material.IRON_BARS
                            : patternedStone(Material.STONE_BRICKS, x, y, z);
                    queue.add(() -> setBlock.set(fx, fy, fz, material));
                }
            }
        }

        List<Runnable> roof = new ArrayList<>();
        VillageRoofBuilder.buildHip(
                roof,
                world,
                setBlock,
                minX,
                maxX,
                minZ,
                maxZ,
                topY + 1,
                Material.DARK_OAK_STAIRS,
                Material.DARK_OAK_SLAB
        );
        queue.addAll(roof);
    }

    private static void buildGateApproach(Queue<Runnable> queue,
                                          TerrainManager.SetBlock setBlock,
                                          int centerX,
                                          int startZ,
                                          int endZ,
                                          int baseY) {
        for (int z = startZ; z <= endZ; z++) {
            for (int dx = -GATE_HALF_WIDTH; dx <= GATE_HALF_WIDTH; dx++) {
                int x = centerX + dx;
                int fz = z;
                Material material;
                if (Math.abs(dx) == GATE_HALF_WIDTH) {
                    material = Math.floorMod(x + z, 2) == 0
                            ? Material.COBBLESTONE
                            : Material.STONE_BRICKS;
                } else {
                    material = Math.floorMod(x * 17 + z * 31, 5) == 0
                            ? Material.POLISHED_ANDESITE
                            : Material.GRAVEL;
                }
                Material finalMaterial = material;
                queue.add(() -> setBlock.set(x, baseY, fz, finalMaterial));
            }
        }
    }

    private static void addTopSlab(Queue<Runnable> queue,
                                   World world,
                                   TerrainManager.SetBlock setBlock,
                                   int x,
                                   int y,
                                   int z) {
        queue.add(() -> setBlock.set(x, y, z, Material.STONE_BRICK_SLAB));
        queue.add(() -> VillageStyle.setSlab(
                world, x, y, z,
                Material.STONE_BRICK_SLAB,
                Slab.Type.TOP
        ));
    }

    private static Material patternedStone(Material primary,
                                           int x,
                                           int y,
                                           int z) {
        int selector = Math.floorMod(x * 31 + y * 13 + z * 17, 23);
        if (selector == 0 || selector == 7) {
            return Material.MOSSY_STONE_BRICKS;
        }
        if (selector == 3) {
            return Material.CRACKED_STONE_BRICKS;
        }
        if (selector == 11) {
            return Material.COBBLESTONE;
        }
        return primary == null ? Material.STONE_BRICKS : primary;
    }

    private static boolean isOuterFace(int x,
                                       int z,
                                       int minX,
                                       int maxX,
                                       int minZ,
                                       int maxZ) {
        return x == minX || x == maxX || z == minZ || z == maxZ;
    }

    private static int axisCoordinate(int x,
                                      int z,
                                      int minX,
                                      int maxX) {
        return x == minX || x == maxX ? z : x;
    }
}
