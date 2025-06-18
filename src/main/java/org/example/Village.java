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

import java.util.*;

/**
 * Commande :
 *   /village        – génère un village orthogonal (place, routes, maisons)
 *   /village undo   – supprime la dernière génération.
 */
public final class Village implements CommandExecutor {

    /* ────────────────────────── QUOTAS ────────────────────────── */
    private static final int MIN_VIL_SPAWNERS = 4;
    private static final int MAX_VIL_SPAWNERS = 8;
    private static final int GOLEM_SPAWNERS   = 2;          // au moins 2 générateurs de golems
    private static final int NPC_CAP          = 100;        // plafond mou pour limiter le lag
    /* ──────────────────────────────────────────────────────────── */

    private final Random rng = new Random();

    /* sélection aléatoire des maisons qui recevront un spawner PNJ */
    private Set<Integer> villagerSpawnerIdx = Collections.emptySet();
    private int currentHouseIdx = 0;

    public void prepareVillagerSpawnerDistribution(int totalHouses) {
        int quota = Math.min(MAX_VIL_SPAWNERS,
                Math.max(MIN_VIL_SPAWNERS, totalHouses / 2));
        Set<Integer> chosen = new HashSet<>();
        while (chosen.size() < quota) chosen.add(rng.nextInt(totalHouses));
        villagerSpawnerIdx = chosen;
        currentHouseIdx    = 0;
    }
    /** Appelé par {@link Batiments#buildHouseRotatedActions}. */
    public boolean shouldPlaceSpawner() {
        return villagerSpawnerIdx.contains(currentHouseIdx++);
    }

    /* ────────────────────────── INSTANCE ───────────────────────── */
    private final JavaPlugin plugin;
    private final List<Location> placedBlocks = new ArrayList<>();
    private final Map<String, Object> cfg     = new HashMap<>();

