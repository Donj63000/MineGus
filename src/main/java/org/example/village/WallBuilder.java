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
 * Enceinte fortifiée médiévale du village.
 *
 * <p>La muraille est conçue comme un ouvrage habitable et lisible depuis les
 * rues : fondations, soubassement taluté, courtines épaisses, chemin de ronde,
 * parapet intérieur, créneaux, contreforts, tours d'angle et châtelet. Les
 * ouvertures ne traversent jamais toute l'épaisseur et les toitures emploient
 * le constructeur fermé de {@link VillageRoofBuilder}.</p>
 */
public final class WallBuilder {

    /*
     * Ces deux cotes sont partagées avec KeepBuilder afin que le plancher du
     * donjon et les raccords de courtine restent parfaitement alignés.
     */
    static final int WALL_HEIGHT = 9;
    static final int WALL_THICKNESS = 3;
    private static final int FOUNDATION_DEPTH = 3;
    private static final int BUTTRESS_SPACING = 8;

    private static final int GATE_HALF_WIDTH = 2;
    private static final int APPROACH_HALF_WIDTH = 3;

    private static final int TOWER_RADIUS = 3;
    private static final int TOWER_HEIGHT = 13;

    private static final int GATEHOUSE_HALF_WIDTH = 9;
    private static final int GATEHOUSE_HALF_DEPTH = 4;
    private static final int GATE_TOWER_HEIGHT = 15;

    private WallBuilder() {}

    /**
     * Renvoie l'emprise extérieure complète, tours, talus et châtelet compris.
     * Ordre : minX, maxX, minZ, maxZ.
     */
    public static int[] outerBounds(Location center, int rx, int rz) {
        if (center == null) {
            return new int[]{0, 0, 0, 0};
        }

        int cx = center.getBlockX();
        int cz = center.getBlockZ();
        int safeRx = Math.max(4, rx);
        int safeRz = Math.max(4, rz);

        /*
         * Le +1 final correspond au débord de la première rangée de toiture.
         * Sans lui, la préparation du terrain omettait une couronne de blocs
         * autour des tours et du châtelet.
         */
        int wallAndTowerX = safeRx
                + WALL_THICKNESS
                - 1
                + TOWER_RADIUS
                + 1;
        int wallAndTowerZ = safeRz
                + WALL_THICKNESS
                - 1
                + TOWER_RADIUS
                + 1;
        int extentX = Math.max(
                wallAndTowerX,
                GATEHOUSE_HALF_WIDTH + 1
        );
        int extentZ = Math.max(
                wallAndTowerZ,
                safeRz + 1 + GATEHOUSE_HALF_DEPTH + 1
        );

        int[] keepBounds = KeepBuilder.outerBounds(center, safeRz);
        return new int[]{
                Math.min(cx - extentX, keepBounds[0]),
                Math.max(cx + extentX, keepBounds[1]),
                Math.min(cz - extentZ, keepBounds[2]),
                Math.max(cz + extentZ, keepBounds[3])
        };
    }

