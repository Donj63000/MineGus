package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.example.village.Disposition;
import org.example.village.GateGuardManager;
import org.example.village.VillageEntityManager;
import org.example.village.VillageGenerationSession;
import org.example.village.VillageLayoutPlan;
import org.example.village.VillageLayoutPlanner;
import org.example.village.VillageLayoutSettings;
import org.example.village.WallBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Commande:
 *   /village      genere un village medieval semi-organique
 *   /village undo supprime la derniere generation.
 */
public final class Village implements CommandExecutor {

    private static final int VIL_SPAWNERS = 4;
    private static final int GOLEM_SPAWNERS = 2;
    private static final int WALL_GAP = 6;

    private final Random rng = new Random();
    private final JavaPlugin plugin;
    private final int plazaSize;
    private final VillageLayoutSettings layoutSettings;

    private Set<Integer> villagerSpawnerIdx = Collections.emptySet();
    private int currentHouseIdx = 0;
    private VillageGenerationSession currentSession;

    public Village(JavaPlugin plugin) {
        this.plugin = plugin;
        Objects.requireNonNull(plugin.getCommand("village")).setExecutor(this);

        FileConfiguration cfg = plugin.getConfig();
        int rows = cfg.getInt("village.rows", 4);
        int cols = cfg.getInt("village.cols", 5);
        int houseSmall = cfg.getInt("village.houseSmall", 9);
        int houseBig = cfg.getInt("village.houseBig", 11);
        int roadHalf = cfg.getInt("village.roadHalf", 2);
        int spacing = cfg.getInt("village.spacing", 20);
        this.plazaSize = cfg.getInt("village.plazaSize", 9);
        this.layoutSettings = new VillageLayoutSettings(
                cfg.getString("village.layout-style", "semi_organic"),
                rows,
                cols,
                houseSmall,
                houseBig,
                spacing,
                roadHalf,
                plazaSize,
                cfg.getInt("village.houseCountMin", 10),
                cfg.getInt("village.houseCountMax", 16),
                cfg.getInt("village.mainStreetHalf", 2),
                cfg.getInt("village.sideStreetHalf", 1),
                cfg.getInt("village.terrainMaxStep", 2),
                cfg.getString("village.decorDensity", "medium")
        );
    }

    public void prepareVillagerSpawnerDistribution(int totalHouses) {
        Set<Integer> chosen = new HashSet<>();
        if (totalHouses == 0) {
            villagerSpawnerIdx = chosen;
            return;
        }

        double step = (double) totalHouses / VIL_SPAWNERS;
        for (int i = 0; i < VIL_SPAWNERS; i++) {
            int idx = (int) Math.round(i * step + step / 2 - 0.5);
            idx = Math.min(idx, totalHouses - 1);
            chosen.add(idx);
        }
        villagerSpawnerIdx = chosen;
        currentHouseIdx = 0;
    }

    public boolean shouldPlaceSpawner() {
        return villagerSpawnerIdx.contains(currentHouseIdx++);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Commande reservee aux joueurs.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("undo")) {
            undoVillage();
            player.sendMessage(ChatColor.YELLOW + "Village supprime.");
            return true;
        }

