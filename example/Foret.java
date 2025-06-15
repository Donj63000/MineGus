package org.example;

import org.bukkit.*;
import org.bukkit.block.Block;
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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * /foret : micro‑forêt automatisée (cadre, saplings, PNJ, golems, récolte BFS…).
 * Version juin 2025 : maillage ajustable, coffres dynamiques et API rétro‑compatible.
 */
public final class Foret implements CommandExecutor, Listener {

    /* ══════════════════════ CONSTANTES ══════════════════════ */
    private static final String FORET_SELECTOR_NAME = ChatColor.GOLD + "Sélecteur de forêt";
    private static final int    MAILLAGE_SAPLINGS   = 6;   // espacement
    private static final int    FOREST_HEIGHT       = 24;  // y‑max
    // 26 piles de 64 => on garde toujours un emplacement vide
    private static final int    MAX_PER_CHEST       = 26 * 64;

    private static final List<Material> SAPLINGS = List.of(
            Material.OAK_SAPLING, Material.BIRCH_SAPLING, Material.SPRUCE_SAPLING,
            Material.JUNGLE_SAPLING, Material.ACACIA_SAPLING, Material.DARK_OAK_SAPLING
    );
    /* ════════════════════════════════════════════════════════ */

    private final JavaPlugin plugin;

    /* sélection par joueur */
    private final Map<UUID, Selection> selections = new HashMap<>();

    /* sessions actives */
    private final List<ForestSession> sessions = new ArrayList<>();

    /* persistance */
    private final File forestsFile;
    private final YamlConfiguration forestsYaml;

    /* ───────────────────── constructeur ───────────────────── */
    public Foret(JavaPlugin plugin) {
        this.plugin = plugin;

        Objects.requireNonNull(plugin.getCommand("foret")).setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);

