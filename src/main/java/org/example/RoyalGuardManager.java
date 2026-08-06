package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Gère les gardes royaux temporaires associés à un joueur connecté.
 */
public final class RoyalGuardManager implements CommandExecutor, Listener {

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
    private static final long THREAT_DURATION_MILLIS = 10_000L;
    private static final int MAX_PATH_FAILURES = 3;
    private static final double DEFAULT_FOLLOW_RADIUS = 20.0D;
    private static final double DEFAULT_COMFORT_DISTANCE = 3.0D;
    private static final int DEFAULT_RESPAWN_DELAY_SECONDS = 20;
    private static final double DEFAULT_MAX_HEALTH = 100.0D;
    private static final double DEFAULT_ATTACK_DAMAGE = 16.0D;
    private static final double DEFAULT_MOVEMENT_SPEED = 0.35D;
    private static final double DEFAULT_KNOCKBACK_RESISTANCE = 0.6D;
    private static final double FOLLOW_SPEED = 1.15D;
    private static final double MAX_CONFIGURED_HEALTH = 1024.0D;
    private static final double MAX_CONFIGURED_ATTACK_DAMAGE = 2048.0D;
    private static final double MAX_CONFIGURED_MOVEMENT_SPEED = 1024.0D;
    private static final double MAX_CONFIGURED_KNOCKBACK_RESISTANCE = 1.0D;
    private static final int SAFE_LOCATION_SEARCH_RADIUS = 4;
    private static final int SAFE_LOCATION_VERTICAL_RANGE = 4;

    private final JavaPlugin plugin;
    private final GuardNavigator navigator;
    private final GuardFactory guardFactory;
    private final GuardLocationResolver locationResolver;
    private final Map<UUID, GuardSquad> squads = new HashMap<>();
    private final Map<UUID, Husk> guardsById = new HashMap<>();
    private BukkitTask followTask;

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

        UUID ownerId = player.getUniqueId();
        if (squads.containsKey(ownerId)) {
            dismissSquad(ownerId);
            player.sendMessage(ChatColor.YELLOW + "Tes gardes royaux sont repartis.");
            return true;
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
            return true;
        }
        player.sendMessage(ChatColor.GOLD + "Tes deux gardes royaux sont à tes côtés.");
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnerDamaged(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (!(event.getEntity() instanceof Player owner)) {
            return;
        }

        GuardSquad squad = squads.get(owner.getUniqueId());
        if (squad == null || !squad.active) {
            return;
        }

        LivingEntity attacker = resolveLivingAttacker(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(owner.getUniqueId())
                || isGuardOfOwner(attacker, owner.getUniqueId()) || !isInSameWorld(owner, attacker)) {
            return;
        }

        squad.threatId = attacker.getUniqueId();
        squad.threat = attacker;
        squad.threatExpiresAt = System.currentTimeMillis() + THREAT_DURATION_MILLIS;
        for (int slot = 0; slot < GUARD_COUNT; slot++) {
            Husk guard = getLiveGuard(squad, slot);
            if (guard != null) {
                if (!isInSameWorld(guard, owner)) {
                    teleportNearOwner(guard, owner, slot);
                }
                restoreCurrentThreatTarget(squad, guard);
            }
        }
    }

    @EventHandler
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
            if (event.getEntity() instanceof Mob mob) {
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
        if (!(event.getEntity() instanceof LivingEntity target)
                || ownerId == null
                || !isCurrentThreat(ownerId, target)) {
            event.setCancelled(true);
            if (event.getDamager() instanceof Mob mob) {
                mob.setTarget(null);
            }
        }
    }

