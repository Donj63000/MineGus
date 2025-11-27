package org.example;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.example.village.VillageEntityManager;
import org.example.village.GateGuardManager;
import org.example.village.WallBuilder;
import org.example.village.Disposition;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;

/**
 * Commande :
 *   /village        – génère un village orthogonal (place, routes, maisons)
 *   /village undo   – supprime la dernière génération.
 */
public final class Village implements CommandExecutor {

    /* ────────────────────────── QUOTAS / CONSTANTES ────────────────────────── */
    private static final int VIL_SPAWNERS   = 4;   // exactement 4 spawners PNJ
    private static final int GOLEM_SPAWNERS = 2;   // deux générateurs de golems
    private static final int NPC_CAP        = 40;  // plafond global pour éviter le lag
    private static final int WALL_GAP       = 6;   // espace maisons ↔ muraille
    /* ───────────────────────────────────────────────────────────────────────── */

    private final Random rng = new Random();

    /* distribution des spawners PNJ */
    private Set<Integer> villagerSpawnerIdx = Collections.emptySet();
    private int currentHouseIdx = 0;

    /** Distribue les 4 spawners à intervalles réguliers dans la séquence de maisons. */
    public void prepareVillagerSpawnerDistribution(int totalHouses) {
        Set<Integer> chosen = new HashSet<>();
        if (totalHouses == 0) { villagerSpawnerIdx = chosen; return; }

        double step = (double) totalHouses / VIL_SPAWNERS;      // taille d’un quart
        for (int i = 0; i < VIL_SPAWNERS; i++) {
            int idx = (int) Math.round(i * step + step / 2 - 0.5);
            idx = Math.min(idx, totalHouses - 1);
            chosen.add(idx);
        }
        villagerSpawnerIdx = chosen;
        currentHouseIdx    = 0;
    }
    /** Appelé par {@code Batiments.buildHouseRotatedActions}. */
    public boolean shouldPlaceSpawner() {
        return villagerSpawnerIdx.contains(currentHouseIdx++);
    }

    /* ────────────────────────── INSTANCE ───────────────────────── */
    private final JavaPlugin plugin;
    private final List<Location> placedBlocks = new ArrayList<>();

    private final int rows;
    private final int cols;
    private final int houseSmall;
    private final int houseBig;
    private final int roadHalf;
    private final int spacing;
    private final int plazaSize;

    public Village(JavaPlugin plugin) {
        this.plugin = plugin;
        Objects.requireNonNull(plugin.getCommand("village")).setExecutor(this);

        FileConfiguration cfg = plugin.getConfig();
        this.rows       = cfg.getInt("village.rows", 4);
        this.cols       = cfg.getInt("village.cols", 5);
        this.houseSmall = cfg.getInt("village.houseSmall", 9);
        this.houseBig   = cfg.getInt("village.houseBig", 11);
        this.roadHalf   = cfg.getInt("village.roadHalf", 2);
        this.spacing    = cfg.getInt("village.spacing", 20);
        this.plazaSize  = cfg.getInt("village.plazaSize", 9);
    }
    /* ──────────────────────────────────────────────────────────── */

