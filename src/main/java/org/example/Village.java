package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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
import org.bukkit.scheduler.BukkitTask;
import org.example.progression.GlobalProgressionManager;
import org.example.village.Disposition;
import org.example.village.KeepBuilder;
import org.example.village.VillageEntityManager;
import org.example.village.VillageGenerationSession;
import org.example.village.VillageLayoutPlan;
import org.example.village.VillageLayoutPlanner;
import org.example.village.VillageLayoutSettings;
import org.example.village.VillageLightingBuilder;
import org.example.village.VillageKnightManager;
import org.example.village.VillagePopulationSettings;
import org.example.village.VillageStyle;
import org.example.village.WallBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Commande de génération du village médiéval.
 *
 * <p>La génération est préparée intégralement avant d'être exécutée par lots.
 * Chaque tâche capture sa propre session : une seconde commande ne peut donc
 * jamais détourner les écritures ou les entités de la génération en cours.</p>
 *
 * <p>Commandes :</p>
 * <ul>
 *     <li>{@code /village} : construit un village semi-organique ;</li>
 *     <li>{@code /village undo} : restaure l'état antérieur à la génération.</li>
 * </ul>
 */
public final class Village implements CommandExecutor {

    private static final int LEGACY_VILLAGER_SPAWNERS = 4;
    private static final int GOLEM_SPAWNERS = 2;
    private static final int DEFAULT_WALL_GAP = 8;
    private static final int TERRAIN_FEATHER = 4;
    private static final int MAX_ACTIONS_PER_TICK = 320;
    private static final long MAX_BATCH_NANOS = 8_000_000L;

    private final Random rng = new Random();
    private final JavaPlugin plugin;
    private final int wallGap;
    private final VillageLayoutSettings layoutSettings;
    private final VillagePopulationSettings populationSettings;

    /*
     * Ces deux champs sont conservés pour la compatibilité avec les tests et
     * intégrations historiques qui interrogeaient la distribution de spawners.
     */
    private Set<Integer> villagerSpawnerIdx = Collections.emptySet();
    private int currentHouseIdx;

    private VillageGenerationSession currentSession;
    private BukkitTask activeBuildTask;

    public Village(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(plugin.getCommand("village"),
                "La commande 'village' doit être déclarée dans plugin.yml")
                .setExecutor(this);

        FileConfiguration cfg = plugin.getConfig();
        int rows = cfg.getInt("village.rows", 5);
        int cols = cfg.getInt("village.cols", 7);
        int houseSmall = cfg.getInt("village.houseSmall", 9);
        int houseBig = cfg.getInt("village.houseBig", 11);
        int roadHalf = cfg.getInt("village.roadHalf", 2);
        int spacing = cfg.getInt("village.spacing", 20);
        int configuredPlazaSize = cfg.getInt("village.plazaSize", 15);

        /*
         * Le porche du donjon occupe les six premiers blocs devant la
         * courtine nord. Une marge plus faible pourrait empiéter sur un lot.
         */
        this.wallGap = Math.max(
                KeepBuilder.minimumWallGap(),
                cfg.getInt("village.wallGap", DEFAULT_WALL_GAP)
        );
        this.layoutSettings = new VillageLayoutSettings(
                cfg.getString("village.layout-style", "semi_organic"),
                rows,
                cols,
                houseSmall,
                houseBig,
                spacing,
                roadHalf,
                configuredPlazaSize,
                cfg.getInt("village.houseCountMin", 26),
                cfg.getInt("village.houseCountMax", 32),
                cfg.getInt("village.mainStreetHalf", 2),
                cfg.getInt("village.sideStreetHalf", 1),
                cfg.getInt("village.terrainMaxStep", 2),
                cfg.getString("village.decorDensity", "high")
        );
        this.populationSettings =
                VillagePopulationSettings.from(cfg);
    }

    /**
     * API historique : sélectionne jusqu'à quatre maisons réparties de façon
     * homogène. Le générateur moderne applique le même principe aux vrais lots
     * résidentiels, après planification.
     */
    public void prepareVillagerSpawnerDistribution(int totalHouses) {
        Set<Integer> chosen = new LinkedHashSet<>();
        if (totalHouses <= 0) {
            villagerSpawnerIdx = chosen;
            currentHouseIdx = 0;
            return;
        }

        int desired = Math.min(
                LEGACY_VILLAGER_SPAWNERS,
                totalHouses
        );
        double step = (double) totalHouses / desired;
        for (int i = 0; i < desired; i++) {
            int idx = (int) Math.floor(i * step + step / 2.0D);
            chosen.add(Math.min(idx, totalHouses - 1));
        }
        villagerSpawnerIdx = chosen;
        currentHouseIdx = 0;
    }

    public boolean shouldPlaceSpawner() {
        return villagerSpawnerIdx.contains(currentHouseIdx++);
    }

