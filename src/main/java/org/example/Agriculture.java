package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

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

    private static final String USE_PERMISSION = "mineplugin.champ.use";
    private static final String ADMIN_PERMISSION = "mineplugin.champ.admin";
    private static final int MIN_SIDE_LENGTH = 3;
    private static final int MAX_SIDE_LENGTH = 64;
    private static final int MAX_AREA_BLOCKS = 4096;
    private static final int MAX_ACTIVE_FIELDS = 64;
    private static final int MAX_FIELDS_PER_PLAYER = 4;
    private static final int MAX_CHESTS_PER_CORNER = 6;

    private final JavaPlugin plugin;
    private final NamespacedKey selectorKey;
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
        this.selectorKey = new NamespacedKey(plugin, "champ-selector");

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
        if (!player.hasPermission(USE_PERMISSION)) {
            player.sendMessage(ChatColor.RED
                    + "Tu n'as pas la permission d'utiliser /champ.");
            return true;
        }
        /*
         * La limite globale protège le thread serveur et ne doit jamais être
         * contournable, même par un administrateur.
         */
        if (sessions.size() >= MAX_ACTIVE_FIELDS) {
            player.sendMessage(ChatColor.RED
                    + "Le nombre maximal de champs actifs est atteint.");
            return true;
        }
        long owned = countOwnedFields(player.getUniqueId());
        if (!player.hasPermission(ADMIN_PERMISSION)
                && owned >= MAX_FIELDS_PER_PLAYER) {
            player.sendMessage(ChatColor.RED
                    + "Tu as déjà atteint la limite de "
                    + MAX_FIELDS_PER_PLAYER + " champs.");
            return true;
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
            meta.getPersistentDataContainer().set(
                    selectorKey,
                    PersistentDataType.BYTE,
                    (byte) 1
            );
            stick.setItemMeta(meta);
        }
        player.getInventory()
                .addItem(stick)
                .values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(
                        player.getLocation(),
                        leftover
                ));
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

        // Vérifie le marqueur interne du véritable sélecteur.
        if (!isChampSelector(itemInHand)) {
            return;
        }

        // Empêche l'action par défaut
        event.setCancelled(true);
        if (!player.hasPermission(USE_PERMISSION)) {
            selections.remove(player.getUniqueId());
            player.sendMessage(ChatColor.RED
                    + "Tu n'as plus la permission d'utiliser ce sélecteur.");
            return;
        }

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

    private boolean isChampSelector(ItemStack item) {
        if (item == null
                || item.getType() != Material.STICK
                || !item.hasItemMeta()) {
            return false;
        }
        Byte marker = item.getItemMeta()
                .getPersistentDataContainer()
                .get(selectorKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    /**
     * Valide la sélection avant toute écriture de blocs. Les limites empêchent
     * qu'une commande déclenche des milliers de chargements de chunks ou une
     * boucle synchrone démesurée sur le thread principal.
     */
    private void validateSelection(Player player, Selection sel) {
        Block c1 = sel.getCorner1();
        Block c2 = sel.getCorner2();
        if (c1 == null || c2 == null) {
            return;
        }
        if (!c1.getWorld().getUID().equals(c2.getWorld().getUID())) {
            player.sendMessage(ChatColor.RED
                    + "Les 2 blocs doivent être dans le même monde !");
            sel.setCorner2(null);
            return;
        }
        if (c1.getY() != c2.getY()) {
            player.sendMessage(ChatColor.RED
                    + "Les 2 blocs doivent être à la même hauteur !");
            sel.setCorner2(null);
            return;
        }

        World world = c1.getWorld();
        int minX = Math.min(c1.getX(), c2.getX());
        int maxX = Math.max(c1.getX(), c2.getX());
        int minZ = Math.min(c1.getZ(), c2.getZ());
        int maxZ = Math.max(c1.getZ(), c2.getZ());
        int width = maxX - minX + 1;
        int length = maxZ - minZ + 1;

        if (!areDimensionsAllowed(width, length)) {
            player.sendMessage(ChatColor.RED
                    + "Un champ doit mesurer entre " + MIN_SIDE_LENGTH
                    + " et " + MAX_SIDE_LENGTH
                    + " blocs par côté, sans dépasser "
                    + MAX_AREA_BLOCKS + " blocs.");
            sel.setCorner2(null);
            return;
        }
        if (!isHeightAllowed(world, c1.getY())) {
            player.sendMessage(ChatColor.RED
                    + "La hauteur choisie ne laisse pas assez d'espace au champ.");
            sel.setCorner2(null);
            return;
        }
        if (sessions.stream().anyMatch(session ->
                session.overlaps(world, minX, minZ, width, length))) {
            player.sendMessage(ChatColor.RED
                    + "Cette zone chevauche un champ existant.");
            sel.setCorner2(null);
            return;
        }
        if (sessions.size() >= MAX_ACTIVE_FIELDS) {
            player.sendMessage(ChatColor.RED
                    + "Le nombre maximal de champs actifs est atteint.");
            selections.remove(player.getUniqueId());
            return;
        }
        long owned = countOwnedFields(player.getUniqueId());
        if (!player.hasPermission(ADMIN_PERMISSION)
                && owned >= MAX_FIELDS_PER_PLAYER) {
            player.sendMessage(ChatColor.RED
                    + "Tu as déjà atteint la limite de "
                    + MAX_FIELDS_PER_PLAYER + " champs.");
            selections.remove(player.getUniqueId());
            return;
        }

        Location origin = new Location(world, minX, c1.getY(), minZ);
        FieldSession session = new FieldSession(
                plugin,
                origin,
                width,
                length,
                player.getUniqueId()
        );
        try {
            session.start();
            sessions.add(session);
            saveAllSessions();
        } catch (RuntimeException failure) {
            session.stop();
            plugin.getLogger().log(
                    Level.SEVERE,
                    "[Agriculture] Impossible de créer le champ de "
                            + player.getName() + ".",
                    failure
            );
            player.sendMessage(ChatColor.RED
                    + "Le champ n'a pas pu être créé. Consulte les logs serveur.");
            return;
        }

        player.sendMessage(ChatColor.GREEN
                + "Champ créé (" + width + "×" + length + ") !");
        selections.remove(player.getUniqueId());
    }

    static boolean areDimensionsAllowed(int width, int length) {
        if (width < MIN_SIDE_LENGTH || length < MIN_SIDE_LENGTH) {
            return false;
        }
        return width <= MAX_SIDE_LENGTH
                && length <= MAX_SIDE_LENGTH
                && (long) width * length <= MAX_AREA_BLOCKS;
    }

    /**
     * Calcule une rangée de coffres qui ne peut pas rejoindre celle du coin
     * opposé. Les petits champs historiques restent donc compatibles sans
     * créer deux références vers le même coffre.
     */
    static int chestCountPerCorner(int width) {
        if (width < MIN_SIDE_LENGTH) {
            return 0;
        }
        return (int) Math.min(
                MAX_CHESTS_PER_CORNER,
                ((long) width + 4L) / 2L
        );
    }

    private long countOwnedFields(UUID ownerId) {
        return sessions.stream()
                .filter(session -> ownerId.equals(session.ownerId))
                .count();
    }

    private static boolean isHeightAllowed(World world, int baseY) {
        return baseY - 1 >= world.getMinHeight()
                && baseY + 2 < world.getMaxHeight();
    }

    /* =========================================================== */
    /*             PROTECTION DES STRUCTURES ET DES COFFRES        */
    /* =========================================================== */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();

        for (FieldSession session : new ArrayList<>(sessions)) {
            if (!session.isManagedBlock(block)) {
                continue;
            }
            if (!session.canManage(player)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED
                        + "Ce champ appartient à un autre joueur.");
                return;
            }

            return;
        }
    }

    /**
     * Met à jour la session uniquement lorsque tous les autres plugins ont
     * définitivement accepté la casse du coffre.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onManagedBlockBreakCompleted(BlockBreakEvent event) {
        Block block = event.getBlock();
        for (FieldSession session : new ArrayList<>(sessions)) {
            if (!session.isChest(block)) {
                continue;
            }
            session.removeChest(block);
            if (!session.hasChests()) {
                session.stop();
                sessions.remove(session);
                saveAllSessions();
            }
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        for (FieldSession session : sessions) {
            if (!session.isManagedBlock(event.getBlock())) {
                continue;
            }
            if (!session.canManage(event.getPlayer())) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED
                        + "Ce champ appartient à un autre joueur.");
            }
            return;
        }
    }

    /* =========================================================== */
    /*                           PERSISTANCE                       */
    /* =========================================================== */
    public void saveAllSessions() {
        farmsYaml.set("farms", null);
        int index = 0;
        for (FieldSession session : sessions) {
            farmsYaml.createSection("farms." + index++, session.toMap());
        }
        YamlFiles.saveAtomically(
                farmsYaml,
                farmsFile,
                plugin.getLogger(),
                "farms.yml"
        );
    }

    public void loadSavedSessions() {
        ConfigurationSection root = farmsYaml.getConfigurationSection("farms");
        if (root == null) {
            return;
        }

        int loaded = 0;
        for (String key : root.getKeys(false)) {
            if (sessions.size() >= MAX_ACTIVE_FIELDS) {
                plugin.getLogger().warning(
                        "[Agriculture] Limite de " + MAX_ACTIVE_FIELDS
                                + " champs atteinte pendant le chargement."
                );
                break;
            }

            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }

            try {
                UUID worldId = parseUuid(section.getString("world"));
                World world = worldId == null ? null : Bukkit.getWorld(worldId);
                if (world == null) {
                    plugin.getLogger().warning(
                            "[Agriculture] Monde invalide ou introuvable pour farms."
                                    + key + "."
                    );
                    continue;
                }

                int baseX = section.getInt("x");
                int baseY = section.getInt("y");
                int baseZ = section.getInt("z");
                int width = section.getInt("width");
                int length = section.getInt("length");
                if (!areDimensionsAllowed(width, length)
                        || !isHeightAllowed(world, baseY)) {
                    plugin.getLogger().warning(
                            "[Agriculture] Dimensions invalides ignorées pour farms."
                                    + key + "."
                    );
                    continue;
                }
                if (sessions.stream().anyMatch(session ->
                        session.overlaps(world, baseX, baseZ, width, length))) {
                    plugin.getLogger().warning(
                            "[Agriculture] Champ chevauchant ignoré: farms."
                                    + key + "."
                    );
                    continue;
                }

                UUID ownerId = parseUuid(section.getString("owner"));
                Location origin = new Location(world, baseX, baseY, baseZ);
                clearZone(
                        origin,
                        width,
                        length,
                        List.of("Agriculteur", "Garde du champ")
                );

                FieldSession session = new FieldSession(
                        plugin,
                        origin,
                        width,
                        length,
                        ownerId
                );
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    try {
                        session.start();
                    } catch (RuntimeException failure) {
                        sessions.remove(session);
                        plugin.getLogger().log(
                                Level.SEVERE,
                                "[Agriculture] Échec du redémarrage d'un champ.",
                                failure
                        );
                    }
                }, 20L);
                sessions.add(session);
                loaded++;
            } catch (RuntimeException failure) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "[Agriculture] Entrée farms." + key + " ignorée.",
                        failure
                );
            }
        }
        plugin.getLogger().info(
                "[Agriculture] Restauré " + loaded + " champ(s)."
        );
    }

    /**
     * API historique conservée pour les intégrations internes. Une création
     * sans propriétaire reste administrable uniquement avec la permission
     * d'administration.
     */
    public void createField(Location origin, int width, int length) {
        Objects.requireNonNull(origin, "origin");
        World world = Objects.requireNonNull(origin.getWorld(), "origin.world");
        if (!areDimensionsAllowed(width, length)
                || !isHeightAllowed(world, origin.getBlockY())) {
            throw new IllegalArgumentException("Dimensions de champ invalides.");
        }
        if (sessions.size() >= MAX_ACTIVE_FIELDS) {
            throw new IllegalStateException("Limite globale de champs atteinte.");
        }
        if (sessions.stream().anyMatch(session -> session.overlaps(
                world,
                origin.getBlockX(),
                origin.getBlockZ(),
                width,
                length
        ))) {
            throw new IllegalArgumentException(
                    "La zone chevauche un champ existant."
            );
        }

        FieldSession session = new FieldSession(
                plugin,
                origin,
                width,
                length,
                null
        );
        try {
            session.start();
            sessions.add(session);
            saveAllSessions();
        } catch (RuntimeException failure) {
            session.stop();
            throw failure;
        }
    }

    public void stopAllSessions() {
        for (FieldSession session : sessions) {
            session.stop();
        }
        sessions.clear();
        selections.clear();
    }

    private void clearZone(
            Location origin,
            int width,
            int length,
            List<String> names) {
        World world = Objects.requireNonNull(origin.getWorld(), "origin.world");
        BoundingBox bounds = new BoundingBox(
                origin.getBlockX() - 3,
                Math.max(world.getMinHeight(), origin.getBlockY() - 4),
                origin.getBlockZ() - 3,
                origin.getBlockX() + width + 3,
                Math.min(world.getMaxHeight(), origin.getBlockY() + 12),
                origin.getBlockZ() + length + 3
        );

        /*
         * Une recherche bornée remplace l'ancien parcours de toutes les
         * entités du monde, qui devenait coûteux sur les serveurs peuplés.
         */
        for (var entity : world.getNearbyEntities(bounds)) {
            String name = ChatColor.stripColor(entity.getCustomName());
            if (name != null && names.contains(name)) {
                entity.remove();
            }
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /* =========================================================== */
    /*                CLASSE FieldSession (un champ)              */
    /* =========================================================== */
    private static final class FieldSession {
        private static final int WATER_GRID        = 6;   // motif irrigation
        private static final int TORCH_SPACING     = 5;   // torches tous les 5 blocs

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
        private final UUID ownerId;

        private Villager farmer;
        private final List<Golem> golems = new ArrayList<>();
        private BukkitRunnable farmTask;
        private final List<Block> farmland = new ArrayList<>();
        private final List<Block> chestBlocks = new ArrayList<>();

        private int farmIndex = 0;
        private int depositIndex = 0;

        FieldSession(
                JavaPlugin plugin,
                Location origin,
                int width,
                int length,
                UUID ownerId) {
            this.plugin = plugin;
            this.world = Objects.requireNonNull(
                    origin.getWorld(),
                    "origin.world"
            );
            this.baseX = origin.getBlockX();
            this.baseY = origin.getBlockY();
            this.baseZ = origin.getBlockZ();
            this.width = width;
            this.length = length;
            this.ownerId = ownerId;
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
            /*
             * Sur un champ étroit, deux rangées fixes de six coffres se
             * chevauchaient. Le nombre est réduit symétriquement tout en
             * conservant six coffres par coin dès huit blocs de largeur.
             */
            int chestCount = Agriculture.chestCountPerCorner(width);
            // Coin Nord-Ouest
            createChests(
                    new Location(world, baseX - 2, baseY, baseZ - 2),
                    true,
                    chestCount
            );
            // Coin Nord-Est
            createChests(
                    new Location(world, baseX + width + 1, baseY, baseZ - 2),
                    false,
                    chestCount
            );
            // Coin Sud-Ouest
            createChests(
                    new Location(world, baseX - 2, baseY, baseZ + length + 1),
                    true,
                    chestCount
            );
            // Coin Sud-Est
            createChests(
                    new Location(
                            world,
                            baseX + width + 1,
                            baseY,
                            baseZ + length + 1
                    ),
                    false,
                    chestCount
            );
        }

        private void createChests(
                Location start,
                boolean positiveX,
                int chestCount) {
            for (int i = 0; i < chestCount; i++) {
                Location loc = start.clone().add(
                        positiveX ? i : -i,
                        0,
                        0
                );
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
         * Stocke les objets dans l'ensemble des coffres. Tout reliquat est
         * déposé au sol : aucune récolte ne disparaît lorsque les coffres sont
         * pleins ou qu'un coffre a été retiré.
         */
        private void deposit(Collection<ItemStack> items) {
            chestBlocks.removeIf(block -> block.getType() != Material.CHEST);
            Location fallback = farmer != null && !farmer.isDead()
                    ? farmer.getLocation()
                    : new Location(
                            world,
                            baseX + width / 2.0,
                            baseY + 1.0,
                            baseZ + length / 2.0
                    );

            for (ItemStack item : items) {
                ItemStack remaining = item.clone();
                int chestCount = chestBlocks.size();
                int attempts = 0;

                while (remaining.getAmount() > 0
                        && chestCount > 0
                        && attempts < chestCount) {
                    int index = (depositIndex + attempts) % chestCount;
                    Block chestBlock = chestBlocks.get(index);
                    if (chestBlock.getState() instanceof Chest chest) {
                        Map<Integer, ItemStack> leftovers =
                                chest.getInventory().addItem(remaining);
                        if (leftovers.isEmpty()) {
                            remaining = null;
                            depositIndex = (index + 1) % chestCount;
                            break;
                        }
                        remaining = leftovers.values().iterator().next();
                    }
                    attempts++;
                }

                if (remaining != null && remaining.getAmount() > 0) {
                    world.dropItemNaturally(fallback, remaining);
                }
            }
        }

        /* ----------------- Protection et coffres ------------------- */
        boolean isChest(Block block) {
            return chestBlocks.contains(block);
        }

        void removeChest(Block block) {
            chestBlocks.remove(block);
        }

        boolean hasChests() {
            return !chestBlocks.isEmpty();
        }

        boolean canManage(Player player) {
            return player.hasPermission(ADMIN_PERMISSION)
                    || ownerId != null
                    && ownerId.equals(player.getUniqueId());
        }

        boolean isManagedBlock(Block block) {
            if (!block.getWorld().getUID().equals(world.getUID())) {
                return false;
            }
            if (isChest(block)) {
                return true;
            }

            int x = block.getX();
            int y = block.getY();
            int z = block.getZ();
            return x >= baseX - 2
                    && x <= baseX + width + 1
                    && z >= baseZ - 2
                    && z <= baseZ + length + 1
                    && y >= baseY - 1
                    && y <= baseY + 2;
        }

        boolean overlaps(
                World candidateWorld,
                int candidateX,
                int candidateZ,
                int candidateWidth,
                int candidateLength) {
            if (!candidateWorld.getUID().equals(world.getUID())) {
                return false;
            }

            /*
             * Les deux blocs de marge englobent les coffres extérieurs et
             * évitent que deux champs se détruisent mutuellement.
             */
            int thisMinX = baseX - 2;
            int thisMaxX = baseX + width + 1;
            int thisMinZ = baseZ - 2;
            int thisMaxZ = baseZ + length + 1;
            int otherMinX = candidateX - 2;
            int otherMaxX = candidateX + candidateWidth + 1;
            int otherMinZ = candidateZ - 2;
            int otherMaxZ = candidateZ + candidateLength + 1;
            return thisMinX <= otherMaxX
                    && thisMaxX >= otherMinX
                    && thisMinZ <= otherMaxZ
                    && thisMaxZ >= otherMinZ;
        }

        /* ----------------- Persistance ------------------- */
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("world", world.getUID().toString());
            map.put("x", baseX);
            map.put("y", baseY);
            map.put("z", baseZ);
            map.put("width", width);
            map.put("length", length);
            if (ownerId != null) {
                map.put("owner", ownerId.toString());
            }
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
