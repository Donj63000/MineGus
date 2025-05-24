package org.example;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Furnace;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Commande /village : génère un village de PNJ très complet,
 * avec plusieurs maisons aux toits finis (blocs pleins), des chemins
 * bien reliés aux maisons, un puits, un champ, des tours de guet,
 * un marché, des arbres customisés, une taverne, un étang décoratif, etc.
 */
public final class Village implements CommandExecutor {

    private final JavaPlugin plugin;

    // Décalage horizontal entre le centre et les maisons
    private final int gap = 25;

    public Village(JavaPlugin plugin) {
        this.plugin = plugin;
        if (plugin.getCommand("village") != null) {
            plugin.getCommand("village").setExecutor(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender,
                             Command command,
                             String label,
                             String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Cette commande doit être exécutée par un joueur.");
            return true;
        }

        if (!command.getName().equalsIgnoreCase("village")) {
            return false;
        }

        // Génère le village
        generateVillage(player.getLocation());
        sender.sendMessage(ChatColor.GREEN + "Village amélioré généré avec succès !");
        return true;
    }

    /* ----------------------------------------------------------------------
       Méthode principale : construction du village à partir d'un point central
    ---------------------------------------------------------------------- */
    private void generateVillage(Location center) {
        World world = center.getWorld();
        int baseX = center.getBlockX();
        int baseY = center.getBlockY();
        int baseZ = center.getBlockZ();

        // 1) Construit 4 maisons (2 Type A, 2 Type B)
        //    + récupère les coordonnées de la porte pour les raccorder aux routes
        // Maison NW (Type A)
        buildHouseTypeA(world, baseX - gap, baseY, baseZ - gap);
        int houseNWdoorX = (baseX - gap) + 9 / 2; // la porte se trouve à x = startX + width/2
        int houseNWdoorZ = (baseZ - gap);        // la porte est sur le mur "z = startZ"
        // Maison NE (Type A)
        buildHouseTypeA(world, baseX + gap, baseY, baseZ - gap);
        int houseNEdoorX = (baseX + gap) + 9 / 2;
        int houseNEdoorZ = (baseZ - gap);
        // Maison SW (Type B)
        buildHouseTypeB(world, baseX - gap, baseY, baseZ + gap);
        int houseSWdoorX = (baseX - gap) + 7 / 2; // width=7
        int houseSWdoorZ = (baseZ + gap);
        // Maison SE (Type B)
        buildHouseTypeB(world, baseX + gap, baseY, baseZ + gap);
        int houseSEdoorX = (baseX + gap) + 7 / 2;
        int houseSEdoorZ = (baseZ + gap);

        // 2) Puits central
        buildWell(world, baseX, baseY, baseZ);

        // 3) Ferme, enclos, forge
        buildFarm(world, baseX - gap - 12, baseY, baseZ - gap + 5);
        buildFenceEnclosure(world, baseX - gap + 5, baseY, baseZ - gap - 6);
        buildBlacksmith(world, baseX + gap + 10, baseY, baseZ - gap - 5);
        // Récupère la coordonnée de la porte du blacksmith (pour un chemin)
        // Le blacksmith fait 6 blocs de large, la porte se trouve "théoriquement" sur le mur "z = startZ"
        int blacksmithDoorX = (baseX + gap + 10) + 6 / 2;
        int blacksmithDoorZ = (baseZ - gap - 5);

        // 4) Tours de guet
        buildWatchTower(world, baseX + gap + 15, baseY, baseZ);
        buildWatchTower(world, baseX - gap - 15, baseY, baseZ);

        // 5) Marché (étals)
        buildMarketStall(world, baseX + 5, baseY, baseZ + 2);
        buildMarketStall(world, baseX - 5, baseY, baseZ - 3);
        buildMarketStall(world, baseX + 2, baseY, baseZ - 6);

        // 6) Taverne
        buildTavern(world, baseX + gap + 8, baseY, baseZ + gap - 5);
        // Porte de la taverne
        int tavernDoorX = (baseX + gap + 8) + 10 / 2;
        int tavernDoorZ = (baseZ + gap - 5);

        // 7) Étang, jardin de fleurs
        buildDecorativePond(world, baseX + gap + 20, baseY, baseZ + 8);
        buildFlowerGarden(world, baseX, baseY, baseZ - gap - 10);

        // 8) Chemins principaux et lampadaires
        buildRoads(world, baseX, baseY, baseZ, gap);

        // 9) Petits chemins pour relier les portes principales au centre du village
        //    (on fait un chemin en "L", 3 blocs de large)
        buildPath(world, baseX, baseZ, houseNWdoorX, houseNWdoorZ, baseY, 1);
        buildPath(world, baseX, baseZ, houseNEdoorX, houseNEdoorZ, baseY, 1);
        buildPath(world, baseX, baseZ, houseSWdoorX, houseSWdoorZ, baseY, 1);
        buildPath(world, baseX, baseZ, houseSEdoorX, houseSEdoorZ, baseY, 1);

        // De même pour la forge et la taverne
        buildPath(world, baseX, baseZ, blacksmithDoorX, blacksmithDoorZ, baseY, 1);
        buildPath(world, baseX, baseZ, tavernDoorX, tavernDoorZ, baseY, 1);

        // 10) Arbres customisés
        buildCustomTree(world, baseX + gap + 5, baseY + 1, baseZ + gap + 5);
        buildCustomTree(world, baseX - gap - 3, baseY + 1, baseZ + gap + 2);
        buildCustomTree(world, baseX + gap + 2, baseY + 1, baseZ - gap - 8);

        // 11) Villageois
        for (int i = 0; i < 6; i++) {
            Location spawn = center.clone().add(i - 3, 1, 0);
            Villager villager = (Villager) world.spawnEntity(spawn, EntityType.VILLAGER);
            villager.setCustomName("Villageois");
            villager.setCustomNameVisible(true);
        }

        // 12) Panneaux indicatifs
        buildSign(world, baseX + 1, baseY + 1, baseZ, BlockFace.EAST, "Bienvenue dans le Village !");
        buildSign(world, baseX + gap + 9, baseY + 1, baseZ - gap + 5, BlockFace.NORTH, "La Taverne");
        buildSign(world, baseX - gap, baseY + 1, baseZ - gap - 7, BlockFace.SOUTH, "Ferme");
    }

    /* ----------------------------------------------------------------------
       A) Maisons : deux variantes (Type A et Type B) avec toits finis
    ---------------------------------------------------------------------- */
    private void buildHouseTypeA(World world, int startX, int startY, int startZ) {
        buildHouseCommonStructure(
                world, startX, startY, startZ,
                9, // width
                9, // depth
                2, // floors
                Material.OAK_LOG,
                Material.OAK_PLANKS,
                Material.SPRUCE_PLANKS
        );
    }

    private void buildHouseTypeB(World world, int startX, int startY, int startZ) {
        buildHouseCommonStructure(
                world, startX, startY, startZ,
                7, // width
                10, // depth
                2, // floors
                Material.SPRUCE_LOG,
                Material.BIRCH_PLANKS,
                Material.OAK_PLANKS
        );
    }

    /**
     * Structure commune : floors étages, coins en cornerLog, murs en wallPlank,
     * planchers en floorPlank, toit + cheminée, un escalier intérieur, etc.
     */
    private void buildHouseCommonStructure(World world,
                                           int startX,
                                           int startY,
                                           int startZ,
                                           int width,
                                           int depth,
                                           int floors,
                                           Material cornerLog,
                                           Material wallPlank,
                                           Material floorPlank) {

        int wallHeightPerFloor = 4;
        int totalHeight = floors * wallHeightPerFloor;

        // 1) Plancher rez-de-chaussée
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                setBlock(world, startX + x, startY, startZ + z, floorPlank);
            }
        }

