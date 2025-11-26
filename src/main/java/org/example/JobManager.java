package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Gestion des métiers et de l'XP métier pour le serveur.
 * <p>
 * Pour l'instant, on ne gère qu'un métier : "mineur".
 * - /job           : affiche les infos du métier courant
 * - /job mineur    : choisit / active le métier de mineur
 * <p>
 * XP :
 *  - Gagnée en cassant des blocs à la pioche.
 *  - Plus le minerai est rare, plus il rapporte d'XP (configurable dans config.yml).
 * <p>
 * Courbe d'XP :
 *  - Niveau 1 à 100.
 *  - XP totale nécessaire pour atteindre le niveau N :
 *        XP(N) = 50 * (N - 1) * (N + 2)
 *    Ce qui donne un coût de niveau (N -> N+1) qui augmente de 100 à chaque niveau.
 * <p>
 * Bonus :
 *  - Tous les 10 niveaux de mineur, le joueur débloque 1 "slot de mine" supplémentaire,
 *    jusqu'à 10 mines au niveau 100.
 */
public final class JobManager implements Listener, CommandExecutor {

    private static final String JOB_MINEUR = "MINEUR";
    private static final int MAX_LEVEL = 100;
    private static final int LEVEL_STEP_FOR_MINE = 10;
    private static final double BAR_TIMEOUT_SECONDS = 4.0;

    private final JavaPlugin plugin;
    private final File dataFile;
    private final Map<UUID, JobData> jobs = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, BukkitTask> barTasks = new HashMap<>();
    private final Map<Material, Integer> blockXp = new HashMap<>();

    private BukkitTask saveTask;