    public Village(JavaPlugin plugin) {
        this.plugin = plugin;
        Objects.requireNonNull(plugin.getCommand("village")).setExecutor(this);

        cfg.put("houseWidth",    plugin.getConfig().getInt("village.houseSmall", 9));
        cfg.put("houseDepth",    plugin.getConfig().getInt("village.houseSmall", 9));
        cfg.put("roadHalf",      plugin.getConfig().getInt("village.roadHalf", 2));
        cfg.put("houseSpacing",  plugin.getConfig().getInt("village.spacing", 20));
        cfg.put("plazaSize",     plugin.getConfig().getInt("village.plazaSize", 9));
        cfg.put("rows",          plugin.getConfig().getInt("village.rows", 4));
        cfg.put("cols",          plugin.getConfig().getInt("village.cols", 5));
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
        int houseW   = (int) cfg.get("houseWidth");
        int houseD   = (int) cfg.get("houseDepth");
        int spacing  = (int) cfg.get("houseSpacing");
        int roadHalf = (int) cfg.get("roadHalf");
        int rows     = (int) cfg.get("rows");
        int cols     = (int) cfg.get("cols");
        int baseY    = center.getBlockY();

        int grid     = houseW + spacing;

        /* répartition spawners PNJ */
        prepareVillagerSpawnerDistribution(rows * cols);

        Queue<Runnable> todo = new LinkedList<>();

        /* terrain plat (aire + rebords) */
        int[] bounds = computeBounds(center, rows, cols,
                houseW, houseD, grid);
        todo.addAll(prepareGroundActions(w,
                bounds[0] - roadHalf - 3, bounds[1] + roadHalf + 3,
                bounds[2] - roadHalf - 3, bounds[3] + roadHalf + 3,
                baseY));

        /* place centrale */
        todo.addAll(buildPlaza(w, center.clone(), (int) cfg.get("plazaSize")));

        /* routes */
        todo.addAll(buildGridRoads(w, center, rows, cols,
                grid, roadHalf, baseY));

        /* maisons */
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int hx = center.getBlockX() + (c - (cols - 1) / 2) * grid;
                int hz = center.getBlockZ() + (r - (rows - 1) / 2) * grid;

                int rot = computeHouseRotation(hx, hz, center);

                double roll = rng.nextDouble();
                boolean habitation = roll < 0.70;           // lot = house small|big

                todo.addAll(Batiments.buildHouseRotatedActions(
                        w, new Location(w, hx, baseY, hz),
                        houseW, houseD, rot, this, habitation));
            }
        }

        /* lampadaires */
        todo.addAll(buildCrossLampPosts(w, center, rows, cols,
                grid, baseY));

        /* muraille périphérique */
        todo.addAll(buildWallActions(w,
                bounds[0] - 5, bounds[1] + 5,
                bounds[2] - 5, bounds[3] + 5,
                baseY));

        /* maire + spawners à golem */
        todo.add(() -> spawnVillager(w, center.clone().add(1, 1, 1), "Maire"));

        int plazaHalf = ((int) cfg.get("plazaSize")) / 2;
        for (int i = 0; i < GOLEM_SPAWNERS; i++) {
            int sign = (i % 2 == 0) ? 1 : -1;            // est / ouest
            int gx   = center.getBlockX() + sign * (plazaHalf + 2);
            todo.add(createSpawnerAction(
                    w, gx, baseY + 1, center.getBlockZ(), EntityType.IRON_GOLEM));
        }

        /* exécution asynchrone (250 blocs / tick) */
        buildActionsInBatches(todo, 250);

        /* tâche de limitation à 100 NPC */
        VillageEntityManager.startCapTask(plugin, center, NPC_CAP);
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
                    for (int h = 1; h <= 10; h++)
                        w.getBlockAt(fx, y + h, fz).setType(Material.AIR, false);
                    setBlockTracked(w, fx, y, fz, Material.GRASS_BLOCK);
                });
            }
        return a;
    }

    private List<Runnable> buildPlaza(World w, Location c, int size) {
        List<Runnable> a = new ArrayList<>();
        int ox = c.getBlockX() - size / 2,
                oz = c.getBlockZ() - size / 2,
                y  = c.getBlockY();

        /* dallage */
        for (int dx = 0; dx < size; dx++)
            for (int dz = 0; dz < size; dz++) {
                final int fx = ox + dx, fz = oz + dz;
                a.add(() -> setBlockTracked(w, fx, y, fz, Material.POLISHED_ANDESITE));
            }

        /* puits + cloche */
        a.addAll(buildWellActions(w, c));
        a.add(() -> placeBell(w, c.clone().add(0, 3, 0)));

        // PATCH 5-B  bancs N & S
        int plazaHalf = size / 2;
        for (int dx = -2; dx <= 2; dx++) {
            int fxN = c.getBlockX() + dx, fzN = c.getBlockZ() - plazaHalf;
            int fxS = c.getBlockX() + dx, fzS = c.getBlockZ() + plazaHalf;
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

    /* ========================= ROUTES ========================= */
    private List<Runnable> buildGridRoads(World w, Location c,
                                          int rows, int cols, int grid,
                                          int half, int y) {
        List<Runnable> a = new ArrayList<>();

        /* horizontales */
        for (int r = 0; r < rows; r++) {
            int z = c.getBlockZ() + (r - (rows - 1) / 2) * grid;
            a.addAll(roadLine(w,
                    c.getBlockX() + (-1 - (cols - 1) / 2) * grid,
                    c.getBlockX() + (cols - (cols - 1) / 2) * grid,
                    z, true, half, y));
        }
        /* verticales */
        for (int col = 0; col < cols; col++) {
            int x = c.getBlockX() + (col - (cols - 1) / 2) * grid;
            a.addAll(roadLine(w,
                    c.getBlockZ() + (-1 - (rows - 1) / 2) * grid,
                    c.getBlockZ() + (rows - (rows - 1) / 2) * grid,
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
        int v = rng.nextInt(100);
        return v < 60 ? Material.GRAVEL
                : v < 85 ? Material.DIRT_PATH
                : Material.COARSE_DIRT;
    }

    /* =============== DÉCOR : lampadaires & puits =============== */
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

    /* ========================= MURAILLE ========================= */
    private List<Runnable> buildWallActions(World w,
                                            int minX, int maxX,
                                            int minZ, int maxZ,
                                            int y) {
        List<Runnable> a = new ArrayList<>();
        int h = 5;
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++) {
                boolean edge = (x == minX || x == maxX || z == minZ || z == maxZ);
                if (!edge) continue;
                for (int dy = 1; dy <= h; dy++) {
                    final int fx = x, fy = y + dy, fz = z;
                    a.add(() -> setBlockTracked(w, fx, fy, fz, Material.STONE_BRICKS));
                }
                final int fx = x, fz = z, top = y + h;
                a.add(() -> setBlockTracked(w, fx, top, fz, Material.STONE_BRICK_SLAB));
            }
        return a;
    }

    /* ==================== UNDO & LIMITES ==================== */
    private void undoVillage() {
        placedBlocks.forEach(loc -> loc.getBlock().setType(Material.AIR, false));
        placedBlocks.clear();
    }

    /**
     * Orientation simplifiée : la maison regarde la route la plus proche.
     * Retourne 0, 90, 180 ou 270°.
     */
    private int computeHouseRotation(int hx, int hz, Location plaza) {
        int dz = Math.abs(hz - plaza.getBlockZ());
        int dx = Math.abs(hx - plaza.getBlockX());
        if (dz < dx) {
            return (hz < plaza.getBlockZ()) ? 180 : 0;   // regarde N ou S
        }
        return (hx < plaza.getBlockX()) ? 90 : 270;      // regarde O ou E
    }

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