    @EventHandler
    public void onGuardDeath(EntityDeathEvent event) {
        if (!isRoyalGuard(event.getEntity())) {
            return;
        }

        event.getDrops().clear();
        event.setDroppedExp(0);

        UUID ownerId = ownerOf(event.getEntity());
        Integer slot = slotOf(event.getEntity());
        if (ownerId == null || slot == null) {
            return;
        }

        GuardSquad squad = squads.get(ownerId);
        if (squad == null || !squad.active) {
            return;
        }
        UUID expectedGuardId = squad.guardIds.get(slot);
        if (!event.getEntity().getUniqueId().equals(expectedGuardId)) {
            return;
        }

        squad.guardIds.remove(slot);
        guardsById.remove(event.getEntity().getUniqueId());
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
                teleportNearOwner(guard, event.getPlayer(), slot);
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
            if (isRoyalGuard(entity) && !isTrackedGuard(entity)) {
                entity.remove();
            }
        }
    }

    /**
     * Ici, je nettoie toutes les entités et tâches quand le plugin s'arrête.
     */
    public void shutdown() {
        if (followTask != null) {
            followTask.cancel();
            followTask = null;
        }

        List<UUID> ownerIds = new ArrayList<>(squads.keySet());
        ownerIds.forEach(this::dismissSquad);
        removeOrphanedGuards();
    }

    void followActiveSquads() {
        for (GuardSquad squad : new ArrayList<>(squads.values())) {
            Player owner = getOnlineOwner(squad);
            if (!squad.active || owner == null) {
                dismissSquad(squad.ownerId);
                continue;
            }

            expireThreatIfNeeded(squad);
            clearThreatIfNoLongerRelevant(squad, owner);
            for (int slot = 0; slot < GUARD_COUNT; slot++) {
                Husk guard = getLiveGuard(squad, slot);
                if (guard == null) {
                    scheduleRespawn(squad, slot);
                    continue;
                }
                followGuard(squad, guard, owner, slot);
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
        if (!(entity instanceof Husk)) {
            return false;
        }
        String type = entity.getPersistentDataContainer().get(Keys.royalGuardType(), PersistentDataType.STRING);
        return GUARD_TYPE.equals(type) && ownerOf(entity) != null && isGuardSlot(slotOf(entity));
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
            teleportNearOwner(guard, owner, slot);
            squad.pathFailures.remove(slot);
            squad.lastLocations.remove(slot);
            squad.stuckChecks.remove(slot);
            restoreCurrentThreatTarget(squad, guard);
            return;
        }

        restoreCurrentThreatTarget(squad, guard);

        double distanceSquared = guard.getLocation().distanceSquared(owner.getLocation());
        double followRadius = getPositiveConfig("garde.follow-radius", DEFAULT_FOLLOW_RADIUS);
        if (distanceSquared > followRadius * followRadius) {
            teleportNearOwner(guard, owner, slot);
            squad.pathFailures.remove(slot);
            squad.lastLocations.remove(slot);
            squad.stuckChecks.remove(slot);
            restoreCurrentThreatTarget(squad, guard);
            return;
        }

        double comfortDistance = Math.min(followRadius,
                getPositiveConfig("garde.comfort-distance", DEFAULT_COMFORT_DISTANCE));
        if (guard.getTarget() != null || distanceSquared <= comfortDistance * comfortDistance) {
            squad.pathFailures.remove(slot);
            squad.lastLocations.remove(slot);
            squad.stuckChecks.remove(slot);
            return;
        }

        boolean pathFound = navigator.moveTo(guard, owner.getLocation(), FOLLOW_SPEED);
        if (pathFound) {
            squad.pathFailures.remove(slot);
            if (isStuck(squad, guard, slot)) {
                teleportNearOwner(guard, owner, slot);
                squad.lastLocations.remove(slot);
                squad.stuckChecks.remove(slot);
            }
            return;
        }

        int failures = squad.pathFailures.merge(slot, 1, Integer::sum);
        if (failures >= MAX_PATH_FAILURES) {
            teleportNearOwner(guard, owner, slot);
            squad.pathFailures.remove(slot);
            squad.lastLocations.remove(slot);
            squad.stuckChecks.remove(slot);
        }
    }

    private boolean spawnGuard(GuardSquad squad, Player owner, int slot) {
        if (!squad.active || !owner.isOnline()) {
            return false;
        }
        if (getLiveGuard(squad, slot) != null) {
            return true;
        }

        try {
            Location spawnLocation = locationResolver.find(owner, slot);
            if (spawnLocation == null || spawnLocation.getWorld() == null) {
                plugin.getLogger().warning("Aucun emplacement sûr n'est disponible pour le garde royal " + slot + " de " + owner.getName() + ".");
                return false;
            }
            Husk guard = guardFactory.spawn(owner, spawnLocation, squad.ownerId, slot, GuardSettings.from(plugin));
            if (guard == null) {
                plugin.getLogger().warning("Impossible de créer le garde royal " + slot + " de " + owner.getName() + ".");
                return false;
            }
            squad.guardIds.put(slot, guard.getUniqueId());
            guardsById.put(guard.getUniqueId(), guard);
            squad.pathFailures.remove(slot);
            squad.lastLocations.remove(slot);
            squad.stuckChecks.remove(slot);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Impossible de créer le garde royal " + slot + " de " + owner.getName() + ".", exception);
            return false;
        }
    }

    private Husk createConfiguredGuard(Player owner, Location location, UUID ownerId, int slot, GuardSettings settings) {
        Husk guard = owner.getWorld().spawn(location, Husk.class);
        configureGuard(guard, ownerId, slot, settings);
        return guard;
    }

    private void configureGuard(Husk guard, UUID ownerId, int slot, GuardSettings settings) {
        guard.setCustomName(GUARD_NAME);
        guard.setCustomNameVisible(true);
        guard.setPersistent(true);
        guard.setCanPickupItems(false);
        guard.getPersistentDataContainer().set(Keys.royalGuardType(), PersistentDataType.STRING, GUARD_TYPE);
        guard.getPersistentDataContainer().set(Keys.royalGuardOwner(), PersistentDataType.STRING, ownerId.toString());
        guard.getPersistentDataContainer().set(Keys.royalGuardSlot(), PersistentDataType.INTEGER, slot);

        setAttribute(guard, Attribute.MAX_HEALTH, settings.maxHealth());
        setAttribute(guard, Attribute.ATTACK_DAMAGE, settings.attackDamage());
        setAttribute(guard, Attribute.MOVEMENT_SPEED, settings.movementSpeed());
        setAttribute(guard, Attribute.KNOCKBACK_RESISTANCE, settings.knockbackResistance());
        AttributeInstance maxHealth = guard.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            guard.setHealth(maxHealth.getBaseValue());
        }

        EntityEquipment equipment = guard.getEquipment();
        if (equipment == null) {
            return;
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

    private void setAttribute(Husk guard, Attribute attribute, double value) {
        AttributeInstance instance = guard.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private void scheduleRespawn(GuardSquad squad, int slot) {
        if (!squad.active || squad.respawnTasks.containsKey(slot)) {
            return;
        }

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            squad.respawnTasks.remove(slot);
            if (!squad.active || squads.get(squad.ownerId) != squad || getLiveGuard(squad, slot) != null) {
                return;
            }

            Player owner = getOnlineOwner(squad);
            if (owner == null) {
                dismissSquad(squad.ownerId);
                return;
            }
            spawnGuard(squad, owner, slot);
        }, getRespawnDelayTicks());
        squad.respawnTasks.put(slot, task);
    }

    private void dismissSquad(UUID ownerId) {
        GuardSquad squad = squads.remove(ownerId);
        if (squad == null) {
            return;
        }

        squad.active = false;
        squad.respawnTasks.values().forEach(BukkitTask::cancel);
        squad.respawnTasks.clear();
        for (UUID guardId : squad.guardIds.values()) {
            Entity entity = guardsById.remove(guardId);
            if (entity == null) {
                entity = Bukkit.getEntity(guardId);
            }
            if (entity != null && entity.isValid() && !entity.isDead()) {
                entity.remove();
            }
        }
        squad.guardIds.clear();
        squad.pathFailures.clear();
        squad.lastLocations.clear();
        squad.stuckChecks.clear();
        clearThreat(squad);
    }

    private void expireThreatIfNeeded(GuardSquad squad) {
        if (squad.threatId == null || System.currentTimeMillis() <= squad.threatExpiresAt) {
            return;
        }
        clearThreatAndTargets(squad);
    }

    private void clearThreatIfNoLongerRelevant(GuardSquad squad, Player owner) {
        if (squad.threatId == null) {
            return;
        }

        LivingEntity threat = squad.threat;
        double followRadius = getPositiveConfig("garde.follow-radius", DEFAULT_FOLLOW_RADIUS);
        if (threat == null || !isCurrentThreat(squad.ownerId, threat) || !isInSameWorld(owner, threat)
                || owner.getLocation().distanceSquared(threat.getLocation()) > followRadius * followRadius) {
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
        squad.threatId = null;
        squad.threat = null;
        squad.threatExpiresAt = 0L;
    }

    private boolean isCurrentThreat(UUID ownerId, LivingEntity target) {
        GuardSquad squad = squads.get(ownerId);
        return squad != null
                && squad.active
                && squad.threatId != null
                && System.currentTimeMillis() <= squad.threatExpiresAt
                && squad.threatId.equals(target.getUniqueId())
                && !target.isDead()
                && target.isValid();
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
        return first.getWorld().equals(second.getWorld());
    }

    private Husk getLiveGuard(GuardSquad squad, int slot) {
        UUID guardId = squad.guardIds.get(slot);
        if (guardId == null) {
            return null;
        }
        Husk cachedGuard = guardsById.get(guardId);
        if (cachedGuard != null) {
            if (cachedGuard.isValid() && !cachedGuard.isDead()) {
                return cachedGuard;
            }
            guardsById.remove(guardId);
        }
        Entity entity = Bukkit.getEntity(guardId);
        if (entity instanceof Husk guard && guard.isValid() && !guard.isDead()) {
            guardsById.put(guardId, guard);
            return guard;
        }
        return null;
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
        if (previous == null || !previous.getWorld().equals(current.getWorld())
                || previous.distanceSquared(current) > 0.04D) {
            squad.stuckChecks.put(slot, 1);
            return false;
        }

        int checks = squad.stuckChecks.merge(slot, 1, Integer::sum);
        return checks >= MAX_PATH_FAILURES;
    }

    private void teleportNearOwner(Husk guard, Player owner, int slot) {
        Location destination = locationResolver.find(owner, slot);
        if (destination == null || destination.getWorld() == null || !destination.getWorld().equals(owner.getWorld())) {
            return;
        }
        guard.teleport(destination);
        guard.setFallDistance(0.0F);
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
            for (int offsetX = -radius; offsetX <= radius; offsetX++) {
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
        for (int y = minY; y <= maxY; y++) {
            Block ground = world.getBlockAt(x, y - 1, z);
            Block feet = world.getBlockAt(x, y, z);
            Block head = world.getBlockAt(x, y + 1, z);
            if (isSafeGuardLocation(ground, feet, head)) {
                return new Location(world, x + 0.5D, y, z + 0.5D, base.getYaw(), base.getPitch());
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
                if (isRoyalGuard(guard)) {
                    guard.remove();
                }
            }
        }
    }

    private double getPositiveConfig(String path, double fallback) {
        double value = plugin.getConfig().getDouble(path, fallback);
        return value > 0.0D ? value : fallback;
    }

    private long getRespawnDelayTicks() {
        int seconds = plugin.getConfig().getInt("garde.respawn-delay-seconds", DEFAULT_RESPAWN_DELAY_SECONDS);
        return (seconds > 0 ? seconds : DEFAULT_RESPAWN_DELAY_SECONDS) * 20L;
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

    private static double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0D ? value : fallback;
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
        private boolean active = true;
        private UUID threatId;
        private LivingEntity threat;
        private long threatExpiresAt;

        private GuardSquad(UUID ownerId, Player owner) {
            this.ownerId = ownerId;
            this.owner = owner;
        }
    }
}
