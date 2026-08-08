package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;

import java.util.Queue;

/**
 * Construit le donjon principal de l'enceinte.
 *
 * <p>Le bâtiment est volontairement placé au nord, exactement à l'opposé du
 * châtelet sud. Les deux tours méridionales absorbent la courtine et ouvrent un
 * passage au niveau du chemin de ronde : le donjon fait donc réellement partie
 * de la défense, au lieu d'être un bâtiment simplement posé près du mur.</p>
 *
 * <p>La géométrie reste déterministe. Cela facilite l'annulation transactionnelle
 * de {@code /village}, les tests structurels et l'ajout ultérieur de PNJ ou de
 * quêtes utilisant l'ancre {@code keep}.</p>
 */
public final class KeepBuilder {

    /**
     * Distance minimale entre les derniers lots et la face intérieure du mur.
     * Les six premiers blocs accueillent le porche et son dégagement.
     */
    private static final int MINIMUM_WALL_GAP = 7;

    /** Profondeur assurant une assise stable sous le donjon massif. */
    private static final int FOUNDATION_DEPTH = 4;

    /*
     * Le corps central mesure 17 x 15 blocs. Les quatre tours de rayon trois
     * portent l'emprise totale à 23 x 21 blocs, avant les détails extérieurs.
     */
    private static final int BODY_HALF_WIDTH = 8;
    private static final int BODY_DEPTH = 15;
    private static final int TOWER_RADIUS = 3;

    /*
     * Niveaux absolus relatifs au sol. Le chemin de ronde de WallBuilder est à
     * +9 ; le premier étage du donjon utilise exactement le même plancher.
     */
    private static final int WALL_WALK_LEVEL = WallBuilder.WALL_HEIGHT;
    private static final int UPPER_FLOOR_LEVEL = 16;
    private static final int BODY_ROOF_LEVEL = 22;
    private static final int TOWER_ROOF_LEVEL = 26;
    private static final int BODY_PARAPET_LEVEL = 23;
    private static final int TOWER_PARAPET_LEVEL = 27;

    /*
     * Le paratonnerre du mât est le point construit le plus haut. La valeur est
     * publique via maximumRelativeHeight() pour valider l'altitude avant toute
     * écriture dans le monde.
     */
    private static final int MAX_RELATIVE_HEIGHT = 31;

    private static final int ENTRANCE_MIN_X_OFFSET = -1;
    private static final int ENTRANCE_MAX_X_OFFSET = 0;
    private static final int ENTRANCE_CLEAR_HEIGHT = 5;

