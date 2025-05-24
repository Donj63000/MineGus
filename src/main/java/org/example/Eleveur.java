package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
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
 *   3) Le plugin génère :
 *       - Cadre (murs/clôture) + coffres + spawners d’animaux
 *         + PNJ « Éleveur » + 2 golems pour la sécurité.
 *   4) Tant qu’il y a <= ANIMAL_LIMIT animaux d’une espèce,
 *      on les laisse en paix. Dès qu’une espèce dépasse
 *      ANIMAL_LIMIT individus, l’éleveur « tue » l’excès,
 *      loots stockés dans les coffres (5% de viande cuite).
 *   5) Spawners indestructibles pour les joueurs (non-op).
 *   6) Si tous les coffres sont cassés => session arrêtée
 *      (PNJ et golems disparaissent).
 *   7) Persistance dans ranches.yml (rechargées au démarrage).
 *   8) Scoreboard lorsque le joueur est dans l’enclos,
 *      masqué en dehors.
 *   9) PNJ Éleveur propose un inventaire de trade spécial.
 *
 */
public final class Eleveur implements CommandExecutor, Listener {

    // Nom du bâton de sélection
    private static final String RANCH_SELECTOR_NAME = ChatColor.GOLD + "Sélecteur d'élevage";

    // Liste d’espèces majeures
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

    // Sélections en cours : (player UUID) -> (coins)
    private final Map<UUID, Selection> selections = new HashMap<>();

    // Scoreboards en cours : (player UUID) -> scoreboard
    private final Map<UUID, Scoreboard> playerScoreboards = new HashMap<>();

    // Task d’actualisation du scoreboard
    private BukkitRunnable scoreboardTask;

    public Eleveur(JavaPlugin plugin) {
        this.plugin = plugin;

        // Lie la commande /eleveur
        if (plugin.getCommand("eleveur") != null) {
            plugin.getCommand("eleveur").setExecutor(this);
        }

        // Écoute les events
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Prépare ranches.yml
        this.ranchFile = new File(plugin.getDataFolder(), "ranches.yml");
        this.ranchYaml = YamlConfiguration.loadConfiguration(ranchFile);

        // Lance la boucle d’affichage du scoreboard
        startScoreboardLoop();
    }

    /* ============================================================
     *                 Commande /eleveur
     * ============================================================
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Commande réservée aux joueurs.");
            return true;
        }

        if (!cmd.getName().equalsIgnoreCase("eleveur")) {
            return false;
        }

        // Donne le bâton spécial
        giveRanchSelector(player);

        // Initialise la sélection du joueur
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

        // Vérifie bâton "Sélecteur d'élevage"
        if (inHand == null
                || inHand.getType() != Material.STICK
                || !inHand.hasItemMeta()
                || !RANCH_SELECTOR_NAME.equals(inHand.getItemMeta().getDisplayName())) {
            return;
        }

        // Empêche l'action par défaut
        event.setCancelled(true);

        // Récupère la sélection pour ce joueur
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
            // Si les deux coins sont déjà définis, on réinitialise corner1
            sel.corner1 = clicked;
            sel.corner2 = null;
            player.sendMessage(ChatColor.AQUA + "Coin 1 redéfini : " + coords(clicked));
        }
    }

    private String coords(Block b) {
        return "(" + b.getX() + ", " + b.getY() + ", " + b.getZ() + ")";
    }

    /**
     * Vérifie que les 2 coins sont à la même hauteur, puis crée le ranch.
     */
    private void validateSelection(Player player, Selection sel) {
        Block c1 = sel.corner1;
        Block c2 = sel.corner2;
        if (c1 == null || c2 == null) return;

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

        int width  = (maxX - minX) + 1;
        int length = (maxZ - minZ) + 1;

        Location origin = new Location(w, minX, y, minZ);
        RanchSession rs = new RanchSession(plugin, origin, width, length);
        rs.start();
        sessions.add(rs);

        player.sendMessage(ChatColor.GREEN + "Enclos créé (" + width + "×" + length + ") !");
        saveAllSessions();

        // Nettoie la sélection
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

        // 1) Interdire le cassage d’un spawner de l’éleveur (si pas OP)
        if (block.getType() == Material.SPAWNER && !p.isOp()) {
            // On vérifie si c’est dans un ranch => on empêche
            for (RanchSession rs : sessions) {
                if (rs.isInside(block.getLocation())) {
                    event.setCancelled(true);
                    p.sendMessage(ChatColor.RED + "Ce spawner est protégé, vous ne pouvez pas le casser !");
                    return;
                }
            }
        }

        // 2) S’il s’agit d’un coffre d’une session
        for (RanchSession rs : new ArrayList<>(sessions)) {
            if (rs.isChestBlock(block)) {
                rs.removeChest(block);
                if (!rs.hasChests()) {
                    // Plus de coffres => on arrête l’enclos
                    rs.stop();
                    sessions.remove(rs);
                    saveAllSessions();
                }
                break;
            }
        }
    }

