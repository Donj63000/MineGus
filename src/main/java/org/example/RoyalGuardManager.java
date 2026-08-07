package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowman;
import org.bukkit.entity.Tameable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Gère les gardes royaux temporaires associés à un joueur connecté.
 */
public final class RoyalGuardManager implements TabExecutor, Listener {

    static final String GUARD_NAME = "Garde Royale";
    static final String GUARD_TYPE = "royal";
    static final Material ROYAL_HELMET = Material.NETHERITE_HELMET;
    static final Material ROYAL_CHESTPLATE = Material.NETHERITE_CHESTPLATE;
    static final Material ROYAL_LEGGINGS = Material.NETHERITE_LEGGINGS;
    static final Material ROYAL_BOOTS = Material.NETHERITE_BOOTS;
    static final Material ROYAL_SWORD = Material.NETHERITE_SWORD;
    static final int ARMOR_PROTECTION_LEVEL = 4;
    static final int ARMOR_UNBREAKING_LEVEL = 3;
    static final int ARMOR_MENDING_LEVEL = 1;
    static final int SWORD_SHARPNESS_LEVEL = 5;
    static final int SWORD_UNBREAKING_LEVEL = 3;
    static final int SWORD_MENDING_LEVEL = 1;

    private static final String USE_PERMISSION = "mineplugin.garde.use";
    private static final int GUARD_COUNT = 2;
    private static final long FOLLOW_PERIOD_TICKS = 10L;
    private static final long COMBAT_MAINTENANCE_PERIOD_TICKS = 5L;
    private static final int AWARENESS_SCAN_INTERVAL_FOLLOW_CYCLES = 2;
    private static final int MAX_PATH_FAILURES = 3;
    private static final int MAX_TRACKED_THREATS = 24;
    private static final double DEFAULT_FOLLOW_RADIUS = 20.0D;
    private static final double DEFAULT_FORMATION_SIDE_DISTANCE = 2.25D;
    private static final double DEFAULT_FORMATION_REAR_DISTANCE = 1.75D;
    private static final double DEFAULT_FORMATION_TOLERANCE = 0.9D;
    private static final double DEFAULT_PERSONAL_SPACE_RADIUS = 1.65D;
    private static final double DEFAULT_VIEW_CLEARANCE_DISTANCE = 4.0D;
    private static final double DEFAULT_PROTECTION_RADIUS = 32.0D;
    private static final double DEFAULT_THREAT_DETECTION_RADIUS = 32.0D;
    private static final double DEFAULT_COMBAT_LEASH_RADIUS = 48.0D;
    private static final int DEFAULT_THREAT_DURATION_SECONDS = 10;
    private static final int DEFAULT_RESPAWN_DELAY_SECONDS = 20;
    private static final boolean DEFAULT_FRIENDLY_FIRE = false;
    private static final boolean DEFAULT_ASSIST_OWNER_ATTACKS = true;
    private static final boolean DEFAULT_PROACTIVE_DEFENSE = true;
    private static final boolean DEFAULT_FINISH_ENGAGED_TARGETS = true;
    private static final boolean DEFAULT_IRON_GOLEM_NEUTRALITY = true;
    private static final boolean DEFAULT_IRON_GOLEM_RETALIATION = true;
    private static final boolean DEFAULT_SNOW_GOLEM_NEUTRALITY = true;
    private static final boolean DEFAULT_ALLOW_SLEEP_NEAR_GUARDS = true;
    private static final boolean DEFAULT_PREVENT_BLOCK_DAMAGE = true;
    private static final boolean DEFAULT_NOTIFICATIONS = true;
    private static final double DEFAULT_IRON_GOLEM_NEUTRALITY_RADIUS = 32.0D;
    private static final double DEFAULT_MAX_HEALTH = 100.0D;
    private static final double DEFAULT_ATTACK_DAMAGE = 16.0D;
    private static final double DEFAULT_MOVEMENT_SPEED = 0.35D;
    private static final double DEFAULT_FOLLOW_RANGE = 48.0D;
    private static final double DEFAULT_KNOCKBACK_RESISTANCE = 0.6D;
    private static final double FOLLOW_SPEED = 1.15D;
    private static final double NO_REINFORCEMENT_CHANCE = 0.0D;
    /*
     * La vérification vanilla part du volume complet du bloc-lit puis l’agrandit. Comme
     * getNearbyEntities travaille autour d’un point central, le demi-bloc supplémentaire
     * évite de manquer un ennemi exactement sur la bordure du volume de sécurité.
     */
    private static final double SLEEP_SAFETY_HORIZONTAL_RADIUS = 8.5D;
    private static final double SLEEP_SAFETY_VERTICAL_RADIUS = 5.5D;
    private static final double VIEW_CLEARANCE_COSINE = Math.cos(Math.toRadians(55.0D));
    private static final double MIN_DIRECTION_LENGTH_SQUARED = 1.0E-6D;
    private static final double EMERGENCY_OVERLAP_RADIUS = 0.65D;
    private static final double COMBAT_RECALL_MARGIN = 6.0D;
    private static final double MIN_GUARD_SEPARATION = 1.20D;
    private static final double MIN_FORMATION_SIDE_DISTANCE = 0.90D;
    private static final double FORMATION_PERSONAL_SPACE_MARGIN = 0.75D;
    private static final double CLEARANCE_SIDE_MARGIN = 0.85D;
    private static final double CLEARANCE_SIDE_REUSE_THRESHOLD = 0.35D;
    private static final double CLEARANCE_MIN_FORWARD_OFFSET = -0.50D;
    private static final double CLEARANCE_MAX_FORWARD_OFFSET = 1.25D;
    private static final double MAX_FOLLOW_RADIUS = 128.0D;
    private static final double MAX_FORMATION_OFFSET = 8.0D;
    private static final double MAX_FORMATION_TOLERANCE = 4.0D;
    private static final double MAX_PERSONAL_SPACE_RADIUS = 4.0D;
    private static final double MAX_VIEW_CLEARANCE_DISTANCE = 12.0D;
    private static final double MAX_PROTECTION_RADIUS = 128.0D;
    private static final double MAX_THREAT_DETECTION_RADIUS = 64.0D;
    private static final double MAX_COMBAT_LEASH_RADIUS = 128.0D;
    private static final int MAX_THREAT_DURATION_SECONDS = 300;
    private static final int MAX_RESPAWN_DELAY_SECONDS = 3_600;
    private static final double MAX_CONFIGURED_HEALTH = 1024.0D;
    private static final double MAX_CONFIGURED_ATTACK_DAMAGE = 2048.0D;
    private static final double MAX_CONFIGURED_MOVEMENT_SPEED = 1024.0D;
    private static final double MAX_CONFIGURED_FOLLOW_RANGE = 128.0D;
    private static final double MAX_CONFIGURED_KNOCKBACK_RESISTANCE = 1.0D;
    private static final double MAX_IRON_GOLEM_NEUTRALITY_RADIUS = 64.0D;
    private static final int SAFE_LOCATION_SEARCH_RADIUS = 4;
    private static final int SAFE_LOCATION_VERTICAL_RANGE = 4;
    private static final long ERROR_LOG_COOLDOWN_MILLIS = 30_000L;
    private static final int[] SAFE_LOCATION_VERTICAL_OFFSETS = {0, 1, -1, 2, -2, 3, -3, 4, -4};
    private static final List<String> GUARD_SUBCOMMANDS = List.of("invoquer", "renvoyer", "statut", "aide");

    private final JavaPlugin plugin;
    private final GuardNavigator navigator;
    private final GuardFactory guardFactory;
    private final GuardLocationResolver locationResolver;
    private final Map<UUID, GuardSquad> squads = new HashMap<>();
    private final Map<UUID, Husk> guardsById = new HashMap<>();
    private final Map<UUID, Long> lastNavigationErrorLogAt = new HashMap<>();
    private final Map<UUID, BukkitTask> pendingGolemDeaggroTasks = new HashMap<>();
    private BukkitTask followTask;
    private BukkitTask combatTask;
    private boolean shuttingDown;

    public RoyalGuardManager(JavaPlugin plugin) {
        this(plugin, new GuardNavigator() {
            @Override
            public boolean moveTo(Mob guard, Location target, double speed) {
                return guard.getPathfinder().moveTo(target, speed);
            }

            @Override
            public void stop(Mob guard) {
                // Arrêter explicitement l'ancien chemin empêche le Husk de terminer une route
                // devenue obsolète jusque dans le corps ou le champ de vision du propriétaire.
                guard.getPathfinder().stopPathfinding();
            }
        }, true, null, null);
    }

    RoyalGuardManager(JavaPlugin plugin, GuardNavigator navigator, boolean startFollowTask) {
        this(plugin, navigator, startFollowTask, null, null);
    }

    RoyalGuardManager(JavaPlugin plugin, GuardNavigator navigator, boolean startFollowTask, GuardFactory guardFactory) {
        this(plugin, navigator, startFollowTask, guardFactory, null);
    }

    RoyalGuardManager(JavaPlugin plugin,
                      GuardNavigator navigator,
                      boolean startFollowTask,
                      GuardFactory guardFactory,
                      GuardLocationResolver locationResolver) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.guardFactory = guardFactory != null ? guardFactory : this::createConfiguredGuard;
        this.locationResolver = locationResolver != null ? locationResolver : this::findSafeLocationNear;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        removeOrphanedGuards();

