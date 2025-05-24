package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Commande /foret :
 *  1) Le joueur reçoit un bâton "Sélecteur de forêt".
 *  2) Il clique 2 blocs (à la même hauteur) pour définir la zone.
 *  3) Le plugin crée une session forestière (cadre, coffres, PNJ "Forestier", 2 golems).
 *  4) Les arbres poussent (en vanilla). Dès qu'un sapling se transforme en LOG/LEAVES, on le récolte (BFS).
 *  5) On replante automatiquement un sapling à l'emplacement d'origine.
 *  6) Si tous les coffres sont cassés, la forêt n'est plus active (PNJ et golems disparaissent).
 *  7) Persistance complète dans forests.yml (y compris la file BFS).
 */
public final class Foret implements CommandExecutor, Listener {

    private static final String FORET_SELECTOR_NAME = ChatColor.GOLD + "Sélecteur de forêt";

    private final JavaPlugin plugin;

    // Liste de toutes les sessions actives
    private final List<ForestSession> sessions = new ArrayList<>();

    // Fichier forests.yml (persistance)
    private final File forestsFile;
    private final YamlConfiguration forestsYaml;

    // Sélections en cours : joueur -> 2 corners
    private final Map<UUID, Selection> selections = new HashMap<>();

    public Foret(JavaPlugin plugin) {
        this.plugin = plugin;

        // Lier la commande /foret
        if (plugin.getCommand("foret") != null) {
            plugin.getCommand("foret").setExecutor(this);
        }

        // Enregistrer l'écoute des events (clic, blockbreak)
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Charger/ouvrir forests.yml
        this.forestsFile = new File(plugin.getDataFolder(), "forests.yml");
        this.forestsYaml = YamlConfiguration.loadConfiguration(forestsFile);
    }