    /* ============================================================
     *                    Persistance (YAML)
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

    /**
     * Charge toutes les sessions depuis ranches.yml
     * (à appeler dans le onEnable() du plugin principal).
     */
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
            // Nettoie éventuellement PNJ/golems en double
            clearZone(origin, width, length, List.of("Éleveur", "Golem Éleveur"));

            RanchSession rs = new RanchSession(plugin, origin, width, length);
            // On relance la construction au prochain tick
            Bukkit.getScheduler().runTaskLater(plugin, rs::start, 20L);
            sessions.add(rs);
            loaded++;
        }
        plugin.getLogger().info("[Eleveur] " + loaded + " enclos rechargé(s).");
    }

    /**
     * Décharge et arrête toutes les sessions (à appeler dans onDisable()).
     */
    public void stopAllRanches() {
        if (scoreboardTask != null) {
            scoreboardTask.cancel();
        }
        for (RanchSession rs : sessions) {
            rs.stop();
        }
        sessions.clear();
    }

    /**
     * Supprime dans la zone tous les PNJ/golems antérieurs
     * pour éviter d’en avoir en double après un reload.
     */
    private void clearZone(Location origin, int width, int length, List<String> relevantNames) {
        World w = origin.getWorld();
        int minX = origin.getBlockX() - 2;
        int maxX = origin.getBlockX() + width + 2;
        int minZ = origin.getBlockZ() - 2;
        int maxZ = origin.getBlockZ() + length + 2;

        // Charge les chunks concernés
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                w.getChunkAt(cx, cz).load();
            }
        }

        // Retire PNJ/golems du même nom
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

    /* ============================================================
     *                    Boucle scoreboard
     * ============================================================
     * Mise à jour chaque seconde : on détecte si le joueur
     * est dans un enclos => affiche scoreboard,
     * sinon => retire scoreboard.
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
        // Mise à jour toute les secondes (20 ticks)
        scoreboardTask.runTaskTimer(plugin, 20L, 20L);
    }

    private RanchSession findSessionForPlayer(Player p) {
        Location loc = p.getLocation();
        for (RanchSession rs : sessions) {
            if (rs.isInside(loc)) {
                return rs;
            }
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
        // Récupère ou crée le scoreboard pour ce joueur
        Scoreboard sb = playerScoreboards.get(p.getUniqueId());
        if (sb == null) {
            sb = Bukkit.getScoreboardManager().getNewScoreboard();
            playerScoreboards.put(p.getUniqueId(), sb);
        }

        // Objectif "ranchInfo"
        Objective obj = sb.getObjective("ranchInfo");
        if (obj == null) {
            obj = sb.registerNewObjective("ranchInfo", Criteria.DUMMY, ChatColor.GOLD + "Enclos");
        }
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Calcule le nombre d’animaux
        Map<EntityType,Integer> counts = session.countAnimals();

        // On supprime les anciennes entrées
        for (String entry : sb.getEntries()) {
            sb.resetScores(entry);
        }

        // Petit titre
        String title = ChatColor.YELLOW + "== Enclos ==";
        obj.getScore(title).setScore(999);

        // Affiche le count par espèce
        int line = 998;
        for (EntityType type : MAIN_SPECIES) {
            int c = counts.getOrDefault(type, 0);
            String lineStr = ChatColor.GREEN + type.name() + ": " + c;
            obj.getScore(lineStr).setScore(line--);
        }

        // Applique
        p.setScoreboard(sb);
    }

    /* ============================================================
     *                   RanchSession interne
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

        // Paramètres / constantes
        private static final int CHESTS_PER_CORNER = 6;
        private static final Material WALL_BLOCK   = Material.OAK_PLANKS;
        private static final Material GROUND_BLOCK = Material.GRASS_BLOCK;
        private static final int LAMP_SPACING      = 4;

        // Limite d’animaux par espèce
        private static final int ANIMAL_LIMIT = 5;

        // Boucle ranch toutes les 2 secondes (40 ticks)
        private static final int RANCH_LOOP_PERIOD_TICKS = 40;

        // Random unique
        private static final Random RNG = new Random();

        // Index pour le round-robin de dépôt
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
            // Retire le PNJ
            if (rancher != null && !rancher.isDead()) {
                rancher.remove();
            }
            // Retire les golems
            for (Golem g : golems) {
                if (!g.isDead()) {
                    g.remove();
                }
            }
            golems.clear();
        }

        /* ------------------------------------------
         *  Détermine si loc est à l’intérieur
         *  de l’enclos (hauteur +X+Z).
         * ------------------------------------------ */
        public boolean isInside(Location loc) {
            if (loc.getWorld() == null || !loc.getWorld().equals(world)) return false;
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();
            if (x < baseX || x >= baseX + width) return false;
            if (z < baseZ || z >= baseZ + length) return false;
            // on tolère une hauteur de 10 blocs
            return (y >= baseY && y <= baseY + 10);
        }

        /* ------------------------------------------
         *  Construction de l’enclos
         * ------------------------------------------ */
        private void buildWalls() {
            // On fait un cadre de 3 blocs de haut, en WALL_BLOCK
            for (int dx = 0; dx <= width; dx++) {
                buildWallColumn(baseX + dx, baseZ);
                buildWallColumn(baseX + dx, baseZ + length);
            }
            for (int dz = 0; dz <= length; dz++) {
                buildWallColumn(baseX, baseZ + dz);
                buildWallColumn(baseX + width, baseZ + dz);
            }

            // Quatre portes (une par face)
            int gateX = baseX + width / 2;
            int gateZ = baseZ + length / 2;

            placeGate(gateX, baseY, baseZ);
            placeGate(gateX, baseY, baseZ + length);
            placeGate(baseX, baseY, gateZ);
            placeGate(baseX + width, baseY, gateZ);
        }

        private void buildWallColumn(int x, int z) {
            // 3 blocs de hauteur
            setBlock(x, baseY + 1, z, WALL_BLOCK);
            setBlock(x, baseY + 2, z, WALL_BLOCK);
            setBlock(x, baseY + 3, z, WALL_BLOCK);

            // Tous les LAMP_SPACING blocs => lanterne
            boolean lampHere = ((x - baseX) % LAMP_SPACING == 0) && ((z - baseZ) % LAMP_SPACING == 0);
            if (lampHere) {
                setBlock(x, baseY + 4, z, Material.LANTERN);
            }
        }

        private void placeGate(int x, int y, int z) {
            world.getBlockAt(x, y + 1, z).setType(Material.OAK_FENCE_GATE, false);
            setBlock(x, y + 2, z, Material.AIR);
            setBlock(x, y + 3, z, WALL_BLOCK);
        }

        private void buildGround() {
            // Remplace par de l'herbe
            for (int dx = 0; dx < width; dx++) {
                for (int dz = 0; dz < length; dz++) {
                    setBlock(baseX + dx, baseY, baseZ + dz, GROUND_BLOCK);
                }
            }
        }

        private void placeChests() {
            // On place CHESTS_PER_CORNER coffres dans chaque coin (4 coins).
            createChests(new Location(world, baseX - 2, baseY, baseZ - 2), true);
            createChests(new Location(world, baseX + width + 1, baseY, baseZ - 2), false);
            createChests(new Location(world, baseX - 2, baseY, baseZ + length + 1), true);
            createChests(new Location(world, baseX + width + 1, baseY, baseZ + length + 1), false);
        }

        private void createChests(Location start, boolean positiveX) {
            for (int i = 0; i < CHESTS_PER_CORNER; i++) {
                Location loc = start.clone().add(positiveX ? i : -i, 0, 0);
                setBlock(loc, Material.CHEST);
                chestBlocks.add(loc.getBlock());
            }
        }

        private void placeSpawners() {
            // 4 spawners au centre, pour MAIN_SPECIES
            int centerX = baseX + width / 2;
            int centerZ = baseZ + length / 2;
            int[][] offsets = {{0,0}, {1,0}, {0,1}, {1,1}};
            int i = 0;
            for (EntityType type : MAIN_SPECIES) {
                if (i >= offsets.length) break;
                int[] off = offsets[i++];
                Location spawnerLoc = new Location(world, centerX + off[0], baseY + 1, centerZ + off[1]);
                setBlock(spawnerLoc, Material.SPAWNER);

                Block spawnerBlock = spawnerLoc.getBlock();
                if (spawnerBlock.getState() instanceof CreatureSpawner cs) {
                    cs.setSpawnedType(type);
                    cs.update();
                }
            }
        }

        /* ------------------------------------------
         *      Apparition du PNJ et des golems
         * ------------------------------------------ */
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

            setupTrades(rancher);
        }

        private void setupTrades(Villager v) {
            List<MerchantRecipe> recipes = new ArrayList<>();
            // Achat de 64 viandes contre diamants (cru ou cuit)
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
            // On supprime les golems morts
            golems.removeIf(g -> g.isDead());

            // On en veut toujours 2
            while (golems.size() < 2) {
                Location c = new Location(world,
                        baseX + width / 2.0,
                        baseY,
                        baseZ + length / 2.0)
                        .add(golems.size() * 2.0 - 1.0, 0, -1.5);

                double radius = Math.max(1.0, Math.min(width, length) / 2.0 - 1.0);

                // Golem est une classe custom ou un wrapper autour d’un IronGolem
                Golem g = (Golem) world.spawnEntity(c, EntityType.IRON_GOLEM);
                g.setCustomName("Golem Éleveur");

                golems.add(g);
            }
        }

        /* ------------------------------------------
         *     Boucle principale de gestion
         * ------------------------------------------ */
        private void runRanchLoop() {
            ranchTask = new BukkitRunnable() {
                @Override
                public void run() {
                    // Si le PNJ a été tué / despawné => le recréer
                    if (rancher == null || rancher.isDead()) {
                        spawnOrRespawnRancher();
                    }
                    // Idem golems
                    spawnOrRespawnGolems();

                    // Surveillance : on limite chaque espèce à ANIMAL_LIMIT
                    for (EntityType type : MAIN_SPECIES) {
                        cullExcessAnimals(type);
                    }
                }
            };
            ranchTask.runTaskTimer(plugin, 20L, RANCH_LOOP_PERIOD_TICKS);
        }

        /* ------------------------------------------
         *   cullExcessAnimals : tue l’excès
         *   et dépose le loot
         * ------------------------------------------ */
        private void cullExcessAnimals(EntityType type) {
            List<LivingEntity> inZone = getEntitiesInZone(type);
            int surplus = inZone.size() - ANIMAL_LIMIT;
            if (surplus <= 0) return;

            for (int i = 0; i < surplus; i++) {
                if (inZone.isEmpty()) break;
                LivingEntity victim = inZone.remove(inZone.size() - 1);

                // Animation : PNJ TP au-dessus
                if (rancher != null && !rancher.isDead()) {
                    rancher.teleport(victim.getLocation().add(0.5, 1, 0.5));
                }

                // Génère loot
                List<ItemStack> drops = simulateLoot(type);

                // Retire l’animal
                victim.remove();

                // Dépose le loot
                deposit(drops);
            }
        }

        private List<LivingEntity> getEntitiesInZone(EntityType type) {
            List<LivingEntity> result = new ArrayList<>();
            int minX = baseX, maxX = baseX + width - 1;
            int minZ = baseZ, maxZ = baseZ + length - 1;
            int minY = baseY, maxY = baseY + 10; // on limite la hauteur
            for (Entity e : world.getEntities()) {
                if (e.getType() == type && e instanceof LivingEntity le) {
                    Location loc = e.getLocation();
                    int x = loc.getBlockX();
                    int y = loc.getBlockY();
                    int z = loc.getBlockZ();
                    if (x >= minX && x <= maxX
                            && z >= minZ && z <= maxZ
                            && y >= minY && y <= maxY) {
                        result.add(le);
                    }
                }
            }
            return result;
        }

        private List<ItemStack> simulateLoot(EntityType type) {
            List<ItemStack> loot = new ArrayList<>();
            switch (type) {
                case COW -> {
                    // 1-3 beef + 0-2 leather
                    int beef = 1 + RNG.nextInt(3);
                    int leather = RNG.nextInt(3);
                    Material rawBeef = (RNG.nextInt(100) < 5) ? Material.COOKED_BEEF : Material.BEEF;
                    loot.add(new ItemStack(rawBeef, beef));
                    if (leather > 0) {
                        loot.add(new ItemStack(Material.LEATHER, leather));
                    }
                }
                case CHICKEN -> {
                    // 1-2 chicken + 0-2 feather
                    int chicken = 1 + RNG.nextInt(2);
                    int feather = RNG.nextInt(3);
                    Material rawChicken = (RNG.nextInt(100) < 5) ? Material.COOKED_CHICKEN : Material.CHICKEN;
                    loot.add(new ItemStack(rawChicken, chicken));
                    if (feather > 0) {
                        loot.add(new ItemStack(Material.FEATHER, feather));
                    }
                }
                case PIG -> {
                    // 1-3 pork
                    int pork = 1 + RNG.nextInt(3);
                    Material rawPork = (RNG.nextInt(100) < 5) ? Material.COOKED_PORKCHOP : Material.PORKCHOP;
                    loot.add(new ItemStack(rawPork, pork));
                }
                case SHEEP -> {
                    // 1-2 mutton + 1 wool
                    int mutton = 1 + RNG.nextInt(2);
                    Material rawMutton = (RNG.nextInt(100) < 5) ? Material.COOKED_MUTTON : Material.MUTTON;
                    loot.add(new ItemStack(rawMutton, mutton));
                    loot.add(new ItemStack(Material.WHITE_WOOL, 1));
                }
                default -> {
                    // rien
                }
            }
            return loot;
        }

        /**
         * Nouveau système de dépôt :
         *  - Round-robin persistant pour équilibrer le remplissage
         *  - Retrait des coffres détruits
         *  - Si plus de place dans aucun coffre => drop au centre
         */
        private void deposit(List<ItemStack> items) {
            if (items.isEmpty()) return;

            // Nettoyage des coffres inexistants
            chestBlocks.removeIf(b -> b.getType() != Material.CHEST);

            // S’il n’y a plus de coffres, on drop au centre
            if (chestBlocks.isEmpty()) {
                Location center = new Location(world,
                        baseX + width / 2.0,
                        baseY + 1,
                        baseZ + length / 2.0);
                for (ItemStack i : items) {
                    world.dropItemNaturally(center, i);
                }
                return;
            }

            // Round-robin : on part du lastChestIndex
            int size = chestBlocks.size();
            int start = lastChestIndex % size;

            for (ItemStack stack : new ArrayList<>(items)) {
                ItemStack remaining = stack;
                int idx = start;
                int loops = 0;

                while (remaining != null && remaining.getAmount() > 0 && loops < size) {
                    Block b = chestBlocks.get(idx);
                    if (b.getState() instanceof Chest c) {
                        Map<Integer, ItemStack> leftover = c.getInventory().addItem(remaining);
                        c.update();

                        if (leftover.isEmpty()) {
                            remaining = null; // tout stocké
                        } else {
                            // leftover contient au plus 1 itemStack
                            remaining = leftover.values().iterator().next();
                        }
                    }
                    idx = (idx + 1) % size;
                    loops++;
                }

                // Si on n’a pas pu tout stocker
                if (remaining != null && remaining.getAmount() > 0) {
                    // On drop au centre
                    Location center = new Location(world,
                            baseX + width / 2.0,
                            baseY + 1,
                            baseZ + length / 2.0);
                    world.dropItemNaturally(center, remaining);
                }

                // Pour la prochaine pile, on avance l’index de 1
                lastChestIndex = (start + 1) % size;
                start = lastChestIndex;
            }
        }

        /* ------------------------------------------
         *    Comptage des animaux (scoreboard)
         * ------------------------------------------ */
        public Map<EntityType,Integer> countAnimals() {
            Map<EntityType,Integer> map = new HashMap<>();
            for (EntityType et : MAIN_SPECIES) {
                map.put(et, getEntitiesInZone(et).size());
            }
            return map;
        }

        /* ------------------------------------------
         *    Interrogation / gestion des coffres
         * ------------------------------------------ */
        public boolean isChestBlock(Block b) {
            return chestBlocks.contains(b);
        }

        public void removeChest(Block b) {
            chestBlocks.remove(b);
        }

        public boolean hasChests() {
            return !chestBlocks.isEmpty();
        }

        /* ------------------------------------------
         *           Persistance
         * ------------------------------------------ */
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

        /* ------------------------------------------
         *  Outil setBlock
         * ------------------------------------------ */
        private void setBlock(int x, int y, int z, Material mat) {
            world.getBlockAt(x, y, z).setType(mat, false);
        }
        private void setBlock(Location loc, Material mat) {
            setBlock(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), mat);
        }
    }

    /* ============================================================
     *   Classe interne Selection (coins sélectionnés)
     * ============================================================
     */
    private static class Selection {
        private Block corner1;
        private Block corner2;
    }
}
