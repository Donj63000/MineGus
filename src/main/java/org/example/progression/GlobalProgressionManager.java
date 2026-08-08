package org.example.progression;

import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.example.mineur.AutomatedMiningContext;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Niveau global indépendant des métiers.
 *
 * <p>Le niveau commence à 1 et chaque passage de niveau ajoute les bonus
 * configurés. Avec la configuration par défaut, le niveau 100 représente donc
 * +99 %, car le niveau 1 est la valeur de référence et 99 passages de niveau
 * ont été effectués.</p>
 */
public final class GlobalProgressionManager
        implements Listener, CommandExecutor, TabCompleter {

    public static final String ADMIN_PERMISSION =
            "mineplugin.progression.admin";

    private static final String PREFIX =
            ChatColor.GOLD + "[Niveau] " + ChatColor.GRAY;
    private static final long RATE_WINDOW_NANOS =
            TimeUnit.MINUTES.toNanos(1L);
    private static final long SAVE_DEBOUNCE_TICKS = 100L;
    private static final long SAVE_RETRY_TICKS = 200L;

    private final JavaPlugin plugin;
    private final ProgressionConfig config;
    private final File dataFile;

    private final NamespacedKey healthModifierKey;
    private final NamespacedKey oxygenModifierKey;
    private final NamespacedKey miningSpeedModifierKey;

    private final ProgressionHud hud;
    private final PlacedBlockTracker placedBlockTracker;

    private final Map<UUID, PlayerProgress> players = new HashMap<>();
    private final Map<UUID, RateWindow> rateWindows = new HashMap<>();

    private final Object saveStateLock = new Object();
    private final Object diskWriteLock = new Object();
    private BukkitTask pendingSaveTask;
    private long dirtyVersion;
    private volatile long latestWrittenVersion = -1L;
    private boolean saveInProgress;
    private boolean saveQueued;
    private volatile boolean shuttingDown;
    private volatile boolean persistenceBlocked;

    public GlobalProgressionManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.config = ProgressionConfig.load(plugin);
        this.dataFile = new File(plugin.getDataFolder(), "progression.yml");

        this.healthModifierKey =
                new NamespacedKey(plugin, "progression_max_health");
        this.oxygenModifierKey =
                new NamespacedKey(plugin, "progression_oxygen");
        this.miningSpeedModifierKey =
                new NamespacedKey(plugin, "progression_mining_speed");
        this.hud = new ProgressionHud(plugin, config);
        this.placedBlockTracker = new PlacedBlockTracker(
                plugin,
                config.enabled && config.trackPlacedBlocks,
                config.placedBlocksMaxPerChunk,
                config.placedBlocksFlushTicks);

        loadFromDisk();
        plugin.getServer().getPluginManager()
                .registerEvents(this, plugin);

        PluginCommand command = plugin.getCommand("niveau");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        } else {
            plugin.getLogger().warning(
                    "[Progression] La commande /niveau manque dans plugin.yml.");
        }
    }

    /**
     * Applique les bonus aux joueurs déjà présents lors d'un rechargement.
     */
    public void initializeOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateKnownName(player);
            applyBonuses(player, false);
        }
    }

    /**
     * Sauvegarde les données et retire uniquement les effets appartenant à ce
     * module. Les modificateurs du métier Mineur restent indépendants.
     */
    public void shutdown() {
        hud.shutdown();
        placedBlockTracker.shutdown();

        for (Player player : Bukkit.getOnlinePlayers()) {
            /*
             * Conserver le pourcentage de vie avant de retirer le modificateur
             * transitoire évite qu'un /reload ou un redémarrage propre transforme
             * un joueur à 100 % de vie en joueur partiellement blessé.
             */
            rememberHealthRatio(player, false);
            removeBonuses(player);
        }

        /*
         * Empêcher toute nouvelle planification avant la sauvegarde finale.
         * Une écriture asynchrone déjà engagée peut finir : le verrou disque et
         * le numéro de version l'empêcheront d'écraser cet instantané final.
         */
        synchronized (saveStateLock) {
            shuttingDown = true;
            if (pendingSaveTask != null) {
                pendingSaveTask.cancel();
                pendingSaveTask = null;
            }
            saveQueued = false;
        }
        saveProgressSync();
        rateWindows.clear();
    }

    public int getMaxLevel() {
        return config.maxLevel;
    }

    public long getExperience(UUID playerId) {
        PlayerProgress data = players.get(playerId);
        return data == null ? 0L : config.clampXp(data.xp);
    }

    public int getLevel(UUID playerId) {
        return config.getLevelForXp(getExperience(playerId));
    }

    public long getTotalXpForLevel(int level) {
        return config.getTotalXpForLevel(level);
    }

    public long getXpRequiredForNextLevel(int level) {
        return config.getXpRequiredForNextLevel(level);
    }

    public double getHealthBonusPercent(UUID playerId) {
        return getHealthBonusPercentForLevel(getLevel(playerId));
    }

    public double getOxygenBonusPercent(UUID playerId) {
        return getOxygenBonusPercentForLevel(getLevel(playerId));
    }

    public double getDamageBonusPercent(UUID playerId) {
        return getDamageBonusPercentForLevel(getLevel(playerId));
    }

    public double getMiningSpeedBonusPercent(UUID playerId) {
        return getMiningSpeedBonusPercentForLevel(getLevel(playerId));
    }

    public double getHealthBonusPercentForLevel(int level) {
        return completedLevelUps(level)
                * config.healthPercentPerLevel;
    }

    public double getOxygenBonusPercentForLevel(int level) {
        return completedLevelUps(level)
                * config.oxygenPercentPerLevel;
    }

    public double getDamageBonusPercentForLevel(int level) {
        return completedLevelUps(level)
                * config.damagePercentPerLevel;
    }

    public double getMiningSpeedBonusPercentForLevel(int level) {
        return completedLevelUps(level)
                * config.miningSpeedPercentPerLevel;
    }

    /**
     * Indique si un bloc éligible à l'XP est déjà identifié comme artificiel.
     *
     * <p>Cette API sert aux autres modules de MineGus qui remplacent des blocs
     * par code. Ils peuvent ainsi préserver l'état anti-recyclage lors d'un
     * rollback sans rendre un ancien bloc naturel définitivement inéligible.</p>
     */
    public boolean isArtificialBlock(Block block) {
        if (block == null || config.getBlockXp(block.getType()) <= 0) {
            return false;
        }
        return placedBlockTracker.isMarked(block);
    }

    /**
     * Synchronise le marqueur anti-recyclage après une écriture de bloc faite
     * par le plugin.
     *
     * @param block bloc dans son nouvel état.
     * @param artificial {@code true} si ce nouvel état a été généré/posé par le
     *                   plugin, {@code false} si un état antérieur est restauré.
     */
    public void synchronizeArtificialBlock(
            Block block,
            boolean artificial) {
        if (block == null) {
            return;
        }

        if (artificial && config.getBlockXp(block.getType()) > 0) {
            placedBlockTracker.mark(block);
        } else {
            /*
             * Nettoie également un marqueur existant lorsque le bloc vient
             * d'être remplacé par de l'air ou par un matériau sans XP.
             */
            placedBlockTracker.consume(block);
        }
    }

    /**
     * API publique pour les autres modules du plugin.
     *
     * <p>Depuis le thread serveur, la valeur retournée est la quantité réellement
     * ajoutée après les plafonds. Depuis un autre thread, le gain est remis sur
     * le thread serveur et cette méthode retourne immédiatement {@code 0}.</p>
     *
     * @return quantité ajoutée immédiatement, ou {@code 0} si le traitement a
     *         été refusé ou remis sur le thread serveur.
     */
    public long grantExperience(
            Player player,
            long requestedAmount,
            String source) {
        if (player == null || requestedAmount <= 0L) {
            return 0L;
        }

        if (!Bukkit.isPrimaryThread()) {
            String safeSource = source;
            try {
                Bukkit.getScheduler().runTask(
                        plugin,
                        () -> grantExperience(
                                player,
                                requestedAmount,
                                safeSource));
            } catch (IllegalPluginAccessException exception) {
                plugin.getLogger().fine(
                        "[Progression] Gain d'XP ignoré pendant l'arrêt.");
            }
            return 0L;
        }
        return grantExperienceInternal(
                player,
                requestedAmount,
                source,
                false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        updateKnownName(event.getPlayer());
        applyBonuses(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        hud.hide(event.getPlayer());
        rateWindows.remove(playerId);
        /*
         * Les modificateurs transitoires ne doivent pas être considérés comme
         * persistants par le stockage joueur de Minecraft. Sauvegarder le ratio
         * permet de restituer exactement l'état de santé au prochain chargement.
         */
        rememberHealthRatio(event.getPlayer(), true);
        updateKnownName(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        // Les attributs transitoires peuvent être reconstruits par le serveur
        // durant le respawn ; les réappliquer un tick après évite toute course.
        Bukkit.getScheduler().runTask(
                plugin,
                () -> applyBonuses(event.getPlayer(), false));
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (config.getBlockXp(event.getBlockPlaced().getType()) > 0) {
            placedBlockTracker.mark(event.getBlockPlaced());
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        if (config.getBlockXp(event.getNewState().getType()) > 0) {
            // Inclut notamment les générateurs de pierre/cobblestone.
            placedBlockTracker.mark(event.getBlock());
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        placedBlockTracker.moveAll(
                event.getBlocks().stream()
                        .filter(block -> config.getBlockXp(block.getType()) > 0)
                        .toList(),
                event.getDirection());
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        /*
         * Pour cet événement, getDirection() est déjà la direction réelle du
         * déplacement des blocs (vers le piston), pas sa face d'extension.
         */
        placedBlockTracker.moveAll(
                event.getBlocks().stream()
                        .filter(block -> config.getBlockXp(block.getType()) > 0)
                        .toList(),
                event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        placedBlockTracker.onChunkUnload(event.getChunk());
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        discardDestroyedBlockMarkers(event.blockList());
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        discardDestroyedBlockMarkers(event.blockList());
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        Block block = event.getBlock();
        if (config.getBlockXp(block.getType()) > 0) {
            /*
             * Sans ce nettoyage, une bûche posée puis brûlée laisserait un
             * marqueur fantôme à cette coordonnée après le feu.
             */
            placedBlockTracker.consume(block);
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        Material previousType = block.getType();
        Material nextType = event.getTo();
        if (previousType == nextType) {
            return;
        }

        /*
         * Couvre notamment les Endermen, Silverfish et autres entités qui
         * déplacent, retirent ou transforment un bloc sans BlockBreakEvent.
         * Toute apparition d'un matériau à XP par une entité est considérée
         * artificielle ; une disparition nettoie l'ancien marqueur.
         */
        if (config.getBlockXp(previousType) > 0) {
            placedBlockTracker.consume(block);
        }
        if (config.getBlockXp(nextType) > 0) {
            placedBlockTracker.mark(block);
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        int blockXp = config.getBlockXp(block.getType());
        int cropXp = getMatureCropXp(block);

        /*
         * Le marqueur doit être consommé pour toute casse réellement acceptée,
         * même en Créatif ou avec un outil incorrect. Sinon la coordonnée
         * conserverait une entrée persistante alors que le bloc n'existe plus.
         */
        boolean artificial = blockXp > 0
                && placedBlockTracker.consume(block);

        if (AutomatedMiningContext.isActive()
                || !canEarnExperience(event.getPlayer())) {
            return;
        }

        if (config.requireBlockDrops && !event.isDropItems()) {
            return;
        }

        if (blockXp > 0) {
            if (artificial
                    || (config.requirePreferredTool
                    && !isPreferredTool(block, event.getPlayer()))) {
                blockXp = 0;
            }
        }

        long amount = (long) blockXp + cropXp;
        if (amount <= 0L) {
            return;
        }

        String source = cropXp > 0 && blockXp <= 0
                ? "Récolte"
                : cropXp > 0
                ? "Minage et récolte"
                : isWood(block.getType())
                ? "Bûcheronnage"
                : "Minage";
        grantExperience(event.getPlayer(), amount, source);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || !canEarnExperience(killer)) {
            return;
        }

        int baseXp = getEntityXp(event.getEntity());
        if (baseXp <= 0) {
            return;
        }

        String spawnReason = getSpawnReasonName(event.getEntity());
        double multiplier =
                config.getSpawnReasonMultiplier(spawnReason);
        long amount = Math.round(baseXp * multiplier);
        if (amount <= 0L) {
            return;
        }

        grantExperience(killer, amount, "Combat");
    }

    /**
     * Multiplie les dégâts provoqués par le joueur, après les calculs de base,
     * afin de couvrir poings, épées, haches, arcs, arbalètes, tridents,
     * projectiles magiques et autres armes sans doubler le bonus de mêlée.
     */
    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!config.enabled) {
            return;
        }

        Player attacker = resolveAttackingPlayer(event);
        if (attacker == null
                || attacker.getUniqueId().equals(
                        event.getEntity().getUniqueId())) {
            return;
        }

        double bonusFraction =
                getDamageBonusPercent(attacker.getUniqueId()) / 100.0D;
        if (bonusFraction <= 0.0D) {
            return;
        }

        double original = event.getDamage();
        double boosted = original * (1.0D + bonusFraction);
        if (Double.isFinite(original)
                && original > 0.0D
                && Double.isFinite(boosted)) {
            event.setDamage(Math.min(boosted, 1_000_000.0D));
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onFishing(PlayerFishEvent event) {
        int amount = switch (event.getState()) {
            case CAUGHT_FISH -> config.caughtFishXp;
            case CAUGHT_ENTITY -> config.caughtEntityXp;
            default -> 0;
        };
        if (amount > 0) {
            grantExperience(event.getPlayer(), amount, "Pêche");
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onBreeding(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player
                && config.breedingXp > 0) {
            grantExperience(
                    player,
                    config.breedingXp,
                    "Élevage");
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onTaming(EntityTameEvent event) {
        if (event.getOwner() instanceof Player player
                && config.tamingXp > 0) {
            grantExperience(
                    player,
                    config.tamingXp,
                    "Apprivoisement");
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onEnchanting(EnchantItemEvent event) {
        long amount = Math.round(
                config.enchantingBaseXp
                        + event.getExpLevelCost()
                        * config.enchantingXpPerCostLevel);
        if (amount > 0L) {
            grantExperience(
                    event.getEnchanter(),
                    amount,
                    "Enchantement");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFurnaceExtract(FurnaceExtractEvent event) {
        double calculated = event.getItemAmount()
                * config.smeltingXpPerItem
                + event.getExpToDrop()
                * config.smeltingXpPerVanillaXp;
        // Ne pas arrondir chaque extraction à 1 XP : sinon sortir les objets
        // un par un multiplierait artificiellement la récompense.
        long amount = Double.isFinite(calculated) && calculated >= 1.0D
                ? (long) Math.floor(calculated)
                : 0L;
        amount = Math.min(
                amount,
                config.smeltingMaxXpPerExtraction);
        if (amount > 0L) {
            grantExperience(
                    event.getPlayer(),
                    amount,
                    "Cuisson");
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent event) {
        if (config.tradeXp > 0) {
            grantExperience(
                    event.getPlayer(),
                    config.tradeXp,
                    "Commerce");
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (config.shearingXp > 0) {
            grantExperience(
                    event.getPlayer(),
                    config.shearingXp,
                    "Élevage");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        NamespacedKey key = event.getAdvancement().getKey();
        if (config.ignoreRecipeAdvancements
                && key.getKey().startsWith("recipes/")) {
            return;
        }

        int amount = config.getAdvancementXp(key);
        if (amount > 0) {
            grantExperience(
                    event.getPlayer(),
                    amount,
                    "Progrès");
        }
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                sendProgressInfo(sender, player.getUniqueId(), player.getName());
            } else {
                sendUsage(sender);
            }
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "info" -> handleInfoCommand(sender, args);
            case "addxp" -> handleAddXpCommand(sender, args);
            case "setlevel" -> handleSetLevelCommand(sender, args);
            case "reset" -> handleResetCommand(sender, args);
            case "aide", "help" -> {
                sendUsage(sender);
                yield true;
            }
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args) {
        if (args.length == 1) {
            List<String> choices = new ArrayList<>();
            choices.add("info");
            choices.add("aide");
            if (sender.hasPermission(ADMIN_PERMISSION)) {
                choices.add("addxp");
                choices.add("setlevel");
                choices.add("reset");
            }
            return filterPrefix(choices, args[0]);
        }

        if (args.length == 2
                && sender.hasPermission(ADMIN_PERMISSION)
                && Set.of("info", "addxp", "setlevel", "reset")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            return filterPrefix(knownPlayerNames(), args[1]);
        }

        if (args.length == 3
                && sender.hasPermission(ADMIN_PERMISSION)
                && "setlevel".equalsIgnoreCase(args[0])) {
            LinkedHashSet<String> levels = new LinkedHashSet<>();
            for (int candidate : new int[]{1, 10, 25, 50, 75, 100}) {
                if (candidate <= config.maxLevel) {
                    levels.add(Integer.toString(candidate));
                }
            }
            levels.add(Integer.toString(config.maxLevel));
            return filterPrefix(new ArrayList<>(levels), args[2]);
        }
        return List.of();
    }

    public void saveProgressSync() {
        if (persistenceBlocked) {
            return;
        }

        final Map<UUID, SavedProgress> snapshot;
        final long version;
        synchronized (saveStateLock) {
            if (pendingSaveTask != null) {
                pendingSaveTask.cancel();
                pendingSaveTask = null;
            }
            snapshot = snapshotPlayers();
            version = dirtyVersion;
            saveQueued = false;
        }

        try {
            writeSerializedSnapshot(
                    serializeSnapshot(snapshot),
                    version);
        } catch (RuntimeException exception) {
            /*
             * Une configuration YAML ou un système de fichiers atypique ne doit
             * jamais interrompre le reste de l'arrêt propre du plugin.
             */
            plugin.getLogger().log(
                    Level.WARNING,
                    "[Progression] Impossible de finaliser progression.yml",
                    exception);
        }
    }

    private long grantExperienceInternal(
            Player player,
            long requestedAmount,
            String source,
            boolean bypassRateLimit) {
        if (!canEarnExperience(player)
                && !bypassRateLimit) {
            return 0L;
        }

        PlayerProgress data = getOrCreateData(
                player.getUniqueId(),
                player.getName());
        long oldXp = config.clampXp(data.xp);
        long room = config.getMaximumXp() - oldXp;
        if (room <= 0L) {
            return 0L;
        }

        // Les plafonds anti-abus concernent le gameplay. Une commande
        // d'administration doit pouvoir effectuer une migration complète en
        // une seule opération, tout en restant bornée par l'XP maximale.
        long amount = bypassRateLimit
                ? requestedAmount
                : Math.min(requestedAmount, config.maxSingleAward);
        amount = Math.min(amount, room);
        if (!bypassRateLimit) {
            amount = applyRateLimit(
                    player.getUniqueId(),
                    amount);
        }
        if (amount <= 0L) {
            return 0L;
        }

        int oldLevel = config.getLevelForXp(oldXp);
        data.xp = oldXp + amount;
        data.lastKnownName = player.getName();

        int newLevel = config.getLevelForXp(data.xp);
        if (newLevel != oldLevel) {
            applyBonuses(player, newLevel > oldLevel);
        }

        long currentLevelXp =
                config.getTotalXpForLevel(newLevel);
        long nextLevelXp = newLevel >= config.maxLevel
                ? currentLevelXp
                : config.getTotalXpForLevel(newLevel + 1);
        hud.show(
                player,
                amount,
                source,
                newLevel,
                data.xp,
                currentLevelXp,
                nextLevelXp);

        if (newLevel > oldLevel) {
            notifyLevelUp(
                    player,
                    oldLevel,
                    newLevel);
        }

        markDirtyAndScheduleSave();
        return amount;
    }

    private boolean canEarnExperience(Player player) {
        if (!config.enabled || player == null) {
            return false;
        }
        try {
            if (!player.isOnline()) {
                return false;
            }
        } catch (Throwable throwable) {
            // Certains doubles de test ne modélisent pas cet état.
        }

        GameMode gameMode = player.getGameMode();
        if (gameMode == GameMode.CREATIVE
                || gameMode == GameMode.SPECTATOR) {
            return false;
        }

        try {
            String worldName = player.getWorld().getName()
                    .toLowerCase(Locale.ROOT);
            return !config.disabledWorlds.contains(worldName);
        } catch (Throwable throwable) {
            // En cas d'état joueur incomplet, refuser le gain est le choix sûr.
            return false;
        }
    }

    private long applyRateLimit(UUID playerId, long requested) {
        if (config.maxXpPerMinute <= 0L) {
            return requested;
        }

        long now = System.nanoTime();
        RateWindow window = rateWindows.computeIfAbsent(
                playerId,
                ignored -> new RateWindow(now));

        if (now - window.startedAtNanos >= RATE_WINDOW_NANOS
                || now < window.startedAtNanos) {
            window.startedAtNanos = now;
            window.granted = 0L;
        }

        long remaining = Math.max(
                0L,
                config.maxXpPerMinute - window.granted);
        long accepted = Math.min(requested, remaining);
        window.granted += accepted;
        return accepted;
    }

    private void discardDestroyedBlockMarkers(
            Iterable<? extends Block> destroyedBlocks) {
        if (!placedBlockTracker.isEnabled() || destroyedBlocks == null) {
            return;
        }
        for (Block block : destroyedBlocks) {
            if (block != null
                    && config.getBlockXp(block.getType()) > 0) {
                placedBlockTracker.consume(block);
            }
        }
    }

    private int getMatureCropXp(Block block) {
        int configured = config.getMatureCropXp(block.getType());
        if (configured <= 0) {
            return 0;
        }

        if (block.getBlockData() instanceof Ageable ageable
                && ageable.getAge() >= ageable.getMaximumAge()) {
            return configured;
        }
        return 0;
    }

    private boolean isPreferredTool(
            Block block,
            Player player) {
        try {
            ItemStack tool =
                    player.getInventory().getItemInMainHand();
            return tool != null
                    && tool.getType() != Material.AIR
                    && block.isPreferredTool(tool);
        } catch (Throwable throwable) {
            // Sur un environnement API incomplet, refuser l'XP est plus sûr
            // que d'autoriser une exploitation à la main.
            return false;
        }
    }

    private int getEntityXp(LivingEntity entity) {
        int configured =
                config.getConfiguredEntityXp(entity.getType());
        if (configured >= 0) {
            return configured;
        }
        if (entity instanceof Player) {
            return config.playerKillXp;
        }
        if (entity instanceof Monster) {
            return config.monsterXp;
        }
        if (entity instanceof Animals) {
            return config.animalXp;
        }
        return config.otherEntityXp;
    }

    /**
     * Lit directement la raison de spawn exposée par Paper sur l'entité, sans
     * dépendre d'un cache d'événement propre au plugin qui serait perdu dès que
     * le processus serveur est arrêté.
     */
    private String getSpawnReasonName(LivingEntity entity) {
        try {
            CreatureSpawnEvent.SpawnReason reason =
                    entity.getEntitySpawnReason();
            if (reason != null) {
                return reason.name();
            }
        } catch (Throwable throwable) {
            // Repli pour les doubles de test ou une implémentation API partielle.
        }

        try {
            if (entity.fromMobSpawner()) {
                return CreatureSpawnEvent.SpawnReason.SPAWNER.name();
            }
        } catch (Throwable throwable) {
            // Sans origine exploitable, le multiplicateur neutre (1.0) s'applique.
        }
        return null;
    }

    private Player resolveAttackingPlayer(
            EntityDamageByEntityEvent event) {
        try {
            Entity causing =
                    event.getDamageSource().getCausingEntity();
            if (causing instanceof Player player) {
                return player;
            }
        } catch (Throwable throwable) {
            // Repli compatible avec les implémentations de test partielles.
        }

        Entity damager = event.getDamager();
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }
        if (damager instanceof TNTPrimed tnt
                && tnt.getSource() instanceof Player player) {
            return player;
        }
        return null;
    }

    private void applyBonuses(
            Player player,
            boolean healNewlyUnlockedHealth) {
        if (player == null) {
            return;
        }

        int level = getLevel(player.getUniqueId());
        double oldMaximumHealth = getAttributeValue(
                player,
                Attribute.MAX_HEALTH);

        double healthFraction = config.enabled
                ? getHealthBonusPercentForLevel(level) / 100.0D
                : 0.0D;
        double oxygenDurationFraction = config.enabled
                ? getOxygenBonusPercentForLevel(level) / 100.0D
                : 0.0D;
        double oxygenAttributeValue =
                oxygenAttributeForDurationBonus(oxygenDurationFraction);
        double miningFraction = config.enabled
                ? getMiningSpeedBonusPercentForLevel(level) / 100.0D
                : 0.0D;

        replaceModifier(
                player,
                Attribute.MAX_HEALTH,
                healthModifierKey,
                healthFraction,
                AttributeModifier.Operation.ADD_SCALAR);
        /*
         * Vanilla décrémente l'air avec une probabilité 1 / (1 + a), où « a »
         * est la valeur d'OXYGEN_BONUS. La durée moyenne est donc multipliée
         * par 1 + a : ajouter 0,99 produit bien environ +99 % d'apnée.
         * ADD_NUMBER est nécessaire puisque la valeur de base est nulle.
         */
        replaceModifier(
                player,
                Attribute.OXYGEN_BONUS,
                oxygenModifierKey,
                oxygenAttributeValue,
                AttributeModifier.Operation.ADD_NUMBER);
        replaceModifier(
                player,
                Attribute.BLOCK_BREAK_SPEED,
                miningSpeedModifierKey,
                miningFraction,
                AttributeModifier.Operation.ADD_SCALAR);

        double newMaximumHealth = getAttributeValue(
                player,
                Attribute.MAX_HEALTH);
        adjustCurrentHealth(
                player,
                oldMaximumHealth,
                newMaximumHealth,
                healNewlyUnlockedHealth);
        restoreRememberedHealthRatio(player, newMaximumHealth);
    }

    /**
     * Convertit le bonus de durée d'apnée configuré vers la valeur de
     * l'attribut vanilla. Comme l'espérance de durée est multipliée par
     * {@code 1 + OXYGEN_BONUS}, la fraction à ajouter est identique.
     */
    static double oxygenAttributeForDurationBonus(
            double durationBonusFraction) {
        if (!Double.isFinite(durationBonusFraction)
                || durationBonusFraction <= 0.0D) {
            return 0.0D;
        }
        return durationBonusFraction;
    }

    private void removeBonuses(Player player) {
        if (player == null) {
            return;
        }

        removeModifier(
                player,
                Attribute.MAX_HEALTH,
                healthModifierKey);
        removeModifier(
                player,
                Attribute.OXYGEN_BONUS,
                oxygenModifierKey);
        removeModifier(
                player,
                Attribute.BLOCK_BREAK_SPEED,
                miningSpeedModifierKey);

        double maximumHealth = getAttributeValue(
                player,
                Attribute.MAX_HEALTH);
        adjustCurrentHealth(
                player,
                maximumHealth,
                maximumHealth,
                false);
    }

    private void rememberHealthRatio(
            Player player,
            boolean scheduleSave) {
        if (player == null) {
            return;
        }

        PlayerProgress data = getOrCreateData(
                player.getUniqueId(),
                player.getName());
        Double remembered = null;

        /*
         * Aucun ratio différé n'est utile au niveau 1 : retirer notre
         * modificateur ne change alors pas la vie maximale.
         */
        if (config.enabled
                && getHealthBonusPercent(player.getUniqueId()) > 0.0D) {
            double maximum = getAttributeValue(
                    player,
                    Attribute.MAX_HEALTH);
            try {
                double current = player.getHealth();
                if (Double.isFinite(maximum)
                        && maximum > 0.0D
                        && Double.isFinite(current)
                        && current > 0.0D) {
                    remembered = Math.max(
                            0.0D,
                            Math.min(1.0D, current / maximum));
                }
            } catch (Throwable throwable) {
                // Joueur en transition de mort : ne pas mémoriser un état douteux.
            }
        }

        if (sameNullableDouble(data.healthRatioToRestore, remembered)) {
            return;
        }
        data.healthRatioToRestore = remembered;
        if (scheduleSave) {
            markDirtyAndScheduleSave();
        } else {
            /*
             * La sauvegarde finale doit voir une version plus récente, sans
             * pour autant planifier une tâche alors que le plugin s'arrête.
             */
            synchronized (saveStateLock) {
                dirtyVersion++;
            }
        }
    }

    private void restoreRememberedHealthRatio(
            Player player,
            double newMaximumHealth) {
        PlayerProgress data = players.get(player.getUniqueId());
        if (data == null || data.healthRatioToRestore == null) {
            return;
        }
        if (!Double.isFinite(newMaximumHealth)
                || newMaximumHealth <= 0.0D) {
            return;
        }

        double ratio = data.healthRatioToRestore;
        if (!Double.isFinite(ratio)
                || ratio <= 0.0D
                || ratio > 1.0D) {
            data.healthRatioToRestore = null;
            markDirtyAndScheduleSave();
            return;
        }

        try {
            double target = Math.max(
                    0.0D,
                    Math.min(newMaximumHealth, newMaximumHealth * ratio));
            player.setHealth(target);
            data.healthRatioToRestore = null;
            markDirtyAndScheduleSave();
        } catch (Throwable throwable) {
            /*
             * Conserver le ratio pour une prochaine tentative (respawn/join)
             * plutôt que perdre silencieusement l'état de santé sauvegardé.
             */
        }
    }

    private static boolean sameNullableDouble(
            Double left,
            Double right) {
        if (left == null || right == null) {
            return left == right;
        }
        return Math.abs(left - right) < 0.000_000_1D;
    }

    private static Double sanitizeHealthRatio(Double rawRatio) {
        if (rawRatio == null
                || !Double.isFinite(rawRatio)
                || rawRatio <= 0.0D
                || rawRatio > 1.0D) {
            return null;
        }
        return rawRatio;
    }

    private void replaceModifier(
            Player player,
            Attribute attribute,
            NamespacedKey key,
            double amount,
            AttributeModifier.Operation operation) {
        try {
            AttributeInstance instance =
                    player.getAttribute(attribute);
            if (instance == null) {
                return;
            }

            instance.removeModifier(key);
            if (amount > 0.0D && Double.isFinite(amount)) {
                instance.addTransientModifier(
                        new AttributeModifier(
                                key,
                                amount,
                                operation));
            }
        } catch (Throwable throwable) {
            plugin.getLogger().fine(
                    "[Progression] Attribut indisponible: "
                            + attribute.getKey());
        }
    }

    private void removeModifier(
            Player player,
            Attribute attribute,
            NamespacedKey key) {
        try {
            AttributeInstance instance =
                    player.getAttribute(attribute);
            if (instance != null) {
                instance.removeModifier(key);
            }
        } catch (Throwable throwable) {
            // Nettoyage au mieux pendant l'arrêt ou dans MockBukkit.
        }
    }

    private static double getAttributeValue(
            Player player,
            Attribute attribute) {
        try {
            AttributeInstance instance =
                    player.getAttribute(attribute);
            return instance == null
                    ? Double.NaN
                    : instance.getValue();
        } catch (Throwable throwable) {
            return Double.NaN;
        }
    }

    private static void adjustCurrentHealth(
            Player player,
            double oldMaximum,
            double newMaximum,
            boolean healIncrease) {
        if (!Double.isFinite(newMaximum)
                || newMaximum <= 0.0D) {
            return;
        }

        try {
            double current = player.getHealth();
            double target = current;
            if (healIncrease
                    && Double.isFinite(oldMaximum)
                    && newMaximum > oldMaximum) {
                // Le level-up donne immédiatement les nouveaux points de vie,
                // sans soigner le reste de la barre.
                target += newMaximum - oldMaximum;
            }
            target = Math.max(0.0D, Math.min(newMaximum, target));
            if (Math.abs(target - current) > 0.0001D) {
                player.setHealth(target);
            }
        } catch (Throwable throwable) {
            // Un joueur mort ou en respawn refuse parfois setHealth.
        }
    }

    private void notifyLevelUp(
            Player player,
            int oldLevel,
            int newLevel) {
        String transition = newLevel == oldLevel + 1
                ? "Tu passes niveau "
                        + ChatColor.GREEN + newLevel
                : "Tu passes du niveau "
                        + ChatColor.GREEN + oldLevel
                        + ChatColor.GRAY + " au niveau "
                        + ChatColor.GREEN + newLevel;
        player.sendMessage(
                PREFIX + transition + ChatColor.GRAY + " !");

        player.sendMessage(
                ChatColor.GRAY + "Bonus globaux : "
                        + ChatColor.RED + "+"
                        + formatPercent(
                        getHealthBonusPercentForLevel(newLevel))
                        + "% PV"
                        + ChatColor.DARK_GRAY + " | "
                        + ChatColor.AQUA + "+"
                        + formatPercent(
                        getOxygenBonusPercentForLevel(newLevel))
                        + "% apnée"
                        + ChatColor.DARK_GRAY + " | "
                        + ChatColor.GOLD + "+"
                        + formatPercent(
                        getDamageBonusPercentForLevel(newLevel))
                        + "% dégâts"
                        + ChatColor.DARK_GRAY + " | "
                        + ChatColor.YELLOW + "+"
                        + formatPercent(
                        getMiningSpeedBonusPercentForLevel(newLevel))
                        + "% minage");

        if (config.titleOnLevelUp) {
            int gainedLevels = Math.max(1, newLevel - oldLevel);
            String subtitle = newLevel >= config.maxLevel
                    ? ChatColor.GREEN + "Niveau maximum atteint"
                    : ChatColor.YELLOW
                    + "+"
                    + gainedLevels
                    + (gainedLevels > 1
                    ? " paliers de bonus globaux"
                    : " palier de bonus global");
            player.sendTitle(
                    ChatColor.GOLD + "Niveau " + newLevel,
                    subtitle,
                    10,
                    50,
                    10);
        }

        if (config.soundOnLevelUp) {
            try {
                player.playSound(
                        player.getLocation(),
                        Sound.ENTITY_PLAYER_LEVELUP,
                        1.0F,
                        1.0F);
            } catch (Throwable throwable) {
                plugin.getLogger().fine(
                        "[Progression] Son de level-up indisponible.");
            }
        }
    }

    private boolean handleInfoCommand(
            CommandSender sender,
            String[] args) {
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(
                        ChatColor.RED
                                + "Depuis la console: /niveau info <joueur>");
                return true;
            }
            sendProgressInfo(
                    sender,
                    player.getUniqueId(),
                    player.getName());
            return true;
        }

        if (!requireAdmin(sender)) {
            return true;
        }
        PlayerTarget target = findTarget(args[1]);
        if (target == null) {
            sender.sendMessage(
                    ChatColor.RED
                            + "Joueur inconnu dans les données locales: "
                            + args[1]);
            return true;
        }
        sendProgressInfo(
                sender,
                target.playerId(),
                target.displayName());
        return true;
    }

    private boolean handleAddXpCommand(
            CommandSender sender,
            String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length != 3) {
            sender.sendMessage(
                    ChatColor.RED
                            + "Usage: /niveau addxp <joueur> <quantité>");
            return true;
        }

        PlayerTarget target = findTarget(args[1]);
        if (target == null) {
            sender.sendMessage(
                    ChatColor.RED
                            + "Joueur inconnu dans les données locales: "
                            + args[1]);
            return true;
        }

        Long requested = parsePositiveLong(args[2]);
        if (requested == null) {
            sender.sendMessage(
                    ChatColor.RED
                            + "La quantité doit être un entier strictement positif.");
            return true;
        }

        long added;
        if (target.onlinePlayer() != null) {
            added = grantExperienceInternal(
                    target.onlinePlayer(),
                    requested,
                    "Administration",
                    true);
        } else {
            PlayerProgress data = getOrCreateData(
                    target.playerId(),
                    target.displayName());
            long oldXp = config.clampXp(data.xp);
            data.xp = config.clampXp(
                    saturatingAdd(oldXp, requested));
            added = data.xp - oldXp;
            markDirtyAndScheduleSave();
        }

        sender.sendMessage(
                ChatColor.GREEN + "XP ajoutée à "
                        + target.displayName() + " : "
                        + ProgressionHud.formatNumber(added)
                        + " (niveau "
                        + getLevel(target.playerId()) + ").");
        return true;
    }

    private boolean handleSetLevelCommand(
            CommandSender sender,
            String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length != 3) {
            sender.sendMessage(
                    ChatColor.RED
                            + "Usage: /niveau setlevel <joueur> <1-"
                            + config.maxLevel + ">");
            return true;
        }

        PlayerTarget target = findTarget(args[1]);
        if (target == null) {
            sender.sendMessage(
                    ChatColor.RED
                            + "Joueur inconnu dans les données locales: "
                            + args[1]);
            return true;
        }

        Integer level = parseLevel(args[2]);
        if (level == null) {
            sender.sendMessage(
                    ChatColor.RED
                            + "Le niveau doit être compris entre 1 et "
                            + config.maxLevel + ".");
            return true;
        }

        setExperience(
                target,
                config.getTotalXpForLevel(level),
                true);
        sender.sendMessage(
                ChatColor.GREEN + target.displayName()
                        + " est maintenant niveau " + level + ".");
        return true;
    }

    private boolean handleResetCommand(
            CommandSender sender,
            String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage(
                    ChatColor.RED
                            + "Usage: /niveau reset <joueur>");
            return true;
        }

        PlayerTarget target = findTarget(args[1]);
        if (target == null) {
            sender.sendMessage(
                    ChatColor.RED
                            + "Joueur inconnu dans les données locales: "
                            + args[1]);
            return true;
        }

        setExperience(target, 0L, false);
        sender.sendMessage(
                ChatColor.GREEN + "Progression réinitialisée pour "
                        + target.displayName() + ".");
        return true;
    }

    private void setExperience(
            PlayerTarget target,
            long xp,
            boolean notifyTarget) {
        PlayerProgress data = getOrCreateData(
                target.playerId(),
                target.displayName());
        int oldLevel = config.getLevelForXp(data.xp);
        data.xp = config.clampXp(xp);
        data.lastKnownName = target.displayName();
        int newLevel = config.getLevelForXp(data.xp);

        if (target.onlinePlayer() != null) {
            applyBonuses(
                    target.onlinePlayer(),
                    newLevel > oldLevel);
            hud.hide(target.onlinePlayer());

            if (notifyTarget) {
                target.onlinePlayer().sendMessage(
                        PREFIX + "Ton niveau global a été fixé à "
                                + ChatColor.GREEN + newLevel
                                + ChatColor.GRAY + ".");
            }
        }
        rateWindows.remove(target.playerId());
        markDirtyAndScheduleSave();
    }

    private void sendProgressInfo(
            CommandSender sender,
            UUID playerId,
            String displayName) {
        long xp = getExperience(playerId);
        int level = config.getLevelForXp(xp);
        long current = config.getTotalXpForLevel(level);

        sender.sendMessage(
                ChatColor.GOLD + "[Niveau] "
                        + ChatColor.YELLOW + displayName);
        sender.sendMessage(
                ChatColor.GRAY + "Niveau global : "
                        + ChatColor.GREEN + level
                        + ChatColor.DARK_GRAY + "/"
                        + config.maxLevel);
        sender.sendMessage(
                ChatColor.GRAY + "XP totale : "
                        + ChatColor.AQUA
                        + ProgressionHud.formatNumber(xp));

        if (level >= config.maxLevel) {
            sender.sendMessage(
                    ChatColor.GRAY + "Progression : "
                            + ChatColor.GREEN
                            + "niveau maximum atteint");
        } else {
            long next = config.getTotalXpForLevel(level + 1);
            long inLevel = xp - current;
            long required = next - current;
            sender.sendMessage(
                    ChatColor.GRAY + "Palier actuel : "
                            + ChatColor.AQUA
                            + ProgressionHud.formatNumber(inLevel)
                            + ChatColor.GRAY + " / "
                            + ChatColor.AQUA
                            + ProgressionHud.formatNumber(required)
                            + ChatColor.GRAY + " (reste "
                            + ChatColor.AQUA
                            + ProgressionHud.formatNumber(next - xp)
                            + ChatColor.GRAY + ")");
        }

        sender.sendMessage(
                ChatColor.GRAY + "Bonus : "
                        + ChatColor.RED + "+"
                        + formatPercent(
                        getHealthBonusPercentForLevel(level))
                        + "% PV"
                        + ChatColor.DARK_GRAY + " | "
                        + ChatColor.AQUA + "+"
                        + formatPercent(
                        getOxygenBonusPercentForLevel(level))
                        + "% apnée"
                        + ChatColor.DARK_GRAY + " | "
                        + ChatColor.GOLD + "+"
                        + formatPercent(
                        getDamageBonusPercentForLevel(level))
                        + "% dégâts"
                        + ChatColor.DARK_GRAY + " | "
                        + ChatColor.YELLOW + "+"
                        + formatPercent(
                        getMiningSpeedBonusPercentForLevel(level))
                        + "% minage");
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(
                ChatColor.GOLD + "[Niveau] "
                        + ChatColor.YELLOW
                        + "Progression globale indépendante des métiers");
        sender.sendMessage(
                ChatColor.GOLD + "/niveau"
                        + ChatColor.GRAY
                        + " : affiche ton niveau, ton XP et tes bonus.");
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(
                    ChatColor.GOLD
                            + "/niveau info <joueur>"
                            + ChatColor.DARK_RED + " [admin]");
            sender.sendMessage(
                    ChatColor.GOLD
                            + "/niveau addxp <joueur> <quantité>"
                            + ChatColor.DARK_RED + " [admin]");
            sender.sendMessage(
                    ChatColor.GOLD
                            + "/niveau setlevel <joueur> <niveau>"
                            + ChatColor.DARK_RED + " [admin]");
            sender.sendMessage(
                    ChatColor.GOLD
                            + "/niveau reset <joueur>"
                            + ChatColor.DARK_RED + " [admin]");
        }
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        sender.sendMessage(
                ChatColor.RED
                        + "Tu n'as pas la permission "
                        + ADMIN_PERMISSION + ".");
        return false;
    }

    private PlayerTarget findTarget(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }

        Player online = Bukkit.getPlayerExact(query);
        if (online != null) {
            return new PlayerTarget(
                    online.getUniqueId(),
                    online.getName(),
                    online);
        }

        for (Map.Entry<UUID, PlayerProgress> entry
                : players.entrySet()) {
            String knownName = entry.getValue().lastKnownName;
            if (knownName != null
                    && knownName.equalsIgnoreCase(query)) {
                Player possiblyOnline =
                        Bukkit.getPlayer(entry.getKey());
                return new PlayerTarget(
                        entry.getKey(),
                        knownName,
                        possiblyOnline);
            }
        }

        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            String name = offline.getName();
            if (name != null
                    && name.equalsIgnoreCase(query)) {
                return new PlayerTarget(
                        offline.getUniqueId(),
                        name,
                        offline.getPlayer());
            }
        }
        return null;
    }

    private List<String> knownPlayerNames() {
        Set<String> names =
                new LinkedHashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        players.values().stream()
                .map(data -> data.lastKnownName)
                .filter(name -> name != null && !name.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(names::add);
        return new ArrayList<>(names);
    }

    private void updateKnownName(Player player) {
        if (player == null) {
            return;
        }

        PlayerProgress existing = players.get(player.getUniqueId());
        String previousName = existing == null
                ? null
                : existing.lastKnownName;
        PlayerProgress data = getOrCreateData(
                player.getUniqueId(),
                player.getName());
        if (!player.getName().equals(previousName)) {
            data.lastKnownName = player.getName();
            markDirtyAndScheduleSave();
        }
    }

    private PlayerProgress getOrCreateData(
            UUID playerId,
            String knownName) {
        PlayerProgress data = players.computeIfAbsent(
                playerId,
                ignored -> new PlayerProgress());
        if ((data.lastKnownName == null
                || data.lastKnownName.isBlank())
                && knownName != null
                && !knownName.isBlank()) {
            data.lastKnownName = knownName;
        }
        data.xp = config.clampXp(data.xp);
        return data;
    }

    private int completedLevelUps(int level) {
        int safe = Math.max(
                1,
                Math.min(config.maxLevel, level));
        return safe - 1;
    }

    private void loadFromDisk() {
        if (!dataFile.exists()) {
            return;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(dataFile);
        } catch (InvalidConfigurationException exception) {
            /*
             * Ne jamais remplacer silencieusement un YAML invalide. Le fichier
             * original est déplacé à côté avec un suffixe horodaté avant que le
             * module reparte sur une base vide.
             */
            quarantineCorruptDataFile(exception);
            return;
        } catch (IOException | RuntimeException exception) {
            /*
             * Une erreur de lecture peut être temporaire (droits, disque, etc.).
             * Bloquer les écritures protège le fichier existant d'un écrasement.
             */
            persistenceBlocked = true;
            plugin.getLogger().log(
                    Level.SEVERE,
                    "[Progression] progression.yml est illisible : "
                            + "sauvegarde désactivée jusqu'au redémarrage.",
                    exception);
            return;
        }

        ConfigurationSection section =
                yaml.getConfigurationSection("players");
        if (section == null) {
            return;
        }

        for (String rawUuid : section.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(rawUuid);
                ConfigurationSection playerSection =
                        section.getConfigurationSection(rawUuid);
                if (playerSection == null) {
                    continue;
                }

                PlayerProgress data = new PlayerProgress();
                data.lastKnownName =
                        playerSection.getString("name", null);
                data.xp = config.clampXp(
                        playerSection.getLong("xp", 0L));
                data.healthRatioToRestore = sanitizeHealthRatio(
                        playerSection.getDouble("health-ratio", 0.0D));
                players.put(playerId, data);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning(
                        "[Progression] UUID invalide dans progression.yml: "
                                + rawUuid);
            }
        }
    }

    private void quarantineCorruptDataFile(
            InvalidConfigurationException parseFailure) {
        File parent = dataFile.getParentFile();
        File backup = parent == null
                ? new File(dataFile.getName()
                        + ".corrupt-" + System.currentTimeMillis() + ".bak")
                : new File(parent, dataFile.getName()
                        + ".corrupt-" + System.currentTimeMillis() + ".bak");
        try {
            Files.move(
                    dataFile.toPath(),
                    backup.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().log(
                    Level.SEVERE,
                    "[Progression] progression.yml était invalide et a été "
                            + "mis en quarantaine dans " + backup.getName()
                            + ". Une nouvelle sauvegarde saine sera créée.",
                    parseFailure);
        } catch (IOException | RuntimeException backupFailure) {
            persistenceBlocked = true;
            plugin.getLogger().log(
                    Level.SEVERE,
                    "[Progression] progression.yml est invalide et sa copie de "
                            + "sécurité a échoué : sauvegarde désactivée pour "
                            + "éviter toute perte. Cause YAML : "
                            + parseFailure.getMessage(),
                    backupFailure);
        }
    }

    private Map<UUID, SavedProgress> snapshotPlayers() {
        Map<UUID, SavedProgress> snapshot =
                new HashMap<>();
        for (Map.Entry<UUID, PlayerProgress> entry
                : players.entrySet()) {
            PlayerProgress data = entry.getValue();
            snapshot.put(
                    entry.getKey(),
                    new SavedProgress(
                            data.lastKnownName,
                            config.clampXp(data.xp),
                            sanitizeHealthRatio(
                                    data.healthRatioToRestore)));
        }
        return snapshot;
    }

    private String serializeSnapshot(
            Map<UUID, SavedProgress> snapshot) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 2);
        yaml.set("max-level", config.maxLevel);

        List<Map.Entry<UUID, SavedProgress>> entries =
                new ArrayList<>(snapshot.entrySet());
        entries.sort(
                Comparator.comparing(
                        entry -> entry.getKey().toString()));

        ConfigurationSection playersSection =
                yaml.createSection("players");
        for (Map.Entry<UUID, SavedProgress> entry : entries) {
            ConfigurationSection playerSection =
                    playersSection.createSection(
                            entry.getKey().toString());
            SavedProgress data = entry.getValue();
            playerSection.set("name", data.lastKnownName());
            playerSection.set("xp", data.xp());
            playerSection.set(
                    "health-ratio",
                    data.healthRatioToRestore());
        }
        return yaml.saveToString();
    }

    private boolean writeSerializedSnapshot(
            String serializedYaml,
            long version) {
        if (persistenceBlocked) {
            return false;
        }

        synchronized (diskWriteLock) {
            if (version <= latestWrittenVersion) {
                return true;
            }

            File parent = dataFile.getParentFile();
            try {
                if (parent != null && !parent.exists()) {
                    Files.createDirectories(parent.toPath());
                }
                File temporary = parent == null
                        ? new File(dataFile.getName() + ".tmp")
                        : new File(parent, dataFile.getName() + ".tmp");
                Files.writeString(
                        temporary.toPath(),
                        serializedYaml,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                try {
                    Files.move(
                            temporary.toPath(),
                            dataFile.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(
                            temporary.toPath(),
                            dataFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                latestWrittenVersion = version;
                return true;
            } catch (IOException | RuntimeException exception) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "[Progression] Impossible de sauvegarder progression.yml",
                        exception);
                return false;
            }
        }
    }

    private void markDirtyAndScheduleSave() {
        synchronized (saveStateLock) {
            dirtyVersion++;
            if (shuttingDown || persistenceBlocked) {
                return;
            }
        }
        requestSave(SAVE_DEBOUNCE_TICKS);
    }

    /**
     * Regroupe les gains rapprochés au lieu d'écrire progression.yml à chaque
     * bloc cassé. L'instantané reste créé sur le thread principal ; seule
     * l'écriture disque est exécutée de façon asynchrone.
     */
    private void requestSave(long delayTicks) {
        synchronized (saveStateLock) {
            if (shuttingDown || persistenceBlocked) {
                return;
            }
            if (saveInProgress) {
                saveQueued = true;
                return;
            }
            if (pendingSaveTask != null
                    && !pendingSaveTask.isCancelled()) {
                return;
            }

            try {
                pendingSaveTask = Bukkit.getScheduler().runTaskLater(
                        plugin,
                        this::startAsyncSave,
                        Math.max(1L, delayTicks));
            } catch (IllegalPluginAccessException exception) {
                pendingSaveTask = null;
                plugin.getLogger().fine(
                        "[Progression] Sauvegarde différée ignorée pendant l'arrêt.");
            }
        }
    }

    private void startAsyncSave() {
        final Map<UUID, SavedProgress> snapshot;
        final long version;

        synchronized (saveStateLock) {
            pendingSaveTask = null;
            if (shuttingDown || persistenceBlocked) {
                return;
            }
            if (saveInProgress) {
                saveQueued = true;
                return;
            }

            /*
             * Les données joueurs sont confinées au thread serveur. Les copier
             * ici évite toute lecture concurrente de la HashMap dans le worker.
             */
            snapshot = snapshotPlayers();
            version = dirtyVersion;
            saveInProgress = true;
            saveQueued = false;
        }

        /*
         * La sérialisation Bukkit/YAML reste elle aussi sur le thread serveur.
         * Le worker n'effectue ensuite que les opérations de fichiers Java.
         */
        final String serializedYaml;
        try {
            serializedYaml = serializeSnapshot(snapshot);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "[Progression] Impossible de sérialiser progression.yml",
                    exception);
            finishAsyncSave(false);
            return;
        }

        try {
            Bukkit.getScheduler().runTaskAsynchronously(
                    plugin,
                    () -> {
                        boolean written = writeSerializedSnapshot(
                                serializedYaml,
                                version);
                        finishAsyncSave(written);
                    });
        } catch (IllegalPluginAccessException exception) {
            synchronized (saveStateLock) {
                saveInProgress = false;
                saveQueued = true;
            }
            plugin.getLogger().fine(
                    "[Progression] Écriture asynchrone ignorée pendant l'arrêt.");
        }
    }

    private void finishAsyncSave(boolean written) {
        final boolean shouldReschedule;
        synchronized (saveStateLock) {
            saveInProgress = false;
            shouldReschedule = saveQueued
                    || !written
                    || dirtyVersion > latestWrittenVersion;
            saveQueued = false;
        }

        if (!shouldReschedule
                || shuttingDown
                || persistenceBlocked) {
            return;
        }

        long delay = written
                ? SAVE_DEBOUNCE_TICKS
                : SAVE_RETRY_TICKS;
        try {
            /*
             * Le callback revient d'abord sur le thread principal afin que la
             * future photographie de players reste sûre.
             */
            Bukkit.getScheduler().runTask(
                    plugin,
                    () -> requestSave(delay));
        } catch (IllegalPluginAccessException exception) {
            plugin.getLogger().fine(
                    "[Progression] Replanification ignorée pendant l'arrêt.");
        }
    }

    private Integer parseLevel(String raw) {
        try {
            int level = Integer.parseInt(raw);
            return level >= 1 && level <= config.maxLevel
                    ? level
                    : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Long parsePositiveLong(String raw) {
        try {
            long value = Long.parseLong(raw);
            return value > 0L ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static List<String> filterPrefix(
            List<String> values,
            String prefix) {
        String normalized = prefix == null
                ? ""
                : prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT)
                        .startsWith(normalized))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private static boolean isWood(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.endsWith("_LOG")
                || name.endsWith("_STEM")
                || name.endsWith("_HYPHAE");
    }

    private static String formatPercent(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001D) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static final class PlayerProgress {
        private String lastKnownName;
        private long xp;
        private Double healthRatioToRestore;
    }

    private static final class RateWindow {
        private long startedAtNanos;
        private long granted;

        private RateWindow(long startedAtNanos) {
            this.startedAtNanos = startedAtNanos;
        }
    }

    private record SavedProgress(
            String lastKnownName,
            long xp,
            Double healthRatioToRestore) {
    }

    private record PlayerTarget(
            UUID playerId,
            String displayName,
            Player onlinePlayer) {
    }
}
