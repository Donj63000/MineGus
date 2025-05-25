package org.example;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Commande /village : génère un village "amélioré" de PNJ,
 * avec une construction asynchrone (pour éviter le lag) et
 * diverses améliorations (toits pignons, routes texturées, lampadaires, etc.).
 *
 * + Commande "/village undo" possible pour supprimer ce qu'on a construit.
 */
public final class Village implements CommandExecutor {

    private final JavaPlugin plugin;

    // Ajout du générateur aléatoire pour la méthode pickRoadMaterial
    private static final Random RNG = new Random();

    /**
     * On stocke les blocs posés (positions) pour autoriser un /village undo.
     * Dans une vraie implémentation, on conserverait aussi l'ancien matériau
     * pour tout restaurer à l'identique. Ici, on simplifie en mettant l'air.
     */
    private final List<Location> placedBlocks = new ArrayList<>();

    /**
     * Configuration (remplace un config.yml).
     * On pourrait lire un .yml via plugin.getConfig().
     */
    private final Map<String,Object> config = new HashMap<>();

    public Village(JavaPlugin plugin) {
        this.plugin = plugin;

        // Enregistrement de la commande /village
        if (plugin.getCommand("village") != null) {
            plugin.getCommand("village").setExecutor(this);
        }

        // Petite config manuelle (exemple)
        config.put("houseWidth", 9);
        config.put("houseDepth", 9);
        config.put("roadWidth", 3);
        // Espacement entre chaque maison pour la grille
        config.put("houseSpacing", 15);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("village")) {
            return false;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Seulement pour les joueurs en jeu.");
            return true;
        }

        // Sous-commande "undo"
        if (args.length >= 1 && args[0].equalsIgnoreCase("undo")) {
            undoVillage();
            sender.sendMessage(ChatColor.YELLOW + "Village supprimé (blocs mis à AIR).");
            return true;
        }

