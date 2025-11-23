package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.example.mineur.InventoryRouter;
import org.example.mineur.MiningCursor;
import org.example.mineur.MiningIterator;
import org.example.mineur.MiningLoop;
import org.example.mineur.MiningPattern;
import org.example.mineur.MiningSessionState;
import org.example.mineur.MiningSpeed;
import org.example.mineur.QuarryIterator;
import org.example.mineur.builders.StairBuilder;
import org.example.mineur.builders.SupportBuilder;
import org.example.mineur.builders.TorchPlacer;
import org.example.mineur.store.SessionStore;

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

public class Mineur implements CommandExecutor, Listener {

    private static final String SELECTOR_NAME = ChatColor.GOLD + "Sélecteur de mine";
    private static final String CMD_PREFIX = ChatColor.GRAY + "[Mineur] " + ChatColor.RESET;

    private final JavaPlugin plugin;
    private final SessionStore sessionStore;
    private final NamespacedKey containerOwnerKey;

    private final List<MiningSessionState> sessions = new ArrayList<>();
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<UUID, RuntimeSession> runtimes = new HashMap<>();
    private final Map<UUID, UUID> ownerToSession = new HashMap<>();

    public Mineur(JavaPlugin plugin) {
        this.plugin = plugin;
        this.sessionStore = new SessionStore(plugin.getDataFolder());
        this.containerOwnerKey = new NamespacedKey(plugin, "mineur-owner");

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
        if (args.length > 0) {
            sendUsage(player);
            return true;
        }

        giveMineSelector(player);
        return true;
    }

    private void sendUsage(Player player) {
        player.sendMessage(CMD_PREFIX + ChatColor.YELLOW + "Usage:");
        player.sendMessage(ChatColor.GOLD + "/mineur" + ChatColor.GRAY + " – reçois le bâton et clique deux blocs (même Y) pour lancer la mine");
    }

    private void createMineFromSelection(Player player) {
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

        UUID ownerId = player.getUniqueId();

        Location base = new Location(world, minX, c1.getY(), minZ);
        MiningSessionState state = new MiningSessionState();
        state.worldUid = world.getUID();
        state.base = base;
        state.width = width;
        state.length = length;
        state.cursor = new MiningCursor(base, width, length);
        state.minerY = base.getY();
        state.owner = ownerId;
        state.useBarrelMaster = false;
        state.pattern = getDefaultPattern();
        state.speed = getDefaultSpeed();

        selections.remove(ownerId);

        sessions.add(state);
        ownerToSession.put(ownerId, state.id);
        startRuntime(state, true);
        saveAllSessions();

        player.sendMessage(CMD_PREFIX + ChatColor.GREEN + "Mineur lancé pour une zone de " + width + "x" + length + ".");
    }

    private void handleSpeed(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Spécifie une vitesse: slow, normal ou fast.");
            return;
        }

        MiningSessionState state = findSessionByOwner(player.getUniqueId());
        if (state == null) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Aucune session active.");
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

