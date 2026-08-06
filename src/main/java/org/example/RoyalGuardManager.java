package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTransformEvent;
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
import java.util.HashMap;
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
    private static final long FOLLOW_PERIOD_TICKS = 20L;
    private static final long THREAT_TARGET_REFRESH_PERIOD_TICKS = 5L;
    private static final int MAX_PATH_FAILURES = 3;
    private static final double DEFAULT_FOLLOW_RADIUS = 20.0D;
    private static final double DEFAULT_COMFORT_DISTANCE = 3.0D;
    private static final double DEFAULT_PROTECTION_RADIUS = 32.0D;
    private static final int DEFAULT_THREAT_DURATION_SECONDS = 10;
    private static final int DEFAULT_RESPAWN_DELAY_SECONDS = 20;
    private static final boolean DEFAULT_FRIENDLY_FIRE = false;
    private static final boolean DEFAULT_IRON_GOLEM_NEUTRALITY = true;
    private static final boolean DEFAULT_NOTIFICATIONS = true;
    private static final double DEFAULT_MAX_HEALTH = 100.0D;
    private static final double DEFAULT_ATTACK_DAMAGE = 16.0D;
    private static final double DEFAULT_MOVEMENT_SPEED = 0.35D;
    private static final double DEFAULT_KNOCKBACK_RESISTANCE = 0.6D;
    private static final double FOLLOW_SPEED = 1.15D;
    private static final double MAX_FOLLOW_RADIUS = 128.0D;
    private static final double MAX_COMFORT_DISTANCE = 64.0D;
    private static final double MAX_PROTECTION_RADIUS = 128.0D;
    private static final int MAX_THREAT_DURATION_SECONDS = 300;
    private static final int MAX_RESPAWN_DELAY_SECONDS = 3_600;
    private static final double MAX_CONFIGURED_HEALTH = 1024.0D;
    private static final double MAX_CONFIGURED_ATTACK_DAMAGE = 2048.0D;
    private static final double MAX_CONFIGURED_MOVEMENT_SPEED = 1024.0D;
    private static final double MAX_CONFIGURED_KNOCKBACK_RESISTANCE = 1.0D;
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
    private BukkitTask followTask;
    private boolean shuttingDown;

    public RoyalGuardManager(JavaPlugin plugin) {
        this(plugin, (guard, target, speed) -> guard.getPathfinder().moveTo(target, speed), true, null, null);
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
            followTask = Bukkit.getScheduler().runTaskTimer(plugin, this::followActiveSquads,
                    FOLLOW_PERIOD_TICKS, FOLLOW_PERIOD_TICKS);
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
        engageThreat(squad, attacker);
    }

    /**
     * Empêche le propriétaire ou l'autre membre du duo de blesser accidentellement un garde.
     * Le traitement est effectué avant MONITOR afin que la défense automatique respecte bien
     * l'état final d'annulation de l'événement.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
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
        if (event.getEntity() instanceof Mob guard && guard.getTarget() != null
                && guard.getTarget().getUniqueId().equals(attacker.getUniqueId())) {
            guard.setTarget(null);
        }
    }

    /**
     * Les gardes sont des Husks : sans protection explicite, les golems de fer les considèrent
     * comme des monstres hostiles. Le ciblage est bloqué de façon événementielle, sans scan du monde.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIronGolemTargetsGuard(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof IronGolem golem)
                || !isTrackedGuard(event.getTarget())
                || !isIronGolemNeutralityEnabled()) {
            return;
        }

        event.setCancelled(true);
        neutralizeIronGolemAggression(golem, event.getTarget());
    }

    /**
     * Filet de sécurité contre les attaques forcées par un autre plugin ou une cible déjà acquise :
     * le coup du golem est annulé et son agressivité envers ce garde est supprimée.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIronGolemDamagesGuard(EntityDamageByEntityEvent event) {
        if (!isTrackedGuard(event.getEntity()) || !isIronGolemNeutralityEnabled()) {
            return;
        }

        LivingEntity attacker = resolveLivingAttacker(event);
        if (!(attacker instanceof IronGolem golem)) {
            return;
        }

        event.setCancelled(true);
        neutralizeIronGolemAggression(golem, event.getEntity());
    }

    /**
     * Un garde attaqué devient une source de protection pour tout le duo. Auparavant les Husks
     * recevaient les coups sans pouvoir se défendre, car leur ciblage vanilla était toujours annulé.
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

        engageThreat(squad, attacker);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGuardTargets(EntityTargetLivingEntityEvent event) {
        if (!isRoyalGuard(event.getEntity())) {
            return;
        }

        LivingEntity target = event.getTarget();
        if (target == null) {
            return;
        }

        UUID ownerId = ownerOf(event.getEntity());
        GuardSquad squad = ownerId == null ? null : squads.get(ownerId);
        boolean targetIsInAnotherWorld = !isInSameWorld(event.getEntity(), target);
        if (squad == null || !squad.active || targetIsInAnotherWorld || !isCurrentThreat(ownerId, target)) {
            if (targetIsInAnotherWorld && squad != null && squad.threatId != null
                    && squad.threatId.equals(target.getUniqueId())) {
                clearThreat(squad);
            }
            event.setCancelled(true);
            if (event.getEntity() instanceof Husk guard && squad != null && squad.active) {
                // Ne jamais remplacer une menace valide par « aucune cible » : c'était la cause
                // principale des gardes qui recevaient des coups puis restaient passifs.
                restoreCurrentThreatTarget(squad, guard);
            } else if (event.getEntity() instanceof Mob mob) {
                mob.setTarget(null);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGuardDamages(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (!isRoyalGuard(event.getDamager())) {
            return;
        }

        UUID ownerId = ownerOf(event.getDamager());
        GuardSquad squad = ownerId == null ? null : squads.get(ownerId);
        if (!(event.getEntity() instanceof LivingEntity target)
                || ownerId == null
                || !isCurrentThreat(ownerId, target)) {
            event.setCancelled(true);
            if (event.getDamager() instanceof Husk guard && squad != null && squad.active) {
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

    @EventHandler
    public void onGuardTransform(EntityTransformEvent event) {
        if (isRoyalGuard(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        GuardSquad squad = squads.get(event.getPlayer().getUniqueId());
        if (squad == null || !squad.active) {
            return;
        }

        squad.owner = event.getPlayer();
        clearThreat(squad);
        for (int slot = 0; slot < GUARD_COUNT; slot++) {
            Husk guard = getLiveGuard(squad, slot);
            if (guard != null) {
                guard.setTarget(null);
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

        if (followTask != null) {
            followTask.cancel();
            followTask = null;
        }

        List<UUID> ownerIds = new ArrayList<>(squads.keySet());
        ownerIds.forEach(this::dismissSquad);
        removeOrphanedGuards();
        guardsById.clear();
        lastNavigationErrorLogAt.clear();
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

            try {
                expireThreatIfNeeded(squad);
                clearThreatIfNoLongerRelevant(squad, owner);
            } catch (RuntimeException exception) {
                logNavigationFailure(squad, -1, exception);
                clearThreat(squad);
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
        return hasRoyalGuardMarker(entity) && ownerOf(entity) != null && isGuardSlot(slotOf(entity));
    }

    private boolean hasRoyalGuardMarker(Entity entity) {
        if (!(entity instanceof Husk)) {
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
        if (!isInSameWorld(guard, owner)) {
            if (teleportNearOwner(guard, owner, slot)) {
                resetNavigationState(squad, slot);
                restoreCurrentThreatTarget(squad, guard);
            }
            return;
        }

        restoreCurrentThreatTarget(squad, guard);

        double distanceSquared = guard.getLocation().distanceSquared(owner.getLocation());
        double followRadius = getPositiveConfig(
                "garde.follow-radius", DEFAULT_FOLLOW_RADIUS, MAX_FOLLOW_RADIUS);
        if (distanceSquared > followRadius * followRadius) {
            if (teleportNearOwner(guard, owner, slot)) {
                resetNavigationState(squad, slot);
                restoreCurrentThreatTarget(squad, guard);
            }
            return;
        }

        double comfortDistance = Math.min(followRadius, getPositiveConfig(
                "garde.comfort-distance", DEFAULT_COMFORT_DISTANCE, MAX_COMFORT_DISTANCE));
        if (guard.getTarget() != null || distanceSquared <= comfortDistance * comfortDistance) {
            resetNavigationState(squad, slot);
            return;
        }

        boolean pathFound = navigator.moveTo(guard, owner.getLocation(), FOLLOW_SPEED);
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
                || !owner.isOnline() || owner.isDead()) {
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
        guard.setAI(true);
        guard.setAware(true);
        guard.setPersistent(true);
        guard.setRemoveWhenFarAway(false);
        guard.setCanPickupItems(false);
        guard.setTarget(null);
        guard.getPersistentDataContainer().set(Keys.royalGuardType(), PersistentDataType.STRING, GUARD_TYPE);
        guard.getPersistentDataContainer().set(Keys.royalGuardOwner(), PersistentDataType.STRING, ownerId.toString());
        guard.getPersistentDataContainer().set(Keys.royalGuardSlot(), PersistentDataType.INTEGER, slot);

        AttributeInstance maxHealth = setRequiredAttribute(
                guard, Attribute.MAX_HEALTH, settings.maxHealth());
        setRequiredAttribute(guard, Attribute.ATTACK_DAMAGE, settings.attackDamage());
        setRequiredAttribute(guard, Attribute.MOVEMENT_SPEED, settings.movementSpeed());
        setRequiredAttribute(guard, Attribute.KNOCKBACK_RESISTANCE, settings.knockbackResistance());
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

    private void engageThreat(GuardSquad squad, LivingEntity attacker) {
        Player owner = getOnlineOwner(squad);
        if (!squad.active || owner == null || attacker.isDead() || !attacker.isValid()
                || isFriendlyToOwner(attacker, squad.ownerId) || !isInSameWorld(owner, attacker)) {
            return;
        }

        squad.threatId = attacker.getUniqueId();
        squad.threat = attacker;
        squad.threatExpiresAtNanos = System.nanoTime() + getThreatDurationNanos();
        applyThreatToGuards(squad, owner);
        ensureThreatTargetRefresh(squad);
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

    /**
     * Certaines IA vanilla peuvent réévaluer leur cible après le coup initial. Une seule tâche
     * légère par escouade en combat confirme donc la cible au tick suivant, puis toutes les cinq
     * ticks uniquement tant qu'une menace existe. Elle ne refait aucun setTarget si la cible tient.
     */
    private void ensureThreatTargetRefresh(GuardSquad squad) {
        if (shuttingDown || !plugin.isEnabled() || !squad.active || squad.targetRefreshTask != null) {
            return;
        }

        try {
            squad.targetRefreshTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    maintainThreatTargets(squad);
                } catch (RuntimeException exception) {
                    // Une entité momentanément déchargée ne doit ni casser le scheduler Bukkit,
                    // ni empêcher les futures confirmations de cible de cette escouade.
                    logNavigationFailure(squad, -1, exception);
                }
            }, 1L, THREAT_TARGET_REFRESH_PERIOD_TICKS);
        } catch (RuntimeException exception) {
            squad.targetRefreshTask = null;
            plugin.getLogger().log(Level.WARNING,
                    "Impossible de maintenir la cible de combat des gardes de " + squad.ownerId + ".",
                    exception);
        }
    }

    private void maintainThreatTargets(GuardSquad squad) {
        if (shuttingDown || !squad.active || squads.get(squad.ownerId) != squad) {
            cancelThreatTargetRefresh(squad);
            return;
        }

        Player owner = getOnlineOwner(squad);
        if (owner == null || squad.threatId == null || squad.threat == null) {
            clearThreatAndTargets(squad);
            return;
        }

        clearThreatIfNoLongerRelevant(squad, owner);
        if (squad.threat != null) {
            applyThreatToGuards(squad, owner);
        }
    }

    private void cancelThreatTargetRefresh(GuardSquad squad) {
        BukkitTask task = squad.targetRefreshTask;
        squad.targetRefreshTask = null;
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (RuntimeException ignored) {
            // La tâche peut déjà être terminée ou annulée pendant l'arrêt du serveur.
        }
    }

    private void neutralizeIronGolemAggression(IronGolem golem, Entity guardEntity) {
        LivingEntity currentTarget = golem.getTarget();
        if (currentTarget != null && currentTarget.getUniqueId().equals(guardEntity.getUniqueId())) {
            golem.setTarget(null);
        }
        // La neutralité est volontairement unidirectionnelle : le golem ne peut pas frapper
        // un garde, mais le duo conserve le droit de défendre son propriétaire contre ce golem.
    }

    private void expireThreatIfNeeded(GuardSquad squad) {
        if (squad.threatId == null || !isThreatExpired(squad)) {
            return;
        }
        clearThreatAndTargets(squad);
    }

    private void clearThreatIfNoLongerRelevant(GuardSquad squad, Player owner) {
        if (squad.threatId == null) {
            return;
        }

        LivingEntity threat = squad.threat;
        double protectionRadius = getPositiveConfig(
                "garde.protection-radius", DEFAULT_PROTECTION_RADIUS, MAX_PROTECTION_RADIUS);
        if (threat == null || !isCurrentThreat(squad.ownerId, threat) || !isInSameWorld(owner, threat)
                || owner.getLocation().distanceSquared(threat.getLocation())
                > protectionRadius * protectionRadius) {
            clearThreatAndTargets(squad);
        }
    }

    private void clearThreatAndTargets(GuardSquad squad) {
        clearThreat(squad);
        for (int slot = 0; slot < GUARD_COUNT; slot++) {
            Husk guard = getLiveGuard(squad, slot);
            if (guard != null) {
                guard.setTarget(null);
            }
        }
    }

    private void clearThreat(GuardSquad squad) {
        cancelThreatTargetRefresh(squad);
        squad.threatId = null;
        squad.threat = null;
        squad.threatExpiresAtNanos = 0L;
    }

    private boolean isCurrentThreat(UUID ownerId, LivingEntity target) {
        GuardSquad squad = squads.get(ownerId);
        return squad != null
                && squad.active
                && squad.threatId != null
                && !isThreatExpired(squad)
                && squad.threatId.equals(target.getUniqueId())
                && !target.isDead()
                && target.isValid();
    }

    private boolean isThreatExpired(GuardSquad squad) {
        return squad.threatId != null
                && System.nanoTime() - squad.threatExpiresAtNanos >= 0L;
    }

    private void restoreCurrentThreatTarget(GuardSquad squad, Husk guard) {
        LivingEntity currentTarget = guard.getTarget();
        LivingEntity threat = squad.threat;
        if (threat == null || !isCurrentThreat(squad.ownerId, threat) || !isInSameWorld(guard, threat)) {
            if (currentTarget != null) {
                guard.setTarget(null);
            }
            return;
        }

        if (currentTarget == null || !currentTarget.getUniqueId().equals(threat.getUniqueId())) {
            guard.setTarget(threat);
        }
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

    private boolean isFriendlyToOwner(Entity entity, UUID ownerId) {
        return ownerId.equals(entity.getUniqueId()) || isGuardOfOwner(entity, ownerId);
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
        Location base = owner.getLocation();
        if (base == null || base.getWorld() == null) {
            return null;
        }
        int direction = slot == 0 ? 1 : -1;
        int[][] offsets = {
                {2 * direction, 0}, {2 * direction, 1}, {2 * direction, -1},
                {direction, 2}, {direction, -2}, {3 * direction, 0}
        };
        for (int[] offset : offsets) {
            Location candidate = safeLocationAt(base, offset[0], offset[1]);
            if (candidate != null) {
                return candidate;
            }
        }

        for (int radius = 1; radius <= SAFE_LOCATION_SEARCH_RADIUS; radius++) {
            // Chaque slot commence la recherche de son propre côté afin d'éviter que les deux
            // gardes se superposent lorsque les emplacements préférés sont obstrués.
            for (int normalizedX = radius; normalizedX >= -radius; normalizedX--) {
                int offsetX = normalizedX * direction;
                for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                    if (Math.max(Math.abs(offsetX), Math.abs(offsetZ)) != radius) {
                        continue;
                    }
                    Location candidate = safeLocationAt(base, offsetX, offsetZ);
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
        }

        // Ici, je préfère réessayer au prochain cycle plutôt que téléporter un garde dans une zone dangereuse.
        return null;
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

    private void removeOrphanedGuards() {
        for (World world : Bukkit.getWorlds()) {
            for (Husk guard : world.getEntitiesByClass(Husk.class)) {
                if (hasRoyalGuardMarker(guard) && !isTrackedGuard(guard)) {
                    guardsById.remove(guard.getUniqueId());
                    removeEntityQuietly(guard);
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

    private boolean isIronGolemNeutralityEnabled() {
        return plugin.getConfig().getBoolean(
                "garde.iron-golem-neutrality", DEFAULT_IRON_GOLEM_NEUTRALITY);
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
    }

    @FunctionalInterface
    interface GuardFactory {
        Husk spawn(Player owner, Location location, UUID ownerId, int slot, GuardSettings settings);
    }

    @FunctionalInterface
    interface GuardLocationResolver {
        Location find(Player owner, int slot);
    }

    record GuardSettings(double maxHealth,
                         double attackDamage,
                         double movementSpeed,
                         double knockbackResistance) {

        static GuardSettings from(JavaPlugin plugin) {
            return new GuardSettings(
                    positiveBounded(plugin.getConfig().getDouble("garde.attributes.max-health", DEFAULT_MAX_HEALTH),
                            DEFAULT_MAX_HEALTH, MAX_CONFIGURED_HEALTH),
                    positiveBounded(plugin.getConfig().getDouble("garde.attributes.attack-damage", DEFAULT_ATTACK_DAMAGE),
                            DEFAULT_ATTACK_DAMAGE, MAX_CONFIGURED_ATTACK_DAMAGE),
                    positiveBounded(plugin.getConfig().getDouble("garde.attributes.movement-speed", DEFAULT_MOVEMENT_SPEED),
                            DEFAULT_MOVEMENT_SPEED, MAX_CONFIGURED_MOVEMENT_SPEED),
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

    private static final class GuardSquad {
        private final UUID ownerId;
        private Player owner;
        private final Map<Integer, UUID> guardIds = new HashMap<>();
        private final Map<Integer, BukkitTask> respawnTasks = new HashMap<>();
        private final Map<Integer, Integer> pathFailures = new HashMap<>();
        private final Map<Integer, Location> lastLocations = new HashMap<>();
        private final Map<Integer, Integer> stuckChecks = new HashMap<>();
        private BukkitTask targetRefreshTask;
        private boolean active = true;
        private UUID threatId;
        private LivingEntity threat;
        private long threatExpiresAtNanos;

        private GuardSquad(UUID ownerId, Player owner) {
            this.ownerId = ownerId;
            this.owner = owner;
        }
    }
}