        // Sinon, on génère le village
        Location loc = player.getLocation();
        generateVillageAsync(loc);
        sender.sendMessage(ChatColor.GREEN + "Construction du village lancée (asynchrone) !");
        return true;
    }

    /**
     * Méthode asynchrone : on prépare des tasks (Runnable) dans une file,
     * puis on les exécute par batch de 200, tous les 1 tick,
     * pour éviter de placer des milliers de blocs dans le même tick.
     */
    private void generateVillageAsync(Location center) {
        World world = center.getWorld();
        if (world == null) return;

        // 1) Prépare la liste de toutes les actions de construction
        Queue<Runnable> actions = new LinkedList<>();

        // Configuration de la grille
        int houseW = (int) config.get("houseWidth");
        int houseD = (int) config.get("houseDepth");
        int spacing = (int) config.get("houseSpacing");
        int roads = (int) config.get("roadWidth");

        int rows = 4; // 20 maisons => 4 x 5
        int cols = 5;
        int offsetX = -((cols - 1) * spacing) / 2;
        int offsetZ = -((rows - 1) * spacing) / 2;

        int baseY = center.getBlockY();

        // Détermine la zone totale pour dégager le terrain et placer l'herbe
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int hx = center.getBlockX() + offsetX + c * spacing;
                int hz = center.getBlockZ() + offsetZ + r * spacing;

                int rotation;
                if (r == 0) {
                    rotation = 180; // rangée nord => maisons face au sud
                } else if (r == rows - 1) {
                    rotation = 0;   // rangée sud => maisons face au nord
                } else if (c < cols / 2) {
                    rotation = 90;  // à gauche => face à l'est
                } else {
                    rotation = 270; // à droite => face à l'ouest
                }

                int[] bounds = computeHouseBounds(hx, hz, houseW, houseD, rotation);
                minX = Math.min(minX, bounds[0]);
                maxX = Math.max(maxX, bounds[1]);
                minZ = Math.min(minZ, bounds[2]);
                maxZ = Math.max(maxZ, bounds[3]);
            }
        }

        // Inclut la place centrale
        minX = Math.min(minX, center.getBlockX());
        maxX = Math.max(maxX, center.getBlockX() + 3);
        minZ = Math.min(minZ, center.getBlockZ());
        maxZ = Math.max(maxZ, center.getBlockZ() + 3);

        // 1) Prépare la liste de toutes les actions de construction
        // Dégage la zone et ajoute un sol d'herbe
        actions.addAll(prepareGroundActions(world, minX, maxX, minZ, maxZ, baseY));

        // 2) Ajoute la construction de la place centrale (puits + cloche)
        actions.addAll(buildWellActions(world, center.clone().add(0,0,0)));
        actions.add(() -> placeBell(world, center.clone().add(1,1,0)));

        // 3) Génère les maisons en grille autour de la place

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int hx = center.getBlockX() + offsetX + c * spacing;
                int hz = center.getBlockZ() + offsetZ + r * spacing;

                int rotation;
                if (r == 0) {
                    rotation = 180; // rangée nord => maisons face au sud
                } else if (r == rows - 1) {
                    rotation = 0;   // rangée sud => maisons face au nord
                } else if (c < cols / 2) {
                    rotation = 90;  // à gauche => face à l'est
                } else {
                    rotation = 270; // à droite => face à l'ouest
                }

                Location houseLoc = new Location(world, hx, center.getBlockY(), hz);
                actions.addAll(buildHouseRotatedActions(world, houseLoc, houseW, houseD, rotation));
                actions.addAll(buildRoadActions(
                        world,
                        center.getBlockX(), center.getBlockZ(),
                        hx, hz,
                        center.getBlockY(),
                        roads
                ));

                // Lampadaire devant chaque maison
                int lampX = hx;
                int lampZ = hz;
                switch (rotation) {
                    case 0 -> lampZ -= houseD / 2 + 1;
                    case 180 -> lampZ += houseD / 2 + 1;
                    case 90 -> lampX -= houseW / 2 + 1;
                    case 270 -> lampX += houseW / 2 + 1;
                }
                int lampY = center.getBlockY() + 1;
                actions.addAll(buildLampPostActions(world, lampX, lampY, lampZ));
            }
        }

        // 4) Ajoute un PNJ sur la place
        actions.add(() -> spawnVillager(world, center.clone().add(2,1,1), "Villageois"));

        // Spawners de golem aux extrémités
        actions.add(createSpawnerAction(world, minX, baseY + 1, minZ, EntityType.IRON_GOLEM));
        actions.add(createSpawnerAction(world, maxX, baseY + 1, minZ, EntityType.IRON_GOLEM));
        actions.add(createSpawnerAction(world, minX, baseY + 1, maxZ, EntityType.IRON_GOLEM));
        actions.add(createSpawnerAction(world, maxX, baseY + 1, maxZ, EntityType.IRON_GOLEM));

        // On pourrait enchaîner : marché, arbres, etc. … en ajoutant d'autres actions.

        // 5) Lance un scheduler qui place 200 blocs par tick
        buildActionsInBatches(actions, 200);
    }

    /**
     * Exécute les actions par lot (batchSize par tick).
     */
    private void buildActionsInBatches(Queue<Runnable> actions, int batchSize) {
        new BukkitRunnable() {
            @Override
            public void run() {
                int count = 0;
                while (!actions.isEmpty() && count < batchSize) {
                    Runnable task = actions.poll();
                    if (task == null) break;
                    task.run();
                    count++;
                }
                if (actions.isEmpty()) {
                    // Fini
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Suppression (mise à AIR) de tous les blocs qu'on a posés
     * pour un "undo" simplifié.
     */
    private void undoVillage() {
        for (Location loc : placedBlocks) {
            loc.getBlock().setType(Material.AIR, false);
        }
        placedBlocks.clear();
    }

    /* ==========================================================
        EXEMPLES DE FONCTIONS renvoyant des "actions"
       (liste de Runnables) au lieu de placer tout de suite.
       On stocke tout dans placedBlocks pour undo.
     ========================================================== */

    /** Construit un puits simple, renvoie la liste d'actions (Runnables). */
    private List<Runnable> buildWellActions(World world, Location origin) {
        List<Runnable> result = new ArrayList<>();

        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();

        int size = 4;
        // Pose de la base (4x4) en Cobblestone
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                int fx = ox + dx;
                int fz = oz + dz;
                // Chaque bloc => un Runnable
                result.add(() -> setBlockTracked(world, fx, oy, fz, Material.COBBLESTONE));
            }
        }
        // Eau au centre (2x2)
        for (int dx = 1; dx <= 2; dx++) {
            for (int dz = 1; dz <= 2; dz++) {
                int fx = ox + dx;
                int fz = oz + dz;
                result.add(() -> setBlockTracked(world, fx, oy, fz, Material.WATER));
            }
        }

        // Quatre spawners : 2 villageois, 2 golems
        int[][] spawnerPos = {{1,1}, {2,1}, {1,2}, {2,2}};
        EntityType[] types = {
                EntityType.VILLAGER,
                EntityType.VILLAGER,
                EntityType.IRON_GOLEM,
                EntityType.IRON_GOLEM
        };
        for (int i = 0; i < spawnerPos.length; i++) {
            int fx = ox + spawnerPos[i][0];
            int fz = oz + spawnerPos[i][1];
            result.add(createSpawnerAction(world, fx, oy, fz, types[i]));
        }
        // Piliers cobblestone
        for (int dy = 1; dy <= 3; dy++) {
            final int Y = oy + dy;
            result.add(() -> setBlockTracked(world, ox,         Y, oz,         Material.COBBLESTONE));
            result.add(() -> setBlockTracked(world, ox+size-1,  Y, oz,         Material.COBBLESTONE));
            result.add(() -> setBlockTracked(world, ox,         Y, oz+size-1,  Material.COBBLESTONE));
            result.add(() -> setBlockTracked(world, ox+size-1,  Y, oz+size-1,  Material.COBBLESTONE));
        }
        // Toit (slab) au niveau 4
        int roofY = oy + 4;
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                final int fx = ox + dx;
                final int fz = oz + dz;
                result.add(() -> setBlockTracked(world, fx, roofY, fz, Material.COBBLESTONE_SLAB));
            }
        }
        return result;
    }

    /**
     * Place une cloche (Bell).
     */
    private void placeBell(World world, Location loc) {
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        setBlockTracked(world, x, y, z, Material.BELL);
    }

    /**
     * Construit une maison “classique” (sans rotation).
     */
    private List<Runnable> buildHouseActions(World world,
                                             Location start,
                                             int width,
                                             int depth,
                                             BlockFace frontFace) {
        List<Runnable> result = new ArrayList<>();
        int ox = start.getBlockX();
        int oy = start.getBlockY();
        int oz = start.getBlockZ();

        int wallHeight = 4; // Hauteur du mur

        // Murs + coins avec fenêtres
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int fx = ox + x;
                int fz = oz + z;
                boolean edgeX = (x == 0 || x == width-1);
                boolean edgeZ = (z == 0 || z == depth-1);
                if (edgeX || edgeZ) {
                    for (int y = 0; y < wallHeight; y++) {
                        int fy = oy + 1 + y;
                        boolean corner = (edgeX && edgeZ);
                        Material mat = corner ? Material.OAK_LOG : Material.OAK_PLANKS;

                        // Fenêtres simples au milieu des murs
                        boolean windowLayer = (y == 1);
                        boolean frontBack = edgeZ && !corner && (x == 2 || x == width-3);
                        boolean sides = edgeX && !corner && (z == 2 || z == depth-3);
                        if (windowLayer && (frontBack || sides)) {
                            result.add(() -> setBlockTracked(world, fx, fy, fz, Material.GLASS_PANE));
                        } else {
                            result.add(() -> setBlockTracked(world, fx, fy, fz, mat));
                        }
                    }
                }
            }
        }

        // Sol + lit
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int fx = ox + x;
                int fz = oz + z;
                int fy = oy;
                result.add(() -> setBlockTracked(world, fx, fy, fz, Material.SPRUCE_PLANKS));
            }
        }

        // Porte (devant => z=0)
        int doorX = ox + width/2;
        int doorZ = oz;
        result.add(() -> setBlockTracked(world, doorX, oy+1, doorZ, Material.OAK_DOOR));
        result.add(() -> setBlockTracked(world, doorX, oy+2, doorZ, Material.OAK_DOOR));

        // Mobilier basique
        result.add(() -> setBlockTracked(world, ox+2, oy+1, oz+2, Material.WHITE_BED));
        result.add(() -> setBlockTracked(world, ox+width-3, oy+1, oz+2, Material.CRAFTING_TABLE));
        result.add(() -> setBlockTracked(world, ox+width-3, oy+1, oz+depth-3, Material.CHEST));

        // Toit en pignon
        int roofBaseY = oy + wallHeight + 1;
        result.addAll(buildPignonRoofActions(world, ox, roofBaseY, oz, width, depth));

        return result;
    }

    /**
     * Construit une maison en la "tournant" de 90°, 180°, etc.
     */
    private List<Runnable> buildHouseRotatedActions(World world,
                                                    Location start,
                                                    int width,
                                                    int depth,
                                                    int rotationDegrees) {
        List<Runnable> result = new ArrayList<>();
        int ox = start.getBlockX();
        int oy = start.getBlockY();
        int oz = start.getBlockZ();

        int wallHeight = 4;

        // Murs avec fenêtres
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                boolean edgeX = (dx == 0 || dx == width-1);
                boolean edgeZ = (dz == 0 || dz == depth-1);
                if (edgeX || edgeZ) {
                    for (int h = 0; h < wallHeight; h++) {
                        final int fy = oy + 1 + h;
                        Material mat = (edgeX && edgeZ) ? Material.SPRUCE_LOG : Material.BIRCH_PLANKS;
                        int[] rpos = rotateCoord(dx, dz, rotationDegrees);
                        final int fx = ox + rpos[0];
                        final int fz = oz + rpos[1];

                        boolean windowLayer = (h == 1);
                        boolean frontBack = edgeZ && !edgeX && (dx == 2 || dx == width-3);
                        boolean sides = edgeX && !edgeZ && (dz == 2 || dz == depth-3);
                        if (windowLayer && (frontBack || sides)) {
                            result.add(() -> setBlockTracked(world, fx, fy, fz, Material.GLASS_PANE));
                        } else {
                            result.add(() -> setBlockTracked(world, fx, fy, fz, mat));
                        }
                    }
                }
            }
        }

        // Sol
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                int[] rpos = rotateCoord(dx, dz, rotationDegrees);
                final int fx = ox + rpos[0];
                final int fz = oz + rpos[1];
                result.add(() -> setBlockTracked(world, fx, oy, fz, Material.SPRUCE_PLANKS));
            }
        }

        // Porte
        int doorX = width / 2;
        int doorZ = 0;
        int[] dpos1 = rotateCoord(doorX, doorZ, rotationDegrees);
        final int rx1 = ox + dpos1[0];
        final int rz1 = oz + dpos1[1];
        result.add(() -> setBlockTracked(world, rx1, oy+1, rz1, Material.OAK_DOOR));
        result.add(() -> setBlockTracked(world, rx1, oy+2, rz1, Material.OAK_DOOR));

        // Mobilier
        int[] bedPos = rotateCoord(2, 2, rotationDegrees);
        result.add(() -> setBlockTracked(world, ox + bedPos[0], oy+1, oz + bedPos[1], Material.WHITE_BED));
        int[] tablePos = rotateCoord(width-3, 2, rotationDegrees);
        result.add(() -> setBlockTracked(world, ox + tablePos[0], oy+1, oz + tablePos[1], Material.CRAFTING_TABLE));
        int[] chestPos = rotateCoord(width-3, depth-3, rotationDegrees);
        result.add(() -> setBlockTracked(world, ox + chestPos[0], oy+1, oz + chestPos[1], Material.CHEST));

        // Toit
        int roofBaseY = oy + wallHeight + 1;
        result.addAll(buildPignonRoofActionsRotated(world, ox, oz, roofBaseY, width, depth, rotationDegrees));

        return result;
    }

    /** Rotation 2D autour de (0,0). (x,z)->(z,-x) si angle=90°. */
    private int[] rotateCoord(int dx, int dz, int angleDegrees) {
        switch(angleDegrees) {
            case 90:
                return new int[]{ dz, -dx };
            case 180:
                return new int[]{ -dx, -dz };
            case 270:
                return new int[]{ -dz, dx };
            default:
                return new int[]{ dx, dz };
        }
    }

    /**
     * Toit pignon simple (X=large, Z=profondeur), extrémités OUTER corners.
     */
    private List<Runnable> buildPignonRoofActions(World w,
                                                  int startX,
                                                  int startY,
                                                  int startZ,
                                                  int width,
                                                  int depth) {
        List<Runnable> actions = new ArrayList<>();
        int layers = width / 2;
        for (int layer = 0; layer < layers; layer++) {
            final int y = startY + layer;
            int x1 = startX + layer;
            int x2 = startX + width - 1 - layer;
            int z1 = startZ;
            int z2 = startZ + depth - 1;

            // Bord avant
            for (int x = x1; x <= x2; x++) {
                boolean leftCorner = (x == x1);
                boolean rightCorner = (x == x2);
                final int fx = x, fz = z1;
                actions.add(() -> placeBorderStair(w, fx, y, fz, BlockFace.NORTH, leftCorner, rightCorner));
            }
            // Bord arrière
            for (int x = x1; x <= x2; x++) {
                boolean leftCorner = (x == x1);
                boolean rightCorner = (x == x2);
                final int fx = x, fz = z2;
                actions.add(() -> placeBorderStair(w, fx, y, fz, BlockFace.SOUTH, leftCorner, rightCorner));
            }
            // Remplissage
            for (int fx = x1+1; fx <= x2-1; fx++) {
                for (int fz = z1+1; fz <= z2-1; fz++) {
                    final int X = fx, Z = fz;
                    actions.add(() -> setBlockTracked(w, X, y, Z, Material.SPRUCE_PLANKS));
                }
            }
        }
        return actions;
    }

    /**
     * Variante pignon pour la maison pivotée.
     */
    private List<Runnable> buildPignonRoofActionsRotated(World w,
                                                         int ox, int oz,
                                                         int startY,
                                                         int width,
                                                         int depth,
                                                         int angleDeg) {
        List<Runnable> actions = new ArrayList<>();
        int layers = width / 2;
        for (int layer = 0; layer < layers; layer++) {
            final int Y = startY + layer;
            int x1 = layer;
            int x2 = width - 1 - layer;
            int z1 = 0;
            int z2 = depth - 1;

            // Avant
            for (int x = x1; x <= x2; x++) {
                boolean leftCorner = (x == x1);
                boolean rightCorner = (x == x2);
                int[] rpos = rotateCoord(x, z1, angleDeg);
                final int fx = ox + rpos[0];
                final int fz = oz + rpos[1];
                BlockFace face = getRotatedFace(BlockFace.NORTH, angleDeg);
                actions.add(() -> placeBorderStair(w, fx, Y, fz, face, leftCorner, rightCorner));
            }
            // Arrière
            for (int x = x1; x <= x2; x++) {
                boolean leftCorner = (x == x1);
                boolean rightCorner = (x == x2);
                int[] rpos = rotateCoord(x, z2, angleDeg);
                final int fx = ox + rpos[0];
                final int fz = oz + rpos[1];
                BlockFace face = getRotatedFace(BlockFace.SOUTH, angleDeg);
                actions.add(() -> placeBorderStair(w, fx, Y, fz, face, leftCorner, rightCorner));
            }
            // Remplissage
            for (int xx = x1+1; xx <= x2-1; xx++) {
                for (int zz = z1+1; zz <= z2-1; zz++) {
                    int[] rpos = rotateCoord(xx, zz, angleDeg);
                    final int fx = ox + rpos[0];
                    final int fz = oz + rpos[1];
                    final int X = fx, Z = fz, Yf = Y;
                    actions.add(() -> setBlockTracked(w, X, Yf, Z, Material.SPRUCE_PLANKS));
                }
            }
        }
        return actions;
    }

    /**
     * Place un escalier + OUTER_LEFT/RIGHT en coin.
     */
    private void placeBorderStair(World w,
                                  int x,
                                  int y,
                                  int z,
                                  BlockFace facing,
                                  boolean cornerLeft,
                                  boolean cornerRight) {
        setBlockTracked(w, x, y, z, Material.SPRUCE_STAIRS);
        Block b = w.getBlockAt(x,y,z);
        if (b.getType() != Material.SPRUCE_STAIRS) return;

        Stairs s = (Stairs) b.getBlockData();
        s.setFacing(facing);
        if (cornerLeft) {
            s.setShape(Stairs.Shape.OUTER_LEFT);
        }
        if (cornerRight) {
            s.setShape(Stairs.Shape.OUTER_RIGHT);
        }
        b.setBlockData(s, false);
    }

    /** Gère la rotation du facing (ex. N->E->S->W). */
    private BlockFace getRotatedFace(BlockFace original, int angleDeg) {
        List<BlockFace> cycle = Arrays.asList(
                BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST
        );
        int idx = cycle.indexOf(original);
        if (idx < 0) return original;
        int steps = angleDeg / 90;
        int newIdx = (idx + steps) % 4;
        return cycle.get((newIdx + 4) % 4);
    }

    /**
     * Routes texturées : 60% GRAVEL, 25% DIRT_PATH, 15% COARSE_DIRT.
     * On fait un simple chemin en L.
     */
    private List<Runnable> buildRoadActions(World w,
                                            int x0, int z0,
                                            int x1, int z1,
                                            int baseY,
                                            int halfWidth) {
        List<Runnable> actions = new ArrayList<>();

        int dx = (x1 >= x0) ? 1 : -1;
        int cx = x0;
        int step = 0;
        while (cx != x1) {
            for (int woff = -halfWidth; woff <= halfWidth; woff++) {
                final int fx = cx;
                final int fz = z0 + woff;
                actions.add(() -> setBlockTracked(w, fx, baseY, fz, pickRoadMaterial(RNG)));
                if (woff == 0 && step % 4 == 0) {
                    actions.add(() -> setBlockTracked(w, fx, baseY + 1, fz, Material.TORCH));
                }
            }
            cx += dx;
            step++;
        }

        int dz = (z1 >= z0) ? 1 : -1;
        int cz = z0;
        while (cz != z1) {
            for (int woff = -halfWidth; woff <= halfWidth; woff++) {
                final int fx = x1 + woff;
                final int fz = cz;
                actions.add(() -> setBlockTracked(w, fx, baseY, fz, pickRoadMaterial(RNG)));
                if (woff == 0 && step % 4 == 0) {
                    actions.add(() -> setBlockTracked(w, fx, baseY + 1, fz, Material.TORCH));
                }
            }
            cz += dz;
            step++;
        }

        // Dernière ligne
        for (int woff = -halfWidth; woff <= halfWidth; woff++) {
            final int fx = x1 + woff;
            final int fz = z1;
            actions.add(() -> setBlockTracked(w, fx, baseY, fz, pickRoadMaterial(RNG)));
            if (woff == 0 && step % 4 == 0) {
                final int tx = fx;
                final int tz = fz;
                actions.add(() -> setBlockTracked(w, tx, baseY + 1, tz, Material.TORCH));
            }
        }
        return actions;
    }

    private Material pickRoadMaterial(Random r) {
        int v = r.nextInt(100);
        if (v < 60) return Material.GRAVEL;
        if (v < 85) return Material.DIRT_PATH;
        return Material.COARSE_DIRT;
    }

    /**
     * Calcule le rectangle occupé par une maison en fonction de sa rotation.
     * @return tableau [minX, maxX, minZ, maxZ]
     */
    private int[] computeHouseBounds(int ox, int oz, int width, int depth, int angle) {
        return switch (angle) {
            case 0 -> new int[]{ox, ox + width - 1, oz, oz + depth - 1};
            case 90 -> new int[]{ox, ox + depth - 1, oz - width + 1, oz};
            case 180 -> new int[]{ox - width + 1, ox, oz - depth + 1, oz};
            case 270 -> new int[]{ox - depth + 1, ox, oz, oz + width - 1};
            default -> new int[]{ox, ox + width - 1, oz, oz + depth - 1};
        };
    }

    /**
     * Prépare les actions pour nettoyer la zone et placer un sol d'herbe.
     */
    private List<Runnable> prepareGroundActions(World w, int minX, int maxX, int minZ, int maxZ, int baseY) {
        List<Runnable> actions = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                final int fx = x;
                final int fz = z;
                actions.add(() -> {
                    for (int h = 1; h <= 10; h++) {
                        w.getBlockAt(fx, baseY + h, fz).setType(Material.AIR, false);
                    }
                    setBlockTracked(w, fx, baseY, fz, Material.GRASS_BLOCK);
                });
            }
        }
        return actions;
    }

    /**
     * Lampadaire : base = Polished Andesite Wall (ou fallback),
     * puis 2 chaînes + 1 lanterne (hauteur totale = 3).
     */
    private List<Runnable> buildLampPostActions(World w, int x, int y, int z) {
        List<Runnable> actions = new ArrayList<>();

        // Fallback si la version < 1.16 ne possède pas POLISHED_ANDESITE_WALL
        Material wallMat = Material.matchMaterial("POLISHED_ANDESITE_WALL");
        if (wallMat == null) {
            wallMat = Material.COBBLESTONE_WALL; // fallback
        }

        // Base
        Material finalWallMat = wallMat;
        actions.add(() -> setBlockTracked(w, x,     y,     z, finalWallMat));
        // 2 maillons de chaîne
        actions.add(() -> setBlockTracked(w, x,     y+1,   z, Material.CHAIN));
        actions.add(() -> setBlockTracked(w, x,     y+2,   z, Material.CHAIN));
        // Lanterne tout en haut
        actions.add(() -> setBlockTracked(w, x,     y+3,   z, Material.LANTERN));

        return actions;
    }

    /**
     * Crée une action plaçant un spawner configuré.
     */
    private Runnable createSpawnerAction(World w, int x, int y, int z, EntityType type) {
        return () -> {
            setBlockTracked(w, x, y, z, Material.SPAWNER);
            Block b = w.getBlockAt(x, y, z);
            if (b.getState() instanceof CreatureSpawner cs) {
                cs.setSpawnedType(type);
                cs.update();
            }
        };
    }

    /**
     * Fait spawn un PNJ villageois (ex: un vendeur ou habitant).
     */
    private void spawnVillager(World w, Location loc, String name) {
        w.getChunkAt(loc).load();
        Villager vil = (Villager) w.spawnEntity(loc, EntityType.VILLAGER);
        vil.setCustomName(name);
        vil.setCustomNameVisible(true);
        vil.setProfession(Villager.Profession.NONE);
    }

    /**
     * Place un bloc et l'enregistre dans placedBlocks.
     */
    private void setBlockTracked(World w, int x, int y, int z, Material mat) {
        Block b = w.getBlockAt(x, y, z);
        b.setType(mat, false);
        placedBlocks.add(b.getLocation());
    }
}