    /* ========================================================= */
    /*                GESTION DE LA COMMANDE /foret              */
    /* ========================================================= */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Commande réservée aux joueurs.");
            return true;
        }

        if (!cmd.getName().equalsIgnoreCase("foret")) {
            return false;
        }

        // Donne le bâton spécial
        giveForetSelector(player);

        // Initialise la sélection
        selections.put(player.getUniqueId(), new Selection());

        player.sendMessage(ChatColor.GREEN + "Tu as reçu le bâton de sélection de forêt !");
        player.sendMessage(ChatColor.YELLOW + "Clique 2 blocs (même hauteur) avec ce bâton pour définir la zone.");
        return true;
    }

    /**
     * Donne un bâton nommé "Sélecteur de forêt" au joueur.
     */
    private void giveForetSelector(Player player) {
        ItemStack stick = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stick.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(FORET_SELECTOR_NAME);
            stick.setItemMeta(meta);
        }
        player.getInventory().addItem(stick);
    }

    /* ========================================================= */
    /*         ÉCOUTE DES CLICS (PlayerInteractEvent)            */
    /* ========================================================= */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.LEFT_CLICK_BLOCK
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack inHand = event.getItem();
        Block clicked = event.getClickedBlock();

        // Vérifie bâton "Sélecteur de forêt"
        if (inHand == null || inHand.getType() != Material.STICK) {
            return;
        }
        if (!inHand.hasItemMeta()) {
            return;
        }
        if (!FORET_SELECTOR_NAME.equals(inHand.getItemMeta().getDisplayName())) {
            return;
        }
        if (clicked == null) {
            return;
        }

        // Empêcher l'action par défaut
        event.setCancelled(true);

        // Récupère la sélection du joueur
        Selection sel = selections.get(player.getUniqueId());
        if (sel == null) {
            player.sendMessage(ChatColor.RED + "Refais /foret pour obtenir le bâton de sélection !");
            return;
        }

        // coin1 ou coin2 ?
        if (sel.corner1 == null) {
            sel.corner1 = clicked;
            player.sendMessage(ChatColor.AQUA + "Coin 1 sélectionné : " + coords(clicked));
        } else if (sel.corner2 == null) {
            sel.corner2 = clicked;
            player.sendMessage(ChatColor.AQUA + "Coin 2 sélectionné : " + coords(clicked));

            // On valide
            validateSelection(player, sel);
        } else {
            // Redéfinir corner1
            sel.corner1 = clicked;
            sel.corner2 = null;
            player.sendMessage(ChatColor.AQUA + "Coin 1 redéfini : " + coords(clicked));
        }
    }

    private String coords(Block b) {
        return "(" + b.getX() + ", " + b.getY() + ", " + b.getZ() + ")";
    }

    /**
     * Vérifie que corner1 et corner2 ont la même Y,
     * puis crée la ForestSession correspondante.
     */
    private void validateSelection(Player player, Selection sel) {
        if (sel.corner1 == null || sel.corner2 == null) {
            return;
        }
        Block c1 = sel.corner1;
        Block c2 = sel.corner2;

        if (c1.getY() != c2.getY()) {
            player.sendMessage(ChatColor.RED + "Les deux blocs doivent être à la même hauteur (Y) !");
            sel.corner2 = null;
            return;
        }

        // Détermine la zone
        World w = c1.getWorld();
        int y = c1.getY();
        int x1 = c1.getX(), x2 = c2.getX();
        int z1 = c1.getZ(), z2 = c2.getZ();

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        int width  = (maxX - minX) + 1;
        int length = (maxZ - minZ) + 1;

        Location origin = new Location(w, minX, y, minZ);

        // Crée la session
        ForestSession fs = new ForestSession(plugin, origin, width, length);
        fs.start();
        sessions.add(fs);

        player.sendMessage(ChatColor.GREEN + "Forêt créée (" + width + "×" + length + ") !");
        saveAllSessions();

        // Nettoie la sélection
        selections.remove(player.getUniqueId());
    }

    /* ========================================================= */
    /*      GESTION DU CASSAGE DE BLOCS (BlockBreakEvent)        */
    /* ========================================================= */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        // S'il s'agit d'un coffre d'une session, on le retire
        for (ForestSession fs : new ArrayList<>(sessions)) {
            if (fs.isChestBlock(block)) {
                fs.removeChest(block);
                // S'il n'y a plus de coffres => on arrête la session
                if (!fs.hasChests()) {
                    fs.stop();
                    sessions.remove(fs);
                    saveAllSessions();
                }
                break;
            }
        }
    }

    /* ========================================================= */
    /*                      PERSISTANCE                          */
    /* ========================================================= */
    public void saveAllSessions() {
        forestsYaml.set("forests", null);
        int i = 0;
        for (ForestSession fs : sessions) {
            forestsYaml.createSection("forests." + i, fs.toMap());
            i++;
        }
        try {
            forestsYaml.save(forestsFile);
        } catch (IOException e) {
            e.printStackTrace();
            plugin.getLogger().severe("[Foret] Impossible de sauvegarder forests.yml !");
        }
    }

    public void loadSavedSessions() {
        ConfigurationSection root = forestsYaml.getConfigurationSection("forests");
        if (root == null) return;

        int loaded = 0;
        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) continue;

            // Monde
            String worldUID = sec.getString("world", "");
            World w = Bukkit.getWorld(UUID.fromString(worldUID));
            if (w == null) {
                plugin.getLogger().warning("[Foret] Monde introuvable : " + worldUID);
                continue;
            }

            int bx = sec.getInt("x");
            int by = sec.getInt("y");
            int bz = sec.getInt("z");
            int width  = sec.getInt("width");
            int length = sec.getInt("length");

            // Création
            Location origin = new Location(w, bx, by, bz);
            clearZone(origin, width, length,
                    List.of("Forestier", "Golem Forestier"));

            ForestSession fs = new ForestSession(plugin, origin, width, length);

            // BFS (harvestQueue)
            List<String> harvestList = sec.getStringList("harvestQueue");
            if (!harvestList.isEmpty()) {
                for (String coords : harvestList) {
                    String[] c = coords.split(",");
                    if (c.length == 3) {
                        try {
                            int hx = Integer.parseInt(c[0]);
                            int hy = Integer.parseInt(c[1]);
                            int hz = Integer.parseInt(c[2]);
                            Block b = w.getBlockAt(hx, hy, hz);
                            fs.harvestQueue.add(b);
                        } catch (NumberFormatException ignored) { }
                    }
                }
            }

            // replantLocation
            if (sec.contains("replantLocation")) {
                List<Integer> rl = sec.getIntegerList("replantLocation");
                if (rl.size() == 3) {
                    int rx = rl.get(0);
                    int ry = rl.get(1);
                    int rz = rl.get(2);
                    fs.replantLocation = w.getBlockAt(rx, ry, rz);
                }
            }

            Bukkit.getScheduler().runTaskLater(plugin, fs::start, 20L);
            sessions.add(fs);
            loaded++;
        }
        plugin.getLogger().info("[Foret] Restauré " + loaded + " forêt(s).");
    }

    public void stopAllForests() {
        for (ForestSession fs : sessions) {
            fs.stop();
        }
        sessions.clear();
    }

    private void clearZone(Location origin, int width, int length, List<String> names) {
        World w = origin.getWorld();
        int minX = origin.getBlockX() - 2;
        int maxX = origin.getBlockX() + width + 2;
        int minZ = origin.getBlockZ() - 2;
        int maxZ = origin.getBlockZ() + length + 2;

        // Charge les chunks autour de la zone
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                w.getChunkAt(cx, cz).load();
            }
        }

        w.getEntities().forEach(e -> {
            String name = ChatColor.stripColor(e.getCustomName());
            if (name == null || !names.contains(name)) return;

            Location l = e.getLocation();
            int x = l.getBlockX();
            int z = l.getBlockZ();
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                e.remove();
            }
        });
    }

    /* ========================================================= */
    /*  CLASSE INTERNE ForestSession : zone, BFS, PNJ, etc.      */
    /* ========================================================= */
    public static final class ForestSession {
        private static final int CHESTS_PER_CORNER = 6;
        private static final Material FRAME_BLOCK  = Material.OAK_LOG;
        private static final Material LIGHT_BLOCK  = Material.SEA_LANTERN;
        private static final int FOREST_HEIGHT     = 20;

        private static final List<Material> SAPLINGS = Arrays.asList(
                Material.OAK_SAPLING,
                Material.BIRCH_SAPLING,
                Material.SPRUCE_SAPLING,
                Material.JUNGLE_SAPLING,
                Material.ACACIA_SAPLING,
                Material.DARK_OAK_SAPLING
        );

        private final JavaPlugin plugin;
        private final World world;
        private final int baseX, baseY, baseZ, width, length;

        // PNJ + golems
        private Villager forester;
        private final List<Golem> golems = new ArrayList<>();
        private BukkitRunnable forestTask;

        // Coffres
        private final List<Block> chestBlocks = new ArrayList<>();
        private int depositIndex = 0;

        // Spots de saplings
        private final List<Block> saplingSpots = new ArrayList<>();
        private int spotIndex = 0;

        // BFS
        private final Queue<Block> harvestQueue = new LinkedList<>();
        private Block replantLocation = null;

        public ForestSession(JavaPlugin plugin, Location origin, int width, int length) {
            this.plugin = plugin;
            this.world  = origin.getWorld();
            this.baseX  = origin.getBlockX();
            this.baseY  = origin.getBlockY();
            this.baseZ  = origin.getBlockZ();
            this.width  = width;
            this.length = length;
        }

        public void start() {
            buildFrame();
            buildGround();
            buildTreesGrid();
            buildChests();

            spawnOrRespawnForester();
            spawnOrRespawnGolems();

            runForestLoop();
        }

        public void stop() {
            if (forestTask != null) {
                forestTask.cancel();
                forestTask = null;
            }
            // Retire PNJ
            if (forester != null && !forester.isDead()) {
                forester.remove();
            }
            // Retire golems
            for (Golem g : golems) {
                g.remove();
            }
            golems.clear();
        }

        /* --------------------- Construction --------------------- */
        private void buildFrame() {
            for (int dx = -1; dx <= width; dx++) {
                setBlock(baseX + dx, baseY, baseZ - 1, FRAME_BLOCK);
                setBlock(baseX + dx, baseY, baseZ + length, FRAME_BLOCK);
            }
            for (int dz = -1; dz <= length; dz++) {
                setBlock(baseX - 1, baseY, baseZ + dz, FRAME_BLOCK);
                setBlock(baseX + width, baseY, baseZ + dz, FRAME_BLOCK);
            }
        }
        private void buildGround() {
            for (int dx = 0; dx < width; dx++) {
                for (int dz = 0; dz < length; dz++) {
                    setBlock(baseX + dx, baseY, baseZ + dz, Material.GRASS_BLOCK);
                }
            }
        }
        private void buildTreesGrid() {
            // On place un sapling tous les 6 blocs, + lantern au milieu
            for (int dx = 0; dx < width; dx++) {
                for (int dz = 0; dz < length; dz++) {
                    int x = baseX + dx;
                    int z = baseZ + dz;
                    if (dx % 6 == 0 && dz % 6 == 0) {
                        Material sap = randomSapling();
                        setBlock(x, baseY + 1, z, sap);
                        saplingSpots.add(world.getBlockAt(x, baseY + 1, z));
                    }
                    else if (dx % 6 == 3 && dz % 6 == 3) {
                        setBlock(x, baseY + 1, z, LIGHT_BLOCK);
                    }
                }
            }
        }
        private Material randomSapling() {
            return SAPLINGS.get(new Random().nextInt(SAPLINGS.size()));
        }
        private void buildChests() {
            createChests(new Location(world, baseX - 2,      baseY, baseZ - 2),       true);
            createChests(new Location(world, baseX + width + 1, baseY, baseZ - 2),    false);
            createChests(new Location(world, baseX - 2,      baseY, baseZ + length + 1), true);
            createChests(new Location(world, baseX + width + 1, baseY, baseZ + length + 1), false);
        }
        private void createChests(Location start, boolean positiveX) {
            for (int i = 0; i < CHESTS_PER_CORNER; i++) {
                Location loc = start.clone().add(positiveX ? i : -i, 0, 0);
                setBlock(loc, Material.CHEST);
                chestBlocks.add(loc.getBlock());
            }
        }

        /* --------------------- PNJ + Golems --------------------- */
        private void spawnOrRespawnForester() {
            if (forester != null && !forester.isDead()) return;
            Location center = new Location(world,
                    baseX + width / 2.0,
                    baseY + 1,
                    baseZ + length / 2.0);
            forester = (Villager) world.spawnEntity(center, EntityType.VILLAGER);
            forester.setCustomName("Forestier");
            forester.setCustomNameVisible(true);
            forester.setProfession(Villager.Profession.FLETCHER);
            forester.setVillagerLevel(5);
            setupTrades(forester);
        }

        /**
         * Configure les échanges du forestier :
         * 32 lingots de fer permettent d'obtenir 64 bûches de chêne.
         */
        private void setupTrades(Villager v) {
            List<MerchantRecipe> recipes = new ArrayList<>();
            recipes.add(createRecipe(Material.IRON_INGOT, 32,
                    new ItemStack(Material.OAK_LOG, 64)));
            v.setRecipes(recipes);
        }

        private MerchantRecipe createRecipe(Material matInput, int amount, ItemStack output) {
            MerchantRecipe recipe = new MerchantRecipe(output, 9999999);
            recipe.addIngredient(new ItemStack(matInput, amount));
            recipe.setExperienceReward(false);
            return recipe;
        }
        private void spawnOrRespawnGolems() {
            golems.removeIf(g -> g.getGolem().isDead());
            while (golems.size() < 2) {
                Location c = new Location(world,
                        baseX + width / 2.0,
                        baseY,
                        baseZ + length / 2.0).add(golems.size()*2.0 - 1.0, 0, -1.5);

                double radius = Math.max(1.0,
                        Math.min(width, length) / 2.0 - 1.0);

                Golem g = new Golem(plugin, c, radius);
                g.getGolem().setCustomName("Golem Forestier");
                golems.add(g);
            }
        }

        /* --------------------- Boucle 1 bloc/s --------------------- */
        private void runForestLoop() {
            forestTask = new BukkitRunnable() {
                @Override
                public void run() {
                    // PNJ
                    if (forester == null || forester.isDead()) {
                        spawnOrRespawnForester();
                    }
                    keepForesterInArea();

                    // Golems
                    spawnOrRespawnGolems();

                    // BFS en cours ou replantage en attente ?
                    if (!harvestQueue.isEmpty() || replantLocation != null) {
                        harvestNextBlock();
                        return;
                    }

                    // Sinon, on check un sapling
                    if (saplingSpots.isEmpty()) return;
                    Block saplingBlock = saplingSpots.get(spotIndex);
                    spotIndex = (spotIndex + 1) % saplingSpots.size();

                    // Si ce bloc est devenu LOG ou LEAVES => BFS
                    Material mat = saplingBlock.getType();
                    if (isLogOrLeaves(mat)) {
                        replantLocation = saplingBlock; // on stocke pour replanter
                        buildHarvestQueue(saplingBlock);
                    }
                }
            };
            forestTask.runTaskTimer(plugin, 20L, 20L);
        }

        private void keepForesterInArea() {
            if (forester == null) return;
            Location loc = forester.getLocation();
            double fx = loc.getX(), fy = loc.getY(), fz = loc.getZ();
            double minX = baseX, maxX = baseX + width;
            double minZ = baseZ, maxZ = baseZ + length;
            double minY = baseY, maxY = baseY + FOREST_HEIGHT;

            // S'il sort de la zone, on le ramène au centre
            if (fx < minX || fx > maxX || fz < minZ || fz > maxZ || fy < minY || fy > maxY) {
                Location center = new Location(world,
                        baseX + width/2.0,
                        baseY + 1.0,
                        baseZ + length/2.0);
                forester.teleport(center);
            }
        }

        /* --------------------- BFS --------------------- */
        private void buildHarvestQueue(Block start) {
            harvestQueue.clear();
            Set<Block> visited = new HashSet<>();
            Queue<Block> toVisit = new LinkedList<>();
            toVisit.add(start);

            final int MAX_RADIUS = 50;

            while (!toVisit.isEmpty()) {
                Block current = toVisit.poll();
                if (current == null) continue;
                if (!visited.add(current)) continue;

                Material mat = current.getType();
                if (!isLogOrLeaves(mat)) continue;
                if (!inBounds(current.getLocation())) continue;

                // On l'ajoute à la récolte
                harvestQueue.add(current);

                // Parcourt les 6 directions
                Location loc = current.getLocation();
                int dx = loc.getBlockX() - start.getX();
                int dy = loc.getBlockY() - start.getY();
                int dz = loc.getBlockZ() - start.getZ();
                // Limite de sécurité
                if (Math.abs(dx) > MAX_RADIUS || Math.abs(dy) > MAX_RADIUS || Math.abs(dz) > MAX_RADIUS) {
                    continue;
                }

                // Ajoute voisins
                for (int[] dir : new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) {
                    Block nb = current.getRelative(dir[0], dir[1], dir[2]);
                    if (!visited.contains(nb)) {
                        toVisit.add(nb);
                    }
                }
            }
        }
        private boolean inBounds(Location loc) {
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();
            if (x < baseX || x >= baseX + width) return false;
            if (z < baseZ || z >= baseZ + length) return false;
            // On limite la hauteur
            if (y < baseY || y > baseY + FOREST_HEIGHT) return false;
            return true;
        }
        private boolean isLogOrLeaves(Material m) {
            return m.name().endsWith("_LOG") || m.name().endsWith("_LEAVES");
        }

        /**
         * Récolte un bloc par tick.
         * Quand BFS est fini => replant.
         */
        private void harvestNextBlock() {
            Block block = harvestQueue.poll();
            if (block == null) {
                // BFS terminé => replant
                if (replantLocation != null) {
                    // On replante un nouveau sapling
                    replantLocation.setType(randomSapling());
                    replantLocation = null;
                }
                return;
            }

            // Téléporte le PNJ forestier au-dessus
            if (forester != null && !forester.isDead()) {
                forester.teleport(block.getLocation().add(0.5, 1.0, 0.5));
            }

            // Casse le bloc
            Collection<ItemStack> drops = block.getDrops();
            block.setType(Material.AIR);

            // Stocke les items en coffre
            deposit(new ArrayList<>(drops));
        }

        /**
         * Ajoute les items dans un coffre (round-robin).
         */
        private void deposit(List<ItemStack> drops) {
            if (drops.isEmpty() || chestBlocks.isEmpty()) return;
            Block chestBlock = chestBlocks.get(depositIndex % chestBlocks.size());
            depositIndex++;

            if (chestBlock.getType() == Material.CHEST) {
                Chest c = (Chest) chestBlock.getState();
                Inventory inv = c.getInventory();
                for (ItemStack it : drops) {
                    inv.addItem(it);
                }
            }
        }

        /* --------------------- Coffres cassés --------------------- */
        public boolean isChestBlock(Block b) {
            return chestBlocks.contains(b);
        }

        public void removeChest(Block b) {
            chestBlocks.remove(b);
        }

        public boolean hasChests() {
            return !chestBlocks.isEmpty();
        }

        /* --------------------- Persistance: toMap() --------------------- */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("world", world.getUID().toString());
            map.put("x", baseX);
            map.put("y", baseY);
            map.put("z", baseZ);
            map.put("width", width);
            map.put("length", length);

            // BFS en cours
            if (!harvestQueue.isEmpty()) {
                List<String> list = new ArrayList<>();
                for (Block b : harvestQueue) {
                    list.add(b.getX() + "," + b.getY() + "," + b.getZ());
                }
                map.put("harvestQueue", list);
            }
            // replantLocation
            if (replantLocation != null) {
                map.put("replantLocation", Arrays.asList(
                        replantLocation.getX(),
                        replantLocation.getY(),
                        replantLocation.getZ()
                ));
            }
            return map;
        }

        /* --------------------- Outils internes --------------------- */
        private void setBlock(int x, int y, int z, Material mat) {
            world.getBlockAt(x, y, z).setType(mat, false);
        }
        private void setBlock(Location loc, Material mat) {
            setBlock(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), mat);
        }
    }

    /* ========================================================= */
    /*        CLASSE interne Selection (2 coins cliqués)         */
    /* ========================================================= */
    private static class Selection {
        private Block corner1;
        private Block corner2;
    }
}
