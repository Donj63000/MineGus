package org.example;

import org.example.forestry2.SharedChunkTickets;

import org.bukkit.Bukkit;
import org.bukkit.Axis;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.example.mineur.AutomatedMiningContext;
import org.example.mineur.BranchIterator;
import org.example.mineur.InventoryRouter;
import org.example.mineur.MiningBlockPolicy;
import org.example.mineur.MiningCursor;
import org.example.mineur.MiningIterator;
import org.example.mineur.MiningLoop;
import org.example.mineur.MiningPattern;
import org.example.mineur.MiningSessionState;
import org.example.mineur.MiningSpeed;
import org.example.mineur.QuarryIterator;
import org.example.mineur.TunnelIterator;
import org.example.mineur.TunnelOperationBatch;
import org.example.mineur.VeinFirstIterator;
import org.example.mineur.builders.MineCabinBuilder;
import org.example.mineur.builders.MineShaftColumnBuilder;
import org.example.mineur.builders.StairBuilder;
import org.example.mineur.builders.SupportBuilder;
import org.example.mineur.builders.TorchPlacer;
import org.example.mineur.builders.TunnelInfrastructureBuilder;
import org.example.mineur.builders.TunnelInfrastructureBuilder.BlockPos;
import org.example.mineur.builders.TunnelInfrastructureBuilder.FluidPlan;
import org.example.mineur.builders.TunnelInfrastructureBuilder.FluidScanner;
import org.example.mineur.builders.TunnelInfrastructureBuilder.Placement;
import org.example.mineur.builders.TunnelInfrastructureBuilder.PlacementKind;
import org.example.mineur.builders.TunnelInfrastructureBuilder.Volume;
import org.example.mineur.store.SessionStore;
import org.example.mineur.ui.Hologram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

public class Mineur implements CommandExecutor, Listener {

    private static final String SELECTOR_NAME = ChatColor.GOLD + "Sélecteur de mine";
    private static final String CMD_PREFIX = ChatColor.GRAY + "[Mineur] " + ChatColor.RESET;
    private static final long DEFAULT_ENTITY_WATCHDOG_INTERVAL_TICKS = 20L;
    private static final long DEFAULT_ENTITY_RECOVERY_DELAY_TICKS = 2L;
    private static final int DEFAULT_TUNNEL_OPERATIONS_PER_TICK = 64;
    private static final int DEFAULT_TUNNEL_FLUID_INSPECTIONS_PER_TICK = 512;
    private static final int MAX_RUNTIME_TUNNEL_PLAN_OPERATIONS = 20_000;
    private static final int TUNNEL_RESCAN_INTERVAL_TICKS = 100;

    private final JavaPlugin plugin;
    private final SessionStore sessionStore;
    private final NamespacedKey containerOwnerKey;
    private final NamespacedKey containerSessionKey;
    private final NamespacedKey selectorKey;
    private final NamespacedKey entitySessionKey;
    private final MineCabinBuilder cabinBuilder;

    private final List<MiningSessionState> sessions = new ArrayList<>();
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<UUID, RuntimeSession> runtimes = new HashMap<>();
    private final Map<UUID, List<UUID>> ownerSessions = new HashMap<>();
    private final Map<UUID, UUID> selectedSessions = new HashMap<>();
    private final Set<UUID> pendingMinerRecoveries = new HashSet<>();

    private BukkitTask entityWatchdogTask;
    private boolean shuttingDown;

    public Mineur(JavaPlugin plugin) {
        this.plugin = plugin;
        this.sessionStore = new SessionStore(plugin.getDataFolder(), plugin.getLogger());
        this.containerOwnerKey = new NamespacedKey(plugin, "mineur-owner");
        this.containerSessionKey = new NamespacedKey(plugin, "mineur-session");
        this.selectorKey = new NamespacedKey(plugin, "mineur-selector");
        this.entitySessionKey = new NamespacedKey(plugin, "mineur-entity-session");
        this.cabinBuilder = new MineCabinBuilder(plugin);

        plugin.saveDefaultConfig();

        if (plugin.getCommand("mineur") != null) {
            plugin.getCommand("mineur").setExecutor(this);
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Cette commande doit être exécutée par un joueur !");
            return true;
        }
        if (!sender.hasPermission("mineplugin.mineur.use")) {
            sender.sendMessage(ChatColor.RED + "Tu n'as pas la permission pour /mineur.");
            return true;
        }

        // /mineur => donne simplement le bâton
        if (args.length == 0) {
            giveMineSelector(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "aide", "help", "!aide" -> {
                sendUsage(player);
                return true;
            }
            case "liste", "list" -> handleList(player);
            case "selectionner", "select" -> handleSelect(player, args);
            case "vitesse", "speed" -> handleSpeed(player, args);
            case "pattern", "mode", "patron" -> handlePattern(player, args);
            case "pause" -> handlePause(player, true);
            case "reprendre", "resume", "play" -> handlePause(player, false);
            case "stop", "arreter", "off" -> handleStop(player);
            case "info", "status" -> handleInfo(player);
            case "autoriser", "trust" -> handleTrust(player, args);
            case "retirer", "revoquer", "révoquer", "untrust" -> handleUntrust(player, args);
            default -> sendUsage(player);
        }
        return true;
    }

    private void sendUsage(Player player) {
        int stopAtY = plugin.getConfig().getInt("mineur.stop-at-y", -58);
        boolean placementAuto = plugin.getConfig().getBoolean("mineur.allow-block-placement", false);
        for (String line : buildUsageLines(stopAtY, placementAuto)) {
            player.sendMessage(line);
        }
    }

    static List<String> buildUsageLines(int stopAtY, boolean placementAuto) {
        List<String> lines = new ArrayList<>();
        lines.add(CMD_PREFIX + ChatColor.YELLOW + "Aide complete du mode mineur");
        lines.add(ChatColor.GRAY + "Parametres actuels: stop-at-y=" + ChatColor.AQUA + stopAtY
                + ChatColor.GRAY + ", decors secondaires=" + ChatColor.AQUA
                + (placementAuto ? "actives" : "desactives"));
        lines.add(ChatColor.DARK_GRAY + "--------------------------------------------------");

        lines.add(ChatColor.GOLD + "/mineur" + ChatColor.GRAY
                + " : donne le baton de selection. Clique 2 blocs au meme Y pour creer ta mine.");
        lines.add(ChatColor.GRAY
                + "  Au lancement: mode par defaut, coffres auto, mineur PNJ + golems gardes.");

        lines.add(ChatColor.GOLD + "/mineur aide" + ChatColor.GRAY + " | " + ChatColor.GOLD + "/mineur help"
                + ChatColor.GRAY + " | " + ChatColor.GOLD + "/mineur !aide"
                + ChatColor.GRAY + " : affiche cette aide complete.");

        lines.add(ChatColor.GOLD + "/mineur liste" + ChatColor.GRAY + " | " + ChatColor.GOLD + "/mineur list"
                + ChatColor.GRAY + " : liste tes mineurs et indique lequel est selectionne.");
        lines.add(ChatColor.GOLD + "/mineur selectionner <n>" + ChatColor.GRAY + " | "
                + ChatColor.GOLD + "/mineur select <n>" + ChatColor.GRAY
                + " : choisit le mineur cible pour les commandes.");

        lines.add(ChatColor.GOLD + "/mineur vitesse <lent|normal|rapide>" + ChatColor.GRAY + " | "
                + ChatColor.GOLD + "/mineur speed <slow|normal|fast>" + ChatColor.GRAY
                + " : change la cadence de minage du mineur selectionne.");

        lines.add(ChatColor.GOLD + "/mineur pattern <carriere|branche|tunnel|veine>" + ChatColor.GRAY + " | "
                + ChatColor.GOLD + "/mineur mode <...>" + ChatColor.GRAY + " | "
                + ChatColor.GOLD + "/mineur patron <...>" + ChatColor.GRAY
                + " : change le mode de minage.");
        lines.add(ChatColor.GRAY + "  carriere/quarry = balayage complet couche par couche.");
        lines.add(ChatColor.GRAY + "  branche/branch = galerie principale + branches regulieres.");
        lines.add(ChatColor.GRAY + "  tunnel = avance en tunnel directionnel.");
        lines.add(ChatColor.GRAY + "  veine/vein_first = priorise les minerais proches et vide chaque veine detectee.");
        lines.add(ChatColor.GRAY + "  Note: changer de pattern desactive le chainage auto carriere -> tunnel.");

        lines.add(ChatColor.GOLD + "/mineur pause" + ChatColor.GRAY
                + " : met la session en pause sans la supprimer.");
        lines.add(ChatColor.GOLD + "/mineur reprendre" + ChatColor.GRAY + " | " + ChatColor.GOLD + "/mineur resume"
                + ChatColor.GRAY + " | " + ChatColor.GOLD + "/mineur play"
                + ChatColor.GRAY + " : reprend une session en pause.");

        lines.add(ChatColor.GOLD + "/mineur stop" + ChatColor.GRAY + " | " + ChatColor.GOLD + "/mineur arreter"
                + ChatColor.GRAY + " | " + ChatColor.GOLD + "/mineur off"
                + ChatColor.GRAY
                + " : arrête le mineur ; le stockage et les constructions restent dans le monde.");

        lines.add(ChatColor.GOLD + "/mineur info" + ChatColor.GRAY + " | " + ChatColor.GOLD + "/mineur status"
                + ChatColor.GRAY + " : affiche zone, monde, pattern, vitesse, bonus et etat du mineur selectionne.");

        lines.add(ChatColor.GOLD + "/mineur autoriser <joueur>" + ChatColor.GRAY + " | "
                + ChatColor.GOLD + "/mineur trust <joueur>" + ChatColor.GRAY
                + " : autorise un joueur a interagir avec ta session.");
        lines.add(ChatColor.GOLD + "/mineur retirer <joueur>" + ChatColor.GRAY + " | "
                + ChatColor.GOLD + "/mineur untrust <joueur>" + ChatColor.GRAY
                + " : retire une autorisation existante.");

        lines.add(ChatColor.DARK_GRAY + "--------------------------------------------------");
        lines.add(ChatColor.YELLOW + "Exemples rapides:");
        lines.add(ChatColor.GRAY + "  /mineur");
        lines.add(ChatColor.GRAY + "  /mineur list");
        lines.add(ChatColor.GRAY + "  /mineur select 2");
        lines.add(ChatColor.GRAY + "  /mineur vitesse rapide");
        lines.add(ChatColor.GRAY + "  /mineur pattern branche");
        lines.add(ChatColor.GRAY + "  /mineur pause");
        lines.add(ChatColor.GRAY + "  /mineur info");
        lines.add(ChatColor.GRAY + "  /mineur autoriser PseudoJoueur");
        lines.add(ChatColor.GRAY + "  /mineur retirer PseudoJoueur");
        return lines;
    }

    private void createMineFromSelection(Player player) {
        UUID ownerId = player.getUniqueId();
        JobManager jobManager = getJobManager();
        if (jobManager != null && !jobManager.hasMinerJob(ownerId)) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED
                    + "Tu dois avoir le métier " + ChatColor.GOLD + "Mineur"
                    + ChatColor.RED + " pour poser un PNJ mineur.");
            player.sendMessage(CMD_PREFIX + ChatColor.YELLOW + "Utilise " + ChatColor.GOLD + "/job mineur"
                    + ChatColor.YELLOW + " pour débloquer cette fonctionnalité.");
            return;
        }

