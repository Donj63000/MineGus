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
import org.bukkit.scheduler.BukkitTask;
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
 * Commande de gÃ©nÃ©ration du village mÃ©diÃ©val.
 *
 * <p>La gÃ©nÃ©ration est prÃ©parÃ©e intÃ©gralement avant d'Ãªtre exÃ©cutÃ©e par lots.
 * Chaque tÃ¢che capture sa propre session : une seconde commande ne peut donc
 * jamais dÃ©tourner les Ã©critures ou les entitÃ©s de la gÃ©nÃ©ration en cours.</p>
 *
 * <p>Commandes :</p>
 * <ul>
 *     <li>{@code /village} : construit un village semi-organique ;</li>
 *     <li>{@code /village undo} : restaure l'Ã©tat antÃ©rieur Ã  la gÃ©nÃ©ration.</li>
 * </ul>
 */
public final class Village implements CommandExecutor {

    private static final int VILLAGER_SPAWNERS = 4;
    private static final int GOLEM_SPAWNERS = 2;
    private static final int DEFAULT_WALL_GAP = 7;
    private static final int TERRAIN_FEATHER = 4;
    private static final int MAX_ACTIONS_PER_TICK = 320;
    private static final long MAX_BATCH_NANOS = 8_000_000L;

    private final Random rng = new Random();
    private final JavaPlugin plugin;
    private final int wallGap;
    private final VillageLayoutSettings layoutSettings;

    /*
     * Ces deux champs sont conservÃ©s pour la compatibilitÃ© avec les tests et
     * intÃ©grations historiques qui interrogeaient la distribution de spawners.
     */
    private Set<Integer> villagerSpawnerIdx = Collections.emptySet();
    private int currentHouseIdx;

    private VillageGenerationSession currentSession;
    private BukkitTask activeBuildTask;

    public Village(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(plugin.getCommand("village"),
                "La commande 'village' doit Ãªtre dÃ©clarÃ©e dans plugin.yml")
                .setExecutor(this);

        FileConfiguration cfg = plugin.getConfig();
        int rows = cfg.getInt("village.rows", 4);
        int cols = cfg.getInt("village.cols", 5);
        int houseSmall = cfg.getInt("village.houseSmall", 9);
        int houseBig = cfg.getInt("village.houseBig", 11);
        int roadHalf = cfg.getInt("village.roadHalf", 2);
        int spacing = cfg.getInt("village.spacing", 20);
        int configuredPlazaSize = cfg.getInt("village.plazaSize", 13);

        this.wallGap = Math.max(5,
                cfg.getInt("village.wallGap", DEFAULT_WALL_GAP));
        this.layoutSettings = new VillageLayoutSettings(
                cfg.getString("village.layout-style", "semi_organic"),
                rows,
                cols,
                houseSmall,
                houseBig,
                spacing,
                roadHalf,
                configuredPlazaSize,
                cfg.getInt("village.houseCountMin", 12),
                cfg.getInt("village.houseCountMax", 16),
                cfg.getInt("village.mainStreetHalf", 2),
                cfg.getInt("village.sideStreetHalf", 1),
                cfg.getInt("village.terrainMaxStep", 2),
                cfg.getString("village.decorDensity", "high")
        );
    }

    /**
     * API historique : sÃ©lectionne jusqu'Ã  quatre maisons rÃ©parties de faÃ§on
     * homogÃ¨ne. Le gÃ©nÃ©rateur moderne applique le mÃªme principe aux vrais lots
     * rÃ©sidentiels, aprÃ¨s planification.
     */
    public void prepareVillagerSpawnerDistribution(int totalHouses) {
        Set<Integer> chosen = new LinkedHashSet<>();
        if (totalHouses <= 0) {
            villagerSpawnerIdx = chosen;
            currentHouseIdx = 0;
            return;
        }

        int desired = Math.min(VILLAGER_SPAWNERS, totalHouses);
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
            sender.sendMessage("Commande rÃ©servÃ©e aux joueurs.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("undo")) {
            boolean restored = undoVillage();
            player.sendMessage(restored
                    ? ChatColor.YELLOW + "Le village a Ã©tÃ© annulÃ© et le terrain restaurÃ©."
                    : ChatColor.GRAY + "Aucun village gÃ©nÃ©rÃ© n'est actuellement enregistrÃ©.");
            return true;
        }

        if (currentSession != null) {
            player.sendMessage(ChatColor.YELLOW
                    + "Un village est dÃ©jÃ  prÃ©sent ou en construction. "
                    + "Utilisez /village undo avant d'en gÃ©nÃ©rer un autre.");
            return true;
        }

        if (generateVillageAsync(player.getLocation())) {
            player.sendMessage(ChatColor.GREEN
                    + "Construction du village lancÃ©e autour de votre position.");
        } else {
            player.sendMessage(ChatColor.RED
                    + "Le village ne peut pas Ãªtre construit Ã  cet emplacement.");
        }
        return true;
    }

    /**
     * PrÃ©pare puis lance une gÃ©nÃ©ration isolÃ©e.
     *
     * @return {@code true} lorsque la gÃ©nÃ©ration a pu Ãªtre planifiÃ©e.
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
        if (baseY < world.getMinHeight() + 3
                || baseY > world.getMaxHeight() - 24) {
            plugin.getLogger().warning(
                    "Altitude incompatible avec un village : y=" + baseY);
            return false;
        }

        /*
         * Le centre de composition reste exactement le point de commande. Le
         * centre gÃ©omÃ©trique des bornes peut Ãªtre dÃ©calÃ© par un quartier plus
         * large ; l'utiliser dÃ©calait auparavant la rue principale du portail.
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
        currentSession = session;
        logLayoutSummary(layout);

        Queue<Runnable> todo = new LinkedList<>();

        /*
         * L'emprise est calculÃ©e depuis la muraille rÃ©elle, tours comprises.
         * Le sud est prolongÃ© pour accueillir le parvis et la route extÃ©rieure
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
         * Le mur est symÃ©trique autour du centre alors que l'Ã©glise Ã©tire le
         * plan vers le nord. Sans ce raccord, il pouvait rester une bande
         * d'herbe entre l'extrÃ©mitÃ© sud de la grand-rue et le corps de garde.
         */
        todo.addAll(buildGateRoadConnector(
                session,
                world,
                layout,
                centerZ + rz,
                baseY
        ));

