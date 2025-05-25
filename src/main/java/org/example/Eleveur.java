package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Golem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
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
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * =========================================================
 *                Système d’élevage automatique
 * =========================================================
 *
 * Commande /eleveur :
 *   1) Donne un bâton « Sélecteur d’élevage ».
 *   2) On clique 2 blocs (même hauteur) pour définir la zone.
 *   3) Génère un ranch "premium" :
 *       - Soubassement en deepslate, poteaux en bois écorcé
 *       - Murs en palette (planches, barils), toiture légère
 *       - Chemins, coffres, spawners, PNJ éleveur, golems
 *   4) Limite d’animaux (ANIMAL_LIMIT). Si dépassée :
 *      l’éleveur tue l’excès et fait tomber la viande au sol.
 *   5) Le PNJ ramasse les items et les dépose dans les coffres.
 *   6) Scoreboard dans l’enclos, persistance dans ranches.yml
 */
public final class Eleveur implements CommandExecutor, Listener {

    // Nom du bâton de sélection
    private static final String RANCH_SELECTOR_NAME = ChatColor.GOLD + "Sélecteur d'élevage";

    // Espèces concernées par la limitation
    private static final List<EntityType> MAIN_SPECIES = Arrays.asList(
            EntityType.CHICKEN,
            EntityType.COW,
            EntityType.PIG,
            EntityType.SHEEP
    );

    private final JavaPlugin plugin;

    // Liste des sessions actives
    private final List<RanchSession> sessions = new ArrayList<>();

    // Fichier ranches.yml
    private final File ranchFile;
    private final YamlConfiguration ranchYaml;

    // Sélections en cours : (joueur) -> (coin1, coin2)
    private final Map<UUID, Selection> selections = new HashMap<>();

    // Scoreboards en cours : (joueur) -> scoreboard
    private final Map<UUID, Scoreboard> playerScoreboards = new HashMap<>();

    // Task d’actualisation du scoreboard
    private BukkitRunnable scoreboardTask;

    public Eleveur(JavaPlugin plugin) {
        this.plugin = plugin;

        // Lier la commande /eleveur
        if (plugin.getCommand("eleveur") != null) {
            plugin.getCommand("eleveur").setExecutor(this);
        }

        // S'inscrire comme listener
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Préparer ranches.yml
        ranchFile = new File(plugin.getDataFolder(), "ranches.yml");
        ranchYaml = YamlConfiguration.loadConfiguration(ranchFile);

        // Lancer la boucle d’affichage scoreboard
        startScoreboardLoop();
    }