        MiningSessionState state = findSessionByOwner(player.getUniqueId());
        if (state == null) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Aucune session active.");
            return;
        }

        MiningPattern pattern;
        try {
            pattern = MiningPattern.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Pattern inconnu: " + args[1]);
            return;
        }

        if (pattern != MiningPattern.QUARRY) {
            player.sendMessage(CMD_PREFIX + ChatColor.YELLOW + "Ce pattern n'est pas encore disponible. Mode QUARRY conservé.");
            return;
        }

        state.pattern = pattern;
        saveAllSessions();
        player.sendMessage(CMD_PREFIX + ChatColor.GREEN + "Pattern défini sur QUARRY.");
    }

    private void handlePause(Player player, boolean pause) {
        MiningSessionState state = findSessionByOwner(player.getUniqueId());
        if (state == null) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Aucune session active.");
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
        MiningSessionState state = findSessionByOwner(player.getUniqueId());
        if (state == null) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Aucune session active.");
            return;
        }
        stopSession(state.id, true, player);
    }

    private void handleInfo(Player player) {
        MiningSessionState state = findSessionByOwner(player.getUniqueId());
        if (state == null) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Aucune session active.");
            return;
        }

        World world = Bukkit.getWorld(state.worldUid);
        String worldName = world != null ? world.getName() : "?";
        player.sendMessage(CMD_PREFIX + ChatColor.AQUA + "Session " + state.id + " :");
        player.sendMessage(ChatColor.GRAY + " • Monde : " + worldName);
        player.sendMessage(ChatColor.GRAY + " • Zone : " + state.width + "x" + state.length + " (Y " + state.base.getBlockY() + ")");
        int cursorY = state.cursor != null ? state.cursor.y : state.base.getBlockY();
        player.sendMessage(ChatColor.GRAY + " • Curseur Y : " + cursorY + " / stop " + getStopY() + ".");
        player.sendMessage(ChatColor.GRAY + " • Vitesse : " + state.speed.name().toLowerCase(Locale.ROOT));
        player.sendMessage(ChatColor.GRAY + " • Pattern : " + state.pattern.name().toLowerCase(Locale.ROOT));
        player.sendMessage(ChatColor.GRAY + " • Conteneurs : " + state.containers.size());
        player.sendMessage(ChatColor.GRAY + " • Statut : " + (state.paused ? ChatColor.YELLOW + "en pause" : ChatColor.GREEN + "actif"));
    }

    private void handleTrust(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Spécifie un joueur à autoriser.");
            return;
        }

        MiningSessionState state = findSessionByOwner(player.getUniqueId());
        if (state == null) {
            player.sendMessage(CMD_PREFIX + ChatColor.RED + "Aucune session active.");
            return;
        }

        Player targetOnline = Bukkit.getPlayerExact(args[1]);
        UUID targetId;
        String targetName;
        if (targetOnline != null) {
            targetId = targetOnline.getUniqueId();
            targetName = targetOnline.getName();
        } else {
            var offline = Bukkit.getOfflinePlayer(args[1]);
            if (offline == null || offline.getUniqueId() == null) {
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

        state.trusted.add(targetId);
        saveAllSessions();
        player.sendMessage(CMD_PREFIX + ChatColor.GREEN + targetName + " est autorisé à interagir avec ton mineur.");
    }

    private void onStorageBlocked(MiningSessionState state) {
        if (state.waitingStorage) {
            return;
        }
        state.waitingStorage = true;
        saveAllSessions();
        notifyOwner(state.owner, ChatColor.RED + "Stockage plein : le mineur attend qu'un coffre soit vidé.");
    }

    private void onStorageFreed(MiningSessionState state) {
        if (!state.waitingStorage) {
            return;
        }
        state.waitingStorage = false;
        saveAllSessions();
        notifyOwner(state.owner, ChatColor.GREEN + "Le mineur reprend, de la place a été libérée.");
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
        if (event.getItem() == null || event.getItem().getType() != Material.STICK) {
            return;
        }
        ItemMeta meta = event.getItem().getItemMeta();
        if (meta == null || !SELECTOR_NAME.equals(meta.getDisplayName())) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        Selection selection = selections.computeIfAbsent(player.getUniqueId(), id -> new Selection());
        Block clicked = event.getClickedBlock();

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
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block broken = event.getBlock();
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

    public void saveAllSessions() {
        sessionStore.saveAll(sessions);
    }

    public void loadSavedSessions() {
        sessions.clear();
        sessions.addAll(sessionStore.load());
        for (MiningSessionState state : sessions) {
            if (state.owner != null) {
                ownerToSession.put(state.owner, state.id);
            }
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
        ownerToSession.clear();
    }

    private void startRuntime(MiningSessionState state, boolean freshlyCreated) {
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

    private MiningSessionState findSessionByOwner(UUID ownerId) {
        UUID sessionId = ownerToSession.get(ownerId);
        if (sessionId == null) {
            return null;
        }
        return findSessionById(sessionId);
    }

    private RuntimeSession runtimeOf(UUID sessionId) {
        return runtimes.get(sessionId);
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
        int minX = state.base.getBlockX();
        int maxX = state.base.getBlockX() + state.width - 1;
        int minZ = state.base.getBlockZ();
        int maxZ = state.base.getBlockZ() + state.length - 1;

        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                Chunk chunk = world.getChunkAt(cx, cz);
                chunk.addPluginChunkTicket(plugin);
                runtime.ticketChunks.add(chunk);
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

        List<Location> storage = computeStorageLocations(state);
        state.useBarrelMaster = false;
        state.containers.clear();
        for (Location location : storage) {
            Block block = world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            if (block.getType() != Material.CHEST) {
                block.setType(Material.CHEST, false);
            }
            runtime.containerLocations.add(block.getLocation());
            state.containers.add(block.getLocation().toVector());
            markContainerOwner(block, state.owner);
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

    private void markContainerOwner(Block block, UUID owner) {
        if (owner == null) {
            return;
        }
        if (!(block.getState() instanceof Container container)) {
            return;
        }
        PersistentDataContainer data = container.getPersistentDataContainer();
        data.set(containerOwnerKey, PersistentDataType.STRING, owner.toString());
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
        MiningIterator iterator = new QuarryIterator(runtime.state.base.getWorld(), runtime.state.cursor, getStopY());
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
                runtime.state.waitingStorage
        );
        runtime.loop.runTaskTimer(plugin, 1L, Math.max(1L, runtime.state.speed.ticksPerStage));
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
        MiningIterator iterator = new QuarryIterator(state.base.getWorld(), cursorSnapshot, getStopY());
        while (iterator.hasNext()) {
            Block block = iterator.next();
            if (block != null) {
                return true;
            }
        }
        return false;
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
            if (state.owner != null) {
                ownerToSession.remove(state.owner, sessionId);
            }
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
        try {
            return MiningSpeed.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
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

        private void ensureSupportBlock(World world, int x, int y, int z) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.getType().isSolid()) {
                block.setType(Material.STONE_BRICKS, false);
            }
        }
    }
}
