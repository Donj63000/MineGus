package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.Particle;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;

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
 *   4) Population gérée uniquement par les spawners configurés.
 *   5) Le PNJ ramasse les items et les dépose dans les coffres.
 *   6) Persistance dans ranches.yml
 */
public final class Eleveur implements CommandExecutor, Listener {

    private static final String USE_PERMISSION = "mineplugin.eleveur.use";
    private static final String ADMIN_PERMISSION = "mineplugin.eleveur.admin";
    private static final String RANCH_SELECTOR_NAME =
            ChatColor.GOLD + "Sélecteur d'élevage";
    private static final int MIN_SIDE_LENGTH = 8;
    private static final int MAX_SIDE_LENGTH = 64;
    private static final int MAX_AREA_BLOCKS = 4096;
    private static final int MAX_ACTIVE_RANCHES = 32;
    private static final int MAX_RANCHES_PER_PLAYER = 2;
    private static final int MAX_SPECIES = 9;
    private static final List<EntityType> DEFAULT_SPECIES = List.of(
            EntityType.CHICKEN,
            EntityType.COW,
            EntityType.PIG,
            EntityType.SHEEP,
            EntityType.RABBIT
    );

    private final JavaPlugin plugin;
    private final NamespacedKey selectorKey;
    private final List<EntityType> mainSpecies;

    // Liste des sessions actives
    private final List<RanchSession> sessions = new ArrayList<>();

    // Fichier ranches.yml
    private final File ranchFile;
    private final YamlConfiguration ranchYaml;

    // Sélections en cours : (joueur) -> (coin1, coin2)
    private final Map<UUID, Selection> selections = new HashMap<>();

    public Eleveur(JavaPlugin plugin) {
        this.plugin = plugin;
        this.selectorKey = new NamespacedKey(plugin, "ranch-selector");
        this.mainSpecies = loadSpecies();

        // Lier la commande /eleveur
        if (plugin.getCommand("eleveur") != null) {
            plugin.getCommand("eleveur").setExecutor(this);
        }

        // S'inscrire comme listener
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Préparer ranches.yml
        ranchFile = new File(plugin.getDataFolder(), "ranches.yml");
        ranchYaml = YamlConfiguration.loadConfiguration(ranchFile);

    }

    /**
     * Charge les espèces sans permettre qu'une faute de frappe dans config.yml
     * empêche tout le plugin de démarrer.
     */
    private List<EntityType> loadSpecies() {
        LinkedHashSet<EntityType> species = new LinkedHashSet<>();
        for (String configured : plugin.getConfig()
                .getStringList("eleveur.species")) {
            if (species.size() >= MAX_SPECIES) {
                plugin.getLogger().warning(
                        "[Eleveur] Seules les " + MAX_SPECIES
                                + " premières espèces valides sont utilisées."
                );
                break;
            }
            try {
                species.add(EntityType.valueOf(
                        configured.trim().toUpperCase(Locale.ROOT)
                ));
            } catch (IllegalArgumentException failure) {
                plugin.getLogger().warning(
                        "[Eleveur] Espèce inconnue ignorée: " + configured
                );
            }
        }
        if (species.isEmpty()) {
            plugin.getLogger().warning(
                    "[Eleveur] Aucune espèce valide; utilisation des valeurs "
                            + "par défaut."
            );
            species.addAll(DEFAULT_SPECIES);
        }
        return List.copyOf(species);
    }

