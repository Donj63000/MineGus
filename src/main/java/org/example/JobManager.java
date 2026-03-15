package org.example;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Gestion du metier mineur: XP, niveaux, bonus de vitesse et HUD.
 */
public final class JobManager implements Listener, CommandExecutor {

    private static final String JOB_MINEUR = "MINEUR";
    private static final String PREFIX = ChatColor.GOLD + "[Job] " + ChatColor.GRAY;

    private final JavaPlugin plugin;
    private final File dataFile;
    private final NamespacedKey breakSpeedModifierKey;
    private final Map<UUID, JobData> jobs = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, BukkitTask> hudHideTasks = new HashMap<>();
    private final Map<Material, Integer> blockXp = new HashMap<>();

    private final int maxLevel;
    private final long xpBase;
    private final long xpLinear;
    private final long xpQuadratic;
    private final double speedPercentPerLevel;
    private final int baseMiners;
    private final int firstExtraMinerLevel;
    private final int extraMinerEveryLevels;
    private final int maxMiners;
    private final boolean bossbarOnGain;
    private final boolean actionbarOnGain;
    private final boolean titleOnLevelup;
    private final long hudHideDelayTicks;

    private BukkitTask saveTask;
    private final Object saveStateLock = new Object();
    private final Object diskWriteLock = new Object();
    private volatile long latestWrittenVersion = -1L;
    private long dirtyVersion = 0L;
    private boolean saveQueued = false;

