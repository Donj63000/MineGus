package org.example.village;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Lectern;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.example.Village;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * Construit la salle des coffres souterraine du donjon de {@code /village}.
 *
 * <p>La coque extérieure possède deux blocs d'obsidienne. Le quartz, la
 * diorite polie, les tapis et l'or sont uniquement un parement intérieur :
 * casser un élément décoratif ne met donc jamais directement le terrain à nu.</p>
 */
public final class TreasuryBuilder {

    private static final int BODY_DEPTH = 15;

    private static final int OUTER_HALF_WIDTH = 20;
    private static final int OUTER_HALF_DEPTH = 16;
    private static final int OUTER_FLOOR_OFFSET = -20;
    private static final int OUTER_CEILING_OFFSET = -5;
    private static final int SHELL_THICKNESS = 2;

    private static final int STAIR_STEPS = 18;
    private static final int STAIR_WIDTH = 2;
    private static final int ACCESS_TOP_OFFSET = 4;

    private TreasuryBuilder() {
    }

    /**
     * Profondeur maximale écrite sous le niveau principal du village.
     */
    public static int foundationDepth() {
        return -OUTER_FLOOR_OFFSET;
    }

    /**
     * Vérifie que la chambre forte tient dans les limites verticales du monde.
     */
    public static boolean fitsVertically(World world, int baseY) {
        return world != null
                && baseY + OUTER_FLOOR_OFFSET >= world.getMinHeight()
                && baseY + ACCESS_TOP_OFFSET < world.getMaxHeight();
    }

    /**
     * Prépare l'intégralité de la salle et renvoie son plan persistant.
     *
     * <p>Toutes les écritures de blocs passent par le callback transactionnel
     * fourni par {@link Village}. Les configurations de coffres, portes,
     * escaliers et pupitre sont ajoutées après leur matériau : l'état original
     * du bloc a donc déjà été mémorisé pour {@code /village undo}.</p>
     */
    public static Plan build(Location villageCenter,
                             int radiusZ,
                             int baseY,
                             Queue<Runnable> queue,
                             TerrainManager.SetBlock setBlock) {
        Objects.requireNonNull(villageCenter, "villageCenter");
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(setBlock, "setBlock");

        World world = Objects.requireNonNull(
                villageCenter.getWorld(),
                "Le monde de la salle des coffres est obligatoire."
        );
        if (!fitsVertically(world, baseY)) {
            return null;
        }

        Geometry geometry = Geometry.from(
                world,
                villageCenter.getBlockX(),
                villageCenter.getBlockZ(),
                radiusZ,
                baseY
        );

        buildArmoredRoom(queue, setBlock, geometry);
        decorateRoom(queue, setBlock, geometry);
        buildAntechamber(queue, setBlock, geometry);
        buildStaircase(queue, world, setBlock, geometry);
        buildVaultDoor(queue, world, setBlock, geometry);

        List<VillageTreasury.BlockPoint> containers =
                buildChestGalleries(queue, world, setBlock, geometry);
        VillageTreasury.BlockPoint ledger =
                buildCentralLedger(queue, setBlock, geometry);
        decorateCarpetsAndLighting(queue, setBlock, geometry, ledger);

        List<Location> guardAnchors = guardAnchors(world, geometry);
        List<VillageTreasury.BlockPoint> doors = List.of(
                new VillageTreasury.BlockPoint(
                        geometry.centerX() - 1,
                        geometry.interiorFloorY() + 1,
                        geometry.innerSouthZ()
                ),
                new VillageTreasury.BlockPoint(
                        geometry.centerX(),
                        geometry.interiorFloorY() + 1,
                        geometry.innerSouthZ()
                )
        );

        VillageTreasury.Cuboid armoredRoom =
                VillageTreasury.Cuboid.normalized(
                        geometry.outerMinX(),
                        geometry.outerMaxX(),
                        geometry.outerFloorY(),
                        geometry.outerCeilingY(),
                        geometry.outerMinZ(),
                        geometry.outerMaxZ()
                );
        VillageTreasury.Cuboid access =
                VillageTreasury.Cuboid.normalized(
                        geometry.centerX() + 3,
                        geometry.centerX() + 8,
                        geometry.outerFloorY(),
                        baseY + ACCESS_TOP_OFFSET,
                        geometry.stairStartZ() - 1,
                        geometry.stairEndZ() + 2
                );
        VillageTreasury.Cuboid antechamber =
                VillageTreasury.Cuboid.normalized(
                        geometry.centerX() - 8,
                        geometry.centerX() + 8,
                        geometry.outerFloorY(),
                        geometry.interiorFloorY() + 8,
                        geometry.outerMaxZ() + 1,
                        geometry.stairEndZ() + 2
                );
        VillageTreasury.Cuboid roomBounds =
                VillageTreasury.Cuboid.normalized(
                        geometry.innerMinX() + 1,
                        geometry.innerMaxX() - 1,
                        geometry.interiorFloorY() + 1,
                        geometry.interiorCeilingY() - 1,
                        geometry.innerMinZ() + 1,
                        geometry.innerMaxZ() - 1
                );

        return new Plan(
                roomBounds,
                List.of(armoredRoom, access, antechamber),
                containers,
                ledger,
                doors,
                guardAnchors,
                new Location(
                        world,
                        geometry.centerX() + 0.5D,
                        geometry.interiorFloorY() + 1.0D,
                        geometry.roomCenterZ() + 0.5D
                )
        );
    }