    /* ============================================================
     *                 Commande /eleveur
     * ============================================================
     */
    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED
                    + "Cette commande doit être exécutée par un joueur.");
            return true;
        }
        if (!command.getName().equalsIgnoreCase("eleveur")) {
            return false;
        }
        if (!player.hasPermission(USE_PERMISSION)) {
            player.sendMessage(ChatColor.RED
                    + "Tu n'as pas la permission d'utiliser /eleveur.");
            return true;
        }

        if (args.length > 0) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "list", "liste" -> {
                    listSessions(player);
                    return true;
                }
                case "delete", "supprimer" -> {
                    if (args.length < 2) {
                        player.sendMessage(ChatColor.RED
                                + "/eleveur delete <numéro>");
                        return true;
                    }
                    try {
                        deleteSession(player, Integer.parseInt(args[1]));
                    } catch (NumberFormatException failure) {
                        player.sendMessage(ChatColor.RED + "Numéro invalide.");
                    }
                    return true;
                }
                default -> {
                    player.sendMessage(ChatColor.RED
                            + "Sous-commande inconnue: /eleveur [list|delete].");
                    return true;
                }
            }
        }

        long owned = sessions.stream()
                .filter(session -> player.getUniqueId().equals(session.ownerId))
                .count();
        if (!player.hasPermission(ADMIN_PERMISSION)
                && owned >= MAX_RANCHES_PER_PLAYER) {
            player.sendMessage(ChatColor.RED
                    + "Tu as déjà atteint la limite de "
                    + MAX_RANCHES_PER_PLAYER + " enclos.");
            return true;
        }
        /*
         * La limite globale protège le thread serveur et reste applicable aux
         * administrateurs. Seule la limite personnelle peut être contournée.
         */
        if (sessions.size() >= MAX_ACTIVE_RANCHES) {
            player.sendMessage(ChatColor.RED
                    + "Le nombre maximal d'enclos actifs est atteint.");
            return true;
        }

        giveRanchSelector(player);
        selections.put(player.getUniqueId(), new Selection());
        player.sendMessage(ChatColor.GREEN
                + "Tu as reçu le bâton de sélection d'élevage !");
        player.sendMessage(ChatColor.YELLOW
                + "Clique 2 blocs (même hauteur) pour définir l'enclos.");
        return true;
    }

    private void giveRanchSelector(Player player) {
        ItemStack stick = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stick.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(RANCH_SELECTOR_NAME);
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

    private boolean isRanchSelector(ItemStack item) {
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

    private List<RanchSession> visibleSessions(Player player) {
        boolean admin = player.hasPermission(ADMIN_PERMISSION);
        return sessions.stream()
                .filter(session -> admin
                        || player.getUniqueId().equals(session.ownerId))
                .toList();
    }

    private void listSessions(Player player) {
        List<RanchSession> visible = visibleSessions(player);
        if (visible.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "Aucun enclos accessible.");
            return;
        }
        for (int index = 0; index < visible.size(); index++) {
            RanchSession session = visible.get(index);
            Location origin = session.getOrigin();
            player.sendMessage(ChatColor.AQUA
                    + "#" + (index + 1) + " : "
                    + origin.getWorld().getName() + " ("
                    + origin.getBlockX() + ", "
                    + origin.getBlockY() + ", "
                    + origin.getBlockZ() + ")");
        }
    }

    private void deleteSession(Player player, int displayedIndex) {
        List<RanchSession> visible = visibleSessions(player);
        int index = displayedIndex - 1;
        if (index < 0 || index >= visible.size()) {
            player.sendMessage(ChatColor.RED
                    + "Numéro invalide. Utilise /eleveur list.");
            return;
        }

        RanchSession session = visible.get(index);
        session.stop();
        sessions.remove(session);
        saveAllSessions();
        player.sendMessage(ChatColor.YELLOW
                + "Enclos #" + displayedIndex + " désactivé.");
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

        // Seul le marqueur PDC interne identifie un véritable sélecteur.
        if (!isRanchSelector(inHand)) {
            return;
        }

        event.setCancelled(true);
        if (!player.hasPermission(USE_PERMISSION)) {
            selections.remove(player.getUniqueId());
            player.sendMessage(ChatColor.RED
                    + "Tu n'as plus la permission d'utiliser ce sélecteur.");
            return;
        }

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

    private void validateSelection(Player player, Selection selection) {
        Block corner1 = selection.corner1;
        Block corner2 = selection.corner2;
        if (corner1 == null || corner2 == null) {
            return;
        }
        if (!corner1.getWorld().getUID().equals(
                corner2.getWorld().getUID())) {
            player.sendMessage(ChatColor.RED
                    + "Les 2 blocs doivent être dans le même monde !");
            selection.corner2 = null;
            return;
        }
        if (corner1.getY() != corner2.getY()) {
            player.sendMessage(ChatColor.RED
                    + "Les 2 blocs doivent être à la même hauteur !");
            selection.corner2 = null;
            return;
        }

        World world = corner1.getWorld();
        int minX = Math.min(corner1.getX(), corner2.getX());
        int maxX = Math.max(corner1.getX(), corner2.getX());
        int minZ = Math.min(corner1.getZ(), corner2.getZ());
        int maxZ = Math.max(corner1.getZ(), corner2.getZ());
        int width = maxX - minX + 1;
        int length = maxZ - minZ + 1;

        if (!areDimensionsAllowed(width, length)) {
            player.sendMessage(ChatColor.RED
                    + "Un enclos doit mesurer entre " + MIN_SIDE_LENGTH
                    + " et " + MAX_SIDE_LENGTH
                    + " blocs par côté, sans dépasser "
                    + MAX_AREA_BLOCKS + " blocs.");
            selection.corner2 = null;
            return;
        }
        if (!isHeightAllowed(world, corner1.getY())) {
            player.sendMessage(ChatColor.RED
                    + "La hauteur choisie ne laisse pas assez d'espace.");
            selection.corner2 = null;
            return;
        }
        if (sessions.stream().anyMatch(session -> session.overlaps(
                world,
                minX,
                minZ,
                width,
                length
        ))) {
            player.sendMessage(ChatColor.RED
                    + "Cette zone chevauche un enclos existant.");
            selection.corner2 = null;
            return;
        }
        long owned = sessions.stream()
                .filter(session ->
                        player.getUniqueId().equals(session.ownerId))
                .count();
        if (!player.hasPermission(ADMIN_PERMISSION)
                && owned >= MAX_RANCHES_PER_PLAYER) {
            player.sendMessage(ChatColor.RED
                    + "Tu as déjà atteint la limite de "
                    + MAX_RANCHES_PER_PLAYER + " enclos.");
            selections.remove(player.getUniqueId());
            return;
        }
        if (sessions.size() >= MAX_ACTIVE_RANCHES) {
            player.sendMessage(ChatColor.RED
                    + "Le nombre maximal d'enclos actifs est atteint.");
            selections.remove(player.getUniqueId());
            return;
        }

        Location origin = new Location(
                world,
                minX,
                corner1.getY(),
                minZ
        );
        RanchSession session = new RanchSession(
                plugin,
                origin,
                width,
                length,
                player.getUniqueId(),
                mainSpecies
        );
        try {
            session.start();
            sessions.add(session);
            saveAllSessions();
        } catch (RuntimeException failure) {
            session.stop();
            plugin.getLogger().log(
                    Level.SEVERE,
                    "[Eleveur] Impossible de créer l'enclos de "
                            + player.getName() + ".",
                    failure
            );
            player.sendMessage(ChatColor.RED
                    + "L'enclos n'a pas pu être créé. Consulte les logs.");
            return;
        }

        player.sendMessage(ChatColor.GREEN
                + "Enclos créé (" + width + "×" + length + ") !");
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

    private static boolean isHeightAllowed(World world, int baseY) {
        return baseY >= world.getMinHeight()
                && baseY + 12 < world.getMaxHeight();
    }

    /* ============================================================
     *        Protection des blocs et des coffres de l'enclos
     * ============================================================
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();

        for (RanchSession session : new ArrayList<>(sessions)) {
            if (!session.isManagedBlock(block)) {
                continue;
            }
            if (!session.canManage(player)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED
                        + "Cet enclos appartient à un autre joueur.");
                return;
            }

            return;
        }
    }

    /**
     * Le suivi des coffres intervient au niveau MONITOR : une protection de
     * région exécutée après notre contrôle ne peut plus désynchroniser la
     * sauvegarde de l'enclos avec le bloc réellement présent.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onManagedBlockBreakCompleted(BlockBreakEvent event) {
        Block block = event.getBlock();
        for (RanchSession session : new ArrayList<>(sessions)) {
            if (!session.isChestBlock(block)) {
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
        for (RanchSession session : sessions) {
            if (!session.isManagedBlock(event.getBlock())) {
                continue;
            }
            if (!session.canManage(event.getPlayer())) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED
                        + "Cet enclos appartient à un autre joueur.");
            }
            return;
        }
    }

    /* ============================================================
     *                    Persistance YAML
     * ============================================================
     */
    public void saveAllSessions() {
        ranchYaml.set("ranches", null);
        int index = 0;
        for (RanchSession session : sessions) {
            ranchYaml.createSection(
                    "ranches." + index++,
                    session.toMap()
            );
        }
        YamlFiles.saveAtomically(
                ranchYaml,
                ranchFile,
                plugin.getLogger(),
                "ranches.yml"
        );
    }

    public void loadSavedSessions() {
        ConfigurationSection root =
                ranchYaml.getConfigurationSection("ranches");
        if (root == null) {
            return;
        }

        int loaded = 0;
        for (String key : root.getKeys(false)) {
            if (sessions.size() >= MAX_ACTIVE_RANCHES) {
                plugin.getLogger().warning(
                        "[Eleveur] Limite de " + MAX_ACTIVE_RANCHES
                                + " enclos atteinte pendant le chargement."
                );
                break;
            }

            ConfigurationSection section =
                    root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }

            try {
                UUID worldId = parseUuid(section.getString("world"));
                World world = worldId == null
                        ? null
                        : Bukkit.getWorld(worldId);
                if (world == null) {
                    plugin.getLogger().warning(
                            "[Eleveur] Monde invalide ou introuvable pour "
                                    + "ranches." + key + "."
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
                            "[Eleveur] Dimensions invalides ignorées pour "
                                    + "ranches." + key + "."
                    );
                    continue;
                }
                if (sessions.stream().anyMatch(session -> session.overlaps(
                        world,
                        baseX,
                        baseZ,
                        width,
                        length
                ))) {
                    plugin.getLogger().warning(
                            "[Eleveur] Enclos chevauchant ignoré: ranches."
                                    + key + "."
                    );
                    continue;
                }

                UUID ownerId = parseUuid(section.getString("owner"));
                Location origin = new Location(
                        world,
                        baseX,
                        baseY,
                        baseZ
                );
                RanchSession session = new RanchSession(
                        plugin,
                        origin,
                        width,
                        length,
                        ownerId,
                        mainSpecies
                );

                UUID villagerId = parseUuid(
                        section.getString("villager-id")
                );
                if (villagerId != null) {
                    Entity entity = Bukkit.getEntity(villagerId);
                    if (entity instanceof Villager villager
                            && session.contains(villager.getLocation())) {
                        session.rancher = villager;
                    }
                }

                if (session.rancher == null) {
                    clearZone(
                            origin,
                            width,
                            length,
                            List.of("Éleveur", "Golem Éleveur")
                    );
                } else {
                    clearZone(
                            origin,
                            width,
                            length,
                            List.of("Golem Éleveur")
                    );
                }

                sessions.add(session);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    try {
                        session.start();
                    } catch (RuntimeException failure) {
                        sessions.remove(session);
                        session.stop();
                        plugin.getLogger().log(
                                Level.SEVERE,
                                "[Eleveur] Échec du redémarrage d'un enclos.",
                                failure
                        );
                    }
                }, 20L);
                loaded++;
            } catch (RuntimeException failure) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "[Eleveur] Entrée ranches." + key + " ignorée.",
                        failure
                );
            }
        }
        plugin.getLogger().info(
                "[Eleveur] " + loaded + " enclos rechargé(s)."
        );
    }

    /**
     * Nettoie uniquement les entités proches au lieu de parcourir toutes les
     * entités du monde à chaque redémarrage.
     */
    private void clearZone(
            Location origin,
            int width,
            int length,
            List<String> relevantNames) {
        World world = Objects.requireNonNull(
                origin.getWorld(),
                "origin.world"
        );
        BoundingBox bounds = new BoundingBox(
                origin.getBlockX() - 3,
                Math.max(world.getMinHeight(), origin.getBlockY() - 4),
                origin.getBlockZ() - 3,
                origin.getBlockX() + width + 3,
                Math.min(world.getMaxHeight(), origin.getBlockY() + 14),
                origin.getBlockZ() + length + 3
        );

        for (Entity entity : world.getNearbyEntities(bounds)) {
            String name = ChatColor.stripColor(entity.getCustomName());
            if (name != null && relevantNames.contains(name)) {
                entity.remove();
            }
        }
    }

    /**
     * API historique conservée. Un enclos créé sans propriétaire ne peut être
     * administré que par un joueur possédant la permission d'administration.
     */
    public void createRanch(Location origin, int width, int length) {
        Objects.requireNonNull(origin, "origin");
        World world = Objects.requireNonNull(
                origin.getWorld(),
                "origin.world"
        );
        if (!areDimensionsAllowed(width, length)
                || !isHeightAllowed(world, origin.getBlockY())) {
            throw new IllegalArgumentException(
                    "Dimensions d'enclos invalides."
            );
        }
        if (sessions.size() >= MAX_ACTIVE_RANCHES) {
            throw new IllegalStateException(
                    "Limite globale d'enclos atteinte."
            );
        }
        if (sessions.stream().anyMatch(session -> session.overlaps(
                world,
                origin.getBlockX(),
                origin.getBlockZ(),
                width,
                length
        ))) {
            throw new IllegalArgumentException(
                    "La zone chevauche un enclos existant."
            );
        }

        RanchSession session = new RanchSession(
                plugin,
                origin,
                width,
                length,
                null,
                mainSpecies
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

    public void stopAllRanches() {
        for (RanchSession session : sessions) {
            session.stop();
        }
        sessions.clear();
        selections.clear();
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

    /* ============================================================
     *   Classe interne : session ranch
     * ============================================================
     */
    private static final class RanchSession {
        private final JavaPlugin plugin;
        private final World world;
        private final int baseX, baseY, baseZ;
        private final int width, length;
        private final UUID ownerId;
        private final List<EntityType> species;

        private Villager rancher;
        private final List<Golem> golems = new ArrayList<>();
        private final List<Block> chestBlocks = new ArrayList<>();

        private BukkitRunnable ranchTask;
        private BukkitRunnable groundTask;

        // Délai de boucle de la tâche principale
        private int ranchLoopPeriodTicks;

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
        // Compteur pour le rafraîchissement des trades
        private int tradesCounter = 0;

        // Fichier de log pour les enclos
        private final File logFile;

        RanchSession(
                JavaPlugin plugin,
                Location origin,
                int width,
                int length,
                UUID ownerId,
                List<EntityType> species) {
            this.plugin = plugin;
            int configuredPeriod = plugin.getConfig()
                    .getInt("eleveur.villager-restock-ticks", 40);
            /*
             * Bukkit refuse une période nulle. La borne haute évite aussi un
             * débordement du compteur de réapprovisionnement.
             */
            this.ranchLoopPeriodTicks = Math.max(
                    20,
                    Math.min(20 * 60 * 60, configuredPeriod)
            );
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
            this.species = List.copyOf(species);
            this.logFile = new File(
                    plugin.getDataFolder(),
                    "ranches.log"
            );
        }

        public void start() {
            if (ranchTask != null) {
                ranchTask.cancel();
                ranchTask = null;
            }
            if (groundTask != null) {
                groundTask.cancel();
                groundTask = null;
            }
            chestBlocks.clear();

            buildWalls();
            buildGround();
            applyPath();
            ensureCampfire();
            buildRoof();
            placeChests();
            placeSpawners();
            spawnOrRespawnRancher();
            spawnOrRespawnGolems();
            runRanchLoop();

            logEvent(
                    "Enclos démarré en " + world.getName()
                            + " (" + baseX + "," + baseY + "," + baseZ + ")"
            );
        }

        public void stop() {
            if (ranchTask != null) {
                ranchTask.cancel();
                ranchTask = null;
            }
            if (groundTask != null) {
                groundTask.cancel();
                groundTask = null;
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

            logEvent("Enclos arrêté en " + world.getName() + " (" + baseX + "," + baseY + "," + baseZ + ")");
        }

        /**
         * Détermine si une entité se trouve dans le volume intérieur.
         */
        boolean contains(Location location) {
            if (location.getWorld() == null
                    || !location.getWorld().getUID().equals(world.getUID())) {
                return false;
            }
            int x = location.getBlockX();
            int y = location.getBlockY();
            int z = location.getBlockZ();
            return x >= baseX
                    && x < baseX + width
                    && z >= baseZ
                    && z < baseZ + length
                    && y >= baseY
                    && y <= baseY + 10;
        }

        boolean isManagedBlock(Block block) {
            if (!block.getWorld().getUID().equals(world.getUID())) {
                return false;
            }
            if (isChestBlock(block)) {
                return true;
            }

            int x = block.getX();
            int y = block.getY();
            int z = block.getZ();
            return x >= baseX - 2
                    && x <= baseX + width + 1
                    && z >= baseZ - 2
                    && z <= baseZ + length + 1
                    && y >= baseY
                    && y <= baseY + 12;
        }

        boolean canManage(Player player) {
            return player.hasPermission(ADMIN_PERMISSION)
                    || ownerId != null
                    && ownerId.equals(player.getUniqueId());
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

            // Ajout d'un ItemFrame orienté vers l'intérieur
            BlockFace inward;
            if (x == baseX)               inward = BlockFace.EAST;
            else if (x == baseX + width - 1) inward = BlockFace.WEST;
            else if (z == baseZ)            inward = BlockFace.SOUTH;
            else                             inward = BlockFace.NORTH;

            Location frameLocation = gateBlock.getLocation()
                    .add(0.5, 0.5, 0.5);
            ItemFrame frame = world.getNearbyEntities(
                            frameLocation,
                            0.45,
                            0.45,
                            0.45
                    ).stream()
                    .filter(ItemFrame.class::isInstance)
                    .map(ItemFrame.class::cast)
                    .findFirst()
                    .orElseGet(() -> world.spawn(
                            frameLocation,
                            ItemFrame.class
                    ));
            /*
             * La réutilisation évite de dupliquer quatre entités à chaque
             * reload. Le cadre est décoratif et ne doit pas être décroché.
             */
            frame.setFacingDirection(inward, true);
            frame.setItem(new ItemStack(Material.ARROW), false);
            frame.setFixed(true);
            frame.setInvulnerable(true);
            frame.setPersistent(true);
        }

        private void buildGround() {
            /*
             * Le chemin central et son feu de camp sont construits juste après
             * cette méthode. Ils sont donc exclus de la file afin que la tâche
             * différée ne les remplace jamais par de l'herbe.
             */
            int pathCenterZ = length / 2;
            record GroundReplacement(Block block, Material originalType) {}
            Deque<GroundReplacement> pending = new ArrayDeque<>();

            for (int dx = 0; dx < width; dx++) {
                for (int dz = 0; dz < length; dz++) {
                    if (Math.abs(dz - pathCenterZ) <= 1) {
                        continue;
                    }

                    Block block = world.getBlockAt(
                            baseX + dx,
                            baseY,
                            baseZ + dz
                    );
                    if (block.getType() != Material.GRASS_BLOCK) {
                        pending.addLast(new GroundReplacement(
                                block,
                                block.getType()
                        ));
                    }
                }
            }

            if (pending.isEmpty()) {
                return;
            }

            /*
             * La conversion est étalée pour ne pas envoyer plusieurs milliers
             * de mises à jour de blocs et de particules sur un seul tick. Le
             * type d'origine empêche aussi d'écraser une modification effectuée
             * après la sélection, même par un administrateur.
             */
            groundTask = new BukkitRunnable() {
                private int converted;

                @Override
                public void run() {
                    int budget = 64;
                    while (budget-- > 0 && !pending.isEmpty()) {
                        GroundReplacement replacement = pending.removeFirst();
                        Block block = replacement.block();
                        if (block.getType() != replacement.originalType()) {
                            continue;
                        }

                        block.setType(Material.GRASS_BLOCK, false);
                        if ((converted++ & 7) == 0) {
                            world.spawnParticle(
                                    Particle.HAPPY_VILLAGER,
                                    block.getLocation().add(0.5, 1, 0.5),
                                    1,
                                    0.2,
                                    0.2,
                                    0.2
                            );
                        }
                    }
                    if (pending.isEmpty()) {
                        cancel();
                        groundTask = null;
                    }
                }
            };
            groundTask.runTaskTimer(plugin, 1L, 1L);
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
                    if (dx % 7 == 0) {
                        Block lamp = ground.getRelative(BlockFace.UP);
                        lamp.setType(Material.LANTERN);
                    }
                }
            }
        }

        /**
         * Assure la présence d'un feu de camp au centre.
         * Place un HAY_BLOCK en support si besoin.
         */
        private void ensureCampfire() {
            int cx = baseX + width / 2;
            int cz = baseZ + length / 2;
            Block campfire = world.getBlockAt(cx, baseY + 1, cz);
            if (campfire.getType() != Material.CAMPFIRE) {
                setBlock(cx, baseY, cz, Material.HAY_BLOCK);
                campfire.setType(Material.CAMPFIRE);
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
            /*
             * Le centre reste réservé au feu de camp. Neuf positions bornées
             * permettent de prendre en charge toutes les espèces validées.
             */
            int[][] offsets = {
                    {-2, -2}, {0, -2}, {2, -2},
                    {-2, 0},           {2, 0},
                    {-2, 2},  {0, 2},  {2, 2},
                    {0, 3}
            };

            for (int index = 0;
                    index < species.size() && index < offsets.length;
                    index++) {
                EntityType type = species.get(index);
                int[] offset = offsets[index];
                Location location = new Location(
                        world,
                        centerX + offset[0],
                        baseY + 1,
                        centerZ + offset[1]
                );
                setBlock(
                        location.getBlockX(),
                        location.getBlockY(),
                        location.getBlockZ(),
                        Material.SPAWNER
                );

                if (location.getBlock().getState()
                        instanceof CreatureSpawner spawner) {
                    try {
                        spawner.setSpawnedType(type);
                        spawner.update(true, false);
                    } catch (RuntimeException failure) {
                        location.getBlock().setType(Material.AIR, false);
                        plugin.getLogger().log(
                                Level.WARNING,
                                "[Eleveur] L'espèce " + type
                                        + " ne peut pas être utilisée dans "
                                        + "un spawner.",
                                failure
                        );
                    }
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

        /* ----------------------------------------------------------
         *  Remplace l’ancienne méthode setupTrades
         * ---------------------------------------------------------- */
        private void setupTrades(Villager v) {

            v.setRecipes(Collections.emptyList()); // nettoie d’éventuels vieux trades
            List<MerchantRecipe> result = new ArrayList<>();

            // 1) boucle sur les viandes — raw
            int rawPrice = clampEmeraldPrice(
                    plugin.getConfig().getInt(
                            "eleveur.emerald-price.raw-stack",
                            1
                    )
            );
            int cookPrice = clampEmeraldPrice(
                    plugin.getConfig().getInt(
                            "eleveur.emerald-price.cook-stack",
                            2
                    )
            );

            record Meat(Material raw, Material cook) {}
            List<Meat> meats = List.of(
                    new Meat(Material.BEEF,      Material.COOKED_BEEF),
                    new Meat(Material.PORKCHOP,  Material.COOKED_PORKCHOP),
                    new Meat(Material.CHICKEN,   Material.COOKED_CHICKEN),
                    new Meat(Material.MUTTON,    Material.COOKED_MUTTON)
            );

            for (Meat m : meats) {
                result.add(stackForEmerald(rawPrice , new ItemStack(m.raw(),  64)));
                result.add(stackForEmerald(cookPrice, new ItemStack(m.cook(), 64)));
            }

            // 2) quelques à‑côtés : cuir, plumes, laine
            result.add(stackForEmerald(1, new ItemStack(Material.LEATHER, 32)));
            result.add(stackForEmerald(1, new ItemStack(Material.FEATHER, 32)));
            result.add(stackForEmerald(1, new ItemStack(Material.WHITE_WOOL, 32)));

            v.setRecipes(result);
        }

        private int clampEmeraldPrice(int configuredPrice) {
            return Math.max(1, Math.min(64, configuredPrice));
        }

        private MerchantRecipe stackForEmerald(
                int emeraldCost,
                ItemStack stack) {
            MerchantRecipe recipe = new MerchantRecipe(stack, 999_999);
            recipe.addIngredient(new ItemStack(
                    Material.EMERALD,
                    clampEmeraldPrice(emeraldCost)
            ));
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

                c.setY(world.getHighestBlockYAt(c) + 1);

                Golem g = (Golem) world.spawnEntity(c, EntityType.IRON_GOLEM);
                g.setCustomName("Golem Éleveur");
                golems.add(g);
            }
        }

        /* ========================================================
         *   Boucle ranch : PNJ stock
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

                    // Transférer inventaire PNJ vers coffres
                    if (rancher != null) {
                        transferPNJInventory();
                        tradesCounter += ranchLoopPeriodTicks;
                        if (tradesCounter >= 20 * 60) {
                            if (rancher != null && !rancher.isDead()) {
                                setupTrades(rancher);
                            }
                            tradesCounter = 0;
                        }
                    }
                }
            };
            ranchTask.runTaskTimer(plugin, 20L, ranchLoopPeriodTicks);
        }


        /**
         * À chaque boucle, on vide l’inventaire du PNJ boucher dans les coffres.
         */
        private void transferPNJInventory() {
            if (rancher == null) {
                return;
            }
            Inventory inventory = rancher.getInventory();

            /*
             * Inventory#remove(ItemStack) peut retirer plusieurs piles
             * identiques. Le traitement par emplacement garantit qu'une pile
             * déposée correspond exactement à une pile retirée.
             */
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack == null || stack.getType().isAir()) {
                    continue;
                }

                ItemStack leftover = depositOneStack(stack.clone());
                inventory.setItem(slot, null);
                if (leftover != null && leftover.getAmount() > 0) {
                    world.dropItemNaturally(
                            rancher.getLocation(),
                            leftover
                    );
                }
            }
        }

        /** Dépose une stack dans les coffres. Retourne le reste ou null si tout stocké. */
        private ItemStack depositOneStack(ItemStack in) {
            chestBlocks.removeIf(b -> b.getType() != Material.CHEST);
            if (chestBlocks.isEmpty()) return in;            // rien

            int start = lastChestIndex % chestBlocks.size();
            int idx = start;
            do {
                Chest c = (Chest) chestBlocks.get(idx).getState();
                Map<Integer, ItemStack> left = c.getInventory().addItem(in);
                c.update();
                if (left.isEmpty()) {              // stocké
                    lastChestIndex = (idx + 1) % chestBlocks.size();
                    return null;
                }
                in = left.values().iterator().next(); // reste
                idx = (idx + 1) % chestBlocks.size();
            } while (idx != start);
            return in; // aucun coffre n'avait de place
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
            map.put(
                    "villager-id",
                    rancher != null
                            ? rancher.getUniqueId().toString()
                            : ""
            );
            return map;
        }

        public Location getOrigin() {
            return new Location(world, baseX, baseY, baseZ);
        }

        /* ========================================================
         *   Outil setBlock
         * ========================================================
         */
        private void setBlock(int x, int y, int z, Material mat) {
            world.getBlockAt(x, y, z).setType(mat, false);
        }

        /**
         * Envoie un message dans un fichier de log dédié.
         */
        private void logEvent(String message) {
            try {
                if (!logFile.exists()) {
                    logFile.createNewFile();
                }
                try (FileWriter fw = new FileWriter(logFile, true);
                     PrintWriter pw = new PrintWriter(fw)) {
                    String ts = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    pw.println("[" + ts + "] " + message);
                }
            } catch (IOException e) {
                plugin.getLogger().warning("[Eleveur] Impossible d'\u00e9crire dans ranches.log");
            }
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