        forestsFile = new File(plugin.getDataFolder(), "forests.yml");
        forestsYaml = YamlConfiguration.loadConfiguration(forestsFile);
    }

    /* ══════════════════ COMMANDE /foret ══════════════════ */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd,
                             String lbl, String[] args) {

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

        e.setCancelled(true);                                   // pas de casse/pose

        Player     p   = e.getPlayer();
        Selection  sel = selections.get(p.getUniqueId());
        if (sel == null) {
            p.sendMessage(ChatColor.RED + "Refais /foret pour commencer une sélection.");
            return;
        }

        if (sel.corner1 == null) {
            sel.corner1 = e.getClickedBlock();
            p.sendMessage(ChatColor.AQUA + "Coin 1 : " + coord(sel.corner1));
        } else if (sel.corner2 == null) {
            sel.corner2 = e.getClickedBlock();
            p.sendMessage(ChatColor.AQUA + "Coin 2 : " + coord(sel.corner2));
            validateAndCreate(p, sel);
        } else {
            sel.corner1 = e.getClickedBlock();
            sel.corner2 = null;
            p.sendMessage(ChatColor.AQUA + "Coin 1 redéfini : " + coord(sel.corner1));
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        for (ForestSession fs : new ArrayList<>(sessions)) {

            if (fs.isProtectedStructure(b)) {           // cadre/lanternes/coffres
                e.setCancelled(true);
                return;
            }

            if (fs.removeChestIfMatch(b) && !fs.hasChests()) {
                fs.stop();
                sessions.remove(fs);
                saveSessions();                         // persistance
            }
        }
    }

    /* ═════════════════ VALIDATION ZONE ═════════════════ */
    private void validateAndCreate(Player p, Selection sel) {

        if (sel.corner1.getY() != sel.corner2.getY()) {
            p.sendMessage(ChatColor.RED + "Les deux blocs doivent avoir la même hauteur (Y).");
            sel.corner2 = null;
            return;
        }

        World w  = sel.corner1.getWorld();
        int   y  = sel.corner1.getY();
        int   x1 = sel.corner1.getX(), x2 = sel.corner2.getX();
        int   z1 = sel.corner1.getZ(), z2 = sel.corner2.getZ();
        int   minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int   minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        ForestSession fs = new ForestSession(
                w, new Location(w, minX, y, minZ),
                maxX - minX + 1, maxZ - minZ + 1);

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
        for (ForestSession fs : sessions)
            forestsYaml.createSection("forests." + i++, fs.serialize());

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

            ForestSession fs = new ForestSession(
                    w, new Location(w, x, y, z), width, length);

            // file BFS éventuelle
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
            // replantLocation
            if (sec.contains("replantLocation")) {
                List<Integer> rl = sec.getIntegerList("replantLocation");
                if (rl.size() == 3)
                    fs.replant = w.getBlockAt(rl.get(0), rl.get(1), rl.get(2));
            }
            fs.start();                       // relance async (après chargement)
            sessions.add(fs);
            restored++;
        }
        plugin.getLogger().info("[Foret] " + restored + " forêt(s) restaurée(s).");
    }

    /* ══════════════════ API PUBLIQUE (wrappers rétro‑compatibles) ══════════════════ */

    /** Gardé public pour MinePlugin.onEnable() (rétro‑compat). */
    public void loadSavedSessions() {
        loadSessions();
    }

    /** Gardé public pour MinePlugin.onDisable(). */
    public void saveAllSessions() {
        saveSessions();
    }

    /** Appelé par MinePlugin.onDisable() pour arrêter proprement. */
    public void stopAllForests() {
        sessions.forEach(ForestSession::stop);
        sessions.clear();
    }

    /* ══════════════════ CLASSE SESSION ══════════════════ */
    private final class ForestSession {

        private static final Material FRAME = Material.OAK_LOG;
        private static final Material LIGHT = Material.SEA_LANTERN;

        private final World w;
        private final int x0, y0, z0, width, length;

        /* structures & logique */
        private final List<Block> chests = new ArrayList<>();
        private final List<Block> saplingSpots = new ArrayList<>();
        private final Queue<Block> bfs = new LinkedList<>();
        private Block replant = null;

        /* entités */
        private Villager forester;
        private final List<IronGolem> golems = new ArrayList<>();
        private BukkitRunnable loop;
        private Chunk loadedChunk;
        private Location jobSite;

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
            placeSaplings();
            createChests();
            spawnEntities();
            startLoop();
        }

        void stop() {
            if (loop != null) loop.cancel();
            if (forester != null) forester.remove();
            golems.forEach(Entity::remove);
            if (loadedChunk != null) loadedChunk.setForceLoaded(false);
        }

        /* ---------- helpers structure ---------- */
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
        }

        private void placeSaplings() {
            Random R = new Random();
            for (int dx = 0; dx < width; dx += MAILLAGE_SAPLINGS) {
                for (int dz = 0; dz < length; dz += MAILLAGE_SAPLINGS) {
                    int ox = Math.max(0, Math.min(width - 1, dx + R.nextInt(3) - 1));
                    int oz = Math.max(0, Math.min(length - 1, dz + R.nextInt(3) - 1));
                    Block spot = w.getBlockAt(x0 + ox, y0 + 1, z0 + oz);
                    spot.setType(randomSapling());
                    saplingSpots.add(spot);
                    // petite lanterne au centre de maille
                    if (MAILLAGE_SAPLINGS >= 5 && ox + 2 < width && oz + 2 < length) {
                        set(x0 + ox + 2, y0 + 1, z0 + oz + 2, LIGHT);
                    }
                }
            }
        }

        private void createChests() {
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
            jobSite = new Location(w, x0 + width / 2.0, y0, z0 + length / 2.0);
            w.getBlockAt(jobSite).setType(Material.FLETCHING_TABLE);
            forester = (Villager) w.spawnEntity(jobSite.clone().add(0.5, 1, 0.5),
                    EntityType.VILLAGER);
            forester.setCustomName("Forestier");
            forester.setCustomNameVisible(true);
            forester.setProfession(Villager.Profession.FLETCHER);
            forester.setVillagerLevel(5);
            forester.setInvulnerable(true);
            forester.setRecipes(List.of(createTrade()));

            int wanted = Math.max(2, (width * length) / 512);
            for (int i = 0; i < wanted; i++) {
                Location l = new Location(w,
                        x0 + width / 2.0 + (i % 2 == 0 ? 2 : -2),
                        y0,
                        z0 + length / 2.0 + (i < 2 ? 2 : -2));
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

        /* ---------- boucle 1 tick / s ---------- */
        private void startLoop() {
            loop = new BukkitRunnable() {
                int idx = 0;

                @Override
                public void run() {
                    if (forester == null || forester.isDead()) spawnEntities();
                    golems.removeIf(Entity::isDead);

                    if (forester != null) {
                        double d2 = forester.getLocation().distanceSquared(jobSite.clone().add(0.5,1,0.5));
                        if (d2 > 36) {
                            forester.setAI(false);
                            forester.teleport(jobSite.clone().add(0.5, 1, 0.5));
                        } else if (!forester.hasAI()) {
                            forester.setAI(true);
                        }
                    }

                    if (!bfs.isEmpty() || replant != null) {
                        processHarvest();
                        return;
                    }

                    if (saplingSpots.isEmpty()) return;
                    Block check = saplingSpots.get(idx);
                    idx = (idx + 1) % saplingSpots.size();

                    if (isLogOrLeaves(check.getType())) {
                        replant = check;
                        buildBFS(check);
                    }
                }
            };
            loop.runTaskTimer(plugin, 20, 20);
        }

        /* ---------- BFS récolte ---------- */
        private void buildBFS(Block start) {
            bfs.clear();
            Queue<Block> q = new LinkedList<>();
            Set<Block> seen = new HashSet<>();
            q.add(start);

            while (!q.isEmpty()) {
                Block b = q.poll();
                if (!seen.add(b)) continue;
                if (!isLogOrLeaves(b.getType()) || !inBounds(b.getLocation())) continue;
                bfs.add(b);
                for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0},
                        {0, 1, 0}, {0, -1, 0},
                        {0, 0, 1}, {0, 0, -1}}) {
                    q.add(b.getRelative(d[0], d[1], d[2]));
                }
            }
        }

        private void processHarvest() {
            if (!bfs.isEmpty()) {
                Block b = bfs.poll();
                Collection<ItemStack> drops = b.getDrops();
                b.setType(Material.AIR);

                List<ItemStack> list = new ArrayList<>();
                int bone = 0;
                for (ItemStack it : drops) {
                    if (it.getType().name().endsWith("_LEAVES")) {
                        bone += it.getAmount() / 8;
                    } else {
                        list.add(it);
                    }
                }
                if (bone > 0) list.add(new ItemStack(Material.BONE_MEAL, bone));

                deposit(list);
                if (forester != null && !forester.isDead())
                    forester.teleport(b.getLocation().add(0.5, 1, 0.5));
            } else if (replant != null) {
                replant.setType(randomSapling());
                replant = null;
            }
        }

        /* ---------- util ---------- */
        boolean isProtectedStructure(Block b) {
            Material m = b.getType();
            return (m == FRAME || m == LIGHT) && inBounds(b.getLocation())
                    || chests.contains(b);
        }

        boolean removeChestIfMatch(Block b) {
            return chests.remove(b);
        }

        boolean hasChests() {
            return !chests.isEmpty();
        }

        private void deposit(List<ItemStack> items) {
            if (items.isEmpty()) return;

            Iterator<Block> cycle = Iterators.cycle(chests);
            for (ItemStack it : items) {
                boolean stored = false;
                int tries = 0;
                while (!stored && tries < chests.size()) {
                    Chest c = (Chest) cycle.next().getState();
                    tries++;
                    if (hasSpace(c.getInventory(), it)) {
                        c.getInventory().addItem(it);
                        stored = true;
                    }
                }
                if (!stored) {
                    expandStorageRack();
                    ((Chest) chests.get(chests.size() - 1).getState()).getInventory().addItem(it);
                }
            }
        }

        /**
         * Calcule la place restante et vérifie qu'il reste moins de
         * {@link #MAX_PER_CHEST} items après l'ajout (26 piles de 64).
         */
        private static boolean hasSpace(Inventory inv, ItemStack item) {
            int current = Arrays.stream(inv.getStorageContents())
                    .filter(Objects::nonNull)
                    .mapToInt(ItemStack::getAmount)
                    .sum();
            if (current + item.getAmount() > MAX_PER_CHEST) return false;

            if (inv.firstEmpty() != -1) return true;
            for (ItemStack stack : inv.getStorageContents()) {
                if (stack != null && stack.isSimilar(item) && stack.getAmount() < stack.getMaxStackSize()) {
                    return true;
                }
            }
            return false;
        }

        private static final int MAX_RACK_HEIGHT = 6;

        private void expandStorageRack() {
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

        private boolean inBounds(Location l) {
            int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
            return x >= x0 && x < x0 + width
                    && z >= z0 && z < z0 + length
                    && y >= y0 && y <= y0 + FOREST_HEIGHT;
        }

        private Material randomSapling() {
            return SAPLINGS.get(new Random().nextInt(SAPLINGS.size()));
        }

        private boolean isLogOrLeaves(Material m) {
            String n = m.name();
            return n.endsWith("_LOG") || n.endsWith("_LEAVES");
        }

        private void set(int x, int y, int z, Material mat) {
            w.getBlockAt(x, y, z).setType(mat, false);
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
                map.put("replantLocation",
                        List.of(replant.getX(), replant.getY(), replant.getZ()));
            }
            return map;
        }
    }

    /* ══════════════════ OUTILS GÉNÉRAUX ══════════════════ */
    private static String coord(Block b) { return b.getX() + "," + b.getY() + "," + b.getZ(); }

    private static final class Selection { Block corner1, corner2; }
}

/* ===========================================================================
   Utility minimal – équivalent de Guava Iterators.cycle()
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
