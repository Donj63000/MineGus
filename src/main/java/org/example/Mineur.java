package org.example;

import org.bukkit.Bukkit;
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
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.example.mineur.BranchIterator;
import org.example.mineur.InventoryRouter;
import org.example.mineur.MiningCursor;
import org.example.mineur.MiningIterator;
import org.example.mineur.MiningLoop;
import org.example.mineur.MiningPattern;
import org.example.mineur.MiningSessionState;
import org.example.mineur.MiningSpeed;
import org.example.mineur.QuarryIterator;
import org.example.mineur.TunnelIterator;
import org.example.mineur.VeinFirstIterator;
import org.example.mineur.builders.StairBuilder;
import org.example.mineur.builders.SupportBuilder;
import org.example.mineur.builders.TorchPlacer;
import org.example.mineur.store.SessionStore;
import org.example.mineur.ui.Hologram;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class Mineur implements CommandExecutor, Listener {

    private static final String SELECTOR_NAME = ChatColor.GOLD + "Sélecteur de mine";
    private static final String CMD_PREFIX = ChatColor.GRAY + "[Mineur] " + ChatColor.RESET;

    private final JavaPlugin plugin;
    private final SessionStore sessionStore;
    private final NamespacedKey containerOwnerKey;
    private final NamespacedKey containerSessionKey;

    private final List<MiningSessionState> sessions = new ArrayList<>();
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<UUID, RuntimeSession> runtimes = new HashMap<>();
    private final Map<UUID, List<UUID>> ownerSessions = new HashMap<>();
    private final Map<UUID, UUID> selectedSessions = new HashMap<>();

    public Mineur(JavaPlugin plugin) {
        this.plugin = plugin;
        this.sessionStore = new SessionStore(plugin.getDataFolder());
        this.containerOwnerKey = new NamespacedKey(plugin, "mineur-owner");
        this.containerSessionKey = new NamespacedKey(plugin, "mineur-session");

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
                + ChatColor.GRAY + ", pose auto=" + ChatColor.AQUA + (placementAuto ? "activee" : "desactivee"));
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
                + ChatColor.GRAY + " : arrete et nettoie completement la session.");

        lines.add(ChatColor.GOLD + "/mineur info" + ChatColor.GRAY + " | " + ChatColor.GOLD + "/mineur status"
                + ChatColor.GRAY + " : affiche zone, monde, pattern, vitesse, bonus et etat du mineur selectionne.");

        lines.add(ChatColor.GOLD + "/mineur autoriser <joueur>" + ChatColor.GRAY + " | "
                + ChatColor.GOLD + "/mineur trust <joueur>" + ChatColor.GRAY
                + " : autorise un joueur a interagir avec ta session.");

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
        return lines;
    }

    private void createMineFromSelection(Player player) {
        UUID ownerId = player.getUniqueId();
        JobManager jobManager = getJobManager();
        if (jobManager != null && !jobManager.hasMinerJob(ownerId)) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED
                    + "Tu dois avoir le metier " + ChatColor.GOLD + "Mineur"
                    + ChatColor.RED + " pour poser un PNJ mineur.");
            player.sendMessage(CMD_PREFIX + ChatColor.YELLOW + "Utilise " + ChatColor.GOLD + "/job mineur"
                    + ChatColor.YELLOW + " pour debloquer cette fonctionnalite.");
            return;
        }

        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || !selection.isComplete()) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Sélection invalide. Utilise /mineur et clique deux blocs à la même hauteur.");
            return;
        }

        Block c1 = selection.getCorner1();
        Block c2 = selection.getCorner2();
        if (c1.getWorld() != c2.getWorld()) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Les deux coins doivent être dans le même monde.");
            return;
        }
        if (c1.getY() != c2.getY()) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Les deux blocs doivent être à la même hauteur (Y).");
            return;
        }

        World world = c1.getWorld();
        if (!isWorldAllowed(world)) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Ce monde n'est pas autorisé pour le mineur.");
            return;
        }

        int minX = Math.min(c1.getX(), c2.getX());
        int maxX = Math.max(c1.getX(), c2.getX());
        int minZ = Math.min(c1.getZ(), c2.getZ());
        int maxZ = Math.max(c1.getZ(), c2.getZ());
        int width = (maxX - minX) + 1;
        int length = (maxZ - minZ) + 1;

        int activeMines = getSessionsForOwner(ownerId).size();
        int maxMines = getMaxMinesForOwner(ownerId);
        if (activeMines >= maxMines) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Limite atteinte: " + activeMines + "/" + maxMines + " mineur(s) actifs.");
            Integer nextUnlock = getNextMineUnlockLevel(ownerId);
            if (nextUnlock != null) {
                player.sendMessage(CMD_PREFIX + ChatColor.YELLOW + "Prochain mineur debloque au niveau " + nextUnlock + ".");
            } else {
                player.sendMessage(CMD_PREFIX + ChatColor.YELLOW + "Tu as deja atteint le maximum de mineurs.");
            }
            return;
        }

        Location base = new Location(world, minX, c1.getY(), minZ);
        MiningSessionState state = new MiningSessionState();
        state.worldUid = world.getUID();
        state.base = base;
        state.width = width;
        state.length = length;
        state.cursor = new MiningCursor(base, width, length);
        state.minerY = base.getY();
        state.owner = ownerId;
        boolean defaultUseBarrel = plugin.getConfig()
                .getBoolean("mineur.default.use-barrel-master", false);
        state.useBarrelMaster = defaultUseBarrel;
        state.pattern = getDefaultPattern();
        state.speed = getDefaultSpeed();

        // Mode "mineur complet" : carrière puis tunnel infini
        if (state.pattern == MiningPattern.QUARRY) {
            state.chainTunnelAfterQuarry = true;
        } else {
            state.chainTunnelAfterQuarry = false;
        }
        state.infiniteTunnel = false;
        state.tunnelDirection = directionFromYaw(player.getLocation().getYaw());
        state.tunnelSectionSize = getConfiguredTunnelSectionSize();
        state.tunnelHeight = getConfiguredTunnelHeight();
        state.tunnelSectionsMined = 0;
        state.maxTunnelSections = plugin.getConfig().getInt("mineur.tunnel.max-sections", 0);

        selections.remove(ownerId);

        sessions.add(state);
        registerOwnerSession(state, true);
        startRuntime(state, true);
        saveAllSessions();

        player.sendMessage(CMD_PREFIX + ChatColor.GREEN + "Mineur lancé pour une zone de " + width + "x" + length + ".");
        player.sendMessage(CMD_PREFIX + ChatColor.GRAY + "Mineurs actifs: " + ChatColor.GREEN
                + getSessionsForOwner(ownerId).size() + ChatColor.GRAY + "/" + ChatColor.GREEN + maxMines + ChatColor.GRAY + ".");
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
            String status = state.paused ? ChatColor.YELLOW + "en pause" : ChatColor.GREEN + "actif";
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
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Spécifie une vitesse: slow, normal ou fast.");
            return;
        }

        MiningSessionState state = requireSelectedSession(player);
        if (state == null) {
            return;
        }

        MiningSpeed speed = parseSpeed(args[1]);
        if (speed == null) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Vitesse inconnue: " + args[1]);
            return;
        }

        state.speed = speed;
        RuntimeSession runtime = runtimeOf(state.id);
        if (runtime != null && !state.paused) {
            restartLoop(runtime);
        }
        saveAllSessions();
        player.sendMessage(CMD_PREFIX + ChatColor.GREEN + "Vitesse du mineur réglée sur " + speed.name().toLowerCase(Locale.ROOT) + ".");
    }

    private void handlePattern(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Spécifie un pattern: quarry, branch, tunnel ou vein_first.");
            return;
        }

        MiningSessionState state = requireSelectedSession(player);
        if (state == null) {
            return;
        }

        MiningPattern pattern = parsePattern(args[1]);
        if (pattern == null) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Pattern inconnu: " + args[1]);
            return;
        }

        state.pattern = pattern;
        state.chainTunnelAfterQuarry = false;
        state.infiniteTunnel = false;

        if (pattern == MiningPattern.TUNNEL) {
            state.tunnelDirection = directionFromYaw(player.getLocation().getYaw());
            state.tunnelHeight = getConfiguredTunnelHeight();
            prepareTunnelCursor(state, state.base, state.width, state.length, state.tunnelHeight);
            player.sendMessage(CMD_PREFIX + ChatColor.GRAY + "Direction du tunnel: "
                    + ChatColor.AQUA + formatDirection(state.tunnelDirection) + ChatColor.GRAY + ".");
        } else {
            state.cursor = new MiningCursor(state.base, state.width, state.length);
            state.cursor.y = state.base.getBlockY();
            state.cursor.minY = state.base.getBlockY();
            state.cursor.height = 1;
        }

        saveAllSessions();
        player.sendMessage(CMD_PREFIX + ChatColor.GREEN + "Pattern défini sur " + pattern.name().toLowerCase(Locale.ROOT) + ".");

        RuntimeSession runtime = runtimeOf(state.id);
        if (runtime != null && !state.paused) {
            restartLoop(runtime);
        }
    }

    private void handlePause(Player player, boolean pause) {
        MiningSessionState state = requireSelectedSession(player);
        if (state == null) {
            return;
        }

        RuntimeSession runtime = runtimeOf(state.id);
        state.paused = pause;
        if (runtime != null) {
            if (pause) {
                if (runtime.loop != null) {
                    runtime.loop.cancel();
                    runtime.loop = null;
                }
            } else {
                restartLoop(runtime);
            }
        }
        saveAllSessions();
        player.sendMessage(CMD_PREFIX + ChatColor.GREEN + (pause ? "Mineur mis en pause." : "Mineur relancé."));
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
        player.sendMessage(ChatColor.GRAY + " • Curseur Y : " + cursorY + " / stop " + getStopY() + ".");
        player.sendMessage(ChatColor.GRAY + " • Vitesse : " + state.speed.name().toLowerCase(Locale.ROOT));
        player.sendMessage(ChatColor.GRAY + " • Pattern : " + state.pattern.name().toLowerCase(Locale.ROOT));
        if (state.pattern == MiningPattern.TUNNEL || state.infiniteTunnel) {
            player.sendMessage(ChatColor.GRAY + " • Tunnel : direction " + ChatColor.AQUA + formatDirection(state.tunnelDirection)
                    + ChatColor.GRAY + ", hauteur " + ChatColor.AQUA + Math.max(1, state.tunnelHeight));
        }
        player.sendMessage(ChatColor.GRAY + " • Conteneurs : " + state.containers.size());
        player.sendMessage(ChatColor.GRAY + " • Joueurs autorisés : " + ChatColor.GREEN + state.trusted.size());
        player.sendMessage(ChatColor.GRAY + " • Statut : " + (state.paused ? ChatColor.YELLOW + "en pause" : ChatColor.GREEN + "actif"));
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
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Seul le propriétaire peut modifier les autorisations de cette mine.");
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
            if (offline.getUniqueId() == null) {
                player.sendMessage(CMD_PREFIX + ChatColor.RED + "Joueur introuvable : " + args[1]);
                return;
            }
            targetId = offline.getUniqueId();
            targetName = offline.getName() != null ? offline.getName() : args[1];
        }

        if (Objects.equals(targetId, state.owner)) {
            player.sendMessage(CMD_PREFIX + ChatColor.YELLOW + "Ce joueur est déjà propriétaire de la mine.");
            return;
        }

        if (state.trusted.add(targetId)) {
            saveAllSessions();
            player.sendMessage(CMD_PREFIX + ChatColor.GREEN + targetName + " est autorisé à interagir avec ton mineur.");
        } else {
            player.sendMessage(CMD_PREFIX + ChatColor.YELLOW + targetName + " était déjà autorisé.");
        }
    }

    private void onStorageBlocked(MiningSessionState state) {
        if (state.waitingStorage) {
            return;
        }
        state.waitingStorage = true;
        saveAllSessions();
        notifyOwner(state.owner, ChatColor.RED + "Stockage plein : le mineur attend qu'un coffre soit vidé.");

        RuntimeSession runtime = runtimeOf(state.id);
        if (runtime != null && runtime.miner != null && !runtime.miner.isDead()) {
            if (runtime.storageHologram == null) {
                runtime.storageHologram = new Hologram();
            }
            runtime.storageHologram.show(runtime.miner.getLocation(), ChatColor.RED + "Stockage plein");
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
        ItemStack stick = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stick.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(SELECTOR_NAME);
            stick.setItemMeta(meta);
        }
        player.getInventory().addItem(stick);
        selections.put(player.getUniqueId(), new Selection());
        player.sendMessage(CMD_PREFIX + ChatColor.GREEN + "Tu as reçu le bâton de sélection.");
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }

        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        ItemStack item = event.getItem();
        if (item != null && item.getType() == Material.STICK) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && SELECTOR_NAME.equals(meta.getDisplayName())) {
                event.setCancelled(true);
                Selection selection = selections.computeIfAbsent(player.getUniqueId(), id -> new Selection());

                if (selection.getCorner1() == null) {
                    selection.setCorner1(clicked);
                    player.sendMessage(CMD_PREFIX + ChatColor.AQUA + "Premier coin: " + coords(clicked));
                } else if (selection.getCorner2() == null) {
                    selection.setCorner2(clicked);
                    player.sendMessage(CMD_PREFIX + ChatColor.AQUA + "Second coin: " + coords(clicked));
                    if (selection.getCorner1().getY() != selection.getCorner2().getY()) {
                        player.sendMessage(CMD_PREFIX + ChatColor.RED + "Les blocs doivent être au même Y.");
                        selection.setCorner2(null);
                    } else {
                        createMineFromSelection(player);
                    }
                } else {
                    selection.setCorner1(clicked);
                    selection.setCorner2(null);
                    player.sendMessage(CMD_PREFIX + ChatColor.AQUA + "Sélection réinitialisée. Premier coin: " + coords(clicked));
                }
                return;
            }
        }

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            MiningSessionState protectedSession = findProtectedContainerSession(clicked);
            if (protectedSession != null && !isAuthorizedForSession(player, protectedSession)) {
                event.setCancelled(true);
                player.sendMessage(CMD_PREFIX + ChatColor.RED + "Ce stockage appartient à un mineur protégé.");
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block broken = event.getBlock();
        MiningSessionState protectedSession = findProtectedContainerSession(broken);
        if (protectedSession != null && !isAuthorizedForSession(event.getPlayer(), protectedSession)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(CMD_PREFIX + ChatColor.RED + "Ce conteneur appartient à un mineur protégé.");
            return;
        }

        for (RuntimeSession runtime : new ArrayList<>(runtimes.values())) {
            MiningSessionState state = runtime.state;
            if (!broken.getWorld().getUID().equals(state.worldUid)) {
                continue;
            }

            boolean removed = false;
            Iterator<Location> iterator = runtime.containerLocations.iterator();
            while (iterator.hasNext()) {
                Location location = iterator.next();
                if (isSameBlock(location, broken)) {
                    iterator.remove();
                    removeContainerVector(state, location);
                    removed = true;
                }
            }

            if (removed) {
                runtime.router = new InventoryRouter(resolveContainerBlocks(runtime));
                saveAllSessions();
                notifyOwner(state.owner, ChatColor.RED + "Un conteneur du mineur a été cassé.");
                if (runtime.containerLocations.isEmpty()) {
                    notifyOwner(state.owner, ChatColor.RED + "Tous les coffres ont été détruits, arrêt de la session.");
                    stopSession(state.id, true, null);
                }
                break;
            }
        }
    }

    @EventHandler
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

    @EventHandler
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (findProtectedContainerSession(event.getSource().getHolder()) != null
                || findProtectedContainerSession(event.getDestination().getHolder()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> findProtectedContainerSession(block) != null);
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> findProtectedContainerSession(block) != null);
    }

    public void saveAllSessions() {
        sessionStore.saveAll(sessions);
    }

    public void loadSavedSessions() {
        for (RuntimeSession runtime : runtimes.values()) {
            runtime.stop();
        }
        runtimes.clear();
        sessions.clear();
        ownerSessions.clear();
        selectedSessions.clear();
        sessions.addAll(sessionStore.load());
        for (MiningSessionState state : sessions) {
            registerOwnerSession(state, false);
            startRuntime(state, false);
        }
        plugin.getLogger().info("Mineur : " + sessions.size() + " session(s) rechargée(s).");
    }

    public void stopAllSessions() {
        for (RuntimeSession runtime : runtimes.values()) {
            runtime.stop();
        }
        runtimes.clear();
        sessions.clear();
        ownerSessions.clear();
        selectedSessions.clear();
    }

    private void startRuntime(MiningSessionState state, boolean freshlyCreated) {
        RuntimeSession previous = runtimes.remove(state.id);
        if (previous != null) {
            previous.stop();
        }
        RuntimeSession runtime = new RuntimeSession(state);
        runtimes.put(state.id, runtime);

        boolean allowBlockPlacement = allowMinerBlockPlacement();

        clearZoneForState(state);
        registerChunks(state, runtime);
        if (allowBlockPlacement) {
            ensureFrame(state);
        }
        ensureContainers(state, runtime, freshlyCreated);
        runtime.decoration = allowBlockPlacement ? new DecorationDelegate(state) : null;
        runtime.miner = spawnMiner(state);
        ensureGolems(runtime);
        runtime.router = new InventoryRouter(resolveContainerBlocks(runtime));

        if (!state.paused) {
            restartLoop(runtime);
        }
    }

    private MiningSessionState findSessionById(UUID sessionId) {
        for (MiningSessionState state : sessions) {
            if (state.id.equals(sessionId)) {
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
        if (owned.size() == 1) {
            setSelectedSession(state.owner, owned.get(0));
            return;
        }
        for (UUID remainingId : owned) {
            MiningSessionState remaining = findSessionById(remainingId);
            if (remaining != null) {
                remaining.selected = false;
            }
        }
        selectedSessions.remove(state.owner);
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
            if (runtime != null && !state.paused) {
                restartLoop(runtime);
            }
        }
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
        return jobManager != null ? jobManager.getMaxMinesForPlayer(ownerId) : 1;
    }

    private Integer getNextMineUnlockLevel(UUID ownerId) {
        JobManager jobManager = getJobManager();
        return jobManager != null ? jobManager.getNextMineUnlockLevelForPlayer(ownerId) : null;
    }

    private double getOwnerSpeedMultiplier(UUID ownerId) {
        JobManager jobManager = getJobManager();
        return jobManager != null ? jobManager.getMiningSpeedMultiplier(ownerId) : 1.0D;
    }

    private String formatDecimal(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001D) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
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
        for (Location location : runtime.containerLocations) {
            blocks.add(location.getBlock());
        }
        return blocks;
    }

    private boolean canAdministrateSession(Player player, MiningSessionState state) {
        return player != null && state != null
                && (Objects.equals(player.getUniqueId(), state.owner) || player.hasPermission("mineplugin.mineur.admin"));
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

        for (MiningSessionState state : sessions) {
            if (!block.getWorld().getUID().equals(state.worldUid)) {
                continue;
            }
            for (Vector vector : state.containers) {
                if (vector.getBlockX() == block.getX()
                        && vector.getBlockY() == block.getY()
                        && vector.getBlockZ() == block.getZ()) {
                    return state;
                }
            }
        }

        PersistentDataContainer data = container.getPersistentDataContainer();
        String sessionId = data.get(containerSessionKey, PersistentDataType.STRING);
        if (sessionId != null && !sessionId.isEmpty()) {
            try {
                MiningSessionState byId = findSessionById(UUID.fromString(sessionId));
                if (byId != null) {
                    return byId;
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid metadata, ignore and fall back to owner metadata.
            }
        }

        String ownerId = data.get(containerOwnerKey, PersistentDataType.STRING);
        if (ownerId == null || ownerId.isEmpty()) {
            return null;
        }
        try {
            UUID owner = UUID.fromString(ownerId);
            for (MiningSessionState state : sessions) {
                if (Objects.equals(state.owner, owner)) {
                    return state;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid metadata.
        }
        return null;
    }

    private void clearZoneForState(MiningSessionState state) {
        Location base = state.base;
        World world = base.getWorld();
        int minX = base.getBlockX() - 2;
        int maxX = base.getBlockX() + state.width + 2;
        int minZ = base.getBlockZ() - 2;
        int maxZ = base.getBlockZ() + state.length + 2;

        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                world.getChunkAt(cx, cz).load();
            }
        }

        List<String> names = List.of("Mineur", "Golem de minage");
        for (Entity entity : world.getEntities()) {
            String custom = entity.getCustomName();
            if (custom == null) {
                continue;
            }
            if (!names.contains(ChatColor.stripColor(custom))) {
                continue;
            }
            Location loc = entity.getLocation();
            if (loc.getBlockX() >= minX && loc.getBlockX() <= maxX
                    && loc.getBlockZ() >= minZ && loc.getBlockZ() <= maxZ) {
                entity.remove();
            }
        }
    }

    private void registerChunks(MiningSessionState state, RuntimeSession runtime) {
        World world = state.base.getWorld();
        if (world == null) {
            return;
        }

        MiningCursor active = state.cursor;
        int minX = active != null ? active.minX : state.base.getBlockX();
        int maxX = minX + Math.max(1, active != null ? active.width : state.width) - 1;
        int minZ = active != null ? active.minZ : state.base.getBlockZ();
        int maxZ = minZ + Math.max(1, active != null ? active.length : state.length) - 1;

        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                Chunk chunk = world.getChunkAt(cx, cz);
                if (runtime.ticketChunks.add(chunk)) {
                    chunk.addPluginChunkTicket(plugin);
                }
            }
        }
    }

    private void ensureFrame(MiningSessionState state) {
        World world = state.base.getWorld();
        int baseX = state.base.getBlockX();
        int baseY = state.base.getBlockY();
        int baseZ = state.base.getBlockZ();

        int x1 = baseX - 1;
        int x2 = baseX + state.width;
        int z1 = baseZ - 1;
        int z2 = baseZ + state.length;

        for (int x = x1; x <= x2; x++) {
            world.getBlockAt(x, baseY, z1).setType(Material.STONE_BRICKS, false);
            world.getBlockAt(x, baseY, z2).setType(Material.STONE_BRICKS, false);
        }
        for (int z = z1; z <= z2; z++) {
            world.getBlockAt(x1, baseY, z).setType(Material.STONE_BRICKS, false);
            world.getBlockAt(x2, baseY, z).setType(Material.STONE_BRICKS, false);
        }
    }

    private void ensureContainers(MiningSessionState state, RuntimeSession runtime, boolean freshlyCreated) {
        runtime.containerLocations.clear();
        World world = state.base.getWorld();
        if (world == null) {
            return;
        }

        state.containers.clear();

        if (!state.useBarrelMaster) {
            List<Location> storage = computeStorageLocations(state);
            for (Location location : storage) {
                Block block = world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
                if (block.getType() != Material.CHEST) {
                    block.setType(Material.CHEST, false);
                }
                runtime.containerLocations.add(block.getLocation());
                state.containers.add(block.getLocation().toVector());
                markContainerOwner(block, state);
            }
            return;
        }

        int radius = 6;
        int baseX = state.base.getBlockX();
        int baseY = state.base.getBlockY();
        int baseZ = state.base.getBlockZ();

        for (int x = baseX - radius; x <= baseX + radius; x++) {
            for (int y = baseY - 2; y <= baseY + 2; y++) {
                for (int z = baseZ - radius; z <= baseZ + radius; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.BARREL) {
                        runtime.containerLocations.add(block.getLocation());
                        state.containers.add(block.getLocation().toVector());
                        markContainerOwner(block, state);
                    }
                }
            }
        }
    }

    private List<Location> computeStorageLocations(MiningSessionState state) {
        List<Location> locations = new ArrayList<>();
        World world = state.base.getWorld();
        if (world == null) {
            return locations;
        }
        int baseX = state.base.getBlockX();
        int baseY = state.base.getBlockY();
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
                locations.add(new Location(world, point[0], baseY, point[1]));
            }
        }
        return locations;
    }

    private void markContainerOwner(Block block, MiningSessionState state) {
        if (state == null || state.owner == null) {
            return;
        }
        if (!(block.getState() instanceof Container container)) {
            return;
        }
        PersistentDataContainer data = container.getPersistentDataContainer();
        data.set(containerOwnerKey, PersistentDataType.STRING, state.owner.toString());
        data.set(containerSessionKey, PersistentDataType.STRING, state.id.toString());
        container.update(true);
    }

    private Villager spawnMiner(MiningSessionState state) {
        World world = Bukkit.getWorld(state.worldUid);
        Location spawn = state.base.clone().add(state.width / 2.0, 0, state.length / 2.0);
        Villager villager = (Villager) world.spawnEntity(spawn, EntityType.VILLAGER);
        villager.setCustomName(ChatColor.GOLD + "Mineur");
        villager.setCustomNameVisible(true);
        villager.setProfession(Villager.Profession.ARMORER);
        villager.setAI(false);

        ItemStack pickaxe = new ItemStack(Material.IRON_PICKAXE);
        ItemStack helmet = new ItemStack(Material.LEATHER_HELMET);
        helmet.editMeta(meta -> ((LeatherArmorMeta) meta).setColor(org.bukkit.Color.ORANGE));

        EntityEquipment equipment = villager.getEquipment();
        if (equipment != null) {
            equipment.setItemInMainHand(pickaxe);
            equipment.setItemInMainHandDropChance(0f);
            equipment.setHelmet(helmet);
            equipment.setHelmetDropChance(0f);
        }

        state.minerY = spawn.getY();
        return villager;
    }

    private void ensureGolems(RuntimeSession runtime) {
        runtime.golems.removeIf(golem -> golem.getGolem() == null || golem.getGolem().isDead());
        while (runtime.golems.size() < 2) {
            Location center = runtime.state.base.clone().add(runtime.state.width / 2.0, 0, runtime.state.length / 2.0);
            double radius = Math.max(1.5, Math.min(runtime.state.width, runtime.state.length) / 2.0);
            Golem golem = new Golem(plugin, center, radius, true);
            golem.getGolem().setCustomName(ChatColor.GOLD + "Golem de minage");
            golem.getGolem().setCustomNameVisible(true);
            runtime.golems.add(golem);
        }
    }

    private void restartLoop(RuntimeSession runtime) {
        if (runtime.loop != null) {
            runtime.loop.cancel();
        }
        if (runtime.state.cursor == null) {
            runtime.state.cursor = new MiningCursor(runtime.state.base, runtime.state.width, runtime.state.length);
        }
        runtime.router = new InventoryRouter(resolveContainerBlocks(runtime));
        registerChunks(runtime.state, runtime);
        World world = runtime.state.base.getWorld();
        MiningIterator iterator = createIteratorFor(world, runtime.state, runtime.state.cursor);
        double progressPerTick = runtime.state.speed.progressPerTick(getOwnerSpeedMultiplier(runtime.state.owner));
        RuntimeSession currentRuntime = runtime;
        runtime.loop = new MiningLoop(
                plugin,
                runtime.state,
                iterator,
                runtime.router,
                runtime.miner,
                block -> {
                    if (currentRuntime.decoration != null) {
                        currentRuntime.decoration.afterBlock(block);
                    }
                },
                () -> onLoopCompletion(currentRuntime.state),
                () -> onStorageBlocked(currentRuntime.state),
                () -> onStorageFreed(currentRuntime.state),
                runtime.state.waitingStorage,
                progressPerTick
        );
        runtime.loop.runTaskTimer(plugin, 1L, 1L);
    }

    private void onLoopCompletion(MiningSessionState state) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            RuntimeSession runtime = runtimeOf(state.id);
            if (runtime == null) {
                return;
            }
            if (state.paused) {
                return;
            }
            if (runtime.miner == null || runtime.miner.isDead()) {
                runtime.miner = spawnMiner(state);
                ensureGolems(runtime);
                restartLoop(runtime);
                return;
            }
            if (!hasRemainingBlocks(state)) {
                notifyOwner(state.owner, ChatColor.GREEN + "Le mineur a terminé son chantier.");
                stopSession(state.id, true, null);
                return;
            }
            ensureGolems(runtime);
            restartLoop(runtime);
        });
    }

    private boolean hasRemainingBlocks(MiningSessionState state) {
        MiningCursor cursorSnapshot = state.cursor != null
                ? state.cursor.copy()
                : new MiningCursor(state.base, state.width, state.length);
        World world = state.base.getWorld();
        MiningIterator iterator = createIteratorFor(world, state, cursorSnapshot);
        while (iterator.hasNext()) {
            Block block = iterator.next();
            if (block != null) {
                return true;
            }
        }

        if (state.pattern == MiningPattern.QUARRY && state.chainTunnelAfterQuarry) {
            if (initializeTunnelPhase(state)) {
                return true;
            }
        }

        if (state.pattern == MiningPattern.TUNNEL && state.infiniteTunnel) {
            if (extendTunnelSection(state)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Initialise le tunnel infini une fois la carrière terminée.
     * On place un premier tronçon au fond de la carrière, collé sur un côté,
     * dans une direction aléatoire.
     */
    private boolean initializeTunnelPhase(MiningSessionState state) {
        World world = state.base != null ? state.base.getWorld() : null;
        if (world == null) {
            return false;
        }

        int tunnelSize = getConfiguredTunnelSectionSize();
        int tunnelHeight = getConfiguredTunnelHeight();
        int stopY = getStopY();
        int tunnelY = Math.max(world.getMinHeight(), stopY);

        int centerX = state.base.getBlockX() + Math.max(state.width, 1) / 2;
        int centerZ = state.base.getBlockZ() + Math.max(state.length, 1) / 2;

        BlockFace[] faces = {
                BlockFace.NORTH,
                BlockFace.SOUTH,
                BlockFace.EAST,
                BlockFace.WEST
        };
        BlockFace face = faces[ThreadLocalRandom.current().nextInt(faces.length)];

        int minX;
        int minZ;

        switch (face) {
            case NORTH -> {
                minX = centerX - tunnelSize / 2;
                minZ = state.base.getBlockZ() - tunnelSize;
            }
            case SOUTH -> {
                minX = centerX - tunnelSize / 2;
                minZ = state.base.getBlockZ() + state.length;
            }
            case WEST -> {
                minX = state.base.getBlockX() - tunnelSize;
                minZ = centerZ - tunnelSize / 2;
            }
            case EAST -> {
                minX = state.base.getBlockX() + state.width;
                minZ = centerZ - tunnelSize / 2;
            }
            default -> {
                minX = centerX - tunnelSize / 2;
                minZ = state.base.getBlockZ() + state.length;
            }
        }

        Location tunnelBase = new Location(world, minX, tunnelY, minZ);
        state.pattern = MiningPattern.TUNNEL;
        state.chainTunnelAfterQuarry = false;
        state.infiniteTunnel = true;
        state.tunnelDirection = face;
        state.tunnelSectionSize = tunnelSize;
        state.tunnelHeight = tunnelHeight;
        state.tunnelSectionsMined = 0;
        state.maxTunnelSections = plugin.getConfig().getInt("mineur.tunnel.max-sections", 0);
        prepareTunnelCursor(state, tunnelBase, tunnelSize, tunnelSize, tunnelHeight);
        state.minerY = tunnelY;

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
        if (state.cursor == null || state.tunnelDirection == null) {
            return false;
        }

        World world = state.base != null ? state.base.getWorld() : null;
        if (world == null) {
            return false;
        }

        state.tunnelSectionsMined++;
        if (state.maxTunnelSections > 0
                && state.tunnelSectionsMined >= state.maxTunnelSections) {
            state.infiniteTunnel = false;
            saveAllSessions();
            return false;
        }

        int section = state.tunnelSectionSize > 0 ? state.tunnelSectionSize : getConfiguredTunnelSectionSize();
        int height = state.tunnelHeight > 0 ? state.tunnelHeight : getConfiguredTunnelHeight();

        int x = state.cursor.minX;
        int y = Math.max(world.getMinHeight(), state.cursor.minY);
        int z = state.cursor.minZ;

        switch (state.tunnelDirection) {
            case NORTH -> z -= section;
            case SOUTH -> z += section;
            case WEST -> x -= section;
            case EAST -> x += section;
            default -> z += section;
        }

        Location newBase = new Location(world, x, y, z);
        prepareTunnelCursor(state, newBase, section, section, height);

        saveAllSessions();
        return true;
    }

    private void stopSession(UUID sessionId, boolean removeState, Player issuer) {
        RuntimeSession runtime = runtimes.remove(sessionId);
        if (runtime != null) {
            runtime.stop();
        }
        MiningSessionState state = findSessionById(sessionId);
        if (state != null) {
            if (removeState) {
                sessions.remove(state);
            }
            unregisterOwnerSession(state);
            selectedSessions.entrySet().removeIf(entry -> Objects.equals(entry.getValue(), state.id));
        }
        saveAllSessions();
        if (issuer != null) {
            issuer.sendMessage(CMD_PREFIX + ChatColor.YELLOW + "Session arrêtée.");
        }
    }

    private boolean isWorldAllowed(World world) {
        List<String> allowed = plugin.getConfig().getStringList("mineur.allowed-worlds");
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        return allowed.contains(world.getName());
    }

    private int getStopY() {
        return plugin.getConfig().getInt("mineur.stop-at-y", -58);
    }

    private MiningIterator createIteratorFor(World world,
                                             MiningSessionState state,
                                             MiningCursor cursor) {
        if (world == null) {
            throw new IllegalStateException("World null dans createIteratorFor");
        }
        if (state == null) {
            throw new IllegalStateException("Session null dans createIteratorFor");
        }
        if (cursor == null) {
            cursor = new MiningCursor(state.base, state.width, state.length);
            state.cursor = cursor;
        }

        int stopY = getStopY();
        return switch (state.pattern) {
            case QUARRY -> new QuarryIterator(world, cursor, stopY);
            case BRANCH -> {
                int spacing = plugin.getConfig().getInt("mineur.branch.spacing", 6);
                int galleryWidth = plugin.getConfig().getInt("mineur.branch.gallery-width", 3);
                yield new BranchIterator(world, cursor, stopY, spacing, galleryWidth);
            }
            case TUNNEL -> {
                int height = state.tunnelHeight > 0 ? state.tunnelHeight : getConfiguredTunnelHeight();
                ensureTunnelCursorDefaults(cursor, height);
                yield new TunnelIterator(world, cursor, height);
            }
            case VEIN_FIRST -> {
                int scanRadius = Math.max(0, plugin.getConfig().getInt("mineur.vein.scan-radius", 5));
                int maxBlocks = Math.max(1, plugin.getConfig().getInt("mineur.vein.max-blocks", 96));
                MiningIterator delegate = new QuarryIterator(world, cursor, stopY);
                yield new VeinFirstIterator(world, delegate, scanRadius, maxBlocks);
            }
        };
    }

    private void prepareTunnelCursor(MiningSessionState state, Location tunnelBase, int width, int length, int height) {
        int safeWidth = Math.max(1, width);
        int safeLength = Math.max(1, length);
        int safeHeight = Math.max(1, height);
        MiningCursor cursor = new MiningCursor(tunnelBase, safeWidth, safeLength);
        cursor.minY = tunnelBase.getBlockY();
        cursor.y = tunnelBase.getBlockY();
        cursor.height = safeHeight;
        state.cursor = cursor;
        state.tunnelHeight = safeHeight;
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
        return Math.max(1, plugin.getConfig().getInt("mineur.tunnel.section-size", 10));
    }

    private int getConfiguredTunnelHeight() {
        return Math.max(1, plugin.getConfig().getInt("mineur.tunnel.height", 3));
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

    private boolean allowMinerBlockPlacement() {
        return plugin.getConfig().getBoolean("mineur.allow-block-placement", false);
    }

    private MiningPattern getDefaultPattern() {
        String value = plugin.getConfig().getString("mineur.default.pattern", "QUARRY");
        try {
            return MiningPattern.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return MiningPattern.QUARRY;
        }
    }

    private MiningSpeed getDefaultSpeed() {
        String value = plugin.getConfig().getString("mineur.default.speed", "FAST");
        MiningSpeed speed = parseSpeed(value);
        return speed != null ? speed : MiningSpeed.NORMAL;
    }

    private MiningSpeed parseSpeed(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        return switch (v) {
            case "slow", "lent" -> MiningSpeed.SLOW;
            case "normal" -> MiningSpeed.NORMAL;
            case "fast", "rapide" -> MiningSpeed.FAST;
            default -> {
                try {
                    yield MiningSpeed.valueOf(value.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    yield null;
                }
            }
        };
    }

    private MiningPattern parsePattern(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        return switch (v) {
            case "quarry", "carriere" -> MiningPattern.QUARRY;
            case "branch", "branche" -> MiningPattern.BRANCH;
            case "tunnel" -> MiningPattern.TUNNEL;
            case "vein_first", "veine", "vein", "veine_first" -> MiningPattern.VEIN_FIRST;
            default -> {
                try {
                    yield MiningPattern.valueOf(value.toUpperCase(Locale.ROOT));
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

    private final class RuntimeSession {
        private final MiningSessionState state;
        private final Set<Chunk> ticketChunks = new HashSet<>();
        private final List<Location> containerLocations = new ArrayList<>();
        private final List<Golem> golems = new ArrayList<>();
        private Villager miner;
        private MiningLoop loop;
        private InventoryRouter router;
        private DecorationDelegate decoration;
        private Hologram storageHologram;

        RuntimeSession(MiningSessionState state) {
            this.state = state;
        }

        void stop() {
            if (loop != null) {
                loop.cancel();
            }
            if (miner != null && !miner.isDead()) {
                miner.remove();
            }
            for (Golem golem : golems) {
                golem.remove();
            }
            golems.clear();
            if (storageHologram != null) {
                storageHologram.hide();
            }
            for (Chunk chunk : ticketChunks) {
                chunk.removePluginChunkTicket(plugin);
            }
            ticketChunks.clear();
        }
    }

    private final class DecorationDelegate {
        private final MiningSessionState state;
        private final int supportSpacing;
        private final int layerBlockCount;
        private final int torchLayerInterval;
        private final int ladderX;
        private final int ladderZ;
        private final int ladderSupportX;
        private final BlockFace ladderFacing = BlockFace.EAST;
        private final int torchX;
        private final int torchSupportZ;
        private final BlockFace torchFacing = BlockFace.SOUTH;
        private final int depthMarkerInterval = 5;
        private int minedBlocks = 0;
        private int blocksInLayer = 0;
        private int completedLayers = 0;
        private int currentLayerY;

        DecorationDelegate(MiningSessionState state) {
            this.state = state;
            this.supportSpacing = Math.max(0, plugin.getConfig().getInt("mineur.default.supports-every", 8));
            int safeWidth = Math.max(1, state.width);
            int safeLength = Math.max(1, state.length);
            this.layerBlockCount = Math.max(1, safeWidth * safeLength);
            this.torchLayerInterval = Math.max(1, plugin.getConfig().getInt("mineur.default.torch-layers", 4));
            this.ladderX = state.base.getBlockX() + Math.max(state.width - 1, 0);
            this.ladderZ = state.base.getBlockZ() + Math.max(state.length, 1) / 2;
            this.ladderSupportX = this.ladderX + 1;
            this.torchX = state.base.getBlockX() + Math.max(state.width, 1) / 2;
            this.torchSupportZ = state.base.getBlockZ() - 1;
            this.currentLayerY = state.base.getBlockY();
            prepareInitialAccess(state.base.getWorld());
        }

        void afterBlock(Block block) {
            minedBlocks++;
            blocksInLayer++;
            if (supportSpacing > 0 && minedBlocks % supportSpacing == 0) {
                Block floor = block.getRelative(BlockFace.DOWN);
                SupportBuilder.placeSupportColumn(block.getWorld(), floor, 3);
            }
            if (block.getY() < currentLayerY) {
                currentLayerY = block.getY();
                extendAccessLadder(block.getWorld(), currentLayerY);
                if (shouldPlaceDepthMarker(currentLayerY)) {
                    placeDepthMarker(block.getWorld(), currentLayerY);
                }
            }
            if (blocksInLayer >= layerBlockCount) {
                blocksInLayer = 0;
                completedLayers++;
                buildAccessStair(block.getWorld(), state);
                extendAccessLadder(block.getWorld(), block.getY());
                if (completedLayers % torchLayerInterval == 0) {
                    placeWallTorch(block.getWorld(), block.getY());
                }
            }
        }

        private void buildAccessStair(World world, MiningSessionState state) {
            if (state.cursor == null) {
                return;
            }
            Location base = state.base;
            Block stairBlock = world.getBlockAt(base.getBlockX() - 1, state.cursor.y, base.getBlockZ());
            StairBuilder.ensureStair(world, stairBlock, BlockFace.SOUTH, 3);
        }

        private void prepareInitialAccess(World world) {
            if (world == null) {
                return;
            }
            extendAccessLadder(world, currentLayerY);
        }

        private void extendAccessLadder(World world, int targetY) {
            if (world == null) {
                return;
            }
            int minY = Math.max(targetY, world.getMinHeight());
            int startY = state.base.getBlockY();
            for (int y = startY; y >= minY; y--) {
                ensureSupportBlock(world, ladderSupportX, y, ladderZ);
                Block ladderBlock = world.getBlockAt(ladderX, y, ladderZ);
                ladderBlock.setType(Material.LADDER, false);
                if (ladderBlock.getBlockData() instanceof org.bukkit.block.data.type.Ladder ladderData) {
                    ladderData.setFacing(ladderFacing);
                    ladderBlock.setBlockData(ladderData, false);
                }
            }
        }

        private void placeWallTorch(World world, int y) {
            if (world == null) {
                return;
            }
            ensureSupportBlock(world, torchX, y, torchSupportZ);
            Block support = world.getBlockAt(torchX, y, torchSupportZ);
            TorchPlacer.placeWallTorch(world, support, torchFacing);
        }

        private boolean shouldPlaceDepthMarker(int y) {
            if (depthMarkerInterval <= 0) {
                return false;
            }
            int delta = state.base.getBlockY() - y;
            return delta > 0 && delta % depthMarkerInterval == 0;
        }

        private void placeDepthMarker(World world, int y) {
            if (world == null) {
                return;
            }
            ensureSupportBlock(world, torchX, y, torchSupportZ);
        }

        private void ensureSupportBlock(World world, int x, int y, int z) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.getType().isSolid()) {
                block.setType(Material.STONE_BRICKS, false);
            }
        }
    }
}