    private static void buildArmoredRoom(
            Queue<Runnable> queue,
            TerrainManager.SetBlock setBlock,
            Geometry geometry) {
        for (int x = geometry.outerMinX();
             x <= geometry.outerMaxX();
             x++) {
            for (int z = geometry.outerMinZ();
                 z <= geometry.outerMaxZ();
                 z++) {
                int columnX = x;
                int columnZ = z;
                queue.add(() -> {
                    for (int y = geometry.outerFloorY();
                         y <= geometry.outerCeilingY();
                         y++) {
                        boolean shell =
                                columnX < geometry.outerMinX()
                                        + SHELL_THICKNESS
                                || columnX > geometry.outerMaxX()
                                        - SHELL_THICKNESS
                                || columnZ < geometry.outerMinZ()
                                        + SHELL_THICKNESS
                                || columnZ > geometry.outerMaxZ()
                                        - SHELL_THICKNESS
                                || y < geometry.outerFloorY()
                                        + SHELL_THICKNESS
                                || y > geometry.outerCeilingY()
                                        - SHELL_THICKNESS;
                        setBlock.set(
                                columnX,
                                y,
                                columnZ,
                                shell ? Material.OBSIDIAN : Material.AIR
                        );
                    }
                });
            }
        }
    }

    private static void decorateRoom(
            Queue<Runnable> queue,
            TerrainManager.SetBlock setBlock,
            Geometry geometry) {
        for (int x = geometry.innerMinX();
             x <= geometry.innerMaxX();
             x++) {
            for (int z = geometry.innerMinZ();
                 z <= geometry.innerMaxZ();
                 z++) {
                int tileX = x;
                int tileZ = z;
                queue.add(() -> {
                    int selector = Math.floorMod(
                            tileX * 31 + tileZ * 17,
                            8
                    );
                    Material floor = selector == 0
                            ? Material.GOLD_BLOCK
                            : ((tileX + tileZ) & 1) == 0
                            ? Material.SMOOTH_QUARTZ
                            : Material.POLISHED_DIORITE;
                    setBlock.set(
                            tileX,
                            geometry.interiorFloorY(),
                            tileZ,
                            floor
                    );

                    boolean light = Math.floorMod(
                            tileX - geometry.centerX(),
                            8
                    ) == 0
                            && Math.floorMod(
                            tileZ - geometry.roomCenterZ(),
                            8
                    ) == 0;
                    boolean goldGrid =
                            Math.floorMod(
                                    tileX - geometry.centerX(),
                                    8
                            ) == 0
                                    || Math.floorMod(
                                    tileZ - geometry.roomCenterZ(),
                                    8
                            ) == 0;
                    setBlock.set(
                            tileX,
                            geometry.interiorCeilingY(),
                            tileZ,
                            light
                                    ? Material.SEA_LANTERN
                                    : goldGrid
                                    ? Material.GOLD_BLOCK
                                    : Material.SMOOTH_QUARTZ
                    );
                });
            }
        }

        for (int x = geometry.innerMinX();
             x <= geometry.innerMaxX();
             x++) {
            buildDecoratedWallColumn(
                    queue,
                    setBlock,
                    geometry,
                    x,
                    geometry.innerMinZ(),
                    Math.floorMod(x - geometry.centerX(), 4) == 0
            );
            buildDecoratedWallColumn(
                    queue,
                    setBlock,
                    geometry,
                    x,
                    geometry.innerMaxZ(),
                    Math.floorMod(x - geometry.centerX(), 4) == 0
            );
        }
        for (int z = geometry.innerMinZ() + 1;
             z < geometry.innerMaxZ();
             z++) {
            buildDecoratedWallColumn(
                    queue,
                    setBlock,
                    geometry,
                    geometry.innerMinX(),
                    z,
                    Math.floorMod(z - geometry.roomCenterZ(), 4) == 0
            );
            buildDecoratedWallColumn(
                    queue,
                    setBlock,
                    geometry,
                    geometry.innerMaxX(),
                    z,
                    Math.floorMod(z - geometry.roomCenterZ(), 4) == 0
            );
        }
    }

