package org.example.village;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * État persistant d'une salle des coffres créée par {@code /village}.
 *
 * <p>La classe ne conserve aucun objet Bukkit mutable. Elle peut donc être
 * copiée puis sérialisée sur un thread d'entrées/sorties sans accéder au monde
 * Minecraft hors du thread serveur.</p>
 */
public final class VillageTreasury {

    public static final int CURRENT_PLAN_VERSION = 1;
    public static final int REQUIRED_CONTAINER_COUNT = 60;
    public static final int REQUIRED_DOOR_COUNT = 2;
    public static final int REQUIRED_GUARD_COUNT = 4;

    /**
     * Coordonnée de bloc indépendante du chargement du monde.
     */
    public record BlockPoint(int x, int y, int z) {

        public BlockPoint {
            if (Math.abs((long) x) > 30_000_000L
                    || Math.abs((long) z) > 30_000_000L) {
                throw new IllegalArgumentException(
                        "Coordonnée horizontale de trésor invalide."
                );
            }
            if (y < -2_048 || y > 2_048) {
                throw new IllegalArgumentException(
                        "Coordonnée verticale de trésor invalide."
                );
            }
        }

        public static BlockPoint from(Location location) {
            Objects.requireNonNull(location, "location");
            return new BlockPoint(
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );
        }

        public Location toLocation(World world) {
            return new Location(
                    Objects.requireNonNull(world, "world"),
                    x + 0.5D,
                    y,
                    z + 0.5D
            );
        }

        public boolean matches(Block block) {
            return block != null
                    && block.getX() == x
                    && block.getY() == y
                    && block.getZ() == z;
        }
    }

    /**
     * Volume inclusif utilisé à la fois pour la protection et la barre de zone.
     */
    public record Cuboid(int minX,
                         int maxX,
                         int minY,
                         int maxY,
                         int minZ,
                         int maxZ) {

        public Cuboid {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException(
                        "Bornes de trésor inversées."
                );
            }
            if (Math.abs((long) minX) > 30_000_000L
                    || Math.abs((long) maxX) > 30_000_000L
                    || Math.abs((long) minZ) > 30_000_000L
                    || Math.abs((long) maxZ) > 30_000_000L
                    || minY < -2_048
                    || maxY > 2_048) {
                throw new IllegalArgumentException(
                        "Bornes de trésor hors limites."
                );
            }
        }

        public static Cuboid normalized(int firstX,
                                        int secondX,
                                        int firstY,
                                        int secondY,
                                        int firstZ,
                                        int secondZ) {
            return new Cuboid(
                    Math.min(firstX, secondX),
                    Math.max(firstX, secondX),
                    Math.min(firstY, secondY),
                    Math.max(firstY, secondY),
                    Math.min(firstZ, secondZ),
                    Math.max(firstZ, secondZ)
            );
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }

        public boolean contains(Block block) {
            return block != null
                    && contains(block.getX(), block.getY(), block.getZ());
        }

        public boolean contains(Location location) {
            return location != null
                    && contains(
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );
        }

