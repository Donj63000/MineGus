package org.example;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.Tag;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * /foret : micro-forêt automatisée (cadre, saplings, PNJ, golems, coupe haut->bas, capture drops, replant, torches).
 * Mise à jour 2025-10 : comportement "humain", 2x2 (dark oak / spruce / jungle), budget par tick, dépôt fiable.
 *
 * API publique conservée : loadSavedSessions(), saveAllSessions(), stopAllForests().
 */
public final class Foret implements CommandExecutor, Listener {

    /* ══════════════════════ CONSTANTES (faciles à ajuster) ══════════════════════ */
    private static final String FORET_SELECTOR_NAME = ChatColor.GOLD + "Sélecteur de forêt";
    private static final int    MAILLAGE_SAPLINGS   = 6;    // espacement de base
    private static final int    FOREST_HEIGHT       = 26;   // y max géré au dessus du plan
    private static final int    MAX_RACK_HEIGHT     = 6;    // étages de coffres auto
    private static final int    LOOP_PERIOD_TICKS   = 2;    // boucle toutes les 2 ticks (~10 ms)
    private static final int    LOG_BREAK_DELAY     = 6;    // ticks par tronc (délais "humain")
    private static final int    LEAF_BREAK_DELAY    = 2;    // ticks par feuille si "fast leaves"
    private static final int    MAX_BLOCKS_PER_TICK = 14;   // budget de blocs cassés par tick
    private static final int    LIGHT_MIN_LEVEL     = 9;    // si <9, pose une torche
    private static final boolean FAST_LEAVES        = false;// true = casse activement les feuilles, false = laisse la decay
    private static final List<Material> ONE_BY_ONE_SAPLINGS = List.of(
            Material.OAK_SAPLING, Material.BIRCH_SAPLING, Material.SPRUCE_SAPLING,
            Material.JUNGLE_SAPLING, Material.ACACIA_SAPLING, Material.CHERRY_SAPLING
    );
    // 2x2 : dark oak obligatoire, spruce et jungle en version "grande"
    private static final List<Material> TWO_BY_TWO_SAPLINGS = List.of(
            Material.DARK_OAK_SAPLING, Material.SPRUCE_SAPLING, Material.JUNGLE_SAPLING
    );
    private static final Material FRAME = Material.OAK_LOG;
    private static final Material LIGHT = Material.SEA_LANTERN;
    private static final Material JOB_TABLE = Material.FLETCHING_TABLE;
    /* ════════════════════════════════════════════════════════════════════════════ */

    private final JavaPlugin plugin;

    /* sélection par joueur */
    private final Map<UUID, Selection> selections = new HashMap<>();

    /* sessions actives (persistées) */
    private final List<ForestSession> sessions = new ArrayList<>();

    /* persistance */
    private final File forestsFile;
    private final YamlConfiguration forestsYaml;
    private final File forestersFile;
    private final YamlConfiguration forestersYaml;

    private final Map<UUID, ForesterRecord> forestersById = new HashMap<>();
    private final Map<String, ForesterRecord> forestersByHut = new HashMap<>();
    private final Map<String, ForestSession> sessionsByHut = new HashMap<>();
    private final Map<String, AtomicBoolean> spawnLocks = new ConcurrentHashMap<>();
    private final Map<UUID, ForestSession> sessionsByWorkerId = new HashMap<>();
    private final Map<String, IronGolem> guardsByHut = new HashMap<>();

    private BukkitTask missingWorkerTask;

    /* capture des drops durant la coupe */
    private final Set<UUID> activeCapture = new HashSet<>(); // ids de sessions actuellement en coupe

    public Foret(JavaPlugin plugin) {
        this.plugin = plugin;
        Objects.requireNonNull(plugin.getCommand("foret")).setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);

