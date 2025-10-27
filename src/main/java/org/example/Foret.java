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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.Tag;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;

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

    /* capture des drops durant la coupe */
    private final Set<UUID> activeCapture = new HashSet<>(); // ids de sessions actuellement en coupe

    public Foret(JavaPlugin plugin) {
        this.plugin = plugin;
        Objects.requireNonNull(plugin.getCommand("foret")).setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);

        forestsFile = new File(plugin.getDataFolder(), "forests.yml");
        forestsYaml = YamlConfiguration.loadConfiguration(forestsFile);
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
        fs.start();
        sessions.add(fs);
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

            fs.start();
            sessions.add(fs);
            restored++;
        }
        plugin.getLogger().info("[Foret] " + restored + " forêt(s) restaurée(s).");
    }

    /* ══════════════════ API PUBLIQUE (rétro‑compat) ══════════════════ */
    public void loadSavedSessions() { loadSessions(); }
    public void saveAllSessions() { saveSessions(); }
    public void stopAllForests() {
        sessions.forEach(ForestSession::stop);
        sessions.clear();
    }

    /* ══════════════════ CLASSE SESSION ══════════════════ */
    private final class ForestSession {
        final UUID id = UUID.randomUUID();
        final World w;
        final int x0, y0, z0, width, length;

        // structures & logique
        final List<Block> chests = new ArrayList<>();
        final List<PlantSite> plantSites = new ArrayList<>(); // spots 1x1 et 2x2
        final Queue<Block> bfs = new LinkedList<>();
        final List<ItemStack> pocket = new ArrayList<>();
        Block replant = null;

        // entités
        Villager forester;
        final List<IronGolem> golems = new ArrayList<>();
        BukkitRunnable loop;
        Chunk loadedChunk;
        Location jobSite;

        // travail courant
        TreeJob current;
        int stepDelay = 0; // temporisation "humaine"
        int perTickBudget = 0; // budget restant dans le tick

        ForestSession(World w, Location origin, int width, int length) {
            this.w = w;
            this.x0 = origin.getBlockX();
            this.y0 = origin.getBlockY();
            this.z0 = origin.getBlockZ();
            this.width = width;
            this.length = length;
        }

        /* ---------- cycle de vie ---------- */
        void start() {
            loadedChunk = w.getChunkAt(x0 + width / 2, z0 + length / 2);
            loadedChunk.setForceLoaded(true);
            buildFrameGround();
            if (plantSites.isEmpty()) {
                placeSaplings(); // génère nos PlantSite 1x1 et 2x2
            }
            createChestsIfMissing();
            spawnEntities();
            startLoop();
        }

        void stop() {
            if (loop != null) loop.cancel();
            if (forester != null) forester.remove();
            golems.forEach(Entity::remove);
            if (loadedChunk != null) loadedChunk.setForceLoaded(false);
            activeCapture.remove(id);
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
        private void spawnEntities() {
            // Nettoie un éventuel ancien PNJ
            for (Entity e : w.getNearbyEntities(jobSite, 6, 4, 6)) {
                if (e instanceof Villager v && "Forestier".equals(ChatColor.stripColor(Objects.toString(v.getCustomName(), "")))) {
                    v.remove();
                }
            }
            forester = (Villager) w.spawnEntity(jobSite.clone().add(0.5, 1, 0.5), EntityType.VILLAGER);
            forester.setCustomName("Forestier");
            forester.setCustomNameVisible(true);
            forester.setProfession(Villager.Profession.FLETCHER);
            forester.setVillagerLevel(5);
            forester.setInvulnerable(true);
            forester.setRecipes(List.of(createTrade()));

            int wanted = Math.max(2, (width * length) / 512);
            golems.removeIf(Entity::isDead);
            while (golems.size() < wanted) {
                Location l = jobSite.clone().add((golems.size() % 2 == 0 ? 2 : -2), 0, (golems.size() < 2 ? 2 : -2));
                IronGolem g = (IronGolem) w.spawnEntity(l, EntityType.IRON_GOLEM);
                g.setPlayerCreated(true);
                g.setCustomName("Golem Forestier");
                golems.add(g);
            }
        }

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
                    if (forester == null || forester.isDead()) spawnEntities();
                    maintainForesterAtJob();

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
                    }
                }
            };
            loop.runTaskTimer(plugin, LOOP_PERIOD_TICKS, LOOP_PERIOD_TICKS);
        }

        private void maintainForesterAtJob() {
            if (forester == null) return;
            double d2 = forester.getLocation().distanceSquared(jobSite.clone().add(0.5, 1, 0.5));
            if (d2 > 36) {
                forester.setAI(false);
                forester.teleport(jobSite.clone().add(0.5, 1, 0.5));
            } else if (!forester.hasAI()) {
                forester.setAI(true);
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
                case DONE -> { current = null; stepDelay = 0; }
            }
        }

        private void moveTo(Location target) {
            if (forester == null) { current.state = JobState.CHOPPING; return; }
            Location from = forester.getLocation();
            int steps = 4;
            Vector step = target.clone().subtract(from).toVector().multiply(1.0 / steps);
            Location next = from.clone().add(step);
            next.setYaw(faceYaw(next, target));
            forester.teleport(next);
            steps--;
            if (steps <= 0) current.state = JobState.CHOPPING;
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
            if (chests.contains(b)) return true;
            if (m == FRAME || m == LIGHT || m == JOB_TABLE) {
                return inBounds(b.getLocation());
            }
            return false;
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
            // arbre "présent" si un LOG au-dessus (1x1) ou motif 2x2 de LOGs
            Block a = w.getBlockAt(base.getBlockX(), base.getBlockY() + 1, base.getBlockZ());
            if (!is2x2) {
                return Tag.LOGS.isTagged(a.getType());
            } else {
                Block bE  = a.getRelative(1, 0, 0);
                Block bS  = a.getRelative(0, 0, 1);
                Block bSE = a.getRelative(1, 0, 1);
                int count = 0;
                if (Tag.LOGS.isTagged(a.getType())) count++;
                if (Tag.LOGS.isTagged(bE.getType())) count++;
                if (Tag.LOGS.isTagged(bS.getType())) count++;
                if (Tag.LOGS.isTagged(bSE.getType())) count++;
                return count >= 3; // tolérant
            }
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