        generateVillageAsync(player.getLocation());
        player.sendMessage(ChatColor.GREEN + "Construction du village en cours...");
        return true;
    }

    private void generateVillageAsync(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        int baseY = center.getBlockY();
        List<Material> cropPalette = List.of(
                Material.WHEAT_SEEDS,
                Material.CARROT,
                Material.POTATO,
                Material.BEETROOT_SEEDS
        );

        prepareVillagerSpawnerDistribution(0);

        VillageLayoutPlan layout = VillageLayoutPlanner.plan(center, layoutSettings, rng);
        VillageLayoutPlan.Bounds bounds = layout.bounds();
        int villageCenterX = bounds.centerX();
        int villageCenterZ = bounds.centerZ();
        Location villageCenter = new Location(world, villageCenterX, baseY, villageCenterZ);

        int villageId;
        boolean villageEntitiesEnabled = true;
        try {
            villageId = VillageEntityManager.computeVillageId(villageCenter);
        } catch (Throwable throwable) {
            villageId = Math.abs(Objects.hash(villageCenterX, baseY, villageCenterZ));
            villageEntitiesEnabled = false;
            plugin.getLogger().warning("VillageEntityManager indisponible dans cet environnement: "
                    + throwable.getClass().getSimpleName());
        }

        currentSession = new VillageGenerationSession(villageId);
        final int resolvedVillageId = villageId;
        currentSession.getAnchors().putAll(layout.anchors());
        logLayoutSummary(layout);

        Queue<Runnable> todo = new LinkedList<>();

        int rx = (bounds.maxX() - bounds.minX()) / 2 + WALL_GAP;
        int rz = (bounds.maxZ() - bounds.minZ()) / 2 + WALL_GAP;
        int southWallZ = villageCenterZ + rz + 1;
        Location gateAnchor = new Location(world, villageCenterX, baseY + 1, southWallZ - 2);
        currentSession.getAnchors().put("gate", gateAnchor.clone());

        todo.addAll(prepareGroundActions(world,
                bounds.minX() - layoutSettings.mainStreetHalf() - 5 - WALL_GAP,
                bounds.maxX() + layoutSettings.mainStreetHalf() + 5 + WALL_GAP,
                bounds.minZ() - layoutSettings.mainStreetHalf() - 5 - WALL_GAP,
                bounds.maxZ() + layoutSettings.mainStreetHalf() + 5 + WALL_GAP,
                baseY));

        Disposition.buildVillage(plugin,
                center,
                baseY,
                layoutSettings,
                cropPalette,
                todo,
                (x, y, z, m) -> setBlockTracked(currentSession, world, x, y, z, m),
                rng,
                villageId,
                layout);

        todo.add(spawnMerchantNpc(world, layout.anchors().get("market"), villageId));
        todo.addAll(buildQuarterVillagerSpawners(world, new int[]{bounds.minX(), bounds.maxX(), bounds.minZ(), bounds.maxZ()}, baseY));

        todo.add(() -> WallBuilder.build(villageCenter, rx, rz, baseY,
                Material.STONE_BRICKS, todo,
                (x, y, z, m) -> setBlockTracked(currentSession, world, x, y, z, m)));
        if (villageEntitiesEnabled) {
            todo.add(() -> GateGuardManager.ensureGuards(plugin, gateAnchor, resolvedVillageId));
        }

        final int ttlTicks = 20 * 60 * 30;
        if (villageEntitiesEnabled) {
            todo.add(() -> VillageEntityManager.spawnInitial(plugin, villageCenter,
                    currentSession != null ? currentSession.getAnchors() : Map.of(), resolvedVillageId, ttlTicks));
        }

        Location plazaAnchor = layout.anchors().getOrDefault("plaza", center);
        todo.add(() -> spawnVillager(world, plazaAnchor.clone().add(1, 1, 1), "Maire"));

        for (int i = 0; i < GOLEM_SPAWNERS; i++) {
            int sign = i % 2 == 0 ? 1 : -1;
            int gx = plazaAnchor.getBlockX() + sign * (plazaSize / 2 + 2);
            todo.add(createSpawnerAction(currentSession, world, gx, baseY + 1, plazaAnchor.getBlockZ(), EntityType.IRON_GOLEM));
        }

        buildActionsInBatches(todo, 250);
    }

    public void setBlockTracked(VillageGenerationSession session, World world, int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material, false);
        if (session != null) {
            session.trackBlock(block.getLocation());
        }
    }

    public void setBlockTracked(VillageGenerationSession session, World world, int x, int y, int z, BlockData data) {
        Block block = world.getBlockAt(x, y, z);
        block.setBlockData(data, false);
        if (session != null) {
            session.trackBlock(block.getLocation());
        }
    }

    public void setBlockTracked(World world, int x, int y, int z, Material material) {
        setBlockTracked(currentSession, world, x, y, z, material);
    }

    public void setBlockTracked(World world, int x, int y, int z, BlockData data) {
        setBlockTracked(currentSession, world, x, y, z, data);
    }

    public Runnable createSpawnerAction(VillageGenerationSession session, World world, int x, int y, int z, EntityType type) {
        return () -> {
            setBlockTracked(session, world, x, y, z, Material.SPAWNER);
            if (session != null) {
                session.trackSpawner(new Location(world, x, y, z));
            }
            Block block = world.getBlockAt(x, y, z);
            if (block.getState() instanceof CreatureSpawner spawner) {
                spawner.setSpawnedType(type);
                spawner.update();
            }
        };
    }

    public Runnable createSpawnerAction(World world, int x, int y, int z, EntityType type) {
        return createSpawnerAction(currentSession, world, x, y, z, type);
    }

    private Runnable spawnMerchantNpc(World world, Location anchor, int villageId) {
        return () -> {
            if (!(plugin instanceof MinePlugin minePlugin) || minePlugin.getMerchantManager() == null) {
                return;
            }
            Location spawnLoc = anchor != null ? anchor.clone().add(0.5, 1, 0.5) : new Location(world, 0.5, world.getHighestBlockYAt(0, 0) + 1, 0.5);
            try {
                Villager villager = (Villager) world.spawnEntity(spawnLoc, EntityType.VILLAGER);
                try {
                    VillageEntityManager.tagEntity(villager, plugin, villageId);
                } catch (Throwable ignored) {
                    // Ignore les environnements de test qui ne supportent pas completement les metadonnees.
                }
                if (currentSession != null) {
                    currentSession.trackEntity(villager.getUniqueId());
                }
                minePlugin.getMerchantManager().prepareMerchantNpc(villager);
            } catch (Throwable throwable) {
                plugin.getLogger().warning("Spawn du marchand ignore dans cet environnement: "
                        + throwable.getClass().getSimpleName());
            }
        };
    }

    private void spawnVillager(World world, Location location, String name) {
        try {
            world.getChunkAt(location).load();
            Villager villager = (Villager) world.spawnEntity(location, EntityType.VILLAGER);
            if (currentSession != null) {
                currentSession.trackEntity(villager.getUniqueId());
            }
            villager.setCustomName(name);
            villager.setCustomNameVisible(true);
            villager.setProfession(Villager.Profession.NONE);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Spawn du villageois ignore dans cet environnement: "
                    + throwable.getClass().getSimpleName());
        }
    }

    private void logLayoutSummary(VillageLayoutPlan layout) {
        EnumMap<VillageLayoutPlan.LotRole, Integer> counts = new EnumMap<>(VillageLayoutPlan.LotRole.class);
        int terracedLots = 0;
        for (VillageLayoutPlan.LotPlan lot : layout.lots()) {
            counts.merge(lot.role(), 1, Integer::sum);
            if (lot.terraceY() > 0) {
                terracedLots++;
            }
        }
        plugin.getLogger().info("Village plan genere: maisons=" + layout.houseCount()
                + ", rues=" + layout.streets().size()
                + ", terrasses=" + terracedLots
                + ", ancres=" + layout.anchors().keySet());
        plugin.getLogger().info("Repartition des lots: " + counts);
    }

    private void buildActionsInBatches(Queue<Runnable> queue, int perTick) {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (int i = 0; i < perTick && !queue.isEmpty(); i++) {
                    queue.poll().run();
                }
                if (queue.isEmpty()) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private List<Runnable> prepareGroundActions(World world, int minX, int maxX, int minZ, int maxZ, int y) {
        List<Runnable> actions = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                final int fx = x;
                final int fz = z;
                actions.add(() -> {
                    for (int h = 1; h <= 18; h++) {
                        world.getBlockAt(fx, y + h, fz).setType(Material.AIR, false);
                    }
                    setBlockTracked(world, fx, y - 1, fz, Material.DIRT);
                    setBlockTracked(world, fx, y, fz, Material.GRASS_BLOCK);
                });
            }
        }
        return actions;
    }

    private List<Runnable> buildQuarterVillagerSpawners(World world, int[] bounds, int y) {
        List<Runnable> actions = new ArrayList<>();
        int cx = (bounds[0] + bounds[1]) / 2;
        int cz = (bounds[2] + bounds[3]) / 2;
        int x1 = (bounds[0] + cx) / 2;
        int x2 = (bounds[1] + cx) / 2;
        int z1 = (bounds[2] + cz) / 2;
        int z2 = (bounds[3] + cz) / 2;

        int[][] points = {{x1, z1}, {x2, z1}, {x1, z2}, {x2, z2}};
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] point : points) {
            actions.add(createSpawnerAction(currentSession, world, point[0], y + 1, point[1], EntityType.VILLAGER));
            for (int[] offset : offsets) {
                int fx = point[0] + offset[0];
                int fz = point[1] + offset[1];
                actions.add(() -> setBlockTracked(world, fx, y + 1, fz, Material.SEA_LANTERN));
            }
        }
        return actions;
    }

    private void undoVillage() {
        if (currentSession == null) {
            return;
        }
        currentSession.getPlacedBlocks().forEach(location -> location.getBlock().setType(Material.AIR, false));
        for (UUID id : currentSession.getGeneratedEntities()) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        VillageEntityManager.cleanup(plugin, currentSession.getVillageId());
        currentSession = null;
    }
}