        // Fondation apparente autour de la maison
        for (int x = -1; x <= width; x++) {
            for (int z = -1; z <= depth; z++) {
                boolean border = (x == -1 || x == width || z == -1 || z == depth);
                if (border) {
                    setBlock(world, startX + x, startY, startZ + z, Material.STONE_BRICKS);
                }
            }
        }

        // 2) Murs et planchers intermédiaires
        for (int floor = 0; floor < floors; floor++) {
            int floorBaseY = startY + floor * wallHeightPerFloor;

            // Poutres aux 4 coins
            for (int y = 1; y <= wallHeightPerFloor; y++) {
                setBlock(world, startX,             floorBaseY + y, startZ,             cornerLog);
                setBlock(world, startX + width - 1, floorBaseY + y, startZ,             cornerLog);
                setBlock(world, startX,             floorBaseY + y, startZ + depth - 1, cornerLog);
                setBlock(world, startX + width - 1, floorBaseY + y, startZ + depth - 1, cornerLog);
            }

            // Plancher de l'étage suivant (sauf dernier étage)
            if (floor < floors - 1) {
                int floorTopY = floorBaseY + wallHeightPerFloor;
                for (int ix = 0; ix < width; ix++) {
                    for (int iz = 0; iz < depth; iz++) {
                        setBlock(world, startX + ix, floorTopY, startZ + iz, floorPlank);
                    }
                }
            }

            // Murs (y compris fenêtres et porte)
            for (int y = 1; y <= wallHeightPerFloor; y++) {
                int currentY = floorBaseY + y;
                for (int ix = 0; ix < width; ix++) {
                    for (int iz = 0; iz < depth; iz++) {
                        boolean isEdge = (ix == 0 || ix == width - 1 || iz == 0 || iz == depth - 1);
                        if (isEdge && !isLogAtCorner(ix, iz, width, depth)) {
                            boolean isDoor = (floor == 0 && y <= 2 && iz == 0 && ix == width / 2);
                            boolean isWindowLayer = (y == 2 || y == 3);
                            boolean frontBackWindow = (iz == 0 || iz == depth - 1) && (ix >= 2 && ix <= width - 3);
                            boolean sideWindow = (ix == 0 || ix == width - 1) && (iz >= 2 && iz <= depth - 3);
                            boolean isWindowPosition = isWindowLayer && (frontBackWindow || sideWindow) && !isDoor;

                            if (isDoor) {
                                // ouverture pour la porte
                            } else if (isWindowPosition) {
                                setBlock(world, startX + ix, currentY, startZ + iz, Material.GLASS_PANE);
                            } else {
                                setBlock(world, startX + ix, currentY, startZ + iz, wallPlank);
                            }
                        }
                    }
                }
            }
        }