    public JobManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "jobs.yml");

        if (plugin.getCommand("job") != null) {
            plugin.getCommand("job").setExecutor(this);
        }

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        loadBlockXpConfig();
        loadJobsFromDisk();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Cette commande doit être exécutée par un joueur.");
            return true;
        }

        if (args.length == 0) {
            sendJobInfo(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "mineur" -> {
                chooseMinerJob(player);
                return true;
            }
            case "info" -> {
                sendJobInfo(player);
                return true;
            }
            default -> {
                player.sendMessage(ChatColor.YELLOW + "Usage: "
                        + ChatColor.GOLD + "/job mineur" + ChatColor.GRAY + " pour choisir le métier de mineur, "
                        + ChatColor.GOLD + "/job info" + ChatColor.GRAY + " pour voir ta progression.");
                return true;
            }
        }
    }

    private void chooseMinerJob(Player player) {
        JobData data = getOrCreateData(player.getUniqueId());
        if (JOB_MINEUR.equalsIgnoreCase(data.jobName)) {
            player.sendMessage(ChatColor.GOLD + "[Job] " + ChatColor.GRAY
                    + "Tu es déjà " + ChatColor.GREEN + "mineur" + ChatColor.GRAY + ".");
            return;
        }

        data.jobName = JOB_MINEUR;
        if (data.xp < 0) {
            data.xp = 0;
        }

        player.sendMessage(ChatColor.GOLD + "[Job] " + ChatColor.GRAY
                + "Tu as choisi le métier de " + ChatColor.GREEN + "mineur" + ChatColor.GRAY + ".");
        int level = getLevelForXp(data.xp);
        int mines = getMaxMinesForLevel(level);
        player.sendMessage(ChatColor.GRAY + "Niveau actuel: " + ChatColor.GREEN + level
                + ChatColor.GRAY + " | Mines débloquées: " + ChatColor.GREEN + mines + "/10");

        scheduleAsyncSave();
    }

    private void sendJobInfo(Player player) {
        JobData data = jobs.get(player.getUniqueId());
        if (data == null || data.jobName == null) {
            player.sendMessage(ChatColor.GOLD + "[Job] " + ChatColor.GRAY
                    + "Tu n'as pas encore de métier. Utilise " + ChatColor.GOLD + "/job mineur"
                    + ChatColor.GRAY + " pour devenir mineur.");
            return;
        }

        if (!JOB_MINEUR.equalsIgnoreCase(data.jobName)) {
            player.sendMessage(ChatColor.GOLD + "[Job] " + ChatColor.GRAY
                    + "Métier actuel: " + ChatColor.GREEN + data.jobName + ChatColor.GRAY + ".");
            return;
        }

        long xp = data.xp;
        int level = getLevelForXp(xp);
        long currentLevelXp = getTotalXpForLevel(level);
        long nextLevelXp = level >= MAX_LEVEL
                ? currentLevelXp
                : getTotalXpForLevel(level + 1);
        long inLevel = xp - currentLevelXp;
        long toNext = Math.max(0, nextLevelXp - currentLevelXp);
        int maxMines = getMaxMinesForLevel(level);

        player.sendMessage(ChatColor.GOLD + "[Job] " + ChatColor.YELLOW + "Mineur");
        player.sendMessage(ChatColor.GRAY + "Niveau: " + ChatColor.GREEN + level
                + ChatColor.DARK_GRAY + "/" + MAX_LEVEL);
        if (level < MAX_LEVEL) {
            player.sendMessage(ChatColor.GRAY + "XP: " + ChatColor.AQUA + inLevel
                    + ChatColor.GRAY + " / " + ChatColor.AQUA + toNext
                    + ChatColor.GRAY + " (total: " + xp + ")");
        } else {
            player.sendMessage(ChatColor.GRAY + "XP: " + ChatColor.AQUA + xp
                    + ChatColor.GRAY + " (niveau maximum atteint)");
        }
        player.sendMessage(ChatColor.GRAY + "Mines débloquées: "
                + ChatColor.GREEN + maxMines + ChatColor.DARK_GRAY + "/10");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        JobData data = jobs.get(player.getUniqueId());
        if (data == null || data.jobName == null || !JOB_MINEUR.equalsIgnoreCase(data.jobName)) {
            return;
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand == null || itemInHand.getType() == Material.AIR) {
            return;
        }
        if (!isPickaxe(itemInHand.getType())) {
            return;
        }

        Block block = event.getBlock();
        int gained = getXpForBlock(block.getType());
        if (gained <= 0) {
            return;
        }

        addExperience(player, gained);
    }

    private boolean isPickaxe(Material type) {
        String name = type.name();
        return name.endsWith("_PICKAXE");
    }

    private int getXpForBlock(Material material) {
        Integer configured = blockXp.get(material);
        if (configured != null) {
            return configured;
        }

        String name = material.name();
        if (name.endsWith("_ORE")) {
            return 5;
        }
        return 1;
    }

    private void addExperience(Player player, int amount) {
        if (amount <= 0) {
            return;
        }

        JobData data = getOrCreateData(player.getUniqueId());
        if (data.jobName == null || !JOB_MINEUR.equalsIgnoreCase(data.jobName)) {
            return;
        }

        long oldXp = data.xp;
        int oldLevel = getLevelForXp(oldXp);

        data.xp = Math.max(0, oldXp + amount);

        int newLevel = getLevelForXp(data.xp);
        if (newLevel > oldLevel) {
            int oldMines = getMaxMinesForLevel(oldLevel);
            int newMines = getMaxMinesForLevel(newLevel);

            player.sendMessage(ChatColor.GOLD + "[Job] " + ChatColor.GREEN
                    + "Tu passes niveau " + newLevel + " en métier de mineur !");
            if (newMines > oldMines) {
                player.sendMessage(ChatColor.GRAY + "Tu peux maintenant poser "
                        + ChatColor.GREEN + newMines + ChatColor.GRAY
                        + " mines au total avec /mine.");
            }
        }

        updateBossBar(player, data);
        scheduleAsyncSave();
    }

    private void updateBossBar(Player player, JobData data) {
        UUID uuid = player.getUniqueId();
        BossBar bar = bars.get(uuid);
        if (bar == null) {
            bar = Bukkit.createBossBar(
                    ChatColor.GOLD + "Mineur",
                    BarColor.GREEN,
                    BarStyle.SEGMENTED_10
            );
            bar.addPlayer(player);
            bars.put(uuid, bar);
        }

        int level = getLevelForXp(data.xp);
        long currentLevelXp = getTotalXpForLevel(level);
        long nextLevelXp = level >= MAX_LEVEL
                ? currentLevelXp
                : getTotalXpForLevel(level + 1);

        double progress;
        if (level >= MAX_LEVEL || nextLevelXp <= currentLevelXp) {
            progress = 1.0;
        } else {
            long gained = data.xp - currentLevelXp;
            long required = nextLevelXp - currentLevelXp;
            progress = Math.max(0.0, Math.min(1.0, (double) gained / (double) required));
        }

        bar.setTitle(ChatColor.GOLD + "Mineur niveau " + level
                + ChatColor.GRAY + " (" + data.xp + " XP)");
        bar.setProgress(progress);
        bar.setVisible(true);

        BukkitTask oldTask = barTasks.remove(uuid);
        if (oldTask != null) {
            oldTask.cancel();
        }

        long delayTicks = (long) (BAR_TIMEOUT_SECONDS * 20);
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                hideBossBar(uuid);
            }
        }.runTaskLater(plugin, delayTicks);
        barTasks.put(uuid, task);
    }

    private void hideBossBar(UUID playerId) {
        BossBar bar = bars.remove(playerId);
        if (bar != null) {
            bar.removeAll();
        }
        BukkitTask task = barTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        hideBossBar(event.getPlayer().getUniqueId());
    }

    /**
     * XP totale nécessaire pour atteindre le niveau donné.
     * Niveau 1 = 0 XP.
     */
    public long getTotalXpForLevel(int level) {
        if (level <= 1) {
            return 0L;
        }
        long n = level;
        return 50L * (n - 1L) * (n + 2L);
    }

    /**
     * Calcule le niveau correspondant à un total d'XP donné.
     */
    public int getLevelForXp(long xp) {
        int level = 1;
        for (int i = 2; i <= MAX_LEVEL; i++) {
            long needed = getTotalXpForLevel(i);
            if (xp < needed) {
                break;
            }
            level = i;
        }
        return level;
    }

    /**
     * Nombre de mines maximum autorisées pour un niveau donné.
     * - < 10 : 0 mine
     * - 10-19 : 1 mine
     * - 20-29 : 2 mines
     * ...
     * - 100 : 10 mines
     */
    public int getMaxMinesForLevel(int level) {
        if (level < LEVEL_STEP_FOR_MINE) {
            return 0;
        }
        int mines = level / LEVEL_STEP_FOR_MINE;
        if (mines > 10) {
            mines = 10;
        }
        return mines;
    }

    /**
     * Renvoie le nombre de slots de mines pour un joueur donné
     * en fonction de son XP de mineur.
     */
    public int getMaxMinesForPlayer(UUID playerId) {
        JobData data = jobs.get(playerId);
        if (data == null || data.jobName == null || !JOB_MINEUR.equalsIgnoreCase(data.jobName)) {
            return 0;
        }
        int level = getLevelForXp(data.xp);
        return getMaxMinesForLevel(level);
    }

    private JobData getOrCreateData(UUID uuid) {
        JobData existing = jobs.get(uuid);
        if (existing != null) {
            return existing;
        }
        JobData created = new JobData();
        created.jobName = null;
        created.xp = 0L;
        jobs.put(uuid, created);
        return created;
    }

    private void loadBlockXpConfig() {
        ConfigurationSection section = plugin.getConfig()
                .getConfigurationSection("jobs.mineur.xp-per-block");
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null) {
                plugin.getLogger().warning("[Job] Matériau inconnu dans jobs.mineur.xp-per-block: " + key);
                continue;
            }
            int xp = section.getInt(key, 0);
            if (xp > 0) {
                blockXp.put(material, xp);
            }
        }
    }

    private void loadJobsFromDisk() {
        if (!dataFile.exists()) {
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection playersSection = config.getConfigurationSection("players");
        if (playersSection == null) {
            return;
        }

        for (String key : playersSection.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                ConfigurationSection section = playersSection.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                String job = section.getString("job", null);
                long xp = section.getLong("xp", 0L);

                JobData data = new JobData();
                data.jobName = job;
                data.xp = xp;
                jobs.put(uuid, data);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().log(Level.WARNING,
                        "[Job] UUID invalide dans jobs.yml: " + key, ex);
            }
        }
    }

    public void saveJobsSync() {
        Map<UUID, JobData> snapshot = new HashMap<>(jobs);

        YamlConfiguration config = new YamlConfiguration();
        ConfigurationSection playersSection = config.createSection("players");
        for (Map.Entry<UUID, JobData> entry : snapshot.entrySet()) {
            ConfigurationSection section = playersSection.createSection(entry.getKey().toString());
            JobData data = entry.getValue();
            section.set("job", data.jobName);
            section.set("xp", data.xp);
        }

        try {
            File parent = dataFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            config.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[Job] Impossible de sauvegarder jobs.yml", ex);
        }
    }

    private void scheduleAsyncSave() {
        if (saveTask != null && !saveTask.isCancelled()) {
            return;
        }
        saveTask = new BukkitRunnable() {
            @Override
            public void run() {
                saveJobsSync();
                saveTask = null;
            }
        }.runTaskAsynchronously(plugin);
    }

    private static final class JobData {
        String jobName;
        long xp;
    }
}
