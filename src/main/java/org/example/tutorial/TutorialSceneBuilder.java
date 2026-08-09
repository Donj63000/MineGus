package org.example.tutorial;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.block.data.type.Leaves;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Plans de blocs des décors du tutoriel.
 *
 * <p>Cette classe ne touche jamais directement au monde. Le gestionnaire peut
 * donc précharger les chunks, appliquer les placements par lots et interrompre
 * proprement une construction quand le joueur quitte la visite.</p>
 */
public final class TutorialSceneBuilder {

    private static final int GROUND_Y = TutorialWorldGenerator.GROUND_Y;
    private static final int WALK_Y = TutorialWorldGenerator.WALK_Y;

    private TutorialSceneBuilder() {
        // Classe utilitaire.
    }

    /**
     * Étapes fixes du parcours. Les coordonnées restent dans la plaine garantie
     * par {@link TutorialWorldGenerator}.
     */
    public enum Station {
        WELCOME(-24.5D, WALK_Y, -42.5D, -90.0F),
        VILLAGE(20.5D, WALK_Y, -35.5D, 0.0F),
        MINE(80.5D, WALK_Y, -30.5D, 15.0F),
        FARM(120.5D, WALK_Y, -20.5D, 20.0F),
        FOREST(160.5D, WALK_Y, -2.5D, 25.0F),
        RANCH(201.5D, WALK_Y, 13.5D, 25.0F),
        ROYAL(234.5D, WALK_Y, 18.5D, 15.0F),
        FINAL(268.5D, WALK_Y, 0.5D, 0.0F);

        private final double x;
        private final double y;
        private final double z;
        private final float yaw;

        Station(double x, double y, double z, float yaw) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }

