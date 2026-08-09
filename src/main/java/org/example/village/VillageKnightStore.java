package org.example.village;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistance atomique des garnisons dans {@code village-knights.yml}.
 */
public final class VillageKnightStore {

    private static final int SCHEMA_VERSION = 1;
    private static final long MAX_FILE_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_GARRISONS = 32;
    private static final int MAX_ROUTES_PER_GARRISON = 64;
    private static final int MAX_SLOTS_PER_GARRISON = 64;
    private static final int MAX_POINTS_PER_ROUTE = 512;
    private static final int MAX_HORIZONTAL_COORDINATE = 30_000_000;
    private static final int MIN_STORED_Y = -2_048;
    private static final int MAX_STORED_Y = 2_048;

    private final File file;
    private final File backupFile;
    private final Logger logger;
    private boolean preserveBackupOnNextSave;

    public VillageKnightStore(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "village-knights.yml");
        this.backupFile = new File(
                dataFolder,
                "village-knights.yml.bak"
        );
        this.logger = logger != null
                ? logger
                : Logger.getLogger(VillageKnightStore.class.getName());
    }

    public synchronized LoadResult load() {
        preserveBackupOnNextSave = false;

        if (!file.isFile()) {
            return new LoadResult(List.of(), true);
        }

        DocumentResult primary = loadDocument(file);
        if (primary.readable()) {
            return new LoadResult(
                    primary.garrisons(),
                    primary.safeToRewrite()
            );
        }

        if (!backupFile.isFile()) {
            logger.severe(
                    "[Chevaliers] village-knights.yml est illisible et "
                            + "aucune sauvegarde n'est disponible. Le système "
                            + "reste en lecture seule pour ne pas écraser les données."
            );
            return new LoadResult(List.of(), false);
        }

        DocumentResult backup = loadDocument(backupFile);
        if (!backup.readable()) {
            logger.severe(
                    "[Chevaliers] La sauvegarde village-knights.yml.bak "
                            + "est elle aussi illisible. Le système reste en lecture seule."
            );
            return new LoadResult(List.of(), false);
        }

        quarantinePrimary();
        preserveBackupOnNextSave = true;
        logger.warning(
                "[Chevaliers] Garnisons restaurées depuis "
                        + "village-knights.yml.bak."
        );
        return new LoadResult(
                backup.garrisons(),
                backup.safeToRewrite()
        );
    }

    private DocumentResult loadDocument(File source) {
        try {
            if (Files.size(source.toPath()) > MAX_FILE_BYTES) {
                logger.severe(
                        "[Chevaliers] " + source.getName()
                                + " dépasse la taille de sécurité de "
                                + MAX_FILE_BYTES + " octets."
                );
                return DocumentResult.unreadable();
            }
        } catch (IOException exception) {
            logger.log(
                    Level.SEVERE,
                    "[Chevaliers] Impossible de contrôler "
                            + source.getName() + ".",
                    exception
            );
            return DocumentResult.unreadable();
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(source);
        } catch (IOException
                 | InvalidConfigurationException
                 | RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "[Chevaliers] Impossible de lire "
                            + source.getName() + ".",
                    exception
            );
            return DocumentResult.unreadable();
        }

        int schema = yaml.getInt("schema-version", -1);
        if (schema != SCHEMA_VERSION) {
            logger.severe(
                    "[Chevaliers] Version de schéma non prise en charge "
                            + "dans " + source.getName() + " : " + schema
                            + " (attendue : " + SCHEMA_VERSION + ")."
            );
            return new DocumentResult(true, false, List.of());
        }

        ConfigurationSection root =
                yaml.getConfigurationSection("garrisons");
        if (root == null) {
            if (yaml.get("garrisons") == null) {
                return new DocumentResult(true, true, List.of());
            }
            logger.severe(
                    "[Chevaliers] La clé « garrisons » doit être une section YAML."
            );
            return new DocumentResult(true, false, List.of());
        }

        List<VillageKnightGarrison> garrisons = new ArrayList<>();
        boolean safeToRewrite = true;
        int processed = 0;
        for (String key : root.getKeys(false)) {
            if (++processed > MAX_GARRISONS) {
                logger.warning(
                        "[Chevaliers] Les garnisons au-delà de "
                                + MAX_GARRISONS + " sont ignorées."
                );
                safeToRewrite = false;
                break;
            }

            ConfigurationSection section =
                    root.getConfigurationSection(key);
            if (section == null) {
                logger.warning(
                        "[Chevaliers] Garnison " + key
                                + " invalide : section YAML absente."
                );
                safeToRewrite = false;
                continue;
            }
            try {
                garrisons.add(parseGarrison(section));
            } catch (RuntimeException exception) {
                logger.log(
                        Level.WARNING,
                        "[Chevaliers] Garnison " + key
                                + " conservée sur disque mais non activée.",
                        exception
                );
                safeToRewrite = false;
            }
        }
        return new DocumentResult(
                true,
                safeToRewrite,
                garrisons
        );
    }

    private VillageKnightGarrison parseGarrison(
            ConfigurationSection section) {
        int villageId = requiredInt(section, "village-id");
        UUID worldId = requiredUuid(section, "world-id");
        String worldName = section.getString("world-name", "");
        VillageKnightGarrison.BlockPoint center =
                requiredPoint(section, "center");
        int radiusX = boundedPositive(
                requiredInt(section, "radius-x"),
                4,
                2_048
        );
        int radiusZ = boundedPositive(
                requiredInt(section, "radius-z"),
                4,
                2_048
        );
        long createdAt = Math.max(
                0L,
                section.getLong("created-at", 0L)
        );
        int planVersion = section.getInt(
                "plan-version",
                VillageKnightGarrison.LEGACY_PLAN_VERSION
        );
        if (planVersion < VillageKnightGarrison.LEGACY_PLAN_VERSION
                || planVersion > VillageKnightGarrison.CURRENT_PLAN_VERSION) {
            throw new IllegalArgumentException(
                    "Version de plan de garnison non prise en charge : "
                            + planVersion
            );
        }
        boolean rampartPlanComplete = section.getBoolean(
                "rampart-plan-complete",
                planVersion >= VillageKnightGarrison.CURRENT_PLAN_VERSION
        );

        ConfigurationSection routesSection =
                requiredSection(section, "routes");
        List<VillageKnightGarrison.PatrolRoute> routes =
                new ArrayList<>();
        int routeCount = 0;
        for (String routeKey : routesSection.getKeys(false)) {
            if (++routeCount > MAX_ROUTES_PER_GARRISON) {
                throw new IllegalArgumentException(
                        "Trop de routes dans la garnison."
                );
            }
            ConfigurationSection routeSection =
                    requiredSection(routesSection, routeKey);
            int routeId = parseIntKey(routeKey);
            VillageKnightGarrison.RouteKind kind =
                    enumValue(
                            VillageKnightGarrison.RouteKind.class,
                            routeSection.getString("kind")
                    );
            int waypointIndex =
                    routeSection.getInt("waypoint-index", 0);
            long lastAdvanceAt = Math.max(
                    0L,
                    routeSection.getLong("last-advance-at", 0L)
            );

            List<Map<?, ?>> rawPoints =
                    routeSection.getMapList("waypoints");
            if (rawPoints.isEmpty()
                    || rawPoints.size() > MAX_POINTS_PER_ROUTE) {
                throw new IllegalArgumentException(
                        "Nombre de points invalide pour la route "
                                + routeId + "."
                );
            }
            List<VillageKnightGarrison.BlockPoint> points =
                    new ArrayList<>();
            for (Map<?, ?> rawPoint : rawPoints) {
                points.add(parsePointMap(rawPoint));
            }
            routes.add(new VillageKnightGarrison.PatrolRoute(
                    routeId,
                    kind,
                    points,
                    waypointIndex,
                    lastAdvanceAt
            ));
        }

        ConfigurationSection slotsSection =
                requiredSection(section, "slots");
        List<VillageKnightGarrison.KnightSlot> slots =
                new ArrayList<>();
        int slotCount = 0;
        for (String slotKey : slotsSection.getKeys(false)) {
            if (++slotCount > MAX_SLOTS_PER_GARRISON) {
                throw new IllegalArgumentException(
                        "Trop de créneaux dans la garnison."
                );
            }
            UUID slotId = parseUuid(slotKey, "slot");
            ConfigurationSection slotSection =
                    requiredSection(slotsSection, slotKey);
            int routeId = requiredInt(slotSection, "route-id");
            VillageKnightGarrison.UnitKind unitKind =
                    enumValue(
                            VillageKnightGarrison.UnitKind.class,
                            slotSection.getString("unit-kind")
                    );
            int formationIndex = boundedPositive(
                    slotSection.getInt("formation-index", 0),
                    0,
                    16
            );
            UUID entityId = optionalUuid(
                    slotSection.getString("entity-id")
            );
            long respawnAt = Math.max(
                    0L,
                    slotSection.getLong("respawn-at", 0L)
            );
            VillageKnightGarrison.BlockPoint lastPosition =
                    optionalPoint(slotSection, "last-position");

            slots.add(new VillageKnightGarrison.KnightSlot(
                    slotId,
                    routeId,
                    unitKind,
                    formationIndex,
                    entityId,
                    respawnAt,
                    lastPosition
            ));
        }

        return new VillageKnightGarrison(
                planVersion,
                villageId,
                worldId,
                worldName,
                center,
                radiusX,
                radiusZ,
                createdAt,
                rampartPlanComplete,
                routes,
                slots
        );
    }

    public synchronized boolean save(
            Collection<VillageKnightGarrison> garrisons) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", SCHEMA_VERSION);
        yaml.set(
                "saved-at",
                System.currentTimeMillis()
        );
        ConfigurationSection root =
                yaml.createSection("garrisons");

        int written = 0;
        if (garrisons != null) {
            for (VillageKnightGarrison garrison : garrisons) {
                if (garrison == null) {
                    continue;
                }
                if (++written > MAX_GARRISONS) {
                    logger.severe(
                            "[Chevaliers] Sauvegarde refusée : plus de "
                                    + MAX_GARRISONS + " garnisons actives."
                    );
                    return false;
                }
                try {
                    writeGarrison(
                            root.createSection(
                                    Integer.toString(garrison.villageId())
                            ),
                            garrison
                    );
                } catch (RuntimeException exception) {
                    logger.log(
                            Level.SEVERE,
                            "[Chevaliers] Sauvegarde refusée : la garnison "
                                    + garrison.villageId()
                                    + " contient un état invalide.",
                            exception
                    );
                    return false;
                }
            }
        }

        File parent = file.getParentFile();
        if (parent != null
                && !parent.exists()
                && !parent.mkdirs()) {
            logger.severe(
                    "[Chevaliers] Impossible de créer "
                            + parent.getAbsolutePath() + "."
            );
            return false;
        }

        File temporary = new File(
                file.getPath() + ".tmp"
        );
        boolean saved = false;
        try {
            yaml.save(temporary);
            if (file.isFile() && !preserveBackupOnNextSave) {
                Files.copy(
                        file.toPath(),
                        backupFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
            moveAtomically(
                    temporary,
                    file
            );
            saved = true;
            preserveBackupOnNextSave = false;
            return true;
        } catch (IOException | RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "[Chevaliers] Impossible de sauvegarder "
                            + file.getName() + ".",
                    exception
            );
            return false;
        } finally {
            if (!saved) {
                try {
                    Files.deleteIfExists(temporary.toPath());
                } catch (IOException exception) {
                    logger.log(
                            Level.FINE,
                            "[Chevaliers] Nettoyage du fichier temporaire impossible.",
                            exception
                    );
                }
            }
        }
    }

    private void writeGarrison(ConfigurationSection section,
                               VillageKnightGarrison garrison) {
        section.set("plan-version", garrison.planVersion());
        section.set(
                "rampart-plan-complete",
                garrison.rampartPlanComplete()
        );
        section.set("village-id", garrison.villageId());
        section.set("world-id", garrison.worldId().toString());
        section.set("world-name", garrison.worldName());
        setPoint(section, "center", garrison.center());
        section.set("radius-x", garrison.radiusX());
        section.set("radius-z", garrison.radiusZ());
        section.set("created-at", garrison.createdAtMillis());

        ConfigurationSection routesSection =
                section.createSection("routes");
        int routeCount = 0;
        for (VillageKnightGarrison.PatrolRoute route
                : garrison.routes()) {
            if (++routeCount > MAX_ROUTES_PER_GARRISON
                    || route.waypoints().size()
                    > MAX_POINTS_PER_ROUTE) {
                throw new IllegalStateException(
                        "Garnison trop volumineuse pour la sauvegarde."
                );
            }
            ConfigurationSection routeSection =
                    routesSection.createSection(
                            Integer.toString(route.routeId())
                    );
            routeSection.set("kind", route.kind().name());
            routeSection.set(
                    "waypoint-index",
                    route.waypointIndex()
            );
            routeSection.set(
                    "last-advance-at",
                    route.lastAdvanceAtMillis()
            );
            List<Map<String, Object>> points = new ArrayList<>();
            for (VillageKnightGarrison.BlockPoint point
                    : route.waypoints()) {
                points.add(pointMap(point));
            }
            routeSection.set("waypoints", points);
        }

        ConfigurationSection slotsSection =
                section.createSection("slots");
        int slotCount = 0;
        for (VillageKnightGarrison.KnightSlot slot
                : garrison.slots()) {
            if (++slotCount > MAX_SLOTS_PER_GARRISON) {
                throw new IllegalStateException(
                        "Trop de chevaliers dans la garnison."
                );
            }
            ConfigurationSection slotSection =
                    slotsSection.createSection(
                            slot.slotId().toString()
                    );
            slotSection.set("route-id", slot.routeId());
            slotSection.set(
                    "unit-kind",
                    slot.unitKind().name()
            );
            slotSection.set(
                    "formation-index",
                    slot.formationIndex()
            );
            slotSection.set(
                    "entity-id",
                    slot.entityId() != null
                            ? slot.entityId().toString()
                            : null
            );
            slotSection.set(
                    "respawn-at",
                    slot.respawnAtMillis()
            );
            if (slot.lastKnownPosition() != null) {
                setPoint(
                        slotSection,
                        "last-position",
                        slot.lastKnownPosition()
                );
            }
        }
    }

    private Map<String, Object> pointMap(
            VillageKnightGarrison.BlockPoint point) {
        validatePoint(point);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("x", point.x());
        result.put("y", point.y());
        result.put("z", point.z());
        return result;
    }

    private void setPoint(ConfigurationSection section,
                          String path,
                          VillageKnightGarrison.BlockPoint point) {
        validatePoint(point);
        section.set(path + ".x", point.x());
        section.set(path + ".y", point.y());
        section.set(path + ".z", point.z());
    }

    private VillageKnightGarrison.BlockPoint requiredPoint(
            ConfigurationSection section,
            String path) {
        VillageKnightGarrison.BlockPoint point =
                optionalPoint(section, path);
        if (point == null) {
            throw new IllegalArgumentException(
                    "Point requis absent : " + path
            );
        }
        return point;
    }

    private VillageKnightGarrison.BlockPoint optionalPoint(
            ConfigurationSection section,
            String path) {
        ConfigurationSection pointSection =
                section.getConfigurationSection(path);
        if (pointSection == null) {
            return null;
        }
        VillageKnightGarrison.BlockPoint point =
                new VillageKnightGarrison.BlockPoint(
                        requiredInt(pointSection, "x"),
                        requiredInt(pointSection, "y"),
                        requiredInt(pointSection, "z")
                );
        validatePoint(point);
        return point;
    }

    private VillageKnightGarrison.BlockPoint parsePointMap(
            Map<?, ?> map) {
        if (map == null) {
            throw new IllegalArgumentException("Point YAML nul.");
        }
        VillageKnightGarrison.BlockPoint point =
                new VillageKnightGarrison.BlockPoint(
                        mapInt(map, "x"),
                        mapInt(map, "y"),
                        mapInt(map, "z")
                );
        validatePoint(point);
        return point;
    }

    private void validatePoint(
            VillageKnightGarrison.BlockPoint point) {
        if (point == null
                || Math.abs((long) point.x())
                > MAX_HORIZONTAL_COORDINATE
                || Math.abs((long) point.z())
                > MAX_HORIZONTAL_COORDINATE
                || point.y() < MIN_STORED_Y
                || point.y() > MAX_STORED_Y) {
            throw new IllegalArgumentException(
                    "Coordonnées de garnison hors limites."
            );
        }
    }

    private int mapInt(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                // Le message commun ci-dessous est plus exploitable.
            }
        }
        throw new IllegalArgumentException(
                "Valeur entière absente dans un point : " + key
        );
    }

    private int requiredInt(ConfigurationSection section,
                            String path) {
        Object value = section.get(path);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    "Entier requis absent : " + path
            );
        }
        return number.intValue();
    }

    private UUID requiredUuid(ConfigurationSection section,
                              String path) {
        return parseUuid(
                section.getString(path),
                path
        );
    }

    private UUID optionalUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseUuid(value, "UUID optionnel");
    }

    private UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "UUID requis absent : " + field
            );
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "UUID invalide pour " + field + ".",
                    exception
            );
        }
    }

    private int parseIntKey(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Identifiant numérique invalide : " + key,
                    exception
            );
        }
    }

    private ConfigurationSection requiredSection(
            ConfigurationSection parent,
            String path) {
        ConfigurationSection section =
                parent.getConfigurationSection(path);
        if (section == null) {
            throw new IllegalArgumentException(
                    "Section requise absente : " + path
            );
        }
        return section;
    }

    private <E extends Enum<E>> E enumValue(
            Class<E> type,
            String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Valeur d'énumération absente."
            );
        }
        try {
            return Enum.valueOf(
                    type,
                    value.trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Valeur inconnue : " + value,
                    exception
            );
        }
    }

    private int boundedPositive(int value,
                                int minimum,
                                int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    "Valeur hors limites : " + value
            );
        }
        return value;
    }

    private void quarantinePrimary() {
        if (!file.isFile()) {
            return;
        }
        File quarantine = new File(
                file.getPath()
                        + ".corrupt-"
                        + System.currentTimeMillis()
        );
        try {
            moveAtomically(file, quarantine);
            logger.severe(
                    "[Chevaliers] Fichier primaire corrompu déplacé vers "
                            + quarantine.getAbsolutePath() + "."
            );
        } catch (IOException exception) {
            logger.log(
                    Level.SEVERE,
                    "[Chevaliers] Impossible de mettre le fichier "
                            + "primaire corrompu en quarantaine.",
                    exception
            );
        }
    }

    private void moveAtomically(File source,
                                File destination)
            throws IOException {
        try {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    public record LoadResult(
            List<VillageKnightGarrison> garrisons,
            boolean writable
    ) {
        public LoadResult {
            garrisons = garrisons == null
                    ? List.of()
                    : List.copyOf(garrisons);
        }
    }

    private record DocumentResult(
            boolean readable,
            boolean safeToRewrite,
            List<VillageKnightGarrison> garrisons
    ) {
        private static DocumentResult unreadable() {
            return new DocumentResult(
                    false,
                    false,
                    List.of()
            );
        }

        private DocumentResult {
            garrisons = garrisons == null
                    ? List.of()
                    : List.copyOf(garrisons);
        }
    }
}