    /* ========================= COMMANDE ========================= */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd,
                             String label, String[] args) {

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Commande réservée aux joueurs.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("undo")) {
            undoVillage();
            p.sendMessage(ChatColor.YELLOW + "Village supprimé.");
            return true;
        }

        generateVillageAsync(p.getLocation());
        p.sendMessage(ChatColor.GREEN + "Construction du village en cours…");
        return true;
    }

    /* ========================= GÉNÉRATION ========================= */
    private void generateVillageAsync(Location center) {

        World w = center.getWorld();
        if (w == null) return;

        /* ─── paramètres de grille ─── */
        int houseW = houseSmall;
        int grid   = houseW + spacing;
        int baseY  = center.getBlockY();

        /* palettes variées pour les maisons et routes */
        List<Material> wallLogs = List.of(
                Material.OAK_LOG,
                Material.SPRUCE_LOG,
                Material.BIRCH_LOG,
                Material.DARK_OAK_LOG
        );
        List<Material> wallPlanks = List.of(
                Material.OAK_PLANKS,
                Material.SPRUCE_PLANKS,
                Material.BIRCH_PLANKS,
                Material.DARK_OAK_PLANKS
        );
        List<Material> roofPalette = List.of(
                Material.OAK_LOG  // bloc bois (ID 17) pour les toits
        );
        /* mix chemin/pavés (duplication = pondération) */
        List<Material> roadPalette = Arrays.asList(
                Material.DIRT_PATH, Material.DIRT_PATH, Material.DIRT_PATH, Material.DIRT_PATH, Material.DIRT_PATH,
                Material.COARSE_DIRT, Material.COARSE_DIRT,
                Material.GRAVEL, Material.GRAVEL,
                Material.COBBLESTONE
        );
        List<Material> cropPalette = List.of(
                Material.WHEAT_SEEDS,
                Material.CARROT,
                Material.POTATO,
                Material.BEETROOT_SEEDS
        );

        /* aucun spawner dans les maisons */
        prepareVillagerSpawnerDistribution(0);

        Queue<Runnable> todo = new LinkedList<>();

        /* bornes de l’ensemble maisons+routes */
        int maxHouse = Math.max(houseSmall, houseBig);
        int[] bounds = computeBounds(center, rows, cols, maxHouse, maxHouse, grid);

        /* centre géométrique → muraille & quota */
        int villageCenterX = (bounds[0] + bounds[1]) / 2;
        int villageCenterZ = (bounds[2] + bounds[3]) / 2;
        Location villageCenter = new Location(w, villageCenterX, baseY, villageCenterZ);
        int villageId = VillageEntityManager.computeVillageId(villageCenter);

        int rx = (bounds[1] - bounds[0]) / 2 + WALL_GAP;
        int rz = (bounds[3] - bounds[2]) / 2 + WALL_GAP;
        int southWallZ = villageCenterZ + rz + 1;
        Location gateAnchor = new Location(w, villageCenterX, baseY + 1, southWallZ - 2);

        /* terrain : on dégage / remblaie y=baseY */
        todo.addAll(prepareGroundActions(w,
                bounds[0] - roadHalf - 5 - WALL_GAP,
                bounds[1] + roadHalf + 5 + WALL_GAP,
                bounds[2] - roadHalf - 5 - WALL_GAP,
                bounds[3] + roadHalf + 5 + WALL_GAP,
                baseY));

        /* place centrale */
        todo.addAll(buildPlaza(w, center.clone(), plazaSize));

        /* stand du marchand (/marchand) */
        todo.addAll(buildMerchantStand(w, center.clone(), plazaSize));

        /* routes quadrillées */
        todo.addAll(buildGridRoads(w, center, rows, cols, grid, roadHalf, baseY));

        /* lots (maisons/fermes/…) */
        Disposition.buildVillage(plugin,
                center,
                rows, cols, baseY,
                houseSmall, houseBig, spacing, roadHalf,
                wallLogs,
                wallPlanks,
                roofPalette,
                roadPalette,
                cropPalette,
                todo,
                (x, y, z, m) -> setBlockTracked(w, x, y, z, m),
                rng,
                rng.nextInt());

        /* lampadaires de carrefour */
        todo.addAll(buildCrossLampPosts(w, center, rows, cols, grid, baseY));

        /* spawners PNJ aux quatre coins */
        todo.addAll(buildQuarterVillagerSpawners(w, bounds, baseY));

        /* muraille + gardiens */
        todo.add(() ->
                WallBuilder.build(villageCenter, rx, rz, baseY,
                        Material.STONE_BRICKS, todo,
                        (x, y, z, m) -> setBlockTracked(w, x, y, z, m)));
        todo.add(() -> GateGuardManager.ensureGuards(plugin, gateAnchor, villageId));

        /* population initiale + cap */
        int ttlTicks = 20 * 60 * 30; // 30 min
        todo.add(() -> VillageEntityManager.spawnInitial(plugin, villageCenter, villageId, ttlTicks));

        /* maire */
        todo.add(() -> spawnVillager(w, center.clone().add(1, 1, 1), "Maire"));

        /* spawners à golems (2, E/W) */
        int plazaHalf = plazaSize / 2;
        for (int i = 0; i < GOLEM_SPAWNERS; i++) {
            int sign = (i % 2 == 0) ? 1 : -1;
            int gx   = center.getBlockX() + sign * (plazaHalf + 2);
            todo.add(createSpawnerAction(
                    w, gx, baseY + 1, center.getBlockZ(), EntityType.IRON_GOLEM));
        }

        /* exécution asynchrone (250 blocs / tick) */
        buildActionsInBatches(todo, 250);
    }

    /* ====================== I/O BLOCS TRACKÉS ====================== */
    public void setBlockTracked(World w, int x, int y, int z, Material mat) {
        Block b = w.getBlockAt(x, y, z);
        b.setType(mat, false);
        placedBlocks.add(b.getLocation());
    }
    public void setBlockTracked(World w, int x, int y, int z, BlockData data) {
        Block b = w.getBlockAt(x, y, z);
        b.setBlockData(data, false);
        placedBlocks.add(b.getLocation());
    }

    public Runnable createSpawnerAction(World w, int x, int y, int z, EntityType type) {
        return () -> {
            setBlockTracked(w, x, y, z, Material.SPAWNER);
            Block b = w.getBlockAt(x, y, z);
            if (b.getState() instanceof CreatureSpawner cs) {
                cs.setSpawnedType(type);
                cs.update();
            }
        };
    }

    private void spawnVillager(World world, Location loc, String name) {
        world.getChunkAt(loc).load();
        Villager v = (Villager) world.spawnEntity(loc, EntityType.VILLAGER);
        v.setCustomName(name);
        v.setCustomNameVisible(true);
        v.setProfession(Villager.Profession.NONE);
    }

    /* ====================== BATCH BUILDER ====================== */
    private void buildActionsInBatches(Queue<Runnable> q, int perTick) {
        new BukkitRunnable() {
            @Override public void run() {
                for (int i = 0; i < perTick && !q.isEmpty(); i++) q.poll().run();
                if (q.isEmpty()) cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /* ====================== TERRAIN & PLACE ====================== */
    private List<Runnable> prepareGroundActions(World w,
                                                int minX, int maxX,
                                                int minZ, int maxZ,
                                                int y) {
        List<Runnable> a = new ArrayList<>();
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++) {
                final int fx = x, fz = z;
                a.add(() -> {
                    for (int h = 1; h <= 15; h++)
                        w.getBlockAt(fx, y + h, fz).setType(Material.AIR, false);
                    setBlockTracked(w, fx, y, fz, Material.GRASS_BLOCK);
                });
            }
        return a;
    }

    /* -------------------------- Place centrale -------------------------- */
    private List<Runnable> buildPlaza(World w, Location c, int size) {
        List<Runnable> a = new ArrayList<>();
        int ox = c.getBlockX() - size / 2,
                oz = c.getBlockZ() - size / 2,
                y  = c.getBlockY();

        /* dallage andésite */
        for (int dx = 0; dx < size; dx++)
            for (int dz = 0; dz < size; dz++) {
                final int fx = ox + dx, fz = oz + dz;
                a.add(() -> setBlockTracked(w, fx, y, fz, Material.POLISHED_ANDESITE));
            }

        /* puits + cloche */
        a.addAll(buildWellActions(w, c));
        a.add(() -> placeBell(w, c.clone().add(0, 3, 0)));

        /* bancs N & S */
        int half = size / 2;
        for (int dx = -2; dx <= 2; dx++) {
            int fxN = c.getBlockX() + dx, fzN = c.getBlockZ() - half;
            int fxS = c.getBlockX() + dx, fzS = c.getBlockZ() + half;
            final int fnx = fxN, fnz = fzN, fsx = fxS, fsz = fzS;
            a.add(() -> {
                setBlockTracked(w, fnx, y + 1, fnz, Material.SPRUCE_STAIRS);
                Block b = w.getBlockAt(fnx, y + 1, fnz);
                if (b.getBlockData() instanceof Stairs st) {
                    st.setFacing(BlockFace.SOUTH);
                    b.setBlockData(st, false);
                }
            });
            a.add(() -> {
                setBlockTracked(w, fsx, y + 1, fsz, Material.SPRUCE_STAIRS);
                Block b = w.getBlockAt(fsx, y + 1, fsz);
                if (b.getBlockData() instanceof Stairs st) {
                    st.setFacing(BlockFace.NORTH);
                    b.setBlockData(st, false);
                }
            });
        }
        return a;
    }

    /**
     * Construit un petit stand au bord sud de la place,
     * et y spawne un PNJ configuré comme marchand (/marchand).
     */
    private List<Runnable> buildMerchantStand(World w, Location center, int size) {
        List<Runnable> a = new ArrayList<>();

        int baseY = center.getBlockY();
        int half  = size / 2;

        int cx = center.getBlockX();
        int cz = center.getBlockZ() + half + 2;

        int xMin = cx - 2;
        int xMax = cx + 2;
        int zMin = cz - 1;
        int zMax = cz + 1;

        for (int x = xMin; x <= xMax; x++) {
            for (int z = zMin; z <= zMax; z++) {
                final int fx = x, fz = z;
                a.add(() -> setBlockTracked(w, fx, baseY, fz, Material.SPRUCE_PLANKS));
            }
        }

        int[][] posts = {
                {xMin, zMin},
                {xMax, zMin},
                {xMin, zMax},
                {xMax, zMax}
        };
        for (int[] p : posts) {
            int px = p[0];
            int pz = p[1];
            for (int dy = 1; dy <= 3; dy++) {
                final int fx = px, fy = baseY + dy, fz = pz;
                a.add(() -> setBlockTracked(w, fx, fy, fz, Material.SPRUCE_LOG));
            }
        }

        for (int x = xMin; x <= xMax; x++) {
            for (int z = zMin; z <= zMax; z++) {
                final int fx = x, fz = z;
                a.add(() -> setBlockTracked(w, fx, baseY + 4, fz, Material.SPRUCE_SLAB));
            }
        }

        for (int x = xMin; x <= xMax; x++) {
            if (x == cx) continue;
            final int fx = x, fz = zMin;
            a.add(() -> setBlockTracked(w, fx, baseY + 1, fz, Material.OAK_TRAPDOOR));
        }

        final double npcX = cx + 0.5;
        final double npcY = baseY + 1;
        final double npcZ = zMin + 1.5;

        a.add(() -> {
            Location npcLoc = new Location(w, npcX, npcY, npcZ);
            Villager villager = (Villager) w.spawnEntity(npcLoc, EntityType.VILLAGER);

            MinePlugin minePlugin = (MinePlugin) plugin;
            minePlugin.getMerchantManager().prepareMerchantNpc(villager);
        });

        return a;
    }

    /* -------------------------- Routes quadrillées -------------------------- */
    private List<Runnable> buildGridRoads(World w, Location c,
                                          int rows, int cols, int grid,
                                          int half, int y) {
        List<Runnable> a = new ArrayList<>();

        /* horizontales */
        for (int r = 0; r < rows; r++) {
            int z = c.getBlockZ() + (r - (rows - 1) / 2) * grid;
            a.addAll(roadLine(w,
                    c.getBlockX() + (0 - (cols - 1) / 2) * grid,
                    c.getBlockX() + ((cols - 1) - (cols - 1) / 2) * grid,
                    z, true, half, y));
        }
        /* verticales */
        for (int col = 0; col < cols; col++) {
            int x = c.getBlockX() + (col - (cols - 1) / 2) * grid;
            a.addAll(roadLine(w,
                    c.getBlockZ() + (0 - (rows - 1) / 2) * grid,
                    c.getBlockZ() + ((rows - 1) - (rows - 1) / 2) * grid,
                    x, false, half, y));
        }
        return a;
    }
    private List<Runnable> roadLine(World w, int start, int end, int fixed,
                                    boolean horiz, int half, int y) {
        List<Runnable> a = new ArrayList<>();
        if (start > end) { int t = start; start = end; end = t; }
        for (int pos = start; pos <= end; pos++)
            for (int off = -half; off <= half; off++) {
                final int fx = horiz ? pos : fixed + off;
                final int fz = horiz ? fixed + off : pos;
                a.add(() -> setBlockTracked(w, fx, y, fz, pickRoadMaterial()));
            }
        return a;
    }
    private Material pickRoadMaterial() {
        double roll = rng.nextDouble();
        if (roll < 0.55) {
            return Material.DIRT_PATH;
        } else if (roll < 0.75) {
            return Material.COARSE_DIRT;
        } else if (roll < 0.90) {
            return Material.GRAVEL;
        } else {
            return Material.COBBLESTONE;
        }
    }

    /* -------------------------- Lampadaires de croisement -------------------------- */
    private List<Runnable> buildCrossLampPosts(World w, Location c,
                                               int rows, int cols, int grid,
                                               int baseY) {
        List<Runnable> a = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            int z = c.getBlockZ() + (r - (rows - 1) / 2) * grid;
            for (int col = 0; col < cols; col++) {
                int x = c.getBlockX() + (col - (cols - 1) / 2) * grid;
                a.addAll(buildLampPostActions(w, x, baseY + 1, z));
            }
        }
        return a;
    }
    private List<Runnable> buildLampPostActions(World w, int x, int y, int z) {
        return List.of(
                () -> setBlockTracked(w, x, y,     z, Material.COBBLESTONE_WALL),
                () -> setBlockTracked(w, x, y + 1, z, Material.CHAIN),
                () -> setBlockTracked(w, x, y + 2, z, Material.CHAIN),
                () -> setBlockTracked(w, x, y + 3, z, Material.LANTERN)
        );
    }

    private List<Runnable> buildQuarterVillagerSpawners(World w, int[] b, int y) {
        List<Runnable> a = new ArrayList<>();

        int cx = (b[0] + b[1]) / 2;
        int cz = (b[2] + b[3]) / 2;
        int x1 = (b[0] + cx) / 2;
        int x2 = (b[1] + cx) / 2;
        int z1 = (b[2] + cz) / 2;
        int z2 = (b[3] + cz) / 2;

        int[][] pts = { {x1, z1}, {x2, z1}, {x1, z2}, {x2, z2} };
        int[][] off = { {1,0}, {-1,0}, {0,1}, {0,-1} };

        for (int[] p : pts) {
            int px = p[0];
            int pz = p[1];
            a.add(createSpawnerAction(w, px, y + 1, pz, EntityType.VILLAGER));
            for (int[] o : off) {
                int fx = px + o[0];
                int fz = pz + o[1];
                a.add(() -> setBlockTracked(w, fx, y + 1, fz, Material.SEA_LANTERN));
            }
        }

        return a;
    }

    /* -------------------------- Puits + cloche utilitaires -------------------------- */
    private List<Runnable> buildWellActions(World w, Location c) {
        List<Runnable> a = new ArrayList<>();
        int x0 = c.getBlockX(), y = c.getBlockY(), z0 = c.getBlockZ();

        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                final int fx = x0 + dx, fz = z0 + dz;
                if (Math.abs(dx) == 1 || Math.abs(dz) == 1)
                    a.add(() -> setBlockTracked(w, fx, y, fz, Material.STONE_BRICKS));
                else
                    a.add(() -> setBlockTracked(w, fx, y, fz, Material.WATER));
            }
        for (int dx : new int[]{-1, 1})
            for (int dz : new int[]{-1, 1}) {
                for (int dy = 1; dy <= 3; dy++) {
                    final int fx = x0 + dx, fy = y + dy, fz = z0 + dz;
                    a.add(() -> setBlockTracked(w, fx, fy, fz, Material.OAK_LOG));
                }
                final int fx = x0 + dx, fz = z0 + dz;
                a.add(() -> setBlockTracked(w, fx, y + 4, fz, Material.OAK_SLAB));
            }
        return a;
    }
    private void placeBell(World w, Location l) {
        setBlockTracked(w, l.getBlockX(),     l.getBlockY(),     l.getBlockZ(), Material.CHAIN);
        setBlockTracked(w, l.getBlockX(), l.getBlockY() - 1, l.getBlockZ(), Material.BELL);
    }

    /* ==================== UNDO & LIMITES ==================== */
    private void undoVillage() {
        placedBlocks.forEach(loc -> loc.getBlock().setType(Material.AIR, false));
        placedBlocks.clear();
    }

    /* --------------------------- BORNES --------------------------- */
    private int[] computeBounds(Location c,
                                int rows, int cols,
                                int w, int d,
                                int grid) {

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

        for (int r = 0; r < rows; r++)
            for (int col = 0; col < cols; col++) {
                int hx = c.getBlockX() + (col - (cols - 1) / 2) * grid;
                int hz = c.getBlockZ() + (r - (rows - 1) / 2) * grid;
                int[] b = Batiments.computeHouseBounds(hx, hz, w, d, 0);
                minX = Math.min(minX, b[0]); maxX = Math.max(maxX, b[1]);
                minZ = Math.min(minZ, b[2]); maxZ = Math.max(maxZ, b[3]);
            }
        return new int[]{minX, maxX, minZ, maxZ};
    }
}