        public boolean intersects(Cuboid other) {
            return other != null
                    && minX <= other.maxX
                    && maxX >= other.minX
                    && minY <= other.maxY
                    && maxY >= other.minY
                    && minZ <= other.maxZ
                    && maxZ >= other.minZ;
        }
    }

    private final int planVersion;
    private final int villageId;
    private final UUID ownerId;
    private final UUID worldId;
    private final String worldName;
    private final Cuboid roomBounds;
    private final List<Cuboid> protectedZones;
    private final List<BlockPoint> containers;
    private final BlockPoint ledger;
    private final List<BlockPoint> doors;
    private final long createdAtMillis;

    private long nextTaxAtMillis;
    private int pendingTaxes;
    private long depositedItems;

    public VillageTreasury(int villageId,
                           UUID ownerId,
                           UUID worldId,
                           String worldName,
                           Cuboid roomBounds,
                           List<Cuboid> protectedZones,
                           List<BlockPoint> containers,
                           BlockPoint ledger,
                           List<BlockPoint> doors,
                           long createdAtMillis,
                           long nextTaxAtMillis,
                           int pendingTaxes,
                           long depositedItems) {
        this(
                CURRENT_PLAN_VERSION,
                villageId,
                ownerId,
                worldId,
                worldName,
                roomBounds,
                protectedZones,
                containers,
                ledger,
                doors,
                createdAtMillis,
                nextTaxAtMillis,
                pendingTaxes,
                depositedItems
        );
    }

    public VillageTreasury(int planVersion,
                           int villageId,
                           UUID ownerId,
                           UUID worldId,
                           String worldName,
                           Cuboid roomBounds,
                           List<Cuboid> protectedZones,
                           List<BlockPoint> containers,
                           BlockPoint ledger,
                           List<BlockPoint> doors,
                           long createdAtMillis,
                           long nextTaxAtMillis,
                           int pendingTaxes,
                           long depositedItems) {
        if (planVersion <= 0 || planVersion > CURRENT_PLAN_VERSION) {
            throw new IllegalArgumentException(
                    "Version de plan de trésor non prise en charge."
            );
        }
        if (worldName == null || worldName.isBlank()
                || worldName.length() > 128) {
            throw new IllegalArgumentException(
                    "Nom de monde de trésor invalide."
            );
        }

        this.planVersion = planVersion;
        this.villageId = villageId;
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.worldName = worldName;
        this.roomBounds = Objects.requireNonNull(
                roomBounds,
                "roomBounds"
        );
        this.protectedZones = immutableDistinctCuboids(protectedZones);
        if (this.protectedZones.isEmpty()
                || this.protectedZones.stream()
                .noneMatch(zone -> zone.intersects(roomBounds))) {
            throw new IllegalArgumentException(
                    "La salle doit appartenir à une zone protégée."
            );
        }
        this.containers = immutableDistinctPoints(
                containers,
                REQUIRED_CONTAINER_COUNT,
                REQUIRED_CONTAINER_COUNT
        );
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.doors = immutableDistinctPoints(
                doors,
                REQUIRED_DOOR_COUNT,
                REQUIRED_DOOR_COUNT
        );
        if (!roomBounds.contains(ledger.x(), ledger.y(), ledger.z())
                || this.containers.stream().anyMatch(point ->
                !roomBounds.contains(point.x(), point.y(), point.z())
        )) {
            throw new IllegalArgumentException(
                    "Le registre et les coffres doivent être dans la salle."
            );
        }
        if (this.doors.stream().anyMatch(point ->
                this.protectedZones.stream().noneMatch(zone ->
                        zone.contains(point.x(), point.y(), point.z())
                )
        )) {
            throw new IllegalArgumentException(
                    "Chaque porte doit appartenir à une zone protégée."
            );
        }
        this.createdAtMillis = Math.max(0L, createdAtMillis);
        this.nextTaxAtMillis = Math.max(0L, nextTaxAtMillis);
        this.pendingTaxes = Math.max(0, pendingTaxes);
        this.depositedItems = Math.max(0L, depositedItems);
    }

    private static List<Cuboid> immutableDistinctCuboids(
            List<Cuboid> values) {
        Set<Cuboid> distinct = new LinkedHashSet<>();
        if (values != null) {
            for (Cuboid value : values) {
                if (value != null) {
                    distinct.add(value);
                }
            }
        }
        if (distinct.size() > 8) {
            throw new IllegalArgumentException(
                    "Trop de volumes protégés pour un trésor."
            );
        }
        return Collections.unmodifiableList(new ArrayList<>(distinct));
    }

    private static List<BlockPoint> immutableDistinctPoints(
            List<BlockPoint> values,
            int minimum,
            int maximum) {
        Set<BlockPoint> distinct = new LinkedHashSet<>();
        if (values != null) {
            for (BlockPoint value : values) {
                if (value != null) {
                    distinct.add(value);
                }
            }
        }
        if (distinct.size() < minimum || distinct.size() > maximum) {
            throw new IllegalArgumentException(
                    "Nombre de points de trésor invalide : "
                            + distinct.size()
            );
        }
        return Collections.unmodifiableList(new ArrayList<>(distinct));
    }

    public int planVersion() {
        return planVersion;
    }

    public int villageId() {
        return villageId;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public UUID worldId() {
        return worldId;
    }

    public String worldName() {
        return worldName;
    }

    public Cuboid roomBounds() {
        return roomBounds;
    }

    public List<Cuboid> protectedZones() {
        return protectedZones;
    }

    public List<BlockPoint> containers() {
        return containers;
    }

    public BlockPoint ledger() {
        return ledger;
    }

    public List<BlockPoint> doors() {
        return doors;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public long nextTaxAtMillis() {
        return nextTaxAtMillis;
    }

    public void setNextTaxAtMillis(long nextTaxAtMillis) {
        this.nextTaxAtMillis = Math.max(0L, nextTaxAtMillis);
    }

    public int pendingTaxes() {
        return pendingTaxes;
    }

    public void setPendingTaxes(int pendingTaxes) {
        this.pendingTaxes = Math.max(0, pendingTaxes);
    }

    public long depositedItems() {
        return depositedItems;
    }

    public void addDepositedItems(long amount) {
        if (amount <= 0L) {
            return;
        }
        depositedItems = Math.min(
                Long.MAX_VALUE,
                depositedItems > Long.MAX_VALUE - amount
                        ? Long.MAX_VALUE
                        : depositedItems + amount
        );
    }

    public boolean isOwner(UUID playerId) {
        return playerId != null && ownerId.equals(playerId);
    }

    public boolean belongsTo(World world) {
        return world != null && worldId.equals(world.getUID());
    }

    public boolean isInsideRoom(Location location) {
        return location != null
                && location.getWorld() != null
                && worldId.equals(location.getWorld().getUID())
                && roomBounds.contains(location);
    }

    public boolean protects(Block block) {
        if (block == null
                || !worldId.equals(block.getWorld().getUID())) {
            return false;
        }
        for (Cuboid zone : protectedZones) {
            if (zone.contains(block)) {
                return true;
            }
        }
        return false;
    }

    public boolean isContainer(Block block) {
        if (block == null
                || !worldId.equals(block.getWorld().getUID())) {
            return false;
        }
        for (BlockPoint point : containers) {
            if (point.matches(block)) {
                return true;
            }
        }
        return false;
    }

    public boolean isLedger(Block block) {
        return block != null
                && worldId.equals(block.getWorld().getUID())
                && ledger.matches(block);
    }

    public boolean isDoor(Block block) {
        if (block == null
                || !worldId.equals(block.getWorld().getUID())) {
            return false;
        }
        for (BlockPoint point : doors) {
            if (point.matches(block)
                    || point.x() == block.getX()
                    && point.y() + 1 == block.getY()
                    && point.z() == block.getZ()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copie détachée utilisée par la persistance asynchrone.
     */
    public VillageTreasury snapshot() {
        return new VillageTreasury(
                planVersion,
                villageId,
                ownerId,
                worldId,
                worldName,
                roomBounds,
                protectedZones,
                containers,
                ledger,
                doors,
                createdAtMillis,
                nextTaxAtMillis,
                pendingTaxes,
                depositedItems
        );
    }
}
