package org.example.village;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Snowman;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.example.Keys;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Gère les « Chevaliers du village » persistants.
 *
 * <p>Le gestionnaire travaille exclusivement sur le thread serveur. Il ne
 * force jamais le chargement d'un chunk : une patrouille d'une zone déchargée
 * est simplement suspendue, puis réconciliée lors du prochain chargement.</p>
 */
public final class VillageKnightManager implements Listener {

    public static final String KNIGHT_NAME = "Chevalier du village";
    public static final String KNIGHT_TYPE = "village_knight";

    private static final String SCOREBOARD_TAG =
            "MG_VILLAGE_KNIGHT";
    private static final String ROYAL_GUARD_TYPE = "royal";
    private static final String ROYAL_GUARD_NAME = "Garde Royale";

    private static final String LEGACY_GATE_GUARD_TAG =
            "MG_VILLAGE_GUARD";
    private static final long AUTOSAVE_PERIOD_TICKS = 20L * 30L;
    private static final long TARGET_MEMORY_MILLIS = 20_000L;
    private static final long POSITION_SAVE_INTERVAL_MILLIS = 5_000L;
    private static final long EQUIPMENT_REPAIR_INTERVAL_MILLIS = 10_000L;
    private static final long RAMPART_REFRESH_INTERVAL_MILLIS = 30_000L;
    private static final long MIN_RECOVERY_RETRY_MILLIS = 2_000L;
    private static final int MAX_PATH_FAILURES = 3;
    private static final double TELEPORT_VISIBILITY_RADIUS = 14.0D;
    private static final double MIN_MOVEMENT_SAMPLE_DISTANCE_SQ = 1.0D;
    private static final double RAMPART_VERTICAL_TOLERANCE = 1.75D;

    private static final Set<Material> DANGEROUS_GROUND = Set.of(
            Material.LAVA,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.MAGMA_BLOCK,
            Material.CACTUS,
            Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE,
            Material.SWEET_BERRY_BUSH,
            Material.POWDER_SNOW
    );

    private final JavaPlugin plugin;
    private final VillageKnightSettings settings;
    private final VillageKnightStore store;
    private final Map<Integer, VillageKnightGarrison> garrisons =
            new LinkedHashMap<>();
    private final Map<UUID, SlotReference> slotsByEntity =
            new HashMap<>();
    private final List<SlotReference> scanOrder =
            new ArrayList<>();
    private final Map<UUID, Long> lastEquipmentRepairAt =
            new HashMap<>();
    private final Map<Integer, Long> lastRampartRefreshAt =
            new HashMap<>();

    private boolean writable;
    private boolean dirty;
    private boolean shuttingDown;
    private int scanCursor;
    private BukkitTask maintenanceTask;
    private BukkitTask autosaveTask;
    private BukkitTask urgentSaveTask;

    public VillageKnightManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = VillageKnightSettings.from(plugin);
        this.store = new VillageKnightStore(
                plugin.getDataFolder(),
                plugin.getLogger()
        );

        VillageKnightStore.LoadResult loaded = store.load();
        this.writable = loaded.writable();
        for (VillageKnightGarrison garrison : loaded.garrisons()) {
            VillageKnightGarrison previous = garrisons.putIfAbsent(
                    garrison.villageId(),
                    garrison
            );
            if (previous != null) {
                writable = false;
                plugin.getLogger().severe(
                        "[Chevaliers] Identifiant de village dupliqué : "
                                + garrison.villageId()
                                + ". La sauvegarde est placée en lecture seule."
                );
            }
        }

        migrateLoadedPlansAtStartup();

        Bukkit.getPluginManager().registerEvents(this, plugin);
        reconcileInitiallyLoadedEntities();
        rebuildScanOrder();

        if (settings.enabled()) {
            maintenanceTask = Bukkit.getScheduler().runTaskTimer(
                    plugin,
                    this::runMaintenance,
                    1L,
                    settings.maintenancePeriodTicks()
            );
            autosaveTask = Bukkit.getScheduler().runTaskTimer(
                    plugin,
                    this::saveIfDirty,
                    AUTOSAVE_PERIOD_TICKS,
                    AUTOSAVE_PERIOD_TICKS
            );
        } else {
            plugin.getLogger().info(
                    "[Chevaliers] Système désactivé par configuration."
            );
        }