    public JobManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "jobs.yml");
        this.breakSpeedModifierKey = new NamespacedKey(plugin, "mineur_break_speed");

        ConfigurationSection mineurSection = plugin.getConfig().getConfigurationSection("jobs.mineur");
        this.maxLevel = Math.max(2, getInt(mineurSection, "max-level", 100));
        this.xpBase = Math.max(1L, getLong(mineurSection, "xp-levelup.base", 200L));
        this.xpLinear = Math.max(0L, getLong(mineurSection, "xp-levelup.linear", 50L));
        this.xpQuadratic = Math.max(0L, getLong(mineurSection, "xp-levelup.quadratic", 4L));
        this.speedPercentPerLevel = Math.max(0.0D, getDouble(mineurSection, "bonus.speed-percent-per-level", 1.0D));
        this.baseMiners = Math.max(1, getInt(mineurSection, "bonus.mineurs.base", 1));
        this.firstExtraMinerLevel = Math.max(2, getInt(mineurSection, "bonus.mineurs.first-extra-level", 20));
        this.extraMinerEveryLevels = Math.max(1, getInt(mineurSection, "bonus.mineurs.every-levels", 10));
        this.maxMiners = Math.max(this.baseMiners, getInt(mineurSection, "bonus.mineurs.max", 10));
        this.bossbarOnGain = getDisplayBoolean(mineurSection, "bossbar-on-gain", "persistent-bossbar", true);
        this.actionbarOnGain = getBoolean(mineurSection, "display.actionbar-on-gain", true);
        this.titleOnLevelup = getBoolean(mineurSection, "display.title-on-levelup", true);
        this.hudHideDelayTicks = Math.max(20L, getInt(mineurSection, "display.hud-hide-delay-seconds", 4) * 20L);

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
            sender.sendMessage(ChatColor.RED + "Cette commande doit etre executee par un joueur.");
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
                        + ChatColor.GOLD + "/job mineur" + ChatColor.GRAY + " pour choisir le metier de mineur, "
                        + ChatColor.GOLD + "/job info" + ChatColor.GRAY + " pour voir ta progression.");
                return true;
            }
        }
    }

    public void initializeOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshPlayerState(player);
        }
    }

    public void shutdown() {
        saveJobsSync();
        synchronized (saveStateLock) {
            if (saveTask != null) {
                saveTask.cancel();
                saveTask = null;
            }
            saveQueued = false;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeMiningSpeedBonus(player);
            hideMiningHud(player.getUniqueId());
        }
    }

    private void chooseMinerJob(Player player) {
        JobData data = getOrCreateData(player.getUniqueId());
        boolean alreadyMiner = JOB_MINEUR.equalsIgnoreCase(data.jobName);
        data.jobName = JOB_MINEUR;
        data.xp = Math.max(0L, Math.min(data.xp, getTotalXpForLevel(maxLevel)));

        refreshPlayerState(player);
        notifyMineurSpeedChanged(player.getUniqueId());

        if (alreadyMiner) {
            player.sendMessage(PREFIX + "Tu es deja " + ChatColor.GREEN + "mineur" + ChatColor.GRAY + ".");
        } else {
            player.sendMessage(PREFIX + "Tu as choisi le metier de " + ChatColor.GREEN + "mineur" + ChatColor.GRAY + ".");
        }

        int level = getLevelForXp(data.xp);
        player.sendMessage(ChatColor.GRAY + "Niveau actuel: " + ChatColor.GREEN + level
                + ChatColor.GRAY + " | Bonus vitesse: " + ChatColor.GREEN + "+" + formatPercent(getMiningSpeedBonusPercent(player.getUniqueId())) + "%"
                + ChatColor.GRAY + " | Mineurs max: " + ChatColor.GREEN + getMaxMinesForPlayer(player.getUniqueId()));
        markDirtyAndScheduleAsyncSave();
    }

    private void sendJobInfo(Player player) {
        JobData data = jobs.get(player.getUniqueId());
        if (data == null || data.jobName == null) {
            player.sendMessage(PREFIX + "Tu n'as pas encore de metier. Utilise "
                    + ChatColor.GOLD + "/job mineur" + ChatColor.GRAY + " pour devenir mineur.");
            return;
        }

        if (!JOB_MINEUR.equalsIgnoreCase(data.jobName)) {
            player.sendMessage(PREFIX + "Metier actuel: " + ChatColor.GREEN + data.jobName + ChatColor.GRAY + ".");
            return;
        }

        long xp = data.xp;
        int level = getLevelForXp(xp);
        long currentLevelXp = getTotalXpForLevel(level);
        long nextLevelXp = level >= maxLevel ? currentLevelXp : getTotalXpForLevel(level + 1);
        long inLevel = Math.max(0L, xp - currentLevelXp);
        long toNext = Math.max(0L, nextLevelXp - xp);
        int maxMinesForPlayer = getMaxMinesForPlayer(player.getUniqueId());

        player.sendMessage(ChatColor.GOLD + "[Job] " + ChatColor.YELLOW + "Mineur");
        player.sendMessage(ChatColor.GRAY + "Niveau: " + ChatColor.GREEN + level + ChatColor.DARK_GRAY + "/" + maxLevel);
        player.sendMessage(ChatColor.GRAY + "XP totale: " + ChatColor.AQUA + xp);
        if (level < maxLevel) {
            player.sendMessage(ChatColor.GRAY + "Progression niveau: " + ChatColor.AQUA + inLevel
                    + ChatColor.GRAY + " / " + ChatColor.AQUA + (nextLevelXp - currentLevelXp)
                    + ChatColor.GRAY + " | Reste: " + ChatColor.AQUA + toNext);
        } else {
            player.sendMessage(ChatColor.GRAY + "Progression niveau: " + ChatColor.GREEN + "niveau maximum atteint");
        }
        player.sendMessage(ChatColor.GRAY + "Bonus vitesse: " + ChatColor.GREEN
                + "+" + formatPercent(getMiningSpeedBonusPercent(player.getUniqueId())) + "%");
        player.sendMessage(ChatColor.GRAY + "Mineurs max: " + ChatColor.GREEN + maxMinesForPlayer
                + ChatColor.DARK_GRAY + "/" + maxMiners);

        Integer nextUnlock = getNextMineUnlockLevel(level);
        if (nextUnlock != null) {
            player.sendMessage(ChatColor.GRAY + "Prochain mineur debloque au niveau "
                    + ChatColor.GREEN + nextUnlock + ChatColor.GRAY + ".");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        grantMiningXp(event.getPlayer(), event.getBlock().getType());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        refreshPlayerState(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        hideMiningHud(event.getPlayer().getUniqueId());
    }

    public int grantMiningXp(Player player, Material material) {
        if (player == null || material == null) {
            return 0;
        }
        if (!hasMinerJob(player.getUniqueId())) {
            return 0;
        }

        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand == null || itemInHand.getType() == Material.AIR || !isPickaxe(itemInHand.getType())) {
            return 0;
        }

        int gained = getXpForBlock(material);
        if (gained <= 0) {
            return 0;
        }

        addExperience(player, gained, material);
        return gained;
    }

    public boolean hasMinerJob(UUID playerId) {
        JobData data = jobs.get(playerId);
        return data != null && JOB_MINEUR.equalsIgnoreCase(data.jobName);
    }

    public long getXpForPlayer(UUID playerId) {
        JobData data = jobs.get(playerId);
        if (data == null || !JOB_MINEUR.equalsIgnoreCase(data.jobName)) {
            return 0L;
        }
        return data.xp;
    }

    public int getLevelForPlayer(UUID playerId) {
        if (!hasMinerJob(playerId)) {
            return 1;
        }
        return getLevelForXp(getXpForPlayer(playerId));
    }

    public double getMiningSpeedBonusPercent(UUID playerId) {
        if (!hasMinerJob(playerId)) {
            return 0.0D;
        }
        int level = getLevelForPlayer(playerId);
        return getMiningSpeedBonusPercentForLevel(level);
    }

    public double getMiningSpeedBonusPercentForLevel(int level) {
        int safeLevel = Math.max(1, Math.min(level, maxLevel));
        return Math.max(0.0D, (safeLevel - 1) * speedPercentPerLevel);
    }

    public double getMiningSpeedMultiplier(UUID playerId) {
        return 1.0D + (getMiningSpeedBonusPercent(playerId) / 100.0D);
    }

    public int getMaxMinesForPlayer(UUID playerId) {
        int level = hasMinerJob(playerId) ? getLevelForPlayer(playerId) : 1;
        return getMaxMinesForLevel(level);
    }

    public Integer getNextMineUnlockLevelForPlayer(UUID playerId) {
        int level = hasMinerJob(playerId) ? getLevelForPlayer(playerId) : 1;
        return getNextMineUnlockLevel(level);
    }

    /**
     * XP totale necessaire pour atteindre le niveau donne.
     * Niveau 1 = 0 XP.
     */
    public long getTotalXpForLevel(int level) {
        int cappedLevel = Math.max(1, Math.min(level, maxLevel));
        long total = 0L;
        for (int current = 1; current < cappedLevel; current++) {
            total += getXpRequiredForNextLevel(current);
        }
        return total;
    }

    public long getXpRequiredForNextLevel(int level) {
        int safeLevel = Math.max(1, Math.min(level, maxLevel));
        long step = safeLevel - 1L;
        return xpBase + (xpLinear * step) + (xpQuadratic * step * step);
    }

    public int getLevelForXp(long xp) {
        long safeXp = Math.max(0L, Math.min(xp, getTotalXpForLevel(maxLevel)));
        int level = 1;
        for (int next = 2; next <= maxLevel; next++) {
            if (safeXp < getTotalXpForLevel(next)) {
                break;
            }
            level = next;
        }
        return level;
    }

    public int getMaxMinesForLevel(int level) {
        int safeLevel = Math.max(1, Math.min(level, maxLevel));
        int total = baseMiners;
        if (safeLevel >= firstExtraMinerLevel) {
            int extras = 1 + ((safeLevel - firstExtraMinerLevel) / extraMinerEveryLevels);
            total += extras;
        }
        return Math.min(total, maxMiners);
    }

    public Integer getNextMineUnlockLevel(int level) {
        if (getMaxMinesForLevel(level) >= maxMiners) {
            return null;
        }
        if (level < firstExtraMinerLevel) {
            return firstExtraMinerLevel;
        }

        int currentExtras = Math.max(0, getMaxMinesForLevel(level) - baseMiners);
        return firstExtraMinerLevel + (currentExtras * extraMinerEveryLevels);
    }

    public int getXpForBlock(Material material) {
        Integer configured = blockXp.get(material);
        if (configured != null) {
            return configured;
        }

        String name = material.name();
        if (name.endsWith("_ORE")) {
            return 5;
        }
        if (material == Material.ANCIENT_DEBRIS) {
            return 15;
        }
        return 0;
    }

    private void addExperience(Player player, int amount, Material sourceBlock) {
        JobData data = getOrCreateData(player.getUniqueId());
        if (!JOB_MINEUR.equalsIgnoreCase(data.jobName)) {
            return;
        }

        long oldXp = data.xp;
        int oldLevel = getLevelForXp(oldXp);
        long maxXp = getTotalXpForLevel(maxLevel);

        data.xp = Math.max(0L, Math.min(maxXp, oldXp + amount));

        int newLevel = getLevelForXp(data.xp);
        refreshPlayerState(player);
        showMiningHud(player);

        if (actionbarOnGain) {
            player.sendActionBar(Component.text(
                    "+" + amount + " XP Mineur | "
                            + formatMaterial(sourceBlock) + " | Niv. " + newLevel
                            + " | +" + formatPercent(getMiningSpeedBonusPercent(player.getUniqueId())) + "% vitesse"));
        }

        if (newLevel > oldLevel) {
            int oldMines = getMaxMinesForLevel(oldLevel);
            int newMines = getMaxMinesForLevel(newLevel);

            player.sendMessage(ChatColor.GOLD + "[Job] " + ChatColor.GREEN
                    + "Tu passes niveau " + newLevel + " en metier de mineur !");
            player.sendMessage(ChatColor.GRAY + "Bonus vitesse actuel: " + ChatColor.GREEN
                    + "+" + formatPercent(getMiningSpeedBonusPercent(player.getUniqueId())) + "%");
            if (newMines > oldMines) {
                player.sendMessage(ChatColor.GRAY + "Tu peux maintenant poser "
                        + ChatColor.GREEN + newMines + ChatColor.GRAY + " mineur(s) au total.");
            }
            if (titleOnLevelup) {
                String subtitle = "+" + formatPercent(getMiningSpeedBonusPercentForLevel(newLevel)
                        - getMiningSpeedBonusPercentForLevel(Math.max(1, newLevel - 1))) + "% vitesse";
                if (newMines > oldMines) {
                    subtitle += " | " + newMines + " mineurs max";
                }
                player.sendTitle(ChatColor.GOLD + "Niveau " + newLevel,
                        ChatColor.YELLOW + subtitle, 10, 50, 10);
            }
            try {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0F, 1.0F);
            } catch (Throwable throwable) {
                plugin.getLogger().fine("[Job] Son de niveau indisponible dans cet environnement.");
            }
            notifyMineurSpeedChanged(player.getUniqueId());
        }

        markDirtyAndScheduleAsyncSave();
    }

    private void refreshPlayerState(Player player) {
        if (player == null) {
            return;
        }
        if (!hasMinerJob(player.getUniqueId())) {
            removeMiningSpeedBonus(player);
            hideMiningHud(player.getUniqueId());
            return;
        }
        applyMiningSpeedBonus(player);
    }

    private void applyMiningSpeedBonus(Player player) {
        final AttributeInstance attribute;
        try {
            attribute = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        } catch (Throwable throwable) {
            return;
        }
        if (attribute == null) {
            return;
        }

        attribute.removeModifier(breakSpeedModifierKey);

        double bonusFraction = getMiningSpeedBonusPercent(player.getUniqueId()) / 100.0D;
        if (bonusFraction <= 0.0D) {
            return;
        }

        attribute.addTransientModifier(new AttributeModifier(
                breakSpeedModifierKey,
                bonusFraction,
                AttributeModifier.Operation.ADD_SCALAR));
    }

    private void removeMiningSpeedBonus(Player player) {
        try {
            AttributeInstance attribute = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
            if (attribute != null) {
                attribute.removeModifier(breakSpeedModifierKey);
            }
        } catch (Throwable throwable) {
            // Attribute registry may be unavailable in constrained test environments.
        }
    }

    private void showMiningHud(Player player) {
        if (player == null) {
            return;
        }
        if (bossbarOnGain && hasMinerJob(player.getUniqueId())) {
            showOrRefreshBossBar(player);
            scheduleHudHide(player.getUniqueId());
            return;
        }
        hideMiningHud(player.getUniqueId());
    }

    private void showOrRefreshBossBar(Player player) {
        if (!hasMinerJob(player.getUniqueId())) {
            hideBossBar(player.getUniqueId());
            return;
        }

        BossBar bar = bars.computeIfAbsent(player.getUniqueId(), uuid -> {
            BossBar created = Bukkit.createBossBar("Mineur", BarColor.GREEN, BarStyle.SEGMENTED_10);
            created.addPlayer(player);
            return created;
        });
        if (!bar.getPlayers().contains(player)) {
            bar.addPlayer(player);
        }

        long xp = getXpForPlayer(player.getUniqueId());
        int level = getLevelForXp(xp);
        long currentLevelXp = getTotalXpForLevel(level);
        long nextLevelXp = level >= maxLevel ? currentLevelXp : getTotalXpForLevel(level + 1);
        double progress;
        if (level >= maxLevel || nextLevelXp <= currentLevelXp) {
            progress = 1.0D;
        } else {
            progress = (double) (xp - currentLevelXp) / (double) (nextLevelXp - currentLevelXp);
        }

        bar.setTitle(ChatColor.GOLD + "Mineur niv. " + level
                + ChatColor.GRAY + " | " + xp + " XP"
                + ChatColor.GRAY + " | +" + formatPercent(getMiningSpeedBonusPercent(player.getUniqueId())) + "%"
                + ChatColor.GRAY + " | " + getMaxMinesForPlayer(player.getUniqueId()) + " mineur(s)");
        bar.setProgress(Math.max(0.0D, Math.min(1.0D, progress)));
        bar.setVisible(true);
    }

    private void scheduleHudHide(UUID playerId) {
        cancelHudHideTask(playerId);
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                hudHideTasks.remove(playerId);
                hideBossBar(playerId);
            }
        }.runTaskLater(plugin, hudHideDelayTicks);
        hudHideTasks.put(playerId, task);
    }

    private void hideMiningHud(UUID playerId) {
        cancelHudHideTask(playerId);
        hideBossBar(playerId);
    }

    private void cancelHudHideTask(UUID playerId) {
        BukkitTask task = hudHideTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    private void hideBossBar(UUID playerId) {
        BossBar bar = bars.remove(playerId);
        if (bar != null) {
            Collection<Player> viewers = bar.getPlayers();
            for (Player viewer : viewers.toArray(Player[]::new)) {
                bar.removePlayer(viewer);
            }
            bar.removeAll();
        }
    }

    private boolean isPickaxe(Material type) {
        return type != null && type.name().endsWith("_PICKAXE");
    }

    private void loadBlockXpConfig() {
        blockXp.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("jobs.mineur.xp-per-block");
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null) {
                plugin.getLogger().warning("[Job] Materiau inconnu dans jobs.mineur.xp-per-block: " + key);
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
                long xp = Math.max(0L, section.getLong("xp", 0L));

                JobData data = new JobData();
                data.jobName = job;
                data.xp = Math.min(xp, getTotalXpForLevel(maxLevel));
                jobs.put(uuid, data);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().log(Level.WARNING, "[Job] UUID invalide dans jobs.yml: " + key, ex);
            }
        }
    }

    public void saveJobsSync() {
        long version;
        synchronized (saveStateLock) {
            version = dirtyVersion;
            saveQueued = false;
        }
        writeJobsSync(snapshotJobs(), version);
    }

    private Map<UUID, SavedJobData> snapshotJobs() {
        Map<UUID, SavedJobData> snapshot = new HashMap<>();
        for (Map.Entry<UUID, JobData> entry : jobs.entrySet()) {
            snapshot.put(entry.getKey(), SavedJobData.from(entry.getValue()));
        }
        return snapshot;
    }

    private boolean writeJobsSync(Map<UUID, SavedJobData> snapshot, long version) {
        synchronized (diskWriteLock) {
            if (version < latestWrittenVersion) {
                plugin.getLogger().fine("[Job] Sauvegarde obsolete ignoree (version " + version + ").");
                return true;
            }

            YamlConfiguration config = new YamlConfiguration();
            ConfigurationSection playersSection = config.createSection("players");
            for (Map.Entry<UUID, SavedJobData> entry : snapshot.entrySet()) {
                ConfigurationSection section = playersSection.createSection(entry.getKey().toString());
                SavedJobData data = entry.getValue();
                section.set("job", data.jobName);
                section.set("xp", data.xp);
            }

            try {
                File parent = dataFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                config.save(dataFile);
                latestWrittenVersion = version;
                return true;
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "[Job] Impossible de sauvegarder jobs.yml", ex);
                return false;
            }
        }
    }

    private void markDirtyAndScheduleAsyncSave() {
        synchronized (saveStateLock) {
            dirtyVersion++;
        }
        scheduleAsyncSave();
    }

    private void scheduleAsyncSave() {
        final Map<UUID, SavedJobData> snapshot;
        final long version;
        synchronized (saveStateLock) {
            if (saveTask != null && !saveTask.isCancelled()) {
                saveQueued = true;
                return;
            }
            snapshot = snapshotJobs();
            version = dirtyVersion;
            saveTask = new BukkitRunnable() {
                @Override
                public void run() {
                    boolean upToDate = writeJobsSync(snapshot, version);
                    finishAsyncSave(upToDate);
                }
            }.runTaskAsynchronously(plugin);
        }
    }

    private void finishAsyncSave(boolean upToDate) {
        boolean shouldReschedule;
        synchronized (saveStateLock) {
            saveTask = null;
            shouldReschedule = saveQueued || !upToDate || dirtyVersion > latestWrittenVersion;
            saveQueued = false;
        }
        if (shouldReschedule && plugin.isEnabled()) {
            try {
                Bukkit.getScheduler().runTask(plugin, this::scheduleAsyncSave);
            } catch (IllegalStateException ex) {
                plugin.getLogger().fine("[Job] Replanification de sauvegarde ignoree pendant l'arret du plugin.");
            }
        }
    }

    private JobData getOrCreateData(UUID uuid) {
        return jobs.computeIfAbsent(uuid, id -> new JobData());
    }

    private void notifyMineurSpeedChanged(UUID ownerId) {
        if (!(plugin instanceof MinePlugin minePlugin) || minePlugin.getMineur() == null) {
            return;
        }
        minePlugin.getMineur().refreshOwnerSessions(ownerId);
    }

    private static int getInt(ConfigurationSection section, String path, int fallback) {
        return section != null ? section.getInt(path, fallback) : fallback;
    }

    private static long getLong(ConfigurationSection section, String path, long fallback) {
        return section != null ? section.getLong(path, fallback) : fallback;
    }

    private static double getDouble(ConfigurationSection section, String path, double fallback) {
        return section != null ? section.getDouble(path, fallback) : fallback;
    }

    private static boolean getBoolean(ConfigurationSection section, String path, boolean fallback) {
        return section != null ? section.getBoolean(path, fallback) : fallback;
    }

    private static boolean getDisplayBoolean(ConfigurationSection mineurSection,
                                             String newKey,
                                             String legacyKey,
                                             boolean fallback) {
        if (mineurSection == null) {
            return fallback;
        }
        ConfigurationSection display = mineurSection.getConfigurationSection("display");
        if (display == null) {
            return fallback;
        }
        return display.getBoolean(newKey, display.getBoolean(legacyKey, fallback));
    }

    private static String formatMaterial(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String formatPercent(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001D) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static final class JobData {
        String jobName;
        long xp;
    }

    private static final class SavedJobData {
        private final String jobName;
        private final long xp;

        private SavedJobData(String jobName, long xp) {
            this.jobName = jobName;
            this.xp = xp;
        }

        private static SavedJobData from(JobData data) {
            return new SavedJobData(data.jobName, data.xp);
        }
    }
}
