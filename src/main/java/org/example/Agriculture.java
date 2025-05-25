package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Ageable;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Commande /champ
 *  1) À l'exécution de /champ => le joueur reçoit un bâton spécial "Sélecteur de champ".
 *  2) Le joueur clique deux blocs (clic gauche / clic droit) avec ce bâton, à la même hauteur.
 *  3) On génère la zone (farmland, irrigation, coffres, PNJ fermier, 2 golems...).
 *  4) Les récoltes sont stockées en coffres (récolte auto), tant que les coffres existent.
 *  5) Si tous les coffres sont cassés, le champ est désactivé (PNJ + golems retirés).
 *  6) Persistance dans farms.yml.
 */
public final class Agriculture implements CommandExecutor, Listener {

    private final JavaPlugin plugin;
    private final List<FieldSession> sessions = new ArrayList<>();

    // Fichier de persistance
    private final File farmsFile;
    private final YamlConfiguration farmsYaml;

    /**
     * Sélections en cours : joueur -> sélection (corner1, corner2).
     */
    private final Map<UUID, Selection> selections = new HashMap<>();

    /**
     * Nom du bâton spécial pour sélectionner les coins.
     */
    private static final String CHAMP_SELECTOR_NAME = ChatColor.GOLD + "Sélecteur de champ";

    public Agriculture(JavaPlugin plugin) {
        this.plugin = plugin;

        // Lie la commande /champ à cette classe
        if (plugin.getCommand("champ") != null) {
            plugin.getCommand("champ").setExecutor(this);
        }

        // Enregistrement des événements (clics + cassage coffres)
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Prépare le fichier farms.yml pour la persistance
        this.farmsFile = new File(plugin.getDataFolder(), "farms.yml");
        this.farmsYaml = YamlConfiguration.loadConfiguration(farmsFile);
    }

    /* =========================================================== */
    /*                       GESTION COMMANDE                      */
    /* =========================================================== */