        if (startFollowTask) {
            startMaintenanceTasks();
        }
    }

    private void startMaintenanceTasks() {
        try {
            followTask = Bukkit.getScheduler().runTaskTimer(plugin, this::followActiveSquads,
                    FOLLOW_PERIOD_TICKS, FOLLOW_PERIOD_TICKS);
            combatTask = Bukkit.getScheduler().runTaskTimer(plugin, this::maintainActiveThreats,
                    1L, COMBAT_MAINTENANCE_PERIOD_TICKS);
        } catch (RuntimeException exception) {
            // Si le second enregistrement échoue, on annule aussi le premier afin de ne jamais
            // laisser un gestionnaire partiellement initialisé tourner en arrière-plan.
            cancelTaskQuietly(followTask);
            cancelTaskQuietly(combatTask);
            followTask = null;
            combatTask = null;
            HandlerList.unregisterAll(this);
            throw exception;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Cette commande doit être exécutée par un joueur.");
            return true;
        }
        if (!player.hasPermission(USE_PERMISSION)) {
            player.sendMessage(ChatColor.RED + "Tu n'as pas la permission pour /garde.");
            return true;
        }
        if (shuttingDown) {
            player.sendMessage(ChatColor.RED + "Le service des gardes est en cours d'arrêt.");
            return true;
        }

        GuardCommandAction action = resolveCommandAction(args);
        if (action == GuardCommandAction.INVALID) {
            player.sendMessage(ChatColor.RED + "Sous-commande inconnue.");
            sendGuardHelp(player);
            return true;
        }

        UUID ownerId = player.getUniqueId();
        switch (action) {
            case TOGGLE -> {
                if (hasActiveSquad(ownerId)) {
                    dismissSquad(ownerId);
                    player.sendMessage(ChatColor.YELLOW + "Tes gardes royaux sont repartis.");
                } else {
                    summonSquad(player);
                }
            }
            case SUMMON -> {
                if (hasActiveSquad(ownerId)) {
                    player.sendMessage(ChatColor.YELLOW + "Tes gardes royaux sont déjà en service.");
                    sendGuardStatus(player);
                } else {
                    summonSquad(player);
                }
            }
            case DISMISS -> {
                if (!hasActiveSquad(ownerId)) {
                    player.sendMessage(ChatColor.YELLOW + "Tu n'as aucun garde royal à renvoyer.");
                } else {
                    dismissSquad(ownerId);
                    player.sendMessage(ChatColor.YELLOW + "Tes gardes royaux sont repartis.");
                }
            }
            case STATUS -> sendGuardStatus(player);
            case HELP -> sendGuardHelp(player);
            case INVALID -> {
                // Ce cas est traité avant le switch ; il reste présent pour garder le switch exhaustif.
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(USE_PERMISSION) || args.length != 1) {
            return List.of();
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);
        return GUARD_SUBCOMMANDS.stream()
                .filter(subcommand -> subcommand.startsWith(prefix))
                .toList();
    }

    private GuardCommandAction resolveCommandAction(String[] args) {
        if (args == null || args.length == 0) {
            return GuardCommandAction.TOGGLE;
        }
        if (args.length != 1) {
            return GuardCommandAction.INVALID;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "invoquer", "invocation", "summon", "on" -> GuardCommandAction.SUMMON;
            case "renvoyer", "retirer", "dismiss", "off" -> GuardCommandAction.DISMISS;
            case "statut", "status", "info" -> GuardCommandAction.STATUS;
            case "aide", "help", "?" -> GuardCommandAction.HELP;
            default -> GuardCommandAction.INVALID;
        };
    }

    private void summonSquad(Player player) {
        UUID ownerId = player.getUniqueId();
        GuardSquad staleSquad = squads.get(ownerId);
        if (staleSquad != null) {
            // Ici, je nettoie tout état résiduel avant une nouvelle invocation pour empêcher les doublons.
            dismissSquad(ownerId);
        }
        if (!player.isOnline() || player.isDead()) {
            player.sendMessage(ChatColor.RED + "Impossible d'invoquer tes gardes dans ton état actuel.");
            return;
        }
        if (isPeacefulWorld(player.getWorld())) {
            /*
             * Un Husk est supprimé par le moteur en difficulté Paisible, même lorsqu'il est
             * persistant. Refuser explicitement évite un duo qui disparaît puis respawn en boucle.
             */
            player.sendMessage(ChatColor.RED
                    + "Les gardes royaux ne peuvent pas rester dans un monde en difficulté Paisible.");
            return;
        }

        GuardSquad squad = new GuardSquad(ownerId, player);
        squads.put(ownerId, squad);
        for (int slot = 0; slot < GUARD_COUNT; slot++) {
            if (spawnGuard(squad, player, slot)) {
                continue;
            }

            // Ici, je retire le premier garde pour ne jamais laisser un duo partiel actif.
            dismissSquad(ownerId);
            player.sendMessage(ChatColor.RED + "Impossible d'invoquer tes deux gardes royaux. Réessaie dans un instant.");
            return;
        }
        player.sendMessage(ChatColor.GOLD + "Tes deux gardes royaux sont à tes côtés.");
    }

    private void sendGuardStatus(Player player) {
        GuardSquad squad = squads.get(player.getUniqueId());
        if (squad == null || !squad.active) {
            player.sendMessage(ChatColor.GRAY + "Gardes royaux : " + ChatColor.RED + "inactifs");
            return;
        }

        int activeGuards = activeGuardCount(player.getUniqueId());
        int pendingRespawns = squad.respawnTasks.size();
        player.sendMessage(ChatColor.GRAY + "Gardes royaux : " + ChatColor.GREEN + "actifs"
                + ChatColor.GRAY + " (" + activeGuards + "/" + GUARD_COUNT + " présents, "
                + pendingRespawns + " retour(s) en attente)");
        if (squad.threat != null && isCurrentThreat(squad.ownerId, squad.threat)) {
            String targetName = squad.threat.getName();
            if (targetName == null || targetName.isBlank()) {
                targetName = squad.threat.getType() == null
                        ? squad.threat.getUniqueId().toString()
                        : squad.threat.getType().name();
            }
            player.sendMessage(ChatColor.GRAY + "Cible actuelle : " + ChatColor.RED + targetName);
        }
    }

    private void sendGuardHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "Commandes des gardes royaux :");
        sender.sendMessage(ChatColor.YELLOW + "/garde" + ChatColor.GRAY + " : invoque ou renvoie le duo.");
        sender.sendMessage(ChatColor.YELLOW + "/garde invoquer" + ChatColor.GRAY + " : invoque le duo sans bascule.");
        sender.sendMessage(ChatColor.YELLOW + "/garde renvoyer" + ChatColor.GRAY + " : renvoie le duo.");
        sender.sendMessage(ChatColor.YELLOW + "/garde statut" + ChatColor.GRAY + " : affiche l'état du duo.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnerDamaged(EntityDamageByEntityEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof Player owner)) {
            return;
        }

        GuardSquad squad = squads.get(owner.getUniqueId());
        if (squad == null || !squad.active) {
            return;
        }

        LivingEntity attacker = resolveLivingAttacker(event);
        if (attacker == null || isFriendlyToOwner(attacker, owner.getUniqueId())
                || !isInSameWorld(owner, attacker)) {
            return;
        }

        squad.owner = owner;
        engageThreat(squad, attacker, ThreatReason.OWNER_DAMAGED, false);
    }

    /**
     * Ordonne au duo d'assister une attaque réellement validée du propriétaire. DamageSource
     * permet également de reconnaître le joueur derrière une flèche, un trident ou une potion.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnerAttacks(EntityDamageByEntityEvent event) {
        if (event.isCancelled() || !isOwnerAttackAssistEnabled()) {
            return;
        }

        LivingEntity target = resolveLivingDamageTarget(event.getEntity());
        if (target == null) {
            return;
        }

        LivingEntity attacker = resolveLivingAttacker(event);
        if (!(attacker instanceof Player owner)) {
            return;
        }

        GuardSquad squad = squads.get(owner.getUniqueId());
        if (squad == null || !squad.active) {
            return;
        }

        squad.owner = owner;
        // Une nouvelle cible explicitement frappée par le maître remplace une autre consigne
        // de même priorité, mais ne détourne pas les gardes d'un danger immédiat plus grave.
        engageThreat(squad, target, ThreatReason.OWNER_ATTACKED_TARGET, true);
    }

    /**
     * Détecte l'intention hostile avant le premier dégât : dès qu'un mob choisit le propriétaire
     * ou l'un de ses gardes comme cible de combat, le duo peut l'intercepter. Les raisons de suivi
     * non agressives (par exemple un animal attiré par de la nourriture) sont ignorées.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityTargetsOwner(EntityTargetLivingEntityEvent event) {
        if (event.isCancelled() || !isProactiveDefenseEnabled()
                || !(event.getEntity() instanceof LivingEntity threat)
                || !isHostileTargetReason(event.getReason())) {
            return;
        }

        LivingEntity protectedTarget = event.getTarget();
        GuardSquad squad;
        ThreatReason reason;
        if (protectedTarget instanceof Player owner) {
            squad = squads.get(owner.getUniqueId());
            reason = ThreatReason.TARGETING_OWNER;
            if (squad != null) {
                squad.owner = owner;
            }
        } else if (isTrackedGuard(protectedTarget)) {
            UUID ownerId = ownerOf(protectedTarget);
            squad = ownerId == null ? null : squads.get(ownerId);
            reason = ThreatReason.TARGETING_GUARD;
        } else {
            return;
        }

        if (squad == null || !squad.active) {
            return;
        }
        engageThreat(squad, threat, reason, false);
    }

    /**
     * Empêche le propriétaire ou l'autre membre du duo de blesser accidentellement un garde.
     * Le listener s'exécute même si un autre plugin a déjà annulé le coup afin de retirer aussi
     * une éventuelle cible amicale restée mémorisée par l'IA.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onGuardFriendlyFire(EntityDamageByEntityEvent event) {
        if (isFriendlyFireEnabled() || !isTrackedGuard(event.getEntity())) {
            return;
        }

        UUID ownerId = ownerOf(event.getEntity());
        LivingEntity attacker = resolveLivingAttacker(event);
        if (ownerId == null || attacker == null || !isFriendlyToOwner(attacker, ownerId)) {
            return;
        }

        event.setCancelled(true);
        if (event.getEntity() instanceof Husk guard) {
            GuardSquad squad = squads.get(ownerId);
            if (squad != null && squad.active) {
                // Une menace ennemie déjà valide reste prioritaire : un coup accidentel du maître
                // ne doit jamais remettre le garde à l'état passif au milieu d'un combat.
                restoreCurrentThreatTarget(squad, guard);
            } else if (guard.getTarget() != null
                    && guard.getTarget().getUniqueId().equals(attacker.getUniqueId())) {
                guard.setTarget(null);
            }
        }
    }

    /**
     * Les gardes sont des Husks : les golems de fer vanilla, ceux des villages MineGus et ceux
     * créés par un joueur les considèrent donc comme des monstres. On neutralise l'acquisition
     * avant qu'elle soit appliquée, puis on la vérifie encore au tick suivant.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onIronGolemTargetsGuard(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof IronGolem golem)
                || !isRoyalGuard(event.getTarget())
                || !isIronGolemNeutralityEnabled()) {
            return;
        }

        // setTarget(null) sur l'événement évite que le moteur applique la nouvelle cible.
        // L'annulation protège aussi contre les implémentations qui ignoreraient la substitution.
        event.setTarget(null);
        event.setCancelled(true);
        neutralizeGolemAggression(golem);
    }

    /**
     * Second filet de sécurité : même si une cible a été injectée directement par un autre plugin
     * ou conservée par l'IA vanilla, aucun coup de golem de fer ne peut atteindre un garde suivi.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onIronGolemDamagesGuard(EntityDamageByEntityEvent event) {
        if (!isRoyalGuard(event.getEntity()) || !isIronGolemNeutralityEnabled()) {
            return;
        }

        LivingEntity attacker = resolveLivingAttacker(event);
        if (!(attacker instanceof IronGolem golem)) {
            return;
        }

        // Le coup reste sans dégâts même si le golem avait obtenu sa cible par NMS ou par un autre plugin.
        event.setCancelled(true);
        neutralizeGolemAggression(golem);

        if (!isIronGolemRetaliationEnabled() || !isTrackedGuard(event.getEntity())) {
            return;
        }

        UUID ownerId = ownerOf(event.getEntity());
        GuardSquad squad = ownerId == null ? null : squads.get(ownerId);
        if (squad != null && squad.active) {
            // Ce cas ne devrait survenir qu'en dernier recours : la tentative de frappe prouve
            // néanmoins une agression réelle, donc le duo contre-attaque sans exposer le garde aux dégâts.
            engageThreat(squad, golem, ThreatReason.GUARD_DAMAGED, false);
        }
    }

    /**
     * Les golems de neige choisissent eux aussi les Husks comme ennemis et peuvent provoquer une
     * guerre inutile dans une base. Ils restent donc neutres envers les gardes, tout en conservant
     * leurs cibles légitimes contre les véritables monstres.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSnowGolemTargetsGuard(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Snowman golem)
                || !isRoyalGuard(event.getTarget())
                || !isSnowGolemNeutralityEnabled()) {
            return;
        }

        event.setTarget(null);
        event.setCancelled(true);
        neutralizeGolemAggression(golem);
    }

    /**
     * Ferme également la voie des projectiles déjà lancés ou des cibles injectées sans événement.
     * Une boule de neige n'est jamais considérée comme une agression justifiant de tuer le golem.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSnowGolemDamagesGuard(EntityDamageByEntityEvent event) {
        if (!isRoyalGuard(event.getEntity()) || !isSnowGolemNeutralityEnabled()) {
            return;
        }

        LivingEntity attacker = resolveLivingAttacker(event);
        if (!(attacker instanceof Snowman golem)) {
            return;
        }

        event.setCancelled(true);
        neutralizeGolemAggression(golem);
    }

    /**
     * Un garde attaqué devient une source de protection pour tout le duo. Le listener MONITOR
     * ne réagit qu'aux dégâts réellement acceptés après les protections des autres plugins.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGuardDamaged(EntityDamageByEntityEvent event) {
        if (event.isCancelled() || !isTrackedGuard(event.getEntity())) {
            return;
        }

        UUID ownerId = ownerOf(event.getEntity());
        LivingEntity attacker = resolveLivingAttacker(event);
        if (ownerId == null || attacker == null || isFriendlyToOwner(attacker, ownerId)) {
            return;
        }

        GuardSquad squad = squads.get(ownerId);
        Player owner = squad == null ? null : getOnlineOwner(squad);
        if (squad == null || !squad.active || owner == null || !isInSameWorld(owner, attacker)) {
            return;
        }

        engageThreat(squad, attacker, ThreatReason.GUARD_DAMAGED, false);
    }

    /**
     * Le Husk ne doit jamais choisir une cible de lui-même. Lorsqu'une menace légitime existe,
     * toute tentative de changement ou d'oubli est redirigée vers cette menace au niveau de
     * l'événement, sans appeler setTarget depuis le listener et donc sans récursion d'événements.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onGuardTargets(EntityTargetLivingEntityEvent event) {
        if (!isRoyalGuard(event.getEntity())) {
            return;
        }

        UUID ownerId = ownerOf(event.getEntity());
        GuardSquad squad = ownerId == null ? null : squads.get(ownerId);
        if (squad == null || !squad.active || !isTrackedGuard(event.getEntity())) {
            rejectAutonomousGuardTarget(event);
            return;
        }

        Player owner = getOnlineOwner(squad);
        if (owner == null) {
            rejectAutonomousGuardTarget(event);
            return;
        }

        // Si la cible précédente vient de mourir, on bascule directement vers la prochaine
        // menace de la file au lieu de rendre le Husk passif jusqu'au cycle de maintenance suivant.
        selectCurrentThreat(squad, owner, null, false);
        LivingEntity threat = squad.threat;
        boolean validThreat = threat != null
                && isCurrentThreat(ownerId, threat)
                && isInSameWorld(event.getEntity(), threat);
        if (!validThreat) {
            rejectAutonomousGuardTarget(event);
            return;
        }

        LivingEntity requestedTarget = event.getTarget();
        if (requestedTarget == null
                || !requestedTarget.getUniqueId().equals(threat.getUniqueId())) {
            // On conserve l'état d'annulation décidé par un autre plugin. Si l'événement est actif,
            // Paper appliquera directement la menace à la place de la cible vanilla indésirable.
            event.setTarget(threat);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGuardDamages(EntityDamageByEntityEvent event) {
        if (event.isCancelled() || !isTrackedGuard(event.getDamager())) {
            return;
        }

        UUID ownerId = ownerOf(event.getDamager());
        GuardSquad squad = ownerId == null ? null : squads.get(ownerId);
        LivingEntity target = resolveLivingDamageTarget(event.getEntity());
        if (target == null
                || ownerId == null
                || !isCurrentThreat(ownerId, target)) {
            event.setCancelled(true);
            if (event.getDamager() instanceof Husk guard && squad != null && squad.active) {
                // Une animation d'attaque parasite ne doit pas effacer la vraie cible en cours.
                restoreCurrentThreatTarget(squad, guard);
            } else if (event.getDamager() instanceof Mob mob) {
                mob.setTarget(null);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGuardDeath(EntityDeathEvent event) {
        if (!hasRoyalGuardMarker(event.getEntity())) {
            return;
        }

        // Le marqueur de type suffit ici : même une identité persistante corrompue ne doit
        // jamais permettre de dupliquer l'équipement royal ou son expérience.
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (!isRoyalGuard(event.getEntity())) {
            return;
        }

        UUID ownerId = ownerOf(event.getEntity());
        Integer slot = slotOf(event.getEntity());
        if (ownerId == null || !isGuardSlot(slot)) {
            return;
        }

        GuardSquad squad = squads.get(ownerId);
        if (squad == null || !squad.active) {
            return;
        }
        UUID guardId = event.getEntity().getUniqueId();
        if (!guardId.equals(squad.guardIds.get(slot))) {
            return;
        }

        squad.guardIds.remove(slot);
        guardsById.remove(guardId);
        resetNavigationState(squad, slot);
        notifyOwner(squad, ChatColor.RED + "Un de tes gardes est tombé. Il reviendra dans "
                + getRespawnDelaySeconds() + " seconde(s).");
        scheduleRespawn(squad, slot);
    }

    @EventHandler(ignoreCancelled = true)
    public void onGuardFallDamage(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && isRoyalGuard(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Interdit toute modification réelle de bloc provoquée par un garde. Cette protection couvre
     * notamment les œufs de tortue, la terre labourée et une éventuelle destruction de porte,
     * sans empêcher le pathfinder d'ouvrir normalement une porte franchissable.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onGuardChangesBlock(EntityChangeBlockEvent event) {
        if (isGuardBlockDamagePreventionEnabled() && hasRoyalGuardMarker(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Certains blocs fragiles déclenchent d'abord EntityInteractEvent avant leur changement
     * effectif. On ferme cette seconde voie sans neutraliser les plaques de pression ni les portes.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onGuardInteractsWithFragileBlock(EntityInteractEvent event) {
        if (!isGuardBlockDamagePreventionEnabled()
                || !hasRoyalGuardMarker(event.getEntity())) {
            return;
        }

        Material material = event.getBlock().getType();
        if (material == Material.TURTLE_EGG || material == Material.FARMLAND) {
            event.setCancelled(true);
        }
    }

    /**
     * Les Husks commencent naturellement une conversion lorsqu'ils restent immergés. Annuler
     * seulement l'événement final laisse le compteur repartir ; on arrête aussi explicitement
     * l'état de noyade afin de conserver une identité de garde stable.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onGuardTransform(EntityTransformEvent event) {
        if (!hasRoyalGuardMarker(event.getEntity())) {
            return;
        }

        event.setCancelled(true);
        if (event.getEntity() instanceof Husk guard) {
            stopGuardDrowningConversion(guard);
        }
    }

    /**
     * Un Husk est classé comme ennemi par Minecraft et peut donc être l'unique raison du résultat
     * NOT_SAFE. Le sommeil n'est forcé que si tous les ennemis proches sont des gardes suivis et
     * inoffensifs pour ce joueur ; un vrai monstre ou un DENY explicite d'un autre plugin gagne.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        /*
         * Ne pas utiliser event.isCancelled() ici : pour compatibilité historique, Paper le
         * renvoie aussi à true quand useBed() vaut DEFAULT et que le résultat vanilla est NOT_SAFE.
         * useBed() permet au contraire de distinguer ce refus vanilla d'un DENY explicite posé
         * par un plugin de protection, qui doit toujours rester prioritaire.
         */
        if (!isSleepNearGuardsAllowed()
                || event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.NOT_SAFE
                || event.useBed() != Event.Result.DEFAULT
                || squads.isEmpty()) {
            return;
        }

        if (areHarmlessRoyalGuardsTheOnlyNearbyEnemies(event.getPlayer(), event.getBed())) {
            event.setUseBed(Event.Result.ALLOW);
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        GuardSquad squad = squads.get(event.getPlayer().getUniqueId());
        if (squad == null || !squad.active) {
            return;
        }
        if (isPeacefulWorld(event.getPlayer().getWorld())) {
            event.getPlayer().sendMessage(ChatColor.RED
                    + "Tes gardes royaux ont été renvoyés : les Husks ne persistent pas en difficulté Paisible.");
            dismissSquad(event.getPlayer().getUniqueId());
            return;
        }

        squad.owner = event.getPlayer();
        clearThreat(squad);
        for (int slot = 0; slot < GUARD_COUNT; slot++) {
            Husk guard = getLiveGuard(squad, slot);
            if (guard != null) {
                // Un changement de dimension doit aussi interrompre immédiatement l'ancienne
                // animation et l'ancien chemin de combat, même si aucun emplacement sûr n'est
                // disponible pendant ce tick.
                guard.setTarget(null);
                guard.setAggressive(false);
                stopGuardNavigation(guard);
                if (teleportNearOwner(guard, event.getPlayer(), slot)) {
                    resetNavigationState(squad, slot);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        dismissSquad(event.getPlayer().getUniqueId());
    }

    /**
     * Ici, je retire les gardes résiduels d'un ancien démarrage lorsqu'un chunk est chargé plus tard.
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (!hasRoyalGuardMarker(entity)) {
                continue;
            }
            if (!isRoyalGuard(entity) || !isTrackedGuard(entity)) {
                // Un marqueur incomplet ou un UUID non suivi indique un résidu/duplicata.
                guardsById.remove(entity.getUniqueId());
                removeEntityQuietly(entity);
                continue;
            }
            guardsById.put(entity.getUniqueId(), (Husk) entity);
        }
    }

    /**
     * Ici, je nettoie toutes les entités et tâches quand le plugin s'arrête.
     */
    public void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        HandlerList.unregisterAll(this);

        cancelTaskQuietly(followTask);
        cancelTaskQuietly(combatTask);
        followTask = null;
        combatTask = null;
        for (BukkitTask task : new ArrayList<>(pendingGolemDeaggroTasks.values())) {
            cancelTaskQuietly(task);
        }
        pendingGolemDeaggroTasks.clear();

        List<UUID> ownerIds = new ArrayList<>(squads.keySet());
        ownerIds.forEach(this::dismissSquad);
        removeOrphanedGuards();
        guardsById.clear();
        lastNavigationErrorLogAt.clear();
    }

    private void cancelTaskQuietly(BukkitTask task) {
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (RuntimeException ignored) {
            // Paper peut avoir déjà annulé la tâche pendant la désactivation du plugin.
        }
    }

    void followActiveSquads() {
        if (shuttingDown) {
            return;
        }

        for (GuardSquad squad : new ArrayList<>(squads.values())) {
            Player owner;
            try {
                owner = getOnlineOwner(squad);
            } catch (RuntimeException exception) {
                logNavigationFailure(squad, -1, exception);
                continue;
            }
            if (!squad.active || owner == null) {
                dismissSquad(squad.ownerId);
                continue;
            }
            if (isPeacefulWorld(owner.getWorld())) {
                owner.sendMessage(ChatColor.RED
                        + "Tes gardes royaux ont été renvoyés : les Husks ne persistent pas en difficulté Paisible.");
                dismissSquad(squad.ownerId);
                continue;
            }

            try {
                reconcileThreatState(squad, owner, true);
            } catch (RuntimeException exception) {
                logNavigationFailure(squad, -1, exception);
                clearThreatAndTargets(squad);
            }

            if (shouldRunAwarenessScan(squad)) {
                try {
                    // Un seul balayage local et sphérique couvre à la fois les golems résiduels
                    // et les mobs qui ont obtenu le maître comme cible sans événement exploitable.
                    scanNearbySafetyAndThreats(squad, owner);
                } catch (RuntimeException exception) {
                    logNavigationFailure(squad, -1, exception);
                }
            }

            for (int slot = 0; slot < GUARD_COUNT; slot++) {
                try {
                    Husk guard = getLiveGuard(squad, slot);
                    if (guard == null) {
                        scheduleRespawn(squad, slot);
                        continue;
                    }
                    followGuard(squad, guard, owner, slot);
                } catch (RuntimeException exception) {
                    // Une entité défectueuse ne doit jamais arrêter la tâche de suivi de tous les joueurs.
                    logNavigationFailure(squad, slot, exception);
                    resetNavigationState(squad, slot);
                    tryRecallAfterFailure(squad, owner, slot);
                }
            }
        }
    }

    /**
     * Confirme les cibles de combat quatre fois par seconde avec une seule tâche globale.
     * Cette stratégie est plus légère et plus simple à nettoyer qu'une tâche par joueur.
     */
    void maintainActiveThreats() {
        if (shuttingDown) {
            return;
        }

        for (GuardSquad squad : new ArrayList<>(squads.values())) {
            if (!squad.active || (squad.threatId == null && squad.threatCandidates.isEmpty())) {
                continue;
            }

            try {
                Player owner = getOnlineOwner(squad);
                if (owner == null) {
                    dismissSquad(squad.ownerId);
                    continue;
                }

                reconcileThreatState(squad, owner, true);
            } catch (RuntimeException exception) {
                logNavigationFailure(squad, -1, exception);
                // Une menace corrompue ne doit pas bloquer les futurs combats de l'escouade.
                clearThreatAndTargets(squad);
            }
        }
    }

    boolean hasActiveSquad(UUID ownerId) {
        GuardSquad squad = squads.get(ownerId);
        return squad != null && squad.active;
    }

    int activeGuardCount(UUID ownerId) {
        GuardSquad squad = squads.get(ownerId);
        if (squad == null || !squad.active) {
            return 0;
        }

        int count = 0;
        for (int slot = 0; slot < GUARD_COUNT; slot++) {
            if (getLiveGuard(squad, slot) != null) {
                count++;
            }
        }
        return count;
    }

    boolean isRoyalGuard(Entity entity) {
        return entity instanceof Husk
                && hasRoyalGuardMarker(entity)
                && ownerOf(entity) != null
                && isGuardSlot(slotOf(entity));
    }

    /**
     * Vérifie le marqueur brut sans supposer que l'entité est encore un Husk. Une transformation
     * partiellement appliquée par le moteur ou un plugin tiers doit rester nettoyable et ne jamais
     * pouvoir conserver l'équipement ou les protections d'un garde.
     */
    private boolean hasRoyalGuardMarker(Entity entity) {
        if (entity == null) {
            return false;
        }
        String type = entity.getPersistentDataContainer().get(Keys.royalGuardType(), PersistentDataType.STRING);
        return GUARD_TYPE.equals(type);
    }

    UUID ownerOf(Entity entity) {
        String rawOwner = entity.getPersistentDataContainer().get(Keys.royalGuardOwner(), PersistentDataType.STRING);
        if (rawOwner == null) {
            return null;
        }
        try {
            return UUID.fromString(rawOwner);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    Integer slotOf(Entity entity) {
        return entity.getPersistentDataContainer().get(Keys.royalGuardSlot(), PersistentDataType.INTEGER);
    }

    private void followGuard(GuardSquad squad, Husk guard, Player owner, int slot) {
        repairGuardMobState(guard);

        if (!isInSameWorld(guard, owner)) {
            if (teleportNearOwner(guard, owner, slot)) {
                resetNavigationState(squad, slot);
                restoreCurrentThreatTarget(squad, guard);
            }
            return;
        }

        restoreCurrentThreatTarget(squad, guard);

        Location guardLocation = guard.getLocation();
        Location ownerLocation = owner.getLocation();
        double distanceSquared = guardLocation.distanceSquared(ownerLocation);
        boolean fighting = squad.threat != null && isCurrentThreat(squad.ownerId, squad.threat);

        /*
         * En combat, la portée de rappel doit être plus large que la portée de formation.
         * Sinon un garde qui arrive enfin au contact d'une cible éloignée est téléporté près
         * du maître avant d'avoir pu l'achever.
         */
        double recallRadius = fighting
                ? getCombatRecallRadius()
                : getEffectiveFollowRadius();
        if (distanceSquared > recallRadius * recallRadius) {
            if (teleportNearOwner(guard, owner, slot)) {
                resetNavigationState(squad, slot);
                restoreCurrentThreatTarget(squad, guard);
            }
            return;
        }

        /*
         * Une superposition presque exacte reste interdite même pendant un combat : elle masque
         * toute la caméra et peut enfermer le propriétaire. Le rappel latéral conserve ensuite
         * la menace courante, sans substituer une route de formation à l'objectif d'attaque.
         */
        if (isSeverelyOverlappingOwner(ownerLocation, guardLocation)
                && teleportNearOwner(guard, owner, slot)) {
            resetNavigationState(squad, slot);
            if (fighting) {
                restoreCurrentThreatTarget(squad, guard);
            }
            return;
        }

        // Pendant le combat, l'objectif d'attaque du Husk pilote son déplacement. Une route de
        // formation ne doit surtout pas concurrencer la poursuite de la menace courante.
        if (fighting) {
            resetNavigationState(squad, slot);
            return;
        }

        Location formationTarget = getFormationTarget(owner, slot);
        double tolerance = getPositiveConfig(
                "garde.formation-tolerance", DEFAULT_FORMATION_TOLERANCE, MAX_FORMATION_TOLERANCE);
        boolean invadesPersonalSpace = isInsideOwnerPersonalSpace(ownerLocation, guardLocation);
        boolean blocksView = isInsideOwnerViewCorridor(ownerLocation, guardLocation);

        if (!invadesPersonalSpace
                && !blocksView
                && guardLocation.distanceSquared(formationTarget) <= tolerance * tolerance) {
            // Sans cet arrêt explicite, Paper peut continuer l'ancien chemin jusqu'à la position
            // précédente du joueur et le garde finit précisément devant sa caméra.
            stopGuardNavigation(guard);
            resetNavigationState(squad, slot);
            return;
        }

        /*
         * Lorsqu'il est devant la caméra, le garde sort d'abord latéralement du cône de vision.
         * Aller directement vers son point arrière pourrait tracer un chemin qui traverse le
         * joueur ; le point intermédiaire évite ce croisement visuel.
         */
        Location navigationTarget = invadesPersonalSpace || blocksView
                ? getViewClearanceTarget(owner, guardLocation, slot)
                : formationTarget;
        boolean pathFound = navigator.moveTo(guard, navigationTarget, FOLLOW_SPEED);
        if (pathFound) {
            squad.pathFailures.remove(slot);
            if (isStuck(squad, guard, slot) && teleportNearOwner(guard, owner, slot)) {
                resetNavigationState(squad, slot);
            }
            return;
        }

        int failures = squad.pathFailures.merge(slot, 1, Integer::sum);
        if (failures >= MAX_PATH_FAILURES && teleportNearOwner(guard, owner, slot)) {
            resetNavigationState(squad, slot);
        }
    }

    private boolean spawnGuard(GuardSquad squad, Player owner, int slot) {
        if (shuttingDown || !squad.active || !isGuardSlot(slot)
                || !owner.isOnline() || owner.isDead() || isPeacefulWorld(owner.getWorld())) {
            return false;
        }
        if (getLiveGuard(squad, slot) != null) {
            return true;
        }

        Husk guard = null;
        try {
            Location spawnLocation = locationResolver.find(owner, slot);
            if (spawnLocation == null || spawnLocation.getWorld() == null
                    || !spawnLocation.getWorld().equals(owner.getWorld())) {
                plugin.getLogger().warning("Aucun emplacement sûr n'est disponible pour le garde royal "
                        + slot + " de " + owner.getName() + ".");
                return false;
            }

            guard = guardFactory.spawn(owner, spawnLocation, squad.ownerId, slot, GuardSettings.from(plugin));
            if (!isValidSpawnedGuard(guard, squad.ownerId, slot, owner.getWorld())) {
                removeEntityQuietly(guard);
                plugin.getLogger().warning("Le garde royal " + slot + " de " + owner.getName()
                        + " a été créé dans un état invalide puis supprimé.");
                return false;
            }

            UUID guardId = guard.getUniqueId();
            UUID previousGuardId = squad.guardIds.put(slot, guardId);
            if (previousGuardId != null && !previousGuardId.equals(guardId)) {
                guardsById.remove(previousGuardId);
            }
            guardsById.put(guardId, guard);
            resetNavigationState(squad, slot);
            return true;
        } catch (RuntimeException exception) {
            // La fabrique par défaut nettoie déjà les créations partielles ; ce second filet
            // protège également les fabriques injectées et les futures évolutions du code.
            removeEntityQuietly(guard);
            plugin.getLogger().log(Level.WARNING,
                    "Impossible de créer le garde royal " + slot + " de " + owner.getName() + ".", exception);
            return false;
        }
    }

    private boolean isValidSpawnedGuard(Husk guard, UUID ownerId, int slot, World ownerWorld) {
        return guard != null
                && guard.isValid()
                && !guard.isDead()
                && ownerWorld.equals(guard.getWorld())
                && isRoyalGuard(guard)
                && ownerId.equals(ownerOf(guard))
                && Integer.valueOf(slot).equals(slotOf(guard));
    }

    private Husk createConfiguredGuard(Player owner, Location location, UUID ownerId, int slot,
                                       GuardSettings settings) {
        World world = location.getWorld();
        if (world == null || !world.equals(owner.getWorld())) {
            throw new IllegalArgumentException("Le monde d'apparition du garde ne correspond pas à celui du joueur.");
        }

        Husk guard = world.spawn(location, Husk.class);
        try {
            configureGuard(guard, ownerId, slot, settings);
            return guard;
        } catch (RuntimeException exception) {
            // Une exception d'attribut ou d'équipement ne doit jamais laisser un Husk partiellement configuré.
            removeEntityQuietly(guard);
            throw exception;
        }
    }

    private void configureGuard(Husk guard, UUID ownerId, int slot, GuardSettings settings) {
        guard.setCustomName(GUARD_NAME);
        guard.setCustomNameVisible(true);
        guard.setAdult();
        guard.setAgeLock(true);
        guard.setCanBreakDoors(false);
        // Empêche le compte à rebours de conversion aquatique de démarrer sur un nouveau garde.
        guard.stopDrowning();
        guard.setAI(true);
        guard.setAware(true);
        // Les gardes restent visibles mais ne poussent plus le maître et ne peuvent plus
        // l'enfermer physiquement dans un passage étroit.
        guard.setCollidable(false);
        guard.setAggressive(false);
        guard.setPersistent(true);
        guard.setRemoveWhenFarAway(false);
        guard.setCanPickupItems(false);
        guard.setTarget(null);
        // Le pathfinder doit traiter les portes ouvertes et fermées comme franchissables ;
        // sinon un garde peut rester de l'autre côté d'une maison tout en conservant sa cible.
        guard.getPathfinder().setCanOpenDoors(true);
        guard.getPathfinder().setCanPassDoors(true);
        guard.getPathfinder().setCanFloat(true);
        guard.getPersistentDataContainer().set(Keys.royalGuardType(), PersistentDataType.STRING, GUARD_TYPE);
        guard.getPersistentDataContainer().set(Keys.royalGuardOwner(), PersistentDataType.STRING, ownerId.toString());
        guard.getPersistentDataContainer().set(Keys.royalGuardSlot(), PersistentDataType.INTEGER, slot);

        AttributeInstance maxHealth = setRequiredAttribute(
                guard, Attribute.MAX_HEALTH, settings.maxHealth());
        setRequiredAttribute(guard, Attribute.ATTACK_DAMAGE, settings.attackDamage());
        setRequiredAttribute(guard, Attribute.MOVEMENT_SPEED, settings.movementSpeed());
        setRequiredAttribute(guard, Attribute.FOLLOW_RANGE, settings.followRange());
        setRequiredAttribute(guard, Attribute.KNOCKBACK_RESISTANCE, settings.knockbackResistance());
        suppressGuardReinforcements(guard);
        guard.setHealth(maxHealth.getBaseValue());

        EntityEquipment equipment = guard.getEquipment();
        if (equipment == null) {
            throw new IllegalStateException("Un Husk garde doit toujours disposer d'un équipement.");
        }
        equipment.setHelmet(createRoyalArmor(ROYAL_HELMET));
        equipment.setChestplate(createRoyalArmor(ROYAL_CHESTPLATE));
        equipment.setLeggings(createRoyalArmor(ROYAL_LEGGINGS));
        equipment.setBoots(createRoyalArmor(ROYAL_BOOTS));
        equipment.setItemInMainHand(createRoyalSword());
        equipment.setItemInOffHand(null);
        equipment.setHelmetDropChance(0.0F);
        equipment.setChestplateDropChance(0.0F);
        equipment.setLeggingsDropChance(0.0F);
        equipment.setBootsDropChance(0.0F);
        equipment.setItemInMainHandDropChance(0.0F);
        equipment.setItemInOffHandDropChance(0.0F);
    }

    static ItemStack createRoyalArmor(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.addEnchant(Enchantment.PROTECTION, ARMOR_PROTECTION_LEVEL, true);
            meta.addEnchant(Enchantment.UNBREAKING, ARMOR_UNBREAKING_LEVEL, true);
            meta.addEnchant(Enchantment.MENDING, ARMOR_MENDING_LEVEL, true);
            // L'équipement appartient au rôle du garde : il ne doit pas casser après un long combat.
            meta.setUnbreakable(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    static ItemStack createRoyalSword() {
        ItemStack sword = new ItemStack(ROYAL_SWORD);
        ItemMeta meta = sword.getItemMeta();
        if (meta != null) {
            meta.addEnchant(Enchantment.SHARPNESS, SWORD_SHARPNESS_LEVEL, true);
            meta.addEnchant(Enchantment.UNBREAKING, SWORD_UNBREAKING_LEVEL, true);
            meta.addEnchant(Enchantment.MENDING, SWORD_MENDING_LEVEL, true);
            // Même invariant que l'armure : aucune usure ne doit désarmer silencieusement un garde.
            meta.setUnbreakable(true);
            sword.setItemMeta(meta);
        }
        return sword;
    }

    private AttributeInstance setRequiredAttribute(Husk guard, Attribute attribute, double value) {
        AttributeInstance instance = guard.getAttribute(attribute);
        if (instance == null) {
            throw new IllegalStateException("Attribut obligatoire absent sur le garde royal : " + attribute);
        }
        instance.setBaseValue(value);
        return instance;
    }

    /**
     * Un Zombie/Husk peut posséder une valeur de base et des modificateurs de renfort générés
     * lors du spawn. Mettre seulement la base à zéro ne suffit donc pas toujours : l'attribut
     * dédié est intégralement neutralisé pour qu'un garde blessé ne crée jamais de zombies.
     */
    private void suppressGuardReinforcements(Husk guard) {
        AttributeInstance reinforcementChance = guard.getAttribute(Attribute.SPAWN_REINFORCEMENTS);
        if (reinforcementChance == null) {
            // Une implémentation sans cet attribut ne dispose pas du mécanisme à neutraliser.
            return;
        }

        if (Double.compare(reinforcementChance.getBaseValue(), NO_REINFORCEMENT_CHANCE) != 0) {
            reinforcementChance.setBaseValue(NO_REINFORCEMENT_CHANCE);
        }
        for (AttributeModifier modifier : new ArrayList<>(reinforcementChance.getModifiers())) {
            reinforcementChance.removeModifier(modifier);
        }
    }

    private void scheduleRespawn(GuardSquad squad, int slot) {
        if (shuttingDown || !plugin.isEnabled() || !squad.active || !isGuardSlot(slot)
                || squad.respawnTasks.containsKey(slot)) {
            return;
        }

        try {
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                squad.respawnTasks.remove(slot);
                if (shuttingDown || !squad.active || squads.get(squad.ownerId) != squad
                        || getLiveGuard(squad, slot) != null) {
                    return;
                }

                Player owner = getOnlineOwner(squad);
                if (owner == null) {
                    dismissSquad(squad.ownerId);
                    return;
                }
                if (spawnGuard(squad, owner, slot)) {
                    notifyOwner(squad, ChatColor.GREEN + "Ton garde royal est de retour.");
                } else {
                    // Le monde peut être momentanément dangereux ou le joueur encore mort :
                    // on reprogramme proprement au lieu d'abandonner définitivement le slot.
                    scheduleRespawn(squad, slot);
                }
            }, getRespawnDelayTicks());
            squad.respawnTasks.put(slot, task);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Impossible de programmer le retour du garde royal " + slot + " de " + squad.ownerId + ".",
                    exception);
        }
    }

    private void dismissSquad(UUID ownerId) {
        GuardSquad squad = squads.remove(ownerId);
        if (squad == null) {
            return;
        }

        squad.active = false;
        for (BukkitTask task : new ArrayList<>(squad.respawnTasks.values())) {
            try {
                task.cancel();
            } catch (RuntimeException ignored) {
                // La tâche peut déjà avoir été annulée par Paper pendant l'arrêt du plugin.
            }
        }
        squad.respawnTasks.clear();

        for (UUID guardId : new ArrayList<>(squad.guardIds.values())) {
            Entity entity = guardsById.remove(guardId);
            if (entity == null) {
                entity = Bukkit.getEntity(guardId);
            }
            removeEntityQuietly(entity);
        }
        squad.guardIds.clear();
        resetAllNavigationState(squad);
        clearThreat(squad);
        squad.owner = null;
        lastNavigationErrorLogAt.remove(ownerId);
    }

    private void engageThreat(GuardSquad squad,
                              LivingEntity attacker,
                              ThreatReason reason,
                              boolean preferImmediately) {
        Player owner = getOnlineOwner(squad);
        if (!squad.active || owner == null
                || !registerThreatCandidate(squad, owner, attacker, reason)) {
            return;
        }

        selectCurrentThreat(squad, owner, attacker.getUniqueId(), preferImmediately);
        applyThreatToGuards(squad, owner);
    }

    /**
     * Mémorise plusieurs dangers au lieu d'écraser la cible précédente. La file est bornée pour
     * qu'un joueur frappant une grande quantité d'entités ne puisse jamais créer une fuite mémoire.
     */
    private boolean registerThreatCandidate(GuardSquad squad,
                                            Player owner,
                                            LivingEntity entity,
                                            ThreatReason reason) {
        if (reason == null
                || !isEligibleThreatBase(owner, entity)
                || !isInsideInitialEngagementRadius(owner, entity)) {
            return false;
        }

        long now = System.nanoTime();
        UUID entityId = entity.getUniqueId();
        ThreatCandidate candidate = squad.threatCandidates.get(entityId);
        if (candidate == null) {
            candidate = new ThreatCandidate(entityId, entity, reason, now, computeThreatExpiry(now));
            squad.threatCandidates.put(entityId, candidate);
        } else {
            candidate.entity = entity;
            if (reason.priority() > candidate.reason.priority()) {
                candidate.reason = reason;
            }
            candidate.lastObservedAtNanos = now;
            candidate.expiresAtNanos = computeThreatExpiry(now);
        }

        trimThreatCandidates(squad, entityId);
        return true;
    }

    private long computeThreatExpiry(long now) {
        long duration = getThreatDurationNanos();
        long deadline = now + duration;
        // System.nanoTime() est signé ; cette garde évite qu'un dépassement arithmétique
        // transforme une nouvelle menace en cible immédiatement expirée.
        return duration > 0L && deadline < now ? Long.MAX_VALUE : deadline;
    }

    private void trimThreatCandidates(GuardSquad squad, UUID protectedCandidateId) {
        while (squad.threatCandidates.size() > MAX_TRACKED_THREATS) {
            ThreatCandidate victim = null;
            for (ThreatCandidate candidate : squad.threatCandidates.values()) {
                if (candidate.entityId.equals(squad.threatId)
                        || candidate.entityId.equals(protectedCandidateId)) {
                    continue;
                }
                if (victim == null
                        || candidate.reason.priority() < victim.reason.priority()
                        || (candidate.reason.priority() == victim.reason.priority()
                        && candidate.lastObservedAtNanos < victim.lastObservedAtNanos)) {
                    victim = candidate;
                }
            }

            if (victim == null) {
                return;
            }
            squad.threatCandidates.remove(victim.entityId);
        }
    }

    /**
     * Nettoie les menaces mortes/hors zone puis sélectionne la meilleure cible restante.
     * Une priorité identique ne remplace pas la cible active : cette stabilité évite les
     * oscillations d'IA lorsqu'un groupe entier cible le propriétaire au même moment.
     */
    private void reconcileThreatState(GuardSquad squad, Player owner, boolean applyTargets) {
        UUID previousThreatId = squad.threatId;
        selectCurrentThreat(squad, owner, null, false);

        if (squad.threatId == null) {
            if (previousThreatId != null) {
                clearGuardTargets(squad);
            }
            return;
        }

        if (applyTargets) {
            applyThreatToGuards(squad, owner);
        }
    }

    private void selectCurrentThreat(GuardSquad squad,
                                     Player owner,
                                     UUID preferredCandidateId,
                                     boolean preferOnEqualPriority) {
        pruneThreatCandidates(squad, owner);

        ThreatCandidate current = squad.threatId == null
                ? null
                : squad.threatCandidates.get(squad.threatId);
        ThreatCandidate best = findBestThreatCandidate(squad, owner);
        ThreatCandidate selected = current;

        if (selected == null
                || (best != null && best.reason.priority() > selected.reason.priority())) {
            selected = best;
        }

        ThreatCandidate preferred = preferredCandidateId == null
                ? null
                : squad.threatCandidates.get(preferredCandidateId);
        if (preferred != null && (selected == null
                || preferred.reason.priority() > selected.reason.priority()
                || (preferOnEqualPriority
                && preferred.reason.priority() == selected.reason.priority()))) {
            selected = preferred;
        }

        if (selected == null) {
            clearActiveThreat(squad);
        } else {
            activateThreat(squad, selected);
        }
    }

    private ThreatCandidate findBestThreatCandidate(GuardSquad squad, Player owner) {
        ThreatCandidate best = null;
        for (ThreatCandidate candidate : squad.threatCandidates.values()) {
            if (best == null || isBetterThreatCandidate(candidate, best, owner)) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean isBetterThreatCandidate(ThreatCandidate candidate,
                                            ThreatCandidate currentBest,
                                            Player owner) {
        int priorityComparison = Integer.compare(
                candidate.reason.priority(), currentBest.reason.priority());
        if (priorityComparison != 0) {
            return priorityComparison > 0;
        }

        double candidateDistance = owner.getLocation().distanceSquared(candidate.entity.getLocation());
        double currentDistance = owner.getLocation().distanceSquared(currentBest.entity.getLocation());
        int distanceComparison = Double.compare(candidateDistance, currentDistance);
        if (distanceComparison != 0) {
            return distanceComparison < 0;
        }
        return candidate.lastObservedAtNanos > currentBest.lastObservedAtNanos;
    }

    private void pruneThreatCandidates(GuardSquad squad, Player owner) {
        Iterator<Map.Entry<UUID, ThreatCandidate>> iterator =
                squad.threatCandidates.entrySet().iterator();
        while (iterator.hasNext()) {
            ThreatCandidate candidate = iterator.next().getValue();
            try {
                if (!isThreatCandidateRelevant(owner, candidate)) {
                    iterator.remove();
                }
            } catch (RuntimeException exception) {
                /*
                 * Une référence d'entité devenue incohérente (déchargement ou plugin tiers)
                 * invalide seulement cette entrée. Vider toute la file ferait oublier des
                 * adversaires parfaitement valides et interromprait inutilement la défense.
                 */
                iterator.remove();
                logNavigationFailure(squad, -1, exception);
            }
        }
    }

    private boolean isThreatCandidateRelevant(Player owner, ThreatCandidate candidate) {
        if (candidate == null
                || candidate.entity == null
                || !candidate.entityId.equals(candidate.entity.getUniqueId())
                || !isEligibleThreatBase(owner, candidate.entity)) {
            return false;
        }

        double leashRadius = getCombatLeashRadius();
        if (owner.getLocation().distanceSquared(candidate.entity.getLocation())
                > leashRadius * leashRadius) {
            return false;
        }

        // Avec finish-engaged-targets, la cible reste prioritaire jusqu'à sa mort, son
        // invalidation, son changement de monde ou sa sortie de la laisse de sécurité.
        return isFinishEngagedTargetsEnabled()
                || System.nanoTime() - candidate.expiresAtNanos < 0L;
    }

    private boolean isEligibleThreatBase(Player owner, LivingEntity target) {
        if (owner == null
                || target == null
                || target instanceof ArmorStand
                || target.isDead()
                || !target.isValid()
                || target.isInvulnerable()
                || isFriendlyToOwner(target, owner.getUniqueId())
                || !isInSameWorld(owner, target)) {
            return false;
        }

        return !(target instanceof Player targetPlayer)
                || (targetPlayer.getGameMode() != GameMode.CREATIVE
                && targetPlayer.getGameMode() != GameMode.SPECTATOR);
    }

    private boolean isInsideInitialEngagementRadius(Player owner,
                                                    LivingEntity target) {
        /*
         * Les événements de ciblage et de dégâts ne nécessitent aucun balayage du monde : ils
         * peuvent donc engager une menace dans toute la laisse de combat. threat-detection-radius
         * borne seulement le filet de sécurité périodique qui inspecte les entités voisines.
         */
        double initialRadius = getCombatLeashRadius();
        return owner.getLocation().distanceSquared(target.getLocation())
                <= initialRadius * initialRadius;
    }

    private void activateThreat(GuardSquad squad, ThreatCandidate candidate) {
        squad.threatId = candidate.entityId;
        squad.threat = candidate.entity;
    }

    private void clearActiveThreat(GuardSquad squad) {
        squad.threatId = null;
        squad.threat = null;
    }

    private void applyThreatToGuards(GuardSquad squad, Player owner) {
        for (int slot = 0; slot < GUARD_COUNT; slot++) {
            try {
                Husk guard = getLiveGuard(squad, slot);
                if (guard == null) {
                    continue;
                }
                if (!isInSameWorld(guard, owner) && teleportNearOwner(guard, owner, slot)) {
                    resetNavigationState(squad, slot);
                }
                restoreCurrentThreatTarget(squad, guard);
            } catch (RuntimeException exception) {
                // Un garde défectueux ne doit pas empêcher son partenaire de prendre l'agresseur.
                logNavigationFailure(squad, slot, exception);
                resetNavigationState(squad, slot);
            }
        }
    }

    private void rejectAutonomousGuardTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() == null) {
            return;
        }
        event.setTarget(null);
        event.setCancelled(true);
    }

    private void neutralizeGolemAggression(Mob golem) {
        clearGolemRoyalGuardTarget(golem);
        scheduleGolemDeaggroVerification(golem);
    }

    /**
     * Efface uniquement une cible royale : une cible légitime différente (raider, zombie, etc.)
     * n'est jamais supprimée par erreur.
     */
    private void clearGolemRoyalGuardTarget(Mob golem) {
        if (golem == null || !golem.isValid() || golem.isDead()) {
            return;
        }

        LivingEntity currentTarget = golem.getTarget();
        if (currentTarget == null || !isRoyalGuard(currentTarget)) {
            return;
        }

        golem.setTarget(null);
        // Cet état contrôle notamment l'animation d'attaque. L'IA pourra le réactiver
        // normalement dès qu'elle choisira une autre cible légitime.
        golem.setAggressive(false);
    }

    /**
     * Au moment de EntityTargetLivingEntityEvent, la nouvelle cible n'est pas toujours encore
     * visible via getTarget(). La vérification au tick suivant ferme précisément cette fenêtre.
     */
    private void scheduleGolemDeaggroVerification(Mob golem) {
        if (shuttingDown || !plugin.isEnabled() || golem == null) {
            return;
        }

        UUID golemId = golem.getUniqueId();
        if (pendingGolemDeaggroTasks.containsKey(golemId)) {
            return;
        }

        try {
            BukkitTask task = Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (!shuttingDown && isNeutralityEnabledForGolem(golem)) {
                        clearGolemRoyalGuardTarget(golem);
                    }
                } finally {
                    pendingGolemDeaggroTasks.remove(golemId);
                }
            });
            pendingGolemDeaggroTasks.put(golemId, task);
        } catch (RuntimeException exception) {
            pendingGolemDeaggroTasks.remove(golemId);
            plugin.getLogger().log(Level.WARNING,
                    "Impossible de confirmer la neutralisation du golem " + golemId + ".",
                    exception);
        }
    }

    private boolean isNeutralityEnabledForGolem(Mob golem) {
        if (golem instanceof IronGolem) {
            return isIronGolemNeutralityEnabled();
        }
        if (golem instanceof Snowman) {
            return isSnowGolemNeutralityEnabled();
        }
        return false;
    }

    private boolean shouldRunAwarenessScan(GuardSquad squad) {
        if (squad.followCyclesUntilAwarenessScan <= 0) {
            squad.followCyclesUntilAwarenessScan = AWARENESS_SCAN_INTERVAL_FOLLOW_CYCLES - 1;
            return true;
        }

        squad.followCyclesUntilAwarenessScan--;
        return false;
    }

    /**
     * Rattrape une fois par seconde les cibles posées directement par NMS ou par un autre plugin.
     * Le même parcours local traite les golems et la défense proactive afin d'éviter deux scans
     * coûteux du voisinage pour chaque propriétaire.
     */
    private void scanNearbySafetyAndThreats(GuardSquad squad, Player owner) {
        boolean protectFromIronGolems = isIronGolemNeutralityEnabled();
        boolean protectFromSnowGolems = isSnowGolemNeutralityEnabled();
        boolean protectFromGolems = protectFromIronGolems || protectFromSnowGolems;
        boolean detectThreats = isProactiveDefenseEnabled();
        if (!protectFromGolems && !detectThreats) {
            return;
        }

        double golemRadius = protectFromGolems
                ? getPositiveConfig(
                "garde.iron-golem-neutrality-radius",
                DEFAULT_IRON_GOLEM_NEUTRALITY_RADIUS,
                MAX_IRON_GOLEM_NEUTRALITY_RADIUS)
                : 0.0D;
        double threatRadius = detectThreats ? getThreatDetectionRadius() : 0.0D;
        double scanRadius = Math.max(golemRadius, threatRadius);
        if (scanRadius <= 0.0D) {
            return;
        }

        List<Entity> nearbyEntities = owner.getNearbyEntities(scanRadius, scanRadius, scanRadius);
        if (nearbyEntities == null || nearbyEntities.isEmpty()) {
            return;
        }

        Location ownerLocation = owner.getLocation();
        boolean registeredThreat = false;
        for (Entity nearbyEntity : nearbyEntities) {
            try {
                if (nearbyEntity == null
                        || !isInSameWorld(owner, nearbyEntity)
                        || nearbyEntity.getUniqueId().equals(owner.getUniqueId())) {
                    continue;
                }

                double distanceSquared = ownerLocation.distanceSquared(nearbyEntity.getLocation());
                boolean neutralizedGolemGuardTarget = false;
                if (protectFromGolems
                        && distanceSquared <= golemRadius * golemRadius
                        && nearbyEntity instanceof Mob golem
                        && isNeutralityEnabledForGolem(golem)) {
                    LivingEntity target = golem.getTarget();
                    if (target != null && isRoyalGuard(target)) {
                        neutralizeGolemAggression(golem);
                        neutralizedGolemGuardTarget = true;
                    }
                }

                if (neutralizedGolemGuardTarget
                        || !detectThreats
                        || distanceSquared > threatRadius * threatRadius
                        || !(nearbyEntity instanceof Mob mob)) {
                    continue;
                }

                LivingEntity mobTarget = mob.getTarget();
                ThreatReason observedReason = null;
                if (mobTarget != null
                        && mobTarget.getUniqueId().equals(owner.getUniqueId())) {
                    observedReason = ThreatReason.TARGETING_OWNER;
                } else if (mobTarget != null
                        && isTrackedGuard(mobTarget)
                        && squad.ownerId.equals(ownerOf(mobTarget))) {
                    observedReason = ThreatReason.TARGETING_GUARD;
                }

                if (observedReason != null
                        && registerThreatCandidate(squad, owner, mob, observedReason)) {
                    registeredThreat = true;
                }
            } catch (RuntimeException exception) {
                /*
                 * Une entité déchargée ou modifiée pendant l'itération ne doit pas empêcher
                 * l'analyse des autres mobs du voisinage. Le journal reste limité par escouade.
                 */
                logNavigationFailure(squad, -1, exception);
            }
        }

        if (registeredThreat) {
            selectCurrentThreat(squad, owner, null, false);
            applyThreatToGuards(squad, owner);
        }
    }

    private double getThreatDetectionRadius() {
        double protectionRadius = getPositiveConfig(
                "garde.protection-radius", DEFAULT_PROTECTION_RADIUS, MAX_PROTECTION_RADIUS);
        double configuredRadius = getPositiveConfig(
                "garde.threat-detection-radius",
                DEFAULT_THREAT_DETECTION_RADIUS,
                MAX_THREAT_DETECTION_RADIUS);
        // La détection proactive reste volontairement locale. Une cible déjà engagée peut ensuite
        // être poursuivie plus loin, jusqu'à combat-leash-radius.
        return Math.min(configuredRadius, protectionRadius);
    }

    private double getCombatLeashRadius() {
        double protectionRadius = getPositiveConfig(
                "garde.protection-radius", DEFAULT_PROTECTION_RADIUS, MAX_PROTECTION_RADIUS);
        double configuredRadius = getPositiveConfig(
                "garde.combat-leash-radius",
                DEFAULT_COMBAT_LEASH_RADIUS,
                MAX_COMBAT_LEASH_RADIUS);
        // Une configuration incohérente ne doit jamais accepter une menace puis l'abandonner
        // immédiatement : la laisse effective couvre au minimum la zone de protection.
        return Math.max(configuredRadius, protectionRadius);
    }

    private double getCombatRecallRadius() {
        /*
         * Le garde peut légitimement dépasser légèrement la distance maître-cible au moment de
         * contourner ou de frapper l'ennemi. Cette marge évite une boucle rappel/poursuite au bord
         * exact de la laisse sans autoriser la cible elle-même à s'en éloigner davantage.
         */
        return getCombatLeashRadius() + COMBAT_RECALL_MARGIN;
    }

    private void clearThreatAndTargets(GuardSquad squad) {
        clearThreat(squad);
        clearGuardTargets(squad);
    }

    private void clearGuardTargets(GuardSquad squad) {
        for (int slot = 0; slot < GUARD_COUNT; slot++) {
            try {
                Husk guard = getLiveGuard(squad, slot);
                if (guard != null) {
                    // L'appel explicite couvre aussi une cible conservée côté moteur mais non reflétée
                    // momentanément par getTarget() pendant un changement de monde ou un déchargement.
                    guard.setTarget(null);
                    guard.setAggressive(false);
                    stopGuardNavigation(guard);
                }
            } catch (RuntimeException exception) {
                // Un garde momentanément déchargé ne doit pas empêcher le second d'être nettoyé.
                logNavigationFailure(squad, slot, exception);
            }
        }
    }

    private void clearThreat(GuardSquad squad) {
        squad.threatCandidates.clear();
        clearActiveThreat(squad);
    }

    private boolean isCurrentThreat(UUID ownerId, LivingEntity target) {
        GuardSquad squad = squads.get(ownerId);
        if (squad == null
                || !squad.active
                || squad.threatId == null
                || target == null
                || !squad.threatId.equals(target.getUniqueId())) {
            return false;
        }

        Player owner = getOnlineOwner(squad);
        ThreatCandidate candidate = squad.threatCandidates.get(squad.threatId);
        return owner != null && isThreatCandidateRelevant(owner, candidate);
    }


    private void restoreCurrentThreatTarget(GuardSquad squad, Husk guard) {
        LivingEntity currentTarget = guard.getTarget();
        LivingEntity threat = squad.threat;
        if (threat == null || !isCurrentThreat(squad.ownerId, threat) || !isInSameWorld(guard, threat)) {
            if (currentTarget != null) {
                guard.setTarget(null);
            }
            guard.setAggressive(false);
            return;
        }

        repairGuardMobState(guard);
        if (currentTarget == null || !currentTarget.getUniqueId().equals(threat.getUniqueId())) {
            // Un ancien chemin de formation peut sinon gagner quelques ticks sur l'objectif
            // d'attaque et faire repartir le garde vers le joueur au début du combat.
            stopGuardNavigation(guard);
            guard.setTarget(threat);
        }
        guard.setAggressive(true);
    }

    private LivingEntity resolveLivingDamageTarget(Entity damagedEntity) {
        if (damagedEntity instanceof LivingEntity livingEntity) {
            return livingEntity;
        }
        if (damagedEntity instanceof ComplexEntityPart part) {
            // Les dégâts infligés à une partie de dragon doivent commander le parent vivant,
            // seule entité que l'IA d'un Husk peut réellement conserver comme cible.
            return part.getParent();
        }
        return null;
    }

    private LivingEntity resolveLivingAttacker(EntityDamageByEntityEvent event) {
        // DamageSource restitue l'auteur final (par exemple le tireur d'une flèche ou d'une potion),
        // contrairement au damager direct qui peut n'être que le projectile.
        DamageSource damageSource = event.getDamageSource();
        if (damageSource != null && damageSource.getCausingEntity() instanceof LivingEntity livingEntity) {
            return livingEntity;
        }
        return resolveLivingAttacker(event.getDamager());
    }

    private LivingEntity resolveLivingAttacker(Entity damager) {
        if (damager instanceof LivingEntity livingEntity) {
            return livingEntity;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof LivingEntity livingEntity) {
                return livingEntity;
            }
        }
        return null;
    }

    private boolean isHostileTargetReason(EntityTargetEvent.TargetReason reason) {
        if (reason == null) {
            // Une raison absente venant d'une implémentation tierce reste traitée par prudence.
            return true;
        }
        return switch (reason) {
            // TEMPT est le seul ciblage positif explicitement non agressif : l'entité suit
            // simplement le joueur qui tient un objet désiré. FOLLOW_LEADER reste hostile,
            // car Paper l'utilise lorsqu'un raider reprend la cible de ses alliés.
            case TEMPT, FORGOT_TARGET, TARGET_DIED,
                    TARGET_INVALID, TARGET_OTHER_LEVEL -> false;
            default -> true;
        };
    }

    /**
     * Reproduit de manière conservatrice la zone de sécurité du lit : le résultat NOT_SAFE
     * n'est remplacé que lorsqu'au moins un garde valide est présent et qu'aucun autre Enemy
     * vivant ne se trouve dans la même boîte de détection.
     */
    private boolean areHarmlessRoyalGuardsTheOnlyNearbyEnemies(Player sleeper, Block bed) {
        if (sleeper == null || bed == null) {
            return false;
        }

        Collection<Entity> nearbyEntities;
        try {
            Location bedCenter = bed.getLocation().add(0.5D, 0.5D, 0.5D);
            nearbyEntities = bed.getWorld().getNearbyEntities(
                    bedCenter,
                    SLEEP_SAFETY_HORIZONTAL_RADIUS,
                    SLEEP_SAFETY_VERTICAL_RADIUS,
                    SLEEP_SAFETY_HORIZONTAL_RADIUS);
        } catch (RuntimeException ignored) {
            // En cas d'état de chunk incohérent, conserver le refus vanilla est le choix sûr.
            return false;
        }

        if (nearbyEntities == null || nearbyEntities.isEmpty()) {
            return false;
        }

        boolean foundHarmlessGuard = false;
        for (Entity entity : nearbyEntities) {
            try {
                if (!(entity instanceof Enemy) || entity.isDead() || !entity.isValid()) {
                    continue;
                }
                if (!isHarmlessTrackedGuardForSleeper(entity, sleeper)) {
                    return false;
                }
                foundHarmlessGuard = true;
            } catch (RuntimeException ignored) {
                // Une entité impossible à vérifier ne doit jamais contourner la sécurité du lit.
                return false;
            }
        }
        return foundHarmlessGuard;
    }

    private boolean isHarmlessTrackedGuardForSleeper(Entity entity, Player sleeper) {
        if (!isTrackedGuard(entity)) {
            return false;
        }

        UUID guardOwnerId = ownerOf(entity);
        UUID sleeperId = sleeper.getUniqueId();
        if (guardOwnerId == null) {
            return false;
        }
        if (guardOwnerId.equals(sleeperId)) {
            // Le propriétaire est toujours exclu des menaces de son propre duo.
            return true;
        }

        if (entity instanceof Mob guardMob) {
            LivingEntity currentTarget = guardMob.getTarget();
            if (currentTarget != null && sleeperId.equals(currentTarget.getUniqueId())) {
                return false;
            }
        }

        GuardSquad squad = squads.get(guardOwnerId);
        if (squad == null || !squad.active) {
            return false;
        }

        ThreatCandidate queuedThreat = squad.threatCandidates.get(sleeperId);
        if (queuedThreat == null) {
            return true;
        }

        Player guardOwner = getOnlineOwner(squad);
        return guardOwner != null && !isThreatCandidateRelevant(guardOwner, queuedThreat);
    }

    private boolean isFriendlyToOwner(Entity entity, UUID ownerId) {
        if (entity == null || ownerId == null) {
            return false;
        }
        if (ownerId.equals(entity.getUniqueId()) || isGuardOfOwner(entity, ownerId)) {
            return true;
        }
        if (entity instanceof Tameable tameable) {
            AnimalTamer tamer = tameable.getOwner();
            return tamer != null && ownerId.equals(tamer.getUniqueId());
        }
        return false;
    }

    private boolean isGuardOfOwner(Entity entity, UUID ownerId) {
        return isRoyalGuard(entity) && ownerId.equals(ownerOf(entity));
    }

    private boolean isTrackedGuard(Entity entity) {
        if (!isRoyalGuard(entity)) {
            return false;
        }

        UUID ownerId = ownerOf(entity);
        Integer slot = slotOf(entity);
        GuardSquad squad = squads.get(ownerId);
        return squad != null && squad.active && entity.getUniqueId().equals(squad.guardIds.get(slot));
    }

    private boolean isGuardSlot(Integer slot) {
        return slot != null && slot >= 0 && slot < GUARD_COUNT;
    }

    private boolean isInSameWorld(Entity first, Entity second) {
        return Objects.equals(first.getWorld(), second.getWorld());
    }

    private Husk getLiveGuard(GuardSquad squad, int slot) {
        UUID guardId = squad.guardIds.get(slot);
        if (guardId == null) {
            return null;
        }

        Husk cachedGuard = guardsById.get(guardId);
        if (isExpectedLiveGuard(cachedGuard, squad.ownerId, slot)) {
            return cachedGuard;
        }
        guardsById.remove(guardId);

        Entity entity = Bukkit.getEntity(guardId);
        if (entity instanceof Husk guard && isExpectedLiveGuard(guard, squad.ownerId, slot)) {
            guardsById.put(guardId, guard);
            return guard;
        }
        if (entity != null && hasRoyalGuardMarker(entity)) {
            // L'UUID attendu existe mais son identité persistante est corrompue : on le retire
            // avant de laisser le mécanisme de respawn recréer un garde sain.
            removeEntityQuietly(entity);
        }
        return null;
    }

    private boolean isExpectedLiveGuard(Husk guard, UUID ownerId, int slot) {
        return guard != null
                && guard.isValid()
                && !guard.isDead()
                && isRoyalGuard(guard)
                && ownerId.equals(ownerOf(guard))
                && Integer.valueOf(slot).equals(slotOf(guard));
    }

    private Player getOnlineOwner(GuardSquad squad) {
        if (squad.owner != null && squad.owner.isOnline()) {
            return squad.owner;
        }

        Player owner = Bukkit.getPlayer(squad.ownerId);
        if (owner != null && owner.isOnline()) {
            squad.owner = owner;
            return owner;
        }
        return null;
    }

    /**
     * Répare les invariants indispensables au rôle de garde. Cette vérification légère protège
     * contre les états vanilla transitoires et contre un plugin tiers qui aurait modifié le mob.
     */
    private void repairGuardMobState(Husk guard) {
        if (!guard.hasAI()) {
            guard.setAI(true);
        }
        if (!guard.isAware()) {
            guard.setAware(true);
        }
        if (guard.isCollidable()) {
            guard.setCollidable(false);
        }
        if (!guard.isAdult()) {
            guard.setAdult();
        }
        if (!guard.getAgeLock()) {
            guard.setAgeLock(true);
        }
        if (guard.canBreakDoors()) {
            guard.setCanBreakDoors(false);
        }
        if (!guard.isPersistent()) {
            guard.setPersistent(true);
        }
        if (guard.getRemoveWhenFarAway()) {
            guard.setRemoveWhenFarAway(false);
        }
        if (guard.getCanPickupItems()) {
            guard.setCanPickupItems(false);
        }
        if (guard.isInsideVehicle()) {
            /*
             * Un bateau ou un wagonnet immobilise le pathfinder et peut faire croire au système
             * de suivi que le garde avance. Il quitte donc tout véhicule avant de reprendre sa place.
             */
            guard.leaveVehicle();
        }

        stopGuardDrowningConversion(guard);
        suppressGuardReinforcements(guard);
    }

    private void stopGuardDrowningConversion(Husk guard) {
        if (guard.isConverting()) {
            // Une durée négative arrête la conversion en cours sans remplacer l'entité.
            guard.setConversionTime(-1);
        }
        // Réinitialise aussi la phase préalable de noyade pour empêcher un nouveau compte à rebours.
        guard.stopDrowning();
    }

    private void stopGuardNavigation(Mob guard) {
        try {
            navigator.stop(guard);
        } catch (RuntimeException ignored) {
            // L'arrêt est une optimisation de confort. Le prochain ordre de déplacement ou
            // de combat reprendra la main même si un autre plugin a invalidé le pathfinder.
        }
    }

    /**
     * Retourne l'ancrage de formation du slot : un garde à gauche derrière le joueur,
     * l'autre à droite derrière lui. La formation suit le regard horizontal, jamais la
     * position exacte du joueur.
     */
    Location getFormationTarget(Player owner, int slot) {
        if (!isGuardSlot(slot)) {
            throw new IllegalArgumentException("Slot de garde invalide : " + slot);
        }

        Location ownerLocation = owner.getLocation().clone();
        Vector forward = horizontalDirection(ownerLocation);
        Vector right = new Vector(-forward.getZ(), 0.0D, forward.getX());
        FormationOffsets offsets = getFormationOffsets();
        double sideSign = slot == 0 ? 1.0D : -1.0D;
        ownerLocation.add(forward.clone().multiply(-offsets.rearDistance()));
        ownerLocation.add(right.multiply(offsets.sideDistance() * sideSign));
        ownerLocation.setPitch(0.0F);
        return ownerLocation;
    }

    private FormationOffsets getFormationOffsets() {
        double sideDistance = Math.max(
                getPositiveConfig(
                        "garde.formation-side-distance",
                        DEFAULT_FORMATION_SIDE_DISTANCE,
                        MAX_FORMATION_OFFSET),
                MIN_FORMATION_SIDE_DISTANCE);
        double rearDistance = getPositiveConfig(
                "garde.formation-rear-distance",
                DEFAULT_FORMATION_REAR_DISTANCE,
                MAX_FORMATION_OFFSET);
        double personalSpaceRadius = getPositiveConfig(
                "garde.personal-space-radius",
                DEFAULT_PERSONAL_SPACE_RADIUS,
                MAX_PERSONAL_SPACE_RADIUS);
        double minimumAnchorDistance = personalSpaceRadius + FORMATION_PERSONAL_SPACE_MARGIN;
        double configuredAnchorDistance = Math.hypot(sideDistance, rearDistance);
        if (configuredAnchorDistance < minimumAnchorDistance) {
            // Une combinaison de configuration incohérente ne doit jamais replacer les gardes
            // dans le joueur : on conserve l'angle demandé tout en éloignant l'ancrage.
            double scale = minimumAnchorDistance / configuredAnchorDistance;
            sideDistance *= scale;
            rearDistance *= scale;
        }
        return new FormationOffsets(sideDistance, rearDistance);
    }

    private double getEffectiveFollowRadius() {
        FormationOffsets offsets = getFormationOffsets();
        double formationDistance = Math.hypot(offsets.sideDistance(), offsets.rearDistance());
        double configuredRadius = getPositiveConfig(
                "garde.follow-radius", DEFAULT_FOLLOW_RADIUS, MAX_FOLLOW_RADIUS);
        /*
         * La recherche d'un bloc sûr peut décaler l'ancrage de quelques blocs. Relever
         * automatiquement la portée évite qu'une configuration contradictoire téléporte le garde
         * en boucle alors qu'il se trouve déjà sur le meilleur emplacement disponible.
         */
        double minimumRadius = formationDistance + SAFE_LOCATION_SEARCH_RADIUS + 1.0D;
        return Math.max(configuredRadius, minimumRadius);
    }

    /**
     * Construit un point de dégagement latéral. Le côté actuel du garde est conservé lorsqu'il
     * est déjà nettement engagé à gauche ou à droite afin qu'il ne traverse jamais le joueur
     * pour rejoindre trop tôt son slot définitif.
     */
    Location getViewClearanceTarget(Player owner, Location guardLocation, int slot) {
        if (!isGuardSlot(slot)) {
            throw new IllegalArgumentException("Slot de garde invalide : " + slot);
        }

        Location ownerLocation = owner.getLocation().clone();
        Vector forward = horizontalDirection(ownerLocation);
        Vector right = new Vector(-forward.getZ(), 0.0D, forward.getX());
        Vector horizontalOffset = guardLocation.toVector()
                .subtract(ownerLocation.toVector())
                .setY(0.0D);

        double currentLateralOffset = horizontalOffset.dot(right);
        double slotSide = slot == 0 ? 1.0D : -1.0D;
        double escapeSide = Math.abs(currentLateralOffset) >= CLEARANCE_SIDE_REUSE_THRESHOLD
                ? Math.copySign(1.0D, currentLateralOffset)
                : slotSide;

        double configuredSideDistance = getPositiveConfig(
                "garde.formation-side-distance",
                DEFAULT_FORMATION_SIDE_DISTANCE,
                MAX_FORMATION_OFFSET);
        double personalSpaceRadius = getPositiveConfig(
                "garde.personal-space-radius",
                DEFAULT_PERSONAL_SPACE_RADIUS,
                MAX_PERSONAL_SPACE_RADIUS);
        double clearanceSideDistance = Math.max(
                configuredSideDistance + CLEARANCE_SIDE_MARGIN,
                personalSpaceRadius + CLEARANCE_SIDE_MARGIN);

        double currentForwardOffset = horizontalOffset.dot(forward);
        double clearanceForwardOffset = Math.max(
                CLEARANCE_MIN_FORWARD_OFFSET,
                Math.min(currentForwardOffset, CLEARANCE_MAX_FORWARD_OFFSET));

        ownerLocation.add(right.multiply(clearanceSideDistance * escapeSide));
        ownerLocation.add(forward.multiply(clearanceForwardOffset));
        ownerLocation.setPitch(0.0F);
        return ownerLocation;
    }

    private boolean isSeverelyOverlappingOwner(Location ownerLocation, Location guardLocation) {
        if (!Objects.equals(ownerLocation.getWorld(), guardLocation.getWorld())
                || Math.abs(ownerLocation.getY() - guardLocation.getY()) > 2.5D) {
            return false;
        }
        return horizontalDistanceSquared(ownerLocation, guardLocation)
                < EMERGENCY_OVERLAP_RADIUS * EMERGENCY_OVERLAP_RADIUS;
    }

    private Vector horizontalDirection(Location location) {
        Vector direction = location.getDirection().setY(0.0D);
        if (direction.lengthSquared() < MIN_DIRECTION_LENGTH_SQUARED) {
            double yawRadians = Math.toRadians(location.getYaw());
            direction = new Vector(
                    -Math.sin(yawRadians),
                    0.0D,
                    Math.cos(yawRadians));
        }
        return direction.normalize();
    }

    private boolean isInsideOwnerPersonalSpace(Location ownerLocation, Location candidate) {
        if (!Objects.equals(ownerLocation.getWorld(), candidate.getWorld())
                || Math.abs(ownerLocation.getY() - candidate.getY()) > 3.0D) {
            return false;
        }

        double radius = getPositiveConfig(
                "garde.personal-space-radius",
                DEFAULT_PERSONAL_SPACE_RADIUS,
                MAX_PERSONAL_SPACE_RADIUS);
        return horizontalDistanceSquared(ownerLocation, candidate) < radius * radius;
    }

    private boolean isInsideOwnerViewCorridor(Location ownerLocation, Location candidate) {
        if (!Objects.equals(ownerLocation.getWorld(), candidate.getWorld())
                || Math.abs(ownerLocation.getY() - candidate.getY()) > 3.0D) {
            return false;
        }

        double distanceSquared = horizontalDistanceSquared(ownerLocation, candidate);
        double maximumDistance = getPositiveConfig(
                "garde.view-clearance-distance",
                DEFAULT_VIEW_CLEARANCE_DISTANCE,
                MAX_VIEW_CLEARANCE_DISTANCE);
        if (distanceSquared > maximumDistance * maximumDistance) {
            return false;
        }
        if (distanceSquared < MIN_DIRECTION_LENGTH_SQUARED) {
            return true;
        }

        Vector forward = horizontalDirection(ownerLocation);
        double offsetX = candidate.getX() - ownerLocation.getX();
        double offsetZ = candidate.getZ() - ownerLocation.getZ();
        double normalizedDot = (offsetX * forward.getX() + offsetZ * forward.getZ())
                / Math.sqrt(distanceSquared);
        return normalizedDot >= VIEW_CLEARANCE_COSINE;
    }

    private double horizontalDistanceSquared(Location first, Location second) {
        double deltaX = first.getX() - second.getX();
        double deltaZ = first.getZ() - second.getZ();
        return deltaX * deltaX + deltaZ * deltaZ;
    }

    private boolean isStuck(GuardSquad squad, Husk guard, int slot) {
        Location current = guard.getLocation();
        Location previous = squad.lastLocations.put(slot, current.clone());
        if (previous == null || !Objects.equals(previous.getWorld(), current.getWorld())
                || previous.distanceSquared(current) > 0.04D) {
            squad.stuckChecks.put(slot, 1);
            return false;
        }

        int checks = squad.stuckChecks.merge(slot, 1, Integer::sum);
        return checks >= MAX_PATH_FAILURES;
    }

    private boolean teleportNearOwner(Husk guard, Player owner, int slot) {
        Location destination = locationResolver.find(owner, slot);
        if (destination == null || destination.getWorld() == null
                || !destination.getWorld().equals(owner.getWorld())) {
            return false;
        }

        stopGuardNavigation(guard);
        boolean teleported = guard.teleport(destination);
        if (!teleported) {
            return false;
        }
        guard.setVelocity(new Vector());
        guard.setFallDistance(0.0F);
        return true;
    }

    private void resetNavigationState(GuardSquad squad, int slot) {
        squad.pathFailures.remove(slot);
        squad.lastLocations.remove(slot);
        squad.stuckChecks.remove(slot);
    }

    private void resetAllNavigationState(GuardSquad squad) {
        squad.pathFailures.clear();
        squad.lastLocations.clear();
        squad.stuckChecks.clear();
    }

    private void tryRecallAfterFailure(GuardSquad squad, Player owner, int slot) {
        try {
            Husk guard = getLiveGuard(squad, slot);
            if (guard != null && teleportNearOwner(guard, owner, slot)) {
                resetNavigationState(squad, slot);
            }
        } catch (RuntimeException ignored) {
            // Le prochain cycle retentera ; l'exception principale a déjà été journalisée.
        }
    }

    private void logNavigationFailure(GuardSquad squad, int slot, RuntimeException exception) {
        long now = System.currentTimeMillis();
        long previous = lastNavigationErrorLogAt.getOrDefault(squad.ownerId, 0L);
        if (now - previous < ERROR_LOG_COOLDOWN_MILLIS) {
            return;
        }
        lastNavigationErrorLogAt.put(squad.ownerId, now);
        plugin.getLogger().log(Level.WARNING,
                "Erreur de suivi du garde royal " + slot + " pour " + squad.ownerId
                        + " ; les autres escouades continuent normalement.",
                exception);
    }

    private void notifyOwner(GuardSquad squad, String message) {
        if (!plugin.getConfig().getBoolean("garde.notifications", DEFAULT_NOTIFICATIONS)) {
            return;
        }
        Player owner = getOnlineOwner(squad);
        if (owner != null) {
            owner.sendMessage(message);
        }
    }

    private void removeEntityQuietly(Entity entity) {
        if (entity == null) {
            return;
        }
        try {
            if (!entity.isDead()) {
                entity.remove();
            }
        } catch (RuntimeException ignored) {
            // Le nettoyage est best-effort : une entité déjà déchargée sera aussi filtrée à son prochain chargement.
        }
    }

    private Location findSafeLocationNear(Player owner, int slot) {
        Location ownerLocation = owner.getLocation();
        if (ownerLocation == null || ownerLocation.getWorld() == null || !isGuardSlot(slot)) {
            return null;
        }

        Location desired = getFormationTarget(owner, slot);
        for (int radius = 0; radius <= SAFE_LOCATION_SEARCH_RADIUS; radius++) {
            for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                    if (radius > 0
                            && Math.max(Math.abs(offsetX), Math.abs(offsetZ)) != radius) {
                        continue;
                    }

                    Location candidate = safeLocationAt(desired, offsetX, offsetZ);
                    if (candidate != null && isAcceptableRecallLocation(owner, slot, candidate)) {
                        return candidate;
                    }
                }
            }
        }

        // Dernier recours dans un environnement très encombré : on cherche autour du maître,
        // mais jamais dans son espace personnel ni dans le cône qui masque sa vue.
        int direction = slot == 0 ? 1 : -1;
        for (int radius = 2; radius <= SAFE_LOCATION_SEARCH_RADIUS; radius++) {
            for (int normalizedX = radius; normalizedX >= -radius; normalizedX--) {
                int offsetX = normalizedX * direction;
                for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                    if (Math.max(Math.abs(offsetX), Math.abs(offsetZ)) != radius) {
                        continue;
                    }

                    Location candidate = safeLocationAt(ownerLocation, offsetX, offsetZ);
                    if (candidate != null && isAcceptableRecallLocation(owner, slot, candidate)) {
                        return candidate;
                    }
                }
            }
        }

        // On préfère réessayer au prochain cycle plutôt que téléporter un garde dans le joueur,
        // devant sa caméra, dans un bloc dangereux ou hors de la bordure du monde.
        return null;
    }

    private boolean isAcceptableRecallLocation(Player owner, int slot, Location candidate) {
        Location ownerLocation = owner.getLocation();
        return !isInsideOwnerPersonalSpace(ownerLocation, candidate)
                && !isInsideOwnerViewCorridor(ownerLocation, candidate)
                && !isTooCloseToSiblingGuard(owner.getUniqueId(), slot, candidate);
    }

    private boolean isTooCloseToSiblingGuard(UUID ownerId, int slot, Location candidate) {
        GuardSquad squad = squads.get(ownerId);
        if (squad == null || !squad.active) {
            return false;
        }

        for (int otherSlot = 0; otherSlot < GUARD_COUNT; otherSlot++) {
            if (otherSlot == slot) {
                continue;
            }

            Husk sibling = getLiveGuard(squad, otherSlot);
            if (sibling == null) {
                continue;
            }

            Location siblingLocation = sibling.getLocation();
            if (Objects.equals(candidate.getWorld(), siblingLocation.getWorld())
                    && Math.abs(candidate.getY() - siblingLocation.getY()) <= 2.5D
                    && horizontalDistanceSquared(candidate, siblingLocation)
                    < MIN_GUARD_SEPARATION * MIN_GUARD_SEPARATION) {
                return true;
            }
        }
        return false;
    }

    private Location safeLocationAt(Location base, int offsetX, int offsetZ) {
        World world = base.getWorld();
        if (world == null) {
            return null;
        }

        int x = base.getBlockX() + offsetX;
        int z = base.getBlockZ() + offsetZ;
        int baseY = base.getBlockY();
        int minY = Math.max(world.getMinHeight() + 1, baseY - SAFE_LOCATION_VERTICAL_RANGE);
        int maxY = Math.min(world.getMaxHeight() - 2, baseY + SAFE_LOCATION_VERTICAL_RANGE);
        WorldBorder border = world.getWorldBorder();

        // La version précédente balayait du Y le plus bas vers le plus haut et pouvait choisir
        // une cavité souterraine. On privilégie désormais l'étage du joueur, puis le niveau le plus proche.
        for (int verticalOffset : SAFE_LOCATION_VERTICAL_OFFSETS) {
            int y = baseY + verticalOffset;
            if (y < minY || y > maxY) {
                continue;
            }

            Location candidate = new Location(
                    world, x + 0.5D, y, z + 0.5D, base.getYaw(), base.getPitch());
            if (border != null && !border.isInside(candidate)) {
                continue;
            }

            Block ground = world.getBlockAt(x, y - 1, z);
            Block feet = world.getBlockAt(x, y, z);
            Block head = world.getBlockAt(x, y + 1, z);
            if (isSafeGuardLocation(ground, feet, head)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isSafeGuardLocation(Block ground, Block feet, Block head) {
        return ground.getType().isSolid() && !isDangerousGuardBlock(ground.getType())
                && isSafeGuardBodyBlock(feet) && isSafeGuardBodyBlock(head);
    }

    private boolean isSafeGuardBodyBlock(Block block) {
        return block.isPassable() && !block.isLiquid() && !isDangerousGuardBlock(block.getType())
                && block.getType() != Material.NETHER_PORTAL
                && block.getType() != Material.END_PORTAL
                && block.getType() != Material.END_GATEWAY;
    }

    private boolean isDangerousGuardBlock(Material material) {
        return switch (material) {
            case CACTUS, FIRE, SOUL_FIRE, LAVA, MAGMA_BLOCK, CAMPFIRE, SOUL_CAMPFIRE,
                    SWEET_BERRY_BUSH, WITHER_ROSE, POWDER_SNOW, POINTED_DRIPSTONE -> true;
            default -> false;
        };
    }

    private boolean isPeacefulWorld(World world) {
        return world != null && world.getDifficulty() == Difficulty.PEACEFUL;
    }

    private void removeOrphanedGuards() {
        for (World world : Bukkit.getWorlds()) {
            /*
             * Le parcours porte uniquement sur les entités vivantes déjà chargées et ne charge
             * aucun chunk. Il inclut volontairement les non-Husks afin de supprimer un résidu
             * transformé qui aurait conservé le marqueur persistant malgré une interaction tierce.
             * La copie protège aussi l'itération pendant le retrait effectif des entités.
             */
            List<LivingEntity> loadedEntities;
            try {
                loadedEntities = new ArrayList<>(world.getLivingEntities());
            } catch (RuntimeException exception) {
                // Un monde tiers défectueux ne doit pas empêcher l'activation ou l'arrêt du plugin.
                plugin.getLogger().log(Level.WARNING,
                        "Impossible d'analyser les anciens gardes dans " + world.getName() + ".",
                        exception);
                continue;
            }

            for (LivingEntity entity : loadedEntities) {
                try {
                    if (hasRoyalGuardMarker(entity) && !isTrackedGuard(entity)) {
                        guardsById.remove(entity.getUniqueId());
                        removeEntityQuietly(entity);
                    }
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.FINE,
                            "Impossible de vérifier une ancienne entité de garde dans " + world.getName() + ".",
                            exception);
                }
            }
        }
    }

    private double getPositiveConfig(String path, double fallback, double maximum) {
        double value = plugin.getConfig().getDouble(path, fallback);
        return positiveBounded(value, fallback, maximum);
    }

    private int getPositiveIntConfig(String path, int fallback, int maximum) {
        int value = plugin.getConfig().getInt(path, fallback);
        return value > 0 && value <= maximum ? value : fallback;
    }

    private int getRespawnDelaySeconds() {
        return getPositiveIntConfig(
                "garde.respawn-delay-seconds", DEFAULT_RESPAWN_DELAY_SECONDS, MAX_RESPAWN_DELAY_SECONDS);
    }

    private long getRespawnDelayTicks() {
        return getRespawnDelaySeconds() * 20L;
    }

    private long getThreatDurationNanos() {
        int seconds = getPositiveIntConfig(
                "garde.threat-duration-seconds", DEFAULT_THREAT_DURATION_SECONDS, MAX_THREAT_DURATION_SECONDS);
        return TimeUnit.SECONDS.toNanos(seconds);
    }

    private boolean isFriendlyFireEnabled() {
        return plugin.getConfig().getBoolean("garde.friendly-fire", DEFAULT_FRIENDLY_FIRE);
    }

    private boolean isOwnerAttackAssistEnabled() {
        return plugin.getConfig().getBoolean(
                "garde.assist-owner-attacks", DEFAULT_ASSIST_OWNER_ATTACKS);
    }

    private boolean isProactiveDefenseEnabled() {
        return plugin.getConfig().getBoolean(
                "garde.proactive-defense", DEFAULT_PROACTIVE_DEFENSE);
    }

    private boolean isFinishEngagedTargetsEnabled() {
        return plugin.getConfig().getBoolean(
                "garde.finish-engaged-targets", DEFAULT_FINISH_ENGAGED_TARGETS);
    }

    private boolean isSleepNearGuardsAllowed() {
        return plugin.getConfig().getBoolean(
                "garde.allow-sleep-near-guards", DEFAULT_ALLOW_SLEEP_NEAR_GUARDS);
    }

    private boolean isGuardBlockDamagePreventionEnabled() {
        return plugin.getConfig().getBoolean(
                "garde.prevent-block-damage", DEFAULT_PREVENT_BLOCK_DAMAGE);
    }

    private boolean isIronGolemNeutralityEnabled() {
        return plugin.getConfig().getBoolean(
                "garde.iron-golem-neutrality", DEFAULT_IRON_GOLEM_NEUTRALITY);
    }

    private boolean isIronGolemRetaliationEnabled() {
        return plugin.getConfig().getBoolean(
                "garde.iron-golem-retaliation", DEFAULT_IRON_GOLEM_RETALIATION);
    }

    private boolean isSnowGolemNeutralityEnabled() {
        return plugin.getConfig().getBoolean(
                "garde.snow-golem-neutrality", DEFAULT_SNOW_GOLEM_NEUTRALITY);
    }

    /**
     * Classe les ordres de combat selon l'urgence pour éviter qu'une animation ou une cible
     * secondaire ne détourne les gardes d'un danger immédiat visant leur maître.
     */
    private enum ThreatReason {
        OWNER_DAMAGED(500),
        GUARD_DAMAGED(490),
        TARGETING_OWNER(480),
        TARGETING_GUARD(470),
        OWNER_ATTACKED_TARGET(400);

        private final int priority;

        ThreatReason(int priority) {
            this.priority = priority;
        }

        private int priority() {
            return priority;
        }
    }

    private enum GuardCommandAction {
        TOGGLE,
        SUMMON,
        DISMISS,
        STATUS,
        HELP,
        INVALID
    }

    @FunctionalInterface
    interface GuardNavigator {
        boolean moveTo(Mob guard, Location target, double speed);

        /**
         * Le comportement par défaut conserve la compatibilité avec les navigateurs de test
         * et les intégrations existantes qui n'ont besoin que de {@link #moveTo(Mob, Location, double)}.
         */
        default void stop(Mob guard) {
            // Aucun arrêt spécifique n'est requis pour un navigateur injecté qui ne le prend pas en charge.
        }
    }

    @FunctionalInterface
    interface GuardFactory {
        Husk spawn(Player owner, Location location, UUID ownerId, int slot, GuardSettings settings);
    }

    @FunctionalInterface
    interface GuardLocationResolver {
        Location find(Player owner, int slot);
    }

    private record FormationOffsets(double sideDistance, double rearDistance) {
    }

    record GuardSettings(double maxHealth,
                         double attackDamage,
                         double movementSpeed,
                         double followRange,
                         double knockbackResistance) {

        static GuardSettings from(JavaPlugin plugin) {
            double protectionRadius = positiveBounded(
                    plugin.getConfig().getDouble("garde.protection-radius", DEFAULT_PROTECTION_RADIUS),
                    DEFAULT_PROTECTION_RADIUS,
                    MAX_PROTECTION_RADIUS);
            double configuredCombatLeash = positiveBounded(
                    plugin.getConfig().getDouble("garde.combat-leash-radius", DEFAULT_COMBAT_LEASH_RADIUS),
                    DEFAULT_COMBAT_LEASH_RADIUS,
                    MAX_COMBAT_LEASH_RADIUS);
            double effectiveCombatLeash = Math.max(configuredCombatLeash, protectionRadius);
            double configuredFollowRange = positiveBounded(
                    plugin.getConfig().getDouble("garde.attributes.follow-range", DEFAULT_FOLLOW_RANGE),
                    DEFAULT_FOLLOW_RANGE,
                    MAX_CONFIGURED_FOLLOW_RANGE);

            return new GuardSettings(
                    positiveBounded(plugin.getConfig().getDouble("garde.attributes.max-health", DEFAULT_MAX_HEALTH),
                            DEFAULT_MAX_HEALTH, MAX_CONFIGURED_HEALTH),
                    positiveBounded(plugin.getConfig().getDouble("garde.attributes.attack-damage", DEFAULT_ATTACK_DAMAGE),
                            DEFAULT_ATTACK_DAMAGE, MAX_CONFIGURED_ATTACK_DAMAGE),
                    positiveBounded(plugin.getConfig().getDouble("garde.attributes.movement-speed", DEFAULT_MOVEMENT_SPEED),
                            DEFAULT_MOVEMENT_SPEED, MAX_CONFIGURED_MOVEMENT_SPEED),
                    // L'IA du Husk doit pouvoir suivre toute cible que le gestionnaire conserve
                    // dans sa laisse de combat, même si follow-range a été configuré trop bas.
                    Math.max(configuredFollowRange, effectiveCombatLeash),
                    nonNegativeBounded(plugin.getConfig().getDouble("garde.attributes.knockback-resistance", DEFAULT_KNOCKBACK_RESISTANCE),
                            DEFAULT_KNOCKBACK_RESISTANCE, MAX_CONFIGURED_KNOCKBACK_RESISTANCE)
            );
        }
    }

    private static double positiveBounded(double value, double fallback, double maximum) {
        return Double.isFinite(value) && value > 0.0D && value <= maximum ? value : fallback;
    }

    private static double nonNegativeBounded(double value, double fallback, double maximum) {
        return Double.isFinite(value) && value >= 0.0D && value <= maximum ? value : fallback;
    }

    /**
     * Référence bornée vers une menace observée. L'entité est conservée uniquement tant qu'elle
     * reste valide et dans la zone de combat ; aucun identifiant ne survit au renvoi du duo.
     */
    private static final class ThreatCandidate {
        private final UUID entityId;
        private LivingEntity entity;
        private ThreatReason reason;
        private long lastObservedAtNanos;
        private long expiresAtNanos;

        private ThreatCandidate(UUID entityId,
                                LivingEntity entity,
                                ThreatReason reason,
                                long lastObservedAtNanos,
                                long expiresAtNanos) {
            this.entityId = entityId;
            this.entity = entity;
            this.reason = reason;
            this.lastObservedAtNanos = lastObservedAtNanos;
            this.expiresAtNanos = expiresAtNanos;
        }
    }

    private static final class GuardSquad {
        private final UUID ownerId;
        private Player owner;
        private final Map<Integer, UUID> guardIds = new HashMap<>();
        private final Map<Integer, BukkitTask> respawnTasks = new HashMap<>();
        private final Map<Integer, Integer> pathFailures = new HashMap<>();
        private final Map<Integer, Location> lastLocations = new HashMap<>();
        private final Map<Integer, Integer> stuckChecks = new HashMap<>();
        private final Map<UUID, ThreatCandidate> threatCandidates = new LinkedHashMap<>();
        private boolean active = true;
        private UUID threatId;
        private LivingEntity threat;
        private int followCyclesUntilAwarenessScan;

        private GuardSquad(UUID ownerId, Player owner) {
            this.ownerId = ownerId;
            this.owner = owner;
        }
    }
}
