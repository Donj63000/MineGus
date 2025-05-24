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
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.example.Golem;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Commande /mineur : sélection de 2 blocs (même Y) pour définir une zone
 *  - cadre en bois
 *  - PNJ "Mineur" + 2 golems
 *  - coffres aux 4 coins
 *  - mine verticalement jusqu'en bas ( -58 )
 *
 * Persistance dans sessions.yml
 *  - On y stocke la zone, le nombre de blocs déjà minés, etc.
 *  - Si tous les coffres sont cassés, la session s'arrête (PNJ et golems sont supprimés).
 */
public class Mineur implements CommandExecutor, Listener {

    private final JavaPlugin plugin;

    // Nom du bâton spécial pour sélectionner la zone
    private static final String MINE_SELECTOR_NAME = ChatColor.GOLD + "Sélecteur de mine";

    // Fichier YAML pour sauvegarder les sessions
    private final File sessionsFile;
    private final YamlConfiguration sessionsYaml;

    /**
     * Liste de toutes les sessions de minage actives.
     */
    private final List<MiningSession> sessions = new ArrayList<>();

    /**
     * Sélections en cours : joueur -> sélection (coin1, coin2)
     */
    private final Map<UUID, Selection> selections = new HashMap<>();

    public Mineur(JavaPlugin plugin) {
        this.plugin = plugin;

        // Enregistre la commande /mineur
        if (plugin.getCommand("mineur") != null) {
            plugin.getCommand("mineur").setExecutor(this);
        }

        // Écoute l'événement "BlockBreakEvent" (coffres cassés) + PlayerInteractEvent (clics de sélection)
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // Prépare sessions.yml
        this.sessionsFile = new File(plugin.getDataFolder(), "sessions.yml");
        this.sessionsYaml = YamlConfiguration.loadConfiguration(sessionsFile);
    }