        if (!writable) {
            plugin.getLogger().severe(
                    "[Chevaliers] Persistance en lecture seule : aucune "
                            + "donnée existante ne sera écrasée."
            );
        } else if (!garrisons.isEmpty()) {
            plugin.getLogger().info(
                    "[Chevaliers] " + garrisons.size()
                            + " garnison(s) persistante(s) restaurée(s)."
            );
        }
        if (dirty) {
            requestUrgentSave();
        }
    }

    /**
     * Enregistre la garnison une fois la construction du village terminée.
     *
     * @return {@code true} si un plan de garnison a été créé.
     */
    public boolean registerVillage(
            int villageId,
            Location villageCenter,
            VillageLayoutPlan layout,
            Map<String, Location> anchors,
            int radiusX,
            int radiusZ,
            int baseY) {
        ensurePrimaryThread();
        if (shuttingDown || !settings.enabled()) {
            return false;
        }
        if (!writable) {
            plugin.getLogger().severe(
                    "[Chevaliers] Garnison " + villageId
                            + " non créée : persistance en lecture seule."
            );
            return false;
        }

        VillageKnightGarrison planned;
        try {
            planned = VillageKnightPlanner.plan(
                    villageId,
                    villageCenter,
                    layout,
                    anchors,
                    radiusX,
                    radiusZ,
                    baseY,
                    settings
            );
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "[Chevaliers] Impossible de planifier la garnison "
                            + villageId + ".",
                    exception
            );
            return false;
        }
        if (planned == null) {
            plugin.getLogger().warning(
                    "[Chevaliers] Aucun emplacement exploitable pour la "
                            + "garnison " + villageId + "."
            );
            return false;
        }

        removeGarrisonInternal(villageId, true);
        garrisons.put(villageId, planned);
        rebuildScanOrder();
        dirty = true;

        int spawned = 0;
        long now = System.currentTimeMillis();
        for (VillageKnightGarrison.KnightSlot slot : planned.slots()) {
            if (spawnSlot(planned, slot, now)) {
                spawned++;
            }
        }
        requestUrgentSave();

        long archers = planned.slots().stream()
                .filter(slot -> slot.unitKind()
                        == VillageKnightGarrison.UnitKind.ARCHER)
                .count();
        plugin.getLogger().info(
                "[Chevaliers] Garnison " + villageId + " créée : "
                        + planned.unitCount() + " unités, dont "
                        + archers + " archer(s) ; " + spawned
                        + " entité(s) apparue(s) immédiatement."
        );
        return true;
    }

    /**
     * Supprime la garnison, ses entités chargées et son état persistant.
     */
    public boolean unregisterVillage(int villageId) {
        ensurePrimaryThread();
        boolean removed = removeGarrisonInternal(villageId, true);
        if (removed) {
            rebuildScanOrder();
            dirty = true;
            requestUrgentSave();
        }
        return removed;
    }

    private boolean removeGarrisonInternal(
            int villageId,
            boolean removeLoadedEntities) {
        VillageKnightGarrison garrison =
                garrisons.remove(villageId);
        lastRampartRefreshAt.remove(villageId);
        if (garrison == null) {
            /*
             * Une entité orpheline peut encore exister après une restauration
             * manuelle de fichier ; le balayage ciblé reste donc utile.
             */
            if (removeLoadedEntities) {
                removeLoadedEntitiesForVillage(villageId);
            }
            return false;
        }

        for (VillageKnightGarrison.KnightSlot slot
                : garrison.slots()) {
            UUID entityId = slot.entityId();
            if (entityId != null) {
                slotsByEntity.remove(entityId);
                lastEquipmentRepairAt.remove(entityId);
                if (removeLoadedEntities) {
                    Entity entity = Bukkit.getEntity(entityId);
                    if (entity != null && isVillageKnight(entity)) {
                        entity.remove();
                    }
                }
            }
        }
        if (removeLoadedEntities) {
            removeLoadedEntitiesForVillage(villageId);
        }
        return true;
    }

    private void removeLoadedEntitiesForVillage(int villageId) {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : new ArrayList<>(world.getEntities())) {
                if (isVillageKnight(entity)
                        && villageIdOf(entity) == villageId) {
                    slotsByEntity.remove(entity.getUniqueId());
                    lastEquipmentRepairAt.remove(entity.getUniqueId());
                    entity.remove();
                }
            }
        }
    }

    /**
     * Arrête les tâches sans supprimer les entités : leurs marqueurs
     * persistants permettent leur adoption au prochain démarrage.
     */
    public void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        cancelTask(maintenanceTask);
        cancelTask(autosaveTask);
        cancelTask(urgentSaveTask);
        maintenanceTask = null;
        autosaveTask = null;
        urgentSaveTask = null;

        updateAllLoadedPositions();
        saveIfDirty();
        HandlerList.unregisterAll(this);
        slotsByEntity.clear();
        scanOrder.clear();
        lastEquipmentRepairAt.clear();
        lastRampartRefreshAt.clear();
    }

    /**
     * Met à niveau les sauvegardes historiques avant l'adoption des entités
     * déjà chargées. Cette étape ne force aucun chunk : si le monde n'est pas
     * encore disponible, la migration sera retentée par la maintenance.
     */
    private void migrateLoadedPlansAtStartup() {
        if (!writable) {
            return;
        }
        for (VillageKnightGarrison current
                : new ArrayList<>(garrisons.values())) {
            if (!current.requiresPlanMigration()) {
                continue;
            }
            World world = Bukkit.getWorld(current.worldId());
            if (world == null) {
                continue;
            }
            try {
                VillageKnightGarrison replacement =
                        VillageKnightPlanner.upgradeLegacy(
                                current,
                                world,
                                settings
                        );
                if (replacement != null) {
                    installReplacementPlan(
                            current,
                            replacement,
                            "migration du plan historique"
                    );
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                        Level.WARNING,
                        "[Chevaliers] Migration différée pour la garnison "
                                + current.villageId() + ".",
                        exception
                );
            }
        }
    }

    private void runMaintenance() {
        if (shuttingDown || !settings.enabled()) {
            return;
        }
        ensurePrimaryThread();

        long now = System.currentTimeMillis();
        for (VillageKnightGarrison loaded
                : new ArrayList<>(garrisons.values())) {
            World world = Bukkit.getWorld(loaded.worldId());
            if (world == null
                    || world.getDifficulty() == Difficulty.PEACEFUL) {
                continue;
            }

            VillageKnightGarrison garrison =
                    prepareGarrisonPlan(loaded, world, now);
            if (garrison == null) {
                continue;
            }

            advanceRoutes(garrison, now);
            for (VillageKnightGarrison.KnightSlot slot
                    : garrison.slots()) {
                Mob knight = resolveLiveKnight(garrison, slot);
                if (knight == null) {
                    reconcileOrRespawn(garrison, slot, world, now);
                    continue;
                }
                maintainKnight(garrison, slot, knight, now);
            }
        }
        scanForThreats(now);
    }

    /**
     * Termine progressivement la migration et le calcul des remparts lorsque
     * les chunks nécessaires deviennent disponibles.
     */
    private VillageKnightGarrison prepareGarrisonPlan(
            VillageKnightGarrison current,
            World world,
            long now) {
        if (!writable || current == null || world == null) {
            return current;
        }

        VillageKnightGarrison prepared = current;
        try {
            if (prepared.requiresPlanMigration()) {
                VillageKnightGarrison migrated =
                        VillageKnightPlanner.upgradeLegacy(
                                prepared,
                                world,
                                settings
                        );
                if (migrated != null) {
                    prepared = installReplacementPlan(
                            prepared,
                            migrated,
                            "migration du plan historique"
                    );
                }
            }

            if (!prepared.rampartPlanComplete()) {
                long lastAttempt = lastRampartRefreshAt.getOrDefault(
                        prepared.villageId(),
                        0L
                );
                if (now - lastAttempt >= RAMPART_REFRESH_INTERVAL_MILLIS) {
                    lastRampartRefreshAt.put(prepared.villageId(), now);
                    VillageKnightGarrison refreshed =
                            VillageKnightPlanner.refreshRampartsIfLoaded(
                                    prepared,
                                    world,
                                    settings
                            );
                    if (refreshed != null && refreshed != prepared) {
                        prepared = installReplacementPlan(
                                prepared,
                                refreshed,
                                "actualisation des chemins de ronde"
                        );
                    }
                }
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "[Chevaliers] Plan inchangé pour la garnison "
                            + current.villageId()
                            + " après une erreur de mise à niveau.",
                    exception
            );
        }
        return prepared;
    }

    /**
     * Installe atomiquement un nouveau plan en conservant les entités dont le
     * créneau stable existe encore. Les gardes devenus orphelins sont retirés
     * immédiatement s'ils sont chargés ; sinon ils seront écartés à leur
     * prochain chargement par {@link #adoptEntity(Entity)}.
     */
    private VillageKnightGarrison installReplacementPlan(
            VillageKnightGarrison current,
            VillageKnightGarrison replacement,
            String reason) {
        if (current == null || replacement == null
                || current.villageId() != replacement.villageId()) {
            throw new IllegalArgumentException(
                    "Remplacement de garnison incohérent."
            );
        }

        Set<UUID> retainedSlotIds = new java.util.HashSet<>();
        for (VillageKnightGarrison.KnightSlot replacementSlot
                : replacement.slots()) {
            VillageKnightGarrison.KnightSlot previousSlot =
                    current.slot(replacementSlot.slotId());
            if (previousSlot == null
                    || previousSlot.unitKind()
                    != replacementSlot.unitKind()) {
                continue;
            }

            /*
             * Les UUID de créneau sont déterministes. Préserver leur entité
             * évite une vague de despawn/respawn lors d'une migration.
             */
            replacementSlot.setEntityId(previousSlot.entityId());
            replacementSlot.setRespawnAtMillis(
                    previousSlot.respawnAtMillis()
            );
            replacementSlot.setLastKnownPosition(
                    previousSlot.lastKnownPosition()
            );
            replacementSlot.clearTarget();
            replacementSlot.clearNavigationCommand();
            retainedSlotIds.add(replacementSlot.slotId());
        }

        for (VillageKnightGarrison.KnightSlot previousSlot
                : current.slots()) {
            if (retainedSlotIds.contains(previousSlot.slotId())) {
                continue;
            }
            UUID entityId = previousSlot.entityId();
            if (entityId == null) {
                continue;
            }
            slotsByEntity.remove(entityId);
            lastEquipmentRepairAt.remove(entityId);
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null && isVillageKnight(entity)) {
                entity.remove();
            }
        }

        garrisons.put(replacement.villageId(), replacement);
        lastRampartRefreshAt.remove(replacement.villageId());
        rebuildScanOrder();
        dirty = true;
        requestUrgentSave();
        plugin.getLogger().info(
                "[Chevaliers] Garnison " + replacement.villageId()
                        + " réorganisée (" + reason + ") : "
                        + replacement.unitCount() + " unité(s)."
        );
        return replacement;
    }

    private void advanceRoutes(VillageKnightGarrison garrison,
                               long now) {
        for (VillageKnightGarrison.PatrolRoute route
                : garrison.routes()) {
            if (!route.kind().isPatrol()
                    || route.waypoints().size() <= 1) {
                continue;
            }

            int available = 0;
            int arrived = 0;
            for (VillageKnightGarrison.KnightSlot slot
                    : garrison.slots()) {
                if (slot.routeId() != route.routeId()
                        || slot.targetEntityId() != null) {
                    continue;
                }
                Mob knight = resolveLiveKnight(garrison, slot);
                if (knight == null) {
                    continue;
                }
                available++;
                Location target = formationTarget(
                        knight.getWorld(),
                        route,
                        slot
                );
                double radius = arrivalRadius(route);
                if (knight.getLocation().distanceSquared(target)
                        <= radius * radius) {
                    arrived++;
                }
            }

            boolean majorityArrived = available > 0
                    && arrived >= Math.max(1, (available + 1) / 2);
            boolean timedOut = available > 0
                    && now - route.lastAdvanceAtMillis()
                    >= settings.routeAdvanceTimeoutMillis();
            if (majorityArrived || timedOut) {
                route.advance(now);
                for (VillageKnightGarrison.KnightSlot slot
                        : garrison.slots()) {
                    if (slot.routeId() == route.routeId()) {
                        slot.clearNavigationCommand();
                    }
                }
                dirty = true;
            }
        }
    }

    private void maintainKnight(
            VillageKnightGarrison garrison,
            VillageKnightGarrison.KnightSlot slot,
            Mob knight,
            long now) {
        if (!knight.isValid() || knight.isDead()) {
            return;
        }

        VillageKnightGarrison.PatrolRoute route =
                garrison.route(slot.routeId());
        if (route == null) {
            knight.getPathfinder().stopPathfinding();
            slot.clearNavigationCommand();
            return;
        }

        /*
         * Un archer tombé du chemin de ronde doit y remonter immédiatement.
         * Cette correction est volontairement prioritaire sur le combat : elle
         * empêche l'IA Skeleton de poursuivre une cible jusque dans les talus.
         */
        if (route.kind() == VillageKnightGarrison.RouteKind.RAMPART
                && !isWithinRampartCorridor(route, knight.getLocation())) {
            recoverRampartPosition(garrison, slot, knight, route, now);
            return;
        }

        updateLastKnownPosition(slot, knight, now);
        repairKnightPeriodically(garrison, slot, knight, now);

        LivingEntity target = resolveAssignedTarget(
                garrison,
                slot,
                knight,
                now
        );
        if (target != null) {
            slot.clearNavigationCommand();
            if (knight.getTarget() == null
                    || !knight.getTarget().getUniqueId()
                    .equals(target.getUniqueId())) {
                knight.getPathfinder().stopPathfinding();
                knight.setTarget(target);
            }
            knight.setAggressive(true);
            return;
        }

        if (knight.getTarget() != null) {
            knight.setTarget(null);
            slot.clearNavigationCommand();
        }
        knight.setAggressive(false);
        patrol(garrison, slot, knight, route, now);
    }

    /**
     * Replace sans délai un archer qui a quitté le corridor vertical ou
     * horizontal de son rempart. Contrairement à la récupération ordinaire,
     * la présence d'un joueur ne retarde pas cette opération de sécurité.
     */
    private void recoverRampartPosition(
            VillageKnightGarrison garrison,
            VillageKnightGarrison.KnightSlot slot,
            Mob knight,
            VillageKnightGarrison.PatrolRoute route,
            long now) {
        knight.setTarget(null);
        knight.setAggressive(false);
        knight.getPathfinder().stopPathfinding();
        slot.clearTarget();
        slot.clearNavigationCommand();

        Location safe = nearestSafeRouteLocation(
                knight.getWorld(),
                route,
                knight.getLocation()
        );
        if (safe == null || !knight.teleport(safe)) {
            return;
        }

        VillageKnightGarrison.BlockPoint point =
                VillageKnightGarrison.BlockPoint.from(safe);
        slot.setLastKnownPosition(point);
        slot.resetPathFailures();
        slot.updateMovementSample(point, now);
        slot.markRecoveryAttempt(now);
        dirty = true;
    }

    private LivingEntity resolveAssignedTarget(
            VillageKnightGarrison garrison,
            VillageKnightGarrison.KnightSlot slot,
            Mob knight,
            long now) {
        UUID targetId = slot.targetEntityId();
        if (targetId == null) {
            LivingEntity autonomous = knight.getTarget();
            if (isHostile(autonomous)
                    && isWithinCombatLeash(garrison, slot, autonomous)
                    && assignedResponderCount(
                    garrison,
                    autonomous.getUniqueId()
            ) < settings.maxRespondersPerThreat()) {
                slot.assignTarget(
                        autonomous.getUniqueId(),
                        now + TARGET_MEMORY_MILLIS
                );
                return autonomous;
            }
            if (autonomous != null) {
                knight.setTarget(null);
            }
            return null;
        }

        Entity entity = Bukkit.getEntity(targetId);
        if (!(entity instanceof LivingEntity target)
                || !isHostile(target)
                || !knight.getWorld().equals(target.getWorld())
                || !isWithinCombatLeash(garrison, slot, target)) {
            slot.clearTarget();
            knight.setTarget(null);
            return null;
        }

        VillageKnightGarrison.PatrolRoute route =
                garrison.route(slot.routeId());
        double memoryRadius = Math.max(
                settings.detectionRadius(),
                engagementRadius(route)
        );
        boolean stillClose = knight.getLocation().distanceSquared(
                target.getLocation()
        ) <= memoryRadius * memoryRadius;
        boolean activelyEngaged = knight.getTarget() != null
                && knight.getTarget().getUniqueId()
                .equals(target.getUniqueId());

        if (now > slot.targetExpiresAtMillis()
                && !stillClose
                && !activelyEngaged) {
            slot.clearTarget();
            knight.setTarget(null);
            return null;
        }

        if (stillClose || activelyEngaged) {
            slot.assignTarget(
                    targetId,
                    now + TARGET_MEMORY_MILLIS
            );
        }
        return target;
    }

    private void patrol(
            VillageKnightGarrison garrison,
            VillageKnightGarrison.KnightSlot slot,
            Mob knight,
            VillageKnightGarrison.PatrolRoute route,
            long now) {
        Location desired = formationTarget(
                knight.getWorld(),
                route,
                slot
        );
        VillageKnightGarrison.BlockPoint desiredPoint =
                VillageKnightGarrison.BlockPoint.from(desired);
        if (!isPointChunkLoaded(knight.getWorld(), desiredPoint)) {
            /*
             * Le pathfinder ne reçoit jamais une destination située dans un
             * chunk déchargé ; le gestionnaire n'entraîne donc aucun chargement.
             */
            knight.getPathfinder().stopPathfinding();
            slot.clearNavigationCommand();
            return;
        }

        VillageKnightGarrison.BlockPoint commanded =
                slot.navigationTarget();
        boolean compatible = isNavigationDestinationCompatible(
                commanded,
                desiredPoint,
                route
        );
        Location effectiveTarget = compatible && commanded != null
                ? commanded.toLocation(knight.getWorld())
                : desired;

        double arrival = arrivalRadius(route);
        double distanceSq = knight.getLocation()
                .distanceSquared(effectiveTarget);
        if (distanceSq <= arrival * arrival) {
            if (slot.navigationTarget() != null) {
                knight.getPathfinder().stopPathfinding();
                slot.clearNavigationCommand();
            }
            slot.resetPathFailures();
            slot.updateMovementSample(
                    VillageKnightGarrison.BlockPoint.from(
                            knight.getLocation()
                    ),
                    now
            );
            return;
        }

        boolean refreshElapsed = now - slot.lastPathCommandAtMillis()
                >= settings.pathRefreshMillis();
        if (compatible && !refreshElapsed) {
            recoverStuckKnight(
                    garrison,
                    slot,
                    knight,
                    route,
                    effectiveTarget,
                    now,
                    false
            );
            return;
        }

        Location safeTarget = findSafeLocation(
                knight.getWorld(),
                desiredPoint,
                route
        );
        if (safeTarget == null) {
            /*
             * On mémorise aussi un ordre impossible afin de ne pas interroger
             * les mêmes blocs à chaque cycle de maintenance.
             */
            slot.markPathCommand(desiredPoint, now);
            boolean failedOften = slot.incrementPathFailures()
                    >= MAX_PATH_FAILURES;
            recoverStuckKnight(
                    garrison,
                    slot,
                    knight,
                    route,
                    desired,
                    now,
                    failedOften
            );
            return;
        }

        boolean pathFound;
        try {
            pathFound = knight.getPathfinder().moveTo(
                    safeTarget,
                    settings.patrolSpeed()
            );
        } catch (RuntimeException exception) {
            pathFound = false;
            plugin.getLogger().log(
                    Level.FINE,
                    "[Chevaliers] Échec du pathfinder pour "
                            + slot.slotId() + ".",
                    exception
            );
        }

        VillageKnightGarrison.BlockPoint safePoint =
                VillageKnightGarrison.BlockPoint.from(safeTarget);
        slot.markPathCommand(safePoint, now);
        boolean failedOften = false;
        if (pathFound) {
            slot.resetPathFailures();
        } else {
            failedOften = slot.incrementPathFailures()
                    >= MAX_PATH_FAILURES;
        }

        recoverStuckKnight(
                garrison,
                slot,
                knight,
                route,
                safeTarget,
                now,
                failedOften
        );
    }

    private boolean isNavigationDestinationCompatible(
            VillageKnightGarrison.BlockPoint commanded,
            VillageKnightGarrison.BlockPoint desired,
            VillageKnightGarrison.PatrolRoute route) {
        if (commanded == null || desired == null || route == null
                || commanded.y() != desired.y()) {
            return false;
        }
        int tolerance = route.kind()
                == VillageKnightGarrison.RouteKind.RAMPART
                ? 1
                : settings.localTargetSearchRadius() + 1;
        return commanded.horizontalDistanceSquared(desired)
                <= (long) tolerance * tolerance;
    }

    private void recoverStuckKnight(
            VillageKnightGarrison garrison,
            VillageKnightGarrison.KnightSlot slot,
            Mob knight,
            VillageKnightGarrison.PatrolRoute route,
            Location target,
            long now,
            boolean pathFailed) {
        VillageKnightGarrison.BlockPoint current =
                VillageKnightGarrison.BlockPoint.from(
                        knight.getLocation()
                );
        if (slot.movementSample() == null) {
            slot.updateMovementSample(current, now);
            return;
        }

        long elapsed = now - slot.movementSampleAtMillis();
        double movedSq = horizontalDistanceSquared(
                slot.movementSample(),
                current
        );
        if (movedSq >= MIN_MOVEMENT_SAMPLE_DISTANCE_SQ) {
            slot.updateMovementSample(current, now);
            return;
        }

        boolean stuckLongEnough = elapsed >= settings.stuckMillis();
        boolean hardRecovery = elapsed >= settings.hardRecoveryMillis();
        if (!pathFailed && !stuckLongEnough) {
            return;
        }

        long retryDelay = Math.max(
                MIN_RECOVERY_RETRY_MILLIS,
                Math.min(5_000L, settings.stuckMillis() / 2L)
        );
        if (now - slot.lastRecoveryAttemptAtMillis() < retryDelay) {
            return;
        }
        slot.markRecoveryAttempt(now);

        if (!hardRecovery && hasVisiblePlayerNear(knight.getLocation())) {
            /*
             * On évite d'abord une téléportation visible, mais on ne remet pas
             * à zéro l'échantillon de mouvement. Le délai dur garantit donc
             * qu'un garde observé ne restera jamais bloqué indéfiniment.
             */
            if (stuckLongEnough
                    && route.kind().isPatrol()
                    && route.waypoints().size() > 1
                    && now - route.lastAdvanceAtMillis()
                    >= Math.max(2_000L, settings.stuckMillis() / 2L)) {
                route.advance(now);
                slot.clearNavigationCommand();
                dirty = true;
            }
            return;
        }

        Location safe = findRecoveryLocation(
                knight.getWorld(),
                route,
                target,
                knight.getLocation()
        );
        if (safe == null || !knight.teleport(safe)) {
            slot.clearNavigationCommand();
            return;
        }

        knight.getPathfinder().stopPathfinding();
        VillageKnightGarrison.BlockPoint safePoint =
                VillageKnightGarrison.BlockPoint.from(safe);
        slot.setLastKnownPosition(safePoint);
        slot.clearNavigationCommand();
        slot.resetPathFailures();
        slot.updateMovementSample(safePoint, now);
        dirty = true;
    }

    private Location findRecoveryLocation(
            World world,
            VillageKnightGarrison.PatrolRoute route,
            Location requested,
            Location current) {
        if (world == null || route == null) {
            return null;
        }

        if (route.kind().isSentry()
                || route.kind()
                == VillageKnightGarrison.RouteKind.RAMPART) {
            return nearestSafeRouteLocation(world, route, current);
        }

        VillageKnightGarrison.BlockPoint requestedPoint =
                VillageKnightGarrison.BlockPoint.from(requested);
        Location safe = findSafeLocation(world, requestedPoint, route);
        return safe != null
                ? safe
                : nearestSafeRouteLocation(world, route, current);
    }

    /**
     * Cherche le point praticable de la route le plus proche sans jamais
     * charger de chunk. Cette méthode est rare (chute ou blocage avéré), donc
     * privilégier la sûreté à une heuristique approximative reste peu coûteux.
     */
    private Location nearestSafeRouteLocation(
            World world,
            VillageKnightGarrison.PatrolRoute route,
            Location origin) {
        if (world == null || route == null) {
            return null;
        }

        Location best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (VillageKnightGarrison.BlockPoint waypoint
                : route.waypoints()) {
            if (!isPointChunkLoaded(world, waypoint)) {
                continue;
            }
            Location candidate = findSafeLocation(
                    world,
                    waypoint,
                    route
            );
            if (candidate == null) {
                continue;
            }
            double distanceSq = origin != null
                    && origin.getWorld() == world
                    ? origin.distanceSquared(candidate)
                    : 0.0D;
            if (best == null || distanceSq < bestDistanceSq) {
                best = candidate;
                bestDistanceSq = distanceSq;
            }
        }
        return best;
    }

    private double arrivalRadius(
            VillageKnightGarrison.PatrolRoute route) {
        if (route == null) {
            return settings.waypointArrivalRadius();
        }
        if (route.kind().isSentry()) {
            return settings.sentryReturnRadius();
        }
        if (route.kind()
                == VillageKnightGarrison.RouteKind.RAMPART) {
            return Math.max(
                    1.0D,
                    Math.min(
                            settings.waypointArrivalRadius(),
                            settings.rampartCorridorRadius()
                    )
            );
        }
        return settings.waypointArrivalRadius();
    }

    private Location formationTarget(
            World world,
            VillageKnightGarrison.PatrolRoute route,
            VillageKnightGarrison.KnightSlot slot) {
        VillageKnightGarrison.BlockPoint current =
                route.currentWaypoint();
        VillageKnightGarrison.BlockPoint next =
                route.nextWaypoint();
        Location base = current.toLocation(world);

        if (route.kind()
                != VillageKnightGarrison.RouteKind.URBAN) {
            return base;
        }

        double dx = next.x() - current.x();
        double dz = next.z() - current.z();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001D) {
            dx = 0.0D;
            dz = 1.0D;
            length = 1.0D;
        }
        dx /= length;
        dz /= length;

        int index = slot.formationIndex();
        double side = switch (index) {
            case 0 -> -0.85D;
            case 1 -> 0.85D;
            case 2 -> -0.85D;
            case 3 -> 0.85D;
            default -> 0.0D;
        };
        double rear = switch (index) {
            case 0, 1 -> 0.0D;
            case 2, 3 -> 1.55D;
            default -> 2.40D + (index - 4) * 0.75D;
        };

        double perpendicularX = -dz;
        double perpendicularZ = dx;
        return base.add(
                perpendicularX * side - dx * rear,
                0.0D,
                perpendicularZ * side - dz * rear
        );
    }

    private void scanForThreats(long now) {
        if (scanOrder.isEmpty()) {
            return;
        }

        int scans = Math.min(
                settings.scansPerCycle(),
                scanOrder.size()
        );
        for (int count = 0; count < scans; count++) {
            if (scanCursor >= scanOrder.size()) {
                scanCursor = 0;
            }
            SlotReference reference = scanOrder.get(scanCursor++);
            VillageKnightGarrison.KnightSlot slot =
                    reference.slot();
            if (slot.targetEntityId() != null) {
                continue;
            }
            Mob knight = resolveLiveKnight(
                    reference.garrison(),
                    slot
            );
            if (knight == null) {
                continue;
            }
            LivingEntity threat = findNearestThreat(
                    reference.garrison(),
                    slot,
                    knight
            );
            if (threat != null) {
                assignTarget(
                        reference.garrison(),
                        slot,
                        knight,
                        threat,
                        now
                );
            }
        }
    }

    private LivingEntity findNearestThreat(
            VillageKnightGarrison garrison,
            VillageKnightGarrison.KnightSlot slot,
            Mob knight) {
        VillageKnightGarrison.PatrolRoute route =
                garrison.route(slot.routeId());
        double radius = Math.min(
                settings.detectionRadius(),
                engagementRadius(route)
        );
        LivingEntity nearest = null;
        double nearestDistanceSq = Double.MAX_VALUE;

        Collection<Entity> nearby = knight.getWorld()
                .getNearbyEntities(
                        knight.getLocation(),
                        radius,
                        Math.min(24.0D, Math.max(12.0D, radius)),
                        radius
                );
        for (Entity entity : nearby) {
            if (!(entity instanceof LivingEntity living)
                    || !isHostile(living)
                    || !isWithinCombatLeash(garrison, slot, living)
                    || assignedResponderCount(
                    garrison,
                    living.getUniqueId()
            ) >= settings.maxRespondersPerThreat()) {
                continue;
            }
            double distanceSq = knight.getLocation()
                    .distanceSquared(living.getLocation());
            if (distanceSq < nearestDistanceSq) {
                nearest = living;
                nearestDistanceSq = distanceSq;
            }
        }
        return nearest;
    }

    private boolean assignTarget(
            VillageKnightGarrison garrison,
            VillageKnightGarrison.KnightSlot slot,
            Mob knight,
            LivingEntity target,
            long now) {
        if (!isHostile(target)
                || !isWithinCombatLeash(garrison, slot, target)) {
            return false;
        }
        slot.assignTarget(
                target.getUniqueId(),
                now + TARGET_MEMORY_MILLIS
        );
        slot.clearNavigationCommand();
        knight.getPathfinder().stopPathfinding();
        knight.setTarget(target);
        knight.setAggressive(true);
        return true;
    }

    private void dispatchDefenders(
            LivingEntity protectedEntity,
            LivingEntity threat) {
        if (!isHostile(threat)
                || protectedEntity == null
                || protectedEntity.getWorld()
                != threat.getWorld()) {
            return;
        }

        VillageKnightGarrison garrison =
                findProtectingGarrison(protectedEntity.getLocation());
        if (garrison == null) {
            return;
        }

        UUID threatId = threat.getUniqueId();
        int alreadyAssigned = assignedResponderCount(
                garrison,
                threatId
        );
        int remaining = settings.maxRespondersPerThreat()
                - alreadyAssigned;
        if (remaining <= 0) {
            return;
        }

        double assistanceSq = settings.assistanceRadius()
                * settings.assistanceRadius();
        List<AvailableKnight> candidates = new ArrayList<>();
        for (VillageKnightGarrison.KnightSlot slot
                : garrison.slots()) {
            if (threatId.equals(slot.targetEntityId())
                    || !isWithinCombatLeash(garrison, slot, threat)) {
                continue;
            }
            Mob knight = resolveLiveKnight(garrison, slot);
            if (knight == null
                    || !knight.getWorld()
                    .equals(protectedEntity.getWorld())) {
                continue;
            }
            double distanceSq = knight.getLocation()
                    .distanceSquared(protectedEntity.getLocation());
            if (distanceSq <= assistanceSq) {
                candidates.add(new AvailableKnight(
                        slot,
                        knight,
                        responsePriority(garrison, slot),
                        distanceSq
                ));
            }
        }

        /*
         * Les patrouilles mobiles sont engagées avant les postes fixes. Une
         * sentinelle locale reste toutefois libre d'acquérir elle-même toute
         * menace entrant dans son rayon, tandis qu'un archer garde son rempart.
         */
        candidates.sort(Comparator
                .comparingInt((AvailableKnight candidate) ->
                        candidate.slot().targetEntityId() == null
                                ? 0 : 1)
                .thenComparingInt(AvailableKnight::responsePriority)
                .thenComparingDouble(
                        AvailableKnight::distanceSquared
                ));

        int assigned = 0;
        long now = System.currentTimeMillis();
        for (AvailableKnight candidate : candidates) {
            if (assigned >= remaining) {
                break;
            }
            if (assignTarget(
                    garrison,
                    candidate.slot(),
                    candidate.knight(),
                    threat,
                    now
            )) {
                assigned++;
            }
        }
    }

    private int responsePriority(
            VillageKnightGarrison garrison,
            VillageKnightGarrison.KnightSlot slot) {
        VillageKnightGarrison.PatrolRoute route =
                garrison.route(slot.routeId());
        if (route == null) {
            return 3;
        }
        if (route.kind() == VillageKnightGarrison.RouteKind.URBAN) {
            return 0;
        }
        if (route.kind().isSentry()) {
            return 1;
        }
        return 2;
    }

    /**
     * Compte uniquement les défenseurs actuellement chargés et vivants. Une
     * patrouille suspendue dans un chunk déchargé ne doit pas bloquer l'envoi
     * de renforts disponibles.
     */
    private int assignedResponderCount(
            VillageKnightGarrison garrison,
            UUID threatId) {
        if (garrison == null || threatId == null) {
            return 0;
        }
        int count = 0;
        for (VillageKnightGarrison.KnightSlot slot
                : garrison.slots()) {
            if (!threatId.equals(slot.targetEntityId())) {
                continue;
            }
            if (resolveLiveKnight(garrison, slot) != null) {
                count++;
            }
        }
        return count;
    }

    private VillageKnightGarrison findProtectingGarrison(
            Location location) {
        VillageKnightGarrison nearest = null;
        double nearestDistanceSq = Double.MAX_VALUE;
        for (VillageKnightGarrison garrison
                : garrisons.values()) {
            if (!garrison.containsHorizontally(
                    location,
                    settings.protectionMargin()
            )) {
                continue;
            }
            double distanceSq =
                    garrison.centerHorizontalDistanceSquared(location);
            if (distanceSq < nearestDistanceSq) {
                nearest = garrison;
                nearestDistanceSq = distanceSq;
            }
        }
        return nearest;
    }

    /**
     * Applique une laisse adaptée au rôle au lieu d'un unique grand rectangle
     * autour du village. Un poste fixe protège sa zone, un archer protège le
     * corridor du rempart et seules les patrouilles couvrent toute la cité.
     */
    private boolean isWithinCombatLeash(
            VillageKnightGarrison garrison,
            VillageKnightGarrison.KnightSlot slot,
            LivingEntity entity) {
        if (garrison == null || slot == null || entity == null
                || !garrison.worldId().equals(
                entity.getWorld().getUID()
        )) {
            return false;
        }
        VillageKnightGarrison.PatrolRoute route =
                garrison.route(slot.routeId());
        if (route == null) {
            return false;
        }

        Location location = entity.getLocation();
        if (route.kind().isSentry()) {
            VillageKnightGarrison.BlockPoint post =
                    route.currentWaypoint();
            double radius = settings.sentryEngagementRadius();
            return horizontalDistanceSquared(post, location)
                    <= radius * radius
                    && Math.abs(location.getY() - post.y())
                    <= Math.max(12.0D, radius);
        }
        if (route.kind()
                == VillageKnightGarrison.RouteKind.RAMPART) {
            double radius = settings.rampartEngagementRadius();
            return distanceSquaredToRouteHorizontal(route, location)
                    <= radius * radius
                    && verticalDistanceToRoute(route, location)
                    <= 24.0D;
        }

        double margin = Math.min(
                settings.combatLeashRadius(),
                settings.urbanCombatMargin()
        );
        return garrison.containsHorizontally(location, margin);
    }

    private double engagementRadius(
            VillageKnightGarrison.PatrolRoute route) {
        if (route == null) {
            return settings.detectionRadius();
        }
        if (route.kind().isSentry()) {
            return settings.sentryEngagementRadius();
        }
        if (route.kind()
                == VillageKnightGarrison.RouteKind.RAMPART) {
            return settings.rampartEngagementRadius();
        }
        return settings.detectionRadius();
    }

    private boolean isWithinRampartCorridor(
            VillageKnightGarrison.PatrolRoute route,
            Location location) {
        if (route == null || location == null
                || route.kind()
                != VillageKnightGarrison.RouteKind.RAMPART) {
            return false;
        }
        double radius = settings.rampartCorridorRadius();
        return distanceSquaredToRouteHorizontal(route, location)
                <= radius * radius
                && verticalDistanceToRoute(route, location)
                <= RAMPART_VERTICAL_TOLERANCE;
    }

    private double verticalDistanceToRoute(
            VillageKnightGarrison.PatrolRoute route,
            Location location) {
        if (route == null || location == null
                || route.waypoints().isEmpty()) {
            return Double.MAX_VALUE;
        }
        double nearest = Double.MAX_VALUE;
        for (VillageKnightGarrison.BlockPoint point
                : route.waypoints()) {
            nearest = Math.min(
                    nearest,
                    Math.abs(location.getY() - point.y())
            );
        }
        return nearest;
    }

    private double distanceSquaredToRouteHorizontal(
            VillageKnightGarrison.PatrolRoute route,
            Location location) {
        if (route == null || location == null
                || route.waypoints().isEmpty()) {
            return Double.MAX_VALUE;
        }
        List<VillageKnightGarrison.BlockPoint> points =
                route.waypoints();
        if (points.size() == 1) {
            return horizontalDistanceSquared(points.get(0), location);
        }

        double nearest = Double.MAX_VALUE;
        for (int index = 0; index < points.size(); index++) {
            VillageKnightGarrison.BlockPoint from =
                    points.get(index);
            VillageKnightGarrison.BlockPoint to =
                    points.get((index + 1) % points.size());
            nearest = Math.min(
                    nearest,
                    pointToSegmentDistanceSquared(
                            location.getX(),
                            location.getZ(),
                            from.x() + 0.5D,
                            from.z() + 0.5D,
                            to.x() + 0.5D,
                            to.z() + 0.5D
                    )
            );
        }
        return nearest;
    }

    private double pointToSegmentDistanceSquared(
            double pointX,
            double pointZ,
            double fromX,
            double fromZ,
            double toX,
            double toZ) {
        double segmentX = toX - fromX;
        double segmentZ = toZ - fromZ;
        double lengthSq = segmentX * segmentX + segmentZ * segmentZ;
        if (lengthSq < 0.000_001D) {
            double dx = pointX - fromX;
            double dz = pointZ - fromZ;
            return dx * dx + dz * dz;
        }

        double projection = (
                (pointX - fromX) * segmentX
                        + (pointZ - fromZ) * segmentZ
        ) / lengthSq;
        double clamped = Math.max(0.0D, Math.min(1.0D, projection));
        double nearestX = fromX + clamped * segmentX;
        double nearestZ = fromZ + clamped * segmentZ;
        double dx = pointX - nearestX;
        double dz = pointZ - nearestZ;
        return dx * dx + dz * dz;
    }

    private double horizontalDistanceSquared(
            VillageKnightGarrison.BlockPoint point,
            Location location) {
        if (point == null || location == null) {
            return Double.MAX_VALUE;
        }
        double dx = point.x() + 0.5D - location.getX();
        double dz = point.z() + 0.5D - location.getZ();
        return dx * dx + dz * dz;
    }

    private void reconcileOrRespawn(
            VillageKnightGarrison garrison,
            VillageKnightGarrison.KnightSlot slot,
            World world,
            long now) {
        UUID expectedEntityId = slot.entityId();
        VillageKnightGarrison.BlockPoint lastPosition =
                slot.lastKnownPosition();
        if (lastPosition == null) {
            VillageKnightGarrison.PatrolRoute route =
                    garrison.route(slot.routeId());
            lastPosition = route != null
                    ? route.currentWaypoint()
                    : garrison.center();
            slot.setLastKnownPosition(lastPosition);
        }

        if (expectedEntityId != null) {
            if (!isPointChunkLoaded(world, lastPosition)) {
                return;
            }

            Mob adopted = findLoadedEntityForSlot(
                    world,
                    garrison,
                    slot,
                    lastPosition
            );
            if (adopted != null) {
                adoptEntity(adopted);
                return;
            }

            if (slot.incrementMissingLoadedChecks()
                    < settings.missingEntityLoadedChecks()) {
                return;
            }

            /*
             * Le chunk est resté chargé pendant plusieurs contrôles sans
             * contenir l'entité. Ce n'est donc plus un simple déchargement.
             */
            slotsByEntity.remove(expectedEntityId);
            slot.setEntityId(null);
            slot.setRespawnAtMillis(Math.max(
                    slot.respawnAtMillis(),
                    now
            ));
            dirty = true;
        }

        if (now >= slot.respawnAtMillis()) {
            spawnSlot(garrison, slot, now);
        }
    }

    private Mob findLoadedEntityForSlot(
            World world,
            VillageKnightGarrison garrison,
            VillageKnightGarrison.KnightSlot slot,
            VillageKnightGarrison.BlockPoint around) {
        int chunkX = around.x() >> 4;
        int chunkZ = around.z() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int currentX = chunkX + dx;
                int currentZ = chunkZ + dz;
                if (!world.isChunkLoaded(currentX, currentZ)) {
                    continue;
                }
                for (Entity entity : world.getChunkAt(
                        currentX,
                        currentZ
                ).getEntities()) {
                    if (isVillageKnight(entity)
                            && villageIdOf(entity)
                            == garrison.villageId()
                            && slot.slotId().equals(slotIdOf(entity))
                            && entity instanceof Mob mob
                            && !mob.isDead()
                            && mob.isValid()) {
                        return mob;
                    }
                }
            }
        }
        return null;
    }

    private boolean spawnSlot(
            VillageKnightGarrison garrison,
            VillageKnightGarrison.KnightSlot slot,
            long now) {
        if (shuttingDown || !settings.enabled()) {
            return false;
        }

        World world = Bukkit.getWorld(garrison.worldId());
        if (world == null
                || world.getDifficulty() == Difficulty.PEACEFUL) {
            return false;
        }
        VillageKnightGarrison.PatrolRoute route =
                garrison.route(slot.routeId());
        if (route == null) {
            return false;
        }

        VillageKnightGarrison.BlockPoint preferred =
                VillageKnightGarrison.BlockPoint.from(
                        formationTarget(world, route, slot)
                );
        if (!isPointChunkLoaded(world, preferred)) {
            return false;
        }

        Location spawnLocation = findSafeLocation(
                world,
                preferred,
                route
        );
        if (spawnLocation == null) {
            return false;
        }

        Mob knight = null;
        try {
            knight = slot.unitKind()
                    == VillageKnightGarrison.UnitKind.ARCHER
                    ? world.spawn(spawnLocation, Skeleton.class)
                    : world.spawn(spawnLocation, Husk.class);
            configureKnight(garrison, slot, knight, true);
            slot.setEntityId(knight.getUniqueId());
            slot.setRespawnAtMillis(0L);
            slot.setLastKnownPosition(
                    VillageKnightGarrison.BlockPoint.from(
                            knight.getLocation()
                    )
            );
            slot.clearTarget();
            slot.clearNavigationCommand();
            slot.updateMovementSample(
                    slot.lastKnownPosition(),
                    now
            );
            slot.markPositionPersisted(now);
            slotsByEntity.put(
                    knight.getUniqueId(),
                    new SlotReference(garrison, slot)
            );
            lastEquipmentRepairAt.put(knight.getUniqueId(), now);
            dirty = true;
            return true;
        } catch (RuntimeException exception) {
            if (knight != null) {
                knight.remove();
            }
            slot.setEntityId(null);
            slot.setRespawnAtMillis(
                    now + Math.max(
                            5_000L,
                            settings.respawnDelayMillis()
                    )
            );
            dirty = true;
            requestUrgentSave();
            plugin.getLogger().log(
                    Level.WARNING,
                    "[Chevaliers] Apparition impossible pour le créneau "
                            + slot.slotId() + ".",
                    exception
            );
            return false;
        }
    }

    private void configureKnight(
            VillageKnightGarrison garrison,
            VillageKnightGarrison.KnightSlot slot,
            Mob knight,
            boolean heal) {
        knight.setCustomName(ChatColor.DARK_AQUA + KNIGHT_NAME);
        knight.setCustomNameVisible(true);
        knight.setAI(true);
        knight.setAware(true);
        if (heal) {
            /*
             * Une réparation périodique ne doit pas interrompre un combat en
             * cours. Le ciblage n'est réinitialisé qu'à l'apparition.
             */
            knight.setAggressive(false);
            knight.setTarget(null);
        }
        knight.setCollidable(false);
        knight.setPersistent(true);
        knight.setRemoveWhenFarAway(false);
        knight.setCanPickupItems(false);
        knight.addScoreboardTag(SCOREBOARD_TAG);

        knight.getPathfinder().setCanOpenDoors(true);
        knight.getPathfinder().setCanPassDoors(true);
        knight.getPathfinder().setCanFloat(true);

        if (knight instanceof Husk husk) {
            husk.setAdult();
            husk.setAgeLock(true);
            husk.setCanBreakDoors(false);
            husk.stopDrowning();
            suppressReinforcements(husk);
        }

        PersistentDataContainer data =
                knight.getPersistentDataContainer();
        data.set(
                Keys.villageKnightType(),
                PersistentDataType.STRING,
                KNIGHT_TYPE
        );
        data.set(
                Keys.villageKnightVillage(),
                PersistentDataType.INTEGER,
                garrison.villageId()
        );
        data.set(
                Keys.villageKnightSlot(),
                PersistentDataType.STRING,
                slot.slotId().toString()
        );
        data.set(
                Keys.villageKnightRole(),
                PersistentDataType.STRING,
                slot.unitKind().name()
        );
        VillageEntityManager.tagEntity(
                knight,
                plugin,
                garrison.villageId()
        );

        AttributeInstance maxHealth = setAttribute(
                knight,
                Attribute.MAX_HEALTH,
                settings.maxHealth(),
                true
        );
        setAttribute(
                knight,
                Attribute.ATTACK_DAMAGE,
                slot.unitKind()
                        == VillageKnightGarrison.UnitKind.ARCHER
                        ? settings.arrowDamageCap()
                        : settings.meleeDamageCap(),
                false
        );
        setAttribute(
                knight,
                Attribute.MOVEMENT_SPEED,
                settings.movementSpeed(),
                false
        );
        setAttribute(
                knight,
                Attribute.FOLLOW_RANGE,
                settings.followRange(),
                false
        );
        setAttribute(
                knight,
                Attribute.KNOCKBACK_RESISTANCE,
                settings.knockbackResistance(),
                false
        );
        if (heal && maxHealth != null) {
            knight.setHealth(Math.min(
                    maxHealth.getValue(),
                    settings.maxHealth()
            ));
        }

        equipKnight(knight, slot.unitKind());
    }

    private AttributeInstance setAttribute(
            LivingEntity entity,
            Attribute attribute,
            double value,
            boolean required) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            if (required) {
                throw new IllegalStateException(
                        "Attribut absent sur le chevalier : "
                                + attribute
                );
            }
            return null;
        }
        instance.setBaseValue(value);
        return instance;
    }

    private void suppressReinforcements(Husk husk) {
        AttributeInstance chance =
                husk.getAttribute(Attribute.SPAWN_REINFORCEMENTS);
        if (chance == null) {
            return;
        }
        chance.setBaseValue(0.0D);
        for (AttributeModifier modifier
                : new ArrayList<>(chance.getModifiers())) {
            chance.removeModifier(modifier);
        }
    }

    private void equipKnight(
            Mob knight,
            VillageKnightGarrison.UnitKind kind) {
        EntityEquipment equipment = knight.getEquipment();
        if (equipment == null) {
            throw new IllegalStateException(
                    "Le chevalier ne possède pas d'équipement."
            );
        }

        equipment.setHelmet(plainUnbreakable(
                Material.NETHERITE_HELMET
        ));
        equipment.setChestplate(plainUnbreakable(
                Material.NETHERITE_CHESTPLATE
        ));
        equipment.setLeggings(plainUnbreakable(
                Material.NETHERITE_LEGGINGS
        ));
        equipment.setBoots(plainUnbreakable(
                Material.NETHERITE_BOOTS
        ));
        equipment.setItemInMainHand(plainUnbreakable(
                kind == VillageKnightGarrison.UnitKind.ARCHER
                        ? Material.BOW
                        : Material.NETHERITE_SWORD
        ));
        equipment.setItemInOffHand(null);

        equipment.setHelmetDropChance(0.0F);
        equipment.setChestplateDropChance(0.0F);
        equipment.setLeggingsDropChance(0.0F);
        equipment.setBootsDropChance(0.0F);
        equipment.setItemInMainHandDropChance(0.0F);
        equipment.setItemInOffHandDropChance(0.0F);
    }

    private ItemStack plainUnbreakable(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            /*
             * « Incassable » n'est pas un enchantement : l'équipement reste
             * bien non enchanté tout en conservant son rôle visuel à long terme.
             */
            meta.setUnbreakable(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void repairKnightPeriodically(
            VillageKnightGarrison garrison,
            VillageKnightGarrison.KnightSlot slot,
            Mob knight,
            long now) {
        long lastRepair = lastEquipmentRepairAt.getOrDefault(
                knight.getUniqueId(),
                0L
        );
        if (now - lastRepair < EQUIPMENT_REPAIR_INTERVAL_MILLIS) {
            return;
        }
        lastEquipmentRepairAt.put(knight.getUniqueId(), now);

        try {
            configureKnight(garrison, slot, knight, false);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "[Chevaliers] Réparation impossible pour "
                            + knight.getUniqueId() + ".",
                    exception
            );
        }
    }

    private Mob resolveLiveKnight(
            VillageKnightGarrison garrison,
            VillageKnightGarrison.KnightSlot slot) {
        UUID entityId = slot.entityId();
        if (entityId == null) {
            return null;
        }

        Entity entity = Bukkit.getEntity(entityId);
        if (!(entity instanceof Mob mob)
                || mob.isDead()
                || !mob.isValid()
                || !isVillageKnight(mob)
                || villageIdOf(mob) != garrison.villageId()
                || !slot.slotId().equals(slotIdOf(mob))) {
            return null;
        }

        if (!garrison.worldId().equals(mob.getWorld().getUID())) {
            /*
             * Un portail ou un autre plugin ne doit jamais détacher un garde de
             * sa ville. L'entité déplacée est supprimée, puis le créneau est
             * réconcilié dans son monde d'origine.
             */
            mob.remove();
            slotsByEntity.remove(entityId);
            lastEquipmentRepairAt.remove(entityId);
            slot.setEntityId(null);
            slot.setRespawnAtMillis(System.currentTimeMillis());
            slot.clearTarget();
            dirty = true;
            return null;
        }

        slotsByEntity.putIfAbsent(
                entityId,
                new SlotReference(garrison, slot)
        );
        slot.resetMissingLoadedChecks();
        return mob;
    }

    private void reconcileInitiallyLoadedEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (removeLegacyGateGuard(entity)) {
                    continue;
                }
                if (isVillageKnight(entity)) {
                    adoptEntity(entity);
                }
            }
        }
    }

    private void adoptEntity(Entity entity) {
        if (!(entity instanceof Mob knight)
                || knight.isDead()
                || !knight.isValid()
                || !isVillageKnight(knight)) {
            return;
        }

        int villageId = villageIdOf(knight);
        UUID slotId = slotIdOf(knight);
        VillageKnightGarrison garrison =
                garrisons.get(villageId);
        VillageKnightGarrison.KnightSlot slot =
                garrison != null ? garrison.slot(slotId) : null;

        if (garrison == null || slot == null
                || !isExpectedEntityClass(knight, slot.unitKind())
                || !garrison.worldId().equals(
                knight.getWorld().getUID()
        )) {
            if (writable) {
                knight.remove();
            }
            return;
        }

        long now = System.currentTimeMillis();
        UUID expectedEntityId = slot.entityId();
        if (expectedEntityId != null
                && !expectedEntityId.equals(knight.getUniqueId())) {
            /*
             * L'UUID sauvegardé est la source de vérité. Remplacer l'entité
             * canonique par une copie chargée depuis un autre chunk pourrait
             * faire alterner deux doublons à chaque chargement.
             */
            knight.remove();
            return;
        }
        if (expectedEntityId == null
                && slot.respawnAtMillis() > now) {
            /*
             * Une copie résiduelle ne doit jamais court-circuiter la minute de
             * réapparition enregistrée après une mort.
             */
            knight.remove();
            return;
        }

        VillageKnightGarrison.BlockPoint previousPosition =
                slot.lastKnownPosition();
        long previousRespawnAt = slot.respawnAtMillis();
        try {
            configureKnight(garrison, slot, knight, false);
            /*
             * Une entité adoptée après chargement ne doit conserver aucune
             * cible vanilla acquise pendant la phase de désérialisation.
             */
            knight.setAggressive(false);
            knight.setTarget(null);
            slot.clearTarget();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "[Chevaliers] Entité persistante invalide supprimée : "
                            + knight.getUniqueId() + ".",
                    exception
            );
            knight.remove();
            slot.setEntityId(null);
            slot.setRespawnAtMillis(now);
            dirty = true;
            return;
        }

        VillageKnightGarrison.BlockPoint currentPosition =
                VillageKnightGarrison.BlockPoint.from(
                        knight.getLocation()
                );
        slot.setEntityId(knight.getUniqueId());
        slot.setLastKnownPosition(currentPosition);
        slot.setRespawnAtMillis(0L);
        slot.updateMovementSample(currentPosition, now);
        slot.markPositionPersisted(now);
        slotsByEntity.put(
                knight.getUniqueId(),
                new SlotReference(garrison, slot)
        );
        lastEquipmentRepairAt.put(knight.getUniqueId(), now);

        if (expectedEntityId == null
                || previousRespawnAt != 0L
                || previousPosition == null
                || !previousPosition.equals(currentPosition)) {
            dirty = true;
        }
    }

    private boolean isExpectedEntityClass(
            Mob mob,
            VillageKnightGarrison.UnitKind kind) {
        return kind == VillageKnightGarrison.UnitKind.ARCHER
                ? mob instanceof Skeleton
                : mob instanceof Husk;
    }

    private void updateLastKnownPosition(
            VillageKnightGarrison.KnightSlot slot,
            Mob knight,
            long now) {
        if (now - slot.lastPositionPersistAtMillis()
                < POSITION_SAVE_INTERVAL_MILLIS) {
            return;
        }
        VillageKnightGarrison.BlockPoint current =
                VillageKnightGarrison.BlockPoint.from(
                        knight.getLocation()
                );
        VillageKnightGarrison.BlockPoint previous =
                slot.lastKnownPosition();
        slot.markPositionPersisted(now);
        if (previous == null
                || previous.horizontalDistanceSquared(current) >= 16L
                || previous.y() != current.y()) {
            slot.setLastKnownPosition(current);
            dirty = true;
        }
    }

    private void updateAllLoadedPositions() {
        for (VillageKnightGarrison garrison
                : garrisons.values()) {
            for (VillageKnightGarrison.KnightSlot slot
                    : garrison.slots()) {
                Mob knight = resolveLiveKnight(garrison, slot);
                if (knight != null) {
                    slot.setLastKnownPosition(
                            VillageKnightGarrison.BlockPoint.from(
                                    knight.getLocation()
                            )
                    );
                    dirty = true;
                }
            }
        }
    }

    /**
     * Cherche une destination praticable en privilégiant tous les voisins du
     * même étage avant d'envisager un dénivelé. Les sentinelles et les archers
     * ne changent jamais d'étage : cela évite précisément les gardes déplacés
     * au pied d'un mur ou sur le toit d'un élément de décor.
     */
    private Location findSafeLocation(
            World world,
            VillageKnightGarrison.BlockPoint preferred,
            VillageKnightGarrison.PatrolRoute route) {
        if (world == null || preferred == null || route == null) {
            return null;
        }

        boolean rampart = route.kind()
                == VillageKnightGarrison.RouteKind.RAMPART;
        boolean fixedFloor = rampart || route.kind().isSentry();
        int searchRadius = rampart
                ? Math.min(1, settings.localTargetSearchRadius())
                : settings.localTargetSearchRadius();
        int[] verticalOffsets = fixedFloor
                ? new int[]{0}
                : new int[]{0, 1, -1};
        List<int[]> horizontalOffsets =
                safeHorizontalOffsets(searchRadius);

        /*
         * La boucle verticale est volontairement extérieure : on teste d'abord
         * toute la surface au niveau demandé, puis seulement les niveaux +1/-1.
         */
        for (int verticalOffset : verticalOffsets) {
            int feetY = preferred.y() + verticalOffset;
            if (feetY <= world.getMinHeight()
                    || feetY + 1 >= world.getMaxHeight()) {
                continue;
            }

            for (int[] horizontal : horizontalOffsets) {
                int x = preferred.x() + horizontal[0];
                int z = preferred.z() + horizontal[1];
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }

                Location candidate = new Location(
                        world,
                        x + 0.5D,
                        feetY,
                        z + 0.5D
                );
                if (rampart
                        && (!isWithinRampartCorridor(route, candidate)
                        || feetY != preferred.y())) {
                    continue;
                }

                Block ground = world.getBlockAt(x, feetY - 1, z);
                Block feet = world.getBlockAt(x, feetY, z);
                Block head = world.getBlockAt(x, feetY + 1, z);
                if (isSafeBody(ground, feet, head)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * Produit des décalages ordonnés par distance, puis de façon stable. Le
     * rayon est petit et borné par la configuration, l'allocation reste donc
     * négligeable et n'est effectuée que lors d'un nouvel ordre de navigation.
     */
    private List<int[]> safeHorizontalOffsets(int radius) {
        int safeRadius = Math.max(0, Math.min(4, radius));
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -safeRadius; dx <= safeRadius; dx++) {
            for (int dz = -safeRadius; dz <= safeRadius; dz++) {
                offsets.add(new int[]{dx, dz});
            }
        }
        offsets.sort(Comparator
                .comparingInt((int[] offset) ->
                        offset[0] * offset[0] + offset[1] * offset[1])
                .thenComparingInt(offset -> Math.abs(offset[0])
                        + Math.abs(offset[1]))
                .thenComparingInt(offset -> offset[0])
                .thenComparingInt(offset -> offset[1]));
        return offsets;
    }

    private boolean isSafeBody(Block ground,
                               Block feet,
                               Block head) {
        return ground != null
                && feet != null
                && head != null
                && ground.getType().isSolid()
                && !DANGEROUS_GROUND.contains(ground.getType())
                && !DANGEROUS_GROUND.contains(feet.getType())
                && !DANGEROUS_GROUND.contains(head.getType())
                && feet.isPassable()
                && head.isPassable()
                && !feet.isLiquid()
                && !head.isLiquid();
    }

    private boolean isPointChunkLoaded(
            World world,
            VillageKnightGarrison.BlockPoint point) {
        return world != null
                && point != null
                && world.isChunkLoaded(
                point.x() >> 4,
                point.z() >> 4
        );
    }

    private boolean hasVisiblePlayerNear(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        double radiusSq = TELEPORT_VISIBILITY_RADIUS
                * TELEPORT_VISIBILITY_RADIUS;
        for (Player player : location.getWorld().getPlayers()) {
            if (player.isOnline()
                    && !player.isDead()
                    && player.getLocation()
                    .distanceSquared(location) <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    private double horizontalDistanceSquared(
            VillageKnightGarrison.BlockPoint first,
            VillageKnightGarrison.BlockPoint second) {
        if (first == null || second == null) {
            return Double.MAX_VALUE;
        }
        double dx = (double) first.x() - second.x();
        double dz = (double) first.z() - second.z();
        return dx * dx + dz * dz;
    }

    private void rebuildScanOrder() {
        scanOrder.clear();
        slotsByEntity.clear();
        for (VillageKnightGarrison garrison
                : garrisons.values()) {
            for (VillageKnightGarrison.KnightSlot slot
                    : garrison.slots()) {
                SlotReference reference =
                        new SlotReference(garrison, slot);
                scanOrder.add(reference);
                if (slot.entityId() != null) {
                    slotsByEntity.put(
                            slot.entityId(),
                            reference
                    );
                }
            }
        }
        if (scanCursor >= scanOrder.size()) {
            scanCursor = 0;
        }
    }

    private void saveIfDirty() {
        if (!dirty || !writable) {
            return;
        }
        ensurePrimaryThread();
        if (store.save(garrisons.values())) {
            dirty = false;
        }
    }

    private void requestUrgentSave() {
        if (!writable || shuttingDown || urgentSaveTask != null) {
            return;
        }
        urgentSaveTask = Bukkit.getScheduler().runTask(
                plugin,
                () -> {
                    urgentSaveTask = null;
                    saveIfDirty();
                }
        );
    }

    private void cancelTask(BukkitTask task) {
        if (task != null) {
            try {
                task.cancel();
            } catch (RuntimeException ignored) {
                // L'arrêt doit continuer même si le scheduler est déjà fermé.
            }
        }
    }

    private void ensurePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "VillageKnightManager doit être utilisé sur le thread serveur."
            );
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onEntityTargets(
            EntityTargetLivingEntityEvent event) {
        LivingEntity target = event.getTarget();
        Entity source = event.getEntity();

        if (isVillageKnight(source)) {
            SlotReference reference = slotsByEntity.get(
                    source.getUniqueId()
            );
            if (reference == null) {
                reference = referenceFromMarker(source);
            }
            boolean allowed = reference != null
                    && target != null
                    && isHostile(target)
                    && isWithinCombatLeash(
                    reference.garrison(),
                    reference.slot(),
                    target
            );
            if (!allowed) {
                event.setCancelled(true);
                if (source instanceof Mob mob) {
                    mob.setTarget(null);
                }
                if (reference != null) {
                    reference.slot().clearTarget();
                }
            }
            return;
        }

        if (target != null
                && isVillageKnight(target)
                && (source instanceof IronGolem
                || source instanceof Snowman
                || isRoyalGuard(source))) {
            event.setCancelled(true);
            if (source instanceof Mob mob) {
                mob.setTarget(null);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onHostileTargetsProtectedEntity(
            EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity threat)
                || !isHostile(threat)) {
            return;
        }
        LivingEntity target = event.getTarget();
        if (isProtectedEntity(target)) {
            dispatchDefenders(target, threat);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onFriendlyDamage(
            EntityDamageByEntityEvent event) {
        LivingEntity attacker = resolveLivingAttacker(event);
        Entity victim = event.getEntity();

        if (isVillageKnight(victim)) {
            if (attacker instanceof IronGolem
                    || attacker instanceof Snowman
                    || isRoyalGuard(attacker)
                    || isVillageKnight(attacker)) {
                event.setCancelled(true);
                clearTargetIfMatching(attacker, victim);
                return;
            }
            if (isHostile(attacker)) {
                double multiplied = event.getDamage()
                        * settings.incomingHostileDamageMultiplier();
                event.setDamage(Math.min(40.0D, multiplied));
            }
        }

        if (isVillageKnight(attacker)) {
            if (!(victim instanceof LivingEntity livingVictim)
                    || !isHostile(livingVictim)) {
                event.setCancelled(true);
                clearTargetIfMatching(attacker, victim);
                return;
            }

            double cap = event.getDamager() instanceof Projectile
                    ? settings.arrowDamageCap()
                    : settings.meleeDamageCap();
            event.setDamage(Math.min(event.getDamage(), cap));
        }

        if (isRoyalGuard(attacker)
                && isVillageKnight(victim)) {
            event.setCancelled(true);
            clearTargetIfMatching(attacker, victim);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onProtectedEntityDamaged(
            EntityDamageByEntityEvent event) {
        LivingEntity attacker = resolveLivingAttacker(event);
        if (!isHostile(attacker)) {
            return;
        }

        if (event.getEntity() instanceof LivingEntity victim
                && isProtectedEntity(victim)) {
            dispatchDefenders(victim, attacker);
        }
    }

    /**
     * Une chute du chemin de ronde ne doit pas tuer un archer juste avant sa
     * remise en place par la maintenance. Les autres gardes conservent les
     * dégâts vanilla normaux.
     */
    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onKnightFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL
                || !isVillageKnight(event.getEntity())) {
            return;
        }
        SlotReference reference = slotsByEntity.get(
                event.getEntity().getUniqueId()
        );
        if (reference == null) {
            reference = referenceFromMarker(event.getEntity());
        }
        VillageKnightGarrison.PatrolRoute route =
                reference != null
                        ? reference.garrison().route(
                        reference.slot().routeId()
                )
                        : null;
        if (route != null
                && route.kind()
                == VillageKnightGarrison.RouteKind.RAMPART) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onKnightUsesPortal(EntityPortalEvent event) {
        if (isVillageKnight(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onKnightTransforms(EntityTransformEvent event) {
        if (isVillageKnight(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onKnightChangesBlock(EntityChangeBlockEvent event) {
        if (isVillageKnight(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onKnightInteractsWithBlock(
            EntityInteractEvent event) {
        if (isVillageKnight(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onKnightDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!isVillageKnight(entity)) {
            return;
        }

        event.getDrops().clear();
        event.setDroppedExp(0);

        SlotReference reference =
                slotsByEntity.remove(entity.getUniqueId());
        if (reference == null) {
            reference = referenceFromMarker(entity);
        }
        lastEquipmentRepairAt.remove(entity.getUniqueId());
        if (reference == null) {
            return;
        }

        VillageKnightGarrison.KnightSlot slot =
                reference.slot();
        slot.setLastKnownPosition(
                VillageKnightGarrison.BlockPoint.from(
                        entity.getLocation()
                )
        );
        slot.setEntityId(null);
        slot.setRespawnAtMillis(
                System.currentTimeMillis()
                        + settings.respawnDelayMillis()
        );
        slot.clearTarget();
        dirty = true;

        /*
         * Une mort est rare mais son échéance est critique : l'écriture
         * synchrone évite qu'un arrêt entre deux ticks annule la minute prévue.
         */
        saveIfDirty();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (removeLegacyGateGuard(entity)) {
                continue;
            }
            if (isVillageKnight(entity)) {
                adoptEntity(entity);
            }
        }
        if (dirty) {
            requestUrgentSave();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (!isVillageKnight(entity)) {
                continue;
            }
            SlotReference reference =
                    slotsByEntity.get(entity.getUniqueId());
            if (reference != null) {
                reference.slot().setLastKnownPosition(
                        VillageKnightGarrison.BlockPoint.from(
                                entity.getLocation()
                        )
                );
                dirty = true;
            }
        }
    }

    private SlotReference referenceFromMarker(Entity entity) {
        int villageId = villageIdOf(entity);
        UUID slotId = slotIdOf(entity);
        VillageKnightGarrison garrison =
                garrisons.get(villageId);
        VillageKnightGarrison.KnightSlot slot =
                garrison != null ? garrison.slot(slotId) : null;
        return slot != null
                ? new SlotReference(garrison, slot)
                : null;
    }

    private void clearTargetIfMatching(
            LivingEntity attacker,
            Entity victim) {
        if (!(attacker instanceof Mob mob)
                || victim == null
                || mob.getTarget() == null) {
            return;
        }
        if (mob.getTarget().getUniqueId()
                .equals(victim.getUniqueId())) {
            mob.setTarget(null);
            if (isVillageKnight(mob)) {
                SlotReference reference =
                        slotsByEntity.get(mob.getUniqueId());
                if (reference != null) {
                    reference.slot().clearTarget();
                }
            }
        }
    }

    private LivingEntity resolveLivingAttacker(
            EntityDamageByEntityEvent event) {
        DamageSource source = event.getDamageSource();
        if (source != null
                && source.getCausingEntity()
                instanceof LivingEntity living) {
            return living;
        }

        Entity damager = event.getDamager();
        if (damager instanceof LivingEntity living) {
            return living;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    private boolean isProtectedEntity(LivingEntity entity) {
        return entity instanceof Player
                || entity instanceof Villager
                || entity instanceof WanderingTrader
                || isVillageKnight(entity)
                || isRoyalGuard(entity);
    }

    private boolean isHostile(LivingEntity entity) {
        return entity instanceof Enemy
                && entity.isValid()
                && !entity.isDead()
                && !entity.isInvulnerable()
                && !isVillageKnight(entity)
                && !isRoyalGuard(entity);
    }

    /**
     * Retire les anciens golems créés par GateGuardManager. Cette ancienne
     * garnison parallèle n'était ni persistante ni coordonnée avec les
     * chevaliers et provoquait des doublons aux portes.
     */
    private boolean removeLegacyGateGuard(Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            Set<String> tags = entity.getScoreboardTags();
            if (tags != null && tags.contains(LEGACY_GATE_GUARD_TAG)) {
                entity.remove();
                return true;
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "[Chevaliers] Impossible d'inspecter un ancien garde.",
                    exception
            );
        }
        return false;
    }

    private boolean isRoyalGuard(Entity entity) {
        if (entity == null) {
            return false;
        }
        String marker = entity.getPersistentDataContainer().get(
                Keys.royalGuardType(),
                PersistentDataType.STRING
        );
        if (ROYAL_GUARD_TYPE.equals(marker)) {
            return true;
        }
        String name = entity.getCustomName();
        return name != null
                && ROYAL_GUARD_NAME.equals(
                ChatColor.stripColor(name)
        );
    }

    /**
     * API commune utilisée notamment par les Gardes Royaux.
     */
    public static boolean isVillageKnight(Entity entity) {
        if (entity == null) {
            return false;
        }

        /*
         * Ici, je traite séparément les marqueurs persistants et le tag de
         * secours : une entité en cours de déchargement, un proxy ou un mock
         * incomplet ne doit jamais interrompre la logique des gardes royaux.
         */
        try {
            PersistentDataContainer data =
                    entity.getPersistentDataContainer();
            if (data != null) {
                String marker = data.get(
                        Keys.villageKnightType(),
                        PersistentDataType.STRING
                );
                if (KNIGHT_TYPE.equals(marker)) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // Le tag de scoreboard reste une seconde source indépendante.
        }

        try {
            Set<String> tags = entity.getScoreboardTags();
            return tags != null && tags.contains(SCOREBOARD_TAG);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private int villageIdOf(Entity entity) {
        if (entity == null) {
            return Integer.MIN_VALUE;
        }
        Integer value = entity.getPersistentDataContainer().get(
                Keys.villageKnightVillage(),
                PersistentDataType.INTEGER
        );
        return value != null ? value : Integer.MIN_VALUE;
    }

    private UUID slotIdOf(Entity entity) {
        if (entity == null) {
            return null;
        }
        String value = entity.getPersistentDataContainer().get(
                Keys.villageKnightSlot(),
                PersistentDataType.STRING
        );
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record SlotReference(
            VillageKnightGarrison garrison,
            VillageKnightGarrison.KnightSlot slot
    ) {
    }

    private record AvailableKnight(
            VillageKnightGarrison.KnightSlot slot,
            Mob knight,
            int responsePriority,
            double distanceSquared
    ) {
    }
}
