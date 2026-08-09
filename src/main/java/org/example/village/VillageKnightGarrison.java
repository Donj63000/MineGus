package org.example.village;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * État persistant d'une garnison et état transitoire de ses entités.
 *
 * <p>Les identifiants de créneau ({@link KnightSlot#slotId()}) ne changent
 * jamais. Une mort remplace uniquement l'UUID de l'entité Minecraft ; cela
 * permet de réconcilier proprement les chunks après un redémarrage.</p>
 */
public final class VillageKnightGarrison {

    /**
     * Première version persistée par les anciennes garnisons.
     */
    public static final int LEGACY_PLAN_VERSION = 1;

    /**
     * Version du plan qui sépare sentinelles, patrouilles et remparts.
     */
    public static final int CURRENT_PLAN_VERSION = 2;

    public enum UnitKind {
        SWORDSMAN,
        ARCHER
    }

    public enum RouteKind {
        /**
         * Valeur conservée uniquement pour relire les sauvegardes historiques.
         */
        STRATEGIC,
        GATE_SENTRY,
        PLAZA_SENTRY,
        KEEP_ENTRANCE_SENTRY,
        KEEP_INTERIOR_SENTRY,
        URBAN,
        RAMPART;

        public boolean isSentry() {
            return this == STRATEGIC
                    || this == GATE_SENTRY
                    || this == PLAZA_SENTRY
                    || this == KEEP_ENTRANCE_SENTRY
                    || this == KEEP_INTERIOR_SENTRY;
        }

        public boolean isPatrol() {
            return this == URBAN || this == RAMPART;
        }
    }

    /**
     * Coordonnée bloc indépendante d'un objet {@link World}.
     */
    public record BlockPoint(int x, int y, int z) {

        public Location toLocation(World world) {
            return new Location(
                    Objects.requireNonNull(world, "world"),
                    x + 0.5D,
                    y,
                    z + 0.5D
            );
        }

        public long horizontalDistanceSquared(BlockPoint other) {
            if (other == null) {
                return Long.MAX_VALUE;
            }
            long dx = (long) x - other.x;
            long dz = (long) z - other.z;
            return dx * dx + dz * dz;
        }

        public static BlockPoint from(Location location) {
            if (location == null) {
                return null;
            }
            return new BlockPoint(
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );
        }
    }

    /**
     * Itinéraire partagé par une patrouille.
     */
    public static final class PatrolRoute {
        private final int routeId;
        private final RouteKind kind;
        private final List<BlockPoint> waypoints;
        private int waypointIndex;
        private long lastAdvanceAtMillis;

        public PatrolRoute(int routeId,
                           RouteKind kind,
                           List<BlockPoint> waypoints,
                           int waypointIndex,
                           long lastAdvanceAtMillis) {
            if (routeId < 0) {
                throw new IllegalArgumentException(
                        "L'identifiant de route doit être positif."
                );
            }
            this.routeId = routeId;
            this.kind = Objects.requireNonNull(kind, "kind");

            List<BlockPoint> sanitized = new ArrayList<>();
            if (waypoints != null) {
                for (BlockPoint point : waypoints) {
                    if (point != null
                            && (sanitized.isEmpty()
                            || !sanitized.get(sanitized.size() - 1)
                            .equals(point))) {
                        sanitized.add(point);
                    }
                }
            }
            if (sanitized.isEmpty()) {
                throw new IllegalArgumentException(
                        "Une route doit contenir au moins un point."
                );
            }

            this.waypoints = Collections.unmodifiableList(sanitized);
            this.waypointIndex = Math.floorMod(
                    waypointIndex,
                    sanitized.size()
            );
            this.lastAdvanceAtMillis = Math.max(0L, lastAdvanceAtMillis);
        }

        public int routeId() {
            return routeId;
        }

        public RouteKind kind() {
            return kind;
        }

        public List<BlockPoint> waypoints() {
            return waypoints;
        }

        public int waypointIndex() {
            return waypointIndex;
        }

        public long lastAdvanceAtMillis() {
            return lastAdvanceAtMillis;
        }

        public BlockPoint currentWaypoint() {
            return waypoints.get(waypointIndex);
        }

        public BlockPoint nextWaypoint() {
            if (waypoints.size() == 1) {
                return currentWaypoint();
            }
            return waypoints.get((waypointIndex + 1) % waypoints.size());
        }

        public void advance(long nowMillis) {
            if (waypoints.size() > 1) {
                waypointIndex = (waypointIndex + 1) % waypoints.size();
            }
            lastAdvanceAtMillis = Math.max(0L, nowMillis);
        }

        public void setWaypointIndex(int index) {
            waypointIndex = Math.floorMod(index, waypoints.size());
        }

        public void setLastAdvanceAtMillis(long value) {
            lastAdvanceAtMillis = Math.max(0L, value);
        }
    }

    /**
     * Créneau persistant occupé successivement par les réapparitions d'un
     * même chevalier.
     */
    public static final class KnightSlot {
        private final UUID slotId;
        private final int routeId;
        private final UnitKind unitKind;
        private final int formationIndex;

        private UUID entityId;
        private long respawnAtMillis;
        private BlockPoint lastKnownPosition;

        /*
         * Ces champs ne sont pas sauvegardés : ils ne décrivent que l'état
         * d'une session serveur et peuvent être reconstruits sans perte.
         */
        private int missingLoadedChecks;
        private int pathFailures;
        private UUID targetEntityId;
        private long targetExpiresAtMillis;
        private BlockPoint movementSample;
        private long movementSampleAtMillis;
        private long lastPositionPersistAtMillis;
        private BlockPoint navigationTarget;
        private long lastPathCommandAtMillis;
        private long lastRecoveryAttemptAtMillis;

        public KnightSlot(UUID slotId,
                          int routeId,
                          UnitKind unitKind,
                          int formationIndex,
                          UUID entityId,
                          long respawnAtMillis,
                          BlockPoint lastKnownPosition) {
            this.slotId = Objects.requireNonNull(slotId, "slotId");
            if (routeId < 0) {
                throw new IllegalArgumentException("Route de créneau invalide.");
            }
            this.routeId = routeId;
            this.unitKind = Objects.requireNonNull(unitKind, "unitKind");
            this.formationIndex = Math.max(0, formationIndex);
            this.entityId = entityId;
            this.respawnAtMillis = Math.max(0L, respawnAtMillis);
            this.lastKnownPosition = lastKnownPosition;
        }

        public UUID slotId() {
            return slotId;
        }

        public int routeId() {
            return routeId;
        }

        public UnitKind unitKind() {
            return unitKind;
        }

        public int formationIndex() {
            return formationIndex;
        }

        public UUID entityId() {
            return entityId;
        }

        public void setEntityId(UUID entityId) {
            this.entityId = entityId;
            missingLoadedChecks = 0;
            if (entityId == null) {
                clearNavigationCommand();
            }
        }

        public long respawnAtMillis() {
            return respawnAtMillis;
        }

        public void setRespawnAtMillis(long respawnAtMillis) {
            this.respawnAtMillis = Math.max(0L, respawnAtMillis);
        }

        public BlockPoint lastKnownPosition() {
            return lastKnownPosition;
        }

        public void setLastKnownPosition(BlockPoint lastKnownPosition) {
            if (lastKnownPosition != null) {
                this.lastKnownPosition = lastKnownPosition;
            }
        }

        public int incrementMissingLoadedChecks() {
            return ++missingLoadedChecks;
        }

        public void resetMissingLoadedChecks() {
            missingLoadedChecks = 0;
        }

        public int incrementPathFailures() {
            return ++pathFailures;
        }

        public void resetPathFailures() {
            pathFailures = 0;
        }

        public UUID targetEntityId() {
            return targetEntityId;
        }

        public long targetExpiresAtMillis() {
            return targetExpiresAtMillis;
        }

        public void assignTarget(UUID targetEntityId,
                                 long targetExpiresAtMillis) {
            this.targetEntityId = targetEntityId;
            this.targetExpiresAtMillis = Math.max(
                    0L,
                    targetExpiresAtMillis
            );
        }

        public void clearTarget() {
            targetEntityId = null;
            targetExpiresAtMillis = 0L;
        }

        public BlockPoint movementSample() {
            return movementSample;
        }

        public long movementSampleAtMillis() {
            return movementSampleAtMillis;
        }

        public void updateMovementSample(BlockPoint point, long nowMillis) {
            movementSample = point;
            movementSampleAtMillis = Math.max(0L, nowMillis);
        }

        public long lastPositionPersistAtMillis() {
            return lastPositionPersistAtMillis;
        }

        public void markPositionPersisted(long nowMillis) {
            lastPositionPersistAtMillis = Math.max(0L, nowMillis);
        }

        public BlockPoint navigationTarget() {
            return navigationTarget;
        }

        public long lastPathCommandAtMillis() {
            return lastPathCommandAtMillis;
        }

        /**
         * Mémorise un ordre de déplacement. Le gestionnaire peut ainsi laisser
         * le pathfinder vanilla travailler plusieurs ticks sans le réinitialiser.
         */
        public void markPathCommand(BlockPoint target, long nowMillis) {
            navigationTarget = target;
            lastPathCommandAtMillis = Math.max(0L, nowMillis);
        }

        public void clearNavigationCommand() {
            navigationTarget = null;
            lastPathCommandAtMillis = 0L;
        }

        public long lastRecoveryAttemptAtMillis() {
            return lastRecoveryAttemptAtMillis;
        }

        public void markRecoveryAttempt(long nowMillis) {
            lastRecoveryAttemptAtMillis = Math.max(0L, nowMillis);
        }
    }

    private final int planVersion;
    private final int villageId;
    private final UUID worldId;
    private final String worldName;
    private final BlockPoint center;
    private final int radiusX;
    private final int radiusZ;
    private final long createdAtMillis;
    private final boolean rampartPlanComplete;
    private final Map<Integer, PatrolRoute> routes;
    private final Map<UUID, KnightSlot> slots;

    /**
     * Constructeur utilisé par le code courant et les tests. Un plan créé par
     * cette surcharge est considéré comme entièrement calculé.
     */
    public VillageKnightGarrison(int villageId,
                                 UUID worldId,
                                 String worldName,
                                 BlockPoint center,
                                 int radiusX,
                                 int radiusZ,
                                 long createdAtMillis,
                                 Collection<PatrolRoute> routes,
                                 Collection<KnightSlot> slots) {
        this(
                CURRENT_PLAN_VERSION,
                villageId,
                worldId,
                worldName,
                center,
                radiusX,
                radiusZ,
                createdAtMillis,
                true,
                routes,
                slots
        );
    }

    public VillageKnightGarrison(int planVersion,
                                 int villageId,
                                 UUID worldId,
                                 String worldName,
                                 BlockPoint center,
                                 int radiusX,
                                 int radiusZ,
                                 long createdAtMillis,
                                 boolean rampartPlanComplete,
                                 Collection<PatrolRoute> routes,
                                 Collection<KnightSlot> slots) {
        if (planVersion < LEGACY_PLAN_VERSION
                || planVersion > CURRENT_PLAN_VERSION) {
            throw new IllegalArgumentException(
                    "Version de plan de garnison non prise en charge : "
                            + planVersion
            );
        }
        this.planVersion = planVersion;
        this.villageId = villageId;
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.worldName = worldName == null ? "" : worldName;
        this.center = Objects.requireNonNull(center, "center");
        this.radiusX = Math.max(4, radiusX);
        this.radiusZ = Math.max(4, radiusZ);
        this.createdAtMillis = Math.max(0L, createdAtMillis);
        this.rampartPlanComplete = rampartPlanComplete;

        Map<Integer, PatrolRoute> routesById = new LinkedHashMap<>();
        if (routes != null) {
            for (PatrolRoute route : routes) {
                if (route != null
                        && routesById.putIfAbsent(
                        route.routeId(),
                        route
                ) != null) {
                    throw new IllegalArgumentException(
                            "Identifiant de route dupliqué : "
                                    + route.routeId()
                    );
                }
            }
        }
        if (routesById.isEmpty()) {
            throw new IllegalArgumentException(
                    "Une garnison doit posséder au moins une route."
            );
        }

        Map<UUID, KnightSlot> slotsById = new LinkedHashMap<>();
        if (slots != null) {
            for (KnightSlot slot : slots) {
                if (slot == null) {
                    continue;
                }
                if (!routesById.containsKey(slot.routeId())) {
                    throw new IllegalArgumentException(
                            "Le créneau " + slot.slotId()
                                    + " référence une route absente."
                    );
                }
                if (slotsById.putIfAbsent(
                        slot.slotId(),
                        slot
                ) != null) {
                    throw new IllegalArgumentException(
                            "Identifiant de créneau dupliqué : "
                                    + slot.slotId()
                    );
                }
            }
        }
        if (slotsById.isEmpty()) {
            throw new IllegalArgumentException(
                    "Une garnison doit posséder au moins un chevalier."
            );
        }

        this.routes = routesById;
        this.slots = slotsById;
    }

    public int planVersion() {
        return planVersion;
    }

    public boolean requiresPlanMigration() {
        return planVersion < CURRENT_PLAN_VERSION;
    }

    public boolean rampartPlanComplete() {
        return rampartPlanComplete;
    }

    public int villageId() {
        return villageId;
    }

    public UUID worldId() {
        return worldId;
    }

    public String worldName() {
        return worldName;
    }

    public BlockPoint center() {
        return center;
    }

    public int radiusX() {
        return radiusX;
    }

    public int radiusZ() {
        return radiusZ;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public Collection<PatrolRoute> routes() {
        return Collections.unmodifiableCollection(routes.values());
    }

    public Collection<KnightSlot> slots() {
        return Collections.unmodifiableCollection(slots.values());
    }

    public PatrolRoute route(int routeId) {
        return routes.get(routeId);
    }

    public KnightSlot slot(UUID slotId) {
        return slotId == null ? null : slots.get(slotId);
    }

    public int unitCount() {
        return slots.size();
    }

    public boolean containsHorizontally(Location location,
                                        double margin) {
        if (location == null || location.getWorld() == null
                || !worldId.equals(location.getWorld().getUID())) {
            return false;
        }
        double safeMargin = Math.max(0.0D, margin);
        return Math.abs(location.getX() - (center.x() + 0.5D))
                <= radiusX + safeMargin
                && Math.abs(location.getZ() - (center.z() + 0.5D))
                <= radiusZ + safeMargin;
    }

    public double centerHorizontalDistanceSquared(Location location) {
        if (location == null || location.getWorld() == null
                || !worldId.equals(location.getWorld().getUID())) {
            return Double.MAX_VALUE;
        }
        double dx = location.getX() - (center.x() + 0.5D);
        double dz = location.getZ() - (center.z() + 0.5D);
        return dx * dx + dz * dz;
    }
}