        public Location location(World world) {
            return new Location(world, x, y, z, yaw, 0.0F);
        }
    }

    /**
     * Un changement immuable de bloc. BlockData est cloné à la création pour
     * éviter qu'une réutilisation de données orientables ne modifie une tâche
     * déjà planifiée.
     */
    public record Placement(int x, int y, int z, BlockData data) {
        public Placement {
            Objects.requireNonNull(data, "data");
            data = data.clone();
        }

        public Placement(int x, int y, int z, Material material) {
            this(x, y, z, material.createBlockData());
        }

        public void apply(World world) {
            if (world == null
                    || y < world.getMinHeight()
                    || y >= world.getMaxHeight()) {
                return;
            }
            world.getBlockAt(x, y, z).setBlockData(data.clone(), false);
        }
    }

    public static Location npcWelcomeLocation(World world) {
        return new Location(world, -20.5D, WALK_Y, -42.5D, -90.0F, 0.0F);
    }

    public static Location villageNpcLocation(World world) {
        return new Location(world, 17.5D, WALK_Y, -33.5D, 0.0F, 0.0F);
    }

    /**
     * Route exacte suivie par DonjBot entre deux stations.
     */
    public static List<Location> route(
            World world,
            Station from,
            Station to) {
        if (from == Station.VILLAGE && to == Station.MINE) {
            return locations(world,
                    point(20.5, -35.5),
                    point(48.5, -35.5),
                    point(65.5, -33.5),
                    point(80.5, -30.5));
        }
        if (from == Station.MINE && to == Station.FARM) {
            return locations(world,
                    point(80.5, -30.5),
                    point(101.5, -26.5),
                    point(120.5, -20.5));
        }
        if (from == Station.FARM && to == Station.FOREST) {
            return locations(world,
                    point(120.5, -20.5),
                    point(141.5, -12.5),
                    point(160.5, -2.5));
        }
        if (from == Station.FOREST && to == Station.RANCH) {
            return locations(world,
                    point(160.5, -2.5),
                    point(181.5, 7.5),
                    point(201.5, 13.5));
        }
        if (from == Station.RANCH && to == Station.ROYAL) {
            return locations(world,
                    point(201.5, 13.5),
                    point(219.5, 18.5),
                    point(234.5, 18.5));
        }
        if (from == Station.ROYAL && to == Station.FINAL) {
            return locations(world,
                    point(234.5, 18.5),
                    point(252.5, 12.5),
                    point(268.5, 0.5));
        }
        return List.of(from.location(world), to.location(world));
    }

    /**
     * Plateforme d'accueil, chemin complet et éclairage du parcours.
     */
    public static List<Placement> baseScene() {
        List<Placement> placements = new ArrayList<>();

        fillPlatform(
                placements,
                -29,
                -20,
                -47,
                -38,
                Material.POLISHED_ANDESITE
        );
        border(
                placements,
                -29,
                -20,
                -47,
                -38,
                GROUND_Y,
                Material.CHISELED_STONE_BRICKS
        );
        addFloorLight(placements, -27, -45);
        addFloorLight(placements, -22, -45);
        addFloorLight(placements, -27, -40);
        addFloorLight(placements, -22, -40);

        List<IntPoint> route = List.of(
                point(-20.5, -42.5),
                point(20.5, -35.5),
                point(48.5, -35.5),
                point(65.5, -33.5),
                point(80.5, -30.5),
                point(101.5, -26.5),
                point(120.5, -20.5),
                point(141.5, -12.5),
                point(160.5, -2.5),
                point(181.5, 7.5),
                point(201.5, 13.5),
                point(219.5, 18.5),
                point(234.5, 18.5),
                point(252.5, 12.5),
                point(268.5, 0.5)
        );
        addPath(placements, route);

        int[][] lampLocations = {
                {-8, -41}, {8, -38}, {43, -38}, {61, -36},
                {93, -31}, {112, -25}, {132, -19}, {151, -10},
                {172, 1}, {192, 10}, {213, 16}, {230, 21},
                {247, 15}, {262, 6}
        };
        for (int i = 0; i < lampLocations.length; i++) {
            int side = i % 2 == 0 ? -3 : 3;
            addLampPost(
                    placements,
                    lampLocations[i][0],
                    lampLocations[i][1] + side
            );
        }

        /*
         * Les clairières des stations sont nettoyées à hauteur de joueur pour
         * qu'aucune fleur du générateur ne flotte dans les scènes construites.
         */
        clearArea(placements, 66, 95, -24, 4, WALK_Y, WALK_Y + 3);
        clearArea(placements, 106, 136, -8, 18, WALK_Y, WALK_Y + 3);
        clearArea(placements, 145, 178, 8, 42, WALK_Y, WALK_Y + 5);
        clearArea(placements, 185, 219, 23, 51, WALK_Y, WALK_Y + 4);
        clearArea(placements, 220, 248, 27, 50, WALK_Y, WALK_Y + 5);
        clearArea(placements, 250, 283, 8, 35, WALK_Y, WALK_Y + 5);

        return placements;
    }

    /**
     * Village miniature complet : muraille, cinq maisons, place, ferme et puits.
     */
    public static List<Placement> villageScene() {
        List<Placement> placements = new ArrayList<>();

        clearArea(placements, -10, 59, -29, 29, WALK_Y, WALK_Y + 8);

        // Routes et place centrale.
        fill(placements, -6, GROUND_Y, -3, 55, GROUND_Y, 3,
                Material.PACKED_MUD);
        fill(placements, 21, GROUND_Y, -25, 27, GROUND_Y, 25,
                Material.PACKED_MUD);
        fill(placements, 16, GROUND_Y, -6, 32, GROUND_Y, 8,
                Material.STONE_BRICKS);
        border(placements, 16, 32, -6, 8, GROUND_Y,
                Material.MOSSY_STONE_BRICKS);

        addVillageWall(placements, -9, 58, -28, 28);
        addHouse(placements, -1, -20, 9, 8, Material.OAK_PLANKS,
                Material.DARK_OAK_PLANKS, BlockFace.SOUTH);
        addHouse(placements, -1, 10, 9, 8, Material.SPRUCE_PLANKS,
                Material.DARK_OAK_PLANKS, BlockFace.NORTH);
        addHouse(placements, 38, -20, 9, 8, Material.BRICKS,
                Material.DARK_OAK_PLANKS, BlockFace.SOUTH);
        addHouse(placements, 38, 10, 9, 8, Material.OAK_PLANKS,
                Material.SPRUCE_PLANKS, BlockFace.NORTH);
        addHouse(placements, 12, 13, 11, 9, Material.STONE_BRICKS,
                Material.DEEPSLATE_TILES, BlockFace.NORTH);

        addWell(placements, 24, 1);
        addVillageFarm(placements, 30, 11, 36, 22);
        addMarketStall(placements, 10, -5, Material.RED_WOOL);
        addMarketStall(placements, 34, -5, Material.BLUE_WOOL);

        addLampPost(placements, 12, 7);
        addLampPost(placements, 36, 7);
        addLampPost(placements, 12, -8);
        addLampPost(placements, 36, -8);

        addTree(placements, 4, 2, Material.OAK_LOG,
                Material.OAK_LEAVES, 4);
        addTree(placements, 48, 2, Material.BIRCH_LOG,
                Material.BIRCH_LEAVES, 4);

        return placements;
    }

    /**
     * Chevalement, cabane, stockage et coupe de carrière d'exemple.
     */
    public static List<Placement> mineScene() {
        List<Placement> placements = new ArrayList<>();
        int minX = 70;
        int maxX = 91;
        int minZ = -20;
        int maxZ = 2;

        clearArea(placements, minX, maxX, minZ, maxZ, WALK_Y, WALK_Y + 8);

        // Carrière visible mais protégée par un garde-corps.
        for (int x = 75; x <= 87; x++) {
            for (int z = -13; z <= -1; z++) {
                for (int y = GROUND_Y; y >= GROUND_Y - 7; y--) {
                    boolean wall = x == 75 || x == 87 || z == -13 || z == -1;
                    if (wall) {
                        placements.add(new Placement(
                                x,
                                y,
                                z,
                                mineWallMaterial(x, y, z)
                        ));
                    } else {
                        placements.add(new Placement(x, y, z, Material.AIR));
                    }
                }
                placements.add(new Placement(
                        x,
                        GROUND_Y - 8,
                        z,
                        mineWallMaterial(x, GROUND_Y - 8, z)
                ));
            }
        }

        // Minerais exposés au fond pour rendre la démonstration lisible.
        placements.add(new Placement(76, GROUND_Y - 4, -7,
                Material.IRON_ORE));
        placements.add(new Placement(86, GROUND_Y - 5, -9,
                Material.GOLD_ORE));
        placements.add(new Placement(80, GROUND_Y - 8, -2,
                Material.REDSTONE_ORE));
        placements.add(new Placement(84, GROUND_Y - 8, -12,
                Material.DIAMOND_ORE));

        addFenceRectangle(placements, 73, 89, -15, 1,
                Material.DARK_OAK_FENCE, false);

        // Chevalement au-dessus du puits.
        int[][] pillars = {{74, -14}, {88, -14}, {74, 0}, {88, 0}};
        for (int[] pillar : pillars) {
            column(placements, pillar[0], pillar[1],
                    WALK_Y, WALK_Y + 7, Material.DARK_OAK_LOG);
        }
        fill(placements, 73, WALK_Y + 7, -15, 89, WALK_Y + 7, 1,
                Material.DARK_OAK_PLANKS);
        fill(placements, 75, WALK_Y + 8, -13, 87, WALK_Y + 8, -1,
                Material.SPRUCE_SLAB);

        // Cabane et stockage.
        addHouse(placements, 68, -19, 6, 7, Material.SPRUCE_PLANKS,
                Material.DARK_OAK_PLANKS, BlockFace.SOUTH);
        placements.add(new Placement(69, WALK_Y, -10, Material.BARREL));
        placements.add(new Placement(70, WALK_Y, -10, Material.BARREL));
        placements.add(new Placement(71, WALK_Y, -10, Material.CHEST));
        placements.add(new Placement(91, WALK_Y, -5, Material.SMITHING_TABLE));
        placements.add(new Placement(91, WALK_Y, -4, Material.ANVIL));
        placements.add(new Placement(91, WALK_Y, -3, Material.CRAFTING_TABLE));

        addLampPost(placements, 70, -2);
        addLampPost(placements, 92, -17);
        return placements;
    }

    /**
     * Champ irrigué et grange compacte.
     */
    public static List<Placement> farmScene() {
        List<Placement> placements = new ArrayList<>();
        clearArea(placements, 106, 136, -8, 18, WALK_Y, WALK_Y + 7);

        addFenceRectangle(placements, 108, 134, -5, 15,
                Material.OAK_FENCE, true);

        for (int z = -2; z <= 12; z++) {
            for (int x = 110; x <= 132; x++) {
                if (x == 116 || x == 126) {
                    placements.add(new Placement(x, GROUND_Y,
                            z, Material.WATER));
                    continue;
                }

                Farmland farmland =
                        (Farmland) Material.FARMLAND.createBlockData();
                farmland.setMoisture(farmland.getMaximumMoisture());
                placements.add(new Placement(x, GROUND_Y, z, farmland));

                Material crop = cropForColumn(x);
                placements.add(new Placement(
                        x,
                        WALK_Y,
                        z,
                        mature(crop)
                ));
            }
        }

        addHouse(placements, 109, 7, 8, 8, Material.STRIPPED_OAK_WOOD,
                Material.SPRUCE_PLANKS, BlockFace.SOUTH);
        placements.add(new Placement(119, WALK_Y, 14, Material.COMPOSTER));
        placements.add(new Placement(120, WALK_Y, 14, Material.BARREL));
        placements.add(new Placement(121, WALK_Y, 14, Material.CHEST));

        for (int x = 110; x <= 132; x += 4) {
            placements.add(new Placement(x, WALK_Y, -4,
                    Material.HAY_BLOCK));
        }
        addLampPost(placements, 107, -5);
        addLampPost(placements, 135, 15);
        return placements;
    }

    /**
     * Micro-forêt, cabane du forestier et zone de replantation.
     */
    public static List<Placement> forestScene() {
        List<Placement> placements = new ArrayList<>();
        clearArea(placements, 145, 178, 8, 42, WALK_Y, WALK_Y + 10);
        addFenceRectangle(placements, 147, 176, 10, 40,
                Material.SPRUCE_FENCE, true);

        addTree(placements, 153, 17, Material.OAK_LOG,
                Material.OAK_LEAVES, 5);
        addTree(placements, 164, 16, Material.BIRCH_LOG,
                Material.BIRCH_LEAVES, 5);
        addTree(placements, 170, 27, Material.SPRUCE_LOG,
                Material.SPRUCE_LEAVES, 7);
        addTree(placements, 155, 32, Material.DARK_OAK_LOG,
                Material.DARK_OAK_LEAVES, 6);
        addTree(placements, 166, 36, Material.CHERRY_LOG,
                Material.CHERRY_LEAVES, 5);

        int[][] saplings = {
                {150, 25}, {159, 23}, {173, 17},
                {149, 37}, {173, 36}
        };
        Material[] types = {
                Material.OAK_SAPLING,
                Material.BIRCH_SAPLING,
                Material.SPRUCE_SAPLING,
                Material.DARK_OAK_SAPLING,
                Material.CHERRY_SAPLING
        };
        for (int i = 0; i < saplings.length; i++) {
            placements.add(new Placement(
                    saplings[i][0],
                    WALK_Y,
                    saplings[i][1],
                    types[i]
            ));
        }

        addHouse(placements, 147, 11, 8, 7, Material.SPRUCE_PLANKS,
                Material.MOSS_BLOCK, BlockFace.SOUTH);
        placements.add(new Placement(148, WALK_Y, 19, Material.BARREL));
        placements.add(new Placement(149, WALK_Y, 19, Material.CHEST));
        placements.add(new Placement(150, WALK_Y, 19,
                Material.STONECUTTER));

        // Tas de bois lisible depuis le chemin.
        fill(placements, 168, WALK_Y, 12, 173, WALK_Y + 1, 14,
                Material.STRIPPED_OAK_LOG);
        addLampPost(placements, 146, 10);
        addLampPost(placements, 177, 40);
        return placements;
    }

    /**
     * Ranch automatisé avec grange, mangeoires et zones par espèce.
     */
    public static List<Placement> ranchScene() {
        List<Placement> placements = new ArrayList<>();
        clearArea(placements, 185, 219, 23, 51, WALK_Y, WALK_Y + 9);

        addFenceRectangle(placements, 187, 217, 25, 49,
                Material.OAK_FENCE, true);
        fenceLine(placements, 202, 25, 202, 49,
                Material.OAK_FENCE);

        addHouse(placements, 188, 27, 11, 9, Material.RED_TERRACOTTA,
                Material.DARK_OAK_PLANKS, BlockFace.SOUTH);

        // Mangeoires et abreuvoirs.
        fill(placements, 204, GROUND_Y, 29, 215, GROUND_Y, 31,
                Material.MUD_BRICKS);
        fill(placements, 205, WALK_Y, 30, 214, WALK_Y, 30,
                Material.WATER);
        fill(placements, 204, WALK_Y, 40, 215, WALK_Y, 41,
                Material.HAY_BLOCK);
        placements.add(new Placement(199, WALK_Y, 47, Material.BARREL));
        placements.add(new Placement(200, WALK_Y, 47, Material.CHEST));
        placements.add(new Placement(201, WALK_Y, 47, Material.COMPOSTER));

        // Spawners purement visuels : le monde interdit toute apparition naturelle.
        placements.add(new Placement(194, WALK_Y, 44, Material.SPAWNER));
        placements.add(new Placement(210, WALK_Y, 44, Material.SPAWNER));

        addLampPost(placements, 186, 25);
        addLampPost(placements, 218, 49);
        return placements;
    }

    /**
     * Cour royale destinée aux démonstrations /garde, /armure et /army.
     */
    public static List<Placement> royalScene() {
        List<Placement> placements = new ArrayList<>();
        clearArea(placements, 220, 248, 27, 50, WALK_Y, WALK_Y + 9);

        fillPlatform(placements, 222, 246, 29, 48,
                Material.STONE_BRICKS);
        border(placements, 222, 246, 29, 48, GROUND_Y,
                Material.CHISELED_STONE_BRICKS);

        // Petit arc de château au fond de la scène.
        fill(placements, 225, WALK_Y, 44, 243, WALK_Y + 6, 47,
                Material.STONE_BRICKS);
        clearArea(placements, 232, 236, 43, 48,
                WALK_Y, WALK_Y + 5);
        for (int x = 225; x <= 243; x += 3) {
            column(placements, x, 45, WALK_Y + 7,
                    WALK_Y + 8, Material.STONE_BRICKS);
        }

        // Socles des mannequins et emplacements des gardes.
        fill(placements, 226, WALK_Y, 34, 229, WALK_Y, 37,
                Material.POLISHED_BLACKSTONE);
        fill(placements, 239, WALK_Y, 34, 242, WALK_Y, 37,
                Material.POLISHED_BLACKSTONE);
        fill(placements, 232, WALK_Y, 38, 236, WALK_Y, 42,
                Material.GOLD_BLOCK);

        placements.add(new Placement(224, WALK_Y, 31, Material.BELL));
        placements.add(new Placement(244, WALK_Y, 31,
                Material.ENCHANTING_TABLE));
        addLampPost(placements, 222, 29);
        addLampPost(placements, 246, 48);
        return placements;
    }

    /**
     * Place finale : étal marchand et podium de progression.
     */
    public static List<Placement> finalScene() {
        List<Placement> placements = new ArrayList<>();
        clearArea(placements, 250, 283, 8, 35, WALK_Y, WALK_Y + 9);

        fillPlatform(placements, 252, 281, 10, 33,
                Material.SMOOTH_STONE);
        border(placements, 252, 281, 10, 33, GROUND_Y,
                Material.GILDED_BLACKSTONE);

        addMarketStall(placements, 255, 13, Material.PURPLE_WOOL);
        addMarketStall(placements, 270, 13, Material.CYAN_WOOL);

        fill(placements, 259, WALK_Y, 25, 274, WALK_Y, 31,
                Material.DARK_OAK_PLANKS);
        for (int y = WALK_Y + 1; y <= WALK_Y + 5; y++) {
            placements.add(new Placement(259, y, 28,
                    Material.DARK_OAK_LOG));
            placements.add(new Placement(274, y, 28,
                    Material.DARK_OAK_LOG));
        }
        fill(placements, 259, WALK_Y + 5, 25, 274, WALK_Y + 5, 31,
                Material.AMETHYST_BLOCK);

        placements.add(new Placement(264, WALK_Y + 1, 27,
                Material.DIAMOND_BLOCK));
        placements.add(new Placement(265, WALK_Y + 1, 27,
                Material.EMERALD_BLOCK));
        placements.add(new Placement(266, WALK_Y + 1, 27,
                Material.GOLD_BLOCK));
        placements.add(new Placement(267, WALK_Y + 1, 27,
                Material.IRON_BLOCK));
        placements.add(new Placement(268, WALK_Y + 1, 27,
                Material.REDSTONE_BLOCK));
        placements.add(new Placement(269, WALK_Y + 1, 27,
                Material.LAPIS_BLOCK));

        addLampPost(placements, 252, 10);
        addLampPost(placements, 281, 33);
        return placements;
    }

    private static void addVillageWall(
            List<Placement> placements,
            int minX,
            int maxX,
            int minZ,
            int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            addWallColumn(placements, x, minZ, x >= 21 && x <= 27);
            addWallColumn(placements, x, maxZ, false);
        }
        for (int z = minZ + 1; z < maxZ; z++) {
            addWallColumn(placements, minX, z, false);
            addWallColumn(placements, maxX, z, false);
        }

        // Tours d'angle.
        addTower(placements, minX, minZ);
        addTower(placements, maxX, minZ);
        addTower(placements, minX, maxZ);
        addTower(placements, maxX, maxZ);
    }

    private static void addWallColumn(
            List<Placement> placements,
            int x,
            int z,
            boolean gateOpening) {
        if (gateOpening) {
            placements.add(new Placement(x, GROUND_Y, z,
                    Material.STONE_BRICKS));
            return;
        }
        column(placements, x, z, WALK_Y, WALK_Y + 3,
                Material.STONE_BRICKS);
        if (Math.floorMod(x + z, 2) == 0) {
            placements.add(new Placement(x, WALK_Y + 4, z,
                    Material.STONE_BRICK_WALL));
        }
    }

    private static void addTower(
            List<Placement> placements,
            int centerX,
            int centerZ) {
        for (int x = centerX - 2; x <= centerX + 2; x++) {
            for (int z = centerZ - 2; z <= centerZ + 2; z++) {
                boolean edge = x == centerX - 2 || x == centerX + 2
                        || z == centerZ - 2 || z == centerZ + 2;
                if (!edge) {
                    continue;
                }
                column(placements, x, z, WALK_Y, WALK_Y + 5,
                        Material.STONE_BRICKS);
                if (Math.floorMod(x + z, 2) == 0) {
                    placements.add(new Placement(
                            x,
                            WALK_Y + 6,
                            z,
                            Material.STONE_BRICK_WALL
                    ));
                }
            }
        }
    }

    private static void addHouse(
            List<Placement> placements,
            int startX,
            int startZ,
            int width,
            int depth,
            Material wall,
            Material roof,
            BlockFace doorFace) {
        int maxX = startX + width - 1;
        int maxZ = startZ + depth - 1;

        fill(placements, startX, GROUND_Y, startZ,
                maxX, GROUND_Y, maxZ, Material.SPRUCE_PLANKS);

        for (int y = WALK_Y; y <= WALK_Y + 3; y++) {
            for (int x = startX; x <= maxX; x++) {
                placements.add(new Placement(x, y, startZ, wall));
                placements.add(new Placement(x, y, maxZ, wall));
            }
            for (int z = startZ + 1; z < maxZ; z++) {
                placements.add(new Placement(startX, y, z, wall));
                placements.add(new Placement(maxX, y, z, wall));
            }
        }

        int doorX = startX + width / 2;
        int doorZ = startZ + depth / 2;
        switch (doorFace) {
            case NORTH -> doorZ = startZ;
            case SOUTH -> doorZ = maxZ;
            case WEST -> doorX = startX;
            case EAST -> doorX = maxX;
            default -> {
                doorX = startX + width / 2;
                doorZ = maxZ;
            }
        }
        addDoor(placements, doorX, WALK_Y, doorZ, doorFace);

        // Fenêtres latérales, sans remplacer les coins porteurs.
        int windowY = WALK_Y + 2;
        if (width >= 7) {
            placements.add(new Placement(startX + 2, windowY,
                    startZ, Material.GLASS_PANE));
            placements.add(new Placement(maxX - 2, windowY,
                    maxZ, Material.GLASS_PANE));
        }
        if (depth >= 7) {
            placements.add(new Placement(startX, windowY,
                    startZ + 2, Material.GLASS_PANE));
            placements.add(new Placement(maxX, windowY,
                    maxZ - 2, Material.GLASS_PANE));
        }

        /*
         * Toit en trois niveaux de blocs pleins : il reste lisible quelle que
         * soit l'orientation et n'impose pas de calcul de stairs aux coins.
         */
        fill(placements, startX - 1, WALK_Y + 4, startZ - 1,
                maxX + 1, WALK_Y + 4, maxZ + 1, roof);
        fill(placements, startX, WALK_Y + 5, startZ,
                maxX, WALK_Y + 5, maxZ, roof);
        fill(placements, startX + 2, WALK_Y + 6, startZ + 1,
                maxX - 2, WALK_Y + 6, maxZ - 1, roof);

        placements.add(new Placement(startX + 1, WALK_Y,
                startZ + 1, Material.CRAFTING_TABLE));
        placements.add(new Placement(maxX - 1, WALK_Y,
                maxZ - 1, Material.LANTERN));
    }

    private static void addDoor(
            List<Placement> placements,
            int x,
            int y,
            int z,
            BlockFace facing) {
        Door bottom = (Door) Material.OAK_DOOR.createBlockData();
        bottom.setFacing(facing);
        bottom.setHalf(Bisected.Half.BOTTOM);
        bottom.setOpen(false);

        Door top = (Door) bottom.clone();
        top.setHalf(Bisected.Half.TOP);

        placements.add(new Placement(x, y, z, bottom));
        placements.add(new Placement(x, y + 1, z, top));
    }

    private static void addWell(
            List<Placement> placements,
            int centerX,
            int centerZ) {
        for (int x = centerX - 2; x <= centerX + 2; x++) {
            for (int z = centerZ - 2; z <= centerZ + 2; z++) {
                boolean edge = x == centerX - 2 || x == centerX + 2
                        || z == centerZ - 2 || z == centerZ + 2;
                placements.add(new Placement(
                        x,
                        GROUND_Y,
                        z,
                        edge ? Material.COBBLESTONE : Material.WATER
                ));
            }
        }
        column(placements, centerX - 2, centerZ,
                WALK_Y, WALK_Y + 3, Material.OAK_LOG);
        column(placements, centerX + 2, centerZ,
                WALK_Y, WALK_Y + 3, Material.OAK_LOG);
        fill(placements, centerX - 2, WALK_Y + 3, centerZ - 1,
                centerX + 2, WALK_Y + 3, centerZ + 1,
                Material.DARK_OAK_PLANKS);
        placements.add(new Placement(centerX, WALK_Y + 2,
                centerZ, Material.CHAIN));
    }

    private static void addVillageFarm(
            List<Placement> placements,
            int minX,
            int minZ,
            int maxX,
            int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (x == (minX + maxX) / 2) {
                    placements.add(new Placement(x, GROUND_Y,
                            z, Material.WATER));
                } else {
                    Farmland farmland =
                            (Farmland) Material.FARMLAND.createBlockData();
                    farmland.setMoisture(
                            farmland.getMaximumMoisture()
                    );
                    placements.add(new Placement(
                            x,
                            GROUND_Y,
                            z,
                            farmland
                    ));
                    placements.add(new Placement(
                            x,
                            WALK_Y,
                            z,
                            mature(cropForColumn(x))
                    ));
                }
            }
        }
    }

    private static void addMarketStall(
            List<Placement> placements,
            int startX,
            int startZ,
            Material canopy) {
        int maxX = startX + 5;
        int maxZ = startZ + 3;
        fill(placements, startX, GROUND_Y, startZ,
                maxX, GROUND_Y, maxZ, Material.OAK_PLANKS);
        int[][] corners = {
                {startX, startZ},
                {maxX, startZ},
                {startX, maxZ},
                {maxX, maxZ}
        };
        for (int[] corner : corners) {
            column(placements, corner[0], corner[1],
                    WALK_Y, WALK_Y + 3, Material.OAK_FENCE);
        }
        fill(placements, startX, WALK_Y + 4, startZ,
                maxX, WALK_Y + 4, maxZ, canopy);
        fill(placements, startX + 1, WALK_Y, startZ + 1,
                maxX - 1, WALK_Y, maxZ - 1, Material.BARREL);
    }

    private static void addTree(
            List<Placement> placements,
            int x,
            int z,
            Material log,
            Material leaves,
            int trunkHeight) {
        int topY = WALK_Y + trunkHeight - 1;
        column(placements, x, z, WALK_Y, topY, log);

        BlockData persistentLeaves = leaves.createBlockData();
        if (persistentLeaves instanceof Leaves leafData) {
            leafData.setPersistent(true);
        }

        for (int dy = -2; dy <= 2; dy++) {
            int radius = Math.abs(dy) == 2 ? 1 : 2;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) == radius
                            && Math.abs(dz) == radius
                            && Math.floorMod(dx + dz + dy, 2) == 0) {
                        continue;
                    }
                    if (dx == 0 && dz == 0 && dy <= 0) {
                        continue;
                    }
                    placements.add(new Placement(
                            x + dx,
                            topY + dy,
                            z + dz,
                            persistentLeaves
                    ));
                }
            }
        }
    }

    private static void addPath(
            List<Placement> placements,
            List<IntPoint> points) {
        int stepCounter = 0;
        for (int i = 0; i < points.size() - 1; i++) {
            IntPoint from = points.get(i);
            IntPoint to = points.get(i + 1);
            List<IntPoint> line = line(from.x, from.z, to.x, to.z);
            for (IntPoint point : line) {
                Material material = switch (Math.floorMod(stepCounter++, 9)) {
                    case 0 -> Material.COARSE_DIRT;
                    case 4 -> Material.GRAVEL;
                    default -> Material.DIRT_PATH;
                };
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (Math.abs(dx) + Math.abs(dz) > 1) {
                            continue;
                        }
                        placements.add(new Placement(
                                point.x + dx,
                                GROUND_Y,
                                point.z + dz,
                                material
                        ));
                        placements.add(new Placement(
                                point.x + dx,
                                WALK_Y,
                                point.z + dz,
                                Material.AIR
                        ));
                        placements.add(new Placement(
                                point.x + dx,
                                WALK_Y + 1,
                                point.z + dz,
                                Material.AIR
                        ));
                    }
                }
            }
        }
    }

    private static List<IntPoint> line(
            int x0,
            int z0,
            int x1,
            int z1) {
        List<IntPoint> result = new ArrayList<>();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int error = dx - dz;

        int x = x0;
        int z = z0;
        while (true) {
            result.add(new IntPoint(x, z));
            if (x == x1 && z == z1) {
                break;
            }
            int doubleError = error * 2;
            if (doubleError > -dz) {
                error -= dz;
                x += sx;
            }
            if (doubleError < dx) {
                error += dx;
                z += sz;
            }
        }
        return result;
    }

    private static void addLampPost(
            List<Placement> placements,
            int x,
            int z) {
        placements.add(new Placement(x, GROUND_Y, z,
                Material.STONE_BRICKS));
        column(placements, x, z, WALK_Y, WALK_Y + 2,
                Material.DARK_OAK_FENCE);
        placements.add(new Placement(x, WALK_Y + 3, z,
                Material.LANTERN));
    }

    private static void addFloorLight(
            List<Placement> placements,
            int x,
            int z) {
        placements.add(new Placement(x, GROUND_Y, z,
                Material.SEA_LANTERN));
    }

    private static void addFenceRectangle(
            List<Placement> placements,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            Material fence,
            boolean openingSouth) {
        for (int x = minX; x <= maxX; x++) {
            boolean gate = openingSouth
                    && x >= (minX + maxX) / 2 - 1
                    && x <= (minX + maxX) / 2 + 1;
            if (!gate) {
                placements.add(new Placement(x, WALK_Y, minZ, fence));
            }
            placements.add(new Placement(x, WALK_Y, maxZ, fence));
        }
        for (int z = minZ + 1; z < maxZ; z++) {
            placements.add(new Placement(minX, WALK_Y, z, fence));
            placements.add(new Placement(maxX, WALK_Y, z, fence));
        }
    }

    private static void fenceLine(
            List<Placement> placements,
            int startX,
            int startZ,
            int endX,
            int endZ,
            Material fence) {
        for (IntPoint point : line(startX, startZ, endX, endZ)) {
            placements.add(new Placement(
                    point.x,
                    WALK_Y,
                    point.z,
                    fence
            ));
        }
    }

    private static void fillPlatform(
            List<Placement> placements,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            Material material) {
        fill(placements, minX, GROUND_Y, minZ,
                maxX, GROUND_Y, maxZ, material);
        clearArea(placements, minX, maxX, minZ, maxZ,
                WALK_Y, WALK_Y + 3);
    }

    private static void border(
            List<Placement> placements,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int y,
            Material material) {
        for (int x = minX; x <= maxX; x++) {
            placements.add(new Placement(x, y, minZ, material));
            placements.add(new Placement(x, y, maxZ, material));
        }
        for (int z = minZ + 1; z < maxZ; z++) {
            placements.add(new Placement(minX, y, z, material));
            placements.add(new Placement(maxX, y, z, material));
        }
    }

    private static void clearArea(
            List<Placement> placements,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int minY,
            int maxY) {
        fill(placements, minX, minY, minZ,
                maxX, maxY, maxZ, Material.AIR);
    }

    private static void fill(
            List<Placement> placements,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            Material material) {
        int resolvedMinX = Math.min(minX, maxX);
        int resolvedMaxX = Math.max(minX, maxX);
        int resolvedMinY = Math.min(minY, maxY);
        int resolvedMaxY = Math.max(minY, maxY);
        int resolvedMinZ = Math.min(minZ, maxZ);
        int resolvedMaxZ = Math.max(minZ, maxZ);

        for (int x = resolvedMinX; x <= resolvedMaxX; x++) {
            for (int y = resolvedMinY; y <= resolvedMaxY; y++) {
                for (int z = resolvedMinZ; z <= resolvedMaxZ; z++) {
                    placements.add(new Placement(x, y, z, material));
                }
            }
        }
    }

    private static void column(
            List<Placement> placements,
            int x,
            int z,
            int minY,
            int maxY,
            Material material) {
        for (int y = Math.min(minY, maxY);
             y <= Math.max(minY, maxY);
             y++) {
            placements.add(new Placement(x, y, z, material));
        }
    }

    private static Material cropForColumn(int x) {
        return switch (Math.floorMod(x, 4)) {
            case 0 -> Material.WHEAT;
            case 1 -> Material.CARROTS;
            case 2 -> Material.POTATOES;
            default -> Material.BEETROOTS;
        };
    }

    private static BlockData mature(Material crop) {
        BlockData data = crop.createBlockData();
        if (data instanceof Ageable ageable) {
            ageable.setAge(ageable.getMaximumAge());
        }
        return data;
    }

    private static Material mineWallMaterial(int x, int y, int z) {
        int selector = Math.floorMod(x * 31 + y * 17 + z * 13, 19);
        return switch (selector) {
            case 0 -> Material.COAL_ORE;
            case 1 -> Material.COPPER_ORE;
            case 2 -> Material.IRON_ORE;
            case 3 -> Material.ANDESITE;
            case 4 -> Material.TUFF;
            default -> y <= GROUND_Y - 5
                    ? Material.DEEPSLATE
                    : Material.STONE;
        };
    }

    private static List<Location> locations(
            World world,
            IntPoint... points) {
        if (points.length == 0) {
            return Collections.emptyList();
        }
        List<Location> result = new ArrayList<>(points.length);
        for (IntPoint point : points) {
            result.add(new Location(
                    world,
                    point.x + 0.5D,
                    WALK_Y,
                    point.z + 0.5D
            ));
        }
        return result;
    }

    private static IntPoint point(double x, double z) {
        return new IntPoint((int) Math.floor(x), (int) Math.floor(z));
    }

    private record IntPoint(int x, int z) {
    }
}