    /**
     * Commande /champ : on donne un bâton spécial pour sélectionner deux coins.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Commande réservée aux joueurs.");
            return true;
        }

        if (!cmd.getName().equalsIgnoreCase("champ")) {
            return false;
        }

        // Donne le bâton spécial
        giveChampSelector(player);

        // Crée (ou réinitialise) la sélection pour le joueur
        selections.put(player.getUniqueId(), new Selection());

        player.sendMessage(ChatColor.GREEN + "Tu as reçu le bâton de sélection de champ !");
        player.sendMessage(ChatColor.YELLOW + "Clique 2 blocs (même hauteur) avec le bâton.");
        return true;
    }

    /**
     * Donne un bâton nommé "Sélecteur de champ" au joueur.
     */
    private void giveChampSelector(Player player) {
        ItemStack stick = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stick.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(CHAMP_SELECTOR_NAME);
            stick.setItemMeta(meta);
        }
        player.getInventory().addItem(stick);
    }

    /* =========================================================== */
    /*            ÉCOUTE DES CLICS (PlayerInteractEvent)           */
    /* =========================================================== */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // On vérifie si c’est un clic de bloc valide
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                && event.getAction() != org.bukkit.event.block.Action.LEFT_CLICK_BLOCK) {
            return; // on ignore
        }

        Player player = event.getPlayer();
        ItemStack itemInHand = event.getItem();
        Block clickedBlock = event.getClickedBlock();

        if (clickedBlock == null) return;

        // Vérifie si c'est le bâton "Sélecteur de champ"
        if (itemInHand == null || itemInHand.getType() != Material.STICK) {
            return;
        }
        if (!itemInHand.hasItemMeta()) {
            return;
        }
        if (!CHAMP_SELECTOR_NAME.equals(itemInHand.getItemMeta().getDisplayName())) {
            return;
        }

        // Empêche l'action par défaut
        event.setCancelled(true);

        // Récupère la sélection pour ce joueur
        Selection sel = selections.get(player.getUniqueId());
        if (sel == null) {
            player.sendMessage(ChatColor.RED + "Fais /champ pour avoir le bâton de sélection !");
            return;
        }

        // Premier coin ou deuxième coin ?
        if (sel.getCorner1() == null) {
            sel.setCorner1(clickedBlock);
            player.sendMessage(ChatColor.AQUA + "Coin 1 sélectionné : " + coords(clickedBlock));
        } else if (sel.getCorner2() == null) {
            sel.setCorner2(clickedBlock);
            player.sendMessage(ChatColor.AQUA + "Coin 2 sélectionné : " + coords(clickedBlock));

            // On tente de valider
            validateSelection(player, sel);
        } else {
            // Redéfinir corner1
            sel.setCorner1(clickedBlock);
            sel.setCorner2(null);
            player.sendMessage(ChatColor.AQUA + "Coin 1 redéfini : " + coords(clickedBlock));
        }
    }

    private String coords(Block b) {
        return "(" + b.getX() + ", " + b.getY() + ", " + b.getZ() + ")";
    }

    /**
     * Vérifie la même hauteur, crée la session du champ.
     */
    private void validateSelection(Player player, Selection sel) {
        Block c1 = sel.getCorner1();
        Block c2 = sel.getCorner2();
        if (c1 == null || c2 == null) {
            return;
        }
        if (c1.getY() != c2.getY()) {
            player.sendMessage(ChatColor.RED + "Les 2 blocs doivent être à la même hauteur !");
            sel.setCorner2(null);
            return;
        }

        World w = c1.getWorld();
        int x1 = c1.getX();
        int x2 = c2.getX();
        int z1 = c1.getZ();
        int z2 = c2.getZ();
        int y  = c1.getY();

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        int width  = (maxX - minX) + 1;
        int length = (maxZ - minZ) + 1;

        Location origin = new Location(w, minX, y, minZ);
        FieldSession fs = new FieldSession(plugin, origin, width, length);
        fs.start();
        sessions.add(fs);

        player.sendMessage(ChatColor.GREEN + "Champ créé (" + width + "×" + length + ") !");
        saveAllSessions();

        // On retire la sélection
        selections.remove(player.getUniqueId());
    }

    /* =========================================================== */
    /*               ÉVÉNEMENT : BlockBreakEvent (coffres)         */
    /* =========================================================== */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        // On vérifie si c'est un coffre d'une session
        for (FieldSession fs : new ArrayList<>(sessions)) {
            if (fs.isChest(block)) {
                fs.removeChest(block);

                // S'il n'y a plus de coffres => on arrête la session
                if (!fs.hasChests()) {
                    fs.stop();
                    sessions.remove(fs);
                    saveAllSessions();
                }
                break; // on peut s'arrêter
            }
        }
    }

    /* =========================================================== */
    /*                           PERSISTANCE                       */
    /* =========================================================== */
    public void saveAllSessions() {
        farmsYaml.set("farms", null);
        int i = 0;
        for (FieldSession fs : sessions) {
            farmsYaml.createSection("farms." + i, fs.toMap());
            i++;
        }
        try {
            farmsYaml.save(farmsFile);
        } catch (IOException e) {
            e.printStackTrace();
            plugin.getLogger().severe("Impossible de sauvegarder farms.yml !");
        }
    }

    public void loadSavedSessions() {
        ConfigurationSection root = farmsYaml.getConfigurationSection("farms");
        if (root == null) return;

        int loaded = 0;
        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) continue;

            String worldId = sec.getString("world", "");
            World w = Bukkit.getWorld(UUID.fromString(worldId));
            if (w == null) {
                plugin.getLogger().warning("[Agriculture] Monde introuvable: " + worldId);
                continue;
            }
            int bx = sec.getInt("x");
            int by = sec.getInt("y");
            int bz = sec.getInt("z");
            int width  = sec.getInt("width");
            int length = sec.getInt("length");

            Location origin = new Location(w, bx, by, bz);
            clearZone(origin, width, length,
                    List.of("Agriculteur", "Garde du champ"));

            FieldSession fs = new FieldSession(plugin, origin, width, length);
            Bukkit.getScheduler().runTaskLater(plugin, fs::start, 20L);
            sessions.add(fs);
            loaded++;
        }
        plugin.getLogger().info("[Agriculture] Restauré " + loaded + " champ(s).");
    }
    public void createField(Location origin, int width, int length) {
        FieldSession fs = new FieldSession(plugin, origin, width, length);
        fs.start();
        sessions.add(fs);
        saveAllSessions();
    }


    public void stopAllSessions() {
        for (FieldSession fs : sessions) {
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

    /* =========================================================== */
    /*                CLASSE FieldSession (un champ)              */
    /* =========================================================== */
    private static final class FieldSession {
        private static final int WATER_GRID        = 6;   // motif irrigation
        private static final int TORCH_SPACING     = 5;   // torches tous les 5 blocs
        private static final int CHESTS_PER_CORNER = 6;   // coffres par coin
        private static final Material FRAME_BLOCK  = Material.OAK_LOG;
        private static final Material LIGHT_BLOCK  = Material.SEA_LANTERN;
        private static final List<Material> CROPS  = List.of(
                Material.WHEAT_SEEDS,
                Material.POTATO,
                Material.CARROT,
                Material.BEETROOT_SEEDS
        );

        private final JavaPlugin plugin;
        private final World world;
        private final int baseX, baseY, baseZ, width, length;

        private Villager farmer;
        private final List<Golem> golems = new ArrayList<>();
        private BukkitRunnable farmTask;
        private final List<Block> farmland = new ArrayList<>();
        private final List<Block> chestBlocks = new ArrayList<>();

        private int farmIndex = 0;
        private int depositIndex = 0;

        FieldSession(JavaPlugin plugin, Location origin, int width, int length) {
            this.plugin = plugin;
            this.world  = origin.getWorld();
            this.baseX  = origin.getBlockX();
            this.baseY  = origin.getBlockY();
            this.baseZ  = origin.getBlockZ();
            this.width  = width;
            this.length = length;
        }

        void start() {
            buildFrame();
            buildInside();
            placeChests();
            placeTorches();

            spawnOrRespawnFarmer();
            spawnOrRespawnGolems();

            runFarmLoop();
        }

        void stop() {
            if (farmTask != null) {
                farmTask.cancel();
                farmTask = null;
            }
            // Retire PNJ
            if (farmer != null && !farmer.isDead()) {
                farmer.remove();
            }
            // Retire golems
            for (Golem g : golems) {
                g.remove();
            }
            golems.clear();
        }

        /* ----------------- Génération statique ------------------- */
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

        private void buildInside() {
            for (int dx = 0; dx < width; dx++) {
                for (int dz = 0; dz < length; dz++) {
                    int x = baseX + dx;
                    int z = baseZ + dz;
                    // Eau d'irrigation
                    if ((dx + WATER_GRID/2) % WATER_GRID == 0 &&
                            (dz + WATER_GRID/2) % WATER_GRID == 0) {
                        setBlock(x, baseY - 1, z, LIGHT_BLOCK);
                        setBlock(x, baseY,     z, Material.WATER);
                    } else {
                        setBlock(x, baseY, z, Material.FARMLAND);
                        farmland.add(world.getBlockAt(x, baseY, z));
                    }
                }
            }
        }

        private void placeChests() {
            // Coin Nord-Ouest
            createChests(new Location(world, baseX - 2, baseY, baseZ - 2), true);
            // Coin Nord-Est
            createChests(new Location(world, baseX + width + 1, baseY, baseZ - 2), false);
            // Coin Sud-Ouest
            createChests(new Location(world, baseX - 2, baseY, baseZ + length + 1), true);
            // Coin Sud-Est
            createChests(new Location(world, baseX + width + 1, baseY, baseZ + length + 1), false);
        }

        private void createChests(Location start, boolean positiveX) {
            for (int i = 0; i < CHESTS_PER_CORNER; i++) {
                Location loc = start.clone().add(positiveX ? i : -i, 0, 0);
                setBlock(loc, Material.CHEST);
                chestBlocks.add(loc.getBlock());
            }
        }

        private void placeTorches() {
            for (int dx = 0; dx <= width; dx += TORCH_SPACING) {
                setBlock(baseX + dx, baseY + 1, baseZ - 1, Material.TORCH);
                setBlock(baseX + dx, baseY + 1, baseZ + length, Material.TORCH);
            }
            for (int dz = 0; dz <= length; dz += TORCH_SPACING) {
                setBlock(baseX - 1, baseY + 1, baseZ + dz, Material.TORCH);
                setBlock(baseX + width, baseY + 1, baseZ + dz, Material.TORCH);
            }
        }

        /* ----------------- PNJ & golems ------------------- */
        private void spawnOrRespawnFarmer() {
            if (farmer != null && !farmer.isDead()) return;
            Location spawn = new Location(world,
                    baseX + width / 2.0,
                    baseY + 1,
                    baseZ + length / 2.0);
            farmer = (Villager) world.spawnEntity(spawn, EntityType.VILLAGER);
            farmer.setCustomName("Agriculteur");
            farmer.setCustomNameVisible(true);
            farmer.setProfession(Villager.Profession.FARMER);
            farmer.setAI(false); // PNJ sans IA, juste téléporté
        }

        private void spawnOrRespawnGolems() {
            golems.removeIf(g -> g.getGolem().isDead());
            while (golems.size() < 2) {
                Location c = new Location(world,
                        baseX + width / 2.0,
                        baseY,
                        baseZ + length / 2.0);
                Location spot = (golems.isEmpty())
                        ? c.clone().add(-1.5, 0, -1.5)
                        : c.clone().add( 1.5, 0, -1.5);

                double radius = Math.max(1.0,
                        Math.min(width, length) / 2.0 - 1.0);

                Golem g = new Golem(plugin, spot, radius);
                golems.add(g);
            }
        }

        /* ----------------- Boucle farmland 1 bloc/s ------------------- */
        private void runFarmLoop() {
            farmTask = new BukkitRunnable() {
                @Override
                public void run() {
                    // Vérif PNJ
                    if (farmer == null || farmer.isDead()) {
                        spawnOrRespawnFarmer();
                    }
                    spawnOrRespawnGolems();

                    // Champ vide ?
                    if (farmland.isEmpty()) {
                        return;
                    }

                    // On traite 1 bloc farmland par seconde
                    Block soil = farmland.get(farmIndex);
                    farmIndex = (farmIndex + 1) % farmland.size();

                    // Bloc au-dessus
                    Block above = soil.getRelative(0, 1, 0);
                    Material mat = above.getType();

                    // TP PNJ "pour le show"
                    Location posAbove = above.getLocation().add(0.5, 0, 0.5);
                    farmer.teleport(posAbove);

                    // 1) Crop mûr ?
                    if (above.getBlockData() instanceof Ageable age
                            && age.getAge() == age.getMaximumAge()) {
                        Collection<ItemStack> drops = above.getDrops();
                        above.setType(Material.AIR);
                        deposit(drops);
                        replant(above);
                    }
                    // 2) Air => planter
                    else if (mat == Material.AIR) {
                        replant(above);
                    }
                    // sinon, on ne fait rien (croissance)
                }
            };
            farmTask.runTaskTimer(plugin, 20L, 20L);
        }

        /**
         * Plante une graine aléatoire
         */
        private void replant(Block block) {
            Material seed = CROPS.get(ThreadLocalRandom.current().nextInt(CROPS.size()));
            Material cropType = switch (seed) {
                case WHEAT_SEEDS    -> Material.WHEAT;
                case POTATO         -> Material.POTATOES;
                case CARROT         -> Material.CARROTS;
                case BEETROOT_SEEDS -> Material.BEETROOTS;
                default             -> Material.WHEAT;
            };
            block.setType(cropType, false);
        }

        /**
         * Stocke les items dans un coffre (round-robin)
         */
        private void deposit(Collection<ItemStack> items) {
            if (chestBlocks.isEmpty()) {
                return;
            }
            Block chestB = chestBlocks.get(depositIndex % chestBlocks.size());
            depositIndex++;

            if (chestB.getType() == Material.CHEST) {
                Chest c = (Chest) chestB.getState();
                for (ItemStack drop : items) {
                    c.getInventory().addItem(drop);
                }
            }
        }

        /* ----------------- Vérification coffres cassés ------------------- */
        public boolean isChest(Block block) {
            return chestBlocks.contains(block);
        }
        public void removeChest(Block block) {
            chestBlocks.remove(block);
        }
        public boolean hasChests() {
            return !chestBlocks.isEmpty();
        }

        /* ----------------- Persistance ------------------- */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("world", world.getUID().toString());
            map.put("x", baseX);
            map.put("y", baseY);
            map.put("z", baseZ);
            map.put("width",  width);
            map.put("length", length);
            return map;
        }

        /* ----------------- Outils ------------------- */
        private void setBlock(int x, int y, int z, Material mat) {
            world.getBlockAt(x, y, z).setType(mat, false);
        }
        private void setBlock(Location loc, Material mat) {
            setBlock(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), mat);
        }
    }

    /* =========================================================== */
    /*        CLASSE Selection : mémorise 2 coins cliqués         */
    /* =========================================================== */
    private static final class Selection {
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
    }
}