    private static void buildDecoratedWallColumn(
            Queue<Runnable> queue,
            TerrainManager.SetBlock setBlock,
            Geometry geometry,
            int x,
            int z,
            boolean pillar) {
        queue.add(() -> {
            for (int y = geometry.interiorFloorY() + 1;
                 y < geometry.interiorCeilingY();
                 y++) {
                Material material;
                if (y == geometry.interiorFloorY() + 5) {
                    material = Material.GOLD_BLOCK;
                } else if (pillar) {
                    material = Material.QUARTZ_PILLAR;
                } else if (Math.floorMod(x * 13 + y * 7 + z, 11) == 0) {
                    material = Material.CHISELED_QUARTZ_BLOCK;
                } else {
                    material = Material.SMOOTH_QUARTZ;
                }
                setBlock.set(x, y, z, material);
            }
        });
    }

    private static void buildAntechamber(
            Queue<Runnable> queue,
            TerrainManager.SetBlock setBlock,
            Geometry geometry) {
        int minX = geometry.centerX() - 8;
        int maxX = geometry.centerX() + 8;
        int minZ = geometry.outerMaxZ() + 1;
        int maxZ = geometry.stairEndZ() + 2;
        int minY = geometry.outerFloorY();
        int maxY = geometry.interiorFloorY() + 8;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int columnX = x;
                int columnZ = z;
                queue.add(() -> {
                    for (int y = minY; y <= maxY; y++) {
                        boolean outerShell = columnX == minX
                                || columnX == maxX
                                || columnZ == maxZ
                                || y <= minY + 1
                                || y == maxY;
                        boolean innerWall = columnX == minX + 1
                                || columnX == maxX - 1
                                || columnZ == maxZ - 1;
                        Material material;
                        if (outerShell) {
                            material = Material.OBSIDIAN;
                        } else if (y == geometry.interiorFloorY()) {
                            material = ((columnX + columnZ) & 1) == 0
                                    ? Material.POLISHED_DIORITE
                                    : Material.SMOOTH_QUARTZ;
                        } else if (y == maxY - 1) {
                            material = Math.floorMod(columnX, 5) == 0
                                    ? Material.GOLD_BLOCK
                                    : Material.SMOOTH_QUARTZ;
                        } else if (innerWall) {
                            material = y == geometry.interiorFloorY() + 4
                                    ? Material.GOLD_BLOCK
                                    : Material.SMOOTH_QUARTZ;
                        } else {
                            material = Material.AIR;
                        }
                        setBlock.set(columnX, y, columnZ, material);
                    }
                });
            }
        }
    }

    private static void buildStaircase(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            Geometry geometry) {
        int stairMinX = geometry.centerX() + 5;
        int stairMaxX = stairMinX + STAIR_WIDTH - 1;

        for (int step = 0; step < STAIR_STEPS; step++) {
            int stairZ = geometry.stairStartZ() + step;
            int stairY = geometry.baseY() - step;
            int currentStep = step;

            queue.add(() -> {
                /*
                 * Deux couches extérieures et un parement intérieur forment un
                 * tunnel continu. Le profil suit la pente pour ne jamais
                 * conserver de poche naturelle ouverte sur l'escalier.
                 */
                for (int x = stairMinX - 2;
                     x <= stairMaxX + 2;
                     x++) {
                    setBlock.set(
                            x,
                            stairY - 1,
                            stairZ,
                            Material.OBSIDIAN
                    );
                    setBlock.set(
                            x,
                            stairY + 4,
                            stairZ,
                            Material.OBSIDIAN
                    );
                }
                for (int y = stairY; y <= stairY + 4; y++) {
                    setBlock.set(
                            stairMinX - 2,
                            y,
                            stairZ,
                            Material.OBSIDIAN
                    );
                    setBlock.set(
                            stairMaxX + 2,
                            y,
                            stairZ,
                            Material.OBSIDIAN
                    );
                }
                for (int y = stairY; y <= stairY + 3; y++) {
                    Material wall = currentStep % 5 == 2
                            && y == stairY + 2
                            ? Material.SEA_LANTERN
                            : y == stairY + 3
                            ? Material.GOLD_BLOCK
                            : Material.SMOOTH_QUARTZ;
                    setBlock.set(stairMinX - 1, y, stairZ, wall);
                    setBlock.set(stairMaxX + 1, y, stairZ, wall);
                }
                for (int x = stairMinX; x <= stairMaxX; x++) {
                    setBlock.set(
                            x,
                            stairY + 3,
                            stairZ,
                            currentStep % 6 == 0
                                    ? Material.GOLD_BLOCK
                                    : Material.SMOOTH_QUARTZ
                    );
                    setBlock.set(x, stairY + 1, stairZ, Material.AIR);
                    setBlock.set(x, stairY + 2, stairZ, Material.AIR);
                    setBlock.set(
                            x,
                            stairY,
                            stairZ,
                            Material.QUARTZ_STAIRS
                    );
                }
            });

            queue.add(() -> {
                for (int x = stairMinX; x <= stairMaxX; x++) {
                    configureStair(
                            world,
                            x,
                            stairY,
                            stairZ,
                            BlockFace.NORTH
                    );
                }
            });
        }

        /*
         * Arche visible au rez-de-chaussée : elle identifie l'accès royal sans
         * ajouter de porte susceptible de bloquer le pathfinding de l'escalier.
         */
        int archZ = geometry.stairStartZ() - 1;
        for (int x = stairMinX - 1; x <= stairMaxX + 1; x++) {
            int archX = x;
            queue.add(() -> setBlock.set(
                    archX,
                    geometry.baseY() + 3,
                    archZ,
                    archX == stairMinX - 1
                            || archX == stairMaxX + 1
                            ? Material.GOLD_BLOCK
                            : Material.CHISELED_QUARTZ_BLOCK
            ));
        }
        for (int y = geometry.baseY() + 1;
             y <= geometry.baseY() + 2;
             y++) {
            int archY = y;
            queue.add(() -> {
                setBlock.set(
                        stairMinX - 1,
                        archY,
                        archZ,
                        Material.QUARTZ_PILLAR
                );
                setBlock.set(
                        stairMaxX + 1,
                        archY,
                        archZ,
                        Material.QUARTZ_PILLAR
                );
            });
        }
    }

    private static void configureStair(World world,
                                       int x,
                                       int y,
                                       int z,
                                       BlockFace facing) {
        Block block = world.getBlockAt(x, y, z);
        if (!(block.getBlockData() instanceof Stairs stairs)) {
            return;
        }
        stairs.setFacing(facing);
        stairs.setHalf(Stairs.Half.BOTTOM);
        stairs.setShape(Stairs.Shape.STRAIGHT);
        block.setBlockData(stairs, false);
    }

    private static void buildVaultDoor(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            Geometry geometry) {
        int minPassageX = geometry.centerX() - 2;
        int maxPassageX = geometry.centerX() + 1;
        int lowerY = geometry.interiorFloorY() + 1;

        for (int x = minPassageX; x <= maxPassageX; x++) {
            for (int z = geometry.innerSouthZ();
                 z <= geometry.outerMaxZ() + 1;
                 z++) {
                for (int y = lowerY; y <= lowerY + 4; y++) {
                    int carveX = x;
                    int carveY = y;
                    int carveZ = z;
                    queue.add(() -> setBlock.set(
                            carveX,
                            carveY,
                            carveZ,
                            Material.AIR
                    ));
                }
            }
        }

        for (int z = geometry.innerSouthZ();
             z <= geometry.outerMaxZ() + 1;
             z++) {
            int frameZ = z;
            queue.add(() -> {
                for (int y = lowerY; y <= lowerY + 4; y++) {
                    setBlock.set(
                            minPassageX - 1,
                            y,
                            frameZ,
                            y == lowerY + 3
                                    ? Material.GOLD_BLOCK
                                    : Material.OBSIDIAN
                    );
                    setBlock.set(
                            maxPassageX + 1,
                            y,
                            frameZ,
                            y == lowerY + 3
                                    ? Material.GOLD_BLOCK
                                    : Material.OBSIDIAN
                    );
                }
                for (int x = minPassageX - 1;
                     x <= maxPassageX + 1;
                     x++) {
                    setBlock.set(
                            x,
                            lowerY + 4,
                            frameZ,
                            Material.OBSIDIAN
                    );
                }
            });
        }

        int doorZ = geometry.innerSouthZ();
        placeDoor(
                queue,
                world,
                setBlock,
                geometry.centerX() - 1,
                lowerY,
                doorZ,
                Door.Hinge.LEFT
        );
        placeDoor(
                queue,
                world,
                setBlock,
                geometry.centerX(),
                lowerY,
                doorZ,
                Door.Hinge.RIGHT
        );

        queue.add(() -> {
            for (int x = geometry.centerX() - 2;
                 x <= geometry.centerX() + 1;
                 x++) {
                setBlock.set(
                        x,
                        lowerY + 3,
                        doorZ,
                        Material.GOLD_BLOCK
                );
            }
        });
    }

    private static void placeDoor(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            int x,
            int lowerY,
            int z,
            Door.Hinge hinge) {
        queue.add(() -> {
            setBlock.set(x, lowerY, z, Material.IRON_DOOR);
            setBlock.set(x, lowerY + 1, z, Material.IRON_DOOR);
        });
        queue.add(() -> {
            configureDoorHalf(
                    world,
                    x,
                    lowerY,
                    z,
                    Bisected.Half.BOTTOM,
                    hinge
            );
            configureDoorHalf(
                    world,
                    x,
                    lowerY + 1,
                    z,
                    Bisected.Half.TOP,
                    hinge
            );
        });
    }

    private static void configureDoorHalf(
            World world,
            int x,
            int y,
            int z,
            Bisected.Half half,
            Door.Hinge hinge) {
        Block block = world.getBlockAt(x, y, z);
        if (!(block.getBlockData() instanceof Door door)) {
            return;
        }
        door.setFacing(BlockFace.SOUTH);
        door.setHalf(half);
        door.setHinge(hinge);
        door.setOpen(false);
        door.setPowered(false);
        block.setBlockData(door, false);
    }

    private static List<VillageTreasury.BlockPoint> buildChestGalleries(
            Queue<Runnable> queue,
            World world,
            TerrainManager.SetBlock setBlock,
            Geometry geometry) {
        List<ChestPlacement> placements = new ArrayList<>();
        int chestY = geometry.interiorFloorY() + 1;

        int[] rowXOffsets = {-14, -10, -6, 6, 10, 14};
        int[] rowZOffsets = {-10, -6, -2, 2, 6, 10};
        for (int xOffset : rowXOffsets) {
            for (int zOffset : rowZOffsets) {
                placements.add(new ChestPlacement(
                        geometry.centerX() + xOffset,
                        chestY,
                        geometry.roomCenterZ() + zOffset,
                        xOffset < 0 ? BlockFace.EAST : BlockFace.WEST
                ));
            }
        }

        int[] sideZOffsets = {-10, -6, -2, 2, 6, 10};
        for (int zOffset : sideZOffsets) {
            placements.add(new ChestPlacement(
                    geometry.centerX() - 17,
                    chestY,
                    geometry.roomCenterZ() + zOffset,
                    BlockFace.EAST
            ));
            placements.add(new ChestPlacement(
                    geometry.centerX() + 17,
                    chestY,
                    geometry.roomCenterZ() + zOffset,
                    BlockFace.WEST
            ));
        }

        int[] endXOffsets = {-14, -10, -6, 6, 10, 14};
        for (int xOffset : endXOffsets) {
            placements.add(new ChestPlacement(
                    geometry.centerX() + xOffset,
                    chestY,
                    geometry.roomCenterZ() - 13,
                    BlockFace.SOUTH
            ));
            placements.add(new ChestPlacement(
                    geometry.centerX() + xOffset,
                    chestY,
                    geometry.roomCenterZ() + 13,
                    BlockFace.NORTH
            ));
        }

        Set<VillageTreasury.BlockPoint> points = new LinkedHashSet<>();
        for (ChestPlacement placement : placements) {
            VillageTreasury.BlockPoint point =
                    new VillageTreasury.BlockPoint(
                            placement.x(),
                            placement.y(),
                            placement.z()
                    );
            if (!points.add(point)) {
                continue;
            }
            queue.add(() -> {
                setBlock.set(
                        placement.x(),
                        placement.y() - 1,
                        placement.z(),
                        Material.GOLD_BLOCK
                );
                setBlock.set(
                        placement.x(),
                        placement.y(),
                        placement.z(),
                        Material.CHEST
                );
                setBlock.set(
                        placement.x(),
                        placement.y() + 1,
                        placement.z(),
                        Material.AIR
                );
            });
            queue.add(() -> configureChest(
                    world,
                    placement.x(),
                    placement.y(),
                    placement.z(),
                    placement.facing()
            ));
        }
        return List.copyOf(points);
    }

    private static void configureChest(World world,
                                       int x,
                                       int y,
                                       int z,
                                       BlockFace facing) {
        Block block = world.getBlockAt(x, y, z);
        if (!(block.getBlockData() instanceof Chest chest)) {
            return;
        }
        chest.setFacing(facing);
        chest.setType(Chest.Type.SINGLE);
        block.setBlockData(chest, false);
    }

    private static VillageTreasury.BlockPoint buildCentralLedger(
            Queue<Runnable> queue,
            TerrainManager.SetBlock setBlock,
            Geometry geometry) {
        VillageTreasury.BlockPoint point =
                new VillageTreasury.BlockPoint(
                        geometry.centerX(),
                        geometry.interiorFloorY() + 1,
                        geometry.roomCenterZ()
                );
        queue.add(() -> {
            setBlock.set(
                    point.x(),
                    point.y() - 1,
                    point.z(),
                    Material.GOLD_BLOCK
            );
            setBlock.set(
                    point.x(),
                    point.y(),
                    point.z(),
                    Material.LECTERN
            );
        });
        queue.add(() -> configureLedger(
                geometry,
                point
        ));
        return point;
    }

    private static void configureLedger(
            Geometry geometry,
            VillageTreasury.BlockPoint point) {
        World world = geometry.world();
        Block block = world.getBlockAt(point.x(), point.y(), point.z());
        if (!(block.getState() instanceof Lectern lectern)) {
            return;
        }

        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        if (book.getItemMeta() instanceof BookMeta meta) {
            meta.setTitle("Grand registre des impôts");
            meta.setAuthor("Couronne de MineGus");
            meta.setGeneration(BookMeta.Generation.ORIGINAL);
            meta.setPages(
                    ChatColor.GOLD + "" + ChatColor.BOLD
                            + "SALLE DES COFFRES\n\n"
                            + ChatColor.BLACK
                            + "Cette chambre forte appartient au roi qui a "
                            + "fondé la ville avec /village.",
                    ChatColor.DARK_RED + "" + ChatColor.BOLD
                            + "IMPÔT ROYAL\n\n"
                            + ChatColor.BLACK
                            + "Une ressource entièrement aléatoire est "
                            + "collectée automatiquement à des intervalles "
                            + "aléatoires configurés par le serveur.",
                    ChatColor.DARK_GREEN + "" + ChatColor.BOLD
                            + "TRÉSOR\n\n"
                            + ChatColor.BLACK
                            + "Les coffres se remplissent lentement. Blocs, "
                            + "outils, potions, œufs et même spawners peuvent "
                            + "rejoindre les réserves.",
                    ChatColor.DARK_BLUE + "" + ChatColor.BOLD
                            + "GARDE ROYALE\n\n"
                            + ChatColor.BLACK
                            + "Quatre sentinelles tiennent leur poste. Elles "
                            + "ne quittent la salle que pour repousser une "
                            + "menace immédiate."
            );
            book.setItemMeta(meta);
        }
        lectern.getSnapshotInventory().setItem(0, book);
        lectern.update(true, false);
    }

    private static void decorateCarpetsAndLighting(
            Queue<Runnable> queue,
            TerrainManager.SetBlock setBlock,
            Geometry geometry,
            VillageTreasury.BlockPoint ledger) {
        int carpetY = geometry.interiorFloorY() + 1;

        for (int z = geometry.roomCenterZ() - 11;
             z <= geometry.roomCenterZ() + 11;
             z++) {
            for (int x = geometry.centerX() - 2;
                 x <= geometry.centerX() + 2;
                 x++) {
                if (x == ledger.x() && z == ledger.z()) {
                    continue;
                }
                int carpetX = x;
                int carpetZ = z;
                queue.add(() -> setBlock.set(
                        carpetX,
                        carpetY,
                        carpetZ,
                        Math.abs(carpetX - geometry.centerX()) == 2
                                ? Material.YELLOW_CARPET
                                : Material.RED_CARPET
                ));
            }
        }

        for (int x = geometry.centerX() - 15;
             x <= geometry.centerX() + 15;
             x++) {
            if (Math.abs(x - geometry.centerX()) <= 2) {
                continue;
            }
            int carpetX = x;
            queue.add(() -> setBlock.set(
                    carpetX,
                    carpetY,
                    geometry.roomCenterZ(),
                    Math.floorMod(carpetX - geometry.centerX(), 4) == 0
                            ? Material.YELLOW_CARPET
                            : Material.RED_CARPET
            ));
        }

        int[][] lightOffsets = {
                {-4, -8},
                {4, -8},
                {-4, 8},
                {4, 8}
        };
        for (int[] offset : lightOffsets) {
            int x = geometry.centerX() + offset[0];
            int z = geometry.roomCenterZ() + offset[1];
            queue.add(() -> {
                setBlock.set(
                        x,
                        geometry.interiorCeilingY() - 2,
                        z,
                        Material.CHAIN
                );
                setBlock.set(
                        x,
                        geometry.interiorCeilingY() - 3,
                        z,
                        Material.LANTERN
                );
            });
        }
    }

    private static List<Location> guardAnchors(
            World world,
            Geometry geometry) {
        double y = geometry.interiorFloorY() + 1.0D;
        return List.of(
                new Location(
                        world,
                        geometry.centerX() - 3 + 0.5D,
                        y,
                        geometry.roomCenterZ() + 10 + 0.5D
                ),
                new Location(
                        world,
                        geometry.centerX() + 3 + 0.5D,
                        y,
                        geometry.roomCenterZ() + 10 + 0.5D
                ),
                new Location(
                        world,
                        geometry.centerX() - 3 + 0.5D,
                        y,
                        geometry.roomCenterZ() - 6 + 0.5D
                ),
                new Location(
                        world,
                        geometry.centerX() + 3 + 0.5D,
                        y,
                        geometry.roomCenterZ() - 6 + 0.5D
                )
        );
    }

    public record Plan(
            VillageTreasury.Cuboid roomBounds,
            List<VillageTreasury.Cuboid> protectedZones,
            List<VillageTreasury.BlockPoint> containers,
            VillageTreasury.BlockPoint ledger,
            List<VillageTreasury.BlockPoint> doors,
            List<Location> guardAnchors,
            Location roomAnchor) {

        public Plan {
            Objects.requireNonNull(roomBounds, "roomBounds");
            protectedZones = List.copyOf(
                    Objects.requireNonNull(
                            protectedZones,
                            "protectedZones"
                    )
            );
            containers = List.copyOf(
                    Objects.requireNonNull(containers, "containers")
            );
            Objects.requireNonNull(ledger, "ledger");
            doors = List.copyOf(
                    Objects.requireNonNull(doors, "doors")
            );
            if (containers.size()
                    != VillageTreasury.REQUIRED_CONTAINER_COUNT
                    || doors.size()
                    != VillageTreasury.REQUIRED_DOOR_COUNT) {
                throw new IllegalArgumentException(
                        "Plan de chambre forte incomplet."
                );
            }

            List<Location> clonedAnchors = new ArrayList<>();
            for (Location anchor : Objects.requireNonNull(
                    guardAnchors,
                    "guardAnchors"
            )) {
                if (anchor != null) {
                    clonedAnchors.add(anchor.clone());
                }
            }
            if (clonedAnchors.size()
                    != VillageTreasury.REQUIRED_GUARD_COUNT) {
                throw new IllegalArgumentException(
                        "La chambre forte exige quatre postes de garde."
                );
            }
            guardAnchors = List.copyOf(clonedAnchors);
            roomAnchor = Objects.requireNonNull(
                    roomAnchor,
                    "roomAnchor"
            ).clone();
        }
    }

    private record ChestPlacement(
            int x,
            int y,
            int z,
            BlockFace facing) {
    }

    private record Geometry(
            World world,
            int centerX,
            int roomCenterZ,
            int baseY,
            int outerMinX,
            int outerMaxX,
            int outerMinZ,
            int outerMaxZ,
            int outerFloorY,
            int outerCeilingY,
            int innerMinX,
            int innerMaxX,
            int innerMinZ,
            int innerMaxZ,
            int interiorFloorY,
            int interiorCeilingY,
            int innerSouthZ,
            int stairStartZ,
            int stairEndZ) {

        private static Geometry from(
                World world,
                int centerX,
                int villageCenterZ,
                int radiusZ,
                int baseY) {
            int safeRadiusZ = Math.max(4, radiusZ);
            int bodySouthZ = villageCenterZ - safeRadiusZ + 1;
            int bodyNorthZ = bodySouthZ - BODY_DEPTH + 1;
            int roomCenterZ = (bodyNorthZ + bodySouthZ) / 2;

            int outerMinX = centerX - OUTER_HALF_WIDTH;
            int outerMaxX = centerX + OUTER_HALF_WIDTH;
            int outerMinZ = roomCenterZ - OUTER_HALF_DEPTH;
            int outerMaxZ = roomCenterZ + OUTER_HALF_DEPTH;
            int outerFloorY = baseY + OUTER_FLOOR_OFFSET;
            int outerCeilingY = baseY + OUTER_CEILING_OFFSET;
            int innerMinX = outerMinX + SHELL_THICKNESS;
            int innerMaxX = outerMaxX - SHELL_THICKNESS;
            int innerMinZ = outerMinZ + SHELL_THICKNESS;
            int innerMaxZ = outerMaxZ - SHELL_THICKNESS;
            int stairStartZ = bodySouthZ - 4;
            int stairEndZ = stairStartZ + STAIR_STEPS - 1;

            return new Geometry(
                    Objects.requireNonNull(world, "world"),
                    centerX,
                    roomCenterZ,
                    baseY,
                    outerMinX,
                    outerMaxX,
                    outerMinZ,
                    outerMaxZ,
                    outerFloorY,
                    outerCeilingY,
                    innerMinX,
                    innerMaxX,
                    innerMinZ,
                    innerMaxZ,
                    outerFloorY + SHELL_THICKNESS,
                    outerCeilingY - SHELL_THICKNESS,
                    innerMaxZ,
                    stairStartZ,
                    stairEndZ
            );
        }
    }
}