        // 3) Porte d'entrée (en bas, face "z=0")
        placeDoor(world, startX + (width / 2), startY + 1, startZ);

        // 4) Escalier intérieur
        int stairBaseX = startX + width - 2;
        int stairBaseZ = startZ + 1;
        int wallHeightPerFloorMinus1 = wallHeightPerFloor - 1;
        for (int i = 0; i < wallHeightPerFloorMinus1; i++) {
            placeStairs(world,
                    stairBaseX,
                    startY + 1 + i,
                    stairBaseZ + i,
                    Material.OAK_STAIRS,
                    Stairs.Shape.STRAIGHT,
                    getFacingSouth());
        }

        // 5) Toit fini + cheminée
        int roofStartY = startY + totalHeight + 1;
        buildFinishedRoofWithChimney(world, startX, roofStartY, startZ, width, depth);

        // 6) Torches
        setBlock(world, startX + 1, startY + 2, startZ + 1, Material.WALL_TORCH);
        setBlock(world, startX + width - 2, startY + 2, startZ + depth - 2, Material.WALL_TORCH);

        // 7) Intérieur basique
        decorateHouseInterior(world, startX, startY, startZ, width, depth);
    }

    private boolean isLogAtCorner(int x, int z, int width, int depth) {
        return ((x == 0 && z == 0)
                || (x == 0 && z == depth - 1)
                || (x == width - 1 && z == 0)
                || (x == width - 1 && z == depth - 1));
    }

    private void decorateHouseInterior(World world,
                                       int startX,
                                       int startY,
                                       int startZ,
                                       int width,
                                       int depth) {
        // Lit
        int bedX = startX + 2;
        int bedZ = startZ + 2;
        world.getBlockAt(bedX, startY + 1, bedZ).setType(Material.RED_BED, false);

        // Coffre
        int chestX = startX + width - 3;
        int chestZ = startZ + depth - 3;
        setBlock(world, chestX, startY + 1, chestZ, Material.CHEST);

        // Table de craft
        int craftX = startX + 2;
        int craftZ = startZ + depth - 3;
        setBlock(world, craftX, startY + 1, craftZ, Material.CRAFTING_TABLE);

        // Four et baril pour un intérieur plus complet
        int furnaceX = startX + width - 3;
        int furnaceZ = startZ + 2;
        setBlock(world, furnaceX, startY + 1, furnaceZ, Material.FURNACE);

        int barrelX = startX + 1;
        int barrelZ = startZ + depth / 2;
        setBlock(world, barrelX, startY + 1, barrelZ, Material.BARREL);
    }

    /**
     * Construit un toit complet : des escaliers en bordure + blocs pleins
     * (planches) pour remplir l'intérieur, pour chaque "layer".
     */
    private void buildFinishedRoofWithChimney(World world,
                                              int startX,
                                              int startY,
                                              int startZ,
                                              int width,
                                              int depth) {

        // On déborde d'un bloc
        int roofWidth = width + 2;
        int roofDepth = depth + 2;

        // Nombre de couches
        int roofLayers = Math.max(roofWidth, roofDepth) / 2 + 1;

        for (int layer = 0; layer < roofLayers; layer++) {
            int x1 = startX - 1 + layer;
            int z1 = startZ - 1 + layer;
            int x2 = startX + width - layer;
            int z2 = startZ + depth - layer;
            int y = startY + layer;

            if (x1 > x2 || z1 > z2) {
                break;
            }

            // Pose des escaliers sur les bords
            for (int z = z1; z <= z2; z++) {
                placeStairs(world, x1, y, z, Material.SPRUCE_STAIRS, Stairs.Shape.STRAIGHT, getFacingWest());
                placeStairs(world, x2, y, z, Material.SPRUCE_STAIRS, Stairs.Shape.STRAIGHT, getFacingEast());
            }
            for (int x = x1; x <= x2; x++) {
                placeStairs(world, x, y, z1, Material.SPRUCE_STAIRS, Stairs.Shape.STRAIGHT, getFacingNorth());
                placeStairs(world, x, y, z2, Material.SPRUCE_STAIRS, Stairs.Shape.STRAIGHT, getFacingSouth());
            }

            // Remplissage intérieur en planches (pour un toit "plein")
            // On remplit l'intérieur si x2 > x1+1 et z2 > z1+1
            for (int xx = x1 + 1; xx <= x2 - 1; xx++) {
                for (int zz = z1 + 1; zz <= z2 - 1; zz++) {
                    setBlock(world, xx, y, zz, Material.SPRUCE_PLANKS);
                }
            }
        }

        // Cheminée
        int chimneyX = startX + width - 2;
        int chimneyZ = startZ + depth - 2;
        int chimneyBaseY = startY + 1;
        int chimneyHeight = 3;
        for (int i = 0; i < chimneyHeight; i++) {
            setBlock(world, chimneyX, chimneyBaseY + i, chimneyZ, Material.BRICKS);
        }
        world.getBlockAt(chimneyX, chimneyBaseY + chimneyHeight, chimneyZ).setType(Material.CAMPFIRE, false);
        BlockData campfireData = world.getBlockAt(chimneyX, chimneyBaseY + chimneyHeight, chimneyZ).getBlockData();
        if (campfireData instanceof Campfire campfire) {
            campfire.setLit(true);
            campfire.setSignalFire(true);
            world.getBlockAt(chimneyX, chimneyBaseY + chimneyHeight, chimneyZ).setBlockData(campfire);
        }
    }

    /* ----------------------------------------------------------------------
       B) Puits, ferme, enclos, forge, tours de guet, marché, taverne, etc.
    ---------------------------------------------------------------------- */

    private void buildWell(World world, int centerX, int centerY, int centerZ) {
        // ... (identique à la version précédente)
        int wellSize = 4;
        for (int x = 0; x < wellSize; x++) {
            for (int z = 0; z < wellSize; z++) {
                setBlock(world, centerX + x - 1, centerY, centerZ + z - 1, Material.COBBLESTONE);
            }
        }
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                setBlock(world, centerX + x, centerY, centerZ + z, Material.WATER);
            }
        }
        for (int dy = 1; dy <= 3; dy++) {
            setBlock(world, centerX - 1, centerY + dy, centerZ - 1, Material.COBBLESTONE);
            setBlock(world, centerX + 2, centerY + dy, centerZ - 1, Material.COBBLESTONE);
            setBlock(world, centerX - 1, centerY + dy, centerZ + 2, Material.COBBLESTONE);
            setBlock(world, centerX + 2, centerY + dy, centerZ + 2, Material.COBBLESTONE);
        }
        for (int x = -1; x <= 2; x++) {
            for (int z = -1; z <= 2; z++) {
                setBlock(world, centerX + x, centerY + 4, centerZ + z, Material.COBBLESTONE_SLAB);
            }
        }
    }

    private void buildFarm(World world, int startX, int startY, int startZ) {
        // ... (inchangé)
        int farmWidth = 6;
        int farmDepth = 6;
        // Clôture
        for (int x = 0; x <= farmWidth; x++) {
            setBlock(world, startX + x, startY + 1, startZ, Material.OAK_FENCE);
            setBlock(world, startX + x, startY + 1, startZ + farmDepth, Material.OAK_FENCE);
        }
        for (int z = 0; z <= farmDepth; z++) {
            setBlock(world, startX, startY + 1, startZ + z, Material.OAK_FENCE);
            setBlock(world, startX + farmWidth, startY + 1, startZ + z, Material.OAK_FENCE);
        }
        // Terre + eau
        for (int x = 1; x < farmWidth; x++) {
            for (int z = 1; z < farmDepth; z++) {
                setBlock(world, startX + x, startY, startZ + z, Material.DIRT);
            }
        }
        int waterRow = startZ + farmDepth / 2;
        for (int x = 1; x < farmWidth; x++) {
            setBlock(world, startX + x, startY, waterRow, Material.WATER);
        }
        // Farmland + cultures
        for (int x = 1; x < farmWidth; x++) {
            for (int z = 1; z < farmDepth; z++) {
                if (z != farmDepth / 2) {
                    world.getBlockAt(startX + x, startY, startZ + z).setType(Material.FARMLAND, false);
                    if ((x + z) % 2 == 0) {
                        setBlock(world, startX + x, startY + 1, startZ + z, Material.WHEAT);
                    } else {
                        setBlock(world, startX + x, startY + 1, startZ + z, Material.CARROTS);
                    }
                }
            }
        }
    }

    private void buildFenceEnclosure(World world, int startX, int startY, int startZ) {
        // ... (inchangé)
        int enclosureWidth = 8;
        int enclosureDepth = 5;
        for (int x = 0; x <= enclosureWidth; x++) {
            setBlock(world, startX + x, startY + 1, startZ, Material.OAK_FENCE);
            setBlock(world, startX + x, startY + 1, startZ + enclosureDepth, Material.OAK_FENCE);
        }
        for (int z = 0; z <= enclosureDepth; z++) {
            setBlock(world, startX, startY + 1, startZ + z, Material.OAK_FENCE);
            setBlock(world, startX + enclosureWidth, startY + 1, startZ + z, Material.OAK_FENCE);
        }
        world.getBlockAt(startX + enclosureWidth / 2, startY + 1, startZ).setType(Material.OAK_FENCE_GATE);
        for (int i = 0; i < 3; i++) {
            Location spawn = new Location(world, startX + 2 + i, startY + 1, startZ + 2);
            world.spawnEntity(spawn, EntityType.SHEEP);
        }
    }

    private void buildBlacksmith(World world, int startX, int startY, int startZ) {
        // ... (inchangé)
        int smithWidth = 6;
        int smithDepth = 5;
        int height = 4;
        for (int x = 0; x < smithWidth; x++) {
            for (int z = 0; z < smithDepth; z++) {
                setBlock(world, startX + x, startY, startZ + z, Material.COBBLESTONE);
            }
        }
        for (int y = 1; y <= height; y++) {
            for (int x = 0; x < smithWidth; x++) {
                setBlock(world, startX + x, startY + y, startZ + smithDepth - 1, Material.OAK_PLANKS);
            }
            for (int z = 0; z < smithDepth; z++) {
                setBlock(world, startX, startY + y, startZ + z, Material.OAK_PLANKS);
            }
        }
        for (int x = -1; x <= smithWidth; x++) {
            for (int z = -1; z <= smithDepth; z++) {
                setBlock(world, startX + x, startY + height + 1, startZ + z, Material.COBBLESTONE_SLAB);
            }
        }

        // Fenêtres
        int wx = startX + smithWidth / 2;
        int wz = startZ + smithDepth / 2;
        setBlock(world, wx, startY + 2, startZ, Material.GLASS_PANE);
        setBlock(world, wx, startY + 2, startZ + smithDepth - 1, Material.GLASS_PANE);
        setBlock(world, startX, startY + 2, wz, Material.GLASS_PANE);
        setBlock(world, startX + smithWidth - 1, startY + 2, wz, Material.GLASS_PANE);
        world.getBlockAt(startX + smithWidth - 2, startY + 1, startZ + smithDepth - 2).setType(Material.FURNACE);
        BlockData furnaceData = world.getBlockAt(startX + smithWidth - 2, startY + 1, startZ + smithDepth - 2).getBlockData();
        if (furnaceData instanceof Furnace furnace) {
            furnace.setFacing(getFacingNorth());
            world.getBlockAt(startX + smithWidth - 2, startY + 1, startZ + smithDepth - 2).setBlockData(furnace);
        }
        setBlock(world, startX + smithWidth - 1, startY + 1, startZ + smithDepth - 2, Material.ANVIL);
    }

    private void buildWatchTower(World world, int startX, int startY, int startZ) {
        // ... (inchangé)
        int towerHeight = 8;
        int towerSide = 5;
        for (int x = 0; x < towerSide; x++) {
            for (int z = 0; z < towerSide; z++) {
                setBlock(world, startX + x, startY, startZ + z, Material.COBBLESTONE);
            }
        }
        for (int y = 1; y <= towerHeight; y++) {
            for (int x = 0; x < towerSide; x++) {
                for (int z = 0; z < towerSide; z++) {
                    if (x == 0 || x == towerSide - 1 || z == 0 || z == towerSide - 1) {
                        setBlock(world, startX + x, startY + y, startZ + z, Material.COBBLESTONE);
                    }
                }
            }
        }
        setBlock(world, startX + towerSide / 2, startY + 1, startZ, Material.AIR);
        setBlock(world, startX + towerSide / 2, startY + 2, startZ, Material.AIR);
        placeDoor(world, startX + towerSide / 2, startY + 1, startZ);
        int topY = startY + towerHeight;
        for (int x = 0; x < towerSide; x++) {
            for (int z = 0; z < towerSide; z++) {
                setBlock(world, startX + x, topY, startZ + z, Material.SPRUCE_PLANKS);
            }
        }
        for (int x = 0; x < towerSide; x++) {
            for (int z = 0; z < towerSide; z++) {
                if (x == 0 || x == towerSide - 1 || z == 0 || z == towerSide - 1) {
                    setBlock(world, startX + x, topY + 1, startZ + z, Material.COBBLESTONE_WALL);
                }
            }
        }
        for (int y = 1; y <= towerHeight - 1; y++) {
            world.getBlockAt(startX + 1, startY + y, startZ + 2).setType(Material.LADDER, false);
        }
    }

    private void buildMarketStall(World world, int startX, int startY, int startZ) {
        // ... (inchangé)
        int stallWidth = 4;
        int stallDepth = 3;
        for (int x = 0; x < stallWidth; x++) {
            for (int z = 0; z < stallDepth; z++) {
                setBlock(world, startX + x, startY, startZ + z, Material.OAK_PLANKS);
            }
        }
        setBlock(world, startX,               startY + 1, startZ,               Material.OAK_FENCE);
        setBlock(world, startX,               startY + 1, startZ + stallDepth - 1, Material.OAK_FENCE);
        setBlock(world, startX + stallWidth - 1, startY + 1, startZ,               Material.OAK_FENCE);
        setBlock(world, startX + stallWidth - 1, startY + 1, startZ + stallDepth - 1, Material.OAK_FENCE);

        Material[] pattern = { Material.WHITE_WOOL, Material.RED_WOOL };
        for (int x = 0; x < stallWidth; x++) {
            for (int z = 0; z < stallDepth; z++) {
                int colorIndex = (x + z) % 2;
                setBlock(world, startX + x, startY + 2, startZ + z, pattern[colorIndex]);
            }
        }

        Location vendorLoc = new Location(world, startX + stallWidth / 2, startY + 1, startZ + stallDepth / 2);
        Villager v = (Villager) world.spawnEntity(vendorLoc, EntityType.VILLAGER);
        v.setCustomName("Marchand");
        v.setCustomNameVisible(true);
    }

    private void buildTavern(World world, int startX, int startY, int startZ) {
        // ... (inchangé)
        int tavernWidth = 10;
        int tavernDepth = 8;
        int wallHeight = 5;
        for (int x = 0; x < tavernWidth; x++) {
            for (int z = 0; z < tavernDepth; z++) {
                setBlock(world, startX + x, startY, startZ + z, Material.COBBLESTONE);
            }
        }
        for (int y = 1; y <= wallHeight; y++) {
            for (int x = 0; x < tavernWidth; x++) {
                for (int z = 0; z < tavernDepth; z++) {
                    boolean isEdge = (x == 0 || x == tavernWidth - 1 || z == 0 || z == tavernDepth - 1);
                    if (isEdge) {
                        setBlock(world, startX + x, startY + y, startZ + z, Material.OAK_PLANKS);
                    }
                }
            }
        }

        // Fenêtres simples
        int midX = startX + tavernWidth / 2;
        int midZ = startZ + tavernDepth / 2;
        for (int x = 2; x <= tavernWidth - 3; x += 3) {
            setBlock(world, startX + x, startY + 2, startZ, Material.GLASS_PANE);
            setBlock(world, startX + x, startY + 2, startZ + tavernDepth - 1, Material.GLASS_PANE);
        }
        for (int z = 2; z <= tavernDepth - 3; z += 3) {
            setBlock(world, startX, startY + 2, startZ + z, Material.GLASS_PANE);
            setBlock(world, startX + tavernWidth - 1, startY + 2, startZ + z, Material.GLASS_PANE);
        }
        int roofStartY = startY + wallHeight + 1;
        buildFinishedRoofWithChimney(world, startX, roofStartY, startZ, tavernWidth, tavernDepth);

        // Porte (ouverture élargie)
        setBlock(world, startX + tavernWidth / 2, startY + 1, startZ, Material.AIR);
        setBlock(world, startX + tavernWidth / 2, startY + 2, startZ, Material.AIR);
        placeDoor(world, startX + tavernWidth / 2, startY + 1, startZ);

        // Décor intérieur (comptoir, baril, tables)
        for (int i = 0; i < 3; i++) {
            setBlock(world, startX + 2 + i, startY + 1, startZ + 2, Material.OAK_FENCE);
            setBlock(world, startX + 2 + i, startY + 2, startZ + 2, Material.SPRUCE_TRAPDOOR);
        }
        setBlock(world, startX + 3, startY + 1, startZ + 1, Material.BARREL);
        for (int i = 0; i < 2; i++) {
            setBlock(world, startX + 6 + i * 2, startY + 1, startZ + 3, Material.OAK_FENCE);
            setBlock(world, startX + 6 + i * 2, startY + 2, startZ + 3, Material.SPRUCE_PRESSURE_PLATE);
        }
        setBlock(world, startX + 1, startY + 3, startZ + 1, Material.WALL_TORCH);
        setBlock(world, startX + tavernWidth - 2, startY + 3, startZ + tavernDepth - 2, Material.WALL_TORCH);
    }

    private void buildDecorativePond(World world, int centerX, int centerY, int centerZ) {
        // ... (inchangé)
        int radius = 4;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                double dist = Math.sqrt(x * x + z * z);
                if (dist <= radius) {
                    setBlock(world, centerX + x, centerY, centerZ + z, Material.WATER);
                }
            }
        }
        for (int x = -radius - 1; x <= radius + 1; x++) {
            for (int z = -radius - 1; z <= radius + 1; z++) {
                double dist = Math.sqrt(x * x + z * z);
                if (dist > radius && dist < radius + 2) {
                    if ((x + z) % 3 == 0) {
                        setBlock(world, centerX + x, centerY + 1, centerZ + z, Material.ROSE_BUSH);
                    } else if ((x + z) % 3 == 1) {
                        setBlock(world, centerX + x, centerY + 1, centerZ + z, Material.TALL_GRASS);
                    }
                }
            }
        }
    }

    private void buildFlowerGarden(World world, int startX, int startY, int startZ) {
        // ... (inchangé)
        int gardenWidth = 6;
        int gardenDepth = 6;
        for (int x = 0; x < gardenWidth; x++) {
            for (int z = 0; z < gardenDepth; z++) {
                setBlock(world, startX + x, startY, startZ + z, Material.GRASS_BLOCK);
            }
        }
        Material[] flowers = {
                Material.DANDELION,
                Material.POPPY,
                Material.BLUE_ORCHID,
                Material.ALLIUM,
                Material.AZURE_BLUET,
                Material.ORANGE_TULIP,
                Material.PINK_TULIP
        };
        for (int x = 0; x < gardenWidth; x++) {
            for (int z = 0; z < gardenDepth; z++) {
                if ((x + z) % 2 == 0) {
                    int index = (x * z) % flowers.length;
                    setBlock(world, startX + x, startY + 1, startZ + z, flowers[index]);
                }
            }
        }
    }

    private void buildRoads(World world, int baseX, int baseY, int baseZ, int gap) {
        // ... (inchangé) chemin 3 blocs de large en croix
        int halfWidth = 1;
        for (int dx = -gap; dx <= gap; dx++) {
            for (int w = -halfWidth; w <= halfWidth; w++) {
                setBlock(world, baseX + dx, baseY, baseZ + w, Material.GRAVEL);
            }
        }
        for (int dz = -gap; dz <= gap; dz++) {
            for (int w = -halfWidth; w <= halfWidth; w++) {
                setBlock(world, baseX + w, baseY, baseZ + dz, Material.GRAVEL);
            }
        }

        // Lampadaires
        for (int dx = -gap; dx <= gap; dx += 6) {
            for (int w = -halfWidth; w <= halfWidth; w++) {
                buildLampPost(world, baseX + dx, baseY + 1, baseZ + w);
            }
        }
        for (int dz = -gap; dz <= gap; dz += 6) {
            for (int w = -halfWidth; w <= halfWidth; w++) {
                buildLampPost(world, baseX + w, baseY + 1, baseZ + dz);
            }
        }
    }

    /* ----------------------------------------------------------------------
       C) Chemin d'accès "en L" entre le centre (x0,z0) et la porte (x1,z1)
    ---------------------------------------------------------------------- */
    private void buildPath(World world,
                           int x0, int z0,
                           int x1, int z1,
                           int baseY,
                           int halfWidth) {
        // On va créer un chemin "en L" : on bouge en X, puis en Z.
        // Et on le fait large de (2*halfWidth + 1).
        int dx = (x1 > x0) ? 1 : -1;
        if (x1 == x0) dx = 0;

        // 1) On avance en X jusqu'à x1
        int currentX = x0;
        while (currentX != x1) {
            for (int w = -halfWidth; w <= halfWidth; w++) {
                setBlock(world, currentX, baseY, z0 + w, Material.GRAVEL);
            }
            currentX += dx;
        }

        // 2) On avance en Z jusqu'à z1
        int dz = (z1 > z0) ? 1 : -1;
        if (z1 == z0) dz = 0;
        int currentZ = z0;
        while (currentZ != z1) {
            for (int w = -halfWidth; w <= halfWidth; w++) {
                setBlock(world, x1 + w, baseY, currentZ, Material.GRAVEL);
            }
            currentZ += dz;
        }
        // Et on place enfin un bloc GRAVEL à la porte
        for (int w = -halfWidth; w <= halfWidth; w++) {
            setBlock(world, x1 + w, baseY, z1, Material.GRAVEL);
        }
    }

    /* ----------------------------------------------------------------------
       D) Arbre customisé et panneaux
    ---------------------------------------------------------------------- */
    private void buildCustomTree(World world, int x, int y, int z) {
        // ... (inchangé)
        for (int i = 0; i < 4; i++) {
            setBlock(world, x, y + i, z, Material.OAK_LOG);
        }
        int leavesStart = y + 3;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                setBlock(world, x + dx, leavesStart,     z + dz, Material.OAK_LEAVES);
                setBlock(world, x + dx, leavesStart + 1, z + dz, Material.OAK_LEAVES);
            }
        }
    }

    private void buildSign(World world,
                           int x,
                           int y,
                           int z,
                           BlockFace facing,
                           String text) {
        // ... (inchangé)
        Location loc = new Location(world, x, y, z);
        loc.getBlock().setType(Material.OAK_WALL_SIGN, false);
        BlockData data = loc.getBlock().getBlockData();
        if (data instanceof Sign signData) {
            signData.setRotation(facing);
            loc.getBlock().setBlockData(signData, false);
        }
        org.bukkit.block.Sign state = (org.bukkit.block.Sign) loc.getBlock().getState();
        state.setLine(0, text);
        state.update(false, false);
    }

    /* ----------------------------------------------------------------------
       Méthodes de placement utilitaires
    ---------------------------------------------------------------------- */

    /** Place un bloc de manière immédiate, sans effet de physique. */
    private void setBlock(World w, int x, int y, int z, Material mat) {
        w.getBlockAt(x, y, z).setType(mat, false);
    }

    /** Place une porte orientée vers le sud. */
    private void placeDoor(World world, int x, int y, int z) {
        Location bottom = new Location(world, x, y, z);
        Location top = new Location(world, x, y + 1, z);

        bottom.getBlock().setType(Material.OAK_DOOR, false);
        BlockData doorDataBottom = bottom.getBlock().getBlockData();
        if (doorDataBottom instanceof Door door) {
            door.setHalf(Door.Half.BOTTOM);
            door.setFacing(BlockFace.SOUTH);
            bottom.getBlock().setBlockData(door, false);
        }

        top.getBlock().setType(Material.OAK_DOOR, false);
        BlockData doorDataTop = top.getBlock().getBlockData();
        if (doorDataTop instanceof Door door) {
            door.setHalf(Door.Half.TOP);
            door.setFacing(BlockFace.SOUTH);
            top.getBlock().setBlockData(door, false);
        }
    }

    /** Place des escaliers (Stairs) avec la bonne orientation. */
    private void placeStairs(World world,
                             int x,
                             int y,
                             int z,
                             Material material,
                             Stairs.Shape shape,
                             BlockFace facing) {
        Location loc = new Location(world, x, y, z);
        loc.getBlock().setType(material, false);

        BlockData data = loc.getBlock().getBlockData();
        if (data instanceof Stairs stairs) {
            stairs.setShape(shape);
            stairs.setFacing(facing);
            loc.getBlock().setBlockData(stairs, false);
        }
    }

    /** Construit un lampadaire simple. */
    private void buildLampPost(World world, int x, int y, int z) {
        setBlock(world, x, y,     z, Material.OAK_FENCE);
        setBlock(world, x, y + 1, z, Material.OAK_FENCE);
        setBlock(world, x, y + 2, z, Material.OAK_FENCE);
        setBlock(world, x, y + 3, z, Material.OAK_FENCE);
        setBlock(world, x, y + 4, z, Material.LANTERN);
    }

    // Orientations utilitaires
    private BlockFace getFacingNorth() {
        return BlockFace.NORTH;
    }
    private BlockFace getFacingSouth() {
        return BlockFace.SOUTH;
    }
    private BlockFace getFacingEast() {
        return BlockFace.EAST;
    }
    private BlockFace getFacingWest() {
        return BlockFace.WEST;
    }
}