    /**
     * Point sûr où placer les gardes, devant le châtelet et hors de la herse.
     */
    public static Location gateAnchor(Location center,
                                      int rx,
                                      int rz,
                                      int baseY) {
        if (center == null) {
            return null;
        }

        int safeRz = Math.max(4, rz);
        int innerSouthZ = center.getBlockZ() + safeRz;
        int outerSouthZ = innerSouthZ + WALL_THICKNESS - 1;
        int gateCenterZ = (innerSouthZ + outerSouthZ) / 2;
        int gateFrontZ = gateCenterZ + GATEHOUSE_HALF_DEPTH;

        return new Location(
                center.getWorld(),
                center.getBlockX() + 0.5D,
                baseY,
                gateFrontZ + 3.5D
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

        /*
         * rx/rz désignent la face intérieure. Toute l'épaisseur se développe
         * vers l'extérieur afin de ne pas rogner les jardins périphériques.
         */
        int minX = cx - safeRx - WALL_THICKNESS + 1;
        int maxX = cx + safeRx + WALL_THICKNESS - 1;
        int minZ = cz - safeRz - WALL_THICKNESS + 1;
        int maxZ = cz + safeRz + WALL_THICKNESS - 1;
        int innerSouthZ = cz + safeRz;

        buildWallRing(
                queue,
                world,
                setBlock,
                cx,
                minX,
                maxX,
                minZ,
                maxZ,
                innerSouthZ,
                baseY,
                wallMaterial
        );
        buildWallWalk(
                queue,
                setBlock,
                cx,
                minX,
                maxX,
                minZ,
                maxZ,
                innerSouthZ,
                baseY
        );
        addCrenellations(
                queue,
                world,
                setBlock,
                cx,
                minX,
                maxX,
                minZ,
                maxZ,
                innerSouthZ,
                baseY
        );

        buildTower(
                queue,
                world,
                setBlock,
                minX,
                minZ,
                cx,
                cz,
                baseY
        );
        buildTower(
                queue,
                world,
                setBlock,
                minX,
                maxZ,
                cx,
                cz,
                baseY
        );
        buildTower(
                queue,
                world,
                setBlock,
                maxX,
                minZ,
                cx,
                cz,
                baseY
        );
        buildTower(
                queue,
                world,
                setBlock,
                maxX,
                maxZ,
                cx,
                cz,
                baseY
        );

        /*
         * Deux tours de flanquement interrompent les longues courtines
         * latérales. Elles possèdent chacune deux passages nord/sud au niveau
         * du chemin de ronde ; elles ne créent donc jamais d'impasse.
         */
        buildTower(
                queue,
                world,
                setBlock,
                minX,
                cz,
                cx,
                cz,
                baseY
        );
        buildTower(
                queue,
                world,
                setBlock,
                maxX,
                cz,
                cx,
                cz,
                baseY
        );

        int gateCenterZ = (innerSouthZ + maxZ) / 2;
        buildGatehouse(
                queue,
                world,
                setBlock,
                cx,
                gateCenterZ,
                baseY
        );

        /*
         * Le pavage est programmé en dernier : la route reste continue même si
         * une fondation ou une tour a été posée sur le même axe auparavant.
         */
        buildGateApproach(
                queue,
                setBlock,
                cx,
                innerSouthZ - 8,
                gateCenterZ + GATEHOUSE_HALF_DEPTH + 9,
                baseY
        );
    }

    private static void buildWallRing(Queue<Runnable> queue,
                                      World world,
                                      TerrainManager.SetBlock setBlock,
                                      int centerX,
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

                boolean gatePassage = z >= innerSouthZ
                        && Math.abs(x - centerX) <= GATE_HALF_WIDTH;
                if (gatePassage) {
                    continue;
                }

                int fx = x;
                int fz = z;

                // Fondations profondes, visibles même sur un terrain accidenté.
                for (int y = baseY - FOUNDATION_DEPTH; y <= baseY; y++) {
                    int fy = y;
                    Material foundation = foundationStone(fx, fy, fz);
                    place(queue, setBlock, fx, fy, fz, foundation);
                }

                boolean outerFace = isOuterFace(
                        x,
                        z,
                        minX,
                        maxX,
                        minZ,
                        maxZ
                );
                boolean slit = outerFace
                        && isSlitCoordinate(x, z, minX, maxX)
                        && !(z == maxZ
                        && Math.abs(x - centerX)
                        <= GATEHOUSE_HALF_WIDTH + 1);

                for (int y = baseY + 1;
                     y <= baseY + WALL_HEIGHT;
                     y++) {
                    int fy = y;
                    Material material;

                    if (slit && y == baseY + 4) {
                        /*
                         * Les barreaux occupent uniquement la peau extérieure ;
                         * les deux blocs internes restent pleins. On obtient une
                         * meurtrière sombre, pas un tunnel à travers la muraille.
                         */
                        material = Material.IRON_BARS;
                    } else if (y == baseY + 3 || y == baseY + 7) {
                        material = Material.POLISHED_ANDESITE;
                    } else if (slit
                            && (y == baseY + 3 || y == baseY + 5)) {
                        material = Material.CHISELED_STONE_BRICKS;
                    } else {
                        material = patternedStone(primary, x, y, z);
                    }

                    Material finalMaterial = material;
                    place(queue, setBlock,
                            fx,
                            fy,
                            fz,
                            finalMaterial
                    );
                }

                if (outerFace) {
                    BlockFace outward = outwardFace(
                            x,
                            z,
                            minX,
                            maxX,
                            minZ,
                            maxZ
                    );
                    addBatteredBase(
                            queue,
                            setBlock,
                            x,
                            z,
                            baseY,
                            outward
                    );

                    int axis = axisCoordinate(
                            x,
                            z,
                            minX,
                            maxX
                    );
                    boolean gateZone = z == maxZ
                            && Math.abs(x - centerX)
                            <= GATEHOUSE_HALF_WIDTH + 2;
                    if (!gateZone
                            && Math.floorMod(axis, BUTTRESS_SPACING) == 0) {
                        addButtress(
                                queue,
                                world,
                                setBlock,
                                x,
                                z,
                                baseY,
                                outward
                        );
                    }
                }
            }
        }
    }

    /**
     * Talus continu au pied de la courtine : il donne de la masse sans épaissir
     * tout le mur jusqu'au chemin de ronde.
     */
    private static void addBatteredBase(Queue<Runnable> queue,
                                        TerrainManager.SetBlock setBlock,
                                        int x,
                                        int z,
                                        int baseY,
                                        BlockFace outward) {
        if (!cardinal(outward)) {
            return;
        }

        int firstX = x + outward.getModX();
        int firstZ = z + outward.getModZ();
        for (int y = baseY - 1; y <= baseY + 1; y++) {
            int fy = y;
            Material material = y <= baseY
                    ? Material.COBBLED_DEEPSLATE
                    : Material.COBBLESTONE;
            place(queue, setBlock,
                    firstX,
                    fy,
                    firstZ,
                    material
            );
        }

        int secondX = x + outward.getModX() * 2;
        int secondZ = z + outward.getModZ() * 2;
        for (int y = baseY - 1; y <= baseY; y++) {
            int fy = y;
            place(queue, setBlock,
                    secondX,
                    fy,
                    secondZ,
                    Material.COBBLED_DEEPSLATE
            );
        }
    }

    private static void addButtress(Queue<Runnable> queue,
                                    World world,
                                    TerrainManager.SetBlock setBlock,
                                    int x,
                                    int z,
                                    int baseY,
                                    BlockFace outward) {
        if (!cardinal(outward)) {
            return;
        }

        for (int depth = 1; depth <= 2; depth++) {
            int bx = x + outward.getModX() * depth;
            int bz = z + outward.getModZ() * depth;
            int topY = baseY + (depth == 1 ? 6 : 3);

            for (int y = baseY - 2; y <= topY; y++) {
                int fy = y;
                Material material = y <= baseY
                        ? Material.COBBLED_DEEPSLATE
                        : patternedStone(
                        Material.STONE_BRICKS,
                        bx,
                        y,
                        bz
                );
                place(queue, setBlock,
                        bx,
                        fy,
                        bz,
                        material
                );
            }

            slab(
                    queue,
                    world,
                    setBlock,
                    bx,
                    topY + 1,
                    bz,
                    Material.STONE_BRICK_SLAB,
                    Slab.Type.BOTTOM
            );
        }
    }

    private static void buildWallWalk(Queue<Runnable> queue,
                                      TerrainManager.SetBlock setBlock,
                                      int centerX,
                                      int minX,
                                      int maxX,
                                      int minZ,
                                      int maxZ,
                                      int innerSouthZ,
                                      int baseY) {
        int walkY = baseY + WALL_HEIGHT;

        for (int x = minX; x <= maxX; x++) {
            for (int offset = 0; offset < WALL_THICKNESS; offset++) {
                int northZ = minZ + offset;
                int southZ = maxZ - offset;
                Material material = walkwayStone(x, northZ);
                place(queue, setBlock,
                        x,
                        walkY,
                        northZ,
                        material
                );

                if (!(southZ >= innerSouthZ
                        && Math.abs(x - centerX)
                        <= GATE_HALF_WIDTH)) {
                    Material southMaterial = walkwayStone(x, southZ);
                    place(queue, setBlock,
                            x,
                            walkY,
                            southZ,
                            southMaterial
                    );
                }
            }
        }

        for (int z = minZ + WALL_THICKNESS;
             z <= maxZ - WALL_THICKNESS;
             z++) {
            for (int offset = 0; offset < WALL_THICKNESS; offset++) {
                int westX = minX + offset;
                int eastX = maxX - offset;
                Material westMaterial = walkwayStone(westX, z);
                Material eastMaterial = walkwayStone(eastX, z);
                place(queue, setBlock,
                        westX,
                        walkY,
                        z,
                        westMaterial
                );
                place(queue, setBlock,
                        eastX,
                        walkY,
                        z,
                        eastMaterial
                );
            }
        }

        /*
         * Parapet intérieur continu : le chemin central reste praticable et le
         * joueur ne peut pas tomber directement dans les rues du village.
         */
        int parapetY = walkY + 1;
        for (int x = minX + 2; x <= maxX - 2; x++) {
            place(queue, setBlock,
                    x,
                    parapetY,
                    minZ + WALL_THICKNESS - 1,
                    Material.STONE_BRICK_WALL
            );
            if (!(Math.abs(x - centerX) <= GATE_HALF_WIDTH + 1)) {
                place(queue, setBlock,
                        x,
                        parapetY,
                        maxZ - WALL_THICKNESS + 1,
                        Material.STONE_BRICK_WALL
                );
            }
        }
        for (int z = minZ + WALL_THICKNESS;
             z <= maxZ - WALL_THICKNESS;
             z++) {
            place(queue, setBlock,
                    minX + WALL_THICKNESS - 1,
                    parapetY,
                    z,
                    Material.STONE_BRICK_WALL
            );
            place(queue, setBlock,
                    maxX - WALL_THICKNESS + 1,
                    parapetY,
                    z,
                    Material.STONE_BRICK_WALL
            );
        }
    }

    private static void addCrenellations(Queue<Runnable> queue,
                                         World world,
                                         TerrainManager.SetBlock setBlock,
                                         int centerX,
                                         int minX,
                                         int maxX,
                                         int minZ,
                                         int maxZ,
                                         int innerSouthZ,
                                         int baseY) {
        int merlonY = baseY + WALL_HEIGHT + 1;

        for (int x = minX; x <= maxX; x++) {
            if (merlonAt(x)) {
                addMerlon(
                        queue,
                        world,
                        setBlock,
                        x,
                        merlonY,
                        minZ
                );
                if (!(maxZ >= innerSouthZ
                        && Math.abs(x - centerX)
                        <= GATEHOUSE_HALF_WIDTH + 1)) {
                    addMerlon(
                            queue,
                            world,
                            setBlock,
                            x,
                            merlonY,
                            maxZ
                    );
                }
            }

            if (Math.floorMod(x, 2) == 0) {
                addMachicolation(
                        queue,
                        world,
                        setBlock,
                        x,
                        baseY + WALL_HEIGHT,
                        minZ - 1,
                        BlockFace.NORTH
                );
                if (!(Math.abs(x - centerX)
                        <= GATEHOUSE_HALF_WIDTH + 1)) {
                    addMachicolation(
                            queue,
                            world,
                            setBlock,
                            x,
                            baseY + WALL_HEIGHT,
                            maxZ + 1,
                            BlockFace.SOUTH
                    );
                }
            }
        }

        for (int z = minZ + 1; z <= maxZ - 1; z++) {
            if (merlonAt(z)) {
                addMerlon(
                        queue,
                        world,
                        setBlock,
                        minX,
                        merlonY,
                        z
                );
                addMerlon(
                        queue,
                        world,
                        setBlock,
                        maxX,
                        merlonY,
                        z
                );
            }

            if (Math.floorMod(z, 2) == 0) {
                addMachicolation(
                        queue,
                        world,
                        setBlock,
                        minX - 1,
                        baseY + WALL_HEIGHT,
                        z,
                        BlockFace.WEST
                );
                addMachicolation(
                        queue,
                        world,
                        setBlock,
                        maxX + 1,
                        baseY + WALL_HEIGHT,
                        z,
                        BlockFace.EAST
                );
            }
        }
    }

    private static void addMerlon(Queue<Runnable> queue,
                                  World world,
                                  TerrainManager.SetBlock setBlock,
                                  int x,
                                  int y,
                                  int z) {
        place(queue, setBlock,
                x,
                y,
                z,
                Material.STONE_BRICKS
        );
        slab(
                queue,
                world,
                setBlock,
                x,
                y + 1,
                z,
                Material.STONE_BRICK_SLAB,
                Slab.Type.BOTTOM
        );
    }

    private static void addMachicolation(Queue<Runnable> queue,
                                         World world,
                                         TerrainManager.SetBlock setBlock,
                                         int x,
                                         int y,
                                         int z,
                                         BlockFace outward) {
        stair(
                queue,
                world,
                setBlock,
                x,
                y,
                z,
                Material.STONE_BRICK_STAIRS,
                VillageStyle.opposite(outward),
                Stairs.Half.TOP,
                Stairs.Shape.STRAIGHT
        );
    }

    private static void buildTower(Queue<Runnable> queue,
                                   World world,
                                   TerrainManager.SetBlock setBlock,
                                   int centerX,
                                   int centerZ,
                                   int villageCenterX,
                                   int villageCenterZ,
                                   int baseY) {
        int topY = baseY + TOWER_HEIGHT;
        int walkY = baseY + WALL_HEIGHT;

        for (int dx = -TOWER_RADIUS; dx <= TOWER_RADIUS; dx++) {
            for (int dz = -TOWER_RADIUS; dz <= TOWER_RADIUS; dz++) {
                int x = centerX + dx;
                int z = centerZ + dz;
                boolean shell = Math.abs(dx) == TOWER_RADIUS
                        || Math.abs(dz) == TOWER_RADIUS;

                for (int y = baseY - FOUNDATION_DEPTH;
                     y <= baseY;
                     y++) {
                    int fy = y;
                    Material material = foundationStone(x, y, z);
                    place(queue, setBlock,
                            x,
                            fy,
                            z,
                            material
                    );
                }

                if (shell) {
                    for (int y = baseY + 1; y <= topY; y++) {
                        int fy = y;
                        boolean cardinalOpening =
                                (dx == 0
                                        && Math.abs(dz) == TOWER_RADIUS)
                                || (dz == 0
                                        && Math.abs(dx) == TOWER_RADIUS);
                        boolean arrowSlit = cardinalOpening
                                && (y == baseY + 4
                                || y == baseY + 8);
                        Material material;

                        if (arrowSlit) {
                            material = Material.IRON_BARS;
                        } else if (y == baseY + 5
                                || y == baseY + 10
                                || y == topY) {
                            material = Material.POLISHED_ANDESITE;
                        } else {
                            material = patternedStone(
                                    Material.STONE_BRICKS,
                                    x,
                                    y,
                                    z
                            );
                        }

                        Material finalMaterial = material;
                        place(queue, setBlock,
                                x,
                                fy,
                                z,
                                finalMaterial
                        );
                    }
                } else {
                    /*
                     * Les tours sont réellement creuses. Les blocs de la
                     * courtine qui les traversent sont explicitement retirés.
                     */
                    for (int y = baseY + 1; y < topY; y++) {
                        int fy = y;
                        Material material;
                        if (y == baseY + 6
                                || y == walkY
                                || y == baseY + 12) {
                            material = Material.SPRUCE_PLANKS;
                        } else {
                            material = Material.AIR;
                        }
                        Material finalMaterial = material;
                        place(queue, setBlock,
                                x,
                                fy,
                                z,
                                finalMaterial
                        );
                    }
                    place(queue, setBlock,
                            x,
                            topY,
                            z,
                            Material.DARK_OAK_PLANKS
                    );
                }
            }
        }

        openTowerWalkway(
                queue,
                setBlock,
                centerX,
                centerZ,
                villageCenterX,
                villageCenterZ,
                walkY
        );
        addTowerExteriorDetails(
                queue,
                world,
                setBlock,
                centerX,
                centerZ,
                villageCenterX,
                villageCenterZ,
                baseY
        );

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
                Material.DARK_OAK_PLANKS,
                Material.DARK_OAK_STAIRS,
                Material.DARK_OAK_SLAB
        );
        queue.addAll(roof);

        int finialBaseY = topY + 7;
        place(queue, setBlock,
                centerX,
                finialBaseY,
                centerZ,
                Material.DARK_OAK_FENCE
        );
        place(queue, setBlock,
                centerX,
                finialBaseY + 1,
                centerZ,
                Material.LIGHTNING_ROD
        );
    }

    private static void openTowerWalkway(Queue<Runnable> queue,
                                         TerrainManager.SetBlock setBlock,
                                         int centerX,
                                         int centerZ,
                                         int villageCenterX,
                                         int villageCenterZ,
                                         int walkY) {
        int inwardX = Integer.compare(villageCenterX, centerX);
        int inwardZ = Integer.compare(villageCenterZ, centerZ);

        /*
         * Une tour située au milieu d'une courtine doit être traversante. Les
         * ouvertures suivent donc l'axe du mur, tandis que leur largeur se
         * décale vers l'intérieur pour coïncider avec les trois blocs du chemin
         * de ronde.
         */
        boolean lateralFlankingTower = centerZ == villageCenterZ
                && centerX != villageCenterX;
        if (lateralFlankingTower) {
            for (int side : new int[]{-1, 1}) {
                int doorZ = centerZ + side * TOWER_RADIUS;
                for (int depth = 0; depth < WALL_THICKNESS; depth++) {
                    int x = centerX + inwardX * depth;
                    openTowerDoorCell(
                            queue,
                            setBlock,
                            x,
                            doorZ,
                            walkY
                    );
                }
            }
            return;
        }

        /*
         * Le cas symétrique est conservé pour une future tour centrale au nord
         * ou au sud : les deux passages suivent alors l'axe est/ouest.
         */
        boolean longitudinalFlankingTower = centerX == villageCenterX
                && centerZ != villageCenterZ;
        if (longitudinalFlankingTower) {
            for (int side : new int[]{-1, 1}) {
                int doorX = centerX + side * TOWER_RADIUS;
                for (int depth = 0; depth < WALL_THICKNESS; depth++) {
                    int z = centerZ + inwardZ * depth;
                    openTowerDoorCell(
                            queue,
                            setBlock,
                            doorX,
                            z,
                            walkY
                    );
                }
            }
            return;
        }

        /*
         * Chaque tour d'angle reçoit deux portes : une vers chaque courtine.
         * Le niveau du plancher correspond exactement au chemin de ronde.
         */
        if (inwardX != 0) {
            int doorX = centerX + inwardX * TOWER_RADIUS;
            for (int dz = -1; dz <= 1; dz++) {
                openTowerDoorCell(
                        queue,
                        setBlock,
                        doorX,
                        centerZ + dz,
                        walkY
                );
            }
        }
        if (inwardZ != 0) {
            int doorZ = centerZ + inwardZ * TOWER_RADIUS;
            for (int dx = -1; dx <= 1; dx++) {
                openTowerDoorCell(
                        queue,
                        setBlock,
                        centerX + dx,
                        doorZ,
                        walkY
                );
            }
        }
    }

    private static void openTowerDoorCell(
            Queue<Runnable> queue,
            TerrainManager.SetBlock setBlock,
            int x,
            int z,
            int walkY) {
        for (int y = walkY + 1; y <= walkY + 3; y++) {
            place(queue, setBlock, x, y, z, Material.AIR);
        }
    }


    private static void addTowerExteriorDetails(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            int centerX,
            int centerZ,
            int villageCenterX,
            int villageCenterZ,
            int baseY) {
        int outwardX = -Integer.compare(villageCenterX, centerX);
        int outwardZ = -Integer.compare(villageCenterZ, centerZ);

        if (outwardX != 0) {
            int wallX = centerX + outwardX * TOWER_RADIUS;
            int bannerX = wallX + outwardX;
            place(queue, setBlock,
                    bannerX,
                    baseY + 7,
                    centerZ,
                    Material.RED_WALL_BANNER
            );
            queue.add(() -> VillageStyle.setDirectional(
                    world,
                    bannerX,
                    baseY + 7,
                    centerZ,
                    Material.RED_WALL_BANNER,
                    outwardX < 0 ? BlockFace.WEST : BlockFace.EAST
            ));
        }
        if (outwardZ != 0) {
            int wallZ = centerZ + outwardZ * TOWER_RADIUS;
            int bannerZ = wallZ + outwardZ;
            place(queue, setBlock,
                    centerX,
                    baseY + 7,
                    bannerZ,
                    Material.RED_WALL_BANNER
            );
            queue.add(() -> VillageStyle.setDirectional(
                    world,
                    centerX,
                    baseY + 7,
                    bannerZ,
                    Material.RED_WALL_BANNER,
                    outwardZ < 0 ? BlockFace.NORTH : BlockFace.SOUTH
            ));
        }

        // Lanterne suspendue sous une potence, à l'intérieur de la tour.
        place(queue, setBlock,
                centerX,
                baseY + 11,
                centerZ,
                Material.DARK_OAK_FENCE
        );
        place(queue, setBlock,
                centerX,
                baseY + 10,
                centerZ,
                Material.CHAIN
        );
        place(queue, setBlock,
                centerX,
                baseY + 9,
                centerZ,
                Material.LANTERN
        );
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
        int towerTopY = baseY + GATE_TOWER_HEIGHT;

        buildGateTower(
                queue,
                world,
                setBlock,
                leftMinX,
                leftMaxX,
                minZ,
                maxZ,
                baseY,
                towerTopY,
                BlockFace.EAST
        );
        buildGateTower(
                queue,
                world,
                setBlock,
                rightMinX,
                rightMaxX,
                minZ,
                maxZ,
                baseY,
                towerTopY,
                BlockFace.WEST
        );

        buildGateGallery(
                queue,
                world,
                setBlock,
                centerX,
                minZ,
                maxZ,
                baseY
        );

        /*
         * Nettoyage final du corridor. Les tours, la courtine et la galerie
         * ont été programmées avant ; cette passe garantit cinq blocs libres
         * sur toute la profondeur du châtelet.
         */
        for (int x = centerX - GATE_HALF_WIDTH;
             x <= centerX + GATE_HALF_WIDTH;
             x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                for (int y = baseY + 1; y <= baseY + 5; y++) {
                    int fx = x;
                    int fy = y;
                    int fz = z;
                    place(queue, setBlock,
                            fx,
                            fy,
                            fz,
                            Material.AIR
                    );
                }
            }
        }

        buildGateArches(
                queue,
                world,
                setBlock,
                centerX,
                minZ,
                maxZ,
                baseY
        );
        addRaisedPortcullis(
                queue,
                setBlock,
                centerX,
                maxZ - 1,
                baseY
        );
        addGatehouseIdentity(
                queue,
                world,
                setBlock,
                centerX,
                leftMaxX,
                rightMinX,
                maxZ,
                baseY
        );
    }

    private static void buildGateTower(Queue<Runnable> queue,
                                       World world,
                                       TerrainManager.SetBlock setBlock,
                                       int minX,
                                       int maxX,
                                       int minZ,
                                       int maxZ,
                                       int baseY,
                                       int topY,
                                       BlockFace galleryFace) {
        int centerX = (minX + maxX) / 2;
        int centerZ = (minZ + maxZ) / 2;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean shell = x == minX
                        || x == maxX
                        || z == minZ
                        || z == maxZ;

                for (int y = baseY - FOUNDATION_DEPTH;
                     y <= baseY;
                     y++) {
                    int fy = y;
                    Material material = foundationStone(x, y, z);
                    place(queue, setBlock,
                            x,
                            fy,
                            z,
                            material
                    );
                }

                if (shell) {
                    for (int y = baseY + 1; y <= topY; y++) {
                        int fy = y;
                        boolean southWindow = z == maxZ
                                && x == centerX
                                && (y == baseY + 8
                                || y == baseY + 9);
                        boolean slit = ((x == minX || x == maxX)
                                && z == centerZ
                                && (y == baseY + 4
                                || y == baseY + 11))
                                || (z == minZ
                                && x == centerX
                                && y == baseY + 5);
                        Material material;

                        if (southWindow) {
                            material = Material.GRAY_STAINED_GLASS;
                        } else if (slit) {
                            material = Material.IRON_BARS;
                        } else if (y == baseY + 6
                                || y == baseY + 12
                                || y == topY) {
                            material = Material.POLISHED_ANDESITE;
                        } else {
                            material = patternedStone(
                                    Material.STONE_BRICKS,
                                    x,
                                    y,
                                    z
                            );
                        }

                        Material finalMaterial = material;
                        place(queue, setBlock,
                                x,
                                fy,
                                z,
                                finalMaterial
                        );
                    }
                } else {
                    for (int y = baseY + 1; y < topY; y++) {
                        int fy = y;
                        Material material =
                                y == baseY + 6
                                        || y == baseY + 12
                                        ? Material.SPRUCE_PLANKS
                                        : Material.AIR;
                        Material finalMaterial = material;
                        place(queue, setBlock,
                                x,
                                fy,
                                z,
                                finalMaterial
                        );
                    }
                    place(queue, setBlock,
                            x,
                            topY,
                            z,
                            Material.DARK_OAK_PLANKS
                    );
                }
            }
        }

        // Porte de communication avec la galerie haute.
        int galleryX = galleryFace == BlockFace.EAST ? maxX : minX;
        for (int z = centerZ - 1; z <= centerZ + 1; z++) {
            for (int y = baseY + 7; y <= baseY + 9; y++) {
                int fy = y;
                int fz = z;
                place(queue, setBlock,
                        galleryX,
                        fy,
                        fz,
                        Material.AIR
                );
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
                Material.DARK_OAK_PLANKS,
                Material.DARK_OAK_STAIRS,
                Material.DARK_OAK_SLAB
        );
        queue.addAll(roof);

        int roofSpan = Math.min(
                maxX - minX + 3,
                maxZ - minZ + 3
        );
        int roofLayers = Math.max(1, (roofSpan + 1) / 2);
        int finialY = topY + roofLayers + 2;
        place(queue, setBlock,
                centerX,
                finialY,
                centerZ,
                Material.DARK_OAK_FENCE
        );
        place(queue, setBlock,
                centerX,
                finialY + 1,
                centerZ,
                Material.LIGHTNING_ROD
        );
    }

    private static void buildGateGallery(Queue<Runnable> queue,
                                         World world,
                                         TerrainManager.SetBlock setBlock,
                                         int centerX,
                                         int minZ,
                                         int maxZ,
                                         int baseY) {
        int floorY = baseY + 6;
        int roofY = baseY + 12;

        for (int x = centerX - GATE_HALF_WIDTH;
             x <= centerX + GATE_HALF_WIDTH;
             x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int fx = x;
                int fz = z;

                place(queue, setBlock,
                        fx,
                        floorY,
                        fz,
                        Math.floorMod(fx + fz, 2) == 0
                                ? Material.SPRUCE_PLANKS
                                : Material.DARK_OAK_PLANKS
                );
                place(queue, setBlock,
                        fx,
                        roofY,
                        fz,
                        Material.STONE_BRICKS
                );

                boolean facade = z == minZ || z == maxZ;
                if (facade) {
                    for (int y = floorY + 1; y < roofY; y++) {
                        int fy = y;
                        boolean glazed = Math.abs(x - centerX) <= 1
                                && (y == baseY + 9
                                || y == baseY + 10);
                        Material material = glazed
                                ? Material.GRAY_STAINED_GLASS
                                : patternedStone(
                                Material.STONE_BRICKS,
                                x,
                                y,
                                z
                        );
                        place(queue, setBlock,
                                fx,
                                fy,
                                fz,
                                material
                        );
                    }
                } else {
                    for (int y = floorY + 1; y < roofY; y++) {
                        int fy = y;
                        place(queue, setBlock,
                                fx,
                                fy,
                                fz,
                                Material.AIR
                        );
                    }
                }
            }
        }

        // Créneaux du pont supérieur, plus massifs que l'ancienne galerie.
        int merlonY = roofY + 1;
        for (int x = centerX - GATE_HALF_WIDTH;
             x <= centerX + GATE_HALF_WIDTH;
             x++) {
            if (merlonAt(x)) {
                addMerlon(
                        queue,
                        world,
                        setBlock,
                        x,
                        merlonY,
                        minZ
                );
                addMerlon(
                        queue,
                        world,
                        setBlock,
                        x,
                        merlonY,
                        maxZ
                );
            }
        }
        for (int z = minZ + 1; z <= maxZ - 1; z++) {
            place(queue, setBlock,
                    centerX - GATE_HALF_WIDTH,
                    merlonY,
                    z,
                    Material.STONE_BRICK_WALL
            );
            place(queue, setBlock,
                    centerX + GATE_HALF_WIDTH,
                    merlonY,
                    z,
                    Material.STONE_BRICK_WALL
            );
        }
    }

    private static void buildGateArches(Queue<Runnable> queue,
                                        World world,
                                        TerrainManager.SetBlock setBlock,
                                        int centerX,
                                        int minZ,
                                        int maxZ,
                                        int baseY) {
        for (int z : new int[]{minZ, maxZ}) {
            // Jambages épais de part et d'autre du passage.
            for (int y = baseY + 1; y <= baseY + 6; y++) {
                int fy = y;
                place(queue, setBlock,
                        centerX - GATE_HALF_WIDTH - 1,
                        fy,
                        z,
                        Material.POLISHED_ANDESITE
                );
                place(queue, setBlock,
                        centerX + GATE_HALF_WIDTH + 1,
                        fy,
                        z,
                        Material.POLISHED_ANDESITE
                );
            }

            // Épaules de l'arche : le centre reste libre jusqu'à cinq blocs.
            stair(
                    queue,
                    world,
                    setBlock,
                    centerX - GATE_HALF_WIDTH,
                    baseY + 5,
                    z,
                    Material.STONE_BRICK_STAIRS,
                    BlockFace.EAST,
                    Stairs.Half.TOP,
                    Stairs.Shape.STRAIGHT
            );
            stair(
                    queue,
                    world,
                    setBlock,
                    centerX + GATE_HALF_WIDTH,
                    baseY + 5,
                    z,
                    Material.STONE_BRICK_STAIRS,
                    BlockFace.WEST,
                    Stairs.Half.TOP,
                    Stairs.Shape.STRAIGHT
            );

            for (int x = centerX - GATE_HALF_WIDTH;
                 x <= centerX + GATE_HALF_WIDTH;
                 x++) {
                int fx = x;
                place(queue, setBlock,
                        fx,
                        baseY + 6,
                        z,
                        Material.CHISELED_STONE_BRICKS
                );
            }
        }
    }

    private static void addRaisedPortcullis(Queue<Runnable> queue,
                                            TerrainManager.SetBlock setBlock,
                                            int centerX,
                                            int z,
                                            int baseY) {
        /*
         * La herse est stockée au-dessus de l'arche. Elle reste visible depuis
         * la rue mais aucun barreau ne descend dans les cinq blocs de passage.
         */
        for (int x = centerX - GATE_HALF_WIDTH;
             x <= centerX + GATE_HALF_WIDTH;
             x++) {
            int fx = x;
            for (int y = baseY + 7; y <= baseY + 9; y++) {
                int fy = y;
                place(queue, setBlock,
                        fx,
                        fy,
                        z,
                        Material.IRON_BARS
                );
            }
        }

        // Glissières latérales de la herse.
        for (int y = baseY + 1; y <= baseY + 6; y++) {
            int fy = y;
            place(queue, setBlock,
                    centerX - GATE_HALF_WIDTH - 1,
                    fy,
                    z,
                    Material.IRON_BARS
            );
            place(queue, setBlock,
                    centerX + GATE_HALF_WIDTH + 1,
                    fy,
                    z,
                    Material.IRON_BARS
            );
        }
    }

    private static void addGatehouseIdentity(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            int centerX,
            int leftInnerX,
            int rightInnerX,
            int frontZ,
            int baseY) {
        for (int x : new int[]{leftInnerX, rightInnerX}) {
            place(queue, setBlock,
                    x,
                    baseY + 8,
                    frontZ + 1,
                    Material.DARK_OAK_FENCE
            );
            place(queue, setBlock,
                    x,
                    baseY + 7,
                    frontZ + 1,
                    Material.CHAIN
            );
            place(queue, setBlock,
                    x,
                    baseY + 6,
                    frontZ + 1,
                    Material.LANTERN
            );

            place(queue, setBlock,
                    x,
                    baseY + 9,
                    frontZ + 1,
                    Material.RED_WALL_BANNER
            );
            queue.add(() -> VillageStyle.setDirectional(
                    world,
                    x,
                    baseY + 9,
                    frontZ + 1,
                    Material.RED_WALL_BANNER,
                    BlockFace.SOUTH
            ));
        }

        // Blason central au-dessus de l'arche.
        place(queue, setBlock,
                centerX,
                baseY + 11,
                frontZ + 1,
                Material.YELLOW_WALL_BANNER
        );
        queue.add(() -> VillageStyle.setDirectional(
                world,
                centerX,
                baseY + 11,
                frontZ + 1,
                Material.YELLOW_WALL_BANNER,
                BlockFace.SOUTH
        ));
    }

    private static void buildGateApproach(Queue<Runnable> queue,
                                          TerrainManager.SetBlock setBlock,
                                          int centerX,
                                          int startZ,
                                          int endZ,
                                          int baseY) {
        for (int z = startZ; z <= endZ; z++) {
            for (int dx = -APPROACH_HALF_WIDTH;
                 dx <= APPROACH_HALF_WIDTH;
                 dx++) {
                int x = centerX + dx;
                int fz = z;

                place(queue, setBlock,
                        x,
                        baseY - 1,
                        fz,
                        Math.abs(dx) == APPROACH_HALF_WIDTH
                                ? Material.STONE_BRICKS
                                : Material.COBBLESTONE
                );

                Material material;
                if (Math.abs(dx) == APPROACH_HALF_WIDTH) {
                    material = Math.floorMod(x + z, 3) == 0
                            ? Material.POLISHED_ANDESITE
                            : Material.STONE_BRICKS;
                } else {
                    int selector = Math.floorMod(
                            x * 17 + z * 31,
                            7
                    );
                    material = switch (selector) {
                        case 0 -> Material.POLISHED_ANDESITE;
                        case 1 -> Material.COBBLESTONE;
                        case 2 -> Material.ANDESITE;
                        default -> Material.GRAVEL;
                    };
                }

                Material finalMaterial = material;
                place(queue, setBlock,
                        x,
                        baseY,
                        fz,
                        finalMaterial
                );
            }
        }

        // Bornes basses qui cadrent l'entrée sans gêner les joueurs.
        for (int dx : new int[]{
                -APPROACH_HALF_WIDTH,
                APPROACH_HALF_WIDTH
        }) {
            int x = centerX + dx;
            place(queue, setBlock,
                    x,
                    baseY + 1,
                    endZ - 2,
                    Material.STONE_BRICK_WALL
            );
            place(queue, setBlock,
                    x,
                    baseY + 2,
                    endZ - 2,
                    Material.LANTERN
            );
        }
    }

    private static void place(Queue<Runnable> queue,
                              TerrainManager.SetBlock setBlock,
                              int x,
                              int y,
                              int z,
                              Material material) {
        queue.add(() -> setBlock.set(x, y, z, material));
    }

    private static void stair(Queue<Runnable> queue,
                              World world,
                              TerrainManager.SetBlock setBlock,
                              int x,
                              int y,
                              int z,
                              Material material,
                              BlockFace facing,
                              Stairs.Half half,
                              Stairs.Shape shape) {
        place(queue, setBlock, x, y, z, material);
        queue.add(() -> VillageStyle.setStair(
                world,
                x,
                y,
                z,
                material,
                facing,
                half,
                shape
        ));
    }

    private static void slab(Queue<Runnable> queue,
                             World world,
                             TerrainManager.SetBlock setBlock,
                             int x,
                             int y,
                             int z,
                             Material material,
                             Slab.Type type) {
        place(queue, setBlock, x, y, z, material);
        queue.add(() -> VillageStyle.setSlab(
                world,
                x,
                y,
                z,
                material,
                type
        ));
    }

    private static boolean merlonAt(int coordinate) {
        return Math.floorMod(coordinate, 4) <= 1;
    }

    private static boolean isSlitCoordinate(int x,
                                            int z,
                                            int minX,
                                            int maxX) {
        return Math.floorMod(
                axisCoordinate(x, z, minX, maxX),
                9
        ) == 4;
    }

    private static Material walkwayStone(int x, int z) {
        return Math.floorMod(x * 13 + z * 7, 9) == 0
                ? Material.MOSSY_STONE_BRICKS
                : Material.POLISHED_ANDESITE;
    }

    private static Material foundationStone(int x, int y, int z) {
        int selector = Math.floorMod(
                x * 19 + y * 11 + z * 23,
                7
        );
        return selector <= 1
                ? Material.DEEPSLATE_BRICKS
                : Material.COBBLED_DEEPSLATE;
    }

    private static Material patternedStone(Material primary,
                                           int x,
                                           int y,
                                           int z) {
        int selector = Math.floorMod(
                x * 31 + y * 13 + z * 17,
                29
        );
        return switch (selector) {
            case 0, 7 -> Material.MOSSY_STONE_BRICKS;
            case 3 -> Material.CRACKED_STONE_BRICKS;
            case 11 -> Material.COBBLESTONE;
            case 17 -> Material.ANDESITE;
            case 23 -> Material.DEEPSLATE_BRICKS;
            default -> primary == null
                    ? Material.STONE_BRICKS
                    : primary;
        };
    }

    private static boolean isOuterFace(int x,
                                       int z,
                                       int minX,
                                       int maxX,
                                       int minZ,
                                       int maxZ) {
        return x == minX
                || x == maxX
                || z == minZ
                || z == maxZ;
    }

    private static BlockFace outwardFace(int x,
                                         int z,
                                         int minX,
                                         int maxX,
                                         int minZ,
                                         int maxZ) {
        if (x == minX) {
            return BlockFace.WEST;
        }
        if (x == maxX) {
            return BlockFace.EAST;
        }
        if (z == minZ) {
            return BlockFace.NORTH;
        }
        if (z == maxZ) {
            return BlockFace.SOUTH;
        }
        return BlockFace.SELF;
    }

    private static int axisCoordinate(int x,
                                      int z,
                                      int minX,
                                      int maxX) {
        return x == minX || x == maxX ? z : x;
    }

    private static boolean cardinal(BlockFace face) {
        return face == BlockFace.NORTH
                || face == BlockFace.SOUTH
                || face == BlockFace.EAST
                || face == BlockFace.WEST;
    }
}