        /*
         * Les spawners sont placÃ©s sous les maisons rÃ©ellement retenues par le
         * planificateur, et non sur quatre coordonnÃ©es arbitraires susceptibles
         * de tomber dans une route ou un bÃ¢timent.
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
         * WallBuilder ne modifie pas immÃ©diatement le monde : il ajoute ses
         * tÃ¢ches Ã  la file. L'appeler ici Ã©vite de muter la file depuis une
         * tÃ¢che dÃ©jÃ  en cours d'itÃ©ration.
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

        final int resolvedVillageId = villageId;
        if (villageEntitiesEnabled) {
            todo.add(() -> GateGuardManager.ensureGuards(
                    plugin,
                    gateAnchor,
                    resolvedVillageId
            ));
        }

        final int ttlTicks = 20 * 60 * 30;
        if (villageEntitiesEnabled) {
            todo.add(() -> VillageEntityManager.spawnInitial(
                    plugin,
                    villageCenter,
                    session.getAnchors(),
                    resolvedVillageId,
                    ttlTicks
            ));
        }

        Location mayorAnchor = layout.anchors()
                .getOrDefault("mayor", marketAnchor);
        todo.add(() -> spawnVillager(
                session,
                world,
                mayorAnchor.clone().add(0.5D, 1.0D, 0.5D),
                "Maire"
        ));

        buildActionsInBatches(todo, session);
        return true;
    }

    /**
     * Ã‰crit un matÃ©riau en conservant le premier Ã©tat rencontrÃ©.
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
        if (session != null) {
            session.rememberOriginal(
                    block.getLocation(),
                    block.getBlockData()
            );
        }
        block.setType(material, false);
    }

    /**
     * Ã‰crit des donnÃ©es de bloc orientÃ©es en conservant l'Ã©tat initial.
     */
    public voidß]ú¶‰žËkºwµçq±…•ÉMÁ…Ý¹•É¥ÍÑÉ¥‰ÕÑ¥½¸¡¡½ÕÍ•Ì¹Í¥é” ¤¤ì4(4(€€€€€€€1¥ÍÐñIÕ¹¹…‰±”ø…Ñ¥½¹Ì€ô¹•ÜÉÉ…å1¥ÍÐðø ¤ì4(€€€€€€€™½È€¡¥¹Ð¥¹‘•à€ô€Àì¥¹‘•à€ð¡½ÕÍ•Ì¹Í¥é” ¤ì¥¹‘•à¬¬¤ì4(€€€€€€€€€€€¥˜€ …Ù¥±±…•ÉMÁ…Ý¹•É%‘à¹½¹Ñ…¥¹Ì¡¥¹‘•à¤¤ì4(€€€€€€€€€€€€€€€½¹Ñ¥¹Õ”ì4(€€€€€€€€€€€ô4(4(€€€€€€€€€€€Y¥±±…•1…å½ÕÑA±…¸¹1½ÑA±…¸±½Ð€ô¡½ÕÍ•Ì¹•Ð¡¥¹‘•à¤ì4(€€€€€€€€€€€¥¹ÐÍÁ…Ý¹•Éd€ô‰…Í•d€¬±½Ð¹Ñ•ÉÉ…•d ¤€´€Äì4(€€€€€€€€€€€…Ñ¥½¹Ì¹…‘¡É•…Ñ•MÁ…Ý¹•ÉÑ¥½¸ 4(€€€€€€€€€€€€€€€€€€€Í•ÍÍ¥½¸°4(€€€€€€€€€€€€€€€€€€€Ý½É±°4(€€€€€€€€€€€€€€€€€€€±½Ð¹•¹Ñ•É` ¤°4(€€€€€€€€€€€€€€€€€€€ÍÁ…Ý¹•Éd°4(€€€€€€€€€€€€€€€€€€€±½Ð¹•¹Ñ•Éh ¤°4(€€€€€€€€€€€€€€€€€€€¹Ñ¥ÑåQåÁ”¹Y%11H4(€€€€€€€€€€€€¤¤ì4(€€€€€€€ô4(€€€€€€€É•ÑÕÉ¸…Ñ¥½¹Ìì4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”1¥ÍÐñIÕ¹¹…‰±”ø‰Õ¥±‘½±•µMÁ…Ý¹•ÉÌ 4(€€€€€€€€€€€Y¥±±…••¹•É…Ñ¥½¹M•ÍÍ¥½¸Í•ÍÍ¥½¸°4(€€€€€€€€€€€]½É±Ý½É±°4(€€€€€€€€€€€Y¥±±…•1…å½ÕÑA±…¸±…å½ÕÐ°4(€€€€€€€€€€€¥¹Ð‰…Í•d¤ì4(€€€€€€€1¥ÍÐñIÕ¹¹…‰±”ø…Ñ¥½¹Ì€ô¹•ÜÉÉ…å1¥ÍÐðø ¤ì4(€€€€€€€1½…Ñ¥½¸Á±…é„€ô±…å½ÕÐ¹…¹¡½ÉÌ ¤¹•Ð ‰Á±…é„ˆ¤ì4(€€€€€€€¥˜€¡Á±…é„€ôô¹Õ±°¤ì4(€€€€€€€€€€€É•ÑÕÉ¸…Ñ¥½¹Ìì4(€€€€€€€ô4(4(€€€€€€€¥¹Ð½™™Í•Ð€ô5…Ñ ¹µ…à 4(€€€€€€€€€€€€€€€€Ì°4(€€€€€€€€€€€€€€€±…å½ÕÑM•ÑÑ¥¹Ì¹•™™•Ñ¥Ù•A±…é…M¥é” ¤€¼€È€´€È4(€€€€€€€€¤ì4(€€€€€€€™½È€¡¥¹Ð¤€ô€Àì¤€ð=15}MA]9ILì¤¬¬¤ì4(€€€€€€€€€€€¥¹ÐÍ¥¸€ô¤€ôô€À€ü€´Ä€è€Äì4(€€€€€€€€€€€…Ñ¥½¹Ì¹…‘¡É•…Ñ•MÁ…Ý¹•ÉÑ¥½¸ 4(€€€€€€€€€€€€€€€€€€€Í•ÍÍ¥½¸°4(€€€€€€€€€€€€€€€€€€€Ý½É±°4(€€€€€€€€€€€€€€€€€€€Á±…é„¹•Ñ	±½­` ¤€¬Í¥¸€¨½™™Í•Ð°4(€€€€€€€€€€€€€€€€€€€‰…Í•d€´€È°4(€€€€€€€€€€€€€€€€€€€Á±…é„¹•Ñ	±½­h ¤°4(€€€€€€€€€€€€€€€€€€€¹Ñ¥ÑåQåÁ”¹%I=9}=144(€€€€€€€€€€€€¤¤ì4(€€€€€€€ô4(€€€€€€€É•ÑÕÉ¸…Ñ¥½¹Ìì4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”Ù½¥±½1…å½ÕÑMÕµµ…Éä¡Y¥±±…•1…å½ÕÑA±…¸±…å½ÕÐ¤ì4(€€€€€€€¹Õµ5…ÀñY¥±±…•1…å½ÕÑA±…¸¹1½ÑI½±”°%¹Ñ••Èø½Õ¹ÑÌ€ô4(€€€€€€€€€€€€€€€¹•Ü¹Õµ5…Àðø¡Y¥±±…•1…å½ÕÑA±…¸¹1½ÑI½±”¹±…ÍÌ¤ì4(€€€€€€€¥¹ÐÑ•ÉÉ…•‘1½ÑÌ€ô€Àì4(€€€€€€€™½È€¡Y¥±±…•1…å½ÕÑA±…¸¹1½ÑA±…¸±½Ð€è±…å½ÕÐ¹±½ÑÌ ¤¤ì4(€€€€€€€€€€€½Õ¹ÑÌ¹µ•É”¡±½Ð¹É½±” ¤°€Ä°%¹Ñ••ÈèéÍÕ´¤ì4(€€€€€€€€€€€¥˜€¡±½Ð¹Ñ•ÉÉ…•d ¤€ø€À¤ì4(€€€€€€€€€€€€€€€Ñ•ÉÉ…•‘1½ÑÌ¬¬ì4(€€€€€€€€€€€ô4(€€€€€€€ô4(4(€€€€€€€Á±Õ¥¸¹•Ñ1½•È ¤¹¥¹™¼ 4(€€€€€€€€€€€€€€€€‰A±…¸‘ÔÙ¥±±…”€èµ…¥Í½¹Ìôˆ€¬±…å½ÕÐ¹¡½ÕÍ•½Õ¹Ð ¤4(€€€€€€€€€€€€€€€€€€€€€€€€¬€ˆ°ÉÕ•Ìôˆ€¬±…å½ÕÐ¹ÍÑÉ••ÑÌ ¤¹Í¥é” ¤4(€€€€€€€€€€€€€€€€€€€€€€€€¬€ˆ°Ñ•ÉÉ…ÍÍ•Ìôˆ€¬Ñ•ÉÉ…•‘1½ÑÌ4(€€€€€€€€€€€€€€€€€€€€€€€€¬€ˆ°‘¥µ•¹Í¥½¹Ìôˆ€¬±…å½ÕÐ¹‰½Õ¹‘Ì ¤¹Ý¥‘Ñ  ¤4(€€€€€€€€€€€€€€€€€€€€€€€€¬€‰àˆ€¬±…å½ÕÐ¹‰½Õ¹‘Ì ¤¹‘•ÁÑ  ¤4(€€€€€€€€¤ì4(€€€€€€€Á±Õ¥¸¹•Ñ1½•È ¤¹¥¹™¼ ‰K¥Á…ÉÑ¥Ñ¥½¸‘•Ì±½ÑÌ€è€ˆ€¬½Õ¹ÑÌ¤ì4(€€€ô4(4(€€€€¼¨¨4(€€€€€¨ã¥ÕÑ”±„™¥±”…Ù•ŒÕ¹”‘½Õ‰±”±¥µ¥Ñ”€¡¹½µ‰É”…Ñ¥½¹Ì•ÐÑ•µÁÌAT¤4(€€€€€¨…™¥¸ÅÕ”±•Ì½±½¹¹•Ì‘”Ñ•ÉÉ…ÍÍ•µ•¹Ð¹”™¥•¹ÐÁ…Ì±”Í•ÉÙ•ÕÈ¸4(€€€€€¨¼4(€€€ÁÉ¥Ù…Ñ”Ù½¥‰Õ¥±‘Ñ¥½¹Í%¹	…Ñ¡•Ì 4(€€€€€€€€€€€EÕ•Õ”ñIÕ¹¹…‰±”øÅÕ•Õ”°4(€€€€€€€€€€€Y¥±±…••¹•É…Ñ¥½¹M•ÍÍ¥½¸Í•ÍÍ¥½¸¤ì4(€€€€€€€	Õ­­¥ÑIÕ¹¹…‰±”ÉÕ¹¹•È€ô¹•Ü	Õ­­¥ÑIÕ¹¹…‰±” ¤ì4(€€€€€€€€€€€=Ù•ÉÉ¥‘”4(€€€€€€€€€€€ÁÕ‰±¥ŒÙ½¥ÉÕ¸ ¤ì4(€€€€€€€€€€€€€€€¥˜€¡ÕÉÉ•¹ÑM•ÍÍ¥½¸€„ôÍ•ÍÍ¥½¸¤ì4(€€€€€€€€€€€€€€€€€€€±•…ÉÑ¥Ù•Q…Í¬ ¤ì4(€€€€€€€€€€€€€€€€€€€…¹•° ¤ì4(€€€€€€€€€€€€€€€€€€€É•ÑÕÉ¸ì4(€€€€€€€€€€€€€€€ô4(4(€€€€€€€€€€€€€€€±½¹œÍÑ…ÉÑ•‘Ð€ôMåÍÑ•´¹¹…¹½Q¥µ” ¤ì4(€€€€€€€€€€€€€€€¥¹ÐÁÉ½•ÍÍ•€ô€Àì4(€€€€€€€€€€€€€€€ÑÉäì4(€€€€€€€€€€€€€€€€€€€Ý¡¥±”€¡ÁÉ½•ÍÍ•€ð5a}Q%=9M}AI}Q%,4(€€€€€€€€€€€€€€€€€€€€€€€€€€€€˜˜€…ÅÕ•Õ”¹¥ÍµÁÑä ¤4(€€€€€€€€€€€€€€€€€€€€€€€€€€€€˜˜MåÍÑ•´¹¹…¹½Q¥µ” ¤€´ÍÑ…ÉÑ•‘Ð4(€€€€€€€€€€€€€€€€€€€€€€€€€€€€ð5a}	Q!}99=L¤ì4(€€€€€€€€€€€€€€€€€€€€€€€IÕ¹¹…‰±”…Ñ¥½¸€ôÅÕ•Õ”¹Á½±° ¤ì4(€€€€€€€€€€€€€€€€€€€€€€€¥˜€¡…Ñ¥½¸€„ô¹Õ±°¤ì4(€€€€€€€€€€€€€€€€€€€€€€€€€€€…Ñ¥½¸¹ÉÕ¸ ¤ì4(€€€€€€€€€€€€€€€€€€€€€€€€€€€ÁÉ½•ÍÍ•¬¬ì4(€€€€€€€€€€€€€€€€€€€€€€€ô4(€€€€€€€€€€€€€€€€€€€ô4(€€€€€€€€€€€€€€€ô…Ñ €¡Q¡É½Ý…‰±”Ñ¡É½Ý…‰±”¤ì4(€€€€€€€€€€€€€€€€€€€Á±Õ¥¸¹•Ñ1½•È ¤¹±½œ 4(€€€€€€€€€€€€€€€€€€€€€€€€€€€1•Ù•°¹MYI°4(€€€€€€€€€€€€€€€€€€€€€€€€€€€€‰1„Ÿ¥»¥É…Ñ¥½¸‘ÔÙ¥±±…”„ƒ¥¡½×¤€ì€ˆ4(€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€¬€‰±•Ìµ½‘¥™¥…Ñ¥½¹ÌÙ½¹Ðƒ©ÑÉ”É•ÍÑ…ÕË¥•Ì¸ˆ°4(€€€€€€€€€€€€€€€€€€€€€€€€€€€Ñ¡É½Ý…‰±”4(€€€€€€€€€€€€€€€€€€€€¤ì4(€€€€€€€€€€€€€€€€€€€±•…ÉÑ¥Ù•Q…Í¬ ¤ì4(€€€€€€€€€€€€€€€€€€€…¹•° ¤ì4(€€€€€€€€€€€€€€€€€€€É½±±‰…­M•ÍÍ¥½¸¡Í•ÍÍ¥½¸¤ì4(€€€€€€€€€€€€€€€€€€€É•ÑÕÉ¸ì4(€€€€€€€€€€€€€€€ô4(4(€€€€€€€€€€€€€€€¥˜€¡ÅÕ•Õ”¹¥ÍµÁÑä ¤¤ì4(€€€€€€€€€€€€€€€€€€€Á±Õ¥¸¹•Ñ1½•È ¤¹¥¹™¼ 4(€€€€€€€€€€€€€€€€€€€€€€€€€€€€‰Y¥±±…”€ˆ€¬Í•ÍÍ¥½¸¹•ÑY¥±±…•% ¤4(€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€¬€ˆ½¹ÍÑÉÕ¥Ð…Ù•ŒÍÕ¡Ì¸ˆ4(€€€€€€€€€€€€€€€€€€€€¤ì4(€€€€€€€€€€€€€€€€€€€±•…ÉÑ¥Ù•Q…Í¬ ¤ì4(€€€€€€€€€€€€€€€€€€€…¹•° ¤ì4(€€€€€€€€€€€€€€€ô4(€€€€€€€€€€€ô4(€€€€€€€ôì4(4(€€€€€€€…Ñ¥Ù•	Õ¥±‘Q…Í¬€ôÉÕ¹¹•È¹ÉÕ¹Q…Í­Q¥µ•È¡Á±Õ¥¸°€Å0°€Å0¤ì4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”Ù½¥±•…ÉÑ¥Ù•Q…Í¬ ¤ì4(€€€€€€€…Ñ¥Ù•	Õ¥±‘Q…Í¬€ô¹Õ±°ì4(€€€ô4(4(€€€€¼¨¨4(€€€€€¨AË¥Á…É”Õ¹”Ñ•ÉÉ…ÍÍ”ÁÉ¥¹¥Á…±”•ÐÕ¹”ÑÉ…¹Í¥Ñ¥½¸É…‘Õ•±±”Ù•ÉÌ±”4(€€€€€¨É•±¥•˜¹…ÑÕÉ•°¸¡…ÅÕ”½±½¹¹”•ÍÐÕ¹”Í•Õ±”Ó‰¡”Á½ÕÈµ‡¹ÑÉ¥Í•È±„4(€€€€€¨Ñ…¥±±”‘”±„™¥±”°µ…¥Ì¡…ÅÕ”‰±½Œµ½‘¥™§¤É•ÍÑ”¥¹‘¥Ù¥‘Õ•±±•µ•¹ÐÍÕ¥Ù¤¸4(€€€€€¨¼4(€€€ÁÉ¥Ù…Ñ”1¥ÍÐñIÕ¹¹…‰±”øÁÉ•Á…É•É½Õ¹‘Ñ¥½¹Ì 4(€€€€€€€€€€€Y¥±±…••¹•É…Ñ¥½¹M•ÍÍ¥½¸Í•ÍÍ¥½¸°4(€€€€€€€€€€€]½É±Ý½É±°4(€€€€€€€€€€€¥¹Ð™±…Ñ5¥¹`°4(€€€€€€€€€€€¥¹Ð™±…Ñ5…á`°4(€€€€€€€€€€€¥¹Ð™±…Ñ5¥¹h°4(€€€€€€€€€€€¥¹Ð™±…Ñ5…áh°4(€€€€€€€€€€€¥¹Ð‰…Í•d°4(€€€€€€€€€€€¥¹Ð™•…Ñ¡•È¤ì4(€€€€€€€1¥ÍÐñIÕ¹¹…‰±”ø…Ñ¥½¹Ì€ô¹•ÜÉÉ…å1¥ÍÐðø ¤ì4(€€€€€€€¥¹ÐÍ…™••…Ñ¡•È€ô5…Ñ ¹µ…à À°™•…Ñ¡•È¤ì4(€€€€€€€¥¹Ðµ¥¹`€ô™±…Ñ5¥¹`€´Í…™••…Ñ¡•Èì4(€€€€€€€¥¹Ðµ…á`€ô™±…Ñ5…á`€¬Í…™••…Ñ¡•Èì4(€€€€€€€¥¹Ðµ¥¹h€ô™±…Ñ5¥¹h€´Í…™••…Ñ¡•Èì4(€€€€€€€¥¹Ðµ…áh€ô™±…Ñ5…áh€¬Í…™••…Ñ¡•Èì4(4(€€€€€€€™½È€¡¥¹Ðà€ôµ¥¹`ìà€ðôµ…á`ìà¬¬¤ì4(€€€€€€€€€€€™½È€¡¥¹Ðè€ôµ¥¹hìè€ðôµ…áhìè¬¬¤ì4(€€€€€€€€€€€€€€€™¥¹…°¥¹Ð½±Õµ¹`€ôàì4(€€€€€€€€€€€€€€€™¥¹…°¥¹Ð½±Õµ¹h€ôèì4(€€€€€€€€€€€€€€€…Ñ¥½¹Ì¹…‘  ¤€´øÁÉ•Á…É•É½Õ¹‘½±Õµ¸ 4(€€€€€€€€€€€€€€€€€€€€€€€Í•ÍÍ¥½¸°4(€€€€€€€€€€€€€€€€€€€€€€€Ý½É±°4(€€€€€€€€€€€€€€€€€€€€€€€½±Õµ¹`°4(€€€€€€€€€€€€€€€€€€€€€€€½±Õµ¹h°4(€€€€€€€€€€€€€€€€€€€€€€€™±…Ñ5¥¹`°4(€€€€€€€€€€€€€€€€€€€€€€€™±…Ñ5…á`°4(€€€€€€€€€€€€€€€€€€€€€€€™±…Ñ5¥¹h°4(€€€€€€€€€€€€€€€€€€€€€€€™±…Ñ5…áh°4(€€€€€€€€€€€€€€€€€€€€€€€‰…Í•d°4(€€€€€€€€€€€€€€€€€€€€€€€Í…™••…Ñ¡•È4(€€€€€€€€€€€€€€€€¤¤ì4(€€€€€€€€€€€ô4(€€€€€€€ô4(€€€€€€€É•ÑÕÉ¸…Ñ¥½¹Ìì4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”Ù½¥ÁÉ•Á…É•É½Õ¹‘½±Õµ¸ 4(€€€€€€€€€€€Y¥±±…••¹•É…Ñ¥½¹M•ÍÍ¥½¸Í•ÍÍ¥½¸°4(€€€€€€€€€€€]½É±Ý½É±°4(€€€€€€€€€€€¥¹Ðà°4(€€€€€€€€€€€¥¹Ðè°4(€€€€€€€€€€€¥¹Ð™±…Ñ5¥¹`°4(€€€€€€€€€€€¥¹Ð™±…Ñ5…á`°4(€€€€€€€€€€€¥¹Ð™±…Ñ5¥¹h°4(€€€€€€€€€€€¥¹Ð™±…Ñ5…áh°4(€€€€€€€€€€€¥¹Ð‰…Í•d°4(€€€€€€€€€€€¥¹Ð™•…Ñ¡•È¤ì4(€€€€€€€¥¹Ð¡¥¡•ÍÑd€ôÝ½É±¹•Ñ!¥¡•ÍÑ	±½­eÐ¡à°è¤ì4(€€€€€€€¥¹Ð¹…ÑÕÉ…±d€ô™¥¹‘9…ÑÕÉ…±É½Õ¹‘d¡Ý½É±°à°è°‰…Í•d¤ì4(€€€€€€€¥¹Ð‘¥ÍÑ…¹”€ô‘¥ÍÑ…¹•=ÕÑÍ¥‘” 4(€€€€€€€€€€€€€€€à°4(€€€€€€€€€€€€€€€è°4(€€€€€€€€€€€€€€€™±…Ñ5¥¹`°4(€€€€€€€€€€€€€€€™±…Ñ5…á`°4(€€€€€€€€€€€€€€€™±…Ñ5¥¹h°4(€€€€€€€€€€€€€€€™±…Ñ5…áh4(€€€€€€€€¤ì4(4(€€€€€€€¥¹ÐÑ…É•Ñdì4(€€€€€€€¥˜€¡‘¥ÍÑ…¹”€ðô€Àñð™•…Ñ¡•È€ôô€À¤ì4(€€€€€€€€€€€Ñ…É•Ñd€ô‰…Í•dì4(€€€€€€€ô•±Í”ì4(€€€€€€€€€€€€¼¨4(€€€€€€€€€€€€€¨1„‘•É¹§¡É”½ÕÉ½¹¹”ÁË¥Á…Ë¥”É•©½¥¹Ð•á…Ñ•µ•¹Ð±”É•±¥•˜4(€€€€€€€€€€€€€¨¹…ÑÕÉ•°¸Ù•Œí½‘”™•…Ñ¡•È€¬€Åô°¥°É•ÍÑ…¥Ð•¹½É”€ÈÀ€”‘”4(€€€€€€€€€€€€€¨Ñ•ÉÉ…ÍÍ•µ•¹Ð…Ô‰½É°ÁÕ¥ÌÕ¹”µ…É¡”‰ÉÕÑ…±”¡½ÉÌ‘”±„é½¹”¸4(€€€€€€€€€€€€€¨¼4(€€€€€€€€€€€‘½Õ‰±”É…Ñ¥¼€ô5…Ñ ¹µ¥¸ 4(€€€€€€€€€€€€€€€€€€€€Ä¸Á°4(€€€€€€€€€€€€€€€€€€€‘¥ÍÑ…¹”€¼€¡‘½Õ‰±”¤5…Ñ ¹µ…à Ä°™•…Ñ¡•È¤4(€€€€€€€€€€€€¤ì4(€€€€€€€€€€€Ñ…É•Ñd€ô€¡¥¹Ð¤5…Ñ ¹É½Õ¹ 4(€€€€€€€€€€€€€€€€€€€‰…Í•d€¬€¡¹…ÑÕÉ…±d€´‰…Í•d¤€¨É…Ñ¥¼4(€€€€€€€€€€€€¤ì4(€€€€€€€ô4(€€€€€€€Ñ…É•Ñd€ô5…Ñ ¹µ…à 4(€€€€€€€€€€€€€€€Ý½É±¹•Ñ5¥¹!•¥¡Ð ¤€¬€È°4(€€€€€€€€€€€€€€€5…Ñ ¹µ¥¸¡Ý½É±¹•Ñ5…á!•¥¡Ð ¤€´€È°Ñ…É•Ñd¤4(€€€€€€€€¤ì4(4(€€€€€€€€¼¨4(€€€€€€€€€¨=¸É•Ñ¥É”…ÕÍÍ¤±•ÌÑÉ½¹Ì•Ð™•Õ¥±±…•Ì…Ôµ‘•ÍÍÕÌ‘ÔÍ½°¥‰±”¸4(€€€€€€€€€¨½¹ÑÉ…¥É•µ•¹Ðƒ€°…¹¥•¹¹”Ù•ÉÍ¥½¸°¡…ÅÕ”‰±½Œ…¥È•ÍÐ·¥µ½É¥Ï¤4(€€€€€€€€€¨•ÐÁ½ÕÉÉ„‘½¹Œƒ©ÑÉ”É•ÍÑ…ÕË¤Á…È€½Ù¥±±…”Õ¹‘¼¸4(€€€€€€€€€¨¼4(€€€€€€€¥¹Ð±•…ÉQ½À€ô5…Ñ ¹µ¥¸ 4(€€€€€€€€€€€€€€€Ý½É±¹•Ñ5…á!•¥¡Ð ¤€´€Ä°4(€€€€€€€€€€€€€€€€¼¨4(€€€€€€€€€€€€€€€€€¨1•Ì¹½ÕÙ•±±•ÌÑ½ÕÉÌ•Ð±”£‰Ñ•±•ÐÕ±µ¥¹•¹Ðƒ€•¹Ù¥É½¸4(€€€€€€€€€€€€€€€€€¨Ù¥¹Ðµ‘•Õà‰±½Ì…Ôµ‘•ÍÍÕÌ‘ÔÍ½°¸1„µ…É”‘”Ù¥¹ÐµÅÕ…ÑÉ”4(€€€€€€€€€€€€€€€€€¨‰±½Ìƒ¥Ù¥Ñ”ÅÔÕ¸ÑÉ½¹Œ½ÔÕ¹”…¹½Ã¥”ÑÉ…Ù•ÉÍ”±•ÕÈÑ½¥ÑÕÉ”¸4(€€€€€€€€€€€€€€€€€¨¼4(€€€€€€€€€€€€€€€5…Ñ ¹µ…à¡¡¥¡•ÍÑd°Ñ…É•Ñd€¬€ÈÐ¤4(€€€€€€€€¤ì4(€€€€€€€™½È€¡¥¹Ðä€ôÑ…É•Ñd€¬€Äìä€ðô±•…ÉQ½Àìä¬¬¤ì4(€€€€€€€€€€€	±½¬‰±½¬€ôÝ½É±¹•Ñ	±½­Ð¡à°ä°è¤ì4(€€€€€€€€€€€¥˜€ …‰±½¬¹•ÑQåÁ” ¤¹¥Í¥È ¤¤ì4(€€€€€€€€€€€€€€€Í•Ñ	±½­QÉ…­• 4(€€€€€€€€€€€€€€€€€€€€€€€Í•ÍÍ¥½¸°4(€€€€€€€€€€€€€€€€€€€€€€€Ý½É±°4(€€€€€€€€€€€€€€€€€€€€€€€à°4(€€€€€€€€€€€€€€€€€€€€€€€ä°4(€€€€€€€€€€€€€€€€€€€€€€€è°4(€€€€€€€€€€€€€€€€€€€€€€€5…Ñ•É¥…°¹%H4(€€€€€€€€€€€€€€€€¤ì4(€€€€€€€€€€€ô4(€€€€€€€ô4(4(€€€€€€€¥˜€¡¹…ÑÕÉ…±d€ðÑ…É•Ñd¤ì4(€€€€€€€€€€€™½È€¡¥¹Ðä€ô¹…ÑÕÉ…±d€¬€Äìä€ðÑ…É•Ñdìä¬¬¤ì4(€€€€€€€€€€€€€€€5…Ñ•É¥…°™¥±°€ôä€ðÑ…É•Ñd€´€Ì4(€€€€€€€€€€€€€€€€€€€€€€€€ü5…Ñ•É¥…°¹MQ=94(€€€€€€€€€€€€€€€€€€€€€€€€è5…Ñ•É¥…°¹%IPì4(€€€€€€€€€€€€€€€Í•Ñ	±½­QÉ…­•¡Í•ÍÍ¥½¸°Ý½É±°à°ä°è°™¥±°¤ì4(€€€€€€€€€€€ô4(€€€€€€€ô4(4(€€€€€€€Í•Ñ	±½­QÉ…­• 4(€€€€€€€€€€€€€€€Í•ÍÍ¥½¸°4(€€€€€€€€€€€€€€€Ý½É±°4(€€€€€€€€€€€€€€€à°4(€€€€€€€€€€€€€€€Ñ…É•Ñd€´€Ä°4(€€€€€€€€€€€€€€€è°4(€€€€€€€€€€€€€€€5…Ñ ¹™±½½É5½¡à€¨€ÄÜ€¬è€¨€ÌÄ°€Ü¤€ôô€À4(€€€€€€€€€€€€€€€€€€€€€€€€ü5…Ñ•É¥…°¹=IM}%IP4(€€€€€€€€€€€€€€€€€€€€€€€€è5…Ñ•É¥…°¹%IP4(€€€€€€€€¤ì4(€€€€€€€Í•Ñ	±½­QÉ…­• 4(€€€€€€€€€€€€€€€Í•ÍÍ¥½¸°4(€€€€€€€€€€€€€€€Ý½É±°4(€€€€€€€€€€€€€€€à°4(€€€€€€€€€€€€€€€Ñ…É•Ñd°4(€€€€€€€€€€€€€€€è°4(€€€€€€€€€€€€€€€Ñ•ÉÉ…¥¹Q½Á5…Ñ•É¥…°¡à°è°‘¥ÍÑ…¹”°™•…Ñ¡•È¤4(€€€€€€€€¤ì4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”¥¹ÐÉ•Í½±Ù•	…Í•d 4(€€€€€€€€€€€]½É±Ý½É±°4(€€€€€€€€€€€¥¹Ð•¹Ñ•É`°4(€€€€€€€€€€€¥¹Ð•¹Ñ•Éh°4(€€€€€€€€€€€¥¹Ð™…±±‰…­d¤ì4(€€€€€€€1¥ÍÐñ%¹Ñ••ÈøÍ…µÁ±•Ì€ô¹•ÜÉÉ…å1¥ÍÐðø ¤ì4(€€€€€€€¥¹Ñmt½™™Í•ÑÌ€ôì´Ð°€À°€Ñôì4(€€€€€€€™½È€¡¥¹Ð‘à€è½™™Í•ÑÌ¤ì4(€€€€€€€€€€€™½È€¡¥¹Ð‘è€è½™™Í•ÑÌ¤ì4(€€€€€€€€€€€€€€€Í…µÁ±•Ì¹…‘¡™¥¹‘9…ÑÕÉ…±É½Õ¹‘d 4(€€€€€€€€€€€€€€€€€€€€€€€Ý½É±°4(€€€€€€€€€€€€€€€€€€€€€€€•¹Ñ•É`€¬‘à°4(€€€€€€€€€€€€€€€€€€€€€€€•¹Ñ•Éh€¬‘è°4(€€€€€€€€€€€€€€€€€€€€€€€™…±±‰…­d4(€€€€€€€€€€€€€€€€¤¤ì4(€€€€€€€€€€€ô4(€€€€€€€ô4(€€€€€€€½±±•Ñ¥½¹Ì¹Í½ÉÐ¡Í…µÁ±•Ì¤ì4(€€€€€€€¥¹Ðµ•‘¥…¸€ôÍ…µÁ±•Ì¹•Ð¡Í…µÁ±•Ì¹Í¥é” ¤€¼€È¤ì4(4(€€€€€€€€¼¨4(€€€€€€€€€¨…¹ÌÕ¹”É½ÑÑ”½ÔÍÕÈÕ¹”ÑË¡Ì¡…ÕÑ”½¹ÍÑÉÕÑ¥½¸°±”¹¥Ù•…Ô±”4(€€€€€€€€€¨Á±ÕÌÁÉ½¡”‘Ô©½Õ•ÕÈ•ÍÐÁ±ÕÌÁË¥Ù¥Í¥‰±”ÅÕ”±„ÍÕÉ™…”‘¥ÍÑ…¹Ñ”¸4(€€€€€€€€€¨¼4(€€€€€€€¥˜€¡5…Ñ ¹…‰Ì¡µ•‘¥…¸€´™…±±‰…­d¤€ø€Äà¤ì4(€€€€€€€€€€€É•ÑÕÉ¸™…±±‰…­dì4(€€€€€€€ô4(€€€€€€€É•ÑÕÉ¸µ•‘¥…¸ì4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”¥¹Ð™¥¹‘9…ÑÕÉ…±É½Õ¹‘d 4(€€€€€€€€€€€]½É±Ý½É±°4(€€€€€€€€€€€¥¹Ðà°4(€€€€€€€€€€€¥¹Ðè°4(€€€€€€€€€€€¥¹Ð™…±±‰…­d¤ì4(€€€€€€€¥¹Ð¡¥¡•ÍÐ€ô5…Ñ ¹µ¥¸ 4(€€€€€€€€€€€€€€€Ý½É±¹•Ñ5…á!•¥¡Ð ¤€´€Ä°4(€€€€€€€€€€€€€€€Ý½É±¹•Ñ!¥¡•ÍÑ	±½­eÐ¡à°è¤4(€€€€€€€€¤ì4(€€€€€€€¥¹Ð±½Ý•É	½Õ¹€ô5…Ñ ¹µ…à 4(€€€€€€€€€€€€€€€Ý½É±¹•Ñ5¥¹!•¥¡Ð ¤°4(€€€€€€€€€€€€€€€¡¥¡•ÍÐ€´€äØ4(€€€€€€€€¤ì4(4(€€€€€€€™½È€¡¥¹Ðä€ô¡¥¡•ÍÐìä€øô±½Ý•É	½Õ¹ìä´´¤ì4(€€€€€€€€€€€5…Ñ•É¥…°µ…Ñ•É¥…°€ôÝ½É±¹•Ñ	±½­Ð¡à°ä°è¤¹•ÑQåÁ” ¤ì4(€€€€€€€€€€€¥˜€¡¥Í9…ÑÕÉ…±É½Õ¹¡µ…Ñ•É¥…°¤¤ì4(€€€€€€€€€€€€€€€É•ÑÕÉ¸äì4(€€€€€€€€€€€ô4(€€€€€€€ô4(€€€€€€€É•ÑÕÉ¸5…Ñ ¹µ…à 4(€€€€€€€€€€€€€€€Ý½É±¹•Ñ5¥¹!•¥¡Ð ¤€¬€È°4(€€€€€€€€€€€€€€€5…Ñ ¹µ¥¸¡Ý½É±¹•Ñ5…á!•¥¡Ð ¤€´€È°™…±±‰…­d¤4(€€€€€€€€¤ì4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”‰½½±•…¸¥Í9…ÑÕÉ…±É½Õ¹¡5…Ñ•É¥…°µ…Ñ•É¥…°¤ì4(€€€€€€€¥˜€¡µ…Ñ•É¥…°€ôô¹Õ±°ñðµ…Ñ•É¥…°¹¥Í¥È ¤ñð€…µ…Ñ•É¥…°¹¥ÍM½±¥ ¤¤ì4(€€€€€€€€€€€É•ÑÕÉ¸™…±Í”ì4(€€€€€€€ô4(4(€€€€€€€MÑÉ¥¹œ¹…µ”€ôµ…Ñ•É¥…°¹¹…µ” ¤ì4(€€€€€€€É•ÑÕÉ¸€…¹…µ”¹•¹‘Í]¥Ñ  ‰}1YLˆ¤4(€€€€€€€€€€€€€€€€˜˜€…¹…µ”¹•¹‘Í]¥Ñ  ‰}1=ˆ¤4(€€€€€€€€€€€€€€€€˜˜€…¹…µ”¹•¹‘Í]¥Ñ  ‰}]==ˆ¤4(€€€€€€€€€€€€€€€€˜˜€…¹…µ”¹•¹‘Í]¥Ñ  ‰}MQ4ˆ¤4(€€€€€€€€€€€€€€€€˜˜€…¹…µ”¹•¹‘Í]¥Ñ  ‰}!eA!ˆ¤4(€€€€€€€€€€€€€€€€˜˜µ…Ñ•É¥…°€„ô5…Ñ•É¥…°¹	5	==}	1=,4(€€€€€€€€€€€€€€€€˜˜µ…Ñ•É¥…°€„ô5…Ñ•É¥…°¹QUL4(€€€€€€€€€€€€€€€€˜˜µ…Ñ•É¥…°€„ô5…Ñ•É¥…°¹5UM!I==5}MQ4ì4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”¥¹Ð‘¥ÍÑ…¹•=ÕÑÍ¥‘” 4(€€€€€€€€€€€¥¹Ðà°4(€€€€€€€€€€€¥¹Ðè°4(€€€€€€€€€€€¥¹Ðµ¥¹`°4(€€€€€€€€€€€¥¹Ðµ…á`°4(€€€€€€€€€€€¥¹Ðµ¥¹h°4(€€€€€€€€€€€¥¹Ðµ…áh¤ì4(€€€€€€€¥¹Ð‘à€ôà€ðµ¥¹`4(€€€€€€€€€€€€€€€€üµ¥¹`€´à4(€€€€€€€€€€€€€€€€è5…Ñ ¹µ…à À°à€´µ…á`¤ì4(€€€€€€€¥¹Ð‘è€ôè€ðµ¥¹h4(€€€€€€€€€€€€€€€€üµ¥¹h€´è4(€€€€€€€€€€€€€€€€è5…Ñ ¹µ…à À°è€´µ…áh¤ì4(€€€€€€€É•ÑÕÉ¸5…Ñ ¹µ…à¡‘à°‘è¤ì4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”5…Ñ•É¥…°Ñ•ÉÉ…¥¹Q½Á5…Ñ•É¥…° 4(€€€€€€€€€€€¥¹Ðà°4(€€€€€€€€€€€¥¹Ðè°4(€€€€€€€€€€€¥¹Ð‘¥ÍÑ…¹”°4(€€€€€€€€€€€¥¹Ð™•…Ñ¡•È¤ì4(€€€€€€€¥¹ÐÍ•±•Ñ½È€ô5…Ñ ¹™±½½É5½¡à€¨€ÌÄ€¬è€¨€ÄÜ°€ÄÄ¤ì4(€€€€€€€¥˜€¡‘¥ÍÑ…¹”€ø€À€˜˜‘¥ÍÑ…¹”€øô5…Ñ ¹µ…à Ä°™•…Ñ¡•È€´€Ä¤¤ì4(€€€€€€€€€€€É•ÑÕÉ¸Í•±•Ñ½È€”€Ì€ôô€À4(€€€€€€€€€€€€€€€€€€€€ü5…Ñ•É¥…°¹5=MM}	1=,4(€€€€€€€€€€€€€€€€€€€€è5…Ñ•É¥…°¹IMM}	1=,ì4(€€€€€€€ô4(€€€€€€€¥˜€¡Í•±•Ñ½È€ôô€À¤ì4(€€€€€€€€€€€É•ÑÕÉ¸5…Ñ•É¥…°¹=IM}%IPì4(€€€€€€€ô4(€€€€€€€¥˜€¡Í•±•Ñ½È€ôô€Ä¤ì4(€€€€€€€€€€€É•ÑÕÉ¸5…Ñ•É¥…°¹5=MM}	1=,ì4(€€€€€€€ô4(€€€€€€€É•ÑÕÉ¸5…Ñ•É¥…°¹IMM}	1=,ì4(€€€ô4(4(€€€€¼¨¨4(€€€€€¨¹¹Õ±”±„Ÿ¥»¥É…Ñ¥½¸…Ñ¥Ù”½ÔÑ•Éµ¥»¥”•ÐÉ•ÍÑ…ÕÉ”±•Ì‰±½Ì‘…¹Ì4(€€€€€¨°½É‘É”¥¹Ù•ÉÍ”‘”±•ÕÈÁÉ•µ§¡É”µ½‘¥™¥…Ñ¥½¸¸4(€€€€€¨¼4(€€€ÁÉ¥Ù…Ñ”‰½½±•…¸Õ¹‘½Y¥±±…” ¤ì4(€€€€€€€Y¥±±…••¹•É…Ñ¥½¹M•ÍÍ¥½¸Í•ÍÍ¥½¸€ôÕÉÉ•¹ÑM•ÍÍ¥½¸ì4(€€€€€€€¥˜€¡Í•ÍÍ¥½¸€ôô¹Õ±°¤ì4(€€€€€€€€€€€É•ÑÕÉ¸™…±Í”ì4(€€€€€€€ô4(4(€€€€€€€…¹•±Ñ¥Ù•	Õ¥± ¤ì4(€€€€€€€É½±±‰…­M•ÍÍ¥½¸¡Í•ÍÍ¥½¸¤ì4(€€€€€€€É•ÑÕÉ¸ÑÉÕ”ì4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”Ù½¥…¹•±Ñ¥Ù•	Õ¥± ¤ì4(€€€€€€€¥˜€¡…Ñ¥Ù•	Õ¥±‘Q…Í¬€„ô¹Õ±°¤ì4(€€€€€€€€€€€…Ñ¥Ù•	Õ¥±‘Q…Í¬¹…¹•° ¤ì4(€€€€€€€€€€€…Ñ¥Ù•	Õ¥±‘Q…Í¬€ô¹Õ±°ì4(€€€€€€€ô4(€€€ô4(4(€€€ÁÉ¥Ù…Ñ”Ù½¥É½±±‰…­M•ÍÍ¥½¸¡Y¥±±…••¹•É…Ñ¥½¹M•ÍÍ¥½¸Í•ÍÍ¥½¸¤ì4(€€€€€€€¥˜€¡Í•ÍÍ¥½¸€ôô¹Õ±°¤ì4(€€€€€€€€€€€É•ÑÕÉ¸ì4(€€€€€€€ô4(4(€€€€€€€€¼¨4(€€€€€€€€€¨=¸“¥Ñ…¡”±„Í•ÍÍ¥½¸…Ù…¹Ð±„É•ÍÑ…ÕÉ…Ñ¥½¸€è…ÕÕ¹”½µµ…¹‘”½Ô4(€€€€€€€€€¨Ó‰¡”Ë¥Í¥‘Õ•±±”¹”Á•ÕÐ…±½ÉÌ•¹É•¥ÍÑÉ•È±•Ì‰±½ÌÉ•ÍÑ…ÕË¥Ì¸4(€€€€€€€€€¨¼4(€€€€€€€¥˜€¡ÕÉÉ•¹ÑM•ÍÍ¥½¸€ôôÍ•ÍÍ¥½¸¤ì4(€€€€€€€€€€€ÕÉÉ•¹ÑM•ÍÍ¥½¸€ô¹Õ±°ì4(€€€€€€€ô4(4(€€€€€€€™½È€¡UU%¥€èÍ•ÍÍ¥½¸¹•Ñ•¹•É…Ñ•‘¹Ñ¥Ñ¥•Ì ¤¤ì4(€€€€€€€€€€€¹Ñ¥Ñä•¹Ñ¥Ñä€ô	Õ­­¥Ð¹•Ñ¹Ñ¥Ñä¡¥¤ì4(€€€€€€€€€€€¥˜€¡•¹Ñ¥Ñä€„ô¹Õ±°¤ì4(€€€€€€€€€€€€€€€•¹Ñ¥Ñä¹É•µ½Ù” ¤ì4(€€€€€€€€€€€ô4(€€€€€€€ô4(4(€€€€€€€1¥ÍÐñ5…À¹¹ÑÉäñ1½…Ñ¥½¸°	±½­…Ñ„øø½É¥¥¹…±Ì€ô4(€€€€€€€€€€€€€€€¹•ÜÉÉ…å1¥ÍÐðø¡Í•ÍÍ¥½¸¹•Ñ=É¥¥¹…±	±½­Ì ¤¹•¹ÑÉåM•Ð ¤¤ì4(€€€€€€€½±±•Ñ¥½¹Ì¹É•Ù•ÉÍ”¡½É¥¥¹…±Ì¤ì4(€€€€€€€™½È€¡5…À¹¹ÑÉäñ1½…Ñ¥½¸°	±½­…Ñ„ø•¹ÑÉä€è½É¥¥¹…±Ì¤ì4(€€€€€€€€€€€1½…Ñ¥½¸±½…Ñ¥½¸€ô•¹ÑÉä¹•Ñ-•ä ¤ì4(€€€€€€€€€€€¥˜€¡±½…Ñ¥½¸¹•Ñ]½É± ¤€ôô¹Õ±°¤ì4(€€€€€€€€€€€€€€€½¹Ñ¥¹Õ”ì4(€€€€€€€€€€€ô4(€€€€€€€€€€€±½…Ñ¥½¸¹•Ñ	±½¬ ¤¹Í•Ñ	±½­…Ñ„ 4(€€€€€€€€€€€€€€€€€€€•¹ÑÉä¹•ÑY…±Õ” ¤¹±½¹” ¤°4(€€€€€€€€€€€€€€€€€€€™…±Í”4(€€€€€€€€€€€€¤ì4(€€€€€€€ô4(4(€€€€€€€ÑÉäì4(€€€€€€€€€€€Y¥±±…•¹Ñ¥Ñå5…¹…•È¹±•…¹ÕÀ 4(€€€€€€€€€€€€€€€€€€€Á±Õ¥¸°4(€€€€€€€€€€€€€€€€€€€Í•ÍÍ¥½¸¹•ÑY¥±±…•% ¤4(€€€€€€€€€€€€¤ì4(€€€€€€€ô…Ñ €¡Q¡É½Ý…‰±”Ñ¡É½Ý…‰±”¤ì4(€€€€€€€€€€€Á±Õ¥¸¹•Ñ1½•È ¤¹Ý…É¹¥¹œ 4(€€€€€€€€€€€€€€€€€€€€‰9•ÑÑ½å…”‘•Ì•¹Ñ¥Ó¥Ì‘ÔÙ¥±±…”¥¹½µÁ±•Ð€è€ˆ4(€€€€€€€€€€€€€€€€€€€€€€€€€€€€¬Ñ¡É½Ý…‰±”¹•Ñ±…ÍÌ ¤¹•ÑM¥µÁ±•9…µ” ¤4(€€€€€€€€€€€€¤ì4(€€€€€€€ô4(€€€ô4)ô4(