        Selection selection = selections.get(ownerId);
        if (selection == null || !selection.isComplete()) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED
                    + "Sélection invalide. Utilise /mineur puis clique deux blocs à la même hauteur.");
            return;
        }

        Block first = selection.getCorner1();
        Block second = selection.getCorner2();
        if (!first.getWorld().equals(second.getWorld())) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Les deux coins doivent être dans le même monde.");
            return;
        }
        if (first.getY() != second.getY()) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Les deux blocs doivent être à la même hauteur (Y).");
            return;
        }

        World world = first.getWorld();
        if (!isWorldAllowed(world)) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Ce monde n'est pas autorisé pour le mineur.");
            return;
        }

        int minX = Math.min(first.getX(), second.getX());
        int maxX = Math.max(first.getX(), second.getX());
        int minZ = Math.min(first.getZ(), second.getZ());
        int maxZ = Math.max(first.getZ(), second.getZ());
        long widthLong = (long) maxX - minX + 1L;
        long lengthLong = (long) maxZ - minZ + 1L;
        long area = widthLong * lengthLong;

        if (widthLong > getMaximumMineWidth()
                || lengthLong > getMaximumMineLength()
                || area > getMaximumMineArea()) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED
                    + "Zone trop grande : " + widthLong + "x" + lengthLong + " (" + area + " blocs/couche).");
            player.sendMessage(CMD_PREFIX + ChatColor.YELLOW
                    + "Limites : " + getMaximumMineWidth() + "x" + getMaximumMineLength()
                    + " et " + getMaximumMineArea() + " blocs par couche.");
            return;
        }

        int baseY = first.getY();
        int stopY = getEffectiveStopY(world);
        if (baseY < stopY) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED
                    + "La sélection est sous la profondeur d'arrêt effective (Y " + stopY + ").");
            return;
        }

        if (!world.getWorldBorder().isInside(new Location(world, minX, baseY, minZ))
                || !world.getWorldBorder().isInside(new Location(world, maxX, baseY, maxZ))) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "La zone dépasse la bordure du monde.");
            return;
        }

        int activeMines = getSessionsForOwner(ownerId).size();
        int maxMines = getMaxMinesForOwner(ownerId);
        if (activeMines >= maxMines) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Limite atteinte : "
                    + activeMines + "/" + maxMines + " mineur(s) actifs.");
            Integer nextUnlock = getNextMineUnlockLevel(ownerId);
            if (nextUnlock != null) {
                player.sendMessage(CMD_PREFIX + ChatColor.YELLOW
                        + "Prochain mineur débloqué au niveau " + nextUnlock + ".");
            } else {
                player.sendMessage(CMD_PREFIX + ChatColor.YELLOW
                        + "Tu as déjà atteint le maximum de mineurs.");
            }
            return;
        }

        int width = (int) widthLong;
        int length = (int) lengthLong;
        Location base = new Location(world, minX, baseY, minZ);
        MiningSessionState state = new MiningSessionState();
        state.worldUid = world.getUID();
        state.base = base;
        state.width = width;
        state.length = length;
        state.cursor = new MiningCursor(base, width, length);
        state.minerY = baseY + 1.0D;
        state.owner = ownerId;
        state.useBarrelMaster = plugin.getConfig()
                .getBoolean("mineur.default.use-barrel-master", false);
        state.pattern = getDefaultPattern();
        state.speed = getDefaultSpeed();
        state.chainTunnelAfterQuarry = state.pattern == MiningPattern.QUARRY;
        state.infiniteTunnel = false;
        state.tunnelDirection = directionFromYaw(player.getLocation().getYaw());
        state.tunnelSectionSize = getConfiguredTunnelSectionSize();
        state.tunnelHeight = getConfiguredTunnelHeight();
        state.tunnelSectionsMined = 0;
        state.maxTunnelSections = Math.max(0,
                plugin.getConfig().getInt("mineur.tunnel.max-sections", 0));

        MineCabinBuilder.Plan preparedCabinPlan = null;
        if (!state.useBarrelMaster && isAutomaticMineStructureEnabled()) {
            try {
                preparedCabinPlan = createCabinPlan(state);
            } catch (IllegalArgumentException exception) {
                player.sendMessage(CMD_PREFIX + ChatColor.RED
                        + "Impossible de dimensionner le chevalement : " + exception.getMessage());
                return;
            }

            MineCabinBuilder.ValidationResult envelope = cabinBuilder.validateEnvelope(
                    world,
                    preparedCabinPlan,
                    getMaximumLoadedChunks()
            );
            if (!envelope.valid()) {
                player.sendMessage(CMD_PREFIX + ChatColor.RED + envelope.message());
                return;
            }
            applyStructureMetadata(state, preparedCabinPlan);
        } else if (!state.useBarrelMaster && !canCreateAutomaticStorage(state)) {
            /*
             * Repli de compatibilité lorsque l'administrateur désactive
             * explicitement la cabane automatique.
             */
            player.sendMessage(CMD_PREFIX + ChatColor.RED
                    + "Impossible de placer les coffres sans remplacer des blocs existants.");
            player.sendMessage(CMD_PREFIX + ChatColor.YELLOW
                    + "Libère les emplacements autour de la zone, un bloc au-dessus du niveau sélectionné.");
            return;
        }

        int footprintMinX = preparedCabinPlan != null
                ? preparedCabinPlan.bounds().minX()
                : minX;
        int footprintMaxX = preparedCabinPlan != null
                ? preparedCabinPlan.bounds().maxX()
                : maxX;
        int footprintMinZ = preparedCabinPlan != null
                ? preparedCabinPlan.bounds().minZ()
                : minZ;
        int footprintMaxZ = preparedCabinPlan != null
                ? preparedCabinPlan.bounds().maxZ()
                : maxZ;
        int overlapMargin = preparedCabinPlan != null ? 1 : 3;
        if (findOverlappingSession(
                world,
                footprintMinX,
                footprintMaxX,
                footprintMinZ,
                footprintMaxZ,
                overlapMargin
        ) != null) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED
                    + "Cette zone chevauche une mine, une cabane ou un stockage existant.");
            return;
        }

        /*
         * La session n'est validée qu'après une initialisation complète. En cas
         * d'échec, aucune entrée fantôme ne reste dans sessions.yml.
         */
        sessions.add(state);
        registerOwnerSession(state, true);
        try {
            startRuntime(state, true, preparedCabinPlan);
        } catch (RuntimeException exception) {
            RuntimeSession failed = runtimes.remove(state.id);
            if (failed != null) {
                failed.stop(false);
            }
            rollbackFreshAutomaticStorage(state);
            cleanupContainerMetadata(state);
            sessions.remove(state);
            unregisterOwnerSession(state);
            selectedSessions.entrySet().removeIf(entry -> Objects.equals(entry.getValue(), state.id));
            plugin.getLogger().log(Level.SEVERE,
                    "[Mineur] Échec de création de la session " + state.id + ".", exception);
            player.sendMessage(CMD_PREFIX + ChatColor.RED
                    + "La création du mineur a échoué sans modifier la zone : " + exception.getMessage());
            return;
        }

        selections.remove(ownerId);
        saveAllSessions();

        player.sendMessage(CMD_PREFIX + ChatColor.GREEN
                + "Mineur lancé pour une zone de " + width + "x" + length + ".");
        if (preparedCabinPlan != null) {
            player.sendMessage(CMD_PREFIX + ChatColor.GOLD
                    + "Chevalement construit : plateforme à Y " + preparedCabinPlan.deckY()
                    + ", cabane de stockage, accès central au puits et "
                    + preparedCabinPlan.chestPairs().size() + " coffres doubles.");
        }
        player.sendMessage(CMD_PREFIX + ChatColor.GRAY + "Mineurs actifs : " + ChatColor.GREEN
                + getSessionsForOwner(ownerId).size() + ChatColor.GRAY + "/"
                + ChatColor.GREEN + maxMines + ChatColor.GRAY + ".");
    }

    private void handleList(Player player) {
        List<MiningSessionState> accessibleSessions = getAccessibleSessions(player);
        if (accessibleSessions.isEmpty()) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Aucune session active ou partagée.");
            return;
        }

        UUID selectedId = selectedSessions.get(player.getUniqueId());
        int ownedCount = getSessionsForOwner(player.getUniqueId()).size();
        player.sendMessage(CMD_PREFIX + ChatColor.AQUA + "Mineurs accessibles: " + accessibleSessions.size()
                + ChatColor.GRAY + " | A toi: " + ChatColor.GREEN + ownedCount
                + ChatColor.GRAY + "/" + ChatColor.GREEN + getMaxMinesForOwner(player.getUniqueId()));
        for (int index = 0; index < accessibleSessions.size(); index++) {
            MiningSessionState state = accessibleSessions.get(index);
            World world = Bukkit.getWorld(state.worldUid);
            String worldName = world != null ? world.getName() : "?";
            String status = formatSessionStatus(state);
            String selected = Objects.equals(selectedId, state.id) ? ChatColor.GOLD + " [selectionne]" : "";
            String access = Objects.equals(state.owner, player.getUniqueId())
                    ? ChatColor.GREEN + "proprietaire"
                    : ChatColor.AQUA + "autorise";
            player.sendMessage(ChatColor.GRAY + " - Mineur " + (index + 1)
                    + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + worldName
                    + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY
                    + state.base.getBlockX() + ", " + state.base.getBlockY() + ", " + state.base.getBlockZ()
                    + ChatColor.DARK_GRAY + " | " + status
                    + ChatColor.DARK_GRAY + " | " + access + selected);
        }
    }

    private void handleSelect(Player player, String[] args) {
        List<MiningSessionState> accessibleSessions = getAccessibleSessions(player);
        if (accessibleSessions.isEmpty()) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Aucune session active ou partagée.");
            return;
        }
        if (args.length < 2) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Precise le numero du mineur a selectionner.");
            return;
        }

        int index;
        try {
            index = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Numero invalide: " + args[1]);
            return;
        }

        if (index < 1 || index > accessibleSessions.size()) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Numero invalide. Utilise /mineur list pour voir les mineurs accessibles.");
            return;
        }

        MiningSessionState selected = accessibleSessions.get(index - 1);
        setSelectedSession(player.getUniqueId(), selected.id);
        saveAllSessions();
        player.sendMessage(CMD_PREFIX + ChatColor.GREEN + "Mineur " + index + " selectionne.");
    }

    private void handleSpeed(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED
                    + "Spécifie une vitesse : slow, normal ou fast.");
            return;
        }

        MiningSessionState state = requireSelectedSession(player);
        if (state == null || !requireManagementAccess(player, state)) {
            return;
        }

        MiningSpeed speed = parseSpeed(args[1]);
        if (speed == null) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Vitesse inconnue : " + args[1]);
            return;
        }

        state.speed = speed;
        RuntimeSession runtime = runtimeOf(state.id);
        if (runtime != null && !state.paused) {
            try {
                restartLoop(runtime);
            } catch (RuntimeException exception) {
                state.pause(MiningSessionState.PauseReason.ERROR);
                runtime.suspend();
                plugin.getLogger().log(Level.WARNING,
                        "[Mineur] Changement de vitesse impossible pour la session "
                                + state.id + ".",
                        exception);
                player.sendMessage(CMD_PREFIX + ChatColor.RED
                        + "Vitesse enregistrée, mais la session a été mise en pause : "
                        + exception.getMessage());
            }
        }
        saveAllSessions();
        player.sendMessage(CMD_PREFIX + ChatColor.GREEN
                + "Vitesse du mineur réglée sur "
                + speed.name().toLowerCase(Locale.ROOT) + ".");
    }

    private void handlePattern(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED
                    + "Spécifie un pattern : quarry, branch, tunnel ou vein_first.");
            return;
        }

        MiningSessionState state = requireSelectedSession(player);
        if (state == null || !requireManagementAccess(player, state)) {
            return;
        }

        MiningPattern pattern = parsePattern(args[1]);
        if (pattern == null) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Pattern inconnu : " + args[1]);
            return;
        }

        World world = state.base != null ? state.base.getWorld() : null;
        if (world == null) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED
                    + "Le monde de cette session n'est pas chargé.");
            return;
        }

        MiningCursor effectiveCursor = state.pendingCursor != null
                ? state.pendingCursor
                : state.cursor;
        int currentY = effectiveCursor != null
                ? effectiveCursor.y
                : state.base.getBlockY();
        currentY = clampY(world, currentY);

        BlockFace proposedDirection = state.tunnelDirection;
        int proposedSectionSize = state.tunnelSectionSize;
        int proposedHeight = state.tunnelHeight;
        Location proposedTunnelBase = null;

        /*
         * Valider entièrement le nouveau tunnel avant d'annuler la boucle
         * courante. Une commande invalide ne peut ainsi plus transformer la
         * session en carrière ni perdre sa progression.
         */
        if (pattern == MiningPattern.TUNNEL) {
            proposedDirection = directionFromYaw(player.getLocation().getYaw());
            proposedSectionSize = getConfiguredTunnelSectionSize();
            proposedHeight = getConfiguredTunnelHeight();
            proposedTunnelBase = computeAdjacentTunnelBase(
                    state,
                    currentY,
                    proposedSectionSize,
                    proposedDirection
            );
            if (!isTunnelSectionAllowed(
                    state,
                    proposedTunnelBase,
                    proposedSectionSize,
                    proposedHeight
            )) {
                player.sendMessage(CMD_PREFIX + ChatColor.RED
                        + "Le premier tronçon du tunnel dépasserait une limite "
                        + "de sécurité, de chunks, une autre mine ou la bordure.");
                return;
            }
        }

        RuntimeSession runtime = runtimeOf(state.id);
        if (runtime != null && runtime.loop != null) {
            runtime.loop.cancelAndRollback();
            runtime.loop = null;
        } else {
            state.rollbackPendingCursor();
        }

        boolean leavingTunnel = state.pattern == MiningPattern.TUNNEL
                && pattern != MiningPattern.TUNNEL;
        state.pattern = pattern;
        state.chainTunnelAfterQuarry = false;
        state.infiniteTunnel = false;
        state.waitingStorage = false;

        if (leavingTunnel) {
            /*
             * Je cesse explicitement de revendiquer les scellements lorsque le
             * joueur abandonne le mode tunnel. Un WAL partiel ne peut ainsi
             * rester protégé indéfiniment après le changement de pattern.
             */
            clearTunnelInfrastructureCheckpoint(state);
            if (runtime != null) {
                runtime.tunnelInfrastructure = null;
                runtime.tunnelDecoration = null;
                runtime.tunnelMaterialPalette = null;
                runtime.decoratedTunnelSection = "";
            }
        }

        if (pattern == MiningPattern.TUNNEL) {
            state.tunnelDirection = proposedDirection;
            state.tunnelSectionSize = proposedSectionSize;
            state.tunnelHeight = proposedHeight;
            state.tunnelSectionsMined = 0;
            state.tunnelArrivalRoom = false;
            clearTunnelInfrastructureCheckpoint(state);
            if (runtime != null) {
                runtime.tunnelInfrastructure = null;
                runtime.tunnelDecoration = null;
                runtime.tunnelMaterialPalette = null;
                runtime.decoratedTunnelSection = "";
            }
            state.maxTunnelSections = Math.max(0,
                    plugin.getConfig().getInt("mineur.tunnel.max-sections", 0));
            prepareTunnelCursor(
                    state,
                    proposedTunnelBase,
                    proposedSectionSize,
                    proposedSectionSize,
                    proposedHeight
            );
            recordTunnelOriginBounds(state);
            player.sendMessage(CMD_PREFIX + ChatColor.GRAY + "Direction du tunnel : "
                    + ChatColor.AQUA + formatDirection(state.tunnelDirection)
                    + ChatColor.GRAY + ".");
        } else {
            resetVerticalCursorAtDepth(state, currentY);
        }

        if (runtime != null && !state.paused) {
            try {
                activateRuntime(runtime);
            } catch (RuntimeException exception) {
                state.pause(MiningSessionState.PauseReason.ERROR);
                runtime.suspend();
                plugin.getLogger().log(Level.WARNING,
                        "[Mineur] Nouveau pattern impossible pour la session "
                                + state.id + ".",
                        exception);
                player.sendMessage(CMD_PREFIX + ChatColor.RED
                        + "Le pattern est enregistré mais la session a été mise en pause : "
                        + exception.getMessage());
            }
        }

        saveAllSessions();
        player.sendMessage(CMD_PREFIX + ChatColor.GREEN
                + "Pattern défini sur "
                + pattern.name().toLowerCase(Locale.ROOT) + ".");
    }

    private void handlePause(Player player, boolean pause) {
        MiningSessionState state = requireSelectedSession(player);
        if (state == null || !requireManagementAccess(player, state)) {
            return;
        }

        if (state.paused == pause) {
            player.sendMessage(CMD_PREFIX + ChatColor.YELLOW
                    + (pause ? "Ce mineur est déjà en pause." : "Ce mineur est déjà actif."));
            return;
        }

        RuntimeSession runtime = runtimeOf(state.id);
        if (pause) {
            state.pause(MiningSessionState.PauseReason.MANUAL);
        } else {
            state.resume();
        }
        if (pause) {
            if (runtime != null) {
                runtime.suspend();
            }
        } else {
            try {
                /*
                 * Une session conservée après une erreur de chargement peut ne
                 * posséder aucun runtime. La reconstruire ici évite d'afficher
                 * « relancé » alors qu'aucune tâche de minage n'existe.
                 */
                if (runtime == null) {
                    startRuntime(state, false);
                    runtime = runtimeOf(state.id);
                    if (runtime == null || runtime.loop == null) {
                        throw new IllegalStateException(
                                "La boucle du mineur n'a pas pu être créée."
                        );
                    }
                } else {
                    activateRuntime(runtime);
                }
            } catch (RuntimeException exception) {
                state.pause(MiningSessionState.PauseReason.ERROR);
                RuntimeSession failedRuntime = runtimeOf(state.id);
                if (failedRuntime != null) {
                    failedRuntime.suspend();
                }
                plugin.getLogger().log(Level.WARNING,
                        "[Mineur] Reprise impossible pour la session "
                                + state.id + ".",
                        exception);
                player.sendMessage(CMD_PREFIX + ChatColor.RED
                        + "Reprise impossible : " + exception.getMessage());
            }
        }

        saveAllSessions();
        player.sendMessage(CMD_PREFIX + (state.paused ? ChatColor.YELLOW : ChatColor.GREEN)
                + (state.paused ? "Mineur mis en pause." : "Mineur relancé."));
    }

    private void handleStop(Player player) {
        MiningSessionState state = requireSelectedSession(player);
        if (state == null) {
            return;
        }
        if (!canAdministrateSession(player, state)) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Seul le propriétaire peut arrêter définitivement ce mineur.");
            return;
        }
        stopSession(state.id, true, player);
    }

    private void handleInfo(Player player) {
        MiningSessionState state = requireSelectedSession(player);
        if (state == null) {
            return;
        }

        World world = Bukkit.getWorld(state.worldUid);
        String worldName = world != null ? world.getName() : "?";
        JobManager jobManager = getJobManager();
        UUID statsOwner = state.owner != null ? state.owner : player.getUniqueId();
        int level = jobManager != null ? jobManager.getLevelForPlayer(statsOwner) : 1;
        double speedBonus = jobManager != null ? jobManager.getMiningSpeedBonusPercent(statsOwner) : 0.0D;
        double multiplier = state.speed.progressPerTick(getOwnerSpeedMultiplier(state.owner)) * state.speed.ticksPerStage;
        OfflinePlayer owner = state.owner != null ? Bukkit.getOfflinePlayer(state.owner) : null;
        String ownerName = owner != null && owner.getName() != null ? owner.getName() : "?";

        player.sendMessage(CMD_PREFIX + ChatColor.AQUA + "Session " + state.id + " :");
        player.sendMessage(ChatColor.GRAY + " - Propriétaire : " + ChatColor.GREEN + ownerName);
        player.sendMessage(ChatColor.GRAY + " - Base : " + state.base.getBlockX() + ", " + state.base.getBlockY() + ", " + state.base.getBlockZ());
        player.sendMessage(ChatColor.GRAY + " - Niveau mineur : " + ChatColor.GREEN + level
                + ChatColor.GRAY + " | Bonus vitesse : " + ChatColor.GREEN + "+" + formatDecimal(speedBonus) + "%");
        player.sendMessage(ChatColor.GRAY + " - Multiplicateur effectif : " + ChatColor.GREEN + "x" + formatDecimal(multiplier));
        player.sendMessage(ChatColor.GRAY + " - Mineurs du propriétaire : " + ChatColor.GREEN + getSessionsForOwner(statsOwner).size()
                + ChatColor.GRAY + "/" + ChatColor.GREEN + getMaxMinesForOwner(statsOwner));
        player.sendMessage(ChatColor.GRAY + " • Monde : " + worldName);
        player.sendMessage(ChatColor.GRAY + " • Zone : " + state.width + "x" + state.length + " (Y " + state.base.getBlockY() + ")");
        int cursorY = state.cursor != null ? state.cursor.y : state.base.getBlockY();
        player.sendMessage(ChatColor.GRAY + " • Curseur Y : " + cursorY
                + " / stop " + getEffectiveStopY(world) + ".");
        player.sendMessage(ChatColor.GRAY + " • Vitesse : " + state.speed.name().toLowerCase(Locale.ROOT));
        player.sendMessage(ChatColor.GRAY + " • Pattern : " + state.pattern.name().toLowerCase(Locale.ROOT));
        if (state.pattern == MiningPattern.TUNNEL || state.infiniteTunnel) {
            player.sendMessage(ChatColor.GRAY + " • Tunnel : direction " + ChatColor.AQUA + formatDirection(state.tunnelDirection)
                    + ChatColor.GRAY + ", hauteur " + ChatColor.AQUA + Math.max(1, state.tunnelHeight));
        }
        if (state.structureVersion >= MineCabinBuilder.FIRST_STRUCTURE_VERSION) {
            player.sendMessage(ChatColor.GRAY + " • Stockage de la cabane : "
                    + ChatColor.GOLD + (state.containers.size() / 2)
                    + ChatColor.GRAY + " coffres doubles ("
                    + state.containers.size() + " blocs de coffre).");
            player.sendMessage(ChatColor.GRAY + " • Accès vertical : "
                    + (hasIntegratedShaft(state)
                    ? ChatColor.GREEN + "puits central éclairé"
                    : ChatColor.YELLOW + "cabane héritée sans puits central"));
        } else {
            player.sendMessage(ChatColor.GRAY + " • Conteneurs : " + state.containers.size());
        }
        player.sendMessage(ChatColor.GRAY + " • Joueurs autorisés : " + ChatColor.GREEN + state.trusted.size());
        player.sendMessage(ChatColor.GRAY + " • Statut : " + formatSessionStatus(state));
    }

    private void handleTrust(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Spécifie un joueur à autoriser.");
            return;
        }

        MiningSessionState state = requireSelectedSession(player);
        if (state == null) {
            return;
        }
        if (!canAdministrateSession(player, state)) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Seul le propriétaire ou un administrateur peut modifier les autorisations de cette mine.");
            return;
        }

        Player targetOnline = Bukkit.getPlayerExact(args[1]);
        UUID targetId;
        String targetName;
        if (targetOnline != null) {
            targetId = targetOnline.getUniqueId();
            targetName = targetOnline.getName();
        } else {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(args[1]);
            if (!offline.hasPlayedBefore()) {
                player.sendMessage(CMD_PREFIX + ChatColor.RED
                        + "Joueur inconnu du serveur : " + args[1]);
                return;
            }
            targetId = offline.getUniqueId();
            targetName = offline.getName() != null ? offline.getName() : args[1];
        }

        if (Objects.equals(targetId, state.owner)) {
            player.sendMessage(CMD_PREFIX + ChatColor.YELLOW + "Ce joueur est déjà propriétaire de la mine.");
            return;
        }

        if (state.trusted.contains(targetId)) {
            player.sendMessage(CMD_PREFIX + ChatColor.YELLOW + targetName + " était déjà autorisé.");
            return;
        }
        if (state.trusted.size() >= getMaximumTrustedPlayers()) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED
                    + "Limite de joueurs autorisés atteinte (" + getMaximumTrustedPlayers() + ").");
            return;
        }

        state.trusted.add(targetId);
        saveAllSessions();
        player.sendMessage(CMD_PREFIX + ChatColor.GREEN + targetName
                + " est autorisé à interagir avec ce mineur.");
    }

    private void handleUntrust(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Spécifie un joueur à retirer.");
            return;
        }

        MiningSessionState state = requireSelectedSession(player);
        if (state == null) {
            return;
        }
        if (!canAdministrateSession(player, state)) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED
                    + "Seul le propriétaire ou un administrateur peut modifier les autorisations.");
            return;
        }

        Player targetOnline = Bukkit.getPlayerExact(args[1]);
        UUID targetId;
        String targetName;
        if (targetOnline != null) {
            targetId = targetOnline.getUniqueId();
            targetName = targetOnline.getName();
        } else {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(args[1]);
            if (!offline.hasPlayedBefore()) {
                player.sendMessage(CMD_PREFIX + ChatColor.RED
                        + "Joueur inconnu du serveur : " + args[1]);
                return;
            }
            targetId = offline.getUniqueId();
            targetName = offline.getName() != null ? offline.getName() : args[1];
        }

        if (state.trusted.remove(targetId)) {
            saveAllSessions();
            player.sendMessage(CMD_PREFIX + ChatColor.GREEN
                    + "Autorisation retirée pour " + targetName + ".");
        } else {
            player.sendMessage(CMD_PREFIX + ChatColor.YELLOW
                    + targetName + " n'était pas autorisé sur cette mine.");
        }
    }

    private void onStorageBlocked(MiningSessionState state) {
        boolean newlyBlocked = !state.waitingStorage;
        state.waitingStorage = true;
        if (newlyBlocked) {
            saveAllSessions();
            notifyOwner(state.owner, ChatColor.RED
                    + "Stockage plein ou indisponible : le mineur attend un conteneur valide.");
        }

        RuntimeSession runtime = runtimeOf(state.id);
        if (runtime != null && runtime.miner != null && !runtime.miner.isDead()) {
            if (runtime.storageHologram == null) {
                runtime.storageHologram = new Hologram();
            }
            runtime.storageHologram.show(
                    runtime.miner.getLocation(),
                    ChatColor.RED + "Stockage indisponible"
            );
        }
    }

    private void onStorageFreed(MiningSessionState state) {
        if (!state.waitingStorage) {
            return;
        }
        state.waitingStorage = false;
        saveAllSessions();
        notifyOwner(state.owner, ChatColor.GREEN + "Le mineur reprend, de la place a été libérée.");

        RuntimeSession runtime = runtimeOf(state.id);
        if (runtime != null && runtime.storageHologram != null) {
            runtime.storageHologram.hide();
        }
    }

    private void giveMineSelector(Player player) {
        for (ItemStack content : player.getInventory().getContents()) {
            if (isMineSelector(content)) {
                selections.put(player.getUniqueId(), new Selection());
                player.sendMessage(CMD_PREFIX + ChatColor.YELLOW
                        + "Tu possèdes déjà le sélecteur ; la sélection a été réinitialisée.");
                return;
            }
        }

        ItemStack stick = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stick.getItemMeta();
        if (meta == null) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Impossible de créer le sélecteur.");
            return;
        }

        meta.setDisplayName(SELECTOR_NAME);
        meta.setLore(List.of(
                ChatColor.GRAY + "Clic sur deux blocs au même Y",
                ChatColor.DARK_GRAY + "Objet signé par MineGus"
        ));
        meta.getPersistentDataContainer().set(selectorKey, PersistentDataType.BYTE, (byte) 1);
        stick.setItemMeta(meta);

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stick);
        if (!leftovers.isEmpty()) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED
                    + "Ton inventaire est plein : aucun sélecteur n'a été donné.");
            return;
        }

        selections.put(player.getUniqueId(), new Selection());
        player.sendMessage(CMD_PREFIX + ChatColor.GREEN + "Tu as reçu le bâton de sélection.");
    }

    private boolean isMineSelector(ItemStack item) {
        if (item == null || item.getType() != Material.STICK || !item.hasItemMeta()) {
            return false;
        }
        Byte marker = item.getItemMeta().getPersistentDataContainer()
                .get(selectorKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        /*
         * Paper émet aussi un événement pour la seconde main. Ne traiter que la
         * main principale évite de sélectionner deux coins avec un seul clic.
         */
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) {
            return;
        }

        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        if (isMineSelector(event.getItem())) {
            event.setCancelled(true);
            if (!player.hasPermission("mineplugin.mineur.use")) {
                player.sendMessage(CMD_PREFIX + ChatColor.RED
                        + "Tu n'as pas la permission d'utiliser ce sélecteur.");
                return;
            }

            Selection selection = selections.computeIfAbsent(
                    player.getUniqueId(),
                    ignored -> new Selection()
            );

            if (selection.getCorner1() == null) {
                selection.setCorner1(clicked);
                player.sendMessage(CMD_PREFIX + ChatColor.AQUA + "Premier coin : " + coords(clicked));
                return;
            }

            if (selection.getCorner2() == null) {
                if (!selection.getCorner1().getWorld().equals(clicked.getWorld())) {
                    player.sendMessage(CMD_PREFIX + ChatColor.RED
                            + "Les deux coins doivent être dans le même monde.");
                    return;
                }
                if (selection.getCorner1().getY() != clicked.getY()) {
                    player.sendMessage(CMD_PREFIX + ChatColor.RED
                            + "Les deux blocs doivent être au même Y.");
                    return;
                }

                selection.setCorner2(clicked);
                player.sendMessage(CMD_PREFIX + ChatColor.AQUA + "Second coin : " + coords(clicked));
                createMineFromSelection(player);
                return;
            }

            selection.setCorner1(clicked);
            selection.setCorner2(null);
            player.sendMessage(CMD_PREFIX + ChatColor.AQUA
                    + "Sélection réinitialisée. Premier coin : " + coords(clicked));
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK) {
            return;
        }

        MiningSessionState protectedSession = findProtectedContainerSession(clicked);
        if (protectedSession != null && !isAuthorizedForSession(player, protectedSession)) {
            event.setCancelled(true);
            player.sendMessage(CMD_PREFIX + ChatColor.RED
                    + "Ce stockage appartient à un mineur protégé.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProtectedContainerBreak(BlockBreakEvent event) {
        MiningSessionState protectedSession = findProtectedContainerSession(event.getBlock());
        if (protectedSession == null || isAuthorizedForSession(event.getPlayer(), protectedSession)) {
            return;
        }

        event.setCancelled(true);
        event.getPlayer().sendMessage(CMD_PREFIX + ChatColor.RED
                + "Ce conteneur appartient à un mineur protégé.");
    }

    /**
     * Planifie la mise à jour après la résolution complète de l'événement.
     *
     * <p>Modifier le monde en priorité MONITOR est déconseillé et certains
     * plugins annulent encore tardivement la casse. Au tick suivant, la
     * présence du PDC prouve qu'un coffre annulé est toujours intact.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrackedContainerBreak(BlockBreakEvent event) {
        Block broken = event.getBlock();
        MiningSessionState state = findProtectedContainerSession(broken);
        if (state == null || !isAuthorizedForSession(event.getPlayer(), state)) {
            return;
        }

        UUID sessionId = state.id;
        UUID worldId = broken.getWorld().getUID();
        int x = broken.getX();
        int y = broken.getY();
        int z = broken.getZ();
        Bukkit.getScheduler().runTask(plugin, () ->
                finalizeTrackedContainerBreak(sessionId, worldId, x, y, z));
    }

    private void finalizeTrackedContainerBreak(UUID sessionId,
                                               UUID worldId,
                                               int x,
                                               int y,
                                               int z) {
        MiningSessionState state = findSessionById(sessionId);
        World world = Bukkit.getWorld(worldId);
        if (state == null || world == null) {
            return;
        }

        Block current = world.getBlockAt(x, y, z);
        if (current.getState() instanceof Container container
                && containerBelongsTo(container, state)) {
            // La casse a été annulée ou n'a finalement pas eu lieu.
            return;
        }

        Location brokenLocation = new Location(world, x, y, z);
        removeContainerVector(state, brokenLocation);

        RuntimeSession runtime = runtimeOf(state.id);
        if (runtime != null) {
            runtime.containerLocations.removeIf(location ->
                    isSameBlock(location, current));
            runtime.router = createInventoryRouter(runtime);
        }

        notifyOwner(state.owner, ChatColor.RED + "Un conteneur du mineur a été cassé.");
        if (state.containers.isEmpty()) {
            notifyOwner(state.owner, ChatColor.RED
                    + "Tous les stockages ont été détruits : la session est arrêtée.");
            stopSession(state.id, true, null);
            return;
        }
        saveAllSessions();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        MiningSessionState protectedSession = findProtectedContainerSession(event.getInventory().getHolder());
        if (protectedSession != null && !isAuthorizedForSession(player, protectedSession)) {
            event.setCancelled(true);
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Ce stockage appartient à un mineur protégé.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (findProtectedContainerSession(event.getSource().getHolder()) != null
                || findProtectedContainerSession(event.getDestination().getHolder()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block ->
                findProtectedContainerSession(block) != null
                        || isTrackedTunnelSeal(block));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block ->
                findProtectedContainerSession(block) != null
                        || isTrackedTunnelSeal(block));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChestPlacedNextToProtectedStorage(BlockPlaceEvent event) {
        Material placedType = event.getBlockPlaced().getType();
        if (placedType != Material.CHEST && placedType != Material.TRAPPED_CHEST) {
            return;
        }

        /*
         * Une moitié de coffre non signée fusionnerait avec le stockage du
         * mineur. Le routeur la refuserait ensuite pour empêcher le vol, mais
         * toute la session resterait bloquée. La fusion est donc interdite dès
         * la pose, y compris au propriétaire, qui doit utiliser un emplacement
         * séparé.
         */
        for (BlockFace face : List.of(
                BlockFace.NORTH,
                BlockFace.SOUTH,
                BlockFace.EAST,
                BlockFace.WEST
        )) {
            Block adjacent = event.getBlockPlaced().getRelative(face);
            if (adjacent.getType() != placedType) {
                continue;
            }
            MiningSessionState protectedSession = findProtectedContainerSession(adjacent);
            if (protectedSession == null) {
                continue;
            }
            event.setCancelled(true);
            event.getPlayer().sendMessage(CMD_PREFIX + ChatColor.RED
                    + "Impossible d'accoler un coffre au stockage d'un mineur.");
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (movesProtectedInfrastructure(
                event.getBlocks(),
                event.getDirection()
        ) || isTrackedTunnelSeal(
                event.getBlock().getRelative(event.getDirection())
        )) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (movesProtectedInfrastructure(
                event.getBlocks(),
                event.getDirection()
        )) {
            event.setCancelled(true);
        }
    }

    private boolean movesProtectedInfrastructure(List<Block> movedBlocks,
                                                 BlockFace direction) {
        if (movedBlocks == null) {
            return false;
        }
        for (Block moved : movedBlocks) {
            if (findProtectedContainerSession(moved) != null
                    || isTrackedTunnelSeal(moved)
                    || direction != null
                    && (isTrackedTunnelSeal(moved.getRelative(direction))
                    || isTrackedTunnelSeal(
                    moved.getRelative(direction.getOppositeFace())
            ))) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        int paused = 0;
        int waitingStorage = 0;
        for (MiningSessionState state : getSessionsForOwner(event.getPlayer().getUniqueId())) {
            if (state.paused) {
                paused++;
            } else if (state.waitingStorage) {
                waitingStorage++;
            }

            /*
             * Les poses protégées sont différées lorsque le propriétaire est
             * hors ligne. Sa reconnexion fournit maintenant un acteur valide
             * pour réparer d'un coup toute la colonne déjà excavée.
             */
            RuntimeSession runtime = runtimeOf(state.id);
            if (runtime != null && runtime.decoration != null && !state.paused) {
                runtime.decoration.synchronizeToCursor();
            }
            if (runtime != null
                    && !state.paused
                    && (!isHealthyMiner(runtime) || !isRuntimeWorkActive(runtime))) {
                requestMinerRecovery(
                        state.id,
                        runtime.loopGeneration,
                        "reconnexion du propriétaire"
                );
            }
        }
        if (paused > 0) {
            event.getPlayer().sendMessage(CMD_PREFIX + ChatColor.YELLOW
                    + paused + " mineur(s) sont en pause. Utilise /mineur list puis /mineur reprendre.");
        }
        if (waitingStorage > 0) {
            event.getPlayer().sendMessage(CMD_PREFIX + ChatColor.RED
                    + waitingStorage + " mineur(s) attendent de la place dans leur stockage.");
        }
    }

    /**
     * Les dégâts ordinaires ne doivent jamais détruire le représentant visuel
     * d'une session persistante. Le superviseur reste néanmoins nécessaire pour
     * couvrir /kill, Entity#remove et les suppressions provenant d'autres plugins.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMinerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }
        UUID sessionId = entitySessionId(villager);
        if (sessionId != null && findSessionById(sessionId) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * Empêche notamment la zombification d'un mineur. Une transformation
     * remplacerait l'entité sans conserver de référence runtime fiable.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMinerTransform(EntityTransformEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }
        UUID sessionId = entitySessionId(villager);
        if (sessionId != null && findSessionById(sessionId) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * Une mort forcée est réparée au tick suivant. Les drops sont supprimés pour
     * éviter qu'une commande répétée duplique l'équipement cosmétique du PNJ.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMinerDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }

        UUID sessionId = entitySessionId(villager);
        MiningSessionState state = findSessionById(sessionId);
        if (state == null) {
            return;
        }

        /*
         * Le nettoyage vaut aussi pour un ancien doublon signé qui serait tué
         * avant le passage de réconciliation.
         */
        event.getDrops().clear();
        event.setDroppedExp(0);

        RuntimeSession runtime = runtimeOf(sessionId);
        if (runtime == null || runtime.state.paused || shuttingDown) {
            return;
        }
        if (runtime.miner != null
                && runtime.miner.getUniqueId().equals(villager.getUniqueId())) {
            runtime.miner = null;
        }
        requestMinerRecovery(
                sessionId,
                runtime.loopGeneration,
                "mort forcée du PNJ"
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTunnelFluidFlow(BlockFromToEvent event) {
        if (event == null || !isFluidBlock(event.getBlock())) {
            return;
        }

        Block source = event.getBlock();
        Block destination = event.getToBlock();
        for (RuntimeSession runtime : runtimes.values()) {
            MiningSessionState state = runtime.state;
            if (state == null
                    || state.paused
                    || state.pattern != MiningPattern.TUNNEL
                    || !isTunnelInfrastructureEnabled(state)
                    || !isTunnelFluidsEnabled()
                    || state.worldUid == null
                    || !state.worldUid.equals(source.getWorld().getUID())) {
                continue;
            }

            boolean sourceInside = isInsideTunnelVolume(state, source);
            boolean destinationInside = isInsideTunnelVolume(state, destination);
            if (!sourceInside && !destinationInside) {
                continue;
            }

            /*
             * Je fige aussi les écoulements entièrement intérieurs. Pendant un
             * drainage réparti sur plusieurs ticks, une source déjà détectée ne
             * peut ainsi coloniser une case qui a été scannée plus tôt.
             */
            event.setCancelled(true);
            boolean touchesActiveSection = isInsideActiveTunnelVolume(state, source)
                    || isInsideActiveTunnelVolume(state, destination);
            if (touchesActiveSection) {
                if (runtime.tunnelInfrastructure == null
                        || runtime.tunnelInfrastructure.completed) {
                    runtime.tunnelInfrastructure = null;
                } else {
                    runtime.tunnelInfrastructure.rescanRequested = true;
                }
            }
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnedTunnelSealBreak(BlockBreakEvent event) {
        if (!AutomatedMiningContext.isActive()) {
            invalidateTunnelSealOwnership(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnedTunnelSealPlacement(BlockPlaceEvent event) {
        if (!AutomatedMiningContext.isActive()) {
            invalidateTunnelSealOwnership(event.getBlockPlaced());
        }
    }

    private void invalidateTunnelSealOwnership(Block block) {
        if (block == null) {
            return;
        }
        boolean changed = false;
        for (MiningSessionState state : sessions) {
            if (state == null
                    || state.worldUid == null
                    || !state.worldUid.equals(block.getWorld().getUID())) {
                continue;
            }
            BlockPos position = new BlockPos(block.getX(), block.getY(), block.getZ());
            boolean removed = removeCoordinate(state.tunnelFrontierSeals, block)
                    | removeCoordinate(state.tunnelForeignFrontierSeals, block)
                    | removeCoordinate(state.tunnelPermanentSeals, block)
                    | removeCoordinate(state.pendingTunnelFrontierSeals, block)
                    | removeCoordinate(state.pendingTunnelPermanentSeals, block);
            RuntimeSession runtime = runtimeOf(state.id);
            boolean runtimeAffected = runtime != null
                    && runtime.tunnelInfrastructure != null
                    && runtime.tunnelInfrastructure.managedPositions.contains(position);
            if (!removed && !runtimeAffected) {
                continue;
            }
            if (removed && state.pendingTunnelFrontierSeals.isEmpty()
                    && state.pendingTunnelPermanentSeals.isEmpty()) {
                state.pendingTunnelInfrastructureSection = "";
            }
            changed |= removed;
            if (runtime != null) {
                /*
                 * Je recommence aussi un préflight encore non sauvegardé : un
                 * joueur ne peut pas faire revendiquer son bloc en le posant
                 * entre l'inspection incrémentale et l'écriture du journal.
                 */
                runtime.tunnelInfrastructure = null;
                runtime.tunnelDecoration = null;
            }
        }
        if (changed) {
            saveAllSessions();
        }
    }

    private boolean isTrackedTunnelSeal(Block block) {
        if (block == null) {
            return false;
        }
        for (MiningSessionState state : sessions) {
            if (state == null
                    || state.worldUid == null
                    || !state.worldUid.equals(block.getWorld().getUID())) {
                continue;
            }
            if (containsCoordinate(state.tunnelFrontierSeals, block)
                    || containsCoordinate(state.tunnelForeignFrontierSeals, block)
                    || containsCoordinate(state.tunnelPermanentSeals, block)
                    || containsCoordinate(state.pendingTunnelFrontierSeals, block)
                    || containsCoordinate(state.pendingTunnelPermanentSeals, block)) {
                return true;
            }
            RuntimeSession runtime = runtimeOf(state.id);
            if (runtime != null
                    && runtime.tunnelInfrastructure != null
                    && runtime.tunnelInfrastructure.managedPositions.contains(
                    new BlockPos(block.getX(), block.getY(), block.getZ())
            )) {
                return true;
            }
            if (isGlassBlock(block.getType())
                    && isTunnelBoundaryBlock(state, block)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsCoordinate(List<Vector> positions, Block block) {
        for (Vector vector : positions) {
            if (vector != null
                    && vector.getBlockX() == block.getX()
                    && vector.getBlockY() == block.getY()
                    && vector.getBlockZ() == block.getZ()) {
                return true;
            }
        }
        return false;
    }

    private boolean removeCoordinate(List<Vector> positions, Block block) {
        return positions.removeIf(vector -> vector != null
                && vector.getBlockX() == block.getX()
                && vector.getBlockY() == block.getY()
                && vector.getBlockZ() == block.getZ());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        /*
         * Après un arrêt brutal, un ancien PNJ peut être sauvegardé dans un
         * tronçon qui n'est plus celui du curseur. Il est supprimé dès que son
         * chunk revient en mémoire, sauf s'il correspond exactement à l'un des
         * acteurs du runtime courant.
         */
        cleanupUnexpectedMineEntities(event.getChunk());
    }

    private void cleanupUnexpectedMineEntities(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        for (Entity entity : chunk.getEntities()) {
            String rawSession = entity.getPersistentDataContainer()
                    .get(entitySessionKey, PersistentDataType.STRING);
            if (rawSession == null || rawSession.isBlank()) {
                continue;
            }

            UUID sessionId;
            try {
                sessionId = UUID.fromString(rawSession);
            } catch (IllegalArgumentException exception) {
                removeEntitySafely(entity);
                continue;
            }

            RuntimeSession runtime = runtimeOf(sessionId);
            if (!isExpectedRuntimeEntity(runtime, entity)) {
                removeEntitySafely(entity);
            }
        }
    }

    private boolean isExpectedRuntimeEntity(RuntimeSession runtime, Entity entity) {
        if (runtime == null || entity == null || runtime.state.paused) {
            return false;
        }
        if (runtime.miner != null
                && runtime.miner.getUniqueId().equals(entity.getUniqueId())) {
            return true;
        }
        for (Golem golem : runtime.golems) {
            if (golem != null
                    && golem.getGolem() != null
                    && golem.getGolem().getUniqueId().equals(entity.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Nettoie les acteurs persistants déjà chargés avant l'enregistrement du
     * listener de chunks. Cette passe ne charge aucun chunk supplémentaire.
     */
    private void cleanupLoadedMineEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                cleanupUnexpectedMineEntities(chunk);
            }
        }
    }

    /**
     * Lit l'identifiant persistant d'un acteur de mine sans faire confiance à
     * une donnée NBT/PDC malformée.
     */
    private UUID entitySessionId(Entity entity) {
        if (entity == null) {
            return null;
        }
        String rawSession = entity.getPersistentDataContainer()
                .get(entitySessionKey, PersistentDataType.STRING);
        if (rawSession == null || rawSession.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(rawSession.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Vérifie que la référence runtime désigne bien l'unique PNJ signé de la
     * session, dans le bon monde et encore utilisable par Bukkit.
     */
    private boolean isHealthyMiner(RuntimeSession runtime) {
        if (runtime == null
                || runtime.state == null
                || runtime.state.id == null
                || runtime.state.worldUid == null
                || runtime.miner == null) {
            return false;
        }
        try {
            return !runtime.miner.isDead()
                    && runtime.miner.isValid()
                    && runtime.miner.getWorld() != null
                    && runtime.state.worldUid.equals(runtime.miner.getWorld().getUID())
                    && runtime.state.id.equals(entitySessionId(runtime.miner));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Une référence non nulle ne garantit pas que la tâche Bukkit tourne
     * encore : elle peut avoir été annulée par une erreur ou un plugin tiers.
     */
    private boolean isLoopActive(RuntimeSession runtime) {
        if (runtime == null || runtime.loop == null) {
            return false;
        }
        try {
            return !runtime.loop.isCancelled();
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    /**
     * Retire une entité sans laisser une implémentation tierce interrompre la
     * réconciliation des autres sessions.
     */
    private boolean removeEntitySafely(Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            entity.remove();
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "[Mineur] Suppression d'un ancien acteur impossible.",
                    exception
            );
            return false;
        }
    }

    /**
     * Réapplique les propriétés critiques. Un autre plugin peut les modifier
     * sans supprimer l'entité ; la surveillance doit aussi réparer ce cas.
     */
    private void enforceMinerProperties(RuntimeSession runtime) {
        if (!isHealthyMiner(runtime)) {
            return;
        }
        Villager villager = runtime.miner;
        villager.setCustomName(ChatColor.GOLD + "Mineur");
        villager.setCustomNameVisible(true);
        villager.setProfession(Villager.Profession.ARMORER);
        villager.setAI(false);
        villager.setGravity(false);
        villager.setInvulnerable(true);
        villager.setCollidable(false);
        villager.setRemoveWhenFarAway(false);
        villager.setPersistent(true);
        villager.getPersistentDataContainer().set(
                entitySessionKey,
                PersistentDataType.STRING,
                runtime.state.id.toString()
        );
    }

    /**
     * Supprime tous les PNJ mineurs chargés d'une session, sauf l'UUID
     * explicitement conservé. La recherche couvre tous les mondes chargés afin
     * qu'une téléportation externe ne puisse laisser un doublon ailleurs.
     */
    private int removeLoadedMinersForSession(UUID sessionId, UUID keptEntityId) {
        if (sessionId == null) {
            return 0;
        }

        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Villager villager : new ArrayList<>(world.getEntitiesByClass(Villager.class))) {
                if (!sessionId.equals(entitySessionId(villager))
                        || Objects.equals(keptEntityId, villager.getUniqueId())) {
                    continue;
                }
                if (removeEntitySafely(villager)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Passe globale anti-doublon. Les acteurs sans runtime, ceux d'une session
     * en pause et tout exemplaire autre que la référence canonique sont retirés.
     */
    private void reconcileLoadedMinerEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Villager villager : new ArrayList<>(world.getEntitiesByClass(Villager.class))) {
                String rawSession = villager.getPersistentDataContainer()
                        .get(entitySessionKey, PersistentDataType.STRING);
                if (rawSession == null || rawSession.isBlank()) {
                    continue;
                }

                UUID sessionId = entitySessionId(villager);
                if (sessionId == null) {
                    removeEntitySafely(villager);
                    continue;
                }

                RuntimeSession runtime = runtimeOf(sessionId);
                boolean canonical = runtime != null
                        && !runtime.state.paused
                        && isHealthyMiner(runtime)
                        && runtime.miner.getUniqueId().equals(villager.getUniqueId());
                if (!canonical) {
                    removeEntitySafely(villager);
                }
            }
        }
    }

    private long getEntityWatchdogIntervalTicks() {
        long configured = plugin.getConfig().getLong(
                "mineur.persistence.entity-watchdog-interval-ticks",
                DEFAULT_ENTITY_WATCHDOG_INTERVAL_TICKS
        );
        return Math.max(5L, Math.min(1_200L, configured));
    }

    private long getEntityRecoveryDelayTicks() {
        long configured = plugin.getConfig().getLong(
                "mineur.persistence.entity-recovery-delay-ticks",
                DEFAULT_ENTITY_RECOVERY_DELAY_TICKS
        );
        return Math.max(1L, Math.min(200L, configured));
    }

    /**
     * Démarre une unique tâche de réconciliation. Elle couvre les suppressions
     * qui ne déclenchent aucun événement Bukkit standard, notamment
     * {@link Entity#remove()} appelé par un autre plugin.
     */
    private void startEntityWatchdog() {
        stopEntityWatchdog();
        if (shuttingDown) {
            return;
        }

        long interval = getEntityWatchdogIntervalTicks();
        entityWatchdogTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::superviseMinerEntities,
                interval,
                interval
        );
    }

    private void stopEntityWatchdog() {
        if (entityWatchdogTask != null) {
            entityWatchdogTask.cancel();
            entityWatchdogTask = null;
        }
    }

    private void superviseMinerEntities() {
        if (shuttingDown) {
            return;
        }

        /*
         * Cette passe est effectuée avant les respawns : un ancien exemplaire
         * chargé ne peut donc jamais coexister avec celui qui va être créé.
         */
        try {
            reconcileLoadedMinerEntities();
        } catch (RuntimeException exception) {
            /*
             * Le contrôle des runtimes reste utile même si l'énumération globale
             * d'un monde en cours de déchargement échoue temporairement.
             */
            plugin.getLogger().log(
                    Level.WARNING,
                    "[Mineur] Réconciliation globale des PNJ impossible.",
                    exception
            );
        }

        for (RuntimeSession runtime : new ArrayList<>(runtimes.values())) {
            if (runtime == null || runtime.state.paused) {
                continue;
            }

            try {
                if (isHealthyMiner(runtime)) {
                    enforceMinerProperties(runtime);
                    try {
                        ensureGolems(runtime);
                    } catch (RuntimeException exception) {
                        plugin.getLogger().log(
                                Level.FINE,
                                "[Mineur] Surveillance des golems impossible pour la session "
                                        + runtime.state.id + ".",
                                exception
                        );
                    }
                }

                if (!isHealthyMiner(runtime) || !isRuntimeWorkActive(runtime)) {
                    requestMinerRecovery(
                            runtime.state.id,
                            runtime.loopGeneration,
                            "surveillance périodique"
                    );
                }
            } catch (RuntimeException exception) {
                /*
                 * Une session défectueuse ne doit jamais interrompre la
                 * surveillance de toutes les autres.
                 */
                plugin.getLogger().log(
                        Level.WARNING,
                        "[Mineur] Surveillance impossible pour la session "
                                + runtime.state.id + ".",
                        exception
                );
            }
        }
    }

    /**
     * Sérialise les demandes de réparation par session. Plusieurs événements
     * survenus dans le même tick ne peuvent donc produire qu'un seul respawn.
     */
    private void requestMinerRecovery(UUID sessionId,
                                      long expectedGeneration,
                                      String reason) {
        if (sessionId == null || shuttingDown || !pendingMinerRecoveries.add(sessionId)) {
            return;
        }

        long delay = getEntityRecoveryDelayTicks();
        try {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    if (shuttingDown) {
                        return;
                    }

                    RuntimeSession runtime = runtimeOf(sessionId);
                    if (runtime == null || runtime.state.paused) {
                        return;
                    }

                    /*
                     * Une commande a peut-être déjà créé une nouvelle boucle entre
                     * la demande et son exécution. On ne touche pas à ce runtime sain.
                     * Si le PNJ manque toujours, la réparation reste prioritaire.
                     */
                    if (runtime.loopGeneration != expectedGeneration
                            && isHealthyMiner(runtime)
                            && isLoopActive(runtime)) {
                        return;
                    }
                    recoverMinerRuntime(runtime, reason);
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "[Mineur] Restauration inattendue impossible pour la session "
                                    + sessionId + ".",
                            exception
                    );
                } finally {
                    /*
                     * Le verrou reste détenu pendant toute la réparation : un
                     * événement déclenché synchroniquement par le respawn ne peut
                     * pas programmer un second PNJ.
                     */
                    pendingMinerRecoveries.remove(sessionId);
                }
            }, delay);
        } catch (RuntimeException exception) {
            pendingMinerRecoveries.remove(sessionId);
            if (!shuttingDown) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "[Mineur] Impossible de planifier la restauration de la session "
                                + sessionId + ".",
                        exception
                );
            }
        }
    }

    /**
     * Répare atomiquement le couple « PNJ + boucle ». Le curseur est restauré
     * avant toute création, puis les anciens acteurs chargés sont supprimés.
     * En cas d'échec temporaire, la session reste active et le watchdog réessaie.
     */
    private void recoverMinerRuntime(RuntimeSession runtime, String reason) {
        if (runtime == null || runtime.state.paused || shuttingDown) {
            return;
        }

        boolean minerWasMissing = !isHealthyMiner(runtime);
        boolean loopWasMissing = !isLoopActive(runtime);

        if (minerWasMissing) {
            /*
             * Invalide immédiatement tous les callbacks de l'ancienne boucle.
             * Le checkpoint non validé est restauré avant de créer un acteur.
             */
            runtime.loopGeneration++;
            if (runtime.loop != null) {
                runtime.loop.cancelAndRollback();
                runtime.loop = null;
            } else {
                runtime.state.rollbackPendingCursor();
            }

            try {
                refreshChunkTickets(runtime.state, runtime);
                replaceRuntimeMiner(runtime);
                runtime.minerRecoveryFailures = 0;
            } catch (RuntimeException exception) {
                int failures = ++runtime.minerRecoveryFailures;
                if (failures == 1 || failures % 30 == 0) {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "[Mineur] Restauration du PNJ impossible pour la session "
                                    + runtime.state.id + " (tentative " + failures + ").",
                            exception
                    );
                }
                return;
            }
        } else {
            try {
                enforceMinerProperties(runtime);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                        Level.FINE,
                        "[Mineur] Propriétés du PNJ non réappliquées pour la session "
                                + runtime.state.id + ".",
                        exception
                );
            }
        }

        /*
         * isLoopActive() détecte également une BukkitRunnable annulée dont la
         * référence n'a pas encore été remise à null.
         */
        if (!isLoopActive(runtime)) {
            try {
                restartLoop(runtime);
                runtime.minerRecoveryFailures = 0;
            } catch (RuntimeException exception) {
                runtime.state.rollbackPendingCursor();
                runtime.loop = null;
                int failures = ++runtime.minerRecoveryFailures;
                if (failures == 1 || failures % 30 == 0) {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "[Mineur] Boucle non restaurée pour la session "
                                    + runtime.state.id + " (tentative " + failures + ").",
                            exception
                    );
                }
                return;
            }
        }

        try {
            ensureGolems(runtime);
        } catch (RuntimeException exception) {
            /*
             * Les gardes sont auxiliaires : leur échec ne doit jamais annuler
             * la restauration réussie du mineur principal.
             */
            plugin.getLogger().log(
                    Level.FINE,
                    "[Mineur] Golems non restaurés pour la session "
                            + runtime.state.id + ".",
                    exception
            );
        }

        removeLoadedMinersForSession(
                runtime.state.id,
                runtime.miner != null ? runtime.miner.getUniqueId() : null
        );
        saveAllSessions();

        if (minerWasMissing || loopWasMissing) {
            plugin.getLogger().info("[Mineur] Runtime de la session " + runtime.state.id
                    + " restauré automatiquement (" + reason + ").");
        }
    }

    public boolean saveAllSessions() {
        return sessionStore.saveAll(sessions);
    }

    public void loadSavedSessions() {
        stopEntityWatchdog();
        shuttingDown = false;
        pendingMinerRecoveries.clear();

        for (RuntimeSession runtime : new ArrayList<>(runtimes.values())) {
            runtime.stop(false);
        }
        runtimes.clear();
        sessions.clear();
        ownerSessions.clear();
        selectedSessions.clear();

        /*
         * À ce stade aucun acteur n'est légitime : tous les PNJ signés encore
         * présents proviennent d'un arrêt brutal ou d'une ancienne duplication.
         */
        cleanupLoadedMineEntities();

        List<MiningSessionState> loaded = sessionStore.load();
        for (MiningSessionState state : loaded) {
            try {
                if (state.resumeLegacyUnknownPause()) {
                    /*
                     * Avant le schéma 6, une simple absence du propriétaire au
                     * démarrage pouvait être persistée comme une pause définitive.
                     * La cause étant inconnue dans ces anciens fichiers, cette
                     * migration réactive la session une seule fois ; toutes les
                     * nouvelles pauses possèdent ensuite une cause fiable.
                     */
                    plugin.getLogger().info("[Mineur] Session " + state.id
                            + " réactivée lors de la migration de persistance.");
                }
                normalizeLoadedState(state);
                if (!hasValidDimensions(state.width, state.length)) {
                    plugin.getLogger().warning("[Mineur] Session " + state.id
                            + " ignorée : dimensions supérieures aux limites de sécurité.");
                    continue;
                }

                MiningSessionState.StructureBounds persistedStructure = state.structureBounds;
                MiningSessionState overlap = findOverlappingSession(
                        state.base.getWorld(),
                        persistedStructure != null
                                ? persistedStructure.minX()
                                : state.base.getBlockX(),
                        persistedStructure != null
                                ? persistedStructure.maxX()
                                : safeRectangleMaximum(state.base.getBlockX(), state.width),
                        persistedStructure != null
                                ? persistedStructure.minZ()
                                : state.base.getBlockZ(),
                        persistedStructure != null
                                ? persistedStructure.maxZ()
                                : safeRectangleMaximum(state.base.getBlockZ(), state.length),
                        persistedStructure != null ? 1 : 3
                );
                if (overlap == null && state.cursor != null) {
                    overlap = findOverlappingSession(
                            state.base.getWorld(),
                            state.cursor.minX,
                            safeRectangleMaximum(state.cursor.minX, state.cursor.width),
                            state.cursor.minZ,
                            safeRectangleMaximum(state.cursor.minZ, state.cursor.length),
                            3
                    );
                }
                if (overlap != null) {
                    state.pause(MiningSessionState.PauseReason.SAFETY);
                    plugin.getLogger().warning("[Mineur] Session " + state.id
                            + " chargée en pause : chevauchement avec "
                            + overlap.id + ".");
                }

                sessions.add(state);
                registerOwnerSession(state, false);
                startRuntime(state, false);
            } catch (RuntimeException exception) {
                state.pause(MiningSessionState.PauseReason.ERROR);
                RuntimeSession failed = runtimes.remove(state.id);
                if (failed != null) {
                    failed.stop(false);
                }
                if (!sessions.contains(state)) {
                    sessions.add(state);
                    registerOwnerSession(state, false);
                }
                plugin.getLogger().log(Level.WARNING,
                        "[Mineur] Session " + state.id + " chargée en pause après une erreur.",
                        exception);
            }
        }

        saveAllSessions();
        startEntityWatchdog();
        plugin.getLogger().info("Mineur : " + sessions.size() + " session(s) rechargée(s).");
    }

    public void stopAllSessions() {
        shuttingDown = true;
        stopEntityWatchdog();
        pendingMinerRecoveries.clear();

        for (RuntimeSession runtime : new ArrayList<>(runtimes.values())) {
            /*
             * À l'arrêt du serveur, les coffres restent associés aux sessions
             * persistées. Seuls les acteurs et tickets temporaires sont retirés.
             */
            runtime.stop(false);
        }
        runtimes.clear();
        sessions.clear();
        ownerSessions.clear();
        selectedSessions.clear();
        selections.clear();
    }

    private void startRuntime(MiningSessionState state, boolean freshlyCreated) {
        startRuntime(state, freshlyCreated, null);
    }

    private void startRuntime(MiningSessionState state,
                              boolean freshlyCreated,
                              MineCabinBuilder.Plan preparedCabinPlan) {
        normalizeLoadedState(state);

        RuntimeSession previous = runtimes.remove(state.id);
        if (previous != null) {
            previous.stop(false);
        }

        RuntimeSession runtime = new RuntimeSession(state);
        runtimes.put(state.id, runtime);

        try {
            /*
             * Les tickets couvrent aussi l'emprise persistée du chevalement.
             * Tous les blocs sont donc lus et écrits dans des chunks déjà
             * chargés, sans coût synchrone caché au milieu de la transaction.
             */
            refreshChunkTickets(state, runtime);
            clearZoneForState(state);

            boolean allowBlockPlacement = allowMinerBlockPlacement();

            ensureContainers(state, runtime, freshlyCreated, preparedCabinPlan);
            runtime.router = createInventoryRouter(runtime);
            runtime.decoration = shouldUseQuarryDecoration(state)
                    ? new DecorationDelegate(state)
                    : null;

            if (state.paused) {
                runtime.commitFreshConstruction();
                if (runtime.decoration != null) {
                    runtime.decoration.synchronizeToCursor();
                }
                runtime.suspend();
                return;
            }
            activateRuntime(runtime);

            /*
             * L'ancien cadre de pierre reste disponible comme repli lorsque la
             * nouvelle structure est explicitement désactivée. Une cabane ne
             * reçoit jamais ce cadre supplémentaire.
             */
            if (freshlyCreated
                    && state.structureVersion < MineCabinBuilder.FIRST_STRUCTURE_VERSION
                    && allowBlockPlacement
                    && state.pattern == MiningPattern.QUARRY) {
                ensureFrame(state);
            }
            runtime.commitFreshConstruction();
            if (runtime.decoration != null) {
                runtime.decoration.synchronizeToCursor();
            }
        } catch (RuntimeException exception) {
            runtime.rollbackFreshConstruction();
            throw exception;
        }
    }

    private void activateRuntime(RuntimeSession runtime) {
        if (runtime == null || runtime.state.paused) {
            return;
        }

        refreshChunkTickets(runtime.state, runtime);
        runtime.router = createInventoryRouter(runtime);

        if (!isHealthyMiner(runtime)) {
            replaceRuntimeMiner(runtime);
        }
        ensureGolems(runtime);

        if (shouldUseQuarryDecoration(runtime.state)) {
            if (runtime.decoration == null) {
                runtime.decoration = new DecorationDelegate(runtime.state);
            }
        } else {
            runtime.decoration = null;
        }

        /*
         * Une construction fraîche reste rollbackable jusqu'à la fin de
         * startRuntime. Son puits dynamique ne démarre qu'après le commit ;
         * les restaurations, elles, peuvent être réparées immédiatement.
         */
        if (runtime.decoration != null && runtime.freshConstruction == null) {
            /*
             * Une reprise répare aussi les niveaux déjà parcourus. Cela couvre
             * une pose précédemment refusée, un bloc retiré pendant la pause ou
             * une ancienne tâche interrompue entre deux callbacks.
             */
            runtime.decoration.synchronizeToCursor();
        }

        restartLoop(runtime);
    }

    private MiningSessionState findSessionById(UUID sessionId) {
        if (sessionId == null) {
            return null;
        }
        for (MiningSessionState state : sessions) {
            if (state != null && Objects.equals(state.id, sessionId)) {
                return state;
            }
        }
        return null;
    }

    private String coords(Block block) {
        return "(" + block.getX() + ", " + block.getY() + ", " + block.getZ() + ")";
    }

    private List<MiningSessionState> getSessionsForOwner(UUID ownerId) {
        List<MiningSessionState> ownedSessions = new ArrayList<>();
        if (ownerId == null) {
            return ownedSessions;
        }
        for (UUID sessionId : ownerSessions.getOrDefault(ownerId, List.of())) {
            MiningSessionState state = findSessionById(sessionId);
            if (state != null) {
                ownedSessions.add(state);
            }
        }
        return ownedSessions;
    }

    private List<MiningSessionState> getAccessibleSessions(Player player) {
        List<MiningSessionState> accessible = new ArrayList<>();
        if (player == null) {
            return accessible;
        }
        if (player.hasPermission("mineplugin.mineur.admin")) {
            accessible.addAll(sessions);
            return accessible;
        }

        Set<UUID> seen = new HashSet<>();
        for (MiningSessionState state : getSessionsForOwner(player.getUniqueId())) {
            if (seen.add(state.id)) {
                accessible.add(state);
            }
        }
        for (MiningSessionState state : sessions) {
            if (state.trusted.contains(player.getUniqueId()) && seen.add(state.id)) {
                accessible.add(state);
            }
        }
        return accessible;
    }

    private void registerOwnerSession(MiningSessionState state, boolean selectNew) {
        if (state == null || state.owner == null) {
            return;
        }
        List<UUID> owned = ownerSessions.computeIfAbsent(state.owner, ignored -> new ArrayList<>());
        if (!owned.contains(state.id)) {
            owned.add(state.id);
        }
        if (selectNew || state.selected || (owned.size() == 1 && !selectedSessions.containsKey(state.owner))) {
            setSelectedSession(state.owner, state.id);
        }
    }

    private void unregisterOwnerSession(MiningSessionState state) {
        if (state == null || state.owner == null) {
            return;
        }

        List<UUID> owned = ownerSessions.get(state.owner);
        if (owned != null) {
            owned.remove(state.id);
            if (owned.isEmpty()) {
                ownerSessions.remove(state.owner);
            }
        }

        if (!Objects.equals(selectedSessions.get(state.owner), state.id)) {
            return;
        }
        if (owned == null || owned.isEmpty()) {
            selectedSessions.remove(state.owner);
            return;
        }
        /*
         * Toujours sélectionner une session restante. L'ancien comportement
         * forçait inutilement /mineur select après l'arrêt d'un mineur parmi
         * trois ou plus.
         */
        setSelectedSession(state.owner, owned.get(0));
    }

    private void setSelectedSession(UUID viewerId, UUID sessionId) {
        if (viewerId == null || sessionId == null) {
            return;
        }
        selectedSessions.put(viewerId, sessionId);
        MiningSessionState selected = findSessionById(sessionId);
        if (selected == null || !Objects.equals(selected.owner, viewerId)) {
            return;
        }
        for (MiningSessionState state : getSessionsForOwner(viewerId)) {
            state.selected = Objects.equals(state.id, sessionId);
        }
    }

    private MiningSessionState resolveSelectedSession(Player player) {
        List<MiningSessionState> accessibleSessions = getAccessibleSessions(player);
        if (accessibleSessions.isEmpty()) {
            return null;
        }
        if (accessibleSessions.size() == 1) {
            setSelectedSession(player.getUniqueId(), accessibleSessions.get(0).id);
            return accessibleSessions.get(0);
        }

        UUID selectedId = selectedSessions.get(player.getUniqueId());
        if (selectedId == null) {
            return null;
        }

        for (MiningSessionState state : accessibleSessions) {
            if (Objects.equals(state.id, selectedId)) {
                return state;
            }
        }
        selectedSessions.remove(player.getUniqueId());
        return null;
    }

    private MiningSessionState requireSelectedSession(Player player) {
        List<MiningSessionState> accessibleSessions = getAccessibleSessions(player);
        if (accessibleSessions.isEmpty()) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Aucune session active ou partagée.");
            return null;
        }

        MiningSessionState selected = resolveSelectedSession(player);
        if (selected != null) {
            return selected;
        }

        player.sendMessage(CMD_PREFIX + ChatColor.YELLOW + "Plusieurs mineurs accessibles. Utilise /mineur list puis /mineur select <n>.");
        return null;
    }

    public void refreshOwnerSessions(UUID ownerId) {
        for (MiningSessionState state : getSessionsForOwner(ownerId)) {
            RuntimeSession runtime = runtimeOf(state.id);
            if (runtime == null || state.paused) {
                continue;
            }
            try {
                restartLoop(runtime);
            } catch (RuntimeException exception) {
                /*
                 * Le recalcul du bonus de métier intervient depuis un événement
                 * joueur. Une erreur de chunk ou d'acteur ne doit ni remonter
                 * dans cet événement ni laisser une session annoncée active.
                 */
                state.pause(MiningSessionState.PauseReason.ERROR);
                runtime.suspend();
                plugin.getLogger().log(
                        Level.WARNING,
                        "[Mineur] Actualisation de vitesse impossible pour la session "
                                + state.id + ".",
                        exception
                );
                notifyOwner(state.owner, ChatColor.RED
                        + "Le bonus de vitesse n'a pas pu être appliqué ; la session est en pause.");
            }
        }
        saveAllSessions();
    }

    private RuntimeSession runtimeOf(UUID sessionId) {
        return runtimes.get(sessionId);
    }

    private JobManager getJobManager() {
        if (plugin instanceof MinePlugin minePlugin) {
            return minePlugin.getJobManager();
        }
        return null;
    }

    private int getMaxMinesForOwner(UUID ownerId) {
        JobManager jobManager = getJobManager();
        int configuredFallback = Math.max(1, Math.min(64,
                plugin.getConfig().getInt("mineur.limits.max-sessions-per-player", 1)));
        int result = jobManager != null
                ? jobManager.getMaxMinesForPlayer(ownerId)
                : configuredFallback;
        return Math.max(1, Math.min(64, result));
    }

    private Integer getNextMineUnlockLevel(UUID ownerId) {
        JobManager jobManager = getJobManager();
        return jobManager != null ? jobManager.getNextMineUnlockLevelForPlayer(ownerId) : null;
    }

    private double getOwnerSpeedMultiplier(UUID ownerId) {
        JobManager jobManager = getJobManager();
        double multiplier = jobManager != null
                ? jobManager.getMiningSpeedMultiplier(ownerId)
                : 1.0D;
        return Double.isFinite(multiplier) && multiplier > 0.0D
                ? Math.min(multiplier, 100.0D)
                : 1.0D;
    }

    private String formatDecimal(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001D) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String formatSessionStatus(MiningSessionState state) {
        if (state == null) {
            return ChatColor.RED + "indisponible";
        }
        if (state.paused) {
            return ChatColor.YELLOW + "en pause";
        }
        if (state.waitingStorage) {
            return ChatColor.RED + "en attente de stockage";
        }
        return ChatColor.GREEN + "actif";
    }

    private boolean isSameBlock(Location location, Block block) {
        return location.getWorld() != null
                && location.getWorld().equals(block.getWorld())
                && location.getBlockX() == block.getX()
                && location.getBlockY() == block.getY()
                && location.getBlockZ() == block.getZ();
    }

    private void removeContainerVector(MiningSessionState state, Location location) {
        Iterator<Vector> iterator = state.containers.iterator();
        while (iterator.hasNext()) {
            Vector vector = iterator.next();
            if (vector.getBlockX() == location.getBlockX()
                    && vector.getBlockY() == location.getBlockY()
                    && vector.getBlockZ() == location.getBlockZ()) {
                iterator.remove();
                return;
            }
        }
    }

    private void notifyOwner(UUID ownerId, String message) {
        if (ownerId == null) {
            return;
        }
        Player target = Bukkit.getPlayer(ownerId);
        if (target != null) {
            target.sendMessage(CMD_PREFIX + message);
        }
    }

    private List<Block> resolveContainerBlocks(RuntimeSession runtime) {
        List<Block> blocks = new ArrayList<>();
        if (runtime == null || runtime.state == null) {
            return blocks;
        }

        Iterator<Location> iterator = runtime.containerLocations.iterator();
        while (iterator.hasNext()) {
            Location location = iterator.next();
            if (location == null || location.getWorld() == null
                    || !location.getWorld().getUID().equals(runtime.state.worldUid)) {
                iterator.remove();
                continue;
            }

            Block block = location.getBlock();
            if (!(block.getState() instanceof Container container)
                    || !containerBelongsTo(container, runtime.state)) {
                iterator.remove();
                removeContainerVector(runtime.state, location);
                continue;
            }
            blocks.add(block);
        }
        return blocks;
    }

    private InventoryRouter createInventoryRouter(RuntimeSession runtime) {
        if (runtime == null || runtime.state == null) {
            return new InventoryRouter(List.of(), ignored -> false);
        }
        return new InventoryRouter(
                resolveContainerBlocks(runtime),
                container -> isUsableStorageContainer(container, runtime.state)
        );
    }

    /**
     * Refuse un double coffre dont une moitié n'appartient pas à la session.
     * Sans ce contrôle, un joueur pourrait accoler son coffre au stockage et
     * recevoir une partie des drops via l'inventaire combiné.
     */
    private boolean isUsableStorageContainer(Container container, MiningSessionState state) {
        if (!containerBelongsTo(container, state)) {
            return false;
        }

        InventoryHolder holder = container.getInventory().getHolder();
        if (!(holder instanceof DoubleChest doubleChest)) {
            return true;
        }
        return holderBelongsToSession(doubleChest.getLeftSide(), state)
                && holderBelongsToSession(doubleChest.getRightSide(), state);
    }

    private boolean holderBelongsToSession(InventoryHolder holder, MiningSessionState state) {
        return holder instanceof Container container
                && containerBelongsTo(container, state);
    }

    private boolean canAdministrateSession(Player player, MiningSessionState state) {
        return player != null && state != null
                && (Objects.equals(player.getUniqueId(), state.owner) || player.hasPermission("mineplugin.mineur.admin"));
    }

    private boolean requireManagementAccess(Player player, MiningSessionState state) {
        if (canAdministrateSession(player, state)) {
            return true;
        }
        player.sendMessage(CMD_PREFIX + ChatColor.RED
                + "Seul le propriétaire (ou un administrateur) peut modifier ce mineur.");
        return false;
    }

    private boolean isAuthorizedForSession(Player player, MiningSessionState state) {
        return player != null && state != null
                && (Objects.equals(player.getUniqueId(), state.owner)
                || state.trusted.contains(player.getUniqueId())
                || player.hasPermission("mineplugin.mineur.admin"));
    }

    private MiningSessionState findProtectedContainerSession(InventoryHolder holder) {
        if (holder == null) {
            return null;
        }
        if (holder instanceof BlockState blockState) {
            return findProtectedContainerSession(blockState.getBlock());
        }
        if (holder instanceof DoubleChest doubleChest) {
            MiningSessionState left = findProtectedContainerSession(doubleChest.getLeftSide());
            if (left != null) {
                return left;
            }
            return findProtectedContainerSession(doubleChest.getRightSide());
        }
        return null;
    }

    private MiningSessionState findProtectedContainerSession(Block block) {
        if (block == null || !(block.getState() instanceof Container container)) {
            return null;
        }

        /*
         * Les coordonnées persistées sont la source principale. Elles assurent
         * aussi la compatibilité avec les anciennes sauvegardes sans PDC.
         */
        for (MiningSessionState state : sessions) {
            if (state == null || state.worldUid == null
                    || !block.getWorld().getUID().equals(state.worldUid)) {
                continue;
            }
            for (Vector vector : state.containers) {
                if (vector != null
                        && vector.getBlockX() == block.getX()
                        && vector.getBlockY() == block.getY()
                        && vector.getBlockZ() == block.getZ()) {
                    return state;
                }
            }
        }

        String sessionId = container.getPersistentDataContainer()
                .get(containerSessionKey, PersistentDataType.STRING);
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return findSessionById(UUID.fromString(sessionId));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void clearZoneForState(MiningSessionState state) {
        RuntimeSession runtime = runtimeOf(state.id);
        if (runtime == null || state.id == null) {
            return;
        }

        String expectedSession = state.id.toString();
        Set<UUID> processed = new HashSet<>();
        for (Chunk chunk : runtime.ticketChunks) {
            for (Entity entity : chunk.getEntities()) {
                if (!processed.add(entity.getUniqueId())) {
                    continue;
                }
                String entitySession = entity.getPersistentDataContainer()
                        .get(entitySessionKey, PersistentDataType.STRING);
                if (expectedSession.equals(entitySession)) {
                    entity.remove();
                }
            }
        }
    }

    private void refreshChunkTickets(MiningSessionState state, RuntimeSession runtime) {
        World world = state.base != null ? state.base.getWorld() : null;
        if (world == null) {
            throw new IllegalStateException("Monde de la session non chargé.");
        }

        MiningCursor active = state.cursor;
        int minX = active != null ? active.minX : state.base.getBlockX();
        int minZ = active != null ? active.minZ : state.base.getBlockZ();
        int width = Math.max(1, active != null ? active.width : state.width);
        int length = Math.max(1, active != null ? active.length : state.length);
        Set<Long> desired = collectRequiredChunkKeys(
                state,
                minX,
                minZ,
                width,
                length,
                state.pattern == MiningPattern.TUNNEL
                        && isTunnelInfrastructureConfigured()
        );

        Iterator<Chunk> current = runtime.ticketChunks.iterator();
        while (current.hasNext()) {
            Chunk chunk = current.next();
            if (!chunk.getWorld().getUID().equals(world.getUID())
                    || !desired.contains(chunkKey(chunk.getX(), chunk.getZ()))) {
                SharedChunkTickets.release(plugin, chunk);
                current.remove();
            }
        }

        Set<Long> alreadyLoaded = new HashSet<>();
        for (Chunk chunk : runtime.ticketChunks) {
            alreadyLoaded.add(chunkKey(chunk.getX(), chunk.getZ()));
        }

        for (long key : desired) {
            if (alreadyLoaded.contains(key)) {
                continue;
            }
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            SharedChunkTickets.acquire(plugin, chunk);
            runtime.ticketChunks.add(chunk);
        }
    }

    private void addChunkRectangle(Set<Long> destination, int minX, int maxX, int minZ, int maxZ) {
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;
        long count = ((long) maxChunkX - minChunkX + 1L)
                * ((long) maxChunkZ - minChunkZ + 1L);
        if (count > getMaximumLoadedChunks()) {
            throw new IllegalStateException("Zone trop vaste pour les tickets de chunks.");
        }

        /*
         * Les compteurs sont des long : une borne Integer.MAX_VALUE ne peut
         * pas reboucler à Integer.MIN_VALUE et figer le thread serveur.
         */
        for (long chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (long chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                destination.add(chunkKey((int) chunkX, (int) chunkZ));
            }
        }
    }

    private long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private void ensureFrame(MiningSessionState state) {
        World world = state.base.getWorld();
        if (world == null) {
            return;
        }

        int baseX = state.base.getBlockX();
        int frameY = state.base.getBlockY() + 1;
        int baseZ = state.base.getBlockZ();
        if (frameY < world.getMinHeight() || frameY >= world.getMaxHeight()) {
            return;
        }

        int x1 = baseX - 1;
        int x2 = baseX + state.width;
        int z1 = baseZ - 1;
        int z2 = baseZ + state.length;
        List<Block> placed = new ArrayList<>();

        try {
            for (int x = x1; x <= x2; x++) {
                placeFrameBlock(world.getBlockAt(x, frameY, z1), placed);
                placeFrameBlock(world.getBlockAt(x, frameY, z2), placed);
            }
            for (int z = z1; z <= z2; z++) {
                placeFrameBlock(world.getBlockAt(x1, frameY, z), placed);
                placeFrameBlock(world.getBlockAt(x2, frameY, z), placed);
            }
        } catch (RuntimeException exception) {
            /*
             * Une erreur serveur au milieu du cadre ne doit pas laisser une
             * décoration partielle. Seuls les blocs réellement créés ici sont
             * remis à l'air.
             */
            for (Block block : placed) {
                if (block != null && block.getType() == Material.STONE_BRICKS) {
                    block.setType(Material.AIR, false);
                }
            }
            throw exception;
        }
    }

    private void placeFrameBlock(Block block, List<Block> placed) {
        /*
         * Même lorsque la décoration est activée, ne jamais écraser une
         * construction existante : le cadre est uniquement posé dans l'air.
         */
        if (block != null && block.getType().isAir()) {
            block.setType(Material.STONE_BRICKS, false);
            placed.add(block);
        }
    }

    private void ensureContainers(MiningSessionState state,
                                  RuntimeSession runtime,
                                  boolean freshlyCreated,
                                  MineCabinBuilder.Plan preparedCabinPlan) {
        runtime.containerLocations.clear();
        World world = state.base != null ? state.base.getWorld() : null;
        if (world == null) {
            throw new IllegalStateException("Monde de stockage non chargé.");
        }

        if (!freshlyCreated) {
            restorePersistedContainers(state, runtime, world);
            return;
        }

        state.containers.clear();
        if (!state.useBarrelMaster && isAutomaticMineStructureEnabled()) {
            MineCabinBuilder.Plan plan = preparedCabinPlan != null
                    ? preparedCabinPlan
                    : createCabinPlan(state);
            MineCabinBuilder.ValidationResult envelope = cabinBuilder.validateEnvelope(
                    world,
                    plan,
                    getMaximumLoadedChunks()
            );
            if (!envelope.valid()) {
                throw new IllegalStateException(envelope.message());
            }
            applyStructureMetadata(state, plan);

            Player constructionActor = state.owner != null
                    ? Bukkit.getPlayer(state.owner)
                    : null;
            MineCabinBuilder.BuildResult construction = cabinBuilder.build(
                    world,
                    plan,
                    constructionActor,
                    fireStructureProtectionEvents()
            );
            runtime.freshConstruction = construction;

            try {
                if (construction.chestLocations().size() != plan.chestBlockCount()
                        || construction.chestLocations().size() > getMaximumStorageContainers()) {
                    throw new IllegalStateException(
                            "Nombre de coffres de la cabane incohérent."
                    );
                }
                for (Location location : construction.chestLocations()) {
                    registerContainer(state, runtime, location.getBlock());
                }
            } catch (RuntimeException exception) {
                runtime.rollbackFreshConstruction();
                throw exception;
            }

            state.waitingStorage = runtime.containerLocations.isEmpty();
            if (state.waitingStorage) {
                runtime.rollbackFreshConstruction();
                throw new IllegalStateException(
                        "La salle de stockage n'a enregistré aucun coffre."
                );
            }
            return;
        }

        if (!state.useBarrelMaster) {
            List<Location> storage = computeStorageLocations(state);
            for (Location location : storage) {
                if (!isSafeAutomaticStorageLocation(location)) {
                    throw new IllegalStateException("Emplacement de coffre occupé en "
                            + location.getBlockX() + ", " + location.getBlockY() + ", "
                            + location.getBlockZ() + ".");
                }
            }

            List<Block> created = new ArrayList<>();
            try {
                for (Location location : storage) {
                    Block block = location.getBlock();
                    block.setType(Material.CHEST, false);
                    created.add(block);
                    registerContainer(state, runtime, block);
                }
            } catch (RuntimeException exception) {
                /*
                 * Une erreur au milieu de la création ne doit laisser ni coffre
                 * gratuit ni métadonnée orpheline dans le monde.
                 */
                for (Block block : created) {
                    /*
                     * Tous ces emplacements étaient AIR et sont modifiés sur le
                     * thread principal dans cette transaction. Même si
                     * registerContainer échoue avant d'écrire le PDC, le coffre
                     * créé ici doit donc être retiré.
                     */
                    if (block.getState() instanceof Container container
                            && containerBelongsTo(container, state)) {
                        removeContainerMetadata(block, state);
                    }
                    if (block.getType() == Material.CHEST) {
                        block.setType(Material.AIR, false);
                    }
                }
                state.containers.clear();
                runtime.containerLocations.clear();
                throw exception;
            }

            state.waitingStorage = runtime.containerLocations.isEmpty();
            return;
        }

        int radius = Math.max(1, Math.min(16,
                plugin.getConfig().getInt("mineur.storage.barrel-search-radius", 6)));
        int verticalRadius = Math.max(0, Math.min(8,
                plugin.getConfig().getInt("mineur.storage.barrel-search-height", 2)));
        int baseX = state.base.getBlockX();
        int baseY = state.base.getBlockY();
        int baseZ = state.base.getBlockZ();
        List<Block> candidates = new ArrayList<>();

        int minY = Math.max(effectiveWorldMinHeight(world), baseY - verticalRadius);
        int maxY = Math.min(effectiveWorldMaxHeight(world) - 1, baseY + verticalRadius);
        for (int x = baseX - radius; x <= baseX + radius; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = baseZ - radius; z <= baseZ + radius; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.BARREL
                            && canClaimContainer(block, state)) {
                        candidates.add(block);
                    }
                }
            }
        }

        /*
         * Le tonneau le plus proche est choisi en premier et le nombre total
         * est borné. L'ancien code pouvait revendiquer des milliers de tonneaux
         * et faire exploser le coût de chaque dépôt.
         */
        candidates.sort((first, second) -> {
            long firstDistance = squaredDistance(first, baseX, baseY, baseZ);
            long secondDistance = squaredDistance(second, baseX, baseY, baseZ);
            int distanceOrder = Long.compare(firstDistance, secondDistance);
            if (distanceOrder != 0) {
                return distanceOrder;
            }
            int xOrder = Integer.compare(first.getX(), second.getX());
            if (xOrder != 0) {
                return xOrder;
            }
            int yOrder = Integer.compare(first.getY(), second.getY());
            return yOrder != 0 ? yOrder : Integer.compare(first.getZ(), second.getZ());
        });

        int maximum = getMaximumStorageContainers();
        for (Block candidate : candidates) {
            if (runtime.containerLocations.size() >= maximum) {
                break;
            }
            registerContainer(state, runtime, candidate);
        }

        if (runtime.containerLocations.isEmpty()) {
            state.waitingStorage = true;
            throw new IllegalStateException(
                    "Aucun tonneau libre n'a été trouvé autour de la sélection.");
        }
        state.waitingStorage = false;
    }

    private long squaredDistance(Block block, int x, int y, int z) {
        long dx = (long) block.getX() - x;
        long dy = (long) block.getY() - y;
        long dz = (long) block.getZ() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private void restorePersistedContainers(MiningSessionState state,
                                            RuntimeSession runtime,
                                            World world) {
        List<Vector> persisted = new ArrayList<>(state.containers);
        state.containers.clear();
        Set<String> seen = new HashSet<>();

        /*
         * Une baisse ultérieure de max-containers ne doit pas abandonner des
         * moitiés d'un coffre double déjà construit et signé. Les nouvelles
         * cabanes respectent toujours la limite courante ; une cabane persistée
         * restaure, elle, ses cibles historiques bornées à 256 blocs.
         */
        int maximum = getMaximumStorageContainers();
        if (state.structureVersion >= MineCabinBuilder.FIRST_STRUCTURE_VERSION) {
            maximum = Math.max(maximum, Math.min(256, persisted.size()));
        }

        for (Vector vector : persisted) {
            if (state.containers.size() >= maximum) {
                break;
            }
            if (vector == null
                    || vector.getBlockY() < effectiveWorldMinHeight(world)
                    || vector.getBlockY() >= effectiveWorldMaxHeight(world)
                    || !isStorageCoordinateNearBase(state, vector)) {
                continue;
            }

            String coordinateKey = vector.getBlockX() + ":" + vector.getBlockY()
                    + ":" + vector.getBlockZ();
            if (!seen.add(coordinateKey)) {
                continue;
            }

            Block block = world.getBlockAt(
                    vector.getBlockX(),
                    vector.getBlockY(),
                    vector.getBlockZ()
            );
            if (!(block.getState() instanceof Container)
                    || !canRestorePersistedContainer(block, state)) {
                continue;
            }
            registerContainer(state, runtime, block, maximum);
        }

        if (runtime.containerLocations.isEmpty()) {
            state.waitingStorage = true;
            state.pause(MiningSessionState.PauseReason.SAFETY);
            plugin.getLogger().warning("[Mineur] Session " + state.id
                    + " chargée en pause : aucun stockage persistant valide.");
            notifyOwner(state.owner, ChatColor.RED
                    + "Aucun stockage valide n'a été retrouvé ; la session reste en pause.");
            return;
        }
        state.waitingStorage = false;
    }

    private void registerContainer(MiningSessionState state,
                                   RuntimeSession runtime,
                                   Block block) {
        registerContainer(
                state,
                runtime,
                block,
                getMaximumStorageContainers()
        );
    }

    private void registerContainer(MiningSessionState state,
                                   RuntimeSession runtime,
                                   Block block,
                                   int maximumContainers) {
        if (state == null || runtime == null || block == null
                || !(block.getState() instanceof Container)) {
            throw new IllegalArgumentException("Conteneur de mine invalide.");
        }

        int safeMaximum = Math.max(1, Math.min(256, maximumContainers));
        if (runtime.containerLocations.size() >= safeMaximum) {
            throw new IllegalStateException("Limite de conteneurs atteinte.");
        }

        Location location = block.getLocation();
        for (Location existing : runtime.containerLocations) {
            if (isSameBlock(existing, block)) {
                return;
            }
        }

        markContainerOwner(block, state);
        runtime.containerLocations.add(location);
        state.containers.add(location.toVector());
    }

    private List<Location> computeStorageLocations(MiningSessionState state) {
        List<Location> locations = new ArrayList<>();
        World world = state.base.getWorld();
        if (world == null) {
            return locations;
        }

        int baseX = state.base.getBlockX();
        int storageY = state.base.getBlockY() + 1;
        int baseZ = state.base.getBlockZ();

        int westX = baseX - 2;
        int eastX = baseX + state.width + 1;
        int northZ = baseZ - 2;
        int southZ = baseZ + state.length + 1;
        int midX = baseX + Math.max(state.width, 1) / 2;
        int midZ = baseZ + Math.max(state.length, 1) / 2;

        int[][] points = {
                {westX, northZ},
                {eastX, northZ},
                {westX, southZ},
                {eastX, southZ},
                {westX, midZ},
                {eastX, midZ},
                {midX, northZ},
                {midX, southZ}
        };

        Set<String> seen = new HashSet<>();
        for (int[] point : points) {
            String key = point[0] + ":" + point[1];
            if (seen.add(key)) {
                locations.add(new Location(world, point[0], storageY, point[1]));
            }
        }
        return locations;
    }

    private boolean canCreateAutomaticStorage(MiningSessionState state) {
        List<Location> locations = computeStorageLocations(state);
        if (locations.isEmpty()) {
            return false;
        }
        for (Location location : locations) {
            if (!isSafeAutomaticStorageLocation(location)) {
                return false;
            }
        }
        return true;
    }

    private boolean isSafeAutomaticStorageLocation(Location location) {
        World world = location != null ? location.getWorld() : null;
        if (world == null
                || location.getBlockY() < effectiveWorldMinHeight(world)
                || location.getBlockY() >= effectiveWorldMaxHeight(world)
                || !isInsideWorldBorder(location)) {
            return false;
        }
        Block target = location.getBlock();
        if (!target.getType().isAir()) {
            return false;
        }

        /*
         * Ne jamais créer une moitié de double coffre avec un coffre du joueur.
         * Le routeur revalide également ce point après coup, mais le refuser dès
         * la création évite une session immédiatement bloquée et toute fuite de
         * ressources vers un inventaire non signé.
         */
        for (BlockFace face : List.of(
                BlockFace.NORTH,
                BlockFace.SOUTH,
                BlockFace.EAST,
                BlockFace.WEST
        )) {
            Material adjacent = target.getRelative(face).getType();
            if (adjacent == Material.CHEST || adjacent == Material.TRAPPED_CHEST) {
                return false;
            }
        }
        return true;
    }

    private boolean isStorageCoordinateNearBase(MiningSessionState state, Vector vector) {
        if (state == null || state.base == null || vector == null) {
            return false;
        }

        /*
         * L'emprise du chevalement est déjà validée, bornée et proche de la
         * mine lors de la désérialisation. Elle constitue donc l'autorité la
         * plus précise pour les coffres de la salle, y compris si un
         * administrateur réduit ensuite storage.maximum-distance.
         */
        MiningSessionState.StructureBounds structure = state.structureBounds;
        if (structure != null
                && vector.getBlockY() >= structure.minY()
                && vector.getBlockY() <= structure.maxY()
                && structure.containsHorizontal(
                        vector.getBlockX(),
                        vector.getBlockZ()
                )) {
            return true;
        }

        int maximumDistance = Math.max(8, Math.min(64,
                plugin.getConfig().getInt("mineur.storage.maximum-distance", 24)));

        /*
         * La distance est mesurée depuis le rectangle complet de la mine, pas
         * uniquement depuis son coin minX/minZ. Sans cela, les coffres placés
         * automatiquement sur les côtés est/sud d'une grande sélection étaient
         * rejetés au redémarrage puis laissés avec un PDC orphelin.
         */
        long minX = state.base.getBlockX();
        long maxX = minX + Math.max(1L, state.width) - 1L;
        long minZ = state.base.getBlockZ();
        long maxZ = minZ + Math.max(1L, state.length) - 1L;
        long x = vector.getBlockX();
        long z = vector.getBlockZ();

        long dx = x < minX ? minX - x : (x > maxX ? x - maxX : 0L);
        long dz = z < minZ ? minZ - z : (z > maxZ ? z - maxZ : 0L);
        long dy = Math.abs((long) vector.getBlockY() - state.base.getBlockY());
        return dx <= maximumDistance && dz <= maximumDistance && dy <= 8L;
    }

    private void markContainerOwner(Block block, MiningSessionState state) {
        if (block == null || state == null || state.owner == null || state.id == null
                || !(block.getState() instanceof Container container)) {
            return;
        }

        PersistentDataContainer data = container.getPersistentDataContainer();
        data.set(containerOwnerKey, PersistentDataType.STRING, state.owner.toString());
        data.set(containerSessionKey, PersistentDataType.STRING, state.id.toString());
        container.update(true, false);
    }

    /**
     * Restaure uniquement un conteneur déjà signé par cette session.
     *
     * <p>Les anciennes versions écrivaient au minimum l'UUID du propriétaire.
     * Accepter un bloc totalement vierge à une ancienne coordonnée permettrait,
     * après un crash, de revendiquer le coffre qu'un joueur y a replacé.</p>
     */
    private boolean canRestorePersistedContainer(Block block, MiningSessionState state) {
        if (block == null || state == null || state.id == null
                || !(block.getState() instanceof Container container)) {
            return false;
        }

        PersistentDataContainer data = container.getPersistentDataContainer();
        String sessionId = data.get(containerSessionKey, PersistentDataType.STRING);
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId.equals(state.id.toString());
        }

        String ownerId = data.get(containerOwnerKey, PersistentDataType.STRING);
        return ownerId != null
                && !ownerId.isBlank()
                && state.owner != null
                && ownerId.equals(state.owner.toString());
    }

    private boolean canClaimContainer(Block block, MiningSessionState state) {
        if (block == null || state == null || !(block.getState() instanceof Container container)) {
            return false;
        }

        PersistentDataContainer data = container.getPersistentDataContainer();
        String sessionId = data.get(containerSessionKey, PersistentDataType.STRING);
        if (sessionId != null && !sessionId.isBlank()) {
            return state.id != null && sessionId.equals(state.id.toString());
        }

        MiningSessionState protectedState = findProtectedContainerSession(block);
        if (protectedState != null && !Objects.equals(protectedState.id, state.id)) {
            return false;
        }

        String ownerId = data.get(containerOwnerKey, PersistentDataType.STRING);
        return ownerId == null
                || ownerId.isBlank()
                || (state.owner != null && ownerId.equals(state.owner.toString()));
    }

    private boolean containerBelongsTo(Container container, MiningSessionState state) {
        if (container == null || state == null || state.id == null) {
            return false;
        }
        String sessionId = container.getPersistentDataContainer()
                .get(containerSessionKey, PersistentDataType.STRING);
        return state.id.toString().equals(sessionId);
    }

    private void removeContainerMetadata(Block block, MiningSessionState state) {
        if (block == null || state == null || state.id == null
                || !(block.getState() instanceof Container container)) {
            return;
        }

        PersistentDataContainer data = container.getPersistentDataContainer();
        String sessionId = data.get(containerSessionKey, PersistentDataType.STRING);
        if (!state.id.toString().equals(sessionId)) {
            return;
        }
        data.remove(containerOwnerKey);
        data.remove(containerSessionKey);
        container.update(true, false);
    }

    private void cleanupContainerMetadata(MiningSessionState state) {
        if (state == null || state.base == null || state.base.getWorld() == null) {
            return;
        }
        World world = state.base.getWorld();
        for (Vector vector : new ArrayList<>(state.containers)) {
            if (vector == null
                    || vector.getBlockY() < world.getMinHeight()
                    || vector.getBlockY() >= world.getMaxHeight()
                    || !isStorageCoordinateNearBase(state, vector)) {
                continue;
            }
            removeContainerMetadata(
                    world.getBlockAt(vector.getBlockX(), vector.getBlockY(), vector.getBlockZ()),
                    state
            );
        }
    }

    /**
     * Annule uniquement les coffres automatiques créés pendant une
     * initialisation qui a échoué. Les tonneaux du joueur ne sont jamais
     * supprimés et un bloc qui n'est plus marqué par la session est laissé tel
     * quel.
     */
    private void rollbackFreshAutomaticStorage(MiningSessionState state) {
        if (state == null
                || state.useBarrelMaster
                || state.id == null
                || state.base == null
                || state.base.getWorld() == null) {
            return;
        }

        World world = state.base.getWorld();
        for (Vector vector : new ArrayList<>(state.containers)) {
            if (vector == null || !isStorageCoordinateNearBase(state, vector)) {
                continue;
            }
            Block block = world.getBlockAt(
                    vector.getBlockX(),
                    vector.getBlockY(),
                    vector.getBlockZ()
            );
            if (block.getState() instanceof Container container
                    && containerBelongsTo(container, state)) {
                removeContainerMetadata(block, state);
                block.setType(Material.AIR, false);
            }
        }
        state.containers.clear();
    }

    /**
     * Crée le PNJ canonique d'une session après avoir supprimé tout ancien
     * exemplaire chargé portant le même identifiant persistant.
     */
    private Villager spawnSingleMiner(MiningSessionState state) {
        if (state == null || state.id == null) {
            throw new IllegalArgumentException("Session mineur invalide.");
        }
        removeLoadedMinersForSession(state.id, null);
        return spawnMiner(state);
    }

    /**
     * Remplace la référence runtime non canonique avant le respawn. Cela couvre
     * aussi une entité encore valide que quelqu'un aurait déplacée dans un autre
     * monde ou dont la donnée persistante aurait été altérée.
     */
    private Villager replaceRuntimeMiner(RuntimeSession runtime) {
        if (runtime == null || runtime.state == null) {
            throw new IllegalArgumentException("Runtime mineur invalide.");
        }
        Villager previous = runtime.miner;
        runtime.miner = null;
        if (previous != null && !removeEntitySafely(previous)) {
            throw new IllegalStateException(
                    "L'ancien PNJ n'a pas pu être supprimé sans risque de doublon."
            );
        }
        runtime.miner = spawnSingleMiner(runtime.state);
        return runtime.miner;
    }

    private Villager spawnMiner(MiningSessionState state) {
        World world = state.base != null ? state.base.getWorld() : Bukkit.getWorld(state.worldUid);
        if (world == null) {
            throw new IllegalStateException("Monde du mineur non chargé.");
        }

        Location spawn = findSafeMinerLocation(state, world);
        Villager villager = (Villager) world.spawnEntity(spawn, EntityType.VILLAGER);
        villager.setCustomName(ChatColor.GOLD + "Mineur");
        villager.setCustomNameVisible(true);
        villager.setProfession(Villager.Profession.ARMORER);
        villager.setAI(false);
        /*
         * Le puits est excavé sous le PNJ. Sans gravité désactivée, il chute
         * entre deux ticks et finit très loin du bloc qu'il anime.
         */
        villager.setGravity(false);
        villager.setInvulnerable(true);
        villager.setCollidable(false);
        villager.setRemoveWhenFarAway(false);
        villager.setPersistent(true);
        villager.getPersistentDataContainer().set(
                entitySessionKey,
                PersistentDataType.STRING,
                state.id.toString()
        );

        ItemStack pickaxe = createMiningTool();
        ItemStack helmet = new ItemStack(Material.LEATHER_HELMET);
        helmet.editMeta(meta -> {
            if (meta instanceof LeatherArmorMeta leatherMeta) {
                leatherMeta.setColor(org.bukkit.Color.ORANGE);
            }
        });

        EntityEquipment equipment = villager.getEquipment();
        if (equipment != null) {
            equipment.setItemInMainHand(pickaxe);
            equipment.setItemInMainHandDropChance(0.0F);
            equipment.setHelmet(helmet);
            equipment.setHelmetDropChance(0.0F);
        }

        state.minerY = spawn.getY();
        return villager;
    }

    private Location findSafeMinerLocation(MiningSessionState state, World world) {
        MiningCursor cursor = state.cursor;
        int x = cursor != null
                ? clampToSpan(cursor.x, cursor.minX, cursor.width)
                : clampToSpan(
                state.base.getBlockX() + Math.max(0, state.width / 2),
                state.base.getBlockX(),
                state.width
        );
        int z = cursor != null
                ? clampToSpan(cursor.z, cursor.minZ, cursor.length)
                : clampToSpan(
                state.base.getBlockZ() + Math.max(0, state.length / 2),
                state.base.getBlockZ(),
                state.length
        );

        long rawTargetY = cursor != null
                ? (long) cursor.y + 1L
                : Math.round(state.minerY);
        int targetY = (int) Math.max(
                world.getMinHeight(),
                Math.min((long) world.getMaxHeight() - 2L, rawTargetY)
        );

        int[][] horizontalOffsets = {
                {0, 0},
                {-1, 0}, {1, 0}, {0, -1}, {0, 1},
                {-1, -1}, {-1, 1}, {1, -1}, {1, 1},
                {-2, 0}, {2, 0}, {0, -2}, {0, 2},
                {-2, -1}, {-2, 1}, {2, -1}, {2, 1},
                {-1, -2}, {1, -2}, {-1, 2}, {1, 2},
                {-3, 0}, {3, 0}, {0, -3}, {0, 3}
        };

        for (int verticalOffset = -2; verticalOffset <= 8; verticalOffset++) {
            int feetY = targetY + verticalOffset;
            if (feetY < world.getMinHeight() || feetY >= world.getMaxHeight() - 1) {
                continue;
            }
            for (int[] offset : horizontalOffsets) {
                Location location = passableEntityLocation(
                        world,
                        x + offset[0],
                        feetY,
                        z + offset[1]
                );
                if (location != null) {
                    return location;
                }
            }
        }

        /*
         * Une ancienne sauvegarde peut viser une cavité rebouchée. Dans ce cas,
         * on cherche une surface sûre autour de la colonne avant de refuser
         * l'activation ; le PNJ ne sera jamais créé à l'intérieur de la roche.
         */
        for (int[] offset : horizontalOffsets) {
            int surfaceX = x + offset[0];
            int surfaceZ = z + offset[1];
            /*
             * getHighestBlockYAt peut charger un chunk de manière synchrone.
             * Une restauration ne doit jamais provoquer ce coût caché : seuls
             * les chunks déjà couverts par les tickets de la session sont lus.
             */
            if (!world.isChunkLoaded(surfaceX >> 4, surfaceZ >> 4)) {
                continue;
            }
            int surfaceY = Math.max(
                    world.getMinHeight(),
                    Math.min(
                            world.getMaxHeight() - 2,
                            world.getHighestBlockYAt(surfaceX, surfaceZ) + 1
                    )
            );
            Location location = passableEntityLocation(world, surfaceX, surfaceY, surfaceZ);
            if (location != null) {
                return location;
            }
        }

        throw new IllegalStateException(
                "Aucun emplacement libre de deux blocs n'a été trouvé pour le PNJ mineur."
        );
    }

    private Location passableEntityLocation(World world, int x, int feetY, int z) {
        if (world == null
                || feetY < world.getMinHeight()
                || feetY >= world.getMaxHeight() - 1
                || !world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }

        Location location = new Location(world, x + 0.5D, feetY, z + 0.5D);
        if (!isInsideWorldBorder(location)) {
            return null;
        }

        Block feet = world.getBlockAt(x, feetY, z);
        Block head = world.getBlockAt(x, feetY + 1, z);
        return isSafeEntitySpace(feet) && isSafeEntitySpace(head) ? location : null;
    }

    private boolean isSafeEntitySpace(Block block) {
        if (block == null || !block.isPassable() || block.isLiquid()) {
            return false;
        }
        return switch (block.getType()) {
            case FIRE, SOUL_FIRE, POWDER_SNOW,
                    CACTUS, SWEET_BERRY_BUSH, WITHER_ROSE,
                    NETHER_PORTAL, END_PORTAL, END_GATEWAY -> false;
            default -> true;
        };
    }

    private boolean isSafeGuardFloor(Block block) {
        if (block == null || !block.getType().isSolid()) {
            return false;
        }
        return switch (block.getType()) {
            case MAGMA_BLOCK, CAMPFIRE, SOUL_CAMPFIRE,
                    CACTUS, POWDER_SNOW -> false;
            default -> true;
        };
    }

    private int clampToSpan(int value, int start, int size) {
        long minimum = start;
        long maximum = Math.min(
                Integer.MAX_VALUE,
                minimum + Math.max(1L, size) - 1L
        );
        return (int) Math.max(minimum, Math.min(maximum, (long) value));
    }

    private void ensureGolems(RuntimeSession runtime) {
        runtime.golems.removeIf(golem -> golem == null
                || golem.getGolem() == null
                || golem.getGolem().isDead()
                || !golem.getGolem().isValid());

        /*
         * Les gardes ne sont plus créés au centre du puits : ils y tombaient
         * dès les premières couches puis restaient sous terre parce que leur
         * distance verticale était ignorée. Deux emplacements distincts et
         * réellement praticables sont maintenant recherchés autour du chantier.
         */
        for (int slot = 0; slot < 2 && runtime.golems.size() < 2; slot++) {
            Location spawn = findSafeGuardLocation(runtime, slot);
            if (spawn == null) {
                continue;
            }

            double radius = Math.max(
                    6.0D,
                    Math.min(
                            12.0D,
                            Math.max(runtime.state.width, runtime.state.length) / 2.0D + 3.0D
                    )
            );
            try {
                Golem golem = new Golem(plugin, spawn, radius, false);
                golem.getGolem().setCustomName(ChatColor.GOLD + "Golem de minage");
                golem.getGolem().setCustomNameVisible(true);
                golem.getGolem().setPersistent(true);
                golem.getGolem().getPersistentDataContainer().set(
                        entitySessionKey,
                        PersistentDataType.STRING,
                        runtime.state.id.toString()
                );
                runtime.golems.add(golem);
            } catch (RuntimeException exception) {
                /*
                 * Les gardes sont une protection auxiliaire : leur échec ne doit
                 * jamais bloquer l'extraction ni supprimer une session valide.
                 */
                plugin.getLogger().warning("[Mineur] Golem non créé pour la session "
                        + runtime.state.id + " : " + exception.getMessage());
            }
        }

        if (runtime.golems.size() < 2) {
            plugin.getLogger().fine("[Mineur] Session " + runtime.state.id
                    + " : moins de deux emplacements sûrs pour les golems.");
        }
    }

    private Location findSafeGuardLocation(RuntimeSession runtime, int preferredSlot) {
        MiningSessionState state = runtime.state;
        World world = state.base != null ? state.base.getWorld() : null;
        if (world == null) {
            return null;
        }

        int minX = state.base.getBlockX();
        int minZ = state.base.getBlockZ();
        int maxX = safeRectangleMaximum(minX, state.width);
        int maxZ = safeRectangleMaximum(minZ, state.length);
        long middleX = (long) minX + ((long) maxX - minX) / 2L;
        long middleZ = (long) minZ + ((long) maxZ - minZ) / 2L;

        long[][] firstAnchors = {
                {(long) minX - 5L, middleZ},
                {middleX, (long) minZ - 5L},
                {middleX, (long) maxZ + 5L},
                {(long) maxX + 5L, middleZ}
        };
        long[][] secondAnchors = {
                {(long) maxX + 5L, middleZ},
                {middleX, (long) maxZ + 5L},
                {middleX, (long) minZ - 5L},
                {(long) minX - 5L, middleZ}
        };
        long[][] anchors = preferredSlot == 0 ? firstAnchors : secondAnchors;
        int[][] offsets = {
                {0, 0},
                {-1, 0}, {1, 0}, {0, -1}, {0, 1},
                {-1, -1}, {-1, 1}, {1, -1}, {1, 1},
                {-2, 0}, {2, 0}, {0, -2}, {0, 2}
        };

        int maximumFeetY = world.getMaxHeight() - 3;
        int targetY = (int) Math.max(
                (long) world.getMinHeight() + 1L,
                Math.min((long) maximumFeetY, (long) state.base.getBlockY() + 1L)
        );

        for (long[] anchor : anchors) {
            for (int[] offset : offsets) {
                long candidateX = anchor[0] + offset[0];
                long candidateZ = anchor[1] + offset[1];
                if (candidateX < Integer.MIN_VALUE || candidateX > Integer.MAX_VALUE
                        || candidateZ < Integer.MIN_VALUE || candidateZ > Integer.MAX_VALUE) {
                    continue;
                }

                int x = (int) candidateX;
                int z = (int) candidateZ;
                if (!hasRuntimeChunkTicket(runtime, x >> 4, z >> 4)) {
                    continue;
                }

                for (int distance = 0; distance <= 6; distance++) {
                    int upward = targetY + distance;
                    Location location = groundedGuardLocation(world, x, upward, z);
                    if (isDistinctGuardLocation(runtime, location)) {
                        return location;
                    }
                    if (distance == 0) {
                        continue;
                    }
                    int downward = targetY - distance;
                    location = groundedGuardLocation(world, x, downward, z);
                    if (isDistinctGuardLocation(runtime, location)) {
                        return location;
                    }
                }

                if (world.isChunkLoaded(x >> 4, z >> 4)) {
                    int surfaceY = Math.max(
                            world.getMinHeight() + 1,
                            Math.min(maximumFeetY, world.getHighestBlockYAt(x, z) + 1)
                    );
                    Location surface = groundedGuardLocation(world, x, surfaceY, z);
                    if (isDistinctGuardLocation(runtime, surface)) {
                        return surface;
                    }
                }
            }
        }
        return null;
    }

    private Location groundedGuardLocation(World world, int x, int feetY, int z) {
        if (world == null
                || feetY <= world.getMinHeight()
                || feetY >= world.getMaxHeight() - 2
                || !world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }

        Location location = new Location(world, x + 0.5D, feetY, z + 0.5D);
        if (!isInsideWorldBorder(location)
                || !isSafeGuardFloor(world.getBlockAt(x, feetY - 1, z))) {
            return null;
        }

        for (int offsetY = 0; offsetY < 3; offsetY++) {
            if (!isSafeEntitySpace(world.getBlockAt(x, feetY + offsetY, z))) {
                return null;
            }
        }
        return location;
    }

    private boolean isDistinctGuardLocation(RuntimeSession runtime, Location candidate) {
        if (candidate == null) {
            return false;
        }
        for (Golem existing : runtime.golems) {
            if (existing == null || existing.getGolem() == null
                    || existing.getGolem().getWorld() != candidate.getWorld()) {
                continue;
            }
            if (existing.getGolem().getLocation().distanceSquared(candidate) < 9.0D) {
                return false;
            }
        }
        return true;
    }

    private boolean hasRuntimeChunkTicket(RuntimeSession runtime, int chunkX, int chunkZ) {
        long expected = chunkKey(chunkX, chunkZ);
        for (Chunk chunk : runtime.ticketChunks) {
            if (chunk != null
                    && chunk.getWorld().equals(runtime.state.base.getWorld())
                    && chunkKey(chunk.getX(), chunk.getZ()) == expected) {
                return true;
            }
        }
        return false;
    }

    private void restartLoop(RuntimeSession runtime) {
        if (runtime == null || runtime.state.paused) {
            return;
        }
        runtime.tunnelCompletionPending = false;
        if (runtime.loop != null) {
            runtime.loop.cancelAndRollback();
            runtime.loop = null;
        } else {
            runtime.state.rollbackPendingCursor();
        }

        if (!isHealthyMiner(runtime)) {
            replaceRuntimeMiner(runtime);
        }
        if (runtime.state.cursor == null) {
            runtime.state.cursor = new MiningCursor(
                    runtime.state.base,
                    runtime.state.width,
                    runtime.state.length
            );
        }

        refreshChunkTickets(runtime.state, runtime);
        runtime.router = createInventoryRouter(runtime);
        World world = runtime.state.base.getWorld();
        if (world == null) {
            throw new IllegalStateException("Monde du mineur non chargé.");
        }

        MiningIterator iterator = createIteratorFor(world, runtime.state, runtime.state.cursor);
        double progressPerTick = runtime.state.speed.progressPerTick(
                getOwnerSpeedMultiplier(runtime.state.owner)
        );
        RuntimeSession currentRuntime = runtime;
        long loopGeneration = ++runtime.loopGeneration;
        runtime.loop = new MiningLoop(
                plugin,
                runtime.state,
                iterator,
                runtime.router,
                runtime.miner,
                createMiningTool(),
                block -> (currentRuntime.decoration == null
                        || !currentRuntime.decoration.isManagedBlock(block))
                        && !isTunnelInfrastructureBlock(currentRuntime, block),
                block -> evaluateAutomatedMinerBreak(runtime.state, block),
                block -> {
                    if (currentRuntime.decoration != null) {
                        currentRuntime.decoration.afterBlock(block);
                    }
                },
                () -> onLoopCompletion(currentRuntime.state.id, loopGeneration),
                () -> onStorageBlocked(currentRuntime.state),
                () -> onStorageFreed(currentRuntime.state),
                block -> onMiningProtectionBlocked(
                        currentRuntime.state,
                        block,
                        loopGeneration
                ),
                exception -> onMiningFailure(
                        currentRuntime.state,
                        exception,
                        loopGeneration
                ),
                () -> requestMinerRecovery(
                        currentRuntime.state.id,
                        loopGeneration,
                        "la boucle a détecté la disparition du PNJ"
                ),
                plugin.getConfig().getBoolean("mineur.apply-physics", true),
                runtime.state.waitingStorage,
                progressPerTick,
                () -> ensureTunnelInfrastructureReady(
                        currentRuntime,
                        loopGeneration
                )
        );
        runtime.loop.runTaskTimer(plugin, 1L, 1L);
    }

    private void onLoopCompletion(UUID sessionId, long expectedGeneration) {
        RuntimeSession pendingRuntime = runtimeOf(sessionId);
        if (pendingRuntime != null
                && !pendingRuntime.state.paused
                && pendingRuntime.loopGeneration == expectedGeneration) {
            /*
             * Je publie l'état de transition avant de céder au scheduler. Le
             * watchdog ne peut ainsi prendre une boucle volontairement
             * terminée pour une panne pendant ce court intervalle.
             */
            pendingRuntime.tunnelCompletionPending = true;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            RuntimeSession runtime = runtimeOf(sessionId);
            if (runtime == null
                    || runtime.state.paused
                    || runtime.loopGeneration != expectedGeneration) {
                return;
            }

            /*
             * La tâche MiningLoop s'est déjà annulée. Libérer sa référence
             * avant toute transition empêche un ancien callback de manipuler
             * une boucle plus récente créée par une commande dans le même tick.
             */
            runtime.loop = null;
            runtime.tunnelCompletionPending = true;
            MiningSessionState state = runtime.state;

            try {
                /*
                 * L'itérateur peut terminer sur des couches entièrement vides,
                 * donc sans callback de casse. Une dernière synchronisation
                 * prolonge alors l'échelle jusqu'au stop-at-y exact.
                 */
                if (runtime.decoration != null) {
                    runtime.decoration.synchronizeToCursor();
                }

                if (runtime.miner == null
                        || runtime.miner.isDead()
                        || !runtime.miner.isValid()) {
                    runtime.removeActors();
                    activateRuntime(runtime);
                    return;
                }

                if (state.pattern == MiningPattern.TUNNEL) {
                    if (!ensureTunnelInfrastructureReady(runtime, expectedGeneration)) {
                        if (!state.paused) {
                            scheduleTunnelCompletionRetry(
                                    state.id,
                                    expectedGeneration,
                                    20L
                            );
                        }
                        return;
                    }

                    if (isTunnelInfrastructureEnabled(state)) {
                        TunnelWorkResult decorationResult =
                                decorateCompletedTunnelSection(runtime, expectedGeneration);
                        if (decorationResult != TunnelWorkResult.COMPLETE) {
                            if (!state.paused) {
                                scheduleTunnelCompletionRetry(
                                        state.id,
                                        expectedGeneration,
                                        decorationResult == TunnelWorkResult.IN_PROGRESS
                                                ? 1L
                                                : 20L
                                );
                            }
                            return;
                        }
                    }
                }

                boolean continued = false;
                if (state.pattern == MiningPattern.QUARRY && state.chainTunnelAfterQuarry) {
                    continued = initializeTunnelPhase(state);
                } else if (state.pattern == MiningPattern.TUNNEL && state.infiniteTunnel) {
                    continued = extendTunnelSection(state);
                }

                if (continued) {
                    /*
                     * Le puits est lié au balayage vertical de la carrière. Une
                     * fois le tunnel horizontal initialisé, conserver le
                     * delegate ferait interpréter ses blocs comme de nouvelles
                     * couches du puits et ajouterait un travail inutile.
                     */
                    if (!shouldUseQuarryDecoration(state)) {
                        runtime.decoration = null;
                    }
                    refreshChunkTickets(state, runtime);
                    restartLoop(runtime);
                    return;
                }

                notifyOwner(state.owner, ChatColor.GREEN + "Le mineur a terminé son chantier.");
                stopSession(state.id, true, null);
            } catch (RuntimeException exception) {
                /*
                 * Les transitions de tronçon chargent des chunks, déplacent le
                 * curseur et reconstruisent la boucle. Une erreur ne doit jamais
                 * rester non interceptée avec une session affichée « active »
                 * alors qu'aucune tâche ne tourne.
                 */
                state.pause(MiningSessionState.PauseReason.ERROR);
                runtime.suspend();
                saveAllSessions();
                notifyOwner(state.owner, ChatColor.RED
                        + "La transition vers la suite du chantier a échoué ; "
                        + "la session a été mise en pause.");
                plugin.getLogger().log(
                        Level.WARNING,
                        "[Mineur] Transition de fin impossible pour la session "
                                + state.id + ".",
                        exception
                );
            }
        });
    }

    /**
     * Initialise le tunnel infini une fois la carrière terminée.
     * On place un premier tronçon au fond de la carrière, collé sur un côté,
     * dans la direction choisie à la création de la session.
     */
    private boolean initializeTunnelPhase(MiningSessionState state) {
        World world = state.base != null ? state.base.getWorld() : null;
        if (world == null) {
            return false;
        }

        int tunnelSize = getConfiguredTunnelSectionSize();
        int tunnelHeight = getConfiguredTunnelHeight();
        int tunnelY = getEffectiveStopY(world);
        BlockFace direction = isCardinalDirection(state.tunnelDirection)
                ? state.tunnelDirection
                : BlockFace.SOUTH;

        boolean arrivalRoom = plugin.getConfig().getBoolean(
                "mineur.tunnel.arrival-room.enabled",
                true
        );
        int transverseWidth = arrivalRoom
                ? Math.max(tunnelSize, plugin.getConfig().getInt(
                "mineur.tunnel.arrival-room.width",
                15
        ))
                : tunnelSize;
        int forwardDepth = arrivalRoom
                ? Math.max(1, plugin.getConfig().getInt(
                "mineur.tunnel.arrival-room.depth",
                15
        ))
                : tunnelSize;
        int roomHeight = arrivalRoom
                ? Math.max(tunnelHeight, Math.min(
                getMaximumTunnelHeight(),
                plugin.getConfig().getInt("mineur.tunnel.arrival-room.height", 5)
        ))
                : tunnelHeight;

        int roomWidth = direction == BlockFace.NORTH || direction == BlockFace.SOUTH
                ? Math.min(getMaximumMineWidth(), transverseWidth)
                : Math.min(getMaximumMineWidth(), forwardDepth);
        int roomLength = direction == BlockFace.NORTH || direction == BlockFace.SOUTH
                ? Math.min(getMaximumMineLength(), forwardDepth)
                : Math.min(getMaximumMineLength(), transverseWidth);
        int configuredAisle = Math.max(1, plugin.getConfig().getInt(
                "mineur.tunnel.infrastructure.central-aisle-width",
                3
        ));
        int configuredPlatformDepth = Math.max(3, plugin.getConfig().getInt(
                "mineur.tunnel.infrastructure.arrival-platform-depth",
                4
        ));
        if (arrivalRoom && (!hasValidDimensions(roomWidth, roomLength)
                || transverseWidth < configuredAisle + 4
                || forwardDepth < configuredPlatformDepth
                || roomHeight < 3)) {
            arrivalRoom = false;
            roomWidth = tunnelSize;
            roomLength = tunnelSize;
            roomHeight = tunnelHeight;
            plugin.getLogger().warning(
                    "[Mineur] Salle d'arrivée désactivée pour la session " + state.id
                            + " : ses dimensions dépassent les limites configurées."
            );
        }

        state.tunnelDirection = direction;
        Location tunnelBase = computeAdjacentTunnelBase(
                state,
                tunnelY,
                roomWidth,
                roomLength,
                direction
        );
        if (!isTunnelSectionAllowed(
                state,
                tunnelBase,
                roomWidth,
                roomLength,
                roomHeight
        )) {
            notifyOwner(state.owner, ChatColor.RED
                    + "Le tunnel ne peut pas démarrer : budget de sécurité, bordure, "
                    + "chevauchement ou distance maximale.");
            return false;
        }

        state.pattern = MiningPattern.TUNNEL;
        state.chainTunnelAfterQuarry = false;
        state.infiniteTunnel = true;
        state.tunnelSectionSize = tunnelSize;
        state.tunnelHeight = tunnelHeight;
        state.tunnelSectionsMined = 0;
        state.tunnelArrivalRoom = arrivalRoom;
        clearTunnelInfrastructureCheckpoint(state);
        state.maxTunnelSections = Math.max(0,
                plugin.getConfig().getInt("mineur.tunnel.max-sections", 0));
        prepareTunnelCursor(state, tunnelBase, roomWidth, roomLength, roomHeight);
        recordTunnelOriginBounds(state);
        state.minerY = tunnelY + 1.0D;
        saveAllSessions();
        return true;
    }

    /**
     * Prolonge le tunnel infini en ajoutant une nouvelle section dans la même
     * direction que la précédente.
     *
     * @return true si une nouvelle section a été créée, false sinon.
     */
    private boolean extendTunnelSection(MiningSessionState state) {
        if (state.cursor == null || !isCardinalDirection(state.tunnelDirection)) {
            return false;
        }

        World world = state.base != null ? state.base.getWorld() : null;
        if (world == null) {
            return false;
        }

        int completedSections = state.tunnelSectionsMined + 1;
        if (state.maxTunnelSections > 0 && completedSections >= state.maxTunnelSections) {
            state.tunnelSectionsMined = completedSections;
            state.infiniteTunnel = false;
            saveAllSessions();
            return false;
        }

        int section = state.tunnelSectionSize > 0
                ? Math.min(state.tunnelSectionSize, getMaximumMineWidth())
                : getConfiguredTunnelSectionSize();
        int height = state.tunnelHeight > 0
                ? state.tunnelHeight
                : getConfiguredTunnelHeight();

        long nextX = state.cursor.minX;
        long nextZ = state.cursor.minZ;
        switch (state.tunnelDirection) {
            case NORTH -> {
                nextX += ((long) state.cursor.width - section) / 2L;
                nextZ -= section;
            }
            case SOUTH -> {
                nextX += ((long) state.cursor.width - section) / 2L;
                nextZ += state.cursor.length;
            }
            case WEST -> {
                nextX -= section;
                nextZ += ((long) state.cursor.length - section) / 2L;
            }
            case EAST -> {
                nextX += state.cursor.width;
                nextZ += ((long) state.cursor.length - section) / 2L;
            }
            default -> {
                return false;
            }
        }
        if (nextX < Integer.MIN_VALUE || nextX > Integer.MAX_VALUE
                || nextZ < Integer.MIN_VALUE || nextZ > Integer.MAX_VALUE) {
            return false;
        }

        Location newBase = new Location(
                world,
                (int) nextX,
                clampY(world, state.cursor.minY),
                (int) nextZ
        );
        if (!isTunnelSectionAllowed(state, newBase, section, height)) {
            state.infiniteTunnel = false;
            saveAllSessions();
            notifyOwner(state.owner, ChatColor.YELLOW
                    + "Tunnel arrêté avant une limite de sécurité, la bordure, "
                    + "une autre mine ou la distance maximale.");
            return false;
        }

        state.tunnelSectionsMined = completedSections;
        prepareTunnelCursor(state, newBase, section, section, height);
        state.tunnelArrivalRoom = false;
        saveAllSessions();
        return true;
    }

    private void stopSession(UUID sessionId, boolean removeState, Player issuer) {
        MiningSessionState state = findSessionById(sessionId);
        RuntimeSession runtime = runtimes.remove(sessionId);

        if (removeState && state != null) {
            cleanupContainerMetadata(state);
        }
        if (runtime != null) {
            runtime.stop(false);
        }

        if (state != null) {
            if (removeState) {
                sessions.remove(state);
            }
            unregisterOwnerSession(state);
            selectedSessions.entrySet().removeIf(
                    entry -> Objects.equals(entry.getValue(), state.id)
            );
        }

        saveAllSessions();
        if (issuer != null) {
            issuer.sendMessage(CMD_PREFIX + ChatColor.YELLOW
                    + "Session arrêtée. Le stockage et les constructions restent en place.");
        }
    }

    private boolean isWorldAllowed(World world) {
        if (world == null) {
            return false;
        }
        List<String> allowed = plugin.getConfig().getStringList("mineur.allowed-worlds");
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        for (String name : allowed) {
            if (name != null && name.equalsIgnoreCase(world.getName())) {
                return true;
            }
        }
        return false;
    }

    private int getStopY() {
        return plugin.getConfig().getInt("mineur.stop-at-y", -58);
    }

    private int getEffectiveStopY(World world) {
        int minimum = effectiveWorldMinHeight(world);
        int maximum = effectiveWorldMaxHeight(world) - 1;
        return Math.max(minimum, Math.min(maximum, getStopY()));
    }

    private int clampY(World world, int y) {
        return Math.max(
                effectiveWorldMinHeight(world),
                Math.min(effectiveWorldMaxHeight(world) - 1, y)
        );
    }

    private int effectiveWorldMinHeight(World world) {
        if (world == null) {
            return -64;
        }
        int minimum = world.getMinHeight();
        int maximum = world.getMaxHeight();
        return maximum > minimum ? minimum : -64;
    }

    private int effectiveWorldMaxHeight(World world) {
        if (world == null) {
            return 320;
        }
        int minimum = world.getMinHeight();
        int maximum = world.getMaxHeight();
        return maximum > minimum ? maximum : 320;
    }

    private void normalizeLoadedState(MiningSessionState state) {
        if (state == null) {
            throw new IllegalArgumentException("Session mineur absente.");
        }
        if (state.id == null) {
            state.id = UUID.randomUUID();
        }

        World world = state.base != null ? state.base.getWorld() : null;
        if (world == null && state.worldUid != null) {
            world = Bukkit.getWorld(state.worldUid);
        }
        if (world == null || state.base == null) {
            throw new IllegalStateException("Monde ou base de session non chargé.");
        }
        if (!isWorldAllowed(world)) {
            throw new IllegalStateException(
                    "Le monde " + world.getName() + " n'est plus autorisé."
            );
        }

        state.worldUid = world.getUID();
        state.base = new Location(
                world,
                state.base.getBlockX(),
                clampY(world, state.base.getBlockY()),
                state.base.getBlockZ()
        );
        state.pattern = state.pattern != null
                ? state.pattern
                : MiningPattern.QUARRY;
        state.speed = state.speed != null
                ? state.speed
                : MiningSpeed.NORMAL;
        state.width = Math.max(1, state.width);
        state.length = Math.max(1, state.length);

        if (!hasValidDimensions(state.width, state.length)) {
            throw new IllegalStateException("Dimensions de carrière hors limites.");
        }

        int baseMaxX = safeRectangleMaximum(
                state.base.getBlockX(),
                state.width
        );
        int baseMaxZ = safeRectangleMaximum(
                state.base.getBlockZ(),
                state.length
        );
        if (!isRectangleInsideWorldBorder(
                world,
                state.base.getBlockX(),
                baseMaxX,
                state.base.getBlockZ(),
                baseMaxZ,
                state.base.getBlockY()
        )) {
            throw new IllegalStateException(
                    "La carrière persistée dépasse la bordure du monde."
            );
        }

        MiningSessionState.StructureBounds persistedStructure = state.structureBounds;
        if (persistedStructure != null) {
            if (persistedStructure.minY() < effectiveWorldMinHeight(world)
                    || persistedStructure.maxY() >= effectiveWorldMaxHeight(world)
                    || !isRectangleInsideWorldBorder(
                    world,
                    persistedStructure.minX(),
                    persistedStructure.maxX(),
                    persistedStructure.minZ(),
                    persistedStructure.maxZ(),
                    state.base.getBlockY()
            )) {
                throw new IllegalStateException(
                        "Le chevalement persisté dépasse les limites du monde."
                );
            }
            /*
             * La présence de bornes prouve au minimum une structure v1. Ne pas
             * promouvoir silencieusement une ancienne cabane vers la version
             * courante : le puits v2 n'existe physiquement que s'il a été
             * construit avec sa trappe et son garde-corps.
             */
            state.structureVersion = Math.max(
                    MineCabinBuilder.FIRST_STRUCTURE_VERSION,
                    state.structureVersion
            );
        } else {
            state.structureVersion = 0;
        }

        state.tunnelSectionSize = Math.max(1, Math.min(
                Math.min(getMaximumMineWidth(), getMaximumMineLength()),
                state.tunnelSectionSize
        ));
        state.tunnelHeight = Math.max(1, Math.min(
                getMaximumTunnelHeight(),
                state.tunnelHeight
        ));
        state.tunnelDirection = isCardinalDirection(state.tunnelDirection)
                ? state.tunnelDirection
                : BlockFace.SOUTH;
        state.tunnelSectionsMined = Math.max(0, state.tunnelSectionsMined);
        state.maxTunnelSections = Math.max(0, state.maxTunnelSections);

        /*
         * Un pendingCursor représente le bloc sélectionné mais pas encore
         * cassé au dernier enregistrement. Il doit redevenir le curseur actif.
         */
        state.rollbackPendingCursor();
        if (state.cursor == null) {
            state.cursor = new MiningCursor(
                    state.base,
                    state.width,
                    state.length
            );
        }

        MiningCursor cursor = state.cursor;
        if (state.pattern != MiningPattern.TUNNEL) {
            /*
             * Les parcours verticaux n'ont jamais le droit de déplacer leurs
             * bornes horizontales hors de la sélection d'origine.
             */
            cursor.minX = state.base.getBlockX();
            cursor.minZ = state.base.getBlockZ();
            cursor.width = state.width;
            cursor.length = state.length;
            cursor.minY = state.base.getBlockY();
            cursor.height = 1;

            cursor.x = Math.max(
                    cursor.minX,
                    Math.min(cursor.x, baseMaxX)
            );
            cursor.z = Math.max(
                    cursor.minZ,
                    Math.min(cursor.z, baseMaxZ)
            );
            cursor.y = Math.min(
                    state.base.getBlockY(),
                    clampY(world, cursor.y)
            );
            if (cursor.y < getEffectiveStopY(world)) {
                cursor.exhausted = true;
            }
        } else {
            cursor.width = Math.max(1, cursor.width);
            cursor.length = Math.max(1, cursor.length);
            if (!hasValidDimensions(cursor.width, cursor.length)) {
                throw new IllegalStateException(
                        "Dimensions du tronçon de tunnel hors limites."
                );
            }
            if (cursor.width != cursor.length && !state.tunnelArrivalRoom) {
                throw new IllegalStateException(
                        "Le tronçon de tunnel persistant n'est pas carré."
                );
            }

            if (cursor.minY == 0 && cursor.y != 0) {
                cursor.minY = cursor.y;
            }
            cursor.minY = clampY(world, cursor.minY);
            int availableHeight = Math.max(
                    1,
                    effectiveWorldMaxHeight(world) - cursor.minY
            );
            cursor.height = Math.max(1, Math.min(
                    Math.min(getMaximumTunnelHeight(), availableHeight),
                    cursor.height
            ));

            int cursorMaxX = safeRectangleMaximum(
                    cursor.minX,
                    cursor.width
            );
            int cursorMaxZ = safeRectangleMaximum(
                    cursor.minZ,
                    cursor.length
            );
            long legacyExclusiveZ = (long) cursor.minZ + cursor.length;
            if (!cursor.exhausted
                    && legacyExclusiveZ <= Integer.MAX_VALUE
                    && cursor.z == (int) legacyExclusiveZ) {
                /*
                 * Migration de la sentinelle utilisée avant le schéma v4.
                 */
                cursor.exhausted = true;
            }
            int cursorMaxY = cursor.minY + cursor.height - 1;
            if (!isRectangleInsideWorldBorder(
                    world,
                    cursor.minX,
                    cursorMaxX,
                    cursor.minZ,
                    cursorMaxZ,
                    cursor.minY
            ) || !isTunnelRectangleWithinDistance(
                    state,
                    cursor.minX,
                    cursorMaxX,
                    cursor.minZ,
                    cursorMaxZ
            )) {
                throw new IllegalStateException(
                        "Le tronçon de tunnel persistant dépasse ses limites."
                );
            }

            cursor.x = Math.max(
                    cursor.minX,
                    Math.min(cursor.x, cursorMaxX)
            );
            cursor.z = Math.max(
                    cursor.minZ,
                    Math.min(cursor.z, cursorMaxZ)
            );
            cursor.y = Math.max(
                    cursor.minY,
                    Math.min(cursor.y, cursorMaxY)
            );
            if (!state.tunnelArrivalRoom) {
                state.tunnelSectionSize = cursor.width;
                state.tunnelHeight = cursor.height;
            }
        }

        sanitizeStoredContainers(state, world);

        if (!Double.isFinite(state.minerY)) {
            state.minerY = state.base.getBlockY() + 1.0D;
        }
        state.minerY = Math.max(
                effectiveWorldMinHeight(world),
                Math.min(
                        effectiveWorldMaxHeight(world) - 1.0D,
                        state.minerY
                )
        );
        state.trusted.remove(null);
    }

    private void sanitizeStoredContainers(MiningSessionState state,
                                          World world) {
        List<Vector> valid = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int maximum = getMaximumStorageContainers();

        for (Vector vector : state.containers) {
            if (vector == null
                    || valid.size() >= maximum
                    || vector.getBlockY() < effectiveWorldMinHeight(world)
                    || vector.getBlockY() >= effectiveWorldMaxHeight(world)
                    || !isStorageCoordinateNearBase(state, vector)) {
                continue;
            }

            Location location = new Location(
                    world,
                    vector.getBlockX(),
                    vector.getBlockY(),
                    vector.getBlockZ()
            );
            if (!isInsideWorldBorder(location)) {
                continue;
            }

            String key = vector.getBlockX() + ":"
                    + vector.getBlockY() + ":"
                    + vector.getBlockZ();
            if (seen.add(key)) {
                valid.add(new Vector(
                        vector.getBlockX(),
                        vector.getBlockY(),
                        vector.getBlockZ()
                ));
            }
        }

        state.containers.clear();
        state.containers.addAll(valid);
    }

    private boolean isRectangleInsideWorldBorder(World world,
                                                 int minX,
                                                 int maxX,
                                                 int minZ,
                                                 int maxZ,
                                                 int y) {
        if (world == null || minX > maxX || minZ > maxZ) {
            return false;
        }
        return isInsideWorldBorder(new Location(world, minX, y, minZ))
                && isInsideWorldBorder(new Location(world, minX, y, maxZ))
                && isInsideWorldBorder(new Location(world, maxX, y, minZ))
                && isInsideWorldBorder(new Location(world, maxX, y, maxZ));
    }

    private boolean isInsideWorldBorder(Location location) {
        return location != null
                && location.getWorld() != null
                && (location.getWorld().getWorldBorder() == null
                || location.getWorld().getWorldBorder().isInside(location));
    }

    private boolean isTunnelRectangleWithinDistance(MiningSessionState state,
                                                    int minX,
                                                    int maxX,
                                                    int minZ,
                                                    int maxZ) {
        int maximumDistance = Math.max(16, Math.min(
                100_000,
                plugin.getConfig().getInt(
                        "mineur.tunnel.max-distance-from-base",
                        2048
                )
        ));
        long dx = Math.max(
                Math.abs((long) minX - state.base.getBlockX()),
                Math.abs((long) maxX - state.base.getBlockX())
        );
        long dz = Math.max(
                Math.abs((long) minZ - state.base.getBlockZ()),
                Math.abs((long) maxZ - state.base.getBlockZ())
        );
        return Math.max(dx, dz) <= maximumDistance;
    }

    private int getMaximumMineWidth() {
        return Math.max(1, Math.min(512,
                plugin.getConfig().getInt("mineur.limits.max-width", 64)));
    }

    private int getMaximumMineLength() {
        return Math.max(1, Math.min(512,
                plugin.getConfig().getInt("mineur.limits.max-length", 64)));
    }

    private int getMaximumMineArea() {
        return Math.max(1, Math.min(262_144,
                plugin.getConfig().getInt("mineur.limits.max-area", 4096)));
    }

    private int getMaximumLoadedChunks() {
        return Math.max(1, Math.min(1024,
                plugin.getConfig().getInt("mineur.limits.max-loaded-chunks", 64)));
    }

    private int getMaximumTrustedPlayers() {
        return Math.max(1, Math.min(256,
                plugin.getConfig().getInt("mineur.limits.max-trusted-players", 64)));
    }

    private int getMaximumStorageContainers() {
        /*
         * La limite compte les blocs de coffre, donc deux entrées par coffre
         * double. La borne basse garantit au moins quatre coffres doubles dans
         * une configuration personnalisée très restrictive.
         */
        return Math.max(8, Math.min(256,
                plugin.getConfig().getInt("mineur.storage.max-containers", 32)));
    }

    private boolean isAutomaticMineStructureEnabled() {
        return plugin.getConfig().getBoolean("mineur.structure.enabled", true);
    }

    private boolean fireStructureProtectionEvents() {
        return plugin.getConfig().getBoolean(
                "mineur.structure.fire-protection-events",
                true
        );
    }

    private MineCabinBuilder.Settings getCabinSettings() {
        int maximumContainerPairs = Math.max(4, getMaximumStorageContainers() / 2);
        int requestedPairs = Math.max(4, Math.min(
                maximumContainerPairs,
                plugin.getConfig().getInt("mineur.structure.double-chests", 16)
        ));

        return new MineCabinBuilder.Settings(
                plugin.getConfig().getInt("mineur.structure.platform-height", 5),
                plugin.getConfig().getInt("mineur.structure.cabin-min-size", 15),
                plugin.getConfig().getInt("mineur.structure.cabin-max-size", 17),
                plugin.getConfig().getInt("mineur.structure.wall-height", 5),
                requestedPairs,
                plugin.getConfig().getInt("mineur.structure.foundation-depth", 6),
                plugin.getConfig().getInt("mineur.structure.max-planned-blocks", 12_000)
        );
    }

    private MineCabinBuilder.Plan createCabinPlan(MiningSessionState state) {
        if (state == null || state.base == null) {
            throw new IllegalArgumentException("Base de mine absente.");
        }
        return MineCabinBuilder.createPlan(
                state.base.getBlockX(),
                state.base.getBlockY(),
                state.base.getBlockZ(),
                Math.max(1, state.width),
                Math.max(1, state.length),
                getCabinSettings()
        );
    }

    private void applyStructureMetadata(MiningSessionState state,
                                        MineCabinBuilder.Plan plan) {
        if (state == null || plan == null) {
            throw new IllegalArgumentException("État ou plan de chevalement absent.");
        }
        MineCabinBuilder.Bounds bounds = plan.bounds();
        state.structureVersion = MineCabinBuilder.STRUCTURE_VERSION;
        state.structureBounds = new MiningSessionState.StructureBounds(
                bounds.minX(),
                bounds.maxX(),
                bounds.minY(),
                bounds.maxY(),
                bounds.minZ(),
                bounds.maxZ()
        );
    }

    private int getMaximumTunnelHeight() {
        return Math.max(1, Math.min(32,
                plugin.getConfig().getInt("mineur.tunnel.max-height", 8)));
    }

    private boolean hasValidDimensions(int width, int length) {
        if (width < 1 || length < 1
                || width > getMaximumMineWidth()
                || length > getMaximumMineLength()) {
            return false;
        }
        return (long) width * length <= getMaximumMineArea();
    }

    private MiningSessionState findOverlappingSession(World world,
                                                      int minX,
                                                      int maxX,
                                                      int minZ,
                                                      int maxZ,
                                                      int margin) {
        return findOverlappingSession(world, minX, maxX, minZ, maxZ, margin, null);
    }

    private MiningSessionState findOverlappingSession(World world,
                                                      int minX,
                                                      int maxX,
                                                      int minZ,
                                                      int maxZ,
                                                      int margin,
                                                      UUID excludedSession) {
        if (world == null) {
            return null;
        }

        for (MiningSessionState existing : sessions) {
            if (existing == null
                    || Objects.equals(existing.id, excludedSession)
                    || existing.worldUid == null
                    || !existing.worldUid.equals(world.getUID())
                    || existing.base == null) {
                continue;
            }

            MiningSessionState.StructureBounds existingStructure = existing.structureBounds;
            if (existingStructure != null && rectanglesOverlap(
                    minX, maxX, minZ, maxZ,
                    existingStructure.minX(),
                    existingStructure.maxX(),
                    existingStructure.minZ(),
                    existingStructure.maxZ(),
                    margin
            )) {
                return existing;
            }

            MiningSessionState.StructureBounds tunnelOrigin = existing.tunnelOriginBounds;
            if (existing.pattern == MiningPattern.TUNNEL
                    && tunnelOrigin != null
                    && rectanglesOverlap(
                    minX, maxX, minZ, maxZ,
                    tunnelOrigin.minX(),
                    tunnelOrigin.maxX(),
                    tunnelOrigin.minZ(),
                    tunnelOrigin.maxZ(),
                    margin
            )) {
                return existing;
            }
            MiningSessionState.StructureBounds tunnelHistory =
                    regularTunnelHistoryBounds(existing);
            if (tunnelHistory != null && rectanglesOverlap(
                    minX, maxX, minZ, maxZ,
                    tunnelHistory.minX(),
                    tunnelHistory.maxX(),
                    tunnelHistory.minZ(),
                    tunnelHistory.maxZ(),
                    margin
            )) {
                return existing;
            }

            if (rectanglesOverlap(
                    minX, maxX, minZ, maxZ,
                    existing.base.getBlockX(),
                    safeRectangleMaximum(existing.base.getBlockX(), existing.width),
                    existing.base.getBlockZ(),
                    safeRectangleMaximum(existing.base.getBlockZ(), existing.length),
                    margin
            )) {
                return existing;
            }

            for (Vector container : existing.containers) {
                if (container != null && rectanglesOverlap(
                        minX, maxX, minZ, maxZ,
                        container.getBlockX(),
                        container.getBlockX(),
                        container.getBlockZ(),
                        container.getBlockZ(),
                        margin
                )) {
                    return existing;
                }
            }

            MiningCursor active = existing.cursor;
            if (active != null && rectanglesOverlap(
                    minX, maxX, minZ, maxZ,
                    active.minX,
                    safeRectangleMaximum(active.minX, active.width),
                    active.minZ,
                    safeRectangleMaximum(active.minZ, active.length),
                    margin
            )) {
                return existing;
            }
        }
        return null;
    }

    private int safeRectangleMaximum(int minimum, int size) {
        long maximum = (long) minimum + Math.max(1, size) - 1L;
        if (maximum < Integer.MIN_VALUE || maximum > Integer.MAX_VALUE) {
            throw new IllegalStateException("Rectangle de session hors limites.");
        }
        return (int) maximum;
    }

    private boolean rectanglesOverlap(int firstMinX,
                                      int firstMaxX,
                                      int firstMinZ,
                                      int firstMaxZ,
                                      int secondMinX,
                                      int secondMaxX,
                                      int secondMinZ,
                                      int secondMaxZ,
                                      int margin) {
        long safeMargin = Math.max(0, margin);
        return (long) firstMinX <= (long) secondMaxX + safeMargin
                && (long) firstMaxX >= (long) secondMinX - safeMargin
                && (long) firstMinZ <= (long) secondMaxZ + safeMargin
                && (long) firstMaxZ >= (long) secondMinZ - safeMargin;
    }

    private void resetVerticalCursorAtDepth(MiningSessionState state, int requestedY) {
        World world = state.base.getWorld();
        MiningCursor cursor = new MiningCursor(state.base, state.width, state.length);
        int y = Math.max(
                getEffectiveStopY(world),
                Math.min(state.base.getBlockY(), clampY(world, requestedY))
        );
        cursor.y = y;
        cursor.minY = state.base.getBlockY();
        cursor.height = 1;
        state.cursor = cursor;
        state.pendingCursor = null;
    }

    private Location computeAdjacentTunnelBase(MiningSessionState state,
                                               int y,
                                               int sectionSize) {
        return computeAdjacentTunnelBase(
                state,
                y,
                sectionSize,
                sectionSize,
                state.tunnelDirection
        );
    }

    private Set<Long> collectRequiredChunkKeys(MiningSessionState state,
                                               int minX,
                                               int minZ,
                                               int width,
                                               int length,
                                               boolean includeTunnelHalo) {
        if (state == null || state.base == null || width < 1 || length < 1) {
            throw new IllegalStateException("Emprise de chunks invalide.");
        }
        long maxX = (long) minX + width - 1L;
        long maxZ = (long) minZ + length - 1L;
        if (maxX > Integer.MAX_VALUE || maxX < Integer.MIN_VALUE
                || maxZ > Integer.MAX_VALUE || maxZ < Integer.MIN_VALUE) {
            throw new IllegalStateException("Coordonnées de session hors limites.");
        }

        Set<Long> desired = new HashSet<>();
        if (includeTunnelHalo) {
            /*
             * Je garde un halo d'un bloc autour du tronçon : le plan de
             * drainage lit uniquement cette enveloppe immédiate et ne charge
             * ainsi jamais un chunk extérieur de façon implicite.
             */
            int haloMinX = checkedCoordinate((long) minX - 1L, "X minimum du halo");
            int haloMaxX = checkedCoordinate(maxX + 1L, "X maximum du halo");
            int haloMinZ = checkedCoordinate((long) minZ - 1L, "Z minimum du halo");
            int haloMaxZ = checkedCoordinate(maxZ + 1L, "Z maximum du halo");
            addChunkRectangle(desired, haloMinX, haloMaxX, haloMinZ, haloMaxZ);
        } else {
            addChunkRectangle(desired, minX, (int) maxX, minZ, (int) maxZ);
        }

        MiningSessionState.StructureBounds structureBounds = state.structureBounds;
        if (structureBounds != null) {
            addChunkRectangle(
                    desired,
                    structureBounds.minX(),
                    structureBounds.maxX(),
                    structureBounds.minZ(),
                    structureBounds.maxZ()
            );
        }
        desired.add(chunkKey(state.base.getBlockX() >> 4, state.base.getBlockZ() >> 4));
        for (Vector vector : state.containers) {
            if (vector != null) {
                desired.add(chunkKey(vector.getBlockX() >> 4, vector.getBlockZ() >> 4));
            }
        }

        int maximum = getMaximumLoadedChunks();
        if (desired.size() > maximum) {
            throw new IllegalStateException("La session demanderait " + desired.size()
                    + " chunks chargés (limite : " + maximum + ").");
        }
        return desired;
    }

    private Location computeAdjacentTunnelBase(MiningSessionState state,
                                               int y,
                                               int sectionSize,
                                               BlockFace direction) {
        return computeAdjacentTunnelBase(
                state,
                y,
                sectionSize,
                sectionSize,
                direction
        );
    }

    private Location computeAdjacentTunnelBase(MiningSessionState state,
                                               int y,
                                               int sectionWidth,
                                               int sectionLength,
                                               BlockFace direction) {
        MiningCursor active = state.cursor;
        int minX = active != null ? active.minX : state.base.getBlockX();
        int minZ = active != null ? active.minZ : state.base.getBlockZ();
        int width = Math.max(1, active != null ? active.width : state.width);
        int length = Math.max(1, active != null ? active.length : state.length);
        long centerX = (long) minX + width / 2L;
        long centerZ = (long) minZ + length / 2L;
        long maxX = (long) minX + width - 1L;
        long maxZ = (long) minZ + length - 1L;

        long tunnelX;
        long tunnelZ;
        switch (direction) {
            case NORTH -> {
                tunnelX = centerX - sectionWidth / 2L;
                tunnelZ = (long) minZ - sectionLength;
            }
            case SOUTH -> {
                tunnelX = centerX - sectionWidth / 2L;
                tunnelZ = maxZ + 1L;
            }
            case WEST -> {
                tunnelX = (long) minX - sectionWidth;
                tunnelZ = centerZ - sectionLength / 2L;
            }
            case EAST -> {
                tunnelX = maxX + 1L;
                tunnelZ = centerZ - sectionLength / 2L;
            }
            default -> throw new IllegalStateException(
                    "Direction de tunnel non cardinale."
            );
        }

        return new Location(
                state.base.getWorld(),
                checkedCoordinate(tunnelX, "X du tunnel"),
                y,
                checkedCoordinate(tunnelZ, "Z du tunnel")
        );
    }

    private int checkedCoordinate(long value, String label) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalStateException(label + " hors limites.");
        }
        return (int) value;
    }

    private boolean isTunnelSectionAllowed(MiningSessionState state,
                                            Location sectionBase,
                                            int sectionSize,
                                            int tunnelHeight) {
        return isTunnelSectionAllowed(
                state,
                sectionBase,
                sectionSize,
                sectionSize,
                tunnelHeight
        );
    }

    private boolean isTunnelSectionAllowed(MiningSessionState state,
                                            Location sectionBase,
                                            int sectionWidth,
                                            int sectionLength,
                                            int tunnelHeight) {
        World world = sectionBase != null ? sectionBase.getWorld() : null;
        if (state == null
                || state.base == null
                || world == null
                || !world.equals(state.base.getWorld())
                || sectionWidth < 1
                || sectionLength < 1
                || !hasValidDimensions(sectionWidth, sectionLength)) {
            return false;
        }

        long maxXLong = (long) sectionBase.getBlockX() + sectionWidth - 1L;
        long maxZLong = (long) sectionBase.getBlockZ() + sectionLength - 1L;
        if (maxXLong > Integer.MAX_VALUE || maxXLong < Integer.MIN_VALUE
                || maxZLong > Integer.MAX_VALUE
                || maxZLong < Integer.MIN_VALUE) {
            return false;
        }

        int minY = sectionBase.getBlockY();
        int safeHeight = Math.max(1, Math.min(
                getMaximumTunnelHeight(),
                tunnelHeight
        ));
        long maxY = (long) minY + safeHeight - 1L;
        if (minY < effectiveWorldMinHeight(world)
                || maxY >= effectiveWorldMaxHeight(world)) {
            return false;
        }

        int maxX = (int) maxXLong;
        int maxZ = (int) maxZLong;
        BlockFace direction = isCardinalDirection(state.tunnelDirection)
                ? state.tunnelDirection
                : BlockFace.SOUTH;
        Volume plannedVolume = new Volume(
                sectionBase.getBlockX(),
                maxX,
                minY,
                (int) maxY,
                sectionBase.getBlockZ(),
                maxZ
        );
        try {
            collectRequiredChunkKeys(
                    state,
                    sectionBase.getBlockX(),
                    sectionBase.getBlockZ(),
                    sectionWidth,
                    sectionLength,
                    isTunnelInfrastructureConfigured()
            );
            if (isTunnelInfrastructureConfigured()
                    && isTunnelFluidsEnabled()
                    && !isTunnelFluidPlanWithinRuntimeBudget(
                    plannedVolume,
                    direction,
                    true
            )) {
                return false;
            }
        } catch (RuntimeException exception) {
            return false;
        }
        if (!isRectangleInsideWorldBorder(
                world,
                sectionBase.getBlockX(),
                maxX,
                sectionBase.getBlockZ(),
                maxZ,
                minY
        ) || !isTunnelRectangleWithinDistance(
                state,
                sectionBase.getBlockX(),
                maxX,
                sectionBase.getBlockZ(),
                maxZ
        )) {
            return false;
        }

        return findOverlappingSession(
                world,
                sectionBase.getBlockX(),
                maxX,
                sectionBase.getBlockZ(),
                maxZ,
                3,
                state.id
        ) == null;
    }

    private boolean isCardinalDirection(BlockFace face) {
        return face == BlockFace.NORTH
                || face == BlockFace.SOUTH
                || face == BlockFace.EAST
                || face == BlockFace.WEST;
    }

    private boolean isTunnelInfrastructureEnabled(MiningSessionState state) {
        return state != null
                && state.pattern == MiningPattern.TUNNEL
                && state.cursor != null
                && isTunnelInfrastructureConfigured();
    }

    private boolean isTunnelInfrastructureConfigured() {
        return plugin.getConfig().getBoolean(
                "mineur.tunnel.infrastructure.enabled",
                true
        );
    }

    private boolean isTunnelFluidsEnabled() {
        return plugin.getConfig().getBoolean(
                "mineur.tunnel.infrastructure.fluids-enabled",
                true
        );
    }

    private int getTunnelOperationsPerTick() {
        return Math.max(1, Math.min(
                512,
                plugin.getConfig().getInt(
                        "mineur.tunnel.infrastructure.operations-per-tick",
                        DEFAULT_TUNNEL_OPERATIONS_PER_TICK
                )
        ));
    }

    private int getTunnelFluidInspectionsPerTick() {
        return Math.max(1, Math.min(
                4_096,
                plugin.getConfig().getInt(
                        "mineur.tunnel.infrastructure.fluid-inspections-per-tick",
                        DEFAULT_TUNNEL_FLUID_INSPECTIONS_PER_TICK
                )
        ));
    }

    private Volume activeTunnelVolume(MiningSessionState state) {
        if (state == null || state.cursor == null) {
            throw new IllegalArgumentException("Curseur de tunnel absent.");
        }
        MiningCursor cursor = state.cursor;
        int maximumX = safeRectangleMaximum(cursor.minX, cursor.width);
        int maximumZ = safeRectangleMaximum(cursor.minZ, cursor.length);
        int maximumY = checkedCoordinate(
                (long) cursor.minY + Math.max(1, cursor.height) - 1L,
                "Y maximum du tunnel"
        );
        return new Volume(
                cursor.minX,
                maximumX,
                cursor.minY,
                maximumY,
                cursor.minZ,
                maximumZ
        );
    }

    private void recordTunnelOriginBounds(MiningSessionState state) {
        Volume volume = activeTunnelVolume(state);
        state.tunnelOriginBounds = new MiningSessionState.StructureBounds(
                volume.minX(),
                volume.maxX(),
                volume.minY(),
                volume.maxY(),
                volume.minZ(),
                volume.maxZ()
        );
    }

    private String tunnelSectionKey(MiningSessionState state, Volume volume) {
        UUID worldId = state.worldUid != null
                ? state.worldUid
                : state.base.getWorld().getUID();
        return worldId + ":" + volume.minX() + ":" + volume.maxX()
                + ":" + volume.minY() + ":" + volume.maxY()
                + ":" + volume.minZ() + ":" + volume.maxZ()
                + ":" + state.tunnelDirection.name();
    }

    private boolean isInsideTunnelVolume(MiningSessionState state, Block block) {
        if (isInsideActiveTunnelVolume(state, block)) {
            return true;
        }
        if (state == null || state.cursor == null || block == null
                || state.worldUid == null
                || !state.worldUid.equals(block.getWorld().getUID())) {
            return false;
        }
        return containsBounds(state.tunnelOriginBounds, block)
                || containsBounds(regularTunnelHistoryBounds(state), block);
    }

    private boolean isInsideActiveTunnelVolume(MiningSessionState state,
                                               Block block) {
        if (state == null || state.cursor == null || block == null
                || state.worldUid == null
                || !state.worldUid.equals(block.getWorld().getUID())) {
            return false;
        }
        MiningCursor cursor = state.cursor;
        long maximumX = (long) cursor.minX + Math.max(1, cursor.width) - 1L;
        long maximumY = (long) cursor.minY + Math.max(1, cursor.height) - 1L;
        long maximumZ = (long) cursor.minZ + Math.max(1, cursor.length) - 1L;
        return block.getX() >= cursor.minX && block.getX() <= maximumX
                && block.getY() >= cursor.minY && block.getY() <= maximumY
                && block.getZ() >= cursor.minZ && block.getZ() <= maximumZ;
    }

    private boolean isTunnelBoundaryBlock(MiningSessionState state, Block block) {
        return isBoundsBoundary(state != null ? state.tunnelOriginBounds : null, block)
                || isBoundsBoundary(regularTunnelHistoryBounds(state), block);
    }

    private boolean containsBounds(MiningSessionState.StructureBounds bounds,
                                   Block block) {
        return bounds != null
                && block != null
                && block.getX() >= bounds.minX()
                && block.getX() <= bounds.maxX()
                && block.getY() >= bounds.minY()
                && block.getY() <= bounds.maxY()
                && block.getZ() >= bounds.minZ()
                && block.getZ() <= bounds.maxZ();
    }

    private boolean isBoundsBoundary(MiningSessionState.StructureBounds bounds,
                                     Block block) {
        if (!containsBounds(bounds, block)) {
            return false;
        }
        return block.getX() == bounds.minX()
                || block.getX() == bounds.maxX()
                || block.getY() == bounds.minY()
                || block.getY() == bounds.maxY()
                || block.getZ() == bounds.minZ()
                || block.getZ() == bounds.maxZ();
    }

    private MiningSessionState.StructureBounds regularTunnelHistoryBounds(
            MiningSessionState state) {
        if (state == null
                || state.pattern != MiningPattern.TUNNEL
                || state.cursor == null
                || state.tunnelSectionsMined < 1
                || !isCardinalDirection(state.tunnelDirection)) {
            return null;
        }
        try {
            Volume history = TunnelInfrastructureBuilder.historicalCorridorVolume(
                    activeTunnelVolume(state),
                    state.tunnelDirection,
                    state.tunnelSectionsMined
            );
            return new MiningSessionState.StructureBounds(
                    history.minX(),
                    history.maxX(),
                    history.minY(),
                    history.maxY(),
                    history.minZ(),
                    history.maxZ()
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean isFluidBlock(Block block) {
        if (block == null) {
            return false;
        }
        if (isDirectFluidBlock(block)) {
            return true;
        }
        BlockData data = block.getBlockData();
        return data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }

    private boolean isDirectFluidBlock(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        return type == Material.WATER
                || type == Material.LAVA
                || type == Material.BUBBLE_COLUMN
                || block.isLiquid();
    }

    private boolean isFluidAt(World world, BlockPos position) {
        if (world == null
                || position == null
                || position.y() < effectiveWorldMinHeight(world)
                || position.y() >= effectiveWorldMaxHeight(world)) {
            return false;
        }
        return isFluidBlock(world.getBlockAt(
                position.x(),
                position.y(),
                position.z()
        ));
    }

    private boolean isTunnelBoundaryThreatAt(World world, BlockPos position) {
        if (world == null
                || position == null
                || position.y() < effectiveWorldMinHeight(world)
                || position.y() >= effectiveWorldMaxHeight(world)) {
            return false;
        }
        Block block = world.getBlockAt(
                position.x(),
                position.y(),
                position.z()
        );
        return isFluidBlock(block) || block.isPassable();
    }

    private boolean isTunnelInfrastructureBlock(RuntimeSession runtime, Block block) {
        if (runtime == null || block == null || runtime.tunnelInfrastructure == null) {
            return false;
        }
        return runtime.tunnelInfrastructure.managedPositions.contains(
                new BlockPos(block.getX(), block.getY(), block.getZ())
        );
    }

    private TunnelInfrastructureWork createTunnelInfrastructureWork(
            RuntimeSession runtime,
            Volume volume,
            String sectionKey) {
        MiningSessionState state = runtime.state;
        World world = state.base != null ? state.base.getWorld() : null;
        if (world == null) {
            throw new IllegalStateException("Monde du tunnel non chargé.");
        }

        TunnelMaterialPalette palette = runtime.tunnelMaterialPalette;
        if (palette == null) {
            palette = getTunnelMaterialPalette();
            runtime.tunnelMaterialPalette = palette;
        }
        Material glass = palette.glass();
        FluidScanner fluidScanner = null;
        if (isTunnelFluidsEnabled()) {
            if (!isTunnelFluidPlanWithinRuntimeBudget(
                    volume,
                    state.tunnelDirection,
                    state.tunnelSectionsMined == 0
            )) {
                throw new IllegalArgumentException(
                        "Le tronçon dépasse le budget opérationnel de l'infrastructure."
                );
            }
            fluidScanner = TunnelInfrastructureBuilder.createFluidScanner(
                    volume,
                    state.tunnelDirection,
                    state.tunnelSectionsMined == 0,
                    glass,
                    glass
            );
        }

        TunnelInfrastructureWork work = new TunnelInfrastructureWork(
                sectionKey,
                volume,
                fluidScanner,
                palette
        );
        if (fluidScanner == null) {
            initializeTunnelInfrastructurePlan(state, work, null);
        }
        return work;
    }

    private boolean isTunnelFluidPlanWithinRuntimeBudget(Volume volume,
                                                         BlockFace direction,
                                                         boolean sealEntrance) {
        if (volume == null || !isCardinalDirection(direction)) {
            return false;
        }
        try {
            return TunnelInfrastructureBuilder.isFluidInspectionPlanWithinBudget(
                    volume,
                    direction,
                    sealEntrance
            ) && TunnelInfrastructureBuilder.fluidInspectionCount(
                    volume,
                    direction,
                    sealEntrance
            ) <= getMaximumTunnelPlanOperations();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void initializeTunnelInfrastructurePlan(
            MiningSessionState state,
            TunnelInfrastructureWork work,
            FluidPlan fluidPlan) {
        Map<BlockPos, Placement> permanent = new java.util.LinkedHashMap<>();
        Map<BlockPos, Placement> temporary = new java.util.LinkedHashMap<>();
        Set<BlockPos> managed = new LinkedHashSet<>();

        if (fluidPlan != null) {
            for (Placement placement : fluidPlan.permanentSeals()) {
                permanent.putIfAbsent(placement.position(), placement);
                managed.add(placement.position());
            }
            for (Placement placement : fluidPlan.temporarySeals()) {
                if (!permanent.containsKey(placement.position())) {
                    temporary.putIfAbsent(placement.position(), placement);
                }
                managed.add(placement.position());
            }
            work.drains = List.copyOf(fluidPlan.drainedInterior());
        }

        boolean samePersistedSection = work.sectionKey.equals(
                state.preparedTunnelSection
        );
        if (samePersistedSection) {
            for (Vector vector : state.tunnelPermanentSeals) {
                BlockPos position = blockPosition(vector);
                if (position != null && work.volume.contains(position)) {
                    work.ownedPermanentSeals.add(position);
                }
            }
            for (Vector vector : state.tunnelForeignFrontierSeals) {
                BlockPos position = blockPosition(vector);
                if (position != null && work.volume.contains(position)) {
                    work.foreignFrontierSeals.add(position);
                }
            }
        }
        boolean samePendingSection = work.sectionKey.equals(
                state.pendingTunnelInfrastructureSection
        );
        if (samePendingSection) {
            for (Vector vector : state.pendingTunnelPermanentSeals) {
                BlockPos position = blockPosition(vector);
                if (position != null && work.volume.contains(position)) {
                    work.journaledPermanentSeals.add(position);
                }
            }
            for (Vector vector : state.pendingTunnelFrontierSeals) {
                BlockPos position = blockPosition(vector);
                if (position != null && work.volume.contains(position)) {
                    work.journaledFrontierSeals.add(position);
                }
            }
        }

        List<BlockPos> previousFrontier = new ArrayList<>();
        for (Vector vector : state.tunnelFrontierSeals) {
            BlockPos position = blockPosition(vector);
            if (position == null) {
                continue;
            }
            if (samePersistedSection && work.volume.contains(position)) {
                work.ownedFrontierSeals.add(position);
            } else if (!samePersistedSection
                    && state.preparedTunnelSection != null
                    && !state.preparedTunnelSection.isBlank()
                    && isPreviousFrontierOpening(
                    position,
                    work.volume,
                    state.tunnelDirection
            )) {
                /*
                 * Je n'ouvre que la section commune avec le nouveau tunnel.
                 * Les bandes latérales et hautes de la grande salle restent
                 * vitrées afin qu'un corridor plus étroit ne les inonde pas.
                 */
                previousFrontier.add(position);
            }
        }
        List<BlockPos> previousForeignFrontier = new ArrayList<>();
        for (Vector vector : state.tunnelForeignFrontierSeals) {
            BlockPos position = blockPosition(vector);
            if (position != null
                    && !samePersistedSection
                    && state.preparedTunnelSection != null
                    && !state.preparedTunnelSection.isBlank()
                    && isPreviousFrontierOpening(
                    position,
                    work.volume,
                    state.tunnelDirection
            )) {
                previousForeignFrontier.add(position);
            }
        }

        for (BlockPos position : new ArrayList<>(work.ownedFrontierSeals)) {
            if (permanent.containsKey(position)
                    || work.journaledPermanentSeals.contains(position)) {
                work.ownedFrontierSeals.remove(position);
                work.ownedPermanentSeals.add(position);
            }
        }
        for (BlockPos position : new ArrayList<>(work.journaledFrontierSeals)) {
            if (permanent.containsKey(position)
                    || work.journaledPermanentSeals.contains(position)
                    || work.ownedPermanentSeals.contains(position)) {
                work.journaledFrontierSeals.remove(position);
                work.journaledPermanentSeals.add(position);
            }
        }
        work.ownedPermanentSeals.removeIf(position ->
                !work.volume.contains(position));
        work.ownedFrontierSeals.removeIf(position ->
                !work.volume.contains(position)
                        || work.ownedPermanentSeals.contains(position));
        work.journaledPermanentSeals.removeIf(position ->
                !work.volume.contains(position));
        work.journaledFrontierSeals.removeIf(position ->
                !work.volume.contains(position)
                        || work.journaledPermanentSeals.contains(position));
        work.foreignFrontierSeals.removeIf(position ->
                !work.volume.contains(position) || permanent.containsKey(position));
        for (BlockPos position : work.ownedPermanentSeals) {
            Placement placement = new Placement(
                    position,
                    work.palette.glass(),
                    PlacementKind.PERMANENT_SEAL
            );
            permanent.putIfAbsent(position, placement);
            temporary.remove(position);
            managed.add(position);
        }
        for (BlockPos position : work.ownedFrontierSeals) {
            if (!permanent.containsKey(position)) {
                temporary.putIfAbsent(
                        position,
                        new Placement(
                                position,
                                work.palette.glass(),
                                PlacementKind.TEMPORARY_SEAL
                        )
                );
            }
            managed.add(position);
        }
        for (BlockPos position : work.journaledPermanentSeals) {
            permanent.putIfAbsent(
                    position,
                    new Placement(
                            position,
                            work.palette.glass(),
                            PlacementKind.PERMANENT_SEAL
                    )
            );
            temporary.remove(position);
            managed.add(position);
        }
        for (BlockPos position : work.journaledFrontierSeals) {
            if (!permanent.containsKey(position)) {
                temporary.putIfAbsent(
                        position,
                        new Placement(
                                position,
                                work.palette.glass(),
                                PlacementKind.TEMPORARY_SEAL
                        )
                );
            }
            managed.add(position);
        }
        for (BlockPos position : work.foreignFrontierSeals) {
            if (!permanent.containsKey(position)) {
                temporary.putIfAbsent(
                        position,
                        new Placement(
                                position,
                                work.palette.glass(),
                                PlacementKind.TEMPORARY_SEAL
                        )
                );
            }
            managed.add(position);
        }

        List<Placement> seals = new ArrayList<>(
                permanent.size() + temporary.size()
        );
        seals.addAll(permanent.values());
        seals.addAll(temporary.values());
        work.seals = List.copyOf(seals);
        work.permanentSeals = List.copyOf(permanent.values());
        work.previousFrontier = List.copyOf(previousFrontier);
        work.previousForeignFrontier = List.copyOf(previousForeignFrontier);
        work.managedPositions = Set.copyOf(managed);
        work.fluidPlanInitialized = true;
    }

    private BlockPos blockPosition(Vector vector) {
        return vector != null
                ? new BlockPos(
                vector.getBlockX(),
                vector.getBlockY(),
                vector.getBlockZ()
        )
                : null;
    }

    private boolean isPreviousFrontierOpening(BlockPos position,
                                              Volume nextVolume,
                                              BlockFace direction) {
        if (position.y() < nextVolume.minY() || position.y() > nextVolume.maxY()) {
            return false;
        }
        return switch (direction) {
            case SOUTH -> position.z() == (long) nextVolume.minZ() - 1L
                    && position.x() >= nextVolume.minX()
                    && position.x() <= nextVolume.maxX();
            case NORTH -> position.z() == (long) nextVolume.maxZ() + 1L
                    && position.x() >= nextVolume.minX()
                    && position.x() <= nextVolume.maxX();
            case EAST -> position.x() == (long) nextVolume.minX() - 1L
                    && position.z() >= nextVolume.minZ()
                    && position.z() <= nextVolume.maxZ();
            case WEST -> position.x() == (long) nextVolume.maxX() + 1L
                    && position.z() >= nextVolume.minZ()
                    && position.z() <= nextVolume.maxZ();
            default -> false;
        };
    }

    private boolean ensureTunnelInfrastructureReady(RuntimeSession runtime,
                                                     long expectedGeneration) {
        if (runtime == null) {
            return true;
        }

        MiningSessionState state = runtime.state;
        if (!isTunnelInfrastructureEnabled(state)) {
            if (state.pattern == MiningPattern.TUNNEL
                    && state.cursor != null
                    && !isTunnelInfrastructureConfigured()
                    && hasTunnelInfrastructureCheckpoint(state, runtime)) {
                state.pause(MiningSessionState.PauseReason.SAFETY);
                saveAllSessions();
                notifyOwner(state.owner, ChatColor.RED
                        + "Tunnel mis en pause : réactive l'infrastructure avant de "
                        + "reprendre ce tronçon déjà préparé.");
                return false;
            }
            runtime.tunnelInfrastructure = null;
            runtime.tunnelDecoration = null;
            return true;
        }

        Volume volume = activeTunnelVolume(state);
        String sectionKey = tunnelSectionKey(state, volume);
        runtime.tunnelInfrastructureTicks++;

        if (isTunnelFluidsEnabled()
                && !isTunnelFluidPlanWithinRuntimeBudget(
                volume,
                state.tunnelDirection,
                state.tunnelSectionsMined == 0
        )) {
            state.pause(MiningSessionState.PauseReason.SAFETY);
            saveAllSessions();
            notifyOwner(state.owner, ChatColor.RED
                    + "Tunnel mis en pause : ses dimensions dépassent le budget "
                    + "opérationnel de l'infrastructure.");
            return false;
        }

        TunnelInfrastructureWork work = runtime.tunnelInfrastructure;
        boolean rescanDue = work != null
                && work.completed
                && runtime.tunnelInfrastructureTicks >= runtime.nextTunnelRescanTick;
        if (work == null || !sectionKey.equals(work.sectionKey) || rescanDue) {
            work = createTunnelInfrastructureWork(runtime, volume, sectionKey);
            runtime.tunnelInfrastructure = work;
        }
        if (!work.fluidPlanInitialized) {
            World world = state.base != null ? state.base.getWorld() : null;
            if (world == null || work.fluidScanner == null) {
                throw new IllegalStateException("Scan du tunnel sans monde chargé.");
            }
            boolean scanComplete = work.fluidScanner.scan(
                    getTunnelFluidInspectionsPerTick(),
                    position -> isFluidAt(world, position),
                    position -> isTunnelBoundaryThreatAt(world, position)
            );
            if (!scanComplete) {
                return false;
            }
            initializeTunnelInfrastructurePlan(
                    state,
                    work,
                    work.fluidScanner.result()
            );
        }
        if (work.completed) {
            return true;
        }

        int remainingBudget = getTunnelOperationsPerTick();
        if (!work.journalPrepared) {
            prepareTunnelSealJournal(runtime, work, remainingBudget);
            /*
             * Je sépare strictement le préflight du lot de mutations. Même si
             * le journal vient d'être sauvegardé, la pose commencera au tick
             * suivant et ne pourra pas doubler le budget de travail courant.
             */
            return false;
        }
        while (remainingBudget-- > 0) {
            TunnelMutationResult result;
            Block affected;

            if (work.sealIndex < work.seals.size()) {
                Placement placement = work.seals.get(work.sealIndex);
                affected = blockAt(state, placement.position());
                boolean alreadyOwned = placement.kind()
                        == PlacementKind.PERMANENT_SEAL
                        ? work.ownedPermanentSeals.contains(placement.position())
                        || work.journaledPermanentSeals.contains(
                        placement.position()
                )
                        : work.ownedFrontierSeals.contains(placement.position())
                        || work.journaledFrontierSeals.contains(
                        placement.position()
                );
                boolean matchingBlockAlreadyPresent =
                        affected.getType() == placement.material()
                                && !isFluidBlock(affected);
                boolean foreignGlassAlreadyPresent = placement.kind()
                        == PlacementKind.TEMPORARY_SEAL
                        && isGlassBlock(affected.getType());
                if (foreignGlassAlreadyPresent) {
                    matchingBlockAlreadyPresent = true;
                    result = TunnelMutationResult.APPLIED;
                } else {
                    result = applyTunnelSeal(runtime, placement);
                }
                if (result.isProgress()) {
                    if (result == TunnelMutationResult.APPLIED
                            && (alreadyOwned || !matchingBlockAlreadyPresent)) {
                        if (placement.kind() == PlacementKind.PERMANENT_SEAL) {
                            work.ownedPermanentSeals.add(placement.position());
                            work.foreignFrontierSeals.remove(placement.position());
                        } else {
                            work.ownedFrontierSeals.add(placement.position());
                            work.foreignFrontierSeals.remove(placement.position());
                        }
                    } else if (result == TunnelMutationResult.APPLIED
                            && placement.kind() == PlacementKind.TEMPORARY_SEAL
                            && matchingBlockAlreadyPresent) {
                        /*
                         * Je garde la provenance du verre déjà présent : il
                         * sera rouvert avec ses drops, sans devenir ma propriété.
                         */
                        work.foreignFrontierSeals.add(placement.position());
                    }
                    work.sealIndex++;
                }
            } else if (work.drainIndex < work.drains.size()) {
                BlockPos position = work.drains.get(work.drainIndex);
                affected = blockAt(state, position);
                result = clearTunnelFluid(runtime, position);
                if (result.isProgress()) {
                    work.drainIndex++;
                }
            } else if (work.previousFrontierIndex < work.previousFrontier.size()) {
                BlockPos position = work.previousFrontier.get(
                        work.previousFrontierIndex
                );
                affected = blockAt(state, position);
                result = removePreviousTunnelFrontier(runtime, position);
                if (result.isProgress()) {
                    work.previousFrontierIndex++;
                }
            } else if (work.previousForeignFrontierIndex
                    < work.previousForeignFrontier.size()) {
                BlockPos position = work.previousForeignFrontier.get(
                        work.previousForeignFrontierIndex
                );
                affected = blockAt(state, position);
                result = removePreviousTunnelFrontier(runtime, position, true);
                if (result.isProgress()) {
                    work.previousForeignFrontierIndex++;
                }
            } else {
                if (work.rescanRequested) {
                    TunnelInfrastructureWork replacement =
                            createTunnelInfrastructureWork(
                            runtime,
                            volume,
                            sectionKey
                    );
                    replacement.ownedFrontierSeals.addAll(
                            work.ownedFrontierSeals
                    );
                    replacement.ownedPermanentSeals.addAll(
                            work.ownedPermanentSeals
                    );
                    replacement.journaledFrontierSeals.addAll(
                            work.journaledFrontierSeals
                    );
                    replacement.journaledPermanentSeals.addAll(
                            work.journaledPermanentSeals
                    );
                    replacement.foreignFrontierSeals.addAll(
                            work.foreignFrontierSeals
                    );
                    if (replacement.fluidPlanInitialized) {
                        initializeTunnelInfrastructurePlan(
                                state,
                                replacement,
                                null
                        );
                    }
                    runtime.tunnelInfrastructure = replacement;
                    return false;
                }
                completeTunnelInfrastructure(runtime, work);
                return true;
            }

            if (result == TunnelMutationResult.RETRY_LATER) {
                return false;
            }
            if (result == TunnelMutationResult.DENIED) {
                state.pause(MiningSessionState.PauseReason.PROTECTION);
                saveAllSessions();
                onMiningProtectionBlocked(state, affected, expectedGeneration);
                return false;
            }
        }
        return false;
    }

    private boolean hasTunnelInfrastructureCheckpoint(MiningSessionState state,
                                                       RuntimeSession runtime) {
        return runtime != null && runtime.tunnelInfrastructure != null
                || state != null && (state.preparedTunnelSection != null
                && !state.preparedTunnelSection.isBlank()
                || state.pendingTunnelInfrastructureSection != null
                && !state.pendingTunnelInfrastructureSection.isBlank()
                || !state.tunnelFrontierSeals.isEmpty()
                || !state.tunnelForeignFrontierSeals.isEmpty()
                || !state.tunnelPermanentSeals.isEmpty()
                || !state.pendingTunnelFrontierSeals.isEmpty()
                || !state.pendingTunnelPermanentSeals.isEmpty());
    }

    private void prepareTunnelSealJournal(RuntimeSession runtime,
                                           TunnelInfrastructureWork work,
                                           int maximumOperations) {
        MiningSessionState state = runtime.state;
        int endExclusive = Math.min(
                work.seals.size(),
                work.journalIndex + Math.max(1, maximumOperations)
        );
        for (int index = work.journalIndex; index < endExclusive; index++) {
            Placement placement = work.seals.get(index);
            BlockPos position = placement.position();
            boolean known = placement.kind() == PlacementKind.PERMANENT_SEAL
                    ? work.ownedPermanentSeals.contains(position)
                    || work.journaledPermanentSeals.contains(position)
                    : work.ownedFrontierSeals.contains(position)
                    || work.journaledFrontierSeals.contains(position);
            if (known) {
                continue;
            }

            Block target = blockAt(state, position);
            if (placement.kind() == PlacementKind.TEMPORARY_SEAL
                    && isGlassBlock(target.getType())) {
                /*
                 * Je conserve tout verre joueur de la couture, même si sa
                 * couleur diffère après un changement de palette.
                 */
                continue;
            }
            if (target.getType() == placement.material()
                    && !isFluidBlock(target)) {
                // Je ne revendique jamais un verre identique déjà présent.
                continue;
            }
            if (placement.kind() == PlacementKind.PERMANENT_SEAL) {
                work.journaledPermanentSeals.add(position);
            } else {
                work.journaledFrontierSeals.add(position);
            }
        }
        work.journalIndex = endExclusive;

        if (work.journalIndex < work.seals.size()) {
            return;
        }

        String pendingSection =
                work.journaledFrontierSeals.isEmpty()
                        && work.journaledPermanentSeals.isEmpty()
                        ? ""
                        : work.sectionKey;
        boolean changed = !Objects.equals(
                pendingSection,
                state.pendingTunnelInfrastructureSection
        ) || !sameCoordinates(
                state.pendingTunnelFrontierSeals,
                work.journaledFrontierSeals
        ) || !sameCoordinates(
                state.pendingTunnelPermanentSeals,
                work.journaledPermanentSeals
        );

        String previousPendingSection = state.pendingTunnelInfrastructureSection;
        List<Vector> previousPendingFrontier = new ArrayList<>(
                state.pendingTunnelFrontierSeals
        );
        List<Vector> previousPendingPermanent = new ArrayList<>(
                state.pendingTunnelPermanentSeals
        );
        state.pendingTunnelInfrastructureSection = pendingSection;
        replaceVectors(
                state.pendingTunnelFrontierSeals,
                work.journaledFrontierSeals
        );
        replaceVectors(
                state.pendingTunnelPermanentSeals,
                work.journaledPermanentSeals
        );
        if (changed) {
            /*
             * J'enregistre tout le plan une seule fois avant la première
             * mutation. Une extinction pourra réadopter uniquement mon verre,
             * sans provoquer une écriture disque à chaque lot de poses.
             */
            if (!saveAllSessions()) {
                state.pendingTunnelInfrastructureSection = previousPendingSection;
                state.pendingTunnelFrontierSeals.clear();
                state.pendingTunnelFrontierSeals.addAll(previousPendingFrontier);
                state.pendingTunnelPermanentSeals.clear();
                state.pendingTunnelPermanentSeals.addAll(previousPendingPermanent);
                state.pause(MiningSessionState.PauseReason.ERROR);
                throw new IllegalStateException(
                        "Le journal du tunnel n'a pas pu être sauvegardé."
                );
            }
        }
        work.journalPrepared = true;
    }

    private void replaceVectors(List<Vector> target,
                                java.util.Collection<BlockPos> positions) {
        target.clear();
        for (BlockPos position : positions) {
            target.add(new Vector(position.x(), position.y(), position.z()));
        }
    }

    private void completeTunnelInfrastructure(RuntimeSession runtime,
                                              TunnelInfrastructureWork work) {
        MiningSessionState state = runtime.state;
        boolean changed = state.tunnelInfrastructureVersion
                != MiningSessionState.CURRENT_TUNNEL_INFRASTRUCTURE_VERSION
                || !work.sectionKey.equals(state.preparedTunnelSection)
                || !sameCoordinates(
                state.tunnelFrontierSeals,
                work.ownedFrontierSeals
        )
                || !sameCoordinates(
                state.tunnelPermanentSeals,
                work.ownedPermanentSeals
        )
                || !sameCoordinates(
                state.tunnelForeignFrontierSeals,
                work.foreignFrontierSeals
        )
                || (state.pendingTunnelInfrastructureSection != null
                && !state.pendingTunnelInfrastructureSection.isBlank())
                || !state.pendingTunnelFrontierSeals.isEmpty()
                || !state.pendingTunnelPermanentSeals.isEmpty();

        int previousInfrastructureVersion = state.tunnelInfrastructureVersion;
        String previousPreparedSection = state.preparedTunnelSection;
        String previousPendingSection = state.pendingTunnelInfrastructureSection;
        List<Vector> previousFrontier = new ArrayList<>(state.tunnelFrontierSeals);
        List<Vector> previousForeignFrontier = new ArrayList<>(
                state.tunnelForeignFrontierSeals
        );
        List<Vector> previousPermanent = new ArrayList<>(state.tunnelPermanentSeals);
        List<Vector> previousPendingFrontier = new ArrayList<>(
                state.pendingTunnelFrontierSeals
        );
        List<Vector> previousPendingPermanent = new ArrayList<>(
                state.pendingTunnelPermanentSeals
        );

        state.tunnelInfrastructureVersion =
                MiningSessionState.CURRENT_TUNNEL_INFRASTRUCTURE_VERSION;
        state.preparedTunnelSection = work.sectionKey;
        replaceVectors(state.tunnelFrontierSeals, work.ownedFrontierSeals);
        replaceVectors(
                state.tunnelForeignFrontierSeals,
                work.foreignFrontierSeals
        );
        replaceVectors(state.tunnelPermanentSeals, work.ownedPermanentSeals);
        state.pendingTunnelInfrastructureSection = "";
        state.pendingTunnelFrontierSeals.clear();
        state.pendingTunnelPermanentSeals.clear();
        if (changed && !saveAllSessions()) {
            state.tunnelInfrastructureVersion = previousInfrastructureVersion;
            state.preparedTunnelSection = previousPreparedSection;
            state.pendingTunnelInfrastructureSection = previousPendingSection;
            state.tunnelFrontierSeals.clear();
            state.tunnelFrontierSeals.addAll(previousFrontier);
            state.tunnelForeignFrontierSeals.clear();
            state.tunnelForeignFrontierSeals.addAll(previousForeignFrontier);
            state.tunnelPermanentSeals.clear();
            state.tunnelPermanentSeals.addAll(previousPermanent);
            state.pendingTunnelFrontierSeals.clear();
            state.pendingTunnelFrontierSeals.addAll(previousPendingFrontier);
            state.pendingTunnelPermanentSeals.clear();
            state.pendingTunnelPermanentSeals.addAll(previousPendingPermanent);
            state.pause(MiningSessionState.PauseReason.ERROR);
            throw new IllegalStateException(
                    "Le commit du tunnel n'a pas pu être sauvegardé."
            );
        }
        work.completed = true;
        runtime.nextTunnelRescanTick = runtime.tunnelInfrastructureTicks
                + TUNNEL_RESCAN_INTERVAL_TICKS;
    }

    private boolean isRuntimeWorkActive(RuntimeSession runtime) {
        return runtime != null
                && (runtime.tunnelCompletionPending || isLoopActive(runtime));
    }

    private boolean sameCoordinates(List<Vector> persisted,
                                    java.util.Collection<BlockPos> planned) {
        Set<BlockPos> persistedPositions = new LinkedHashSet<>();
        for (Vector vector : persisted) {
            if (vector != null) {
                persistedPositions.add(new BlockPos(
                        vector.getBlockX(),
                        vector.getBlockY(),
                        vector.getBlockZ()
                ));
            }
        }
        return persistedPositions.equals(new LinkedHashSet<>(planned));
    }

    private Block blockAt(MiningSessionState state, BlockPos position) {
        World world = state.base != null ? state.base.getWorld() : null;
        if (world == null) {
            throw new IllegalStateException("Monde du tunnel non chargé.");
        }
        return world.getBlockAt(position.x(), position.y(), position.z());
    }

    private TunnelMutationResult applyTunnelSeal(RuntimeSession runtime,
                                                 Placement placement) {
        MiningSessionState state = runtime.state;
        Block target = blockAt(state, placement.position());
        Material material = placement.material();
        if (target.getType() == material && !isFluidBlock(target)) {
            configureTunnelBlock(target, placement.kind(), state.tunnelDirection);
            return TunnelMutationResult.APPLIED;
        }

        Material previousType = target.getType();
        boolean fluid = isFluidBlock(target);
        boolean directFluid = isDirectFluidBlock(target);
        if (!previousType.isAir()
                && !directFluid
                && !MiningBlockPolicy.isMineable(target)) {
            return TunnelMutationResult.SKIPPED;
        }

        List<ItemStack> drops = !previousType.isAir() && !directFluid
                ? new ArrayList<>(MiningBlockPolicy.computeDrops(
                target,
                createMiningTool()
        ))
                : List.of();
        if (!drops.isEmpty()
                && (runtime.router == null || !runtime.router.canFitAll(drops))) {
            onStorageBlocked(state);
            return TunnelMutationResult.RETRY_LATER;
        }
        if ((previousType.isAir() || directFluid)
                && !isTunnelPlacementSpaceAvailable(runtime, target, material)) {
            return TunnelMutationResult.RETRY_LATER;
        }

        BlockState snapshot = target.getState();
        if (!previousType.isAir() || fluid) {
            MiningLoop.BreakPermissionResult breakResult = evaluateTunnelBreak(
                    state,
                    target
            );
            if (breakResult != MiningLoop.BreakPermissionResult.ALLOWED) {
                return breakResult == MiningLoop.BreakPermissionResult.RETRY_LATER
                        ? TunnelMutationResult.RETRY_LATER
                        : TunnelMutationResult.DENIED;
            }
            if (target.getType() != previousType) {
                return TunnelMutationResult.SKIPPED;
            }
        }

        MiningLoop.BreakPermissionResult placeReadiness = tunnelPlaceReadiness(state);
        if (placeReadiness != MiningLoop.BreakPermissionResult.ALLOWED) {
            return placeReadiness == MiningLoop.BreakPermissionResult.RETRY_LATER
                    ? TunnelMutationResult.RETRY_LATER
                    : TunnelMutationResult.DENIED;
        }

        try {
            target.setType(material, false);
            configureTunnelBlock(target, placement.kind(), state.tunnelDirection);
            if (!callTunnelPlaceEvent(state, target, snapshot, material)) {
                restoreTunnelSnapshot(snapshot, target);
                return TunnelMutationResult.DENIED;
            }
            if (target.getType() != material) {
                restoreTunnelSnapshot(snapshot, target);
                return TunnelMutationResult.DENIED;
            }
        } catch (RuntimeException exception) {
            restoreTunnelSnapshot(snapshot, target);
            throw exception;
        }

        depositTunnelDrops(runtime, target.getLocation(), drops);
        return TunnelMutationResult.APPLIED;
    }

    private TunnelMutationResult clearTunnelFluid(RuntimeSession runtime,
                                                  BlockPos position) {
        MiningSessionState state = runtime.state;
        Block target = blockAt(state, position);
        if (!isFluidBlock(target)) {
            return TunnelMutationResult.APPLIED;
        }

        MiningLoop.BreakPermissionResult permission = evaluateTunnelBreak(state, target);
        if (permission != MiningLoop.BreakPermissionResult.ALLOWED) {
            return permission == MiningLoop.BreakPermissionResult.RETRY_LATER
                    ? TunnelMutationResult.RETRY_LATER
                    : TunnelMutationResult.DENIED;
        }
        if (!isFluidBlock(target)) {
            return TunnelMutationResult.APPLIED;
        }

        BlockState snapshot = target.getState();
        try {
            BlockData data = target.getBlockData();
            if (data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
                waterlogged.setWaterlogged(false);
                target.setBlockData(waterlogged, false);
            } else {
                target.setType(Material.AIR, false);
            }
            return TunnelMutationResult.APPLIED;
        } catch (RuntimeException exception) {
            restoreTunnelSnapshot(snapshot, target);
            throw exception;
        }
    }

    private TunnelMutationResult removePreviousTunnelFrontier(
            RuntimeSession runtime,
            BlockPos position) {
        return removePreviousTunnelFrontier(runtime, position, false);
    }

    private TunnelMutationResult removePreviousTunnelFrontier(
            RuntimeSession runtime,
            BlockPos position,
            boolean routeDrops) {
        MiningSessionState state = runtime.state;
        Block target = blockAt(state, position);
        if (!isGlassBlock(target.getType())) {
            return TunnelMutationResult.SKIPPED;
        }

        List<ItemStack> drops = routeDrops
                ? new ArrayList<>(MiningBlockPolicy.computeDrops(
                target,
                createMiningTool()
        ))
                : List.of();
        if (!drops.isEmpty()
                && (runtime.router == null || !runtime.router.canFitAll(drops))) {
            onStorageBlocked(state);
            return TunnelMutationResult.RETRY_LATER;
        }

        MiningLoop.BreakPermissionResult permission = evaluateTunnelBreak(state, target);
        if (permission != MiningLoop.BreakPermissionResult.ALLOWED) {
            return permission == MiningLoop.BreakPermissionResult.RETRY_LATER
                    ? TunnelMutationResult.RETRY_LATER
                    : TunnelMutationResult.DENIED;
        }
        if (!isGlassBlock(target.getType())) {
            return TunnelMutationResult.SKIPPED;
        }

        BlockState snapshot = target.getState();
        try {
            target.setType(Material.AIR, false);
            depositTunnelDrops(runtime, target.getLocation(), drops);
            return TunnelMutationResult.APPLIED;
        } catch (RuntimeException exception) {
            restoreTunnelSnapshot(snapshot, target);
            throw exception;
        }
    }

    private MiningLoop.BreakPermissionResult evaluateTunnelBreak(
            MiningSessionState state,
            Block block) {
        if (state == null
                || block == null
                || state.paused
                || state.worldUid == null
                || !state.worldUid.equals(block.getWorld().getUID())
                || !isInsideWorldBorder(block.getLocation())) {
            return MiningLoop.BreakPermissionResult.DENIED;
        }

        Player owner = state.owner != null ? Bukkit.getPlayer(state.owner) : null;
        boolean ownerRequired = plugin.getConfig().getBoolean(
                "mineur.protection.require-owner-online",
                true
        );
        boolean fireEvent = plugin.getConfig().getBoolean(
                "mineur.protection.fire-block-break-event",
                true
        );
        if (owner == null) {
            return ownerRequired || fireEvent
                    ? MiningLoop.BreakPermissionResult.RETRY_LATER
                    : MiningLoop.BreakPermissionResult.ALLOWED;
        }
        if (!fireEvent) {
            return MiningLoop.BreakPermissionResult.ALLOWED;
        }

        boolean allowed = AutomatedMiningContext.call(() -> {
            BlockBreakEvent event = new BlockBreakEvent(block, owner);
            event.setDropItems(false);
            event.setExpToDrop(0);
            Bukkit.getPluginManager().callEvent(event);
            return !event.isCancelled();
        });
        return allowed
                ? MiningLoop.BreakPermissionResult.ALLOWED
                : MiningLoop.BreakPermissionResult.DENIED;
    }

    private MiningLoop.BreakPermissionResult tunnelPlaceReadiness(
            MiningSessionState state) {
        if (state == null || state.paused) {
            return MiningLoop.BreakPermissionResult.DENIED;
        }
        Player owner = state.owner != null ? Bukkit.getPlayer(state.owner) : null;
        boolean ownerRequired = plugin.getConfig().getBoolean(
                "mineur.protection.require-owner-online",
                true
        );
        boolean fireEvent = plugin.getConfig().getBoolean(
                "mineur.protection.fire-block-place-event",
                true
        );
        return owner == null && (ownerRequired || fireEvent)
                ? MiningLoop.BreakPermissionResult.RETRY_LATER
                : MiningLoop.BreakPermissionResult.ALLOWED;
    }

    private boolean callTunnelPlaceEvent(MiningSessionState state,
                                         Block target,
                                         BlockState replacedState,
                                         Material material) {
        if (!plugin.getConfig().getBoolean(
                "mineur.protection.fire-block-place-event",
                true
        )) {
            return true;
        }
        Player owner = state.owner != null ? Bukkit.getPlayer(state.owner) : null;
        if (owner == null) {
            return false;
        }

        BlockPlaceEvent event = new BlockPlaceEvent(
                target,
                replacedState,
                findShaftPlacedAgainst(target),
                new ItemStack(material, 1),
                owner,
                true,
                EquipmentSlot.HAND
        );
        AutomatedMiningContext.call(() -> {
            Bukkit.getPluginManager().callEvent(event);
            return null;
        });
        return !event.isCancelled() && event.canBuild();
    }

    private void configureTunnelBlock(Block target,
                                      PlacementKind kind,
                                      BlockFace direction) {
        BlockData data = target.getBlockData();
        if (data instanceof Waterlogged waterlogged) {
            waterlogged.setWaterlogged(false);
        }
        if (kind == PlacementKind.SUPPORT_BEAM && data instanceof Orientable orientable) {
            orientable.setAxis(
                    direction == BlockFace.NORTH || direction == BlockFace.SOUTH
                            ? Axis.X
                            : Axis.Z
            );
        }
        target.setBlockData(data, false);
    }

    private void restoreTunnelSnapshot(BlockState snapshot, Block target) {
        if (snapshot == null) {
            return;
        }
        try {
            if (!snapshot.update(true, false)) {
                plugin.getLogger().warning(
                        "[Mineur] Snapshot du tunnel non restauré en "
                                + (target != null ? coords(target) : "position inconnue")
                                + "."
                );
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "[Mineur] Échec de restauration d'un bloc du tunnel.",
                    exception
            );
        }
    }

    private void depositTunnelDrops(RuntimeSession runtime,
                                    Location fallback,
                                    List<ItemStack> drops) {
        if (drops == null || drops.isEmpty()) {
            return;
        }
        List<ItemStack> leftovers = runtime.router != null
                ? runtime.router.deposit(drops)
                : new ArrayList<>(drops);
        if (!leftovers.isEmpty() && runtime.router != null) {
            runtime.router.dropOnGround(
                    runtime.state.base.getWorld(),
                    fallback,
                    leftovers
            );
        }
        onStorageFreed(runtime.state);
    }

    private Material getConfiguredTunnelMaterial(String path, Material fallback) {
        String configured = plugin.getConfig().getString(path, fallback.name());
        Material material = configured != null ? Material.matchMaterial(configured) : null;
        if (material == null
                || !material.isBlock()
                || !material.isItem()
                || !material.isSolid()
                || material.isAir()) {
            plugin.getLogger().warning(
                    "[Mineur] Matériau solide et posable requis pour " + path + " ; "
                            + fallback.name() + " est utilisé."
            );
            return fallback;
        }
        return material;
    }

    private Material resolveTunnelPlacementMaterial(
            Placement placement,
            TunnelMaterialPalette palette) {
        return switch (placement.kind()) {
            case PERMANENT_SEAL, TEMPORARY_SEAL -> palette.glass();
            case SUPPORT_POST -> palette.post();
            case SUPPORT_BEAM -> palette.beam();
            case LIGHT -> palette.light();
            case PLATFORM -> palette.floor();
            case CHEST, WORKSTATION -> placement.material();
        };
    }

    private TunnelMaterialPalette getTunnelMaterialPalette() {
        return new TunnelMaterialPalette(
                getConfiguredTunnelGlassMaterial(),
                getConfiguredTunnelMaterial(
                        "mineur.tunnel.infrastructure.post-material",
                        Material.SPRUCE_FENCE
                ),
                getConfiguredTunnelMaterial(
                        "mineur.tunnel.infrastructure.beam-material",
                        Material.STRIPPED_SPRUCE_LOG
                ),
                getConfiguredTunnelMaterial(
                        "mineur.tunnel.infrastructure.floor-material",
                        Material.SPRUCE_PLANKS
                ),
                getConfiguredTunnelMaterial(
                        "mineur.tunnel.infrastructure.light-material",
                        Material.SHROOMLIGHT
                )
        );
    }

    private boolean isGlassBlock(Material material) {
        return material == Material.GLASS
                || material == Material.TINTED_GLASS
                || (material != null && material.name().endsWith("_STAINED_GLASS"));
    }

    private Material getConfiguredTunnelGlassMaterial() {
        Material configured = getConfiguredTunnelMaterial(
                "mineur.tunnel.infrastructure.glass-material",
                Material.LIGHT_BLUE_STAINED_GLASS
        );
        if (isGlassBlock(configured)) {
            return configured;
        }
        plugin.getLogger().warning(
                "[Mineur] Le scellement du tunnel doit être un bloc de verre plein ; "
                        + "LIGHT_BLUE_STAINED_GLASS est utilisé."
        );
        return Material.LIGHT_BLUE_STAINED_GLASS;
    }

    private TunnelInfrastructureBuilder.Settings getTunnelInfrastructureSettings(
            Volume volume,
            BlockFace direction,
            boolean arrivalRoom) {
        long orientedWidth = direction == BlockFace.NORTH || direction == BlockFace.SOUTH
                ? volume.sizeX()
                : volume.sizeZ();
        long orientedLength = direction == BlockFace.NORTH || direction == BlockFace.SOUTH
                ? volume.sizeZ()
                : volume.sizeX();
        int maximumAisle = Math.max(
                1,
                Math.toIntExact(orientedWidth - (arrivalRoom ? 4L : 2L))
        );
        int aisle = Math.max(1, Math.min(
                Math.min(255, maximumAisle),
                plugin.getConfig().getInt(
                        "mineur.tunnel.infrastructure.central-aisle-width",
                        3
                )
        ));
        if ((aisle & 1) == 0) {
            aisle--;
        }
        return new TunnelInfrastructureBuilder.Settings(
                Math.max(1, Math.min(1_024, plugin.getConfig().getInt(
                        "mineur.tunnel.infrastructure.support-spacing",
                        5
                ))),
                Math.min(
                        Math.max(0, Math.min(1_024, plugin.getConfig().getInt(
                                "mineur.tunnel.infrastructure.first-support-offset",
                                3
                        ))),
                        Math.max(0, Math.toIntExact(orientedLength - 1L))
                ),
                Math.max(1, Math.min(1_024, plugin.getConfig().getInt(
                        "mineur.tunnel.infrastructure.light-every-frames",
                        1
                ))),
                aisle,
                Math.max(3, Math.min(
                        Math.min(1_024, Math.toIntExact(orientedLength)),
                        plugin.getConfig().getInt(
                        "mineur.tunnel.infrastructure.arrival-platform-depth",
                        4
                ))),
                getMaximumTunnelPlanOperations()
        );
    }

    private int getMaximumTunnelPlanOperations() {
        return Math.max(1, Math.min(
                MAX_RUNTIME_TUNNEL_PLAN_OPERATIONS,
                plugin.getConfig().getInt(
                        "mineur.tunnel.infrastructure.max-planned-blocks-per-section",
                        4_096
                )
        ));
    }

    private TunnelWorkResult decorateCompletedTunnelSection(
            RuntimeSession runtime,
            long expectedGeneration) {
        TunnelInfrastructureWork work = runtime.tunnelInfrastructure;
        if (work == null || !work.completed) {
            return TunnelWorkResult.RETRY_LATER;
        }
        if (work.sectionKey.equals(runtime.decoratedTunnelSection)) {
            runtime.tunnelDecoration = null;
            return TunnelWorkResult.COMPLETE;
        }

        TunnelDecorationWork decoration = runtime.tunnelDecoration;
        if (decoration == null || !work.sectionKey.equals(decoration.sectionKey)) {
            TunnelInfrastructureBuilder.Plan plan;
            try {
                plan = TunnelInfrastructureBuilder.planDecorations(
                        work.volume,
                        runtime.state.tunnelDirection,
                        runtime.state.tunnelArrivalRoom,
                        getTunnelInfrastructureSettings(
                                work.volume,
                                runtime.state.tunnelDirection,
                                runtime.state.tunnelArrivalRoom
                        ),
                        work.permanentSeals
                );
            } catch (IllegalArgumentException exception) {
                /*
                 * Je conserve le drainage lorsque des limites personnalisées
                 * rendent le décor impossible (par exemple un tunnel trop bas).
                 * La session ne doit pas s'arrêter pour une amélioration visuelle.
                 */
                plugin.getLogger().warning(
                        "[Mineur] Décor du tunnel ignoré pour la session "
                                + runtime.state.id + " : " + exception.getMessage()
                );
                runtime.decoratedTunnelSection = work.sectionKey;
                runtime.tunnelDecoration = null;
                return TunnelWorkResult.COMPLETE;
            }

            List<Placement> resolvedPlacements = new ArrayList<>(
                    plan.placements().size()
            );
            for (Placement original : plan.placements()) {
                resolvedPlacements.add(new Placement(
                        original.position(),
                        resolveTunnelPlacementMaterial(original, work.palette),
                        original.kind()
                ));
            }
            decoration = new TunnelDecorationWork(
                    work.sectionKey,
                    resolvedPlacements
            );
            runtime.tunnelDecoration = decoration;
        }

        TunnelOperationBatch.Result<Placement> batch =
                TunnelOperationBatch.process(
                        decoration.placements,
                        decoration.placementIndex,
                        getTunnelOperationsPerTick(),
                        placement -> {
                            TunnelMutationResult result = placement.kind()
                                    == PlacementKind.PERMANENT_SEAL
                                    ? applyTunnelSeal(runtime, placement)
                                    : placeTunnelDecoration(runtime, placement);
                            return switch (result) {
                                case APPLIED, SKIPPED ->
                                        TunnelOperationBatch.StepResult.ADVANCED;
                                case RETRY_LATER ->
                                        TunnelOperationBatch.StepResult.RETRY_LATER;
                                case DENIED ->
                                        TunnelOperationBatch.StepResult.DENIED;
                            };
                        }
                );
        decoration.placementIndex = batch.nextIndex();
        if (batch.status() == TunnelOperationBatch.Status.RETRY_LATER) {
            return TunnelWorkResult.RETRY_LATER;
        }
        if (batch.status() == TunnelOperationBatch.Status.DENIED) {
            runtime.state.pause(MiningSessionState.PauseReason.PROTECTION);
            saveAllSessions();
            onMiningProtectionBlocked(
                    runtime.state,
                    blockAt(runtime.state, batch.blockedItem().position()),
                    expectedGeneration
            );
            return TunnelWorkResult.DENIED;
        }
        if (batch.status() == TunnelOperationBatch.Status.IN_PROGRESS) {
            return TunnelWorkResult.IN_PROGRESS;
        }
        runtime.decoratedTunnelSection = work.sectionKey;
        runtime.tunnelDecoration = null;
        return TunnelWorkResult.COMPLETE;
    }

    private TunnelMutationResult placeTunnelDecoration(RuntimeSession runtime,
                                                       Placement placement) {
        MiningSessionState state = runtime.state;
        Block target = blockAt(state, placement.position());
        Material material = placement.material();
        if (target.getType() == material && !isFluidBlock(target)) {
            /*
             * Je considère un bloc déjà présent comme terminé sans modifier ses
             * données : il peut appartenir à un joueur et le retry doit rester
             * strictement idempotent.
             */
            return TunnelMutationResult.APPLIED;
        }
        if (isFluidBlock(target)) {
            runtime.tunnelInfrastructure = null;
            return TunnelMutationResult.RETRY_LATER;
        }
        if (!target.getType().isAir()) {
            /*
             * Je n'écrase jamais une construction apparue dans une salle déjà
             * excavée. Le décor est secondaire face aux blocs du monde.
             */
            return TunnelMutationResult.SKIPPED;
        }
        if (!isTunnelPlacementSpaceAvailable(runtime, target, material)) {
            return TunnelMutationResult.RETRY_LATER;
        }

        MiningLoop.BreakPermissionResult readiness = tunnelPlaceReadiness(state);
        if (readiness != MiningLoop.BreakPermissionResult.ALLOWED) {
            return readiness == MiningLoop.BreakPermissionResult.RETRY_LATER
                    ? TunnelMutationResult.RETRY_LATER
                    : TunnelMutationResult.DENIED;
        }

        BlockState snapshot = target.getState();
        try {
            target.setType(material, false);
            configureTunnelBlock(target, placement.kind(), state.tunnelDirection);
            if (!callTunnelPlaceEvent(state, target, snapshot, material)
                    || target.getType() != material) {
                restoreTunnelSnapshot(snapshot, target);
                return TunnelMutationResult.DENIED;
            }
            return TunnelMutationResult.APPLIED;
        } catch (RuntimeException exception) {
            restoreTunnelSnapshot(snapshot, target);
            throw exception;
        }
    }

    private boolean isTunnelPlacementSpaceAvailable(RuntimeSession runtime,
                                                     Block target,
                                                     Material material) {
        if (target == null || material == null) {
            return false;
        }
        BlockData blockData;
        try {
            blockData = material.createBlockData();
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (!target.canPlace(blockData)) {
            return false;
        }

        double inset = 1.0E-4D;
        BoundingBox bounds = new BoundingBox(
                target.getX() + inset,
                target.getY() + inset,
                target.getZ() + inset,
                target.getX() + 1.0D - inset,
                target.getY() + 1.0D - inset,
                target.getZ() + 1.0D - inset
        );
        return target.getWorld().getNearbyEntities(bounds).stream().noneMatch(
                entity -> entity instanceof LivingEntity living
                        && living.isValid()
                        && !living.isDead()
                        && !isRuntimeActor(runtime, living)
        );
    }

    private boolean isRuntimeActor(RuntimeSession runtime, Entity entity) {
        if (runtime == null || entity == null) {
            return false;
        }
        if (entity.equals(runtime.miner)) {
            return true;
        }
        for (Golem golem : runtime.golems) {
            if (golem != null && entity.equals(golem.getGolem())) {
                return true;
            }
        }
        return false;
    }

    private void scheduleTunnelCompletionRetry(UUID sessionId,
                                               long expectedGeneration,
                                               long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            RuntimeSession runtime = runtimeOf(sessionId);
            if (runtime == null
                    || runtime.state.paused
                    || runtime.loopGeneration != expectedGeneration) {
                return;
            }
            onLoopCompletion(sessionId, expectedGeneration);
        }, Math.max(1L, delayTicks));
    }

    private enum TunnelWorkResult {
        COMPLETE,
        IN_PROGRESS,
        RETRY_LATER,
        DENIED
    }

    private enum TunnelMutationResult {
        APPLIED,
        SKIPPED,
        RETRY_LATER,
        DENIED;

        boolean isProgress() {
            return this == APPLIED || this == SKIPPED;
        }
    }

    private ItemStack createMiningTool() {
        String configured = plugin.getConfig().getString(
                "mineur.tool.material",
                Material.NETHERITE_PICKAXE.name()
        );
        Material material = configured != null ? Material.matchMaterial(configured) : null;
        if (material == null || !material.isItem() || !material.name().endsWith("_PICKAXE")) {
            material = Material.NETHERITE_PICKAXE;
        }
        return new ItemStack(material);
    }

    private MiningLoop.BreakPermissionResult evaluateAutomatedMinerBreak(
            MiningSessionState state,
            Block block) {
        if (state == null
                || block == null
                || state.paused
                || state.worldUid == null
                || !state.worldUid.equals(block.getWorld().getUID())
                || !MiningBlockPolicy.isMineable(block)
                || !isInsideActiveCursor(state, block)
                || !isInsideWorldBorder(block.getLocation())) {
            return MiningLoop.BreakPermissionResult.DENIED;
        }

        Player owner = state.owner != null ? Bukkit.getPlayer(state.owner) : null;
        boolean ownerRequired = plugin.getConfig().getBoolean(
                "mineur.protection.require-owner-online",
                true
        );
        boolean fireBreakEvent = plugin.getConfig().getBoolean(
                "mineur.protection.fire-block-break-event",
                true
        );

        if (owner == null) {
            /*
             * Au démarrage du serveur, les joueurs ne sont pas encore connectés.
             * Cette absence est temporaire : elle doit seulement suspendre la
             * casse, jamais mettre la session persistante en pause ni retirer
             * son PNJ. L'événement synthétique requiert lui aussi un vrai Player.
             */
            return ownerRequired || fireBreakEvent
                    ? MiningLoop.BreakPermissionResult.RETRY_LATER
                    : MiningLoop.BreakPermissionResult.ALLOWED;
        }

        if (!fireBreakEvent) {
            return MiningLoop.BreakPermissionResult.ALLOWED;
        }

        boolean allowed = AutomatedMiningContext.call(() -> {
            BlockBreakEvent syntheticEvent = new BlockBreakEvent(block, owner);
            syntheticEvent.setDropItems(false);
            syntheticEvent.setExpToDrop(0);
            Bukkit.getPluginManager().callEvent(syntheticEvent);
            /*
             * Si un listener autorisé modifie lui-même le bloc, la boucle le
             * revalidera juste après l'événement et le sautera proprement.
             */
            return !syntheticEvent.isCancelled();
        });
        return allowed
                ? MiningLoop.BreakPermissionResult.ALLOWED
                : MiningLoop.BreakPermissionResult.DENIED;
    }

    private boolean isInsideActiveCursor(MiningSessionState state, Block block) {
        MiningCursor cursor = state.cursor;
        if (cursor == null) {
            return false;
        }

        long maxX = (long) cursor.minX + Math.max(1, cursor.width) - 1L;
        long maxZ = (long) cursor.minZ + Math.max(1, cursor.length) - 1L;
        if (block.getX() < cursor.minX || block.getX() > maxX
                || block.getZ() < cursor.minZ || block.getZ() > maxZ) {
            return false;
        }

        if (state.pattern == MiningPattern.TUNNEL) {
            long maxY = (long) cursor.minY + Math.max(1, cursor.height) - 1L;
            return block.getY() >= cursor.minY && block.getY() <= maxY;
        }
        World world = block.getWorld();
        return block.getY() >= getEffectiveStopY(world)
                && block.getY() <= state.base.getBlockY();
    }

    private void onMiningProtectionBlocked(MiningSessionState state,
                                             Block block,
                                             long expectedGeneration) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            RuntimeSession runtime = runtimeOf(state.id);
            if (runtime == null || runtime.loopGeneration != expectedGeneration) {
                return;
            }
            state.pause(MiningSessionState.PauseReason.PROTECTION);
            runtime.suspend();
            saveAllSessions();
            notifyOwner(state.owner, ChatColor.RED
                    + "Minage refusé en " + coords(block)
                    + " par une protection. Session en pause.");
        });
    }

    private void onMiningFailure(MiningSessionState state,
                                 Exception exception,
                                 long expectedGeneration) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            RuntimeSession runtime = runtimeOf(state.id);
            if (runtime == null || runtime.loopGeneration != expectedGeneration) {
                return;
            }
            state.pause(MiningSessionState.PauseReason.ERROR);
            runtime.suspend();
            saveAllSessions();
            notifyOwner(state.owner, ChatColor.RED
                    + "Le mineur a rencontré une erreur et a été mis en pause.");
            plugin.getLogger().log(Level.WARNING,
                    "[Mineur] Erreur de boucle pour la session " + state.id + ".",
                    exception);
        });
    }

    private MiningIterator createIteratorFor(World world,
                                             MiningSessionState state,
                                             MiningCursor cursor) {
        if (world == null) {
            throw new IllegalStateException("Monde absent dans createIteratorFor.");
        }
        if (state == null) {
            throw new IllegalStateException("Session absente dans createIteratorFor.");
        }
        if (cursor == null) {
            cursor = new MiningCursor(state.base, state.width, state.length);
            state.cursor = cursor;
        }

        int stopY = getEffectiveStopY(world);
        return switch (state.pattern) {
            case QUARRY -> new QuarryIterator(world, cursor, stopY);
            case BRANCH -> {
                int spacing = Math.max(2, Math.min(64,
                        plugin.getConfig().getInt("mineur.branch.spacing", 6)));
                int galleryWidth = Math.max(1, Math.min(cursor.length,
                        plugin.getConfig().getInt("mineur.branch.gallery-width", 3)));
                yield new BranchIterator(world, cursor, stopY, spacing, galleryWidth);
            }
            case TUNNEL -> {
                int availableHeight = Math.max(
                        1,
                        effectiveWorldMaxHeight(world) - clampY(world, cursor.minY)
                );
                int height = Math.min(
                        availableHeight,
                        cursor.height > 0
                                ? cursor.height
                                : (state.tunnelHeight > 0
                                ? state.tunnelHeight
                                : getConfiguredTunnelHeight())
                );
                ensureTunnelCursorDefaults(cursor, height);
                yield new TunnelIterator(world, cursor, height);
            }
            case VEIN_FIRST -> {
                int scanRadius = Math.max(0, Math.min(16,
                        plugin.getConfig().getInt("mineur.vein.scan-radius", 5)));
                int maxBlocks = Math.max(1, Math.min(512,
                        plugin.getConfig().getInt("mineur.vein.max-blocks", 96)));
                int scanEvery = Math.max(1, Math.min(128,
                        plugin.getConfig().getInt("mineur.vein.scan-every-blocks", 8)));
                MiningIterator delegate = new QuarryIterator(world, cursor, stopY);
                yield new VeinFirstIterator(
                        world,
                        delegate,
                        scanRadius,
                        maxBlocks,
                        scanEvery,
                        stopY,
                        state.base.getBlockY(),
                        true
                );
            }
        };
    }

    private void prepareTunnelCursor(MiningSessionState state,
                                     Location tunnelBase,
                                     int width,
                                     int length,
                                     int height) {
        World world = tunnelBase != null ? tunnelBase.getWorld() : null;
        if (world == null) {
            throw new IllegalArgumentException("Base de tunnel sans monde.");
        }

        int safeWidth = Math.max(1, Math.min(getMaximumMineWidth(), width));
        int safeLength = Math.max(1, Math.min(getMaximumMineLength(), length));
        if (!hasValidDimensions(safeWidth, safeLength)) {
            throw new IllegalArgumentException("Section de tunnel trop grande.");
        }

        int minimumY = clampY(world, tunnelBase.getBlockY());
        int availableHeight = Math.max(1, effectiveWorldMaxHeight(world) - minimumY);
        int safeHeight = Math.max(1, Math.min(
                Math.min(getMaximumTunnelHeight(), availableHeight),
                height
        ));

        Location normalizedBase = new Location(
                world,
                tunnelBase.getBlockX(),
                minimumY,
                tunnelBase.getBlockZ()
        );
        MiningCursor cursor = new MiningCursor(normalizedBase, safeWidth, safeLength);
        cursor.minY = minimumY;
        cursor.y = minimumY;
        cursor.height = safeHeight;
        state.cursor = cursor;
        state.pendingCursor = null;
    }

    private void clearTunnelInfrastructureCheckpoint(MiningSessionState state) {
        if (state == null) {
            return;
        }
        state.tunnelInfrastructureVersion = 0;
        state.preparedTunnelSection = "";
        state.tunnelFrontierSeals.clear();
        state.tunnelForeignFrontierSeals.clear();
        state.tunnelPermanentSeals.clear();
        state.pendingTunnelInfrastructureSection = "";
        state.pendingTunnelFrontierSeals.clear();
        state.pendingTunnelPermanentSeals.clear();
        state.tunnelOriginBounds = null;
    }

    private void ensureTunnelCursorDefaults(MiningCursor cursor, int height) {
        int safeHeight = Math.max(1, height);
        cursor.height = safeHeight;
        if (cursor.minY == 0 && cursor.y != 0) {
            cursor.minY = cursor.y;
        }
        if (cursor.y < cursor.minY || cursor.y >= cursor.minY + safeHeight) {
            cursor.y = cursor.minY;
        }
        cursor.width = Math.max(1, cursor.width);
        cursor.length = Math.max(1, cursor.length);
    }

    private int getConfiguredTunnelSectionSize() {
        int configured = Math.max(1,
                plugin.getConfig().getInt("mineur.tunnel.section-size", 10));
        int dimensionLimit = Math.min(getMaximumMineWidth(), getMaximumMineLength());
        int areaLimit = Math.max(1, (int) Math.floor(Math.sqrt(getMaximumMineArea())));
        return Math.min(configured, Math.min(dimensionLimit, areaLimit));
    }

    private int getConfiguredTunnelHeight() {
        int maximum = getMaximumTunnelHeight();
        int minimum = Math.min(3, maximum);
        return Math.max(minimum, Math.min(
                maximum,
                plugin.getConfig().getInt("mineur.tunnel.height", 3)
        ));
    }

    private BlockFace directionFromYaw(float yaw) {
        float normalized = yaw % 360.0F;
        if (normalized < 0.0F) {
            normalized += 360.0F;
        }
        if (normalized >= 45.0F && normalized < 135.0F) {
            return BlockFace.WEST;
        }
        if (normalized >= 135.0F && normalized < 225.0F) {
            return BlockFace.NORTH;
        }
        if (normalized >= 225.0F && normalized < 315.0F) {
            return BlockFace.EAST;
        }
        return BlockFace.SOUTH;
    }

    private String formatDirection(BlockFace face) {
        if (face == null) {
            return "inconnue";
        }
        return switch (face) {
            case NORTH -> "nord";
            case SOUTH -> "sud";
            case EAST -> "est";
            case WEST -> "ouest";
            default -> face.name().toLowerCase(Locale.ROOT);
        };
    }

    private boolean hasIntegratedShaft(MiningSessionState state) {
        return state != null
                && state.structureBounds != null
                && state.structureVersion
                >= MineCabinBuilder.SHAFT_ACCESS_STRUCTURE_VERSION;
    }

    private boolean shouldUseQuarryDecoration(MiningSessionState state) {
        return state != null
                && state.pattern == MiningPattern.QUARRY
                && (hasIntegratedShaft(state) || allowMinerBlockPlacement());
    }

    private int getShaftLightInterval() {
        return MineShaftColumnBuilder.normalizeLightInterval(
                plugin.getConfig().getInt(
                        "mineur.structure.shaft-light-interval",
                        MineShaftColumnBuilder.DEFAULT_LIGHT_INTERVAL
                )
        );
    }

    /**
     * Pose un élément de la colonne verticale sans écraser le monde.
     *
     * <p>La méthode répète les contrôles de sûreté au moment exact de
     * l'écriture, applique les BlockData avant l'événement synthétique puis
     * restaure le snapshot si une protection refuse la pose. Une décoration
     * dynamique ne peut ainsi ni contourner un claim, ni laisser un bloc à
     * moitié configuré après une exception.</p>
     */
    private boolean placeShaftBlock(MiningSessionState state,
                                    Block target,
                                    Material material,
                                    Consumer<BlockData> dataConfigurer) {
        if (state == null
                || state.base == null
                || target == null
                || material == null
                || state.worldUid == null
                || !state.worldUid.equals(target.getWorld().getUID())) {
            return false;
        }

        World world = target.getWorld();
        if (target.getY() < effectiveWorldMinHeight(world)
                || target.getY() >= effectiveWorldMaxHeight(world)
                || !world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)
                || !isInsideWorldBorder(target.getLocation().add(0.5D, 0.5D, 0.5D))) {
            return false;
        }

        Material current = target.getType();
        if (current == material) {
            BlockData data = target.getBlockData();
            if (dataConfigurer != null) {
                dataConfigurer.accept(data);
            }
            if (data instanceof Waterlogged waterlogged) {
                waterlogged.setWaterlogged(false);
            }
            target.setBlockData(data, false);
            return true;
        }
        if (!current.isAir() && !MineShaftColumnBuilder.isManagedMaterial(current)) {
            /*
             * Minerai, roche, liquide ou construction du joueur : la colonne
             * attend que la case soit réellement excavée au lieu de la forcer.
             */
            return false;
        }

        boolean fireEvents = fireStructureProtectionEvents();
        Player actor = state.owner != null ? Bukkit.getPlayer(state.owner) : null;
        if (fireEvents && actor == null) {
            /*
             * Un BlockPlaceEvent crédible exige un joueur. La réparation sera
             * retentée au prochain bloc ou à la reconnexion du propriétaire.
             */
            return false;
        }

        BlockState replacedState = target.getState();
        try {
            target.setType(material, false);
            BlockData data = target.getBlockData();
            if (dataConfigurer != null) {
                dataConfigurer.accept(data);
            }
            if (data instanceof Waterlogged waterlogged) {
                waterlogged.setWaterlogged(false);
            }
            target.setBlockData(data, false);

            if (fireEvents) {
                BlockPlaceEvent event = new BlockPlaceEvent(
                        target,
                        replacedState,
                        findShaftPlacedAgainst(target),
                        new ItemStack(material, 1),
                        actor,
                        true,
                        EquipmentSlot.HAND
                );
                AutomatedMiningContext.call(() -> {
                    plugin.getServer().getPluginManager().callEvent(event);
                    return null;
                });
                if (event.isCancelled() || !event.canBuild()) {
                    restoreShaftSnapshot(replacedState, target);
                    return false;
                }
            }

            if (target.getType() != material) {
                /*
                 * Un autre plugin a transformé le bloc pendant l'événement.
                 * Revenir au snapshot est plus sûr que d'adopter une matière
                 * inconnue comme élément géré du puits.
                 */
                restoreShaftSnapshot(replacedState, target);
                return false;
            }
            return true;
        } catch (RuntimeException exception) {
            restoreShaftSnapshot(replacedState, target);
            throw exception;
        }
    }

    private Block findShaftPlacedAgainst(Block block) {
        Block below = block.getRelative(BlockFace.DOWN);
        if (!below.getType().isAir()) {
            return below;
        }

        Block above = block.getRelative(BlockFace.UP);
        if (!above.getType().isAir()) {
            return above;
        }

        for (BlockFace face : List.of(
                BlockFace.NORTH,
                BlockFace.SOUTH,
                BlockFace.EAST,
                BlockFace.WEST
        )) {
            Block adjacent = block.getRelative(face);
            if (!adjacent.getType().isAir()) {
                return adjacent;
            }
        }
        return below;
    }

    private void restoreShaftSnapshot(BlockState snapshot, Block target) {
        if (snapshot == null) {
            return;
        }
        try {
            if (!snapshot.update(true, false)) {
                plugin.getLogger().warning(
                        "[Mineur] Snapshot du puits non restauré en "
                                + (target != null ? coords(target) : "position inconnue")
                                + "."
                );
            }
        } catch (RuntimeException restoreFailure) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "[Mineur] Échec de restauration d'un bloc du puits en "
                            + (target != null ? coords(target) : "position inconnue")
                            + ".",
                    restoreFailure
            );
        }
    }

    private boolean allowMinerBlockPlacement() {
        return plugin.getConfig().getBoolean("mineur.allow-block-placement", false);
    }

    private MiningPattern getDefaultPattern() {
        String value = plugin.getConfig().getString("mineur.default.pattern", "QUARRY");
        if (value == null) {
            return MiningPattern.QUARRY;
        }
        try {
            return MiningPattern.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return MiningPattern.QUARRY;
        }
    }

    private MiningSpeed getDefaultSpeed() {
        String value = plugin.getConfig().getString("mineur.default.speed", "NORMAL");
        MiningSpeed speed = parseSpeed(value);
        return speed != null ? speed : MiningSpeed.NORMAL;
    }

    private MiningSpeed parseSpeed(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "slow", "lent" -> MiningSpeed.SLOW;
            case "normal" -> MiningSpeed.NORMAL;
            case "fast", "rapide" -> MiningSpeed.FAST;
            default -> {
                try {
                    yield MiningSpeed.valueOf(value.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    yield null;
                }
            }
        };
    }

    private MiningPattern parsePattern(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "quarry", "carriere", "carrière" -> MiningPattern.QUARRY;
            case "branch", "branche" -> MiningPattern.BRANCH;
            case "tunnel" -> MiningPattern.TUNNEL;
            case "vein_first", "veine", "vein", "veine_first" -> MiningPattern.VEIN_FIRST;
            default -> {
                try {
                    yield MiningPattern.valueOf(value.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    yield null;
                }
            }
        };
    }

    private static class Selection {
        private Block corner1;
        private Block corner2;

        public Block getCorner1() {
            return corner1;
        }

        public void setCorner1(Block corner1) {
            this.corner1 = corner1;
        }

        public Block getCorner2() {
            return corner2;
        }

        public void setCorner2(Block corner2) {
            this.corner2 = corner2;
        }

        public boolean isComplete() {
            return corner1 != null && corner2 != null;
        }
    }

    private static final class TunnelInfrastructureWork {
        private final String sectionKey;
        private final Volume volume;
        private final FluidScanner fluidScanner;
        private final TunnelMaterialPalette palette;
        private final Set<BlockPos> ownedFrontierSeals = new LinkedHashSet<>();
        private final Set<BlockPos> ownedPermanentSeals = new LinkedHashSet<>();
        private final Set<BlockPos> journaledFrontierSeals = new LinkedHashSet<>();
        private final Set<BlockPos> journaledPermanentSeals = new LinkedHashSet<>();
        private final Set<BlockPos> foreignFrontierSeals = new LinkedHashSet<>();
        private List<Placement> seals = List.of();
        private List<BlockPos> drains = List.of();
        private List<Placement> permanentSeals = List.of();
        private List<BlockPos> previousFrontier = List.of();
        private List<BlockPos> previousForeignFrontier = List.of();
        private Set<BlockPos> managedPositions = Set.of();
        private int sealIndex;
        private int journalIndex;
        private int drainIndex;
        private int previousFrontierIndex;
        private int previousForeignFrontierIndex;
        private boolean fluidPlanInitialized;
        private boolean journalPrepared;
        private boolean rescanRequested;
        private boolean completed;

        private TunnelInfrastructureWork(String sectionKey,
                                         Volume volume,
                                         FluidScanner fluidScanner,
                                         TunnelMaterialPalette palette) {
            this.sectionKey = Objects.requireNonNull(sectionKey, "sectionKey");
            this.volume = Objects.requireNonNull(volume, "volume");
            this.fluidScanner = fluidScanner;
            this.palette = Objects.requireNonNull(palette, "palette");
        }
    }

    private record TunnelMaterialPalette(Material glass,
                                         Material post,
                                         Material beam,
                                         Material floor,
                                         Material light) {

        private TunnelMaterialPalette {
            Objects.requireNonNull(glass, "glass");
            Objects.requireNonNull(post, "post");
            Objects.requireNonNull(beam, "beam");
            Objects.requireNonNull(floor, "floor");
            Objects.requireNonNull(light, "light");
        }
    }

    private static final class TunnelDecorationWork {
        private final String sectionKey;
        private final List<Placement> placements;
        private int placementIndex;

        private TunnelDecorationWork(String sectionKey,
                                     List<Placement> placements) {
            this.sectionKey = Objects.requireNonNull(sectionKey, "sectionKey");
            this.placements = List.copyOf(placements);
        }
    }

    private final class RuntimeSession {
        private final MiningSessionState state;
        private final Set<Chunk> ticketChunks = new HashSet<>();
        private final List<Location> containerLocations = new ArrayList<>();
        private final List<Golem> golems = new ArrayList<>();
        private Villager miner;
        private MiningLoop loop;
        /**
         * Invalide les callbacks différés appartenant à une ancienne boucle.
         */
        private long loopGeneration;
        private InventoryRouter router;
        private DecorationDelegate decoration;
        private TunnelInfrastructureWork tunnelInfrastructure;
        private TunnelDecorationWork tunnelDecoration;
        private TunnelMaterialPalette tunnelMaterialPalette;
        private long tunnelInfrastructureTicks;
        private long nextTunnelRescanTick;
        private String decoratedTunnelSection = "";
        private boolean tunnelCompletionPending;
        private Hologram storageHologram;
        private MineCabinBuilder.BuildResult freshConstruction;
        /**
         * Compteur uniquement destiné à limiter les journaux lors d'un
         * environnement temporairement impossible à restaurer.
         */
        private int minerRecoveryFailures;

        RuntimeSession(MiningSessionState state) {
            this.state = state;
        }

        void suspend() {
            loopGeneration++;
            tunnelCompletionPending = false;
            if (loop != null) {
                loop.cancelAndRollback();
                loop = null;
            } else {
                state.rollbackPendingCursor();
            }
            removeActors();
            if (storageHologram != null) {
                storageHologram.hide();
                storageHologram = null;
            }
            releaseChunkTickets();
        }

        void removeActors() {
            if (miner != null) {
                removeEntitySafely(miner);
                miner = null;
            }
            for (Golem golem : new ArrayList<>(golems)) {
                try {
                    if (golem != null) {
                        golem.remove();
                    }
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(
                            Level.FINE,
                            "[Mineur] Suppression d'un golem auxiliaire impossible.",
                            exception
                    );
                }
            }
            golems.clear();
        }

        void releaseChunkTickets() {
            for (Chunk chunk : new HashSet<>(ticketChunks)) {
                SharedChunkTickets.release(plugin, chunk);
            }
            ticketChunks.clear();
        }

        void commitFreshConstruction() {
            if (freshConstruction == null) {
                return;
            }
            freshConstruction.commit();
            freshConstruction = null;
        }

        void rollbackFreshConstruction() {
            if (freshConstruction == null) {
                return;
            }

            /*
             * Les PDC sont retirés avant de remettre les snapshots à leur état
             * initial. Cela évite de conserver une moitié de coffre signée si
             * une restauration de bloc venait elle-même à échouer.
             */
            cleanupContainerMetadata(state);
            freshConstruction.rollback();
            freshConstruction = null;
            state.containers.clear();
            containerLocations.clear();
            state.structureVersion = 0;
            state.structureBounds = null;
        }

        void stop(boolean cleanupContainers) {
            rollbackFreshConstruction();
            suspend();
            if (cleanupContainers) {
                cleanupContainerMetadata(state);
            }
            router = null;
            decoration = null;
            tunnelInfrastructure = null;
            tunnelDecoration = null;
            tunnelMaterialPalette = null;
            decoratedTunnelSection = "";
            containerLocations.clear();
        }
    }

    private final class DecorationDelegate {
        private final MiningSessionState state;
        private final boolean optionalDecorationsEnabled;
        private final boolean integratedShaftEnabled;
        private final MineShaftColumnBuilder.Layout shaftLayout;
        private final int shaftLightInterval;

        /*
         * Paramètres de compatibilité de l'ancien accès périphérique. Ils ne
         * sont utilisés que pour une cabane v1 ou lorsque la structure
         * automatique est désactivée.
         */
        private final int supportSpacing;
        private final int torchLayerInterval;
        private final int ladderX;
        private final int ladderZ;
        private final int ladderSupportX;
        private final BlockFace ladderFacing = BlockFace.EAST;
        private final int torchX;
        private final int torchSupportZ;
        private final BlockFace torchFacing = BlockFace.SOUTH;
        private final int depthMarkerInterval = 5;

        private int minedBlocks;
        private int completedLayers;
        private int currentLayerY;
        private boolean initialized;

        DecorationDelegate(MiningSessionState state) {
            this.state = Objects.requireNonNull(state, "state");
            if (state.base == null) {
                throw new IllegalArgumentException("Base absente pour la décoration de mine.");
            }

            this.optionalDecorationsEnabled = allowMinerBlockPlacement();
            this.integratedShaftEnabled = hasIntegratedShaft(state);
            this.shaftLayout = integratedShaftEnabled
                    ? MineShaftColumnBuilder.createLayout(
                    state.base.getBlockX(),
                    state.base.getBlockZ(),
                    Math.max(1, state.width),
                    Math.max(1, state.length)
            )
                    : null;
            this.shaftLightInterval = getShaftLightInterval();

            this.supportSpacing = Math.max(
                    0,
                    plugin.getConfig().getInt("mineur.default.supports-every", 8)
            );
            this.torchLayerInterval = Math.max(
                    1,
                    plugin.getConfig().getInt("mineur.default.torch-layers", 4)
            );
            this.ladderX = safeRectangleMaximum(
                    state.base.getBlockX(),
                    Math.max(1, state.width)
            );
            this.ladderZ = state.base.getBlockZ() + Math.max(state.length, 1) / 2;
            this.ladderSupportX = this.ladderX < Integer.MAX_VALUE
                    ? this.ladderX + 1
                    : this.ladderX;
            this.torchX = state.base.getBlockX() + Math.max(state.width, 1) / 2;
            this.torchSupportZ = state.base.getBlockZ() > Integer.MIN_VALUE
                    ? state.base.getBlockZ() - 1
                    : state.base.getBlockZ();

            int baseY = state.base.getBlockY();
            int cursorY = state.cursor != null ? state.cursor.y : baseY;
            this.currentLayerY = Math.min(baseY, cursorY);
            this.completedLayers = Math.max(0, baseY - currentLayerY);
        }

        void initialize() {
            if (initialized) {
                return;
            }
            initialized = true;
            try {
                prepareInitialAccess(state.base.getWorld());
            } catch (RuntimeException exception) {
                /*
                 * La cabane statique est déjà transactionnelle. Une panne de
                 * décoration profonde ne doit pas invalider son commit ni
                 * supprimer une session qui peut continuer à miner.
                 */
                plugin.getLogger().log(
                        Level.WARNING,
                        "[Mineur] Initialisation du puits ignorée pour la session "
                                + state.id + ".",
                        exception
                );
            }
        }

        void afterBlock(Block block) {
            initialize();
            if (block == null) {
                return;
            }
            minedBlocks++;

            /*
             * Le nombre de blocs réellement cassés n'est pas égal à
             * width*length lorsqu'une couche contient de l'air, de la bedrock
             * ou des blocs protégés. La transition du parcours vers un Y plus
             * bas est la seule preuve fiable qu'une couche est terminée.
             */
            advanceCompletedLayers(block.getWorld(), block.getY());

            if (integratedShaftEnabled
                    && shaftLayout.isRelevantHorizontal(block.getX(), block.getZ())) {
                maintainIntegratedLayer(block.getWorld(), block.getY());
            } else if (!integratedShaftEnabled
                    && optionalDecorationsEnabled
                    && block.getX() == ladderX
                    && block.getZ() == ladderZ) {
                /*
                 * Compatibilité v1 : la case de l'échelle n'est équipée qu'après
                 * sa collecte, jamais en remplaçant un minerai.
                 */
                placeLegacyLadderAt(block.getWorld(), block.getY());
            }

            /*
             * Les anciens piliers de pierre aléatoires restent disponibles pour
             * les structures v1. Le puits v2 possède déjà une charpente lisible
             * et ne reçoit pas ce décor dispersé qui brouillerait son identité.
             */
            if (!integratedShaftEnabled
                    && optionalDecorationsEnabled
                    && supportSpacing > 0
                    && minedBlocks % supportSpacing == 0) {
                Block floor = block.getRelative(BlockFace.DOWN);
                SupportBuilder.placeSupportColumn(block.getWorld(), floor, 3);
            }
        }

        /**
         * Indique à la boucle quels blocs appartiennent au puits et doivent être
         * validés sans nouvelle casse lors d'une reprise de checkpoint.
         */
        boolean isManagedBlock(Block block) {
            if (block == null) {
                return false;
            }
            if (integratedShaftEnabled) {
                return MineShaftColumnBuilder.isManagedBlock(
                        shaftLayout,
                        state.base.getBlockY(),
                        block
                );
            }
            return optionalDecorationsEnabled
                    && block.getX() == ladderX
                    && block.getZ() == ladderZ
                    && block.getY() <= state.base.getBlockY()
                    && block.getType() == Material.LADDER;
        }

        /**
         * Répare tout le tronçon déjà excavé, notamment après une reconnexion
         * du propriétaire ou une fin de parcours composée uniquement d'air.
         */
        void synchronizeToCursor() {
            initialize();
            if (!integratedShaftEnabled) {
                return;
            }

            World world = state.base.getWorld();
            if (world == null) {
                return;
            }

            int targetY = state.cursor != null
                    ? Math.min(currentLayerY, state.cursor.y)
                    : currentLayerY;
            targetY = Math.max(
                    effectiveWorldMinHeight(world),
                    Math.min(state.base.getBlockY(), targetY)
            );

            try {
                MineShaftColumnBuilder.maintainRange(
                        world,
                        shaftLayout,
                        state.base.getBlockY(),
                        targetY,
                        shaftLightInterval,
                        this::placeIntegratedShaftBlock
                );
                currentLayerY = targetY;
                completedLayers = Math.max(
                        0,
                        state.base.getBlockY() - currentLayerY
                );
            } catch (RuntimeException exception) {
                /*
                 * Le puits est une amélioration d'accès : une erreur de pose ne
                 * doit pas faire perdre un bloc déjà miné ni interrompre le
                 * dépôt des ressources.
                 */
                plugin.getLogger().log(
                        Level.WARNING,
                        "[Mineur] Synchronisation du puits ignorée pour la session "
                                + state.id + ".",
                        exception
                );
            }
        }

        private void advanceCompletedLayers(World world, int minedY) {
            while (currentLayerY > minedY) {
                currentLayerY--;
                completedLayers++;

                if (integratedShaftEnabled) {
                    maintainIntegratedLayer(world, currentLayerY);
                    continue;
                }
                if (!optionalDecorationsEnabled) {
                    continue;
                }

                buildLegacyAccessStair(world, currentLayerY);
                extendLegacyAccessLadder(world, currentLayerY);
                if (shouldPlaceLegacyDepthMarker(currentLayerY)) {
                    placeLegacyDepthMarker(world, currentLayerY);
                }
                if (completedLayers % torchLayerInterval == 0) {
                    placeLegacyWallTorch(world, currentLayerY);
                }
            }
        }

        private void prepareInitialAccess(World world) {
            if (world == null) {
                return;
            }
            if (integratedShaftEnabled) {
                MineShaftColumnBuilder.maintainRange(
                        world,
                        shaftLayout,
                        state.base.getBlockY(),
                        currentLayerY,
                        shaftLightInterval,
                        this::placeIntegratedShaftBlock
                );
            } else if (optionalDecorationsEnabled) {
                extendLegacyAccessLadder(world, currentLayerY);
            }
        }

        private void maintainIntegratedLayer(World world, int y) {
            MineShaftColumnBuilder.maintainLayer(
                    world,
                    shaftLayout,
                    state.base.getBlockY(),
                    y,
                    shaftLightInterval,
                    this::placeIntegratedShaftBlock
            );
        }

        private boolean placeIntegratedShaftBlock(Block target,
                                                  Material material,
                                                  Consumer<BlockData> dataConfigurer) {
            return Mineur.this.placeShaftBlock(
                    state,
                    target,
                    material,
                    dataConfigurer
            );
        }

        private void buildLegacyAccessStair(World world, int layerY) {
            if (world == null
                    || layerY < effectiveWorldMinHeight(world)
                    || layerY >= effectiveWorldMaxHeight(world)) {
                return;
            }
            Location base = state.base;
            Block stairBlock = world.getBlockAt(
                    base.getBlockX() - 1,
                    layerY,
                    base.getBlockZ()
            );
            StairBuilder.ensureStair(world, stairBlock, BlockFace.SOUTH, 3);
        }

        private void extendLegacyAccessLadder(World world, int targetY) {
            if (world == null) {
                return;
            }
            int minY = Math.max(targetY, effectiveWorldMinHeight(world));
            int startY = state.base.getBlockY();
            for (int y = startY; y >= minY; y--) {
                placeLegacyLadderAt(world, y);
            }
        }

        private void placeLegacyLadderAt(World world, int y) {
            if (world == null
                    || y < effectiveWorldMinHeight(world)
                    || y >= effectiveWorldMaxHeight(world)
                    || !ensureLegacySupportBlock(
                    world,
                    ladderSupportX,
                    y,
                    ladderZ
            )) {
                return;
            }

            Block ladderBlock = world.getBlockAt(ladderX, y, ladderZ);
            if (!ladderBlock.getType().isAir()
                    && ladderBlock.getType() != Material.LADDER) {
                return;
            }

            ladderBlock.setType(Material.LADDER, false);
            if (ladderBlock.getBlockData()
                    instanceof org.bukkit.block.data.type.Ladder ladderData) {
                ladderData.setFacing(ladderFacing);
                ladderBlock.setBlockData(ladderData, false);
            }
        }

        private void placeLegacyWallTorch(World world, int y) {
            if (world == null) {
                return;
            }
            if (ensureLegacySupportBlock(world, torchX, y, torchSupportZ)) {
                Block support = world.getBlockAt(torchX, y, torchSupportZ);
                TorchPlacer.placeWallTorch(world, support, torchFacing);
            }
        }

        private boolean shouldPlaceLegacyDepthMarker(int y) {
            if (depthMarkerInterval <= 0) {
                return false;
            }
            int delta = state.base.getBlockY() - y;
            return delta > 0 && delta % depthMarkerInterval == 0;
        }

        private void placeLegacyDepthMarker(World world, int y) {
            if (world != null) {
                ensureLegacySupportBlock(world, torchX, y, torchSupportZ);
            }
        }

        private boolean ensureLegacySupportBlock(World world,
                                                 int x,
                                                 int y,
                                                 int z) {
            if (world == null
                    || y < effectiveWorldMinHeight(world)
                    || y >= effectiveWorldMaxHeight(world)) {
                return false;
            }

            Block block = world.getBlockAt(x, y, z);
            if (block.getType().isSolid()) {
                return true;
            }
            if (!block.getType().isAir()) {
                /*
                 * Ne jamais remplacer eau, panneau, redstone ou autre bloc
                 * non solide appartenant potentiellement au joueur.
                 */
                return false;
            }
            block.setType(Material.STONE_BRICKS, false);
            return true;
        }
    }

}