    private static final int[][] CARDINAL_DIRECTIONS = {
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1}
    };

    private KeepBuilder() {
    }

    /**
     * Marge minimale requise entre le plan urbain et la muraille.
     */
    public static int minimumWallGap() {
        return MINIMUM_WALL_GAP;
    }

    /**
     * Profondeur maximale écrite sous le niveau principal.
     */
    public static int foundationDepth() {
        return FOUNDATION_DEPTH;
    }

    /**
     * Hauteur maximale écrite au-dessus du niveau principal.
     */
    public static int maximumRelativeHeight() {
        return MAX_RELATIVE_HEIGHT;
    }

    /**
     * Vérifie que toutes les fondations et le mât restent dans les limites
     * verticales du monde. {@link World#getMaxHeight()} est une borne exclusive.
     */
    public static boolean fitsVertically(World world, int baseY) {
        return world != null
                && baseY - FOUNDATION_DEPTH >= world.getMinHeight()
                && baseY + MAX_RELATIVE_HEIGHT < world.getMaxHeight();
    }

    /**
     * Renvoie l'emprise complète du donjon, détails et porche compris.
     *
     * <p>Ordre du tableau : minX, maxX, minZ, maxZ.</p>
     */
    public static int[] outerBounds(Location center, int rz) {
        if (center == null) {
            return new int[]{0, 0, 0, 0};
        }

        Geometry geometry = Geometry.from(center, rz);
        return new int[]{
                geometry.footprintMinX() - 2,
                geometry.footprintMaxX() + 2,
                geometry.footprintMinZ() - 2,
                Math.max(
                        geometry.footprintMaxZ() + 1,
                        geometry.bodySouthZ() + 5
                )
        };
    }

    /**
     * Point d'arrivée sûr situé dans le vestibule, derrière la herse.
     */
    public static Location keepAnchor(Location center, int rz, int baseY) {
        if (center == null) {
            return null;
        }

        Geometry geometry = Geometry.from(center, rz);
        return new Location(
                center.getWorld(),
                geometry.centerX() + 0.5D,
                baseY + 1.0D,
                geometry.bodySouthZ() - 3 + 0.5D
        );
    }

    /**
     * Réserve l'emprise au sol pour empêcher les lampadaires automatiques de
     * traverser le porche ou les tours. La méthode reste au niveau du package
     * afin que le planificateur d'éclairage puisse l'utiliser sans exposer une
     * nouvelle API publique.
     */
    static boolean reservesGround(int centerX,
                                  int centerZ,
                                  int rz,
                                  int x,
                                  int z,
                                  int margin) {
        Geometry geometry = Geometry.from(centerX, centerZ, rz);
        int safeMargin = Math.max(0, margin);
        int minX = geometry.footprintMinX() - safeMargin;
        int maxX = geometry.footprintMaxX() + safeMargin;
        int minZ = geometry.footprintMinZ() - safeMargin;
        int maxZ = Math.max(
                geometry.footprintMaxZ(),
                geometry.bodySouthZ() + 5
        ) + safeMargin;

        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /**
     * Ajoute toutes les opérations du donjon à la file de génération.
     *
     * <p>Les gros volumes sont regroupés par colonne. Une action peut donc
     * écrire plusieurs blocs, mais chaque écriture passe toujours par
     * {@code setBlock} et reste ainsi suivie par
     * {@link VillageGenerationSession} pour {@code /village undo}.</p>
     */
    public static void build(Location center,
                             int rz,
                             int baseY,
                             Material primaryWallMaterial,
                             Queue<Runnable> queue,
                             TerrainManager.SetBlock setBlock) {
        if (center == null || queue == null || setBlock == null) {
            return;
        }

        World world = center.getWorld();
        Geometry geometry = Geometry.from(center, rz);
        Material primary = normalizePrimary(primaryWallMaterial);

        /*
         * L'ordre est important : dégagement, gros œuvre, ouvertures, accès,
         * mobilier, puis détails. Les dernières opérations peuvent ainsi
         * remplacer proprement la courtine déjà construite.
         */
        clearBuildVolume(queue, setBlock, geometry, baseY);
        buildFoundationAndShell(
                queue,
                setBlock,
                geometry,
                baseY,
                primary
        );
        buildBatteredBase(
                queue,
                world,
                setBlock,
                geometry,
                baseY,
                primary
        );
        carveEntrance(
                queue,
                world,
                setBlock,
                geometry,
                baseY
        );
        carveWallWalkConnections(
                queue,
                world,
                setBlock,
                geometry,
                baseY
        );
        addWindowsAndArrowSlits(
                queue,
                world,
                setBlock,
                geometry,
                baseY
        );
        buildInteriorStairs(
                queue,
                world,
                setBlock,
                geometry,
                baseY
        );
        furnishGroundFloor(
                queue,
                world,
                setBlock,
                geometry,
                baseY
        );
        furnishGuardFloor(
                queue,
                world,
                setBlock,
                geometry,
                baseY
        );
        furnishLordFloor(
                queue,
                world,
                setBlock,
                geometry,
                baseY
        );
        addExteriorDetails(
                queue,
                world,
                setBlock,
                geometry,
                baseY
        );
        buildBattlements(
                queue,
                world,
                setBlock,
                geometry,
                baseY
        );
        buildEntranceApproach(
                queue,
                world,
                setBlock,
                geometry,
                baseY
        );
        addLightingAndIdentity(
                queue,
                world,
                setBlock,
                geometry,
                baseY
        );
    }

    /**
     * Retire l'ancien tronçon de courtine et tout obstacle présent dans
     * l'emprise. Une tâche par colonne évite d'ajouter des dizaines de milliers
     * d'objets Runnable à la file.
     */
    private static void clearBuildVolume(Queue<Runnable> queue,
                                         TerrainManager.SetBlock setBlock,
                                         Geometry geometry,
                                         int baseY) {
        for (int x = geometry.footprintMinX();
             x <= geometry.footprintMaxX();
             x++) {
            for (int z = geometry.footprintMinZ();
                 z <= geometry.footprintMaxZ();
                 z++) {
                int columnX = x;
                int columnZ = z;
                queue.add(() -> {
                    for (int relativeY = 1;
                         relativeY <= MAX_RELATIVE_HEIGHT;
                         relativeY++) {
                        setBlock.set(
                                columnX,
                                baseY + relativeY,
                                columnZ,
                                Material.AIR
                        );
                    }
                });
            }
        }

        /*
         * Le porche central dépasse légèrement les tours méridionales. Seule la
         * bande de circulation est dégagée afin de préserver la courtine et les
         * éventuelles décorations voisines.
         */
        for (int x = geometry.centerX() - 3;
             x <= geometry.centerX() + 2;
             x++) {
            for (int z = geometry.bodySouthZ() + 1;
                 z <= geometry.bodySouthZ() + 5;
                 z++) {
                int columnX = x;
                int columnZ = z;
                queue.add(() -> {
                    for (int relativeY = 1;
                         relativeY <= ENTRANCE_CLEAR_HEIGHT + 2;
                         relativeY++) {
                        setBlock.set(
                                columnX,
                                baseY + relativeY,
                                columnZ,
                                Material.AIR
                        );
                    }
                });
            }
        }
    }

    /**
     * Construit les fondations, le corps à double épaisseur, les planchers et
     * les quatre tours. Le calcul de contour porte sur l'union des volumes :
     * aucune cloison artificielle ne subsiste à la jonction corps/tour.
     */
    private static void buildFoundationAndShell(
            Queue<Runnable> queue,
            TerrainManager.SetBlock setBlock,
            Geometry geometry,
            int baseY,
            Material primary) {
        for (int x = geometry.footprintMinX();
             x <= geometry.footprintMaxX();
             x++) {
            for (int z = geometry.footprintMinZ();
                 z <= geometry.footprintMaxZ();
                 z++) {
                if (!isLowerFootprint(geometry, x, z)) {
                    continue;
                }

                int columnX = x;
                int columnZ = z;
                boolean thickShell = isThickLowerShell(
                        geometry,
                        x,
                        z
                );
                boolean towerCell = isAnyTowerCell(geometry, x, z);
                boolean upperTowerBoundary = towerCell
                        && isUpperTowerBoundary(geometry, x, z);

                queue.add(() -> {
                    for (int relativeY = -FOUNDATION_DEPTH;
                         relativeY < 0;
                         relativeY++) {
                        setBlock.set(
                                columnX,
                                baseY + relativeY,
                                columnZ,
                                foundationStone(
                                        columnX,
                                        baseY + relativeY,
                                        columnZ
                                )
                        );
                    }

                    setBlock.set(
                            columnX,
                            baseY,
                            columnZ,
                            groundFloorMaterial(
                                    thickShell,
                                    columnX,
                                    columnZ
                            )
                    );

                    for (int relativeY = 1;
                         relativeY <= BODY_ROOF_LEVEL;
                         relativeY++) {
                        boolean floor = relativeY == WALL_WALK_LEVEL
                                || relativeY == UPPER_FLOOR_LEVEL
                                || relativeY == BODY_ROOF_LEVEL;

                        if (thickShell) {
                            setBlock.set(
                                    columnX,
                                    baseY + relativeY,
                                    columnZ,
                                    wallStone(
                                            primary,
                                            columnX,
                                            baseY + relativeY,
                                            columnZ,
                                            relativeY
                                    )
                            );
                        } else if (floor) {
                            setBlock.set(
                                    columnX,
                                    baseY + relativeY,
                                    columnZ,
                                    floorMaterial(
                                            relativeY,
                                            columnX,
                                            columnZ
                                    )
                            );
                        }
                    }

                    if (!towerCell) {
                        return;
                    }

                    for (int relativeY = BODY_ROOF_LEVEL + 1;
                         relativeY <= TOWER_ROOF_LEVEL;
                         relativeY++) {
                        if (relativeY == TOWER_ROOF_LEVEL) {
                            setBlock.set(
                                    columnX,
                                    baseY + relativeY,
                                    columnZ,
                                    towerDeckMaterial(columnX, columnZ)
                            );
                        } else if (upperTowerBoundary) {
                            setBlock.set(
                                    columnX,
                                    baseY + relativeY,
                                    columnZ,
                                    wallStone(
                                            primary,
                                            columnX,
                                            baseY + relativeY,
                                            columnZ,
                                            relativeY
                                    )
                            );
                        }
                    }
                });
            }
        }
    }

    /**
     * Ajoute un talus bas autour de l'ouvrage. Cette assise masque la coupure du
     * terrain tout en laissant le porche et les raccords de courtine libres.
     */
    private static void buildBatteredBase(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            Geometry geometry,
            int baseY,
            Material primary) {
        for (int x = geometry.footprintMinX() - 1;
             x <= geometry.footprintMaxX() + 1;
             x++) {
            for (int z = geometry.footprintMinZ() - 1;
                 z <= geometry.footprintMaxZ() + 1;
                 z++) {
                if (isLowerFootprint(geometry, x, z)
                        || !touchesLowerFootprint(geometry, x, z)
                        || isEntranceClearance(geometry, x, z)
                        || isWallWalkAxis(geometry, x, z)) {
                    continue;
                }

                int skirtX = x;
                int skirtZ = z;
                place(
                        queue,
                        setBlock,
                        skirtX,
                        baseY - 1,
                        skirtZ,
                        foundationStone(skirtX, baseY - 1, skirtZ)
                );
                place(
                        queue,
                        setBlock,
                        skirtX,
                        baseY,
                        skirtZ,
                        Math.floorMod(skirtX * 17 + skirtZ * 11, 5) == 0
                                ? Material.MOSSY_COBBLESTONE
                                : Material.COBBLED_DEEPSLATE
                );

                /*
                 * Une dalle ponctuelle casse la ligne horizontale du talus sans
                 * créer une marche continue autour du bâtiment.
                 */
                if (Math.floorMod(skirtX * 13 + skirtZ * 7, 4) == 0) {
                    slab(
                            queue,
                            world,
                            setBlock,
                            skirtX,
                            baseY + 1,
                            skirtZ,
                            Material.STONE_BRICK_SLAB,
                            Slab.Type.BOTTOM
                    );
                }
            }
        }

        /*
         * Quatre contreforts principaux donnent au corps central la silhouette
         * massive visible sur les donjons romans et anglo-normands.
         */
        buildButtress(
                queue,
                world,
                setBlock,
                geometry.centerX() - 4,
                geometry.bodyNorthZ() - 1,
                baseY,
                BlockFace.NORTH,
                12,
                primary
        );
        buildButtress(
                queue,
                world,
                setBlock,
                geometry.centerX() + 4,
                geometry.bodyNorthZ() - 1,
                baseY,
                BlockFace.NORTH,
                12,
                primary
        );
        buildButtress(
                queue,
                world,
                setBlock,
                geometry.centerX() - BODY_HALF_WIDTH - 1,
                geometry.bodyCenterZ(),
                baseY,
                BlockFace.WEST,
                10,
                primary
        );
        buildButtress(
                queue,
                world,
                setBlock,
                geometry.centerX() + BODY_HALF_WIDTH + 1,
                geometry.bodyCenterZ(),
                baseY,
                BlockFace.EAST,
                10,
                primary
        );
    }

    /**
     * Ouvre un vestibule à deux battants et place une herse entièrement relevée.
     * Aucun barreau n'occupe les cinq blocs nécessaires au passage du joueur.
     */
    private static void carveEntrance(Queue<Runnable> queue,
                                      World world,
                                      TerrainManager.SetBlock setBlock,
                                      Geometry geometry,
                                      int baseY) {
        int centerX = geometry.centerX();
        int facadeZ = geometry.bodySouthZ();

        for (int x = centerX + ENTRANCE_MIN_X_OFFSET;
             x <= centerX + ENTRANCE_MAX_X_OFFSET;
             x++) {
            for (int z = facadeZ - 2; z <= facadeZ; z++) {
                for (int relativeY = 1;
                     relativeY <= ENTRANCE_CLEAR_HEIGHT;
                     relativeY++) {
                    place(
                            queue,
                            setBlock,
                            x,
                            baseY + relativeY,
                            z,
                            Material.AIR
                    );
                }
            }
        }

        /*
         * Jambages et linteau en andésite : leur teinte plus claire rend
         * l'entrée lisible depuis la rue sans employer de matériaux modernes.
         */
        for (int x : new int[]{centerX - 2, centerX + 1}) {
            for (int relativeY = 1; relativeY <= 6; relativeY++) {
                place(
                        queue,
                        setBlock,
                        x,
                        baseY + relativeY,
                        facadeZ,
                        relativeY == 3
                                ? Material.CHISELED_STONE_BRICKS
                                : Material.POLISHED_ANDESITE
                );
            }
        }
        for (int x = centerX - 1; x <= centerX; x++) {
            place(
                    queue,
                    setBlock,
                    x,
                    baseY + 6,
                    facadeZ,
                    Material.CHISELED_STONE_BRICKS
            );
        }
        stair(
                queue,
                world,
                setBlock,
                centerX - 2,
                baseY + 6,
                facadeZ + 1,
                Material.STONE_BRICK_STAIRS,
                BlockFace.EAST,
                Stairs.Half.TOP,
                Stairs.Shape.STRAIGHT
        );
        stair(
                queue,
                world,
                setBlock,
                centerX + 1,
                baseY + 6,
                facadeZ + 1,
                Material.STONE_BRICK_STAIRS,
                BlockFace.WEST,
                Stairs.Half.TOP,
                Stairs.Shape.STRAIGHT
        );

        /*
         * Les barreaux sont stockés au-dessus de l'ouverture. La herse reste
         * visible, mais le passage inférieur demeure totalement libre.
         */
        for (int x = centerX - 1; x <= centerX; x++) {
            for (int relativeY = 7; relativeY <= 9; relativeY++) {
                place(
                        queue,
                        setBlock,
                        x,
                        baseY + relativeY,
                        facadeZ,
                        Material.IRON_BARS
                );
            }
        }

        int doorZ = facadeZ - 2;
        doubleDoor(
                queue,
                world,
                setBlock,
                centerX - 1,
                centerX,
                baseY + 1,
                doorZ,
                Material.DARK_OAK_DOOR,
                BlockFace.SOUTH
        );

        // Seuil profond : les portes ne flottent jamais au-dessus du terrain.
        for (int x = centerX - 1; x <= centerX; x++) {
            place(
                    queue,
                    setBlock,
                    x,
                    baseY,
                    doorZ,
                    Material.POLISHED_ANDESITE
            );
        }
    }

    /**
     * Raccorde les deux tronçons de la courtine au premier étage du donjon.
     * Le chemin reste large de trois blocs et conserve exactement l'altitude
     * utilisée par {@link WallBuilder}.
     */
    private static void carveWallWalkConnections(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            Geometry geometry,
            int baseY) {
        int outerNorthZ = geometry.innerNorthZ()
                - WallBuilder.WALL_THICKNESS
                + 1;
        int innerNorthZ = geometry.innerNorthZ();
        int floorY = baseY + WALL_WALK_LEVEL;

        for (int z = outerNorthZ; z <= innerNorthZ; z++) {
            for (int x = geometry.footprintMinX();
                 x <= geometry.centerX() - BODY_HALF_WIDTH + 3;
                 x++) {
                carveWalkwayCell(
                        queue,
                        setBlock,
                        x,
                        z,
                        floorY
                );
            }
            for (int x = geometry.centerX() + BODY_HALF_WIDTH - 3;
                 x <= geometry.footprintMaxX();
                 x++) {
                carveWalkwayCell(
                        queue,
                        setBlock,
                        x,
                        z,
                        floorY
                );
            }
        }

        /*
         * Deux arcs bas identifient les accès depuis les courtines. Ils sont
         * placés au-dessus de la tête et n'altèrent pas la largeur utile.
         */
        for (int side : new int[]{-1, 1}) {
            int x = side < 0
                    ? geometry.footprintMinX()
                    : geometry.footprintMaxX();
            BlockFace facing = side < 0
                    ? BlockFace.EAST
                    : BlockFace.WEST;
            stair(
                    queue,
                    world,
                    setBlock,
                    x,
                    floorY + 4,
                    outerNorthZ + 1,
                    Material.STONE_BRICK_STAIRS,
                    facing,
                    Stairs.Half.TOP,
                    Stairs.Shape.STRAIGHT
            );
        }
    }

    private static void carveWalkwayCell(
            Queue<Runnable> queue,
            TerrainManager.SetBlock setBlock,
            int x,
            int z,
            int floorY) {
        place(
                queue,
                setBlock,
                x,
                floorY,
                z,
                walkwayStone(x, z)
        );
        for (int y = floorY + 1; y <= floorY + 4; y++) {
            place(queue, setBlock, x, y, z, Material.AIR);
        }
    }

    /**
     * Ajoute des archères basses et de vraies fenêtres hautes. Les baies en
     * verre traversent les deux épaisseurs de maçonnerie pour éclairer les
     * pièces ; les archères conservent une peau extérieure en barreaux.
     */
    private static void addWindowsAndArrowSlits(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            Geometry geometry,
            int baseY) {
        int centerX = geometry.centerX();

        // Façade nord : archères au rez-de-chaussée, fenêtres aux étages.
        for (int xOffset : new int[]{-3, 3}) {
            addDeepOpening(
                    queue,
                    setBlock,
                    centerX + xOffset,
                    geometry.bodyNorthZ(),
                    0,
                    1,
                    baseY + 4,
                    Material.IRON_BARS
            );
            addDeepWindow(
                    queue,
                    setBlock,
                    centerX + xOffset,
                    geometry.bodyNorthZ(),
                    0,
                    1,
                    baseY + 12,
                    2,
                    Material.GRAY_STAINED_GLASS
            );
            addDeepWindow(
                    queue,
                    setBlock,
                    centerX + xOffset,
                    geometry.bodyNorthZ(),
                    0,
                    1,
                    baseY + 18,
                    2,
                    Material.LIGHT_BLUE_STAINED_GLASS
            );
        }

        // Façade sud, de part et d'autre de l'entrée.
        for (int xOffset : new int[]{-4, 3}) {
            addDeepWindow(
                    queue,
                    setBlock,
                    centerX + xOffset,
                    geometry.bodySouthZ(),
                    0,
                    -1,
                    baseY + 12,
                    2,
                    Material.GRAY_STAINED_GLASS
            );
            addDeepWindow(
                    queue,
                    setBlock,
                    centerX + xOffset,
                    geometry.bodySouthZ(),
                    0,
                    -1,
                    baseY + 18,
                    2,
                    Material.LIGHT_BLUE_STAINED_GLASS
            );
        }

        // Baies latérales au milieu du corps central.
        for (int side : new int[]{-1, 1}) {
            int x = centerX + side * BODY_HALF_WIDTH;
            int inwardX = -side;
            for (int relativeY : new int[]{12, 18}) {
                addDeepWindow(
                        queue,
                        setBlock,
                        x,
                        geometry.bodyCenterZ(),
                        inwardX,
                        0,
                        baseY + relativeY,
                        2,
                        relativeY == 12
                                ? Material.GRAY_STAINED_GLASS
                                : Material.LIGHT_BLUE_STAINED_GLASS
                );
            }
        }

        /*
         * Chaque tour reçoit deux archères tournées vers l'extérieur. La cellule
         * située derrière les barreaux est dégagée pour que la meurtrière soit
         * lisible depuis l'intérieur.
         */
        addTowerSlits(
                queue,
                setBlock,
                geometry,
                baseY,
                geometry.westTowerX(),
                geometry.northTowerZ(),
                BlockFace.WEST,
                BlockFace.NORTH
        );
        addTowerSlits(
                queue,
                setBlock,
                geometry,
                baseY,
                geometry.eastTowerX(),
                geometry.northTowerZ(),
                BlockFace.EAST,
                BlockFace.NORTH
        );
        addTowerSlits(
                queue,
                setBlock,
                geometry,
                baseY,
                geometry.westTowerX(),
                geometry.southTowerZ(),
                BlockFace.WEST,
                BlockFace.SOUTH
        );
        addTowerSlits(
                queue,
                setBlock,
                geometry,
                baseY,
                geometry.eastTowerX(),
                geometry.southTowerZ(),
                BlockFace.EAST,
                BlockFace.SOUTH
        );

        // Volets verticaux autour des fenêtres de la façade sud.
        for (int xOffset : new int[]{-5, -3, 2, 4}) {
            int x = centerX + xOffset;
            place(
                    queue,
                    setBlock,
                    x,
                    baseY + 12,
                    geometry.bodySouthZ() + 1,
                    Material.DARK_OAK_TRAPDOOR
            );
            queue.add(() -> VillageStyle.setTrapdoor(
                    world,
                    x,
                    baseY + 12,
                    geometry.bodySouthZ() + 1,
                    Material.DARK_OAK_TRAPDOOR,
                    BlockFace.SOUTH,
                    false,
                    Bisected.Half.TOP
            ));
        }
    }

    private static void addDeepOpening(
            Queue<Runnable> queue,
            TerrainManager.SetBlock setBlock,
            int x,
            int z,
            int inwardX,
            int inwardZ,
            int y,
            Material facadeMaterial) {
        place(queue, setBlock, x, y, z, facadeMaterial);
        place(
                queue,
                setBlock,
                x + inwardX,
                y,
                z + inwardZ,
                Material.AIR
        );
        place(
                queue,
                setBlock,
                x + inwardX,
                y + 1,
                z + inwardZ,
                Material.AIR
        );
    }

    private static void addDeepWindow(
            Queue<Runnable> queue,
            TerrainManager.SetBlock setBlock,
            int x,
            int z,
            int inwardX,
            int inwardZ,
            int startY,
            int height,
            Material glass) {
        for (int offset = 0; offset < height; offset++) {
            place(queue, setBlock, x, startY + offset, z, glass);
            place(
                    queue,
                    setBlock,
                    x + inwardX,
                    startY + offset,
                    z + inwardZ,
                    glass
            );
        }
    }

    private static void addTowerSlits(
            Queue<Runnable> queue,
            TerrainManager.SetBlock setBlock,
            Geometry geometry,
            int baseY,
            int towerX,
            int towerZ,
            BlockFace firstOutward,
            BlockFace secondOutward) {
        for (BlockFace outward : new BlockFace[]{
                firstOutward,
                secondOutward
        }) {
            int outerX = towerX + outward.getModX() * TOWER_RADIUS;
            int outerZ = towerZ + outward.getModZ() * TOWER_RADIUS;
            int innerX = outerX - outward.getModX();
            int innerZ = outerZ - outward.getModZ();

            for (int relativeY : new int[]{4, 12, 19, 24}) {
                place(
                        queue,
                        setBlock,
                        outerX,
                        baseY + relativeY,
                        outerZ,
                        Material.IRON_BARS
                );
                place(
                        queue,
                        setBlock,
                        innerX,
                        baseY + relativeY,
                        innerZ,
                        Material.AIR
                );
                if (relativeY < BODY_ROOF_LEVEL) {
                    place(
                            queue,
                            setBlock,
                            innerX,
                            baseY + relativeY + 1,
                            innerZ,
                            Material.AIR
                    );
                }
            }
        }
    }

    /**
     * Trois volées droites de deux blocs de large desservent tous les niveaux.
     * Une quatrième volée compacte donne accès au sommet de la tour nord-ouest.
     */
    private static void buildInteriorStairs(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            Geometry geometry,
            int baseY) {
        buildStraightStair(
                queue,
                world,
                setBlock,
                geometry.centerX() + 3,
                geometry.bodySouthZ() - 3,
                baseY + 1,
                9,
                2,
                BlockFace.NORTH,
                Material.DARK_OAK_STAIRS
        );
        buildStraightStair(
                queue,
                world,
                setBlock,
                geometry.centerX() - 4,
                geometry.bodyNorthZ() + 3,
                baseY + WALL_WALK_LEVEL + 1,
                7,
                2,
                BlockFace.SOUTH,
                Material.DARK_OAK_STAIRS
        );
        buildStraightStair(
                queue,
                world,
                setBlock,
                geometry.centerX(),
                geometry.bodySouthZ() - 4,
                baseY + UPPER_FLOOR_LEVEL + 1,
                6,
                2,
                BlockFace.NORTH,
                Material.STONE_BRICK_STAIRS
        );

        /*
         * La terrasse principale se trouve à +22. Quatre marches montent sur la
         * plateforme crénelée de la tour nord-ouest à +26.
         */
        buildStraightStair(
                queue,
                world,
                setBlock,
                geometry.westTowerX() - 1,
                geometry.northTowerZ() + 2,
                baseY + BODY_ROOF_LEVEL + 1,
                4,
                2,
                BlockFace.NORTH,
                Material.STONE_BRICK_STAIRS
        );

    }

    private static void buildStraightStair(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            int startX,
            int startZ,
            int startY,
            int steps,
            int width,
            BlockFace direction,
            Material material) {
        BlockFace lateral = VillageStyle.rightOf(direction);
        int safeSteps = Math.max(1, steps);
        int safeWidth = Math.max(1, width);

        for (int step = 0; step < safeSteps; step++) {
            int x = startX + direction.getModX() * step;
            int z = startZ + direction.getModZ() * step;
            int y = startY + step;

            for (int side = 0; side < safeWidth; side++) {
                int stairX = x + lateral.getModX() * side;
                int stairZ = z + lateral.getModZ() * side;

                /*
                 * Le dégagement est posé avant l'escalier. Il retire notamment
                 * le plancher traversé par les deux dernières marches.
                 */
                place(
                        queue,
                        setBlock,
                        stairX,
                        y + 1,
                        stairZ,
                        Material.AIR
                );
                place(
                        queue,
                        setBlock,
                        stairX,
                        y + 2,
                        stairZ,
                        Material.AIR
                );
                stair(
                        queue,
                        world,
                        setBlock,
                        stairX,
                        y,
                        stairZ,
                        material,
                        direction,
                        Stairs.Half.BOTTOM,
                        Stairs.Shape.STRAIGHT
                );
            }
        }
    }

    /**
     * Grande salle, cuisine et armurerie du rez-de-chaussée.
     */
    private static void furnishGroundFloor(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            Geometry geometry,
            int baseY) {
        int centerX = geometry.centerX();

        // Table commune centrale.
        for (int z = geometry.bodyCenterZ() - 2;
             z <= geometry.bodyCenterZ() + 2;
             z++) {
            slab(
                    queue,
                    world,
                    setBlock,
                    centerX - 1,
                    baseY + 1,
                    z,
                    Material.DARK_OAK_SLAB,
                    Slab.Type.TOP
            );
            slab(
                    queue,
                    world,
                    setBlock,
                    centerX,
                    baseY + 1,
                    z,
                    Material.DARK_OAK_SLAB,
                    Slab.Type.TOP
            );
        }
        for (int z : new int[]{
                geometry.bodyCenterZ() - 3,
                geometry.bodyCenterZ() + 3
        }) {
            stair(
                    queue,
                    world,
                    setBlock,
                    centerX - 1,
                    baseY + 1,
                    z,
                    Material.SPRUCE_STAIRS,
                    z < geometry.bodyCenterZ()
                            ? BlockFace.SOUTH
                            : BlockFace.NORTH,
                    Stairs.Half.BOTTOM,
                    Stairs.Shape.STRAIGHT
            );
            stair(
                    queue,
                    world,
                    setBlock,
                    centerX,
                    baseY + 1,
                    z,
                    Material.SPRUCE_STAIRS,
                    z < geometry.bodyCenterZ()
                            ? BlockFace.SOUTH
                            : BlockFace.NORTH,
                    Stairs.Half.BOTTOM,
                    Stairs.Shape.STRAIGHT
            );
        }

        // Armurerie à l'ouest, hors de l'emprise de l'escalier principal.
        int armoryX = centerX - 5;
        place(
                queue,
                setBlock,
                armoryX,
                baseY + 1,
                geometry.bodySouthZ() - 4,
                Material.SMITHING_TABLE
        );
        place(
                queue,
                setBlock,
                armoryX,
                baseY + 1,
                geometry.bodySouthZ() - 5,
                Material.ANVIL
        );
        place(
                queue,
                setBlock,
                armoryX,
                baseY + 1,
                geometry.bodySouthZ() - 6,
                Material.GRINDSTONE
        );
        queue.add(() -> VillageStyle.setDirectional(
                world,
                armoryX,
                baseY + 1,
                geometry.bodySouthZ() - 6,
                Material.GRINDSTONE,
                BlockFace.EAST
        ));
        for (int z = geometry.bodySouthZ() - 8;
             z <= geometry.bodySouthZ() - 7;
             z++) {
            int barrelZ = z;
            place(
                    queue,
                    setBlock,
                    armoryX,
                    baseY + 1,
                    barrelZ,
                    Material.BARREL
            );
            queue.add(() -> VillageStyle.setDirectional(
                    world,
                    armoryX,
                    baseY + 1,
                    barrelZ,
                    Material.BARREL,
                    BlockFace.EAST
            ));
        }

        // Cuisine et foyer adossés au mur nord.
        int kitchenZ = geometry.bodyNorthZ() + 3;
        place(
                queue,
                setBlock,
                centerX - 1,
                baseY + 1,
                kitchenZ,
                Material.SMOKER
        );
        place(
                queue,
                setBlock,
                centerX,
                baseY + 1,
                kitchenZ,
                Material.CAULDRON
        );
        place(
                queue,
                setBlock,
                centerX + 1,
                baseY + 1,
                kitchenZ,
                Material.CAMPFIRE
        );
        place(
                queue,
                setBlock,
                centerX + 1,
                baseY + 2,
                kitchenZ,
                Material.IRON_BARS
        );

        /*
         * Un conduit en briques traverse les planchers sur une seule colonne.
         * Il évite que la fumée du feu ne s'accumule visuellement dans la salle.
         */
        for (int relativeY = 2;
             relativeY <= BODY_ROOF_LEVEL + 3;
             relativeY++) {
            place(
                    queue,
                    setBlock,
                    centerX + 2,
                    baseY + relativeY,
                    geometry.bodyNorthZ() + 1,
                    relativeY >= BODY_ROOF_LEVEL
                            ? Material.BRICKS
                            : Material.BRICK_WALL
            );
        }

        addHangingLight(
                queue,
                world,
                setBlock,
                centerX,
                baseY + WALL_WALK_LEVEL,
                geometry.bodyCenterZ()
        );
    }

    /**
     * Salle des gardes au niveau du chemin de ronde.
     */
    private static void furnishGuardFloor(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            Geometry geometry,
            int baseY) {
        int floorY = baseY + WALL_WALK_LEVEL;
        int centerX = geometry.centerX();

        place(
                queue,
                setBlock,
                centerX - 5,
                floorY + 1,
                geometry.bodyCenterZ(),
                Material.FLETCHING_TABLE
        );
        place(
                queue,
                setBlock,
                centerX - 5,
                floorY + 1,
                geometry.bodyCenterZ() + 1,
                Material.TARGET
        );
        place(
                queue,
                setBlock,
                centerX + 5,
                floorY + 1,
                geometry.bodyCenterZ(),
                Material.CARTOGRAPHY_TABLE
        );
        place(
                queue,
                setBlock,
                centerX + 5,
                floorY + 1,
                geometry.bodyCenterZ() + 1,
                Material.BARREL
        );

        // Deux couchettes restent éloignées des accès de courtine.
        addBed(
                queue,
                world,
                setBlock,
                centerX - 6,
                floorY + 1,
                geometry.bodyNorthZ() + 4,
                BlockFace.SOUTH
        );
        addBed(
                queue,
                world,
                setBlock,
                centerX + 5,
                floorY + 1,
                geometry.bodyNorthZ() + 4,
                BlockFace.SOUTH
        );

        for (int x = centerX - 2; x <= centerX + 2; x++) {
            place(
                    queue,
                    setBlock,
                    x,
                    floorY + 1,
                    geometry.bodySouthZ() - 4,
                    Math.floorMod(x, 2) == 0
                            ? Material.RED_CARPET
                            : Material.WHITE_CARPET
            );
        }

        addHangingLight(
                queue,
                world,
                setBlock,
                centerX,
                baseY + UPPER_FLOOR_LEVEL,
                geometry.bodyCenterZ()
        );
    }

    /**
     * Chambre seigneuriale et petite bibliothèque au dernier étage.
     */
    private static void furnishLordFloor(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            Geometry geometry,
            int baseY) {
        int floorY = baseY + UPPER_FLOOR_LEVEL;
        int centerX = geometry.centerX();

        addBed(
                queue,
                world,
                setBlock,
                centerX - 6,
                floorY + 1,
                geometry.bodySouthZ() - 5,
                BlockFace.NORTH
        );
        place(
                queue,
                setBlock,
                centerX - 5,
                floorY + 1,
                geometry.bodySouthZ() - 8,
                Material.CHEST
        );
        queue.add(() -> VillageStyle.setDirectional(
                world,
                centerX - 5,
                floorY + 1,
                geometry.bodySouthZ() - 8,
                Material.CHEST,
                BlockFace.EAST
        ));

        int libraryX = centerX + 5;
        for (int z = geometry.bodyNorthZ() + 3;
             z <= geometry.bodyNorthZ() + 7;
             z++) {
            place(
                    queue,
                    setBlock,
                    libraryX,
                    floorY + 1,
                    z,
                    Material.BOOKSHELF
            );
            place(
                    queue,
                    setBlock,
                    libraryX,
                    floorY + 2,
                    z,
                    Material.BOOKSHELF
            );
        }
        place(
                queue,
                setBlock,
                centerX + 3,
                floorY + 1,
                geometry.bodyCenterZ(),
                Material.LECTERN
        );
        queue.add(() -> VillageStyle.setDirectional(
                world,
                centerX + 3,
                floorY + 1,
                geometry.bodyCenterZ(),
                Material.LECTERN,
                BlockFace.WEST
        ));
        place(
                queue,
                setBlock,
                centerX + 3,
                floorY + 1,
                geometry.bodyCenterZ() + 2,
                Material.BREWING_STAND
        );

        // Tapis héraldique laissant le couloir central libre.
        for (int x = centerX - 3; x <= centerX - 1; x++) {
            for (int z = geometry.bodyCenterZ() - 1;
                 z <= geometry.bodyCenterZ() + 1;
                 z++) {
                place(
                        queue,
                        setBlock,
                        x,
                        floorY + 1,
                        z,
                        Math.floorMod(x + z, 2) == 0
                                ? Material.RED_CARPET
                                : Material.YELLOW_CARPET
                );
            }
        }

        addHangingLight(
                queue,
                world,
                setBlock,
                centerX - 2,
                baseY + BODY_ROOF_LEVEL,
                geometry.bodyCenterZ()
        );
    }

    /**
     * Mâchicoulis, bandeaux de pierre, bannières et cheminée extérieure.
     */
    private static void addExteriorDetails(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            Geometry geometry,
            int baseY) {
        int centerX = geometry.centerX();

        // Corbeaux sous les parapets nord et sud du corps central.
        for (int x = centerX - 5; x <= centerX + 5; x += 2) {
            addMachicolation(
                    queue,
                    world,
                    setBlock,
                    x,
                    baseY + BODY_ROOF_LEVEL,
                    geometry.bodyNorthZ() - 1,
                    BlockFace.NORTH
            );
            if (x < centerX - 2 || x > centerX + 1) {
                addMachicolation(
                        queue,
                        world,
                        setBlock,
                        x,
                        baseY + BODY_ROOF_LEVEL,
                        geometry.bodySouthZ() + 1,
                        BlockFace.SOUTH
                );
            }
        }

        for (int z = geometry.bodyNorthZ() + 4;
             z <= geometry.bodySouthZ() - 4;
             z += 2) {
            addMachicolation(
                    queue,
                    world,
                    setBlock,
                    centerX - BODY_HALF_WIDTH - 1,
                    baseY + BODY_ROOF_LEVEL,
                    z,
                    BlockFace.WEST
            );
            addMachicolation(
                    queue,
                    world,
                    setBlock,
                    centerX + BODY_HALF_WIDTH + 1,
                    baseY + BODY_ROOF_LEVEL,
                    z,
                    BlockFace.EAST
            );
        }

        // Blasons de la façade tournée vers le village.
        for (int x : new int[]{centerX - 3, centerX + 2}) {
            place(
                    queue,
                    setBlock,
                    x,
                    baseY + 14,
                    geometry.bodySouthZ() + 1,
                    Material.RED_WALL_BANNER
            );
            queue.add(() -> VillageStyle.setDirectional(
                    world,
                    x,
                    baseY + 14,
                    geometry.bodySouthZ() + 1,
                    Material.RED_WALL_BANNER,
                    BlockFace.SOUTH
            ));
        }
        place(
                queue,
                setBlock,
                centerX,
                baseY + 20,
                geometry.bodySouthZ() + 1,
                Material.YELLOW_WALL_BANNER
        );
        queue.add(() -> VillageStyle.setDirectional(
                world,
                centerX,
                baseY + 20,
                geometry.bodySouthZ() + 1,
                Material.YELLOW_WALL_BANNER,
                BlockFace.SOUTH
        ));

        // Deux gargouilles sobres encadrent l'entrée.
        for (int x : new int[]{centerX - 4, centerX + 3}) {
            stair(
                    queue,
                    world,
                    setBlock,
                    x,
                    baseY + 17,
                    geometry.bodySouthZ() + 1,
                    Material.COBBLED_DEEPSLATE_STAIRS,
                    BlockFace.NORTH,
                    Stairs.Half.TOP,
                    Stairs.Shape.STRAIGHT
            );
        }
    }

    /**
     * Terrasse principale et quatre plateformes de tour, toutes protégées par
     * un parapet continu. Les murs bas occupent les créneaux : le dessin reste
     * crénelé sans laisser un joueur tomber accidentellement.
     */
    private static void buildBattlements(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            Geometry geometry,
            int baseY) {
        for (int x = geometry.centerX() - BODY_HALF_WIDTH;
             x <= geometry.centerX() + BODY_HALF_WIDTH;
             x++) {
            for (int z = geometry.bodyNorthZ();
                 z <= geometry.bodySouthZ();
                 z++) {
                if (!isBodyBoundary(geometry, x, z)
                        || isAnyTowerCell(geometry, x, z)) {
                    continue;
                }
                addSafeMerlon(
                        queue,
                        world,
                        setBlock,
                        x,
                        baseY + BODY_PARAPET_LEVEL,
                        z,
                        x + z
                );
            }
        }

        for (int towerX : new int[]{
                geometry.westTowerX(),
                geometry.eastTowerX()
        }) {
            for (int towerZ : new int[]{
                    geometry.northTowerZ(),
                    geometry.southTowerZ()
            }) {
                for (int x = towerX - TOWER_RADIUS;
                     x <= towerX + TOWER_RADIUS;
                     x++) {
                    for (int z = towerZ - TOWER_RADIUS;
                         z <= towerZ + TOWER_RADIUS;
                         z++) {
                        if (!isTowerCell(towerX, towerZ, x, z)
                                || !isTowerBoundary(
                                towerX,
                                towerZ,
                                x,
                                z
                        )) {
                            continue;
                        }
                        addSafeMerlon(
                                queue,
                                world,
                                setBlock,
                                x,
                                baseY + TOWER_PARAPET_LEVEL,
                                z,
                                x * 3 + z
                        );
                    }
                }
            }
        }

        // Mât central visible depuis le châtelet sud.
        int mastZ = geometry.bodyCenterZ();
        place(
                queue,
                setBlock,
                geometry.centerX(),
                baseY + BODY_PARAPET_LEVEL,
                mastZ,
                Material.CHISELED_STONE_BRICKS
        );
        for (int relativeY = BODY_PARAPET_LEVEL + 1;
             relativeY <= 30;
             relativeY++) {
            place(
                    queue,
                    setBlock,
                    geometry.centerX(),
                    baseY + relativeY,
                    mastZ,
                    Material.DARK_OAK_FENCE
            );
        }
        place(
                queue,
                setBlock,
                geometry.centerX(),
                baseY + MAX_RELATIVE_HEIGHT,
                mastZ,
                Material.LIGHTNING_ROD
        );

        // Deux pavillons fixés au mât, orientés vers le sud.
        for (int relativeY : new int[]{27, 29}) {
            place(
                    queue,
                    setBlock,
                    geometry.centerX(),
                    baseY + relativeY,
                    mastZ + 1,
                    relativeY == 29
                            ? Material.RED_WALL_BANNER
                            : Material.YELLOW_WALL_BANNER
            );
            Material banner = relativeY == 29
                    ? Material.RED_WALL_BANNER
                    : Material.YELLOW_WALL_BANNER;
            queue.add(() -> VillageStyle.setDirectional(
                    world,
                    geometry.centerX(),
                    baseY + relativeY,
                    mastZ + 1,
                    banner,
                    BlockFace.SOUTH
            ));
        }
    }

    private static void addSafeMerlon(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            int x,
            int y,
            int z,
            int selector) {
        boolean fullMerlon = Math.floorMod(selector, 4) <= 1;
        place(
                queue,
                setBlock,
                x,
                y,
                z,
                fullMerlon
                        ? Material.STONE_BRICKS
                        : Material.STONE_BRICK_WALL
        );
        if (fullMerlon) {
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
    }

    /**
     * Pavage du porche et bornes lumineuses intérieures.
     */
    private static void buildEntranceApproach(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            Geometry geometry,
            int baseY) {
        int minX = geometry.centerX() - 2;
        int maxX = geometry.centerX() + 1;
        int startZ = geometry.bodySouthZ() + 1;
        int endZ = geometry.bodySouthZ() + 5;

        for (int x = minX; x <= maxX; x++) {
            for (int z = startZ; z <= endZ; z++) {
                place(
                        queue,
                        setBlock,
                        x,
                        baseY - 1,
                        z,
                        Material.COBBLESTONE
                );
                place(
                        queue,
                        setBlock,
                        x,
                        baseY,
                        z,
                        Math.floorMod(x * 19 + z * 23, 6) == 0
                                ? Material.POLISHED_ANDESITE
                                : Material.STONE_BRICKS
                );
                for (int relativeY = 1;
                     relativeY <= ENTRANCE_CLEAR_HEIGHT;
                     relativeY++) {
                    place(
                            queue,
                            setBlock,
                            x,
                            baseY + relativeY,
                            z,
                            Material.AIR
                    );
                }
            }
        }

        for (int x : new int[]{minX - 1, maxX + 1}) {
            place(
                    queue,
                    setBlock,
                    x,
                    baseY,
                    endZ - 1,
                    Material.COBBLED_DEEPSLATE
            );
            place(
                    queue,
                    setBlock,
                    x,
                    baseY + 1,
                    endZ - 1,
                    Material.STONE_BRICK_WALL
            );
            place(
                    queue,
                    setBlock,
                    x,
                    baseY + 2,
                    endZ - 1,
                    Material.LANTERN
            );
        }

        /*
         * Deux marches décoratives soulignent le seuil sans changer d'altitude.
         * Elles sont placées sur les côtés et ne réduisent pas les quatre blocs
         * de largeur utiles.
         */
        stair(
                queue,
                world,
                setBlock,
                minX - 1,
                baseY,
                startZ,
                Material.STONE_BRICK_STAIRS,
                BlockFace.SOUTH,
                Stairs.Half.BOTTOM,
                Stairs.Shape.STRAIGHT
        );
        stair(
                queue,
                world,
                setBlock,
                maxX + 1,
                baseY,
                startZ,
                Material.STONE_BRICK_STAIRS,
                BlockFace.SOUTH,
                Stairs.Half.BOTTOM,
                Stairs.Shape.STRAIGHT
        );
    }

    /**
     * Lanternes intérieures, potences d'entrée et identité visuelle des tours.
     */
    private static void addLightingAndIdentity(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            Geometry geometry,
            int baseY) {
        int centerX = geometry.centerX();

        // Potences suspendues de part et d'autre de l'entrée.
        for (int x : new int[]{centerX - 3, centerX + 2}) {
            place(
                    queue,
                    setBlock,
                    x,
                    baseY + 7,
                    geometry.bodySouthZ() + 1,
                    Material.DARK_OAK_FENCE
            );
            place(
                    queue,
                    setBlock,
                    x,
                    baseY + 6,
                    geometry.bodySouthZ() + 1,
                    Material.CHAIN
            );
            place(
                    queue,
                    setBlock,
                    x,
                    baseY + 5,
                    geometry.bodySouthZ() + 1,
                    Material.LANTERN
            );
            configureHangingLantern(
                    queue,
                    world,
                    x,
                    baseY + 5,
                    geometry.bodySouthZ() + 1
            );
        }

        // Lanternes protégées sur la terrasse.
        for (int x : new int[]{centerX - 4, centerX + 4}) {
            int z = geometry.bodyCenterZ();
            place(
                    queue,
                    setBlock,
                    x,
                    baseY + BODY_PARAPET_LEVEL,
                    z,
                    Material.STONE_BRICK_WALL
            );
            place(
                    queue,
                    setBlock,
                    x,
                    baseY + BODY_PARAPET_LEVEL + 1,
                    z,
                    Material.LANTERN
            );
        }

        // Blasons extérieurs des quatre tours, tournés vers le paysage.
        addTowerBanner(
                queue,
                world,
                setBlock,
                geometry.westTowerX() - TOWER_RADIUS - 1,
                baseY + 17,
                geometry.northTowerZ(),
                BlockFace.WEST,
                Material.RED_WALL_BANNER
        );
        addTowerBanner(
                queue,
                world,
                setBlock,
                geometry.eastTowerX() + TOWER_RADIUS + 1,
                baseY + 17,
                geometry.northTowerZ(),
                BlockFace.EAST,
                Material.RED_WALL_BANNER
        );
        addTowerBanner(
                queue,
                world,
                setBlock,
                geometry.westTowerX(),
                baseY + 17,
                geometry.southTowerZ() + TOWER_RADIUS + 1,
                BlockFace.SOUTH,
                Material.YELLOW_WALL_BANNER
        );
        addTowerBanner(
                queue,
                world,
                setBlock,
                geometry.eastTowerX(),
                baseY + 17,
                geometry.southTowerZ() + TOWER_RADIUS + 1,
                BlockFace.SOUTH,
                Material.YELLOW_WALL_BANNER
        );
    }

    private static void addHangingLight(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            int x,
            int ceilingY,
            int z) {
        place(queue, setBlock, x, ceilingY - 1, z, Material.CHAIN);
        place(queue, setBlock, x, ceilingY - 2, z, Material.LANTERN);
        configureHangingLantern(queue, world, x, ceilingY - 2, z);
    }

    private static void addTowerBanner(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            int x,
            int y,
            int z,
            BlockFace facing,
            Material material) {
        place(queue, setBlock, x, y, z, material);
        queue.add(() -> VillageStyle.setDirectional(
                world,
                x,
                y,
                z,
                material,
                facing
        ));
    }

    private static void addBed(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            int footX,
            int y,
            int footZ,
            BlockFace facing) {
        int headX = footX + facing.getModX();
        int headZ = footZ + facing.getModZ();
        place(queue, setBlock, footX, y, footZ, Material.RED_BED);
        place(queue, setBlock, headX, y, headZ, Material.RED_BED);
        queue.add(() -> VillageStyle.setBed(
                world,
                footX,
                y,
                footZ,
                Material.RED_BED,
                facing,
                org.bukkit.block.data.type.Bed.Part.FOOT
        ));
        queue.add(() -> VillageStyle.setBed(
                world,
                headX,
                y,
                headZ,
                Material.RED_BED,
                facing,
                org.bukkit.block.data.type.Bed.Part.HEAD
        ));
    }

    private static void buildButtress(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            int x,
            int z,
            int baseY,
            BlockFace outward,
            int height,
            Material primary) {
        for (int depth = 0; depth <= 1; depth++) {
            int bx = x + outward.getModX() * depth;
            int bz = z + outward.getModZ() * depth;
            int topRelativeY = Math.max(3, height - depth * 4);

            for (int relativeY = -2;
                 relativeY <= topRelativeY;
                 relativeY++) {
                Material material = relativeY <= 0
                        ? foundationStone(bx, baseY + relativeY, bz)
                        : wallStone(
                                primary,
                                bx,
                                baseY + relativeY,
                                bz,
                                relativeY
                        );
                place(
                        queue,
                        setBlock,
                        bx,
                        baseY + relativeY,
                        bz,
                        material
                );
            }
            slab(
                    queue,
                    world,
                    setBlock,
                    bx,
                    baseY + topRelativeY + 1,
                    bz,
                    Material.STONE_BRICK_SLAB,
                    Slab.Type.BOTTOM
            );
        }
    }

    private static void addMachicolation(
            Queue<Runnable> queue,
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

    private static void doubleDoor(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            int leftX,
            int rightX,
            int y,
            int z,
            Material material,
            BlockFace facing) {
        place(queue, setBlock, leftX, y, z, material);
        place(queue, setBlock, leftX, y + 1, z, material);
        place(queue, setBlock, rightX, y, z, material);
        place(queue, setBlock, rightX, y + 1, z, material);

        queue.add(() -> configureDoor(
                world,
                leftX,
                y,
                z,
                material,
                facing,
                Door.Hinge.RIGHT,
                Bisected.Half.BOTTOM
        ));
        queue.add(() -> configureDoor(
                world,
                leftX,
                y + 1,
                z,
                material,
                facing,
                Door.Hinge.RIGHT,
                Bisected.Half.TOP
        ));
        queue.add(() -> configureDoor(
                world,
                rightX,
                y,
                z,
                material,
                facing,
                Door.Hinge.LEFT,
                Bisected.Half.BOTTOM
        ));
        queue.add(() -> configureDoor(
                world,
                rightX,
                y + 1,
                z,
                material,
                facing,
                Door.Hinge.LEFT,
                Bisected.Half.TOP
        ));
    }

    private static void configureDoor(World world,
                                      int x,
                                      int y,
                                      int z,
                                      Material material,
                                      BlockFace facing,
                                      Door.Hinge hinge,
                                      Bisected.Half half) {
        if (world == null) {
            return;
        }

        BlockData data = material.createBlockData();
        if (!(data instanceof Door door)) {
            return;
        }
        door.setFacing(facing);
        door.setHinge(hinge);
        door.setHalf(half);
        door.setOpen(false);
        door.setPowered(false);
        world.getBlockAt(x, y, z).setBlockData(door, false);
    }

    private static void configureHangingLantern(
            Queue<Runnable> queue,
            World world,
            int x,
            int y,
            int z) {
        queue.add(() -> {
            if (world == null) {
                return;
            }
            Block block = world.getBlockAt(x, y, z);
            if (block.getType() != Material.LANTERN
                    && block.getType() != Material.SOUL_LANTERN) {
                return;
            }

            BlockData data = block.getType().createBlockData();
            if (data instanceof Lantern lantern) {
                lantern.setHanging(true);
                block.setBlockData(lantern, false);
            }
        });
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

    private static void place(Queue<Runnable> queue,
                              TerrainManager.SetBlock setBlock,
                              int x,
                              int y,
                              int z,
                              Material material) {
        queue.add(() -> setBlock.set(x, y, z, material));
    }

    private static Material normalizePrimary(Material material) {
        return material == null || !material.isBlock()
                ? Material.STONE_BRICKS
                : material;
    }

    private static Material foundationStone(int x, int y, int z) {
        int selector = Math.floorMod(
                x * 19 + y * 11 + z * 23,
                9
        );
        return selector <= 2
                ? Material.DEEPSLATE_BRICKS
                : Material.COBBLED_DEEPSLATE;
    }

    private static Material groundFloorMaterial(boolean shell,
                                                int x,
                                                int z) {
        if (shell) {
            return Math.floorMod(x * 7 + z * 13, 6) == 0
                    ? Material.MOSSY_STONE_BRICKS
                    : Material.STONE_BRICKS;
        }
        return Math.floorMod(x * 17 + z * 31, 7) == 0
                ? Material.ANDESITE
                : Material.POLISHED_ANDESITE;
    }

    private static Material floorMaterial(int relativeY, int x, int z) {
        if (relativeY == BODY_ROOF_LEVEL) {
            return Math.floorMod(x * 13 + z * 17, 8) == 0
                    ? Material.MOSSY_STONE_BRICKS
                    : Material.POLISHED_ANDESITE;
        }
        if (relativeY == UPPER_FLOOR_LEVEL) {
            return Math.floorMod(x + z, 2) == 0
                    ? Material.SPRUCE_PLANKS
                    : Material.DARK_OAK_PLANKS;
        }
        return Math.floorMod(x * 5 + z * 3, 3) == 0
                ? Material.DARK_OAK_PLANKS
                : Material.SPRUCE_PLANKS;
    }

    private static Material towerDeckMaterial(int x, int z) {
        return Math.floorMod(x * 11 + z * 7, 7) == 0
                ? Material.MOSSY_STONE_BRICKS
                : Material.POLISHED_ANDESITE;
    }

    private static Material walkwayStone(int x, int z) {
        return Math.floorMod(x * 13 + z * 7, 9) == 0
                ? Material.MOSSY_STONE_BRICKS
                : Material.POLISHED_ANDESITE;
    }

    private static Material wallStone(Material primary,
                                      int x,
                                      int y,
                                      int z,
                                      int relativeY) {
        if (relativeY == 5
                || relativeY == 10
                || relativeY == 15
                || relativeY == 21
                || relativeY == 24) {
            return Material.POLISHED_ANDESITE;
        }

        int selector = Math.floorMod(
                x * 31 + y * 13 + z * 17,
                37
        );
        return switch (selector) {
            case 0, 9 -> Material.MOSSY_STONE_BRICKS;
            case 4 -> Material.CRACKED_STONE_BRICKS;
            case 13 -> Material.COBBLESTONE;
            case 21 -> Material.ANDESITE;
            case 29 -> Material.DEEPSLATE_BRICKS;
            default -> primary;
        };
    }

    private static boolean isEntranceClearance(Geometry geometry,
                                               int x,
                                               int z) {
        return x >= geometry.centerX() - 3
                && x <= geometry.centerX() + 2
                && z >= geometry.bodySouthZ() - 1;
    }

    private static boolean isWallWalkAxis(Geometry geometry,
                                          int x,
                                          int z) {
        int outerNorthZ = geometry.innerNorthZ()
                - WallBuilder.WALL_THICKNESS
                + 1;
        return z >= outerNorthZ
                && z <= geometry.innerNorthZ()
                && (x <= geometry.centerX() - BODY_HALF_WIDTH + 3
                || x >= geometry.centerX() + BODY_HALF_WIDTH - 3);
    }

    private static boolean touchesLowerFootprint(Geometry geometry,
                                                 int x,
                                                 int z) {
        for (int[] direction : CARDINAL_DIRECTIONS) {
            if (isLowerFootprint(
                    geometry,
                    x + direction[0],
                    z + direction[1]
            )) {
                return true;
            }
        }
        return false;
    }

    private static boolean isThickLowerShell(Geometry geometry,
                                             int x,
                                             int z) {
        if (!isLowerFootprint(geometry, x, z)) {
            return false;
        }
        if (isLowerBoundary(geometry, x, z)) {
            return true;
        }

        for (int[] direction : CARDINAL_DIRECTIONS) {
            int neighbourX = x + direction[0];
            int neighbourZ = z + direction[1];
            if (isLowerBoundary(geometry, neighbourX, neighbourZ)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLowerBoundary(Geometry geometry,
                                           int x,
                                           int z) {
        if (!isLowerFootprint(geometry, x, z)) {
            return false;
        }
        for (int[] direction : CARDINAL_DIRECTIONS) {
            if (!isLowerFootprint(
                    geometry,
                    x + direction[0],
                    z + direction[1]
            )) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUpperTowerBoundary(Geometry geometry,
                                                int x,
                                                int z) {
        for (int towerX : new int[]{
                geometry.westTowerX(),
                geometry.eastTowerX()
        }) {
            for (int towerZ : new int[]{
                    geometry.northTowerZ(),
                    geometry.southTowerZ()
            }) {
                if (isTowerCell(towerX, towerZ, x, z)) {
                    return isTowerBoundary(towerX, towerZ, x, z);
                }
            }
        }
        return false;
    }

    private static boolean isBodyBoundary(Geometry geometry,
                                          int x,
                                          int z) {
        if (!isBodyCell(geometry, x, z)) {
            return false;
        }
        return x == geometry.centerX() - BODY_HALF_WIDTH
                || x == geometry.centerX() + BODY_HALF_WIDTH
                || z == geometry.bodyNorthZ()
                || z == geometry.bodySouthZ();
    }

    private static boolean isLowerFootprint(Geometry geometry,
                                            int x,
                                            int z) {
        return isBodyCell(geometry, x, z)
                || isAnyTowerCell(geometry, x, z);
    }

    private static boolean isBodyCell(Geometry geometry,
                                      int x,
                                      int z) {
        return x >= geometry.centerX() - BODY_HALF_WIDTH
                && x <= geometry.centerX() + BODY_HALF_WIDTH
                && z >= geometry.bodyNorthZ()
                && z <= geometry.bodySouthZ();
    }

    private static boolean isAnyTowerCell(Geometry geometry,
                                          int x,
                                          int z) {
        for (int towerX : new int[]{
                geometry.westTowerX(),
                geometry.eastTowerX()
        }) {
            for (int towerZ : new int[]{
                    geometry.northTowerZ(),
                    geometry.southTowerZ()
            }) {
                if (isTowerCell(towerX, towerZ, x, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Tour carrée à angles abattus. Seules les quatre cellules extrêmes des
     * coins sont retirées, ce qui conserve un intérieur jouable de 5 x 5.
     */
    private static boolean isTowerCell(int towerX,
                                       int towerZ,
                                       int x,
                                       int z) {
        int dx = Math.abs(x - towerX);
        int dz = Math.abs(z - towerZ);
        return dx <= TOWER_RADIUS
                && dz <= TOWER_RADIUS
                && dx + dz <= TOWER_RADIUS * 2 - 1;
    }

    private static boolean isTowerBoundary(int towerX,
                                           int towerZ,
                                           int x,
                                           int z) {
        if (!isTowerCell(towerX, towerZ, x, z)) {
            return false;
        }
        for (int[] direction : CARDINAL_DIRECTIONS) {
            if (!isTowerCell(
                    towerX,
                    towerZ,
                    x + direction[0],
                    z + direction[1]
            )) {
                return true;
            }
        }
        return false;
    }

    /**
     * Toutes les coordonnées utiles sont dérivées d'un seul objet immuable.
     * Cela évite les divergences entre construction, éclairage et terrassement.
     */
    private record Geometry(int centerX,
                            int centerZ,
                            int innerNorthZ,
                            int bodyNorthZ,
                            int bodySouthZ,
                            int bodyCenterZ,
                            int westTowerX,
                            int eastTowerX,
                            int northTowerZ,
                            int southTowerZ,
                            int footprintMinX,
                            int footprintMaxX,
                            int footprintMinZ,
                            int footprintMaxZ) {

        private static Geometry from(Location center, int rz) {
            return from(
                    center.getBlockX(),
                    center.getBlockZ(),
                    rz
            );
        }

        private static Geometry from(int centerX, int centerZ, int rz) {
            int safeRz = Math.max(4, rz);
            int innerNorthZ = centerZ - safeRz;
            int bodySouthZ = innerNorthZ + 1;
            int bodyNorthZ = bodySouthZ - BODY_DEPTH + 1;
            int bodyCenterZ = (bodyNorthZ + bodySouthZ) / 2;
            int westTowerX = centerX - BODY_HALF_WIDTH;
            int eastTowerX = centerX + BODY_HALF_WIDTH;
            int northTowerZ = bodyNorthZ;
            int southTowerZ = bodySouthZ;

            return new Geometry(
                    centerX,
                    centerZ,
                    innerNorthZ,
                    bodyNorthZ,
                    bodySouthZ,
                    bodyCenterZ,
                    westTowerX,
                    eastTowerX,
                    northTowerZ,
                    southTowerZ,
                    westTowerX - TOWER_RADIUS,
                    eastTowerX + TOWER_RADIUS,
                    northTowerZ - TOWER_RADIUS,
                    southTowerZ + TOWER_RADIUS
            );
        }
    }
}