    /* =========================================================== */
    /*                       GESTION COMMANDE                      */
    /* =========================================================== */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Cette commande doit être exécutée par un joueur !");
            return true;
        }
        if (!command.getName().equalsIgnoreCase("mineur")) {
            return false;
        }

        // Donne un bâton spécial au joueur
        giveMineSelector(player);

        // Initialise ou réinitialise la sélection du joueur
        selections.put(player.getUniqueId(), new Selection());
        player.sendMessage(ChatColor.GREEN + "Tu as reçu le bâton de sélection de mine.");
        player.sendMessage(ChatColor.YELLOW + "Clique 2 blocs à la même hauteur pour définir la zone à miner.");

        return true;
    }

    /**
     * Donne le bâton nommé "Sélecteur de mine".
     */
    private void giveMineSelector(Player player) {
        ItemStack stick = new ItemStack(Material.STICK, 1);
        ItemMeta meta = stick.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MINE_SELECTOR_NAME);
            stick.setItemMeta(meta);
        }
        player.getInventory().addItem(stick);
    }

    /* =========================================================== */
    /*          ÉCOUTE DES CLICS (PlayerInteractEvent)            */
    /* =========================================================== */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Vérifie si c’est un clic de bloc (LEFT_CLICK_BLOCK ou RIGHT_CLICK_BLOCK)
        if (event.getAction() != org.bukkit.event.block.Action.LEFT_CLICK_BLOCK
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return; // on ignore
        }

        Player player = event.getPlayer();
        ItemStack itemInHand = event.getItem();
        Block clickedBlock = event.getClickedBlock();

        if (clickedBlock == null) {
            return;
        }
        // Vérifie s'il tient le bâton "Sélecteur de mine"
        if (itemInHand == null || itemInHand.getType() != Material.STICK) {
            return;
        }
        if (!itemInHand.hasItemMeta()) {
            return;
        }
        if (!MINE_SELECTOR_NAME.equals(itemInHand.getItemMeta().getDisplayName())) {
            return;
        }

        // Empêche l'action par défaut (poser, frapper...)
        event.setCancelled(true);

        // Récupère la sélection en cours pour ce joueur
        Selection sel = selections.get(player.getUniqueId());
        if (sel == null) {
            player.sendMessage(ChatColor.RED + "Fais /mineur pour obtenir le bâton de sélection !");
            return;
        }

        // Premier ou deuxième coin ?
        if (sel.getCorner1() == null) {
            sel.setCorner1(clickedBlock);
            player.sendMessage(ChatColor.AQUA + "Coin 1 sélectionné : " + coords(clickedBlock));
        } else if (sel.getCorner2() == null) {
            sel.setCorner2(clickedBlock);
            player.sendMessage(ChatColor.AQUA + "Coin 2 sélectionné : " + coords(clickedBlock));

            // On tente de valider la sélection
            validateSelection(player, sel);
        } else {
            // Les deux coins existent déjà, on redéfinit le corner1
            sel.setCorner1(clickedBlock);
            sel.setCorner2(null);
            player.sendMessage(ChatColor.AQUA + "Coin 1 redéfini : " + coords(clickedBlock));
        }
    }

    /**
     * Vérifie si corner1 et corner2 sont à la même hauteur,
     * puis crée la MiningSession correspondante.
     */
    private void validateSelection(Player player, Selection sel) {
        Block c1 = sel.getCorner1();
        Block c2 = sel.getCorner2();
        if (c1 == null || c2 == null) {
            return;
        }

        if (c1.getY() != c2.getY()) {
            player.sendMessage(ChatColor.RED + "Les 2 blocs doivent être à la même hauteur (Y) !");
            // On réinitialise corner2
            sel.setCorner2(null);
            return;
        }

        // Détermine la zone
        World w = c1.getWorld();
        int y = c1.getY();
        int x1 = c1.getX();
        int x2 = c2.getX();
        int z1 = c1.getZ();
        int z2 = c2.getZ();

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        int width  = (maxX - minX) + 1;
        int height = (maxZ - minZ) + 1;

        Location base = new Location(w, minX, y, minZ);

        // Crée la session
        MiningSession session = new MiningSession(plugin, base, width, height);
        sessions.add(session);

        player.sendMessage(ChatColor.GREEN + "Mineur lancé pour une zone de " + width + "x" + height + " (Y=" + y + ").");
        saveAllSessions();

        // On nettoie la sélection
        selections.remove(player.getUniqueId());
    }

    private String coords(Block b) {
        return "(" + b.getX() + ", " + b.getY() + ", " + b.getZ() + ")";
    }

    /* =========================================================== */
    /*       ÉVÉNEMENT : quand on casse un bloc (BlockBreak)       */
    /* =========================================================== */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        // On parcourt la copie de la liste pour éviter "ConcurrentModificationException"
        for (MiningSession session : new ArrayList<>(sessions)) {
            if (session.isChestBlock(block)) {
                // On retire le coffre de la session
                session.removeChest(block);
                // S'il n'y a plus de coffres => on arrête la session
                if (!session.hasChests()) {
                    session.stopSession();
                    sessions.remove(session);
                    saveAllSessions();
                }
                break;
            }
        }
    }

    /* =========================================================== */
    /*                       PERSISTANCE                          */
    /* =========================================================== */
    public void saveAllSessions() {
        sessionsYaml.set("sessions", null);

        int i = 0;
        for (MiningSession s : sessions) {
            sessionsYaml.createSection("sessions." + i, s.toMap());
            i++;
        }

        try {
            sessionsYaml.save(sessionsFile);
        } catch (IOException e) {
            e.printStackTrace();
            plugin.getLogger().severe("Impossible de sauvegarder sessions.yml !");
        }
    }

    public void loadSavedSessions() {
        ConfigurationSection root = sessionsYaml.getConfigurationSection("sessions");
        if (root == null) {
            return;
        }

        int loaded = 0;
        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) continue;

            String worldUid = sec.getString("world", "");
            World w = Bukkit.getWorld(UUID.fromString(worldUid));
            if (w == null) {
                plugin.getLogger().warning("Monde introuvable pour une session mineur: " + worldUid);
                continue;
            }

            int bx = sec.getInt("x");
            int by = sec.getInt("y");
            int bz = sec.getInt("z");
            int width  = sec.getInt("width");
            int length = sec.getInt("length");
            int remaining = sec.getInt("remaining", -1);

            Location base = new Location(w, bx, by, bz);
            clearZone(base, width, length,
                    List.of("Mineur", "Golem de minage"));

            int finalRemaining = remaining;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                MiningSession session = new MiningSession(plugin, base, width, length);

                if (finalRemaining >= 0) {
                    int total = session.blocksToMine.size();
                    int alreadyMined = total - finalRemaining;
                    for (int i = 0; i < alreadyMined; i++) {
                        if (!session.blocksToMine.isEmpty()) {
                            session.blocksToMine.poll();
                        }
                    }
                }

                sessions.add(session);
            }, 20L);

            loaded++;
        }

        plugin.getLogger().info("Mineur : " + loaded + " session(s) rechargée(s).");
    }

    public void stopAllSessions() {
        for (MiningSession s : sessions) {
            s.stopSession();
        }
        sessions.clear();
    }

    private void clearZone(Location base, int width, int length, List<String> names) {
        World w = base.getWorld();
        int minX = base.getBlockX() - 2;
        int maxX = base.getBlockX() + width + 2;
        int minZ = base.getBlockZ() - 2;
        int maxZ = base.getBlockZ() + length + 2;

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
    /*  CLASSE INTERNE MiningSession : 1 zone, 1 PNJ, 2 golems...  */
    /* =========================================================== */
    public static class MiningSession {
        private final JavaPlugin plugin;
        private final Location base;  // coin minimal (x, z) + y
        private final int width, length;

        // file des blocs à miner (du Y actuel jusqu'à -58)
        Queue<Block> blocksToMine;

        // PNJ + golems
        private Villager miner;
        private final List<Golem> golems = new ArrayList<>();

        // coffres
        private final Set<Block> chestBlocks = new HashSet<>();

        // Tâche de minage (BukkitRunnable)
        private BukkitRunnable miningTask;

        private static final int CHESTS_PER_CORNER = 6;
        private static final Material FRAME_BLOCK  = Material.OAK_PLANKS;

        public MiningSession(JavaPlugin plugin, Location base, int width, int length) {
            this.plugin = plugin;
            this.base = base;
            this.width  = width;
            this.length = length;

            // 1) Place cadre
            placeFrame();

            // 2) Prépare la liste de blocs à miner
            buildMiningQueue();

            // 3) Place les coffres
            placeChests();

            // 4) PNJ + golems
            spawnOrRespawnMiner();
            spawnOrRespawnGolems();

            // 5) Lance la boucle de minage
            startMiningTask();
        }

        /* --------------------- Cadre en bois --------------------- */
        private void placeFrame() {
            World w = base.getWorld();
            int bx = base.getBlockX();
            int by = base.getBlockY();
            int bz = base.getBlockZ();

            int x1 = bx - 1;
            int x2 = bx + width;
            int z1 = bz - 1;
            int z2 = bz + length;

            for (int x = x1; x <= x2; x++) {
                setBlock(w, x, by, z1, FRAME_BLOCK);
                setBlock(w, x, by, z2, FRAME_BLOCK);
            }
            for (int z = z1; z <= z2; z++) {
                setBlock(w, x1, by, z, FRAME_BLOCK);
                setBlock(w, x2, by, z, FRAME_BLOCK);
            }
        }

        private void setBlock(World w, int x, int y, int z, Material mat) {
            w.getBlockAt(x, y, z).setType(mat, false);
        }

        /* --------------------- Prépare la file de blocs à miner --------------------- */
        private void buildMiningQueue() {
            blocksToMine = new LinkedList<>();

            World w = base.getWorld();
            int bx = base.getBlockX();
            int by = base.getBlockY();
            int bz = base.getBlockZ();

            // On mine depuis "by" jusqu'à -58 (ou bedrock)
            for (int y = by; y >= -58; y--) {
                for (int x = bx; x < bx + width; x++) {
                    for (int z = bz; z < bz + length; z++) {
                        Block b = w.getBlockAt(x, y, z);
                        if (b.getType() != Material.BEDROCK && !b.getType().isAir()) {
                            blocksToMine.add(b);
                        }
                    }
                }
            }
        }

        /* --------------------- Place les coffres aux 4 coins --------------------- */
        private void placeChests() {
            World w = base.getWorld();
            int bx = base.getBlockX();
            int by = base.getBlockY();
            int bz = base.getBlockZ();

            createChests(new Location(w, bx - 2, by, bz - 2), true);
            createChests(new Location(w, bx + width + 1, by, bz - 2), false);
            createChests(new Location(w, bx - 2, by, bz + length + 1), true);
            createChests(new Location(w, bx + width + 1, by, bz + length + 1), false);
        }

        private void createChests(Location start, boolean positiveX) {
            for (int i = 0; i < CHESTS_PER_CORNER; i++) {
                Location loc = start.clone().add(positiveX ? i : -i, 0, 0);
                Block b = loc.getBlock();
                b.setType(Material.CHEST, false);
                chestBlocks.add(b);
            }
        }

        /* --------------------- PNJ + golems --------------------- */
        private void spawnOrRespawnMiner() {
            if (miner != null && !miner.isDead()) return;
            miner = (Villager) base.getWorld().spawnEntity(base, EntityType.VILLAGER);
            miner.setCustomName("Mineur");
            miner.setCustomNameVisible(true);
            miner.setProfession(Villager.Profession.ARMORER);
        }

        private void spawnOrRespawnGolems() {
            golems.removeIf(g -> g.getGolem().isDead());
            while (golems.size() < 2) {
                double radius = Math.max(1.0,
                        Math.min(width, length) / 2.0 - 1.0);
                Location center = new Location(base.getWorld(),
                        base.getX() + width / 2.0,
                        base.getY(),
                        base.getZ() + length / 2.0);
                Golem g = new Golem(plugin, center, radius, true);
                g.getGolem().setCustomName("Golem de minage");
                g.getGolem().setCustomNameVisible(true);
                golems.add(g);
            }
        }

        /* --------------------- Boucle de minage (1 bloc/s) --------------------- */
        private void startMiningTask() {
            miningTask = new BukkitRunnable() {
                int chestIndex = 0;

                @Override
                public void run() {
                    // Vérif PNJ + golems
                    if (miner == null || miner.isDead()) {
                        spawnOrRespawnMiner();
                    }
                    spawnOrRespawnGolems();

                    // S'il n'y a plus de blocs => stop
                    if (blocksToMine.isEmpty()) {
                        cancel();
                        return;
                    }

                    // Prend le prochain bloc
                    Block b = blocksToMine.poll();
                    if (b == null) return;

                    Material mat = b.getType();
                    if (mat == Material.AIR || mat == Material.BEDROCK) {
                        return;
                    }

                    // Téléportation "pour le show"
                    Location above = b.getLocation().add(0.5, 1.0, 0.5);
                    miner.teleport(above);

                    // On simule la casse
                    List<ItemStack> drops = new ArrayList<>(b.getDrops(new ItemStack(Material.IRON_PICKAXE)));
                    b.setType(Material.AIR, false);

                    // On dépose dans un coffre
                    if (!drops.isEmpty() && !chestBlocks.isEmpty()) {
                        List<Block> list = new ArrayList<>(chestBlocks);
                        Block chestBlock = list.get(chestIndex % list.size());
                        chestIndex++;

                        if (chestBlock.getType() == Material.CHEST) {
                            Chest c = (Chest) chestBlock.getState();
                            Inventory inv = c.getInventory();
                            for (ItemStack drop : drops) {
                                inv.addItem(drop);
                            }
                        }
                    }
                }
            };
            miningTask.runTaskTimer(plugin, 20L, 20L); // 1 bloc/s
        }

        /* --------------------- Vérifications coffres --------------------- */
        public boolean isChestBlock(Block b) {
            return chestBlocks.contains(b);
        }

        public void removeChest(Block b) {
            chestBlocks.remove(b);
        }

        public boolean hasChests() {
            return !chestBlocks.isEmpty();
        }

        /* --------------------- Arrêt de la session --------------------- */
        public void stopSession() {
            if (miningTask != null) {
                miningTask.cancel();
            }
            // Retire PNJ
            if (miner != null && !miner.isDead()) {
                miner.remove();
            }
            // Retire golems
            for (Golem g : golems) {
                g.remove();
            }
            golems.clear();
        }

        /* --------------------- Persistance : toMap() --------------------- */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            World w = base.getWorld();
            map.put("world", w.getUID().toString());
            map.put("x", base.getBlockX());
            map.put("y", base.getBlockY());
            map.put("z", base.getBlockZ());
            map.put("width",  width);
            map.put("length", length);
            map.put("remaining", blocksToMine.size());
            return map;
        }
    }

    /* =========================================================== */
    /*   CLASSE interne Selection pour mémoriser 2 coins cliqués   */
    /* =========================================================== */
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
    }
}