    /* ============================================================
     *                 Commande /eleveur
     * ============================================================
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Cette commande doit être exécutée par un joueur.");
            return true;
        }
        if (!cmd.getName().equalsIgnoreCase("eleveur")) {
            return false;
        }

        giveRanchSelector(player);
        // Initialise la sélection
        selections.put(player.getUniqueId(), new Selection());

        player.sendMessage(ChatColor.GREEN + "Tu as reçu le bâton de sélection d'élevage !");
        player.sendMessage(ChatColor.YELLOW + "Clique 2 blocs (même hauteur) pour définir l'enclos.");
        return true;
    }

    private void giveRanchSelector(Player player) {
        ItemStack stick = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stick.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(RANCH_SELECTOR_NAME);
            stick.setItemMeta(meta);
        }
        player.getInventory().addItem(stick);
    }

    /* ============================================================
     *          Sélection des coins (PlayerInteractEvent)
     * ============================================================
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == null) return;
        switch (event.getAction()) {
            case LEFT_CLICK_BLOCK, RIGHT_CLICK_BLOCK -> {
                // Traite ci-dessous
            }
            default -> {
                return;
            }
        }

        Player player = event.getPlayer();
        ItemStack inHand = event.getItem();
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        // Vérifier le bâton "Sélecteur d'élevage"
        if (inHand == null ||
                inHand.getType() != Material.STICK ||
                !inHand.hasItemMeta() ||
                !RANCH_SELECTOR_NAME.equals(inHand.getItemMeta().getDisplayName())) {
            return;
        }

        // Empêcher l'action par défaut
        event.setCancelled(true);

        // Récupérer la sélection
        Selection sel = selections.get(player.getUniqueId());
        if (sel == null) {
            player.sendMessage(ChatColor.RED + "Refais /eleveur pour obtenir le bâton de sélection !");
            return;
        }

        if (sel.corner1 == null) {
            sel.corner1 = clicked;
            player.sendMessage(ChatColor.AQUA + "Coin 1 sélectionné : " + coords(clicked));
        } else if (sel.corner2 == null) {
            sel.corner2 = clicked;
            player.sendMessage(ChatColor.AQUA + "Coin 2 sélectionné : " + coords(clicked));
            // On valide
            validateSelection(player, sel);
        } else {
            // Redéfinir corner1 si tout est déjà rempli
            sel.corner1 = clicked;
            sel.corner2 = null;
            player.sendMessage(ChatColor.AQUA + "Coin 1 redéfini : " + coords(clicked));
        }
    }

    private String coords(Block b) {
        return "(" + b.getX() + ", " + b.getY() + ", " + b.getZ() + ")";
    }

    private void validateSelection(Player player, Selection sel) {
        Block c1 = sel.corner1;
        Block c2 = sel.corner2;
        if (c1 == null || c2 == null) return;

        // Vérifier même hauteur
        if (c1.getY() != c2.getY()) {
            player.sendMessage(ChatColor.RED + "Les 2 blocs doivent être à la même hauteur !");
            sel.corner2 = null;
            return;
        }

        World w = c1.getWorld();
        int y = c1.getY();
        int x1 = c1.getX(), x2 = c2.getX();
        int z1 = c1.getZ(), z2 = c2.getZ();

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        int width  = maxX - minX + 1;
        int length = maxZ - minZ + 1;

        Location origin = new Location(w, minX, y, minZ);
        RanchSession rs = new RanchSession(plugin, origin, width, length);
        rs.start();
        sessions.add(rs);

        player.sendMessage(ChatColor.GREEN + "Enclos créé (" + width + "×" + length + ") !");
        saveAllSessions();

        // Nettoyer la sélection
        selections.remove(player.getUniqueId());
    }

    /* ============================================================
     *        Interception du cassage de bloc (BlockBreakEvent)
     * ============================================================
     */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player p = event.getPlayer();

        // 1) Interdire le cassage d’un spawner si pas op
        if (block.getType() == Material.SPAWNER && !p.isOp()) {
            for (RanchSession rs : sessions) {
                if (rs.isInside(block.getLocation())) {
                    event.setCancelled(true);
                    p.sendMessage(ChatColor.RED + "Ce spawner est protégé, vous ne pouvez pas le casser !");
                    return;
                }
            }
        }