        plugin.getDataFolder().mkdirs();
        forestsFile = new File(plugin.getDataFolder(), "forests.yml");
        forestsYaml = YamlConfiguration.loadConfiguration(forestsFile);
        forestersFile = new File(plugin.getDataFolder(), "foresters.yml");
        forestersYaml = YamlConfiguration.loadConfiguration(forestersFile);
    }

    /* ══════════════════ COMMANDE /foret (identique à ton flux) ══════════════════ */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String lbl, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Commande réservée aux joueurs.");
            return true;
        }
        giveSelector(p);
        selections.put(p.getUniqueId(), new Selection());
        p.sendMessage(ChatColor.GREEN + "Sélectionne deux blocs (même Y) avec le bâton.");
        return true;
    }

    private void giveSelector(Player p) {
        ItemStack stick = new ItemStack(Material.STICK);
        ItemMeta m = stick.getItemMeta();
        m.setDisplayName(FORET_SELECTOR_NAME);
        stick.setItemMeta(m);
        p.getInventory().addItem(stick);
    }

    /* ═════════════════════ EVENTS ═════════════════════ */

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        ItemStack item = e.getItem();
        if (item == null || !item.hasItemMeta()
                || !FORET_SELECTOR_NAME.equals(item.getItemMeta().getDisplayName()))
            return;

        e.setCancelled(true);

        Player p = e.getPlayer();
        Selection sel = selections.get(p.getUniqueId());
        if (sel == null) {
            p.sendMessage(ChatColor.RED + "Refais /foret pour commencer une sélection.");
            return;
        }

        if (sel.corner1 == null) {
            sel.corner1 = e.getClickedBlock();
            p.sendMessage(ChatColor.AQUA + "Coin 1 : " + coord(sel.corner1));
        } else if (sel.corner2 == null) {
            sel.corner2 = e.getClickedBlock();
            p.sendMessage(ChatColor.AQUA + "Coin 2 : " + coord(sel.corner2));
            validateAndCreate(p, sel);
        } else {
            sel.corner1 = e.getClickedBlock();
            sel.corner2 = null;
            p.sendMessage(ChatColor.AQUA + "Coin 1 redéfini : " + coord(sel.corner1));
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        for (ForestSession fs : new ArrayList<>(sessions)) {
            if (fs.isProtectedStructure(b)) {
                e.setCancelled(true);
                return;
            }
            if (fs.removeChestIfMatch(b) && !fs.hasChests()) {
                fs.stop();
                sessions.remove(fs);
                sessionsByHut.remove(fs.getHutId());
                discardForesterRecord(fs.getHutId());
                saveSessions();
            }
        }
    }

    /** Capture propre des drops quand la station casse un bloc. */
    @EventHandler
    public void onBlockDrop(BlockDropItemEvent e) {
        for (ForestSession fs : sessions) {
            if (!activeCapture.contains(fs.id)) continue;
            if (!Objects.equals(fs.w.getUID(), e.getBlock().getWorld().getUID())) continue;
            if (!fs.inBounds(e.getBlock().getLocation())) continue;

            // Récupère les Item entities et envoie dans la "poche" de la session
            for (Item it : e.getItems()) {
                fs.pocket.add(it.getItemStack().clone());
                it.remove();
            }
            e.setCancelled(true);
            break;
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Villager villager) {
                handlePotentialForester(villager);
            } else if (entity instanceof IronGolem golem) {
                handlePotentialGuard(golem);
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onNaturalGolemSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() != EntityType.IRON_GOLEM) return;
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.VILLAGE_DEFENSE) return;
        if (isNearMinegusForester(event.getLocation(), 48)) {
            event.setCancelled(true);
        }
    }

    /* ═════════════════ VALIDATION / CREATION ═════════════════ */
    private void validateAndCreate(Player p, Selection sel) {
        if (sel.corner1.getY() != sel.corner2.getY()) {
            p.sendMessage(ChatColor.RED + "Les deux blocs doivent avoir la même hauteur (Y).");
            sel.corner2 = null;
            return;
        }
        World w = sel.corner1.getWorld();
        int y = sel.corner1.getY();
        int x1 = sel.corner1.getX(), x2 = sel.corner2.getX();
        int z1 = sel.corner1.getZ(), z2 = sel.corner2.getZ();
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        ForestSession fs = new ForestSession(
                w, new Location(w, minX, y, minZ),
                maxX - minX + 1, maxZ - minZ + 1
        );
        registerSession(fs, true);
        saveSessions();

        p.sendMessage(ChatColor.GREEN + "Forêt créée ! (" + fs.width + "×" + fs.length + ")");
        selections.remove(p.getUniqueId());
    }

    /* ══════════════════ PERSISTENCE (interne) ══════════════════ */
    private void saveSessions() {
        forestsYaml.set("forests", null);
        int i = 0;
        for (ForestSession fs : sessions) {
            forestsYaml.createSection("forests." + i++, fs.serialize());
        }
        try {
            forestsYaml.save(forestsFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("[Foret] Erreur de sauvegarde forests.yml");
            ex.printStackTrace();
        }
    }

    private void saveForesters() {
        forestersYaml.set("foresters", null);
        for (ForesterRecord record : forestersById.values()) {
            forestersYaml.createSection("foresters." + record.workerId(), record.serialize());
        }
        try {
            forestersYaml.save(forestersFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("[Foret] Erreur de sauvegarde foresters.yml");
            ex.printStackTrace();
        }
    }

    private void loadSessions() {
        ConfigurationSection root = forestsYaml.getConfigurationSection("forests");
        if (root == null) return;

        int restored = 0;
        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) continue;

            World w = Bukkit.getWorld(UUID.fromString(sec.getString("world", "")));
            if (w == null) continue;

            int x = sec.getInt("x"), y = sec.getInt("y"), z = sec.getInt("z");
            int width = sec.getInt("width"), length = sec.getInt("length");

            ForestSession fs = new ForestSession(w, new Location(w, x, y, z), width, length);

            // file de récolte
            List<String> list = sec.getStringList("harvestQueue");
            if (!list.isEmpty()) {
                for (String s : list) {
                    String[] c = s.split(",");
                    if (c.length == 3) {
                        Block b = w.getBlockAt(
                                Integer.parseInt(c[0]),
                                Integer.parseInt(c[1]),
                                Integer.parseInt(c[2]));
                        fs.bfs.add(b);
                    }
                }
            }
            // replant en attente
            if (sec.contains("replantLocation")) {
                List<Integer> rl = sec.getIntegerList("replantLocation");
                if (rl.size() == 3)
                    fs.replant = w.getBlockAt(rl.get(0), rl.get(1), rl.get(2));
            }

            // recharger les sites de plantation (si présents)
            List<String> sites = sec.getStringList("sites");
            if (!sites.isEmpty()) {
                for (String s : sites) {
                    // format: x;y;z;is2x2;mat
                    String[] c = s.split(";");
                    if (c.length == 5) {
                        int sx = Integer.parseInt(c[0]);
                        int sy = Integer.parseInt(c[1]);
                        int sz = Integer.parseInt(c[2]);
                        boolean two = Boolean.parseBoolean(c[3]);
                        Material mat = Material.matchMaterial(c[4]);
                        if (mat != null) fs.plantSites.add(new PlantSite(new Location(w, sx, sy, sz), two, mat));
                    }
                }
            }

            registerSession(fs, false);
            restored++;
        }
        plugin.getLogger().info("[Foret] " + restored + " forêt(s) restaurée(s).");
        Bukkit.getScheduler().runTask(plugin, this::scanLoadedEntities);
    }

    private void loadForesters() {
        forestersById.clear();
        forestersByHut.clear();
        ConfigurationSection root = forestersYaml.getConfigurationSection("foresters");
        if (root == null) return;

        for (String key : root.getKeys(false)) {
            try {
                UUID workerId = UUID.fromString(key);
                ConfigurationSection sec = root.getConfigurationSection(key);
                if (sec == null) continue;
                ForesterRecord record = ForesterRecord.deserialize(workerId, sec);
                if (record == null) continue;
                registerForester(record);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("[Foret] UUID invalide dans foresters.yml: " + key);
            }
        }
    }

    private void registerForester(ForesterRecord record) {
        forestersById.put(record.workerId(), record);
        forestersByHut.put(record.hutId(), record);
    }

    private void registerSession(ForestSession session, boolean spawnFresh) {
        sessions.add(session);
        sessionsByHut.put(session.getHutId(), session);
        ensureForesterRecord(session);
        session.start(spawnFresh);
        ensureMissingWorkerTask();
    }

    private void ensureMissingWorkerTask() {
        if (missingWorkerTask != null && !missingWorkerTask.isCancelled()) {
            return;
        }
        missingWorkerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkMissingWorkers, 20L * 60, 20L * 60);
    }

    private void cancelMissingWorkerTask() {
        if (missingWorkerTask != null) {
            missingWorkerTask.cancel();
            missingWorkerTask = null;
        }
    }

    private void checkMissingWorkers() {
        for (ForesterRecord record : forestersById.values()) {
            ForestSession session = sessionsByHut.get(record.hutId());
            if (session == null) {
                continue;
            }
            if (session.ensureForesterBinding(false)) {
                session.ensureGuardIntegrity();
                continue;
            }
            requestSpawnForester(session);
        }
    }

    private void requestSpawnForester(ForestSession session) {
        AtomicBoolean lock = spawnLocks.computeIfAbsent(session.getHutId(), id -> new AtomicBoolean());
        if (!lock.compareAndSet(false, true)) {
            return;
        }
        try {
            if (session.ensureForesterBinding(true)) {
                session.ensureGuardIntegrity();
            }
        } finally {
            lock.set(false);
        }
    }


    private void handlePotentialForester(Villager villager) {
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        String type = pdc.get(Keys.workerType(), PersistentDataType.STRING);
        if (!"FORESTER".equals(type)) {
            return;
        }
        String hutId = pdc.get(Keys.hutId(), PersistentDataType.STRING);
        if (hutId == null) {
            return;
        }
        ForestSession session = sessionsByHut.get(hutId);
        if (session == null) {
            return;
        }
        ForesterRecord record = forestersByHut.get(hutId);
        UUID workerId = null;
        String workerRaw = pdc.get(Keys.workerId(), PersistentDataType.STRING);
        if (workerRaw != null) {
            try {
                workerId = UUID.fromString(workerRaw);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (record == null) {
            if (workerId == null) {
                workerId = UUID.randomUUID();
            }
            record = ForesterRecord.fromExisting(workerId, hutId, villager.getWorld().getUID(), session.getHomeLocation(), villager.getUniqueId());
            registerForester(record);
        } else {
            if (workerId == null || !workerId.equals(record.workerId())) {
                workerId = record.workerId();
            }
            record.updateHome(session.getHomeLocation());
        }
        session.attachRecord(record);
        sessionsByWorkerId.put(record.workerId(), session);
        pdc.set(Keys.workerType(), PersistentDataType.STRING, "FORESTER");
        pdc.set(Keys.workerId(), PersistentDataType.STRING, record.workerId().toString());
        pdc.set(Keys.hutId(), PersistentDataType.STRING, hutId);
        villager.addScoreboardTag("minegus.worker");
        villager.addScoreboardTag("minegus.worker.forester");
        session.bindForester(villager);
        session.ensureGuardIntegrity();
    }

    private void handlePotentialGuard(IronGolem golem) {
        PersistentDataContainer pdc = golem.getPersistentDataContainer();
        String hutId = pdc.get(Keys.guardFor(), PersistentDataType.STRING);
        if (hutId == null) {
            return;
        }
        ForestSession session = sessionsByHut.get(hutId);
        if (session == null) {
            return;
        }
        session.bindGuard(golem);
    }

    private void scanLoadedEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                handlePotentialForester(villager);
            }
            for (IronGolem golem : world.getEntitiesByClass(IronGolem.class)) {
                handlePotentialGuard(golem);
            }
        }
    }

    private boolean isNearMinegusForester(Location location, double radius) {
        double radiusSq = radius * radius;
        for (ForestSession session : sessions) {
            Location home = session.getHomeLocation();
            if (home.getWorld() == null || !home.getWorld().equals(location.getWorld())) {
                continue;
            }
            if (home.distanceSquared(location) <= radiusSq) {
                return true;
            }
        }
        return false;
    }


    private void discardForesterRecord(String hutId) {
        ForesterRecord record = forestersByHut.remove(hutId);
        if (record != null) {
            forestersById.remove(record.workerId());
            sessionsByWorkerId.remove(record.workerId());
            saveForesters();
        }
    }

    private ForesterRecord ensureForesterRecord(ForestSession session) {
        String hutId = session.getHutId();
        ForesterRecord existing = forestersByHut.get(hutId);
        if (existing != null) {
            if (existing.updateHome(session.getHomeLocation())) {
                saveForesters();
            }
            session.attachRecord(existing);
            sessionsByWorkerId.put(existing.workerId(), session);
            return existing;
        }
        ForesterRecord record = ForesterRecord.create(session.getHutId(),
                session.w.getUID(),
                session.getHomeLocation());
        registerForester(record);
        session.attachRecord(record);
        sessionsByWorkerId.put(record.workerId(), session);
        saveForesters();
        return record;
    }

    /* ══════════════════ API PUBLIQUE (rétro‑compat) ══════════════════ */
    public void loadSavedSessions() {
        loadForesters();
        loadSessions();
    }

    public void saveAllSessions() {
        saveSessions();
        saveForesters();
    }
    public void stopAllForests() {
        sessions.forEach(fs -> fs.stop(true));
        sessions.clear();
        sessionsByHut.clear();
        sessionsByWorkerId.clear();
        guardsByHut.clear();
        spawnLocks.clear();
        cancelMissingWorkerTask();
    }

    public void pauseAllForests() {
        sessions.forEach(fs -> fs.stop(false));
        sessions.clear();
        sessionsByHut.clear();
        sessionsByWorkerId.clear();
        guardsByHut.clear();
        spawnLocks.clear();
        cancelMissingWorkerTask();
    }

    private static String hutIdFor(World world, int x, int y, int z) {
        return world.getUID() + ":" + x + ":" + y + ":" + z;
    }

    /* ══════════════════ PERSISTENCE DES FORESTIERS ══════════════════ */
    private static final class ForesterRecord {
        private final UUID workerId;
        private final String hutId;
        private final UUID worldId;
        private int homeX;
        private int homeY;
        private int homeZ;
        private UUID entityUuid;

        private ForesterRecord(UUID workerId, String hutId, UUID worldId,
                               int homeX, int homeY, int homeZ, UUID entityUuid) {
            this.workerId = workerId;
            this.hutId = hutId;
            this.worldId = worldId;
            this.homeX = homeX;
            this.homeY = homeY;
            this.homeZ = homeZ;
            this.entityUuid = entityUuid;
        }

        static ForesterRecord create(String hutId, UUID worldId, Location home) {
            UUID workerId = UUID.randomUUID();
            return new ForesterRecord(workerId, hutId, worldId,
                    home.getBlockX(), home.getBlockY(), home.getBlockZ(), null);
        }

        static ForesterRecord fromExisting(UUID workerId, String hutId, UUID worldId, Location home, UUID entityUuid) {
            return new ForesterRecord(workerId, hutId, worldId,
                    home.getBlockX(), home.getBlockY(), home.getBlockZ(), entityUuid);
        }

        static ForesterRecord deserialize(UUID workerId, ConfigurationSection sec) {
            String hutId = sec.getString("hutId");
            String worldStr = sec.getString("world");
            List<Integer> homeList = sec.getIntegerList("home");
            if (hutId == null || worldStr == null || homeList.size() != 3) {
                return null;
            }
            UUID worldId;
            try {
                worldId = UUID.fromString(worldStr);
            } catch (IllegalArgumentException ex) {
                return null;
            }
            UUID entityUuid = null;
            String entityStr = sec.getString("entityUuid");
            if (entityStr != null && !entityStr.isEmpty()) {
                try {
                    entityUuid = UUID.fromString(entityStr);
                } catch (IllegalArgumentException ignored) { }
            }
            return new ForesterRecord(workerId, hutId, worldId,
                    homeList.get(0), homeList.get(1), homeList.get(2), entityUuid);
        }

        Map<String, Object> serialize() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("hutId", hutId);
            map.put("world", worldId.toString());
            map.put("home", List.of(homeX, homeY, homeZ));
            if (entityUuid != null) {
                map.put("entityUuid", entityUuid.toString());
            }
            return map;
        }

        UUID workerId() { return workerId; }
        String hutId() { return hutId; }
        UUID worldId() { return worldId; }

        UUID entityUuid() { return entityUuid; }
        void setEntityUuid(UUID entityUuid) { this.entityUuid = entityUuid; }
        void clearEntityUuid() { this.entityUuid = null; }

        boolean updateHome(Location location) {
            if (location == null) return false;
            int x = location.getBlockX();
            int y = location.getBlockY();
            int z = location.getBlockZ();
            if (x == homeX && y == homeY && z == homeZ) return false;
            this.homeX = x;
            this.homeY = y;
            this.homeZ = z;
            return true;
        }

        Location spawnLocation() {
            World world = Bukkit.getWorld(worldId);
            return new Location(world, homeX + 0.5, homeY + 1, homeZ + 0.5);
        }
    }

    /* ══════════════════ CLASSE SESSION ══════════════════ */
    private final class ForestSession {
        final UUID id = UUID.randomUUID();
        final World w;
        final int x0, y0, z0, width, length;
        final String hutId;

        // structures & logique
        final List<Block> chests = new ArrayList<>();
        final List<PlantSite> plantSites = new ArrayList<>(); // spots 1x1 et 2x2
        final Queue<Block> bfs = new LinkedList<>();
        final List<ItemStack> pocket = new ArrayList<>();
        Block replant = null;

        // entités
        Villager forester;
        IronGolem guard;
        BukkitRunnable loop;
        Chunk loadedChunk;
        Location jobSite;
        ForesterRecord record;

        // travail courant
        TreeJob current;
        int stepDelay = 0; // temporisation "humaine"
        int perTickBudget = 0; // budget restant dans le tick
        int missingForesterCooldown = 0;

        ForestSession(World w, Location origin, int width, int length) {
            this.w = w;
            this.x0 = origin.getBlockX();
            this.y0 = origin.getBlockY();
            this.z0 = origin.getBlockZ();
            this.width = width;
            this.length = length;
            this.hutId = hutIdFor(w, this.x0, this.y0, this.z0);
        }

        String getHutId() { return hutId; }

        Location getHomeLocation() {
            if (jobSite != null) {
                return new Location(w, jobSite.getBlockX(), jobSite.getBlockY(), jobSite.getBlockZ());
            }
            int hx = x0 + width / 2;
            int hz = z0 + length / 2;
            return new Location(w, hx, y0, hz);
        }

        void attachRecord(ForesterRecord record) {
            this.record = record;
        }

        /* ---------- cycle de vie ---------- */
        void start(boolean spawnFresh) {
            loadedChunk = w.getChunkAt(x0 + width / 2, z0 + length / 2);
            loadedChunk.setForceLoaded(true);
            buildFrameGround();
            if (plantSites.isEmpty()) {
                scanExistingTreesAsSites();
            }
            if (plantSites.isEmpty()) {
                placeSaplings(); // génère nos PlantSite 1x1 et 2x2
            }
            createChestsIfMissing();
            ensureForesterBinding(spawnFresh);
            ensureGuardIntegrity();
            startLoop();
        }
        private boolean hasActiveForester() {
            return forester != null && forester.isValid() && !forester.isDead();
        }

        private void bindForester(Villager villager) {
            forester = villager;
            applyForesterMetadata(villager);
            if (record != null) {
                record.setEntityUuid(villager.getUniqueId());
                saveForesters();
            }
        }

        private void clearForesterReference(boolean keepEntityUuid) {
            forester = null;
            if (!keepEntityUuid && record != null) {
                record.clearEntityUuid();
                saveForesters();
            }
        }

        private boolean tryBindExistingForester() {
            if (record == null) return false;
            if (hasActiveForester()) {
                applyForesterMetadata(forester);
                return true;
            }
            if (record.entityUuid() != null) {
                Entity entity = Bukkit.getEntity(record.entityUuid());
                if (entity instanceof Villager v && v.isValid() && !v.isDead()) {
                    handlePotentialForester(v);
                    return hasActiveForester();
                }
            }
            Villager candidate = findForesterCandidate(32.0);
            if (candidate != null) {
                handlePotentialForester(candidate);
                return hasActiveForester();
            }
            return false;
        }

        private Villager findForesterCandidate(double radius) {
            if (jobSite == null) return null;
            for (Entity ent : w.getNearbyEntities(jobSite, radius, 6, radius)) {
                if (ent instanceof Villager villager && matchesForesterMetadata(villager)) {
                    return villager;
                }
            }
            return null;
        }

        private boolean matchesForesterMetadata(Villager villager) {
            PersistentDataContainer pdc = villager.getPersistentDataContainer();
            String type = pdc.get(Keys.workerType(), PersistentDataType.STRING);
            if (!"FORESTER".equals(type)) return false;
            String hut = pdc.get(Keys.hutId(), PersistentDataType.STRING);
            if (hut == null || !hut.equals(hutId)) return false;
            if (record != null) {
                String worker = pdc.get(Keys.workerId(), PersistentDataType.STRING);
                if (worker != null) {
                    try {
                        UUID workerUuid = UUID.fromString(worker);
                        if (!workerUuid.equals(record.workerId())) {
                            return false;
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            return true;
        }

        private void spawnFreshForester() {
            if (record == null) return;
            if (hasActiveForester()) {
                applyForesterMetadata(forester);
                return;
            }
            if (tryBindExistingForester()) return;
            Location spawn = record.spawnLocation();
            World world = spawn.getWorld();
            if (world == null) return;
            Villager villager = world.spawn(spawn, Villager.class, v -> {
                applyForesterMetadata(v);
                v.setRemoveWhenFarAway(false);
            });
            bindForester(villager);
        }

        boolean ensureForesterBinding(boolean allowSpawn) {
            if (hasActiveForester()) return true;
            if (tryBindExistingForester()) return true;
            if (allowSpawn) {
                spawnFreshForester();
                return hasActiveForester();
            }
            return false;
        }

        private void applyForesterMetadata(Villager villager) {
            villager.setCustomName("Forestier");
            villager.setCustomNameVisible(true);
            villager.setProfession(Villager.Profession.FLETCHER);
            villager.setVillagerLevel(5);
            villager.setInvulnerable(true);
            villager.setRemoveWhenFarAway(false);
            villager.setRecipes(List.of(createTrade()));
            villager.setAI(false);
            villager.setCollidable(false);
            villager.setGravity(false);
            PersistentDataContainer pdc = villager.getPersistentDataContainer();
            pdc.set(Keys.workerType(), PersistentDataType.STRING, "FORESTER");
            if (record != null) {
                pdc.set(Keys.workerId(), PersistentDataType.STRING, record.workerId().toString());
            }
            pdc.set(Keys.hutId(), PersistentDataType.STRING, hutId);
            villager.addScoreboardTag("minegus.worker");
            villager.addScoreboardTag("minegus.worker.forester");
        }

        private void ensureGuardIntegrity() {
            maintainGuardReference();
            if (!hasActiveForester()) return;
            if (guard == null) {
                spawnGuard();
            } else {
                applyGuardMetadata(guard);
            }
        }

        private void maintainGuardReference() {
            if (guard != null && (!guard.isValid() || guard.isDead())) {
                releaseGuard();
            }
        }

        private void releaseGuard() {
            if (guard != null) {
                guardsByHut.remove(hutId, guard);
            } else {
                guardsByHut.remove(hutId);
            }
            guard = null;
        }

        private void spawnGuard() {
            if (jobSite == null) return;
            Location spawn = jobSite.clone().add(2.0, 0, 0);
            IronGolem golem = w.spawn(spawn, IronGolem.class, g -> {
                g.setPlayerCreated(true);
                g.setCustomName("Golem Forestier");
                g.setCustomNameVisible(true);
                g.setRemoveWhenFarAway(false);
            });
            bindGuard(golem);
        }

        private void bindGuard(IronGolem golem) {
            if (guard != null && guard.isValid() && !guard.isDead()) {
                if (guard.getUniqueId().equals(golem.getUniqueId())) {
                    applyGuardMetadata(golem);
                    guardsByHut.put(hutId, golem);
                    return;
                }
                golem.remove();
                return;
            }
            guard = golem;
            applyGuardMetadata(golem);
            guardsByHut.put(hutId, golem);
        }

        private void applyGuardMetadata(IronGolem golem) {
            PersistentDataContainer pdc = golem.getPersistentDataContainer();
            pdc.set(Keys.guardFor(), PersistentDataType.STRING, hutId);
            golem.setPlayerCreated(true);
            golem.setCustomName("Golem Forestier");
            golem.setCustomNameVisible(true);
            golem.setRemoveWhenFarAway(false);
            golem.addScoreboardTag("minegus.guard");
        }


        void stop() {
            stop(true);
        }

        void stop(boolean removeEntities) {
            if (loop != null) {
                loop.cancel();
                loop = null;
            }
            boolean keepUuid = !removeEntities;
            if (removeEntities && hasActiveForester()) {
                forester.remove();
                keepUuid = false;
            }
            if (removeEntities && guard != null && guard.isValid() && !guard.isDead()) {
                guard.remove();
            }
            clearForesterReference(keepUuid);
            releaseGuard();
            if (loadedChunk != null) {
                loadedChunk.setForceLoaded(false);
                loadedChunk = null;
            }
            activeCapture.remove(id);
            missingForesterCooldown = 0;
        }

        /* ---------- structures ---------- */
        private void buildFrameGround() {
            for (int dx = -1; dx <= width; dx++) {
                set(x0 + dx, y0, z0 - 1, FRAME);
                set(x0 + dx, y0, z0 + length, FRAME);
            }
            for (int dz = -1; dz <= length; dz++) {
                set(x0 - 1, y0, z0 + dz, FRAME);
                set(x0 + width, y0, z0 + dz, FRAME);
            }
            for (int dx = 0; dx < width; dx++)
                for (int dz = 0; dz < length; dz++)
                    set(x0 + dx, y0, z0 + dz, Material.GRASS_BLOCK);

            // table de job au centre
            jobSite = new Location(w, x0 + width / 2.0, y0, z0 + length / 2.0);
            set(jobSite.getBlockX(), jobSite.getBlockY(), jobSite.getBlockZ(), JOB_TABLE);
        }

        private void scanExistingTreesAsSites() {
            for (int dx = 0; dx < width; dx++) {
                for (int dz = 0; dz < length; dz++) {
                    int x = x0 + dx;
                    int z = z0 + dz;
                    Block soil = w.getBlockAt(x, y0, z);
                    Block above = soil.getRelative(BlockFace.UP);
                    if (!isValidSoil(soil.getType())) continue;
                    if (!Tag.LOGS.isTagged(above.getType())) continue;
                    Location base = new Location(w, x, y0, z);
                    plantSites.add(new PlantSite(base, false, guessSaplingForGround(soil.getType())));
                }
            }
        }

        private void placeSaplings() {
            // on remplit par maillage ; si possible, de temps en temps on place un 2x2 pour dark oak/spruce/jungle
            boolean[][] taken = new boolean[width][length];
            Random R = new Random();

            for (int dx = 0; dx < width; dx += MAILLAGE_SAPLINGS) {
                for (int dz = 0; dz < length; dz += MAILLAGE_SAPLINGS) {
                    int ox = Math.max(0, Math.min(width - 1, dx + R.nextInt(3) - 1));
                    int oz = Math.max(0, Math.min(length - 1, dz + R.nextInt(3) - 1));

                    // tente 2x2 1/4 du temps si place suffisante
                    boolean try2x2 = R.nextInt(4) == 0
                            && ox + 1 < width && oz + 1 < length
                            && !taken[ox][oz] && !taken[ox + 1][oz] && !taken[ox][oz + 1] && !taken[ox + 1][oz + 1];

                    if (try2x2) {
                        Material s = TWO_BY_TWO_SAPLINGS.get(R.nextInt(TWO_BY_TWO_SAPLINGS.size()));
                        Location base = new Location(w, x0 + ox, y0, z0 + oz);
                        plant2x2(base, s);
                        plantSites.add(new PlantSite(base, true, s));
                        markTaken(taken, ox, oz, true);
                    } else {
                        Material s = ONE_BY_ONE_SAPLINGS.get(R.nextInt(ONE_BY_ONE_SAPLINGS.size()));
                        Location base = new Location(w, x0 + ox, y0, z0 + oz);
                        plant1x1(base, s);
                        plantSites.add(new PlantSite(base, false, s));
                        taken[ox][oz] = true;
                    }

                    // petite lanterne près de la maille
                    if (MAILLAGE_SAPLINGS >= 5 && ox + 2 < width && oz + 2 < length) {
                        set(x0 + ox + 2, y0 + 1, z0 + oz + 2, LIGHT);
                    }
                }
            }
        }

        private void markTaken(boolean[][] taken, int ox, int oz, boolean is2x2) {
            taken[ox][oz] = true;
            if (is2x2) {
                taken[ox + 1][oz] = true;
                taken[ox][oz + 1] = true;
                taken[ox + 1][oz + 1] = true;
            }
        }

        private void plant1x1(Location base, Material sapling) {
            Block soil = w.getBlockAt(base.getBlockX(), base.getBlockY(), base.getBlockZ());
            Block spot = soil.getRelative(BlockFace.UP);
            if (!isValidSoil(soil.getType())) soil.setType(Material.GRASS_BLOCK, false);
            spot.setType(sapling, false);
        }

        private void plant2x2(Location nwBase, Material sapling) {
            // NW ancre
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    Block soil = w.getBlockAt(nwBase.getBlockX() + dx, nwBase.getBlockY(), nwBase.getBlockZ() + dz);
                    Block spot = soil.getRelative(BlockFace.UP);
                    if (!isValidSoil(soil.getType())) soil.setType(Material.GRASS_BLOCK, false);
                    spot.setType(sapling, false);
                }
            }
        }

        private void createChestsIfMissing() {
            if (!chests.isEmpty()) return;
            // deux coffres doubles opposés
            makeDoubleChest(x0 + width + 1, z0 - 2);
            makeDoubleChest(x0 - 2, z0 + length + 1);
        }

        private void makeDoubleChest(int x, int z) {
            set(x, y0, z, Material.CHEST);
            set(x + 1, y0, z, Material.CHEST);
            chests.add(w.getBlockAt(x, y0, z));
            chests.add(w.getBlockAt(x + 1, y0, z));
        }

        /* ---------- entités ---------- */
        private MerchantRecipe createTrade() {
            MerchantRecipe r = new MerchantRecipe(new ItemStack(Material.OAK_LOG, 64), 9999999);
            r.addIngredient(new ItemStack(Material.IRON_INGOT, 32));
            r.setExperienceReward(false);
            return r;
        }

        /* ---------- boucle (humaine + budget par tick) ---------- */
        private void startLoop() {
            loop = new BukkitRunnable() {
                int siteIndex = 0;

                @Override public void run() {
                    try {
                        if (!hasActiveForester()) {
                            if (forester != null && (!forester.isValid() || forester.isDead())) {
                                clearForesterReference(false);
                            }
                            if (missingForesterCooldown <= 0) {
                                ensureForesterBinding(false);
                                missingForesterCooldown = 40;
                            } else {
                                missingForesterCooldown--;
                            }
                        } else {
                            missingForesterCooldown = 0;
                            maintainForesterAtJob();
                        }

                        ensureGuardIntegrity();

                        perTickBudget = MAX_BLOCKS_PER_TICK;
                        // priorité 1 : travail en cours
                        if (current != null) {
                            workOnCurrentJob();
                            return;
                        }

                        // priorité 2 : file BFS (feuilles si fast-leaves)
                        if (!bfs.isEmpty()) {
                            processBfs();
                            return;
                        }

                        // priorité 3 : replant si demandé
                        if (replant != null) {
                            Material sap = current != null ? current.replantSapling : guessSaplingForGround(replant.getType());
                            if (sap == null && current != null) sap = current.replantSapling;
                            if (sap == null) sap = Material.OAK_SAPLING;
                            replantBlock(replant, sap);
                            replant = null;
                            return;
                        }

                        // sinon : scanner les sites
                        if (plantSites.isEmpty()) return;
                        PlantSite site = plantSites.get(siteIndex);
                        siteIndex = (siteIndex + 1) % plantSites.size();

                        if (site.isMature(w)) {
                            current = new TreeJob(site);
                            current.computeLogStack();
                            stepDelay = 0;
                            activeCapture.add(id);
                            plugin.getLogger().info("[Foret] Job lancé @ " + site.base.getBlockX() + "," + site.base.getBlockY() + "," + site.base.getBlockZ());
                        }
                    } catch (Throwable t) {
                        plugin.getLogger().severe("[Foret] Erreur dans la boucle pour " + hutId);
                        t.printStackTrace();
                    }
                }
            };
            loop.runTaskTimer(plugin, LOOP_PERIOD_TICKS, LOOP_PERIOD_TICKS);
        }

        private void maintainForesterAtJob() {
            if (forester == null || jobSite == null) return;
            if (!forester.isValid() || forester.isDead()) return;
            if (current != null && current.state != JobState.DONE) return;

            Location home = jobSite.clone().add(0.5, 1, 0.5);
            double d2 = forester.getLocation().distanceSquared(home);

            if (d2 > 4) {
                forester.teleport(home);
            }
        }

        private void workOnCurrentJob() {
            if (current.logsTopDown.isEmpty()) {
                // logs terminés
                activeCapture.remove(id);
                if (FAST_LEAVES) buildLeavesBfs(current);
                current.state = JobState.REPLANT;
            }

            switch (current.state) {
                case MOVING -> moveTo(current.foot().clone().add(0.5, 0, 0.5));
                // on considère qu'on est arrivé instantanément (TP progressif possible)
                case CHOPPING -> chopLogs();
                case CLEARING_LEAVES -> processBfs();
                case REPLANT -> doReplant();
                case COLLECT -> collectAround(current.foot());
                case DEPOSIT -> depositPocket();
                case DONE -> {
                    Location foot = current.foot();
                    plugin.getLogger().info("[Foret] Job terminé @ " + foot.getBlockX() + "," + foot.getBlockY() + "," + foot.getBlockZ());
                    current = null;
                    stepDelay = 0;
                }
            }
        }

        private void moveTo(Location target) {
            if (forester == null) {
                current.state = JobState.CHOPPING;
                return;
            }

            // Sécurité si jamais les mondes ne correspondent plus.
            if (target.getWorld() == null || !target.getWorld().equals(forester.getWorld())) {
                forester.teleport(target);
                current.state = JobState.CHOPPING;
                return;
            }

            Location from = forester.getLocation();

            // Arrivée si on est déjà suffisamment proche.
            double d2 = from.distanceSquared(target);
            if (d2 <= 0.7) {
                Location snap = target.clone();
                snap.setYaw(faceYaw(snap, target));
                forester.teleport(snap);
                current.state = JobState.CHOPPING;
                return;
            }

            // Avance d'un petit pas vers la cible.
            Vector dir = target.toVector().subtract(from.toVector());
            if (dir.lengthSquared() > 1.0) {
                dir.normalize();
            }

            Location next = from.clone().add(dir);
            next.setYaw(faceYaw(next, target));
            forester.teleport(next);
        }

        private void chopLogs() {
            if (stepDelay > 0) { stepDelay--; return; }
            int used = 0;
            while (used < perTickBudget && !current.logsTopDown.isEmpty()) {
                Block b = current.logsTopDown.remove(0);
                if (b.getType().isAir()) continue;

                lookAt(forester, b.getLocation().add(0.5, 0.5, 0.5));
                b.getWorld().playSound(b.getLocation(), Sound.BLOCK_WOOD_BREAK, 0.9f, 1.0f);
                b.breakNaturally(new ItemStack(Material.IRON_AXE)); // drops capturés via BlockDropItemEvent

                used++;
                perTickBudget--;
                stepDelay = LOG_BREAK_DELAY;
                if (stepDelay > 0) return; // simulate "human" delay entre blocs
            }

            if (current.logsTopDown.isEmpty()) {
                activeCapture.remove(id);
                current.state = FAST_LEAVES ? JobState.CLEARING_LEAVES : JobState.REPLANT;
                stepDelay = 0;
            }
        }

        private void processBfs() {
            if (bfs.isEmpty()) { // passe à la suite du job
                if (current != null) {
                    current.state = JobState.REPLANT;
                }
                return;
            }
            if (stepDelay > 0) { stepDelay--; return; }

            int used = 0;
            while (used < perTickBudget && !bfs.isEmpty()) {
                Block b = bfs.poll();
                if (b == null || b.getType().isAir()) continue;
                if (!Tag.LEAVES.isTagged(b.getType())) continue;

                lookAt(forester, b.getLocation().add(0.5, 0.5, 0.5));
                b.getWorld().playSound(b.getLocation(), Sound.BLOCK_GRASS_BREAK, 0.7f, 1.4f);
                b.breakNaturally(); // feuilles

                used++;
                perTickBudget--;
                stepDelay = LEAF_BREAK_DELAY;
                if (stepDelay > 0) return;
            }
        }

        private void doReplant() {
            // spots à replanter
            List<Location> spots = current.replantSpots();
            int placed = 0;

            for (Location l : spots) {
                Block ground = w.getBlockAt(l.getBlockX(), l.getBlockY(), l.getBlockZ());
                Block above = ground.getRelative(BlockFace.UP);
                // dégage herbes/fleurs
                if (!above.getType().isAir()) above.setType(Material.AIR, false);
                if (!isValidSoil(ground.getType())) ground.setType(Material.GRASS_BLOCK, false);
                above.setType(current.replantSapling, false);
                placed++;
            }

            ensureLightAt(current.foot());

            current.state = JobState.COLLECT;
            stepDelay = 0;
        }

        private void collectAround(Location center) {
            // fallback : si des items sont tombés (au cas où), aspirer dans un petit rayon
            BoundingBox bb = new BoundingBox(
                    center.getX() - 5, center.getY() - 2, center.getZ() - 5,
                    center.getX() + 5, center.getY() + 5, center.getZ() + 5
            );
            for (Entity e : w.getNearbyEntities(bb)) {
                if (e instanceof Item it) {
                    pocket.add(it.getItemStack().clone());
                    it.remove();
                }
            }
            current.state = JobState.DEPOSIT;
        }

        private void depositPocket() {
            if (pocket.isEmpty()) { current.state = JobState.DONE; return; }

            Iterator<Block> cycle = Iterators.cycle(chests);
            List<ItemStack> leftovers = new ArrayList<>();

            for (ItemStack it : pocket) {
                boolean stored = false;
                int tries = 0;
                while (!stored && tries < chests.size()) {
                    Chest c = (Chest) cycle.next().getState();
                    tries++;
                    Map<Integer, ItemStack> left = c.getInventory().addItem(it);
                    if (left.isEmpty()) {
                        stored = true;
                    } else {
                        it = left.values().iterator().next();
                    }
                }
                if (!stored) leftovers.add(it);
            }
            pocket.clear();
            pocket.addAll(leftovers);

            // si encore des restes : agrandir le rack et réessayer une fois
            if (!pocket.isEmpty()) {
                expandStorageRack();
                List<ItemStack> still = new ArrayList<>();
                for (ItemStack it : pocket) {
                    Chest c = (Chest) chests.get(chests.size() - 1).getState();
                    Map<Integer, ItemStack> left = c.getInventory().addItem(it);
                    if (!left.isEmpty()) still.add(left.values().iterator().next());
                }
                pocket.clear();
                pocket.addAll(still);
            }

            current.state = JobState.DONE;
        }

        /* ---------- util ---------- */
        boolean isProtectedStructure(Block b) {
            Material m = b.getType();

            // 1) Les coffres NE SONT PAS une structure protégée
            if (chests.contains(b)) {
                return false;
            }

            // 2) Table de métier + lanternes dans la zone
            if ((m == LIGHT || m == JOB_TABLE) && inBounds(b.getLocation())) {
                return true;
            }

            // 3) Cadre en OAK_LOG uniquement sur l'anneau extérieur
            if (m == FRAME && isFrameBlock(b)) {
                return true;
            }

            // 4) Le reste (troncs, feuilles…) n'est pas protégé
            return false;
        }

        private boolean isFrameBlock(Block b) {
            int x = b.getX();
            int y = b.getY();
            int z = b.getZ();

            // cadre uniquement sur le plan y0
            if (y != y0) {
                return false;
            }

            boolean onXBorder = (x == x0 - 1 || x == x0 + width);
            boolean onZBorder = (z == z0 - 1 || z == z0 + length);

            if (!onXBorder && !onZBorder) {
                return false;
            }

            if (onXBorder) { // bords Nord/Sud
                return (z >= z0 - 1 && z <= z0 + length);
            } else { // bords Est/Ouest
                return (x >= x0 - 1 && x <= x0 + width);
            }
        }

        boolean removeChestIfMatch(Block b) { return chests.remove(b); }
        boolean hasChests() { return !chests.isEmpty(); }

        private void expandStorageRack() {
            // ajoute un coffre au-dessus du premier, jusqu'à MAX_RACK_HEIGHT
            Block base = chests.get(0);
            int x = base.getX(), z = base.getZ();
            for (int dy = 1; dy <= MAX_RACK_HEIGHT; dy++) {
                Block target = w.getBlockAt(x, base.getY() + dy, z);
                if (target.getType() == Material.AIR) {
                    target.setType(Material.CHEST);
                    chests.add(target);
                    return;
                }
            }
        }

        boolean inBounds(Location l) {
            int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
            return x >= x0 && x < x0 + width
                    && z >= z0 && z < z0 + length
                    && y >= y0 && y <= y0 + FOREST_HEIGHT;
        }

        private void set(int x, int y, int z, Material mat) {
            w.getBlockAt(x, y, z).setType(mat, false);
        }

        private void ensureLightAt(Location foot) {
            Block check = w.getBlockAt(foot.getBlockX(), foot.getBlockY() + 1, foot.getBlockZ());
            if (check.getLightLevel() >= LIGHT_MIN_LEVEL) return;

            // place une torche au-dessus du sol si libre
            Block place = w.getBlockAt(foot.getBlockX(), foot.getBlockY() + 1, foot.getBlockZ());
            if (place.getType().isAir()) place.setType(Material.TORCH, false);
        }

        private void replantBlock(Block target, Material sapling) {
            if (target == null || sapling == null) return;

            Block soil = target;
            Block plantingSpot;

            if (target.getType().isAir()) {
                soil = target.getRelative(BlockFace.DOWN);
                plantingSpot = target;
            } else {
                plantingSpot = target.getRelative(BlockFace.UP);
            }

            if (!isValidSoil(soil.getType())) {
                soil.setType(Material.GRASS_BLOCK, false);
            }

            if (!plantingSpot.getType().isAir()) {
                plantingSpot.setType(Material.AIR, false);
            }
            plantingSpot.setType(sapling, false);
        }

        private void buildLeavesBfs(TreeJob job) {
            bfs.clear();
            Location c = job.foot();
            int r = 5;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = 0; dy <= 10; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        Block b = w.getBlockAt(c.getBlockX() + dx, c.getBlockY() + 1 + dy, c.getBlockZ() + dz);
                        if (Tag.LEAVES.isTagged(b.getType())) bfs.add(b);
                    }
                }
            }
        }

        /* ---------- sérialisation ---------- */
        Map<String, Object> serialize() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("world", w.getUID().toString());
            map.put("x", x0);
            map.put("y", y0);
            map.put("z", z0);
            map.put("width", width);
            map.put("length", length);

            if (!bfs.isEmpty()) {
                List<String> list = new ArrayList<>();
                bfs.forEach(b -> list.add(b.getX() + "," + b.getY() + "," + b.getZ()));
                map.put("harvestQueue", list);
            }
            if (replant != null) {
                map.put("replantLocation", List.of(replant.getX(), replant.getY(), replant.getZ()));
            }
            if (!plantSites.isEmpty()) {
                List<String> sites = new ArrayList<>();
                for (PlantSite s : plantSites) {
                    sites.add(s.base.getBlockX() + ";" + s.base.getBlockY() + ";" + s.base.getBlockZ()
                            + ";" + s.is2x2 + ";" + s.sapling.name());
                }
                map.put("sites", sites);
            }
            return map;
        }

        /* ---------- classes internes "travail" ---------- */

        private class TreeJob {
            final PlantSite site;
            final Material replantSapling;
            List<Block> logsTopDown = new ArrayList<>();
            JobState state = JobState.MOVING;

            TreeJob(PlantSite site) {
                this.site = site;
                this.replantSapling = site.sapling;
            }

            Location foot() { return site.base.clone(); }

            void computeLogStack() {
                // point de départ : pour 1x1 -> base+1 ; pour 2x2 -> base+1 (NW), on remonte tant qu'on est sur un LOG
                Block start = w.getBlockAt(site.base.getBlockX(), site.base.getBlockY() + 1, site.base.getBlockZ());
                if (site.is2x2 && !Tag.LOGS.isTagged(start.getType())) {
                    // essayer les 3 autres
                    Block bE = start.getRelative(1, 0, 0);
                    Block bS = start.getRelative(0, 0, 1);
                    Block bSE = start.getRelative(1, 0, 1);
                    if (Tag.LOGS.isTagged(bE.getType())) start = bE;
                    else if (Tag.LOGS.isTagged(bS.getType())) start = bS;
                    else if (Tag.LOGS.isTagged(bSE.getType())) start = bSE;
                }
                Block cur = start;
                // monter au sommet
                while (Tag.LOGS.isTagged(cur.getType())) cur = cur.getRelative(BlockFace.UP);
                cur = cur.getRelative(BlockFace.DOWN);
                // redescendre en empilant top→bottom
                while (Tag.LOGS.isTagged(cur.getType())) {
                    logsTopDown.add(cur);
                    cur = cur.getRelative(BlockFace.DOWN);
                }
                state = JobState.CHOPPING;
            }

            List<Location> replantSpots() {
                List<Location> spots = new ArrayList<>();
                if (site.is2x2) {
                    spots.add(site.base.clone());
                    spots.add(site.base.clone().add(1, 0, 0));
                    spots.add(site.base.clone().add(0, 0, 1));
                    spots.add(site.base.clone().add(1, 0, 1));
                } else {
                    spots.add(site.base.clone());
                }
                return spots;
            }
        }
    }

    /* ══════════════════ OUTILS GÉNÉRAUX ══════════════════ */

    private static String coord(Block b) { return b.getX() + "," + b.getY() + "," + b.getZ(); }

    private static final class Selection { Block corner1, corner2; }

    private static boolean isValidSoil(Material m) {
        return switch (m) {
            case DIRT, GRASS_BLOCK, COARSE_DIRT, ROOTED_DIRT, PODZOL, MOSS_BLOCK, MUD, CLAY -> true;
            default -> false;
        };
    }

    private static void lookAt(Entity e, Location target) {
        if (e == null) return;
        Location l = e.getLocation();
        l.setYaw(faceYaw(l, target));
        e.teleport(l);
    }

    private static float faceYaw(Location from, Location to) {
        Vector dir = to.toVector().subtract(from.toVector());
        return (float) (Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ())));
    }

    private static Material guessSaplingForGround(Material ground) {
        // fallback si on n'a pas d'info de job courant
        return Material.OAK_SAPLING;
    }

    /* ══════════════════ PLANT SITE ══════════════════ */

    private static final class PlantSite {
        final Location base; // bloc de sol (Y = plan), ancre NW pour 2x2
        final boolean is2x2;
        final Material sapling;

        PlantSite(Location base, boolean is2x2, Material sapling) {
            this.base = base;
            this.is2x2 = is2x2;
            this.sapling = sapling;
        }

        boolean isMature(World w) {
            int bx = base.getBlockX();
            int by = base.getBlockY();
            int bz = base.getBlockZ();
            int radius = is2x2 ? 1 : 0;
            int maxHeight = 4;

            for (int dy = 1; dy <= maxHeight; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        Block b = w.getBlockAt(bx + dx, by + dy, bz + dz);
                        if (Tag.LOGS.isTagged(b.getType())) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    private enum JobState { MOVING, CHOPPING, CLEARING_LEAVES, REPLANT, COLLECT, DEPOSIT, DONE }
}

/* ===========================================================================
   Utility minimal – équivalent de Guava Iterators.cycle()
   =========================================================================== */
final class Iterators {
    static <T> Iterator<T> cycle(List<T> list) {
        return new Iterator<>() {
            int i = 0;
            public boolean hasNext() { return !list.isEmpty(); }
            public T next() {
                T t = list.get(i);
                i = (i + 1) % list.size();
                return t;
            }
        };
    }
}