    @Override
    public boolean onCommand(CommandSender sender,
                             Command command,
                             String label,
                             String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Commande réservée aux joueurs.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("undo")) {
            boolean restored = undoVillage();
            player.sendMessage(restored
                    ? ChatColor.YELLOW + "Le village a été annulé et le terrain restauré."
                    : ChatColor.GRAY + "Aucun village généré n'est actuellement enregistré.");
            return true;
        }

        if (currentSession != null) {
            player.sendMessage(ChatColor.YELLOW
                    + "Un village est déjà présent ou en construction. "
                    + "Utilisez /village undo avant d'en générer un autre.");
            return true;
        }

        if (generateVillageAsync(player.getLocation())) {
            player.sendMessage(ChatColor.GREEN
                    + "Construction du village lancée autour de votre position.");
        } else {
            player.sendMessage(ChatColor.RED
                    + "Le village ne peut pas être construit à cet emplacement.");
        }
        return true;
    }

    /**
     * Prépare puis lance une génération isolée.
     *
     * @return {@code true} lorsque la génération a pu être planifiée.
     */
    private boolean generateVillageAsync(Location requestedCenter) {
        World world = requestedCenter.getWorld();
        if (world == null) {
            return false;
        }

        int centerX = requestedCenter.getBlockX();
        int centerZ = requestedCenter.getBlockZ();
        int baseY = resolveBaseY(
                world,
                centerX,
                centerZ,
                requestedCenter.getBlockY() - 1
        );
        if (!KeepBuilder.fitsVertically(world, baseY)) {
            plugin.getLogger().warning(
                    "Altitude incompatible avec la forteresse : y="
                            + baseY
                            + " (fondations jusqu'à "
                            + (baseY - KeepBuilder.foundationDepth())
                            + ", sommet jusqu'à "
                            + (baseY + KeepBuilder.maximumRelativeHeight())
                            + ")."
            );
            return false;
        }

        /*
         * Le centre de composition reste exactement le point de commande. Le
         * centre géométrique des bornes peut être décalé par un quartier plus
         * large ; l'utiliser décalait auparavant la rue principale du portail.
         */
        Location villageCenter = new Location(
                world,
                centerX,
                baseY,
                centerZ
        );
        VillageLayoutPlan layout = VillageLayoutPlanner.plan(
                villageCenter,
                layoutSettings,
                rng
        );
        VillageLayoutPlan.Bounds bounds = layout.bounds();

        int rx = Math.max(
                Math.abs(bounds.minX() - centerX),
                Math.abs(bounds.maxX() - centerX)
        ) + wallGap;
        int rz = Math.max(
                Math.abs(bounds.minZ() - centerZ),
                Math.abs(bounds.maxZ() - centerZ)
        ) + wallGap;

        int villageId;
        boolean villageEntitiesEnabled = true;
        try {
            villageId = VillageEntityManager.computeVillageId(villageCenter);
        } catch (Throwable throwable) {
            villageId = Math.abs(Objects.hash(centerX, baseY, centerZ));
            villageEntitiesEnabled = false;
            plugin.getLogger().warning(
                    "VillageEntityManager indisponible : "
                            + throwable.getClass().getSimpleName());
        }

        VillageGenerationSession session =
                new VillageGenerationSession(villageId);
        session.getAnchors().putAll(layout.anchors());

        Location gateAnchor = WallBuilder.gateAnchor(
                villageCenter,
                rx,
                rz,
                baseY
        );
        session.getAnchors().put("gate", gateAnchor.clone());

        Location keepAnchor = KeepBuilder.keepAnchor(
                villageCenter,
                rz,
                baseY
        );
        if (keepAnchor != null) {
            session.getAnchors().put("keep", keepAnchor.clone());
        }
        currentSession = session;
        logLayoutSummary(layout);

        Queue<Runnable> todo = new LinkedList<>();

        /*
         * L'emprise est calculée depuis la muraille réelle, tours comprises.
         * Le sud est prolongé pour accueillir le parvis et la route extérieure
         * du corps de garde.
         */
        int[] wallBounds = WallBuilder.outerBounds(villageCenter, rx, rz);
        int flatMinX = wallBounds[0] - 2;
        int flatMaxX = wallBounds[1] + 2;
        int flatMinZ = wallBounds[2] - 2;
        int flatMaxZ = wallBounds[3] + 12;
        todo.addAll(prepareGroundActions(
                session,
                world,
                flatMinX,
                flatMaxX,
                flatMinZ,
                flatMaxZ,
                baseY,
                TERRAIN_FEATHER
        ));

        List<Material> cropPalette = List.of(
                Material.WHEAT_SEEDS,
                Material.CARROT,
                Material.POTATO,
                Material.BEETROOT_SEEDS
        );

        Disposition.buildVillage(
                plugin,
                villageCenter,
                baseY,
                layoutSettings,
                cropPalette,
                todo,
                (x, y, z, material) ->
                        setBlockTracked(session, world, x, y, z, material),
                rng,
                villageId,
                layout
        );

        /*
         * Le mur est symétrique autour du centre alors que l'église étire le
         * plan vers le nord. Sans ce raccord, il pouvait rester une bande
         * d'herbe entre l'extrémité sud de la grand-rue et le corps de garde.
         */
        todo.addAll(buildGateRoadConnector(
                session,
                world,
                layout,
                centerZ + rz,
                baseY
        ));

        /*
         * Les spawners sont répartis dans les bâtiments réellement retenus par
         * le planificateur. Ils restent donc accessibles et disposent du volume
         * libre nécessaire, sans tomber dans une route ou sous le terrain.
         */
        todo.addAll(buildResidentialVillagerSpawners(
                session,
                world,
                layout,
                baseY
        ));
        todo.addAll(buildGolemSpawners(
                session,
                world,
                layout,
                baseY
        ));

        Location marketAnchor = layout.anchors()
                .getOrDefault("market", villageCenter);
        todo.add(spawnMerchantNpc(
                session,
                world,
                marketAnchor,
                villageId
        ));

        /*
         * WallBuilder ne modifie pas immédiatement le monde : il ajoute ses
         * tâches à la file. L'appeler ici évite de muter la file depuis une
         * tâche déjà en cours d'itération.
         */
        WallBuilder.build(
                villageCenter,
                rx,
                rz,
                baseY,
                Material.STONE_BRICKS,
                todo,
                (x, y, z, material) ->
                        setBlockTracked(session, world, x, y, z, material)
        );

        /*
         * Le donjon est volontairement programmé après la courtine : ses tours
         * méridionales absorbent le tronçon nord et recréent deux raccords
         * praticables au niveau exact du chemin de ronde.
         */
        KeepBuilder.build(
                villageCenter,
                rz,
                baseY,
                Material.STONE_BRICKS,
                todo,
                (x, y, z, material) ->
                        setBlockTracked(session, world, x, y, z, material)
        );

        /*
         * Cette passe est volontairement planifiée après la muraille et tous
         * les bâtiments. Elle peut ainsi mesurer les zones réellement sombres,
         * y compris les intérieurs, les toitures et les chemins de ronde.
         */
        VillageLightingBuilder.enqueue(
                plugin,
                villageCenter,
                layout,
                rx,
                rz,
                baseY,
                todo,
                (x, y, z, material) ->
                        setBlockTracked(session, world, x, y, z, material)
        );

        final int resolvedVillageId = villageId;
        if (villageEntitiesEnabled) {
            todo.add(() -> VillageEntityManager.spawnInitial(
                    plugin,
                    villageCenter,
                    session.getAnchors(),
                    resolvedVillageId,
                    populationSettings
            ));
        }

        /*
         * La garnison est enregistrée en toute dernière action : les routes,
         * les remparts et le donjon existent alors réellement dans le monde.
         * Le planificateur peut donc refuser proprement les archers si le
         * chemin de ronde n'est pas praticable.
         */
        VillageKnightManager knightManager = getVillageKnightManager();
        if (knightManager != null) {
            todo.add(() -> knightManager.registerVillage(
                    resolvedVillageId,
                    villageCenter,
                    layout,
                    session.getAnchors(),
                    rx,
                    rz,
                    baseY
            ));
        }

        buildActionsInBatches(todo, session);
        return true;
    }

    /**
     * Écrit un matériau en conservant le premier état rencontré.
     */
    public void setBlockTracked(VillageGenerationSession session,
                                World world,
                                int x,
                                int y,
                                int z,
                                Material material) {
        if (world == null || material == null
                || y < world.getMinHeight()
                || y >= world.getMaxHeight()) {
            return;
        }

        Block block = world.getBlockAt(x, y, z);
        if (block.getType() == material) {
            return;
        }
        GlobalProgressionManager progression = getGlobalProgression();
        if (session != null) {
            session.rememberOriginal(
                    block.getLocation(),
                    block.getBlockData(),
                    progression != null
                            && progression.isArtificialBlock(block)
            );
        }
        block.setType(material, false);
        if (progression != null) {
            progression.synchronizeArtificialBlock(block, true);
        }
    }

    /**
     * Écrit des données de bloc orientées en conservant l'état initial.
     */
    public void setBlockTracked(VillageGenerationSession session,
                                World world,
                                int x,
                                int y,
                                int z,
                                BlockData data) {
        if (world == null || data == null
                || y < world.getMinHeight()
                || y >= world.getMaxHeight()) {
            return;
        }

        Block block = world.getBlockAt(x, y, z);
        if (block.getBlockData().equals(data)) {
            return;
        }
        GlobalProgressionManager progression = getGlobalProgression();
        if (session != null) {
            session.rememberOriginal(
                    block.getLocation(),
                    block.getBlockData(),
                    progression != null
                            && progression.isArtificialBlock(block)
            );
        }
        block.setBlockData(data, false);
        if (progression != null) {
            progression.synchronizeArtificialBlock(block, true);
        }
    }

    /**
     * Surcharge de compatibilité. Les nouvelles tâches doivent toujours
     * capturer explicitement leur session.
     */
    public void setBlockTracked(World world,
                                int x,
                                int y,
                                int z,
                                Material material) {
        setBlockTracked(currentSession, world, x, y, z, material);
    }

    /**
     * Surcharge de compatibilité. Les nouvelles tâches doivent toujours
     * capturer explicitement leur session.
     */
    public void setBlockTracked(World world,
                                int x,
                                int y,
                                int z,
                                BlockData data) {
        setBlockTracked(currentSession, world, x, y, z, data);
    }

    public Runnable createSpawnerAction(VillageGenerationSession session,
                                        World world,
                                        int x,
                                        int y,
                                        int z,
                                        EntityType type) {
        return () -> {
            setBlockTracked(session, world, x, y, z, Material.SPAWNER);
            if (session != null) {
                session.trackSpawner(new Location(world, x, y, z));
            }

            Block block = world.getBlockAt(x, y, z);
            if (block.getState() instanceof CreatureSpawner spawner) {
                spawner.setSpawnedType(type);

                VillageGenerationSession owner = session != null
                        ? session
                        : currentSession;
                if (owner != null
                        && (type == EntityType.VILLAGER
                        || type == EntityType.IRON_GOLEM)) {
                    try {
                        VillageEntityManager.configureVillageSpawner(
                                plugin,
                                spawner,
                                type,
                                owner.getVillageId(),
                                populationSettings
                        );
                    } catch (Throwable throwable) {
                        /*
                         * Le bloc reste un spawner utilisable même dans un
                         * environnement de test qui n'implémente pas le PDC.
                         */
                        plugin.getLogger().log(
                                Level.WARNING,
                                "Configuration sécurisée du spawner ignorée",
                                throwable
                        );
                        spawner.update(true, false);
                    }
                } else {
                    spawner.update(true, false);
                }
            }
        };
    }

    public Runnable createSpawnerAction(World world,
                                        int x,
                                        int y,
                                        int z,
                                        EntityType type) {
        return createSpawnerAction(
                currentSession,
                world,
                x,
                y,
                z,
                type
        );
    }

    private Runnable spawnMerchantNpc(VillageGenerationSession session,
                                      World world,
                                      Location anchor,
                                      int villageId) {
        return () -> {
            if (!(plugin instanceof MinePlugin minePlugin)
                    || minePlugin.getMerchantManager() == null) {
                return;
            }

            Location spawnLocation = anchor != null
                    ? anchor.clone().add(0.5D, 1.0D, 0.5D)
                    : new Location(
                            world,
                            0.5D,
                            world.getHighestBlockYAt(0, 0) + 1.0D,
                            0.5D
                    );
            try {
                Villager villager = (Villager) world.spawnEntity(
                        spawnLocation,
                        EntityType.VILLAGER
                );
                session.trackEntity(villager.getUniqueId());
                try {
                    VillageEntityManager.tagEntity(
                            villager,
                            plugin,
                            villageId
                    );
                } catch (Throwable ignored) {
                    /*
                     * Certains environnements de test ne fournissent pas le
                     * conteneur de données persistantes de Paper.
                     */
                }
                minePlugin.getMerchantManager()
                        .prepareMerchantNpc(villager);
            } catch (Throwable throwable) {
                plugin.getLogger().warning(
                        "Apparition du marchand ignorée : "
                                + throwable.getClass().getSimpleName());
            }
        };
    }

    /**
     * Prolonge la grand-rue jusqu'à la face intérieure du portail.
     *
     * <p>La longueur est dérivée du plan réel et de la muraille calculée :
     * aucune constante liée à la configuration par défaut n'est utilisée.</p>
     */
    private List<Runnable> buildGateRoadConnector(
            VillageGenerationSession session,
            World world,
            VillageLayoutPlan layout,
            int innerGateZ,
            int baseY) {
        List<Runnable> actions = new ArrayList<>();
        VillageLayoutPlan.StreetPlan mainStreet = layout.streets().stream()
                .filter(street -> street.type()
                        == VillageLayoutPlan.StreetType.MAIN)
                .filter(street -> !street.horizontal())
                .findFirst()
                .orElse(null);
        if (mainStreet == null) {
            return actions;
        }

        int roadCenterX = mainStreet.startX();
        int roadEndZ = mainStreet.maxZ();
        if (innerGateZ <= roadEndZ) {
            return actions;
        }

        int halfWidth = Math.max(1, mainStreet.halfWidth());
        for (int z = roadEndZ + 1; z <= innerGateZ; z++) {
            int currentZ = z;
            for (int dx = -halfWidth; dx <= halfWidth; dx++) {
                int x = roadCenterX + dx;
                Material paving;
                if (Math.abs(dx) == halfWidth) {
                    paving = Math.floorMod(x + currentZ, 3) == 0
                            ? Material.STONE_BRICKS
                            : Material.COBBLESTONE;
                } else {
                    paving = Math.floorMod(x * 17 + currentZ * 31, 6) == 0
                            ? Material.POLISHED_ANDESITE
                            : Material.GRAVEL;
                }

                Material finalPaving = paving;
                actions.add(() -> setBlockTracked(
                        session,
                        world,
                        x,
                        baseY - 1,
                        currentZ,
                        Material.COBBLESTONE
                ));
                actions.add(() -> setBlockTracked(
                        session,
                        world,
                        x,
                        baseY,
                        currentZ,
                        finalPaving
                ));
            }

            // Deux accotements en terre battue adoucissent la jonction avec
            // le terrain et évitent l'effet de ruban minéral parfaitement net.
            for (int side : new int[]{-1, 1}) {
                int shoulderX = roadCenterX
                        + side * (halfWidth + 1);
                Material shoulder = Math.floorMod(
                        shoulderX * 13 + currentZ * 7,
                        5
                ) == 0
                        ? Material.COARSE_DIRT
                        : Material.DIRT_PATH;
                actions.add(() -> setBlockTracked(
                        session,
                        world,
                        shoulderX,
                        baseY,
                        currentZ,
                        shoulder
                ));
            }
        }
        return actions;
    }

    private List<Runnable> buildResidentialVillagerSpawners(
            VillageGenerationSession session,
            World world,
            VillageLayoutPlan layout,
            int baseY) {
        List<VillageLayoutPlan.LotPlan> selected =
                selectPopulationSpawnerLots(
                        layout,
                        populationSettings.villagerSpawnerCount()
                );

        List<Runnable> actions = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            VillageLayoutPlan.LotPlan lot = selected.get(index);
            BlockFace right = VillageStyle.rightOf(lot.facing());
            int sideRadius = (lot.facing() == BlockFace.NORTH
                    || lot.facing() == BlockFace.SOUTH)
                    ? (lot.footprintWidth() - 1) / 2
                    : (lot.footprintDepth() - 1) / 2;
            int lateral = Math.min(2, Math.max(0, sideRadius - 2));
            if (index % 2 != 0) {
                lateral = -lateral;
            }

            int spawnerX = lot.centerX()
                    + right.getModX() * lateral;
            int spawnerZ = lot.centerZ()
                    + right.getModZ() * lateral;

            /*
             * Le spawner est posé dans la pièce principale, un bloc au-dessus
             * du plancher. L'ancienne implantation sous terre ne disposait pas
             * des deux blocs d'air nécessaires à un villageois.
             */
            int spawnerY = baseY + lot.terraceY() + 2;
            int finalSpawnerX = spawnerX;
            int finalSpawnerZ = spawnerZ;
            int finalSpawnerY = spawnerY;
            actions.add(() -> setBlockTracked(
                    session,
                    world,
                    finalSpawnerX,
                    finalSpawnerY + 1,
                    finalSpawnerZ,
                    Material.AIR
            ));
            actions.add(createSpawnerAction(
                    session,
                    world,
                    spawnerX,
                    spawnerY,
                    spawnerZ,
                    EntityType.VILLAGER
            ));
        }
        return actions;
    }

    /**
     * Sélection par éloignement maximal : le premier spawner dessert le centre,
     * puis chaque nouveau point est choisi le plus loin possible de ceux déjà
     * retenus. La population se répartit ainsi dans toute la ville.
     */
    private List<VillageLayoutPlan.LotPlan>
    selectPopulationSpawnerLots(VillageLayoutPlan layout,
                                int requestedCount) {
        List<VillageLayoutPlan.LotPlan> candidates =
                layout.lots().stream()
                        .filter(this::supportsVillagerSpawner)
                        .sorted((left, right) -> {
                            int byX = Integer.compare(
                                    left.centerX(),
                                    right.centerX()
                            );
                            return byX != 0
                                    ? byX
                                    : Integer.compare(
                                    left.centerZ(),
                                    right.centerZ()
                            );
                        })
                        .toList();
        if (candidates.isEmpty() || requestedCount <= 0) {
            return List.of();
        }

        int desired = Math.min(requestedCount, candidates.size());
        List<VillageLayoutPlan.LotPlan> selected =
                new ArrayList<>(desired);
        Location plaza = layout.anchors().get("plaza");
        int plazaX = plaza != null ? plaza.getBlockX() : 0;
        int plazaZ = plaza != null ? plaza.getBlockZ() : 0;

        VillageLayoutPlan.LotPlan first = candidates.stream()
                .min((left, right) -> Long.compare(
                        squaredDistance(
                                left.centerX(),
                                left.centerZ(),
                                plazaX,
                                plazaZ
                        ),
                        squaredDistance(
                                right.centerX(),
                                right.centerZ(),
                                plazaX,
                                plazaZ
                        )
                ))
                .orElse(candidates.get(0));
        selected.add(first);

        while (selected.size() < desired) {
            VillageLayoutPlan.LotPlan best = null;
            long bestDistance = Long.MIN_VALUE;
            for (VillageLayoutPlan.LotPlan candidate : candidates) {
                if (selected.contains(candidate)) {
                    continue;
                }

                long nearest = Long.MAX_VALUE;
                for (VillageLayoutPlan.LotPlan existing : selected) {
                    nearest = Math.min(
                            nearest,
                            squaredDistance(
                                    candidate.centerX(),
                                    candidate.centerZ(),
                                    existing.centerX(),
                                    existing.centerZ()
                            )
                    );
                }

                if (nearest > bestDistance) {
                    best = candidate;
                    bestDistance = nearest;
                }
            }

            if (best == null) {
                break;
            }
            selected.add(best);
        }
        return List.copyOf(selected);
    }

    private boolean supportsVillagerSpawner(
            VillageLayoutPlan.LotPlan lot) {
        if (lot.isHouse()) {
            return true;
        }
        return switch (lot.role()) {
            case INN, BAKERY, TOWN_HALL, CARPENTER, STABLE -> true;
            default -> false;
        };
    }

    private static long squaredDistance(int firstX,
                                        int firstZ,
                                        int secondX,
                                        int secondZ) {
        long dx = (long) firstX - secondX;
        long dz = (long) firstZ - secondZ;
        return dx * dx + dz * dz;
    }

    private List<Runnable> buildGolemSpawners(
            VillageGenerationSession session,
            World world,
            VillageLayoutPlan layout,
            int baseY) {
        List<Runnable> actions = new ArrayList<>();
        Location plaza = layout.anchors().get("plaza");
        if (plaza == null) {
            return actions;
        }

        int offset = Math.max(
                3,
                layoutSettings.effectivePlazaSize() / 2 - 2
        );
        for (int i = 0; i < GOLEM_SPAWNERS; i++) {
            int sign = i == 0 ? -1 : 1;
            actions.add(createSpawnerAction(
                    session,
                    world,
                    plaza.getBlockX() + sign * offset,
                    baseY + 1,
                    plaza.getBlockZ(),
                    EntityType.IRON_GOLEM
            ));
        }
        return actions;
    }

    private void logLayoutSummary(VillageLayoutPlan layout) {
        EnumMap<VillageLayoutPlan.LotRole, Integer> counts =
                new EnumMap<>(VillageLayoutPlan.LotRole.class);
        int terracedLots = 0;
        for (VillageLayoutPlan.LotPlan lot : layout.lots()) {
            counts.merge(lot.role(), 1, Integer::sum);
            if (lot.terraceY() > 0) {
                terracedLots++;
            }
        }

        plugin.getLogger().info(
                "Plan du village : maisons=" + layout.houseCount()
                        + ", rues=" + layout.streets().size()
                        + ", terrasses=" + terracedLots
                        + ", dimensions=" + layout.bounds().width()
                        + "x" + layout.bounds().depth()
        );
        plugin.getLogger().info("Répartition des lots : " + counts);
    }

    /**
     * Exécute la file avec une double limite (nombre d'actions et temps CPU)
     * afin que les colonnes de terrassement ne figent pas le serveur.
     */
    private void buildActionsInBatches(
            Queue<Runnable> queue,
            VillageGenerationSession session) {
        BukkitRunnable runner = new BukkitRunnable() {
            @Override
            public void run() {
                if (currentSession != session) {
                    clearActiveTask();
                    cancel();
                    return;
                }

                long startedAt = System.nanoTime();
                int processed = 0;
                try {
                    while (processed < MAX_ACTIONS_PER_TICK
                            && !queue.isEmpty()
                            && System.nanoTime() - startedAt
                            < MAX_BATCH_NANOS) {
                        Runnable action = queue.poll();
                        if (action != null) {
                            action.run();
                            processed++;
                        }
                    }
                } catch (Throwable throwable) {
                    plugin.getLogger().log(
                            Level.SEVERE,
                            "La génération du village a échoué ; "
                                    + "les modifications vont être restaurées.",
                            throwable
                    );
                    clearActiveTask();
                    cancel();
                    rollbackSession(session);
                    return;
                }

                if (queue.isEmpty()) {
                    plugin.getLogger().info(
                            "Village " + session.getVillageId()
                                    + " construit avec succès."
                    );
                    clearActiveTask();
                    cancel();
                }
            }
        };

        activeBuildTask = runner.runTaskTimer(plugin, 1L, 1L);
    }

    private void clearActiveTask() {
        activeBuildTask = null;
    }

    /**
     * Prépare une terrasse principale et une transition graduelle vers le
     * relief naturel. Chaque colonne est une seule tâche pour maîtriser la
     * taille de la file, mais chaque bloc modifié reste individuellement suivi.
     */
    private List<Runnable> prepareGroundActions(
            VillageGenerationSession session,
            World world,
            int flatMinX,
            int flatMaxX,
            int flatMinZ,
            int flatMaxZ,
            int baseY,
            int feather) {
        List<Runnable> actions = new ArrayList<>();
        int safeFeather = Math.max(0, feather);
        int minX = flatMinX - safeFeather;
        int maxX = flatMaxX + safeFeather;
        int minZ = flatMinZ - safeFeather;
        int maxZ = flatMaxZ + safeFeather;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                final int columnX = x;
                final int columnZ = z;
                actions.add(() -> prepareGroundColumn(
                        session,
                        world,
                        columnX,
                        columnZ,
                        flatMinX,
                        flatMaxX,
                        flatMinZ,
                        flatMaxZ,
                        baseY,
                        safeFeather
                ));
            }
        }
        return actions;
    }

    private void prepareGroundColumn(
            VillageGenerationSession session,
            World world,
            int x,
            int z,
            int flatMinX,
            int flatMaxX,
            int flatMinZ,
            int flatMaxZ,
            int baseY,
            int feather) {
        int highestY = world.getHighestBlockYAt(x, z);
        int naturalY = findNaturalGroundY(world, x, z, baseY);
        int distance = distanceOutside(
                x,
                z,
                flatMinX,
                flatMaxX,
                flatMinZ,
                flatMaxZ
        );

        int targetY;
        if (distance <= 0 || feather == 0) {
            targetY = baseY;
        } else {
            /*
             * La dernière couronne préparée rejoint exactement le relief
             * naturel. Avec {@code feather + 1}, il restait encore 20 % de
             * terrassement au bord, puis une marche brutale hors de la zone.
             */
            double ratio = Math.min(
                    1.0D,
                    distance / (double) Math.max(1, feather)
            );
            targetY = (int) Math.round(
                    baseY + (naturalY - baseY) * ratio
            );
        }
        targetY = Math.max(
                world.getMinHeight() + 2,
                Math.min(world.getMaxHeight() - 2, targetY)
        );

        /*
         * On retire aussi les troncs et feuillages au-dessus du sol cible.
         * Contrairement à l'ancienne version, chaque bloc d'air est mémorisé
         * et pourra donc être restauré par /village undo.
         */
        int clearTop = Math.min(
                world.getMaxHeight() - 1,
                /*
                 * Le donjon domine désormais l'enceinte. La hauteur partagée
                 * garantit que ni une canopée ni un ancien bâtiment ne traverse
                 * ses créneaux ou son mât.
                 */
                Math.max(
                        highestY,
                        targetY
                                + KeepBuilder.maximumRelativeHeight()
                                + 1
                )
        );
        for (int y = targetY + 1; y <= clearTop; y++) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.getType().isAir()) {
                setBlockTracked(
                        session,
                        world,
                        x,
                        y,
                        z,
                        Material.AIR
                );
            }
        }

        if (naturalY < targetY) {
            for (int y = naturalY + 1; y < targetY; y++) {
                Material fill = y < targetY - 3
                        ? Material.STONE
                        : Material.DIRT;
                setBlockTracked(session, world, x, y, z, fill);
            }
        }

        setBlockTracked(
                session,
                world,
                x,
                targetY - 1,
                z,
                Math.floorMod(x * 17 + z * 31, 7) == 0
                        ? Material.COARSE_DIRT
                        : Material.DIRT
        );
        setBlockTracked(
                session,
                world,
                x,
                targetY,
                z,
                terrainTopMaterial(x, z, distance, feather)
        );
    }

    private int resolveBaseY(
            World world,
            int centerX,
            int centerZ,
            int fallbackY) {
        List<Integer> samples = new ArrayList<>();
        int[] offsets = {-4, 0, 4};
        for (int dx : offsets) {
            for (int dz : offsets) {
                samples.add(findNaturalGroundY(
                        world,
                        centerX + dx,
                        centerZ + dz,
                        fallbackY
                ));
            }
        }
        Collections.sort(samples);
        int median = samples.get(samples.size() / 2);

        /*
         * Dans une grotte ou sur une très haute construction, le niveau le
         * plus proche du joueur est plus prévisible que la surface distante.
         */
        if (Math.abs(median - fallbackY) > 18) {
            return fallbackY;
        }
        return median;
    }

    private int findNaturalGroundY(
            World world,
            int x,
            int z,
            int fallbackY) {
        int highest = Math.min(
                world.getMaxHeight() - 1,
                world.getHighestBlockYAt(x, z)
        );
        int lowerBound = Math.max(
                world.getMinHeight(),
                highest - 96
        );

        for (int y = highest; y >= lowerBound; y--) {
            Material material = world.getBlockAt(x, y, z).getType();
            if (isNaturalGround(material)) {
                return y;
            }
        }
        return Math.max(
                world.getMinHeight() + 2,
                Math.min(world.getMaxHeight() - 2, fallbackY)
        );
    }

    private boolean isNaturalGround(Material material) {
        if (material == null || material.isAir() || !material.isSolid()) {
            return false;
        }

        String name = material.name();
        return !name.endsWith("_LEAVES")
                && !name.endsWith("_LOG")
                && !name.endsWith("_WOOD")
                && !name.endsWith("_STEM")
                && !name.endsWith("_HYPHAE")
                && material != Material.BAMBOO_BLOCK
                && material != Material.CACTUS
                && material != Material.MUSHROOM_STEM;
    }

    private int distanceOutside(
            int x,
            int z,
            int minX,
            int maxX,
            int minZ,
            int maxZ) {
        int dx = x < minX
                ? minX - x
                : Math.max(0, x - maxX);
        int dz = z < minZ
                ? minZ - z
                : Math.max(0, z - maxZ);
        return Math.max(dx, dz);
    }

    private Material terrainTopMaterial(
            int x,
            int z,
            int distance,
            int feather) {
        int selector = Math.floorMod(x * 31 + z * 17, 11);
        if (distance > 0 && distance >= Math.max(1, feather - 1)) {
            return selector % 3 == 0
                    ? Material.MOSS_BLOCK
                    : Material.GRASS_BLOCK;
        }
        if (selector == 0) {
            return Material.COARSE_DIRT;
        }
        if (selector == 1) {
            return Material.MOSS_BLOCK;
        }
        return Material.GRASS_BLOCK;
    }

    /**
     * Retourne le module de progression lorsqu'il est disponible. Village
     * reste compatible avec un chargement isolé dans les tests.
     */
    private GlobalProgressionManager getGlobalProgression() {
        if (plugin instanceof MinePlugin minePlugin) {
            return minePlugin.getGlobalProgressionManager();
        }
        return null;
    }

    /**
     * Retourne le gestionnaire de garnison sans rendre Village dépendant d'un
     * environnement de test qui n'instancie pas MinePlugin.
     */
    private VillageKnightManager getVillageKnightManager() {
        if (plugin instanceof MinePlugin minePlugin) {
            return minePlugin.getVillageKnightManager();
        }
        return null;
    }

    /**
     * Annule la génération active ou terminée et restaure les blocs dans
     * l'ordre inverse de leur première modification.
     */
    private boolean undoVillage() {
        VillageGenerationSession session = currentSession;
        if (session == null) {
            return false;
        }

        cancelActiveBuild();
        rollbackSession(session);
        return true;
    }

    private void cancelActiveBuild() {
        if (activeBuildTask != null) {
            activeBuildTask.cancel();
            activeBuildTask = null;
        }
    }

    private void rollbackSession(VillageGenerationSession session) {
        if (session == null) {
            return;
        }

        /*
         * On détache la session avant la restauration : aucune commande ou
         * tâche résiduelle ne peut alors enregistrer les blocs restaurés.
         */
        if (currentSession == session) {
            currentSession = null;
        }

        VillageKnightManager knightManager = getVillageKnightManager();
        if (knightManager != null) {
            knightManager.unregisterVillage(session.getVillageId());
        }

        for (UUID id : session.getGeneratedEntities()) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }

        List<Map.Entry<Location, BlockData>> originals =
                new ArrayList<>(session.getOriginalBlocks().entrySet());
        Collections.reverse(originals);
        GlobalProgressionManager progression = getGlobalProgression();
        for (Map.Entry<Location, BlockData> entry : originals) {
            Location location = entry.getKey();
            if (location.getWorld() == null) {
                continue;
            }
            Block block = location.getBlock();
            block.setBlockData(
                    entry.getValue().clone(),
                    false
            );
            if (progression != null) {
                progression.synchronizeArtificialBlock(
                        block,
                        session.wasOriginalArtificial(location)
                );
            }
        }

        try {
            VillageEntityManager.cleanup(
                    plugin,
                    session.getVillageId()
            );
        } catch (Throwable throwable) {
            plugin.getLogger().warning(
                    "Nettoyage des entités du village incomplet : "
                            + throwable.getClass().getSimpleName()
            );
        }
    }
}