        // 2) Coffre cassé => remove
        for (RanchSession rs : new ArrayList<>(sessions)) {
            if (rs.isChestBlock(block)) {
                rs.removeChest(block);
                if (!rs.hasChests()) {
                    rs.stop();
                    sessions.remove(rs);
                    saveAllSessions();
                }
                break;
            }
        }
    }

    /* ============================================================
     *                    Persistance YAML
     * ============================================================
     */
    public void saveAllSessions() {
        ranchYaml.set("ranches", null);
        int i = 0;
        for (RanchSession rs : sessions) {
            ranchYaml.createSection("ranches." + i, rs.toMap());
            i++;
        }
        try {
            ranchYaml.save(ranchFile);
        } catch (IOException e) {
            e.printStackTrace();
            plugin.getLogger().severe("[Eleveur] Impossible de sauvegarder ranches.yml !");
        }
    }

    public void loadSavedSessions() {
        ConfigurationSection root = ranchYaml.getConfigurationSection("ranches");
        if (root == null) return;

        int loaded = 0;
        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) continue;

            String worldUID = sec.getString("world", "");
            World w = Bukkit.getWorld(UUID.fromString(worldUID));
            if (w == null) {
                plugin.getLogger().warning("[Eleveur] Monde introuvable : " + worldUID);
                continue;
            }
            int bx = sec.getInt("x");
            int by = sec.getInt("y");
            int bz = sec.getInt("z");
            int width  = sec.getInt("width");
            int length = sec.getInt("length");

            Location origin = new Location(w, bx, by, bz);
            clearZone(origin, width, length, List.of("Éleveur", "Golem Éleveur"));

            RanchSession rs = new RanchSession(plugin, origin, width, length);
            // Démarrer l'enclos au tick suivant
            Bukkit.getScheduler().runTaskLater(plugin, rs::start, 20L);
            sessions.add(rs);
            loaded++;
        }
        plugin.getLogger().info("[Eleveur] " + loaded + " enclos rechargé(s).");
    }

    /**
     * Nettoie PNJ/golems en double lors d’un reload éventuel.
     */
    private void clearZone(Location origin, int width, int length, List<String> relevantNames) {
        World w = origin.getWorld();
        int minX = origin.getBlockX() - 2;
        int maxX = origin.getBlockX() + width + 2;
        int minZ = origin.getBlockZ() - 2;
        int maxZ = origin.getBlockZ() + length + 2;

        // Charger les chunks concernés
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                w.getChunkAt(cx, cz).load();
            }
        }

        // Supprimer PNJ/golems en double
        w.getEntities().forEach(e -> {
            String name = ChatColor.stripColor(e.getCustomName());
            if (name == null) return;
            if (!relevantNames.contains(name)) return;

            Location l = e.getLocation();
            int x = l.getBlockX();
            int z = l.getBlockZ();
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
                e.remove();
            }
        });
    }

    public void stopAllRanches() {
        if (scoreboardTask != null) {
            scoreboardTask.cancel();
        }
        for (RanchSession rs : sessions) {
            rs.stop();
        }
        sessions.clear();
    }

    /* ============================================================
     *                    Boucle scoreboard
     * ============================================================
     */
    private void startScoreboardLoop() {
        scoreboardTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    RanchSession inside = findSessionForPlayer(p);
                    if (inside == null) {
                        removeScoreboard(p);
                    } else {
                        updateScoreboard(p, inside);
                    }
                }
            }
        };
        scoreboardTask.runTaskTimer(plugin, 20L, 20L); // chaque seconde
    }

    private RanchSession findSessionForPlayer(Player p) {
        Location loc = p.getLocation();
        for (RanchSession rs : sessions) {
            if (rs.isInside(loc)) return rs;
        }
        return null;
    }

    private void removeScoreboard(Player p) {
        if (playerScoreboards.containsKey(p.getUniqueId())) {
            p.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
            playerScoreboards.remove(p.getUniqueId());
        }
    }

    private void updateScoreboard(Player p, RanchSession session) {
        // Scoreboard existant ou nouveau
        Scoreboard sb = playerScoreboards.get(p.getUniqueId());
        if (sb == null) {
            sb = Bukkit.getScoreboardManager().getNewScoreboard();
            playerScoreboards.put(p.getUniqueId(), sb);
        }

        Objective obj = sb.getObjective("ranchInfo");
        if (obj == null) {
            obj = sb.registerNewObjective("ranchInfo", Criteria.DUMMY, ChatColor.GOLD + "Enclos");
        }
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Vider anciens scores
        for (String entry : sb.getEntries()) {
            sb.resetScores(entry);
        }

        // Titre
        String title = ChatColor.YELLOW + "== Enclos ==";
        obj.getScore(title).setScore(999);

        // Nbre d’animaux
        Map<EntityType,Integer> counts = session.countAnimals();
        int line = 998;
        for (EntityType type : MAIN_SPECIES) {
            int c = counts.getOrDefault(type, 0);
            String s = ChatColor.GREEN + type.name() + ": " + c;
            obj.getScore(s).setScore(line--);
        }

        p.setScoreboard(sb);
    }

    /* ============================================================
     *   Classe interne : session ranch
     * ============================================================
     */
    private static final class RanchSession {
        private final JavaPlugin plugin;
        private final World world;
        private final int baseX, baseY, baseZ;
        private final int width, length;

        private Villager rancher;
        private final List<Golem> golems = new ArrayList<>();
        private final List<Block> chestBlocks = new ArrayList<>();

        private BukkitRunnable ranchTask;

        // Limite d’animaux par espèce
        private static final int ANIMAL_LIMIT = 5;

        // Délai de boucle (2 s)
        private static final int RANCH_LOOP_PERIOD_TICKS = 40;

        // Hauteur du mur, 1 bloc de fondation + 2 blocs palette + 1 slab
        private static final int WALL_FOUNDATION_HEIGHT = 1;
        private static final int WALL_BODY_HEIGHT       = 2;
        private static final int TOTAL_WALL_HEIGHT      = WALL_FOUNDATION_HEIGHT + WALL_BODY_HEIGHT;

        // Matériaux
        private static final Material FOUNDATION_MATERIAL = Material.COBBLED_DEEPSLATE;
        private static final Material[] WALL_PALETTE = {
                Material.OAK_PLANKS, Material.BIRCH_PLANKS,
                Material.BARREL,     Material.OAK_PLANKS
        };
        private static final Material POLE_MATERIAL       = Material.STRIPPED_SPRUCE_LOG;
        private static final Material GROUND_BLOCK        = Material.GRASS_BLOCK;

        // Pour le random
        private static final Random RNG = new Random();

        // Index pour le round-robin des coffres
        private int lastChestIndex = 0;

        RanchSession(JavaPlugin plugin, Location origin, int width, int length) {
            this.plugin = plugin;
            this.world = origin.getWorld();
            this.baseX = origin.getBlockX();
            this.baseY = origin.getBlockY();
            this.baseZ = origin.getBlockZ();
            this.width = width;
            this.length = length;
        }

        public void start() {
            buildWalls();
            buildGround();
            applyPath();   // Chemin central
            buildRoof();   // Petit toit décoratif

            placeChests();
            placeSpawners();

            spawnOrRespawnRancher();
            spawnOrRespawnGolems();

            runRanchLoop();
        }

        public void stop() {
            if (ranchTask != null) {
                ranchTask.cancel();
                ranchTask = null;
            }
            if (rancher != null && !rancher.isDead()) {
                rancher.remove();
            }
            for (Golem g : golems) {
                if (!g.isDead()) {
                    g.remove();
                }
            }
            golems.clear();
        }

        /**
         * Détermine si une location est à l'intérieur (X, Z + 10 blocs de hauteur).
         */
        public boolean isInside(Location loc) {
            if (loc.getWorld() == null || !loc.getWorld().equals(world)) return false;
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();
            if (x < baseX || x >= baseX + width) return false;
            if (z < baseZ || z >= baseZ + length) return false;
            return (y >= baseY && y <= baseY + 10);
        }

        /* ========================================================
         *   Construction de l’enclos
         * ========================================================
         */

        /**
         * Soubassement en cobbled deepslate + 2 blocs palette + slab
         * + poteaux tous les 5 blocs.
         */
        private void buildWalls() {
            // Faire le pourtour
            for (int dx = 0; dx < width; dx++) {
                for (int dz = 0; dz < length; dz++) {
                    // Sur le périmètre ?
                    boolean onEdge = (dx == 0 || dz == 0 || dx == width - 1 || dz == length - 1);
                    if (!onEdge) continue;

                    // On empile la hauteur
                    for (int h = 0; h < TOTAL_WALL_HEIGHT; h++) {
                        Block b = world.getBlockAt(baseX + dx, baseY + 1 + h, baseZ + dz);

                        // Poteau tous les 5 blocs
                        boolean isPole = ((dx % 5 == 0 && (dz == 0 || dz == length - 1))
                                || (dz % 5 == 0 && (dx == 0 || dx == width  - 1)));

                        if (isPole) {
                            b.setType(POLE_MATERIAL);
                        }
                        else {
                            if (h < WALL_FOUNDATION_HEIGHT) {
                                b.setType(FOUNDATION_MATERIAL); // soubassement
                            } else {
                                // partie palette
                                b.setType(chooseWallBlock(RNG));
                            }
                        }

                        // On place une slab au-dessus (anti-spawn)
                        if (h == TOTAL_WALL_HEIGHT - 1) {
                            Block slabBlock = b.getRelative(BlockFace.UP);
                            slabBlock.setType(Material.OAK_SLAB);
                        }
                    }
                }
            }

            // On ajoute 4 "portes" centrées
            int gateX = baseX + width / 2;
            int gateZ = baseZ + length / 2;

            placeGate(gateX, baseY, baseZ);
            placeGate(gateX, baseY, baseZ + length - 1);
            placeGate(baseX, baseY, gateZ);
            placeGate(baseX + width - 1, baseY, gateZ);
        }

        private Material chooseWallBlock(Random rng) {
            return WALL_PALETTE[rng.nextInt(WALL_PALETTE.length)];
        }

        private void placeGate(int x, int y, int z) {
            // On détruit la palette sur 2-3 hauteurs
            for (int h = 1; h <= TOTAL_WALL_HEIGHT; h++) {
                world.getBlockAt(x, y + h, z).setType(Material.AIR);
            }
            // On place la fence gate
            Block gateBlock = world.getBlockAt(x, y + 1, z);
            gateBlock.setType(Material.DARK_OAK_FENCE_GATE);
        }

        private void buildGround() {
            // Base du sol en herbe
            for (int dx = 0; dx < width; dx++) {
                for (int dz = 0; dz < length; dz++) {
                    setBlock(baseX + dx, baseY, baseZ + dz, GROUND_BLOCK);
                }
            }
        }

        /**
         * Applique un chemin central de 3 blocs de large (axe Z médian).
         */
        private void applyPath() {
            int pathZ = length / 2;
            for (int dx = 0; dx < width; dx++) {
                for (int dz = pathZ - 1; dz <= pathZ + 1; dz++) {
                    if (dz < 0 || dz >= length) continue;
                    Block ground = world.getBlockAt(baseX + dx, baseY, baseZ + dz);
                    ground.setType(Material.DIRT_PATH);
                }
            }
        }

        /**
         * Petit toit décoratif en escalier sur le milieu.
         * - Approche simplifiée : pose de SPRUCE_STAIRS en pignon.
         */
        private void buildRoof() {
            // On va placer des SPRUCE_STAIRS au-dessus du mur,
            // façon pignon (ex. "2 pentes" simplifiées).
            int roofY = baseY + TOTAL_WALL_HEIGHT + 1;
            for (int dx = 0; dx < width; dx++) {
                // façade Nord
                Block frontN = world.getBlockAt(baseX + dx, roofY, baseZ);
                frontN.setType(Material.SPRUCE_STAIRS);

                // façade Sud
                Block frontS = world.getBlockAt(baseX + dx, roofY, baseZ + length - 1);
                frontS.setType(Material.SPRUCE_STAIRS);
            }
            for (int dz = 0; dz < length; dz++) {
                // façade Ouest
                Block sideW = world.getBlockAt(baseX, roofY, baseZ + dz);
                sideW.setType(Material.SPRUCE_STAIRS);

                // façade Est
                Block sideE = world.getBlockAt(baseX + width - 1, roofY, baseZ + dz);
                sideE.setType(Material.SPRUCE_STAIRS);
            }
            // (Approche rudimentaire ; à affiner si besoin.)
        }

        private void placeChests() {
            // 4 coins externes
            createChests(new Location(world, baseX - 2, baseY, baseZ - 2), true);
            createChests(new Location(world, baseX + width + 1, baseY, baseZ - 2), false);
            createChests(new Location(world, baseX - 2, baseY, baseZ + length + 1), true);
            createChests(new Location(world, baseX + width + 1, baseY, baseZ + length + 1), false);
        }

        private void createChests(Location start, boolean positiveX) {
            int CHESTS_PER_CORNER = 6;
            for (int i = 0; i < CHESTS_PER_CORNER; i++) {
                Location loc = start.clone().add(positiveX ? i : -i, 0, 0);
                setBlock(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), Material.CHEST);
                chestBlocks.add(loc.getBlock());
            }
        }

        private void placeSpawners() {
            int centerX = baseX + width / 2;
            int centerZ = baseZ + length / 2;
            int[][] offsets = {{0,0}, {1,0}, {0,1}, {1,1}};
            int i = 0;
            for (EntityType type : MAIN_SPECIES) {
                if (i >= offsets.length) break;
                int[] off = offsets[i++];
                Location spawnerLoc = new Location(world, centerX + off[0], baseY + 1, centerZ + off[1]);
                setBlock(spawnerLoc.getBlockX(), spawnerLoc.getBlockY(), spawnerLoc.getBlockZ(), Material.SPAWNER);

                if (spawnerLoc.getBlock().getState() instanceof CreatureSpawner cs) {
                    cs.setSpawnedType(type);
                    cs.update();
                }
            }
        }

        /* ========================================================
         *    PNJ, golems
         * ========================================================
         */
        private void spawnOrRespawnRancher() {
            if (rancher != null && !rancher.isDead()) return;
            Location center = new Location(world,
                    baseX + width / 2.0,
                    baseY + 1,
                    baseZ + length / 2.0);
            rancher = (Villager) world.spawnEntity(center, EntityType.VILLAGER);
            rancher.setCustomName("Éleveur");
            rancher.setCustomNameVisible(true);
            rancher.setProfession(Villager.Profession.BUTCHER);
            rancher.setVillagerLevel(5);
            rancher.setCanPickupItems(true); // Permet de ramasser la viande tombée

            setupTrades(rancher);
        }

        private void setupTrades(Villager v) {
            List<MerchantRecipe> recipes = new ArrayList<>();
            // Ex. Achat 64 viandes contre diamants
            recipes.add(createRecipe(Material.DIAMOND, 1, new ItemStack(Material.BEEF, 64)));
            recipes.add(createRecipe(Material.DIAMOND, 1, new ItemStack(Material.PORKCHOP, 64)));
            recipes.add(createRecipe(Material.DIAMOND, 1, new ItemStack(Material.CHICKEN, 64)));
            recipes.add(createRecipe(Material.DIAMOND, 1, new ItemStack(Material.MUTTON, 64)));

            recipes.add(createRecipe(Material.DIAMOND, 2, new ItemStack(Material.COOKED_BEEF, 64)));
            recipes.add(createRecipe(Material.DIAMOND, 2, new ItemStack(Material.COOKED_PORKCHOP, 64)));
            recipes.add(createRecipe(Material.DIAMOND, 2, new ItemStack(Material.COOKED_CHICKEN, 64)));
            recipes.add(createRecipe(Material.DIAMOND, 2, new ItemStack(Material.COOKED_MUTTON, 64)));

            v.setRecipes(recipes);
        }

        private MerchantRecipe createRecipe(Material matInput, int amount, ItemStack output) {
            MerchantRecipe recipe = new MerchantRecipe(output, 9999999); // maxUses
            recipe.addIngredient(new ItemStack(matInput, amount));
            recipe.setExperienceReward(false);
            return recipe;
        }

        private void spawnOrRespawnGolems() {
            // Supprimer golems morts
            golems.removeIf(Golem::isDead);

            // On veut 2 golems
            while (golems.size() < 2) {
                Location c = new Location(world,
                        baseX + width / 2.0,
                        baseY,
                        baseZ + length / 2.0).add(golems.size() * 2.0 - 1.0, 0, -1.5);
                Golem g = (Golem) world.spawnEntity(c, EntityType.IRON_GOLEM);
                g.setCustomName("Golem Éleveur");
                golems.add(g);
            }
        }

        /* ========================================================
         *   Boucle ranch : cull + PNJ stock
         * ========================================================
         */
        private void runRanchLoop() {
            ranchTask = new BukkitRunnable() {
                @Override
                public void run() {
                    // Respawn PNJ si besoin
                    if (rancher == null || rancher.isDead()) {
                        spawnOrRespawnRancher();
                    }
                    // Respawn golems
                    spawnOrRespawnGolems();

                    // Tuer excès
                    for (EntityType type : MAIN_SPECIES) {
                        cullExcessAnimals(type);
                    }

                    // Transférer inventaire PNJ vers coffres
                    if (rancher != null) {
                        transferPNJInventory();
                    }
                }
            };
            ranchTask.runTaskTimer(plugin, 20L, RANCH_LOOP_PERIOD_TICKS);
        }

        /**
         * Tue l'excès d'animaux, fait tomber leur loot au sol.
         * Le PNJ pourra le ramasser grâce à setCanPickupItems(true).
         */
        private void cullExcessAnimals(EntityType type) {
            List<LivingEntity> inZone = getEntitiesInZone(type);
            int surplus = inZone.size() - ANIMAL_LIMIT;
            if (surplus <= 0) return;

            for (int i = 0; i < surplus; i++) {
                if (inZone.isEmpty()) break;
                LivingEntity victim = inZone.remove(inZone.size() - 1);

                // PNJ se TP au-dessus pour "l’effet"
                if (rancher != null && !rancher.isDead()) {
                    rancher.teleportAsync(victim.getLocation().add(0.5, 1, 0.5));
                }

                // Génère drops
                List<ItemStack> drops = simulateLoot(type);
                // On fait tomber les loots (et PNJ les ramassera)
                for (ItemStack it : drops) {
                    world.dropItemNaturally(victim.getLocation(), it);
                }

                // Retirer l’animal
                victim.remove();
            }
        }

        private List<LivingEntity> getEntitiesInZone(EntityType type) {
            List<LivingEntity> result = new ArrayList<>();
            int minX = baseX, maxX = baseX + width - 1;
            int minZ = baseZ, maxZ = baseZ + length - 1;
            int minY = baseY, maxY = baseY + 10;
            for (Entity e : world.getEntities()) {
                if (e.getType() == type && e instanceof LivingEntity le) {
                    Location loc = e.getLocation();
                    int x = loc.getBlockX();
                    int y = loc.getBlockY();
                    int z = loc.getBlockZ();
                    if (x >= minX && x <= maxX &&
                            z >= minZ && z <= maxZ &&
                            y >= minY && y <= maxY) {
                        result.add(le);
                    }
                }
            }
            return result;
        }

        /**
         * Loot basique : 5% cuit.
         */
        private List<ItemStack> simulateLoot(EntityType type) {
            List<ItemStack> loot = new ArrayList<>();
            int chanceCooked = 5;
            switch (type) {
                case COW -> {
                    int beef = 1 + RNG.nextInt(3);
                    int leather = RNG.nextInt(3);
                    Material rawBeef = (RNG.nextInt(100) < chanceCooked) ? Material.COOKED_BEEF : Material.BEEF;
                    loot.add(new ItemStack(rawBeef, beef));
                    if (leather > 0) {
                        loot.add(new ItemStack(Material.LEATHER, leather));
                    }
                }
                case CHICKEN -> {
                    int c = 1 + RNG.nextInt(2);
                    int f = RNG.nextInt(3);
                    Material raw = (RNG.nextInt(100) < chanceCooked) ? Material.COOKED_CHICKEN : Material.CHICKEN;
                    loot.add(new ItemStack(raw, c));
                    if (f > 0) {
                        loot.add(new ItemStack(Material.FEATHER, f));
                    }
                }
                case PIG -> {
                    int p = 1 + RNG.nextInt(3);
                    Material raw = (RNG.nextInt(100) < chanceCooked) ? Material.COOKED_PORKCHOP : Material.PORKCHOP;
                    loot.add(new ItemStack(raw, p));
                }
                case SHEEP -> {
                    int m = 1 + RNG.nextInt(2);
                    Material raw = (RNG.nextInt(100) < chanceCooked) ? Material.COOKED_MUTTON : Material.MUTTON;
                    loot.add(new ItemStack(raw, m));
                    loot.add(new ItemStack(Material.WHITE_WOOL, 1));
                }
                default -> {}
            }
            return loot;
        }

        /**
         * À chaque boucle, on vide l’inventaire du PNJ boucher dans les coffres.
         */
        private void transferPNJInventory() {
            if (rancher == null) return;
            Inventory inv = rancher.getInventory();

            // On parcourt une copie pour éviter les modifications concurrentes
            ItemStack[] contents = inv.getContents();
            for (ItemStack stack : contents) {
                if (stack == null) continue;

                // On dépose
                deposit(Collections.singletonList(stack));
                // On retire de l'inventaire PNJ
                inv.remove(stack);
            }
        }

        /**
         * Dépose une liste d’items en round-robin dans les coffres restants.
         * S’il n’y a aucun coffre ou plus de place, on drop au sol (centre).
         */
        private void deposit(List<ItemStack> items) {
            if (items.isEmpty()) return;
            chestBlocks.removeIf(b -> b.getType() != Material.CHEST);

            if (chestBlocks.isEmpty()) {
                // Plus de coffres => drop au centre
                Location center = new Location(world,
                        baseX + width / 2.0,
                        baseY + 1,
                        baseZ + length / 2.0);
                for (ItemStack it : items) {
                    world.dropItemNaturally(center, it);
                }
                return;
            }

            int size = chestBlocks.size();
            int start = lastChestIndex % size;
            for (ItemStack stack : new ArrayList<>(items)) {
                ItemStack remaining = stack;
                int idx = start;
                int loops = 0;

                // Tenter de stocker la stack
                while (remaining != null && remaining.getAmount() > 0 && loops < size) {
                    Block b = chestBlocks.get(idx);
                    if (b.getState() instanceof Chest c) {
                        Map<Integer, ItemStack> leftover = c.getInventory().addItem(remaining);
                        c.update();
                        if (leftover.isEmpty()) {
                            remaining = null;
                        } else {
                            remaining = leftover.values().iterator().next();
                        }
                    }
                    idx = (idx + 1) % size;
                    loops++;
                }

                // Si restes, on drop au sol
                if (remaining != null && remaining.getAmount() > 0) {
                    Location center = new Location(world,
                            baseX + width / 2.0,
                            baseY + 1,
                            baseZ + length / 2.0);
                    world.dropItemNaturally(center, remaining);
                }

                // Avancer le round-robin
                lastChestIndex = (start + 1) % size;
                start = lastChestIndex;
            }
        }

        /* ========================================================
         *   Comptage des animaux (scoreboard)
         * ========================================================
         */
        public Map<EntityType,Integer> countAnimals() {
            Map<EntityType,Integer> map = new HashMap<>();
            for (EntityType et : MAIN_SPECIES) {
                map.put(et, getEntitiesInZone(et).size());
            }
            return map;
        }

        /* ========================================================
         *   Gestion coffres cassés
         * ========================================================
         */
        public boolean isChestBlock(Block b) {
            return chestBlocks.contains(b);
        }
        public void removeChest(Block b) {
            chestBlocks.remove(b);
        }
        public boolean hasChests() {
            return !chestBlocks.isEmpty();
        }

        /* ========================================================
         *   Persistance
         * ========================================================
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("world", world.getUID().toString());
            map.put("x", baseX);
            map.put("y", baseY);
            map.put("z", baseZ);
            map.put("width", width);
            map.put("length", length);
            return map;
        }

        /* ========================================================
         *   Outil setBlock
         * ========================================================
         */
        private void setBlock(int x, int y, int z, Material mat) {
            world.getBlockAt(x, y, z).setType(mat, false);
        }
    }

    /* ============================================================
     *   Classe interne Selection (coins)
     * ============================================================
     */
    private static class Selection {
        private Block corner1;
        private Block corner2;
    }
}
