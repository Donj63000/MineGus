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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistance atomique des salles des coffres dans
 * {@code village-treasuries.yml}.
 */
public final class VillageTreasuryStore {

    private static final int SCHEMA_VERSION = 1;
    private static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    static final int MAX_TREASURIES = 32;
    private static final int MAX_PROTECTED_ZONES = 8;
    private static final int MAX_CONTAINERS = 256;
    private static final int MAX_DOORS = 16;

    private final File file;
    private final File backupFile;
    private final Logger logger;
    private boolean preserveBackupOnNextSave;

    public VillageTreasuryStore(File dataFolder, Logger logger) {
        Objects.requireNonNull(dataFolder, "dataFolder");
        this.file = new File(dataFolder, "village-treasuries.yml");
        this.backupFile = new File(
                dataFolder,
                "village-treasuries.yml.bak"
        );
        this.logger = logger != null
                ? logger
                : Logger.getLogger(
                        VillageTreasuryStore.class.getName()
                );
    }

    public synchronized LoadResult load() {
        preserveBackupOnNextSave = false;
        if (!file.isFile()) {
            /*
             * Après une coupure située entre la mise en quarantaine du fichier
             * principal et sa prochaine sauvegarde, le .bak est la seule copie
             * durable. Il doit donc être restauré plutôt que traité comme une
             * première installation vide.
             */
            if (!backupFile.isFile()) {
                return new LoadResult(List.of(), true);
            }
            DocumentResult orphanBackup = loadDocument(backupFile);
            if (!orphanBackup.readable()) {
                logger.severe(
                        "[Trésor] Le fichier principal est absent et sa "
                                + "sauvegarde est illisible. La persistance "
                                + "reste en lecture seule."
                );
                return new LoadResult(List.of(), false);
            }
            preserveBackupOnNextSave = true;
            logger.warning(
                    "[Trésor] Fichier principal absent : restauration depuis "
                            + "village-treasuries.yml.bak."
            );
            return new LoadResult(
                    orphanBackup.treasuries(),
                    orphanBackup.safeToRewrite()
            );
        }

        DocumentResult primary = loadDocument(file);
        if (primary.readable()) {
            return new LoadResult(
                    primary.treasuries(),
                    primary.safeToRewrite()
            );
        }

        if (!backupFile.isFile()) {
            logger.severe(
                    "[Trésor] village-treasuries.yml est illisible et "
                            + "aucune sauvegarde n'est disponible. La "
                            + "persistance reste en lecture seule."
            );
            return new LoadResult(List.of(), false);
        }

        DocumentResult backup = loadDocument(backupFile);
        if (!backup.readable()) {
            logger.severe(
                    "[Trésor] La sauvegarde village-treasuries.yml.bak "
                            + "est également illisible. La persistance reste "
                            + "en lecture seule."
            );
            return new LoadResult(List.of(), false);
        }

        quarantinePrimary();
        preserveBackupOnNextSave = true;
        logger.warning(
                "[Trésor] Salles des coffres restaurées depuis la "
                        + "sauvegarde .bak."
        );
        return new LoadResult(
                backup.treasuries(),
                backup.safeToRewrite()
        );
    }

    private DocumentResult loadDocument(File source) {
        try {
            if (Files.size(source.toPath()) > MAX_FILE_BYTES) {
                logger.severe(
                        "[Trésor] " + source.getName()
                                + " dépasse la taille maximale de sécurité."
                );
                return DocumentResult.unreadable();
            }
        } catch (IOException exception) {
            logger.log(
                    Level.SEVERE,
                    "[Trésor] Impossible de contrôler "
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
                    "[Trésor] Impossible de lire "
                            + source.getName() + ".",
                    exception
            );
            return DocumentResult.unreadable();
        }

        int schema = yaml.getInt("schema-version", -1);
        if (schema != SCHEMA_VERSION) {
            logger.severe(
                    "[Trésor] Version de schéma non prise en charge : "
                            + schema + " (attendue : "
                            + SCHEMA_VERSION + ")."
            );
            return new DocumentResult(true, false, List.of());
        }

        ConfigurationSection root =
                yaml.getConfigurationSection("treasuries");
        if (root == null) {
            if (yaml.get("treasuries") == null) {
                return new DocumentResult(true, true, List.of());
            }
            logger.severe(
                    "[Trésor] La clé « treasuries » doit être une section YAML."
            );
            return new DocumentResult(true, false, List.of());
        }

        List<VillageTreasury> result = new ArrayList<>();
        boolean safeToRewrite = true;
        int processed = 0;
        for (String key : root.getKeys(false)) {
            if (++processed > MAX_TREASURIES) {
                logger.warning(
                        "[Trésor] Les salles au-delà de "
                                + MAX_TREASURIES + " sont ignorées."
                );
                safeToRewrite = false;
                break;
            }
            try {
                int keyVillageId = parseInteger(
                        key,
                        "identifiant de section"
                );
                ConfigurationSection section =
                        root.getConfigurationSection(key);
                if (section == null) {
                    throw new IllegalArgumentException(
                            "La salle " + keyVillageId
                                    + " n'est pas une section."
                    );
                }
                VillageTreasury treasury = parseTreasury(section);
                if (treasury.villageId() != keyVillageId) {
                    throw new IllegalArgumentException(
                            "L'identifiant interne ne correspond pas "
                                    + "à la section."
                    );
                }
                boolean duplicate = result.stream().anyMatch(
                        existing -> existing.villageId()
                                == treasury.villageId()
                );
                if (duplicate) {
                    throw new IllegalArgumentException(
                            "Identifiant de village dupliqué."
                    );
                }
                result.add(treasury);
            } catch (IllegalArgumentException exception) {
                safeToRewrite = false;
                logger.warning(
                        "[Trésor] Entrée ignorée : "
                                + exception.getMessage()
                );
            }
        }

        return new DocumentResult(
                true,
                safeToRewrite,
                List.copyOf(result)
        );
    }

    private VillageTreasury parseTreasury(ConfigurationSection section) {
        int planVersion = requiredInt(section, "plan-version");
        int villageId = requiredInt(section, "village-id");
        UUID ownerId = requiredUuid(section, "owner-id");
        UUID worldId = requiredUuid(section, "world-id");
        String worldName = requiredString(
                section,
                "world-name",
                128
        );

        VillageTreasury.Cuboid room = parseCuboid(
                requiredSection(section, "room"),
                "room"
        );
        List<VillageTreasury.Cuboid> zones = parseCuboidList(
                section.get("protected-zones"),
                "protected-zones",
                1,
                MAX_PROTECTED_ZONES
        );
        List<VillageTreasury.BlockPoint> containers = parsePointList(
                section.get("containers"),
                "containers",
                1,
                MAX_CONTAINERS
        );
        VillageTreasury.BlockPoint ledger = parsePoint(
                requiredSection(section, "ledger"),
                "ledger"
        );
        List<VillageTreasury.BlockPoint> doors = parsePointList(
                section.get("doors"),
                "doors",
                1,
                MAX_DOORS
        );

        return new VillageTreasury(
                planVersion,
                villageId,
                ownerId,
                worldId,
                worldName,
                room,
                zones,
                containers,
                ledger,
                doors,
                nonNegativeLong(section, "created-at"),
                nonNegativeLong(section, "next-tax-at"),
                boundedInt(
                        requiredInt(section, "pending-taxes"),
                        0,
                        100_000,
                        "pending-taxes"
                ),
                nonNegativeLong(section, "deposited-items")
        );
    }

    public synchronized boolean save(
            Collection<VillageTreasury> treasuries) {
        List<VillageTreasury> snapshot = new ArrayList<>();
        Set<Integer> villageIds = new HashSet<>();
        if (treasuries != null) {
            for (VillageTreasury treasury : treasuries) {
                if (treasury == null) {
                    continue;
                }
                if (!villageIds.add(treasury.villageId())) {
                    logger.severe(
                            "[Trésor] Sauvegarde refusée : identifiant "
                                    + treasury.villageId() + " dupliqué."
                    );
                    return false;
                }
                snapshot.add(treasury.snapshot());
            }
        }
        if (snapshot.size() > MAX_TREASURIES) {
            logger.severe(
                    "[Trésor] Sauvegarde refusée : trop de salles."
            );
            return false;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", SCHEMA_VERSION);

        /*
         * Une vraie ConfigurationSection est créée même lorsque la collection
         * est vide. Cela garantit qu'un fichier contenant « treasuries: {} »
         * reste relisible comme une sauvegarde vide valide.
         */
        ConfigurationSection root = yaml.createSection("treasuries");
        for (VillageTreasury treasury : snapshot) {
            ConfigurationSection section = root.createSection(
                    Integer.toString(treasury.villageId())
            );
            for (Map.Entry<String, Object> entry
                    : serializeTreasury(treasury).entrySet()) {
                section.set(entry.getKey(), entry.getValue());
            }
        }

        File parent = file.getParentFile();
        File tempFile = new File(
                parent,
                file.getName() + ".tmp"
        );
        File backupTemp = new File(
                parent,
                backupFile.getName() + ".tmp"
        );

        try {
            Files.createDirectories(parent.toPath());
            yaml.save(tempFile);

            if (Files.size(tempFile.toPath()) > MAX_FILE_BYTES) {
                throw new IOException(
                        "Le fichier temporaire dépasse la taille maximale."
                );
            }

            if (file.isFile() && !preserveBackupOnNextSave) {
                Files.copy(
                        file.toPath(),
                        backupTemp.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES
                );
                moveAtomically(backupTemp, backupFile);
            }

            moveAtomically(tempFile, file);
            preserveBackupOnNextSave = false;
            return true;
        } catch (IOException | RuntimeException exception) {
            logger.log(
                    Level.SEVERE,
                    "[Trésor] Impossible de sauvegarder les salles "
                            + "des coffres.",
                    exception
            );
            deleteQuietly(tempFile);
            deleteQuietly(backupTemp);
            return false;
        }
    }

    private Map<String, Object> serializeTreasury(
            VillageTreasury treasury) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("plan-version", treasury.planVersion());
        section.put("village-id", treasury.villageId());
        section.put("owner-id", treasury.ownerId().toString());
        section.put("world-id", treasury.worldId().toString());
        section.put("world-name", treasury.worldName());
        section.put("created-at", treasury.createdAtMillis());
        section.put("next-tax-at", treasury.nextTaxAtMillis());
        section.put("pending-taxes", treasury.pendingTaxes());
        section.put("deposited-items", treasury.depositedItems());
        section.put("room", serializeCuboid(treasury.roomBounds()));

        List<Map<String, Object>> zones = new ArrayList<>();
        for (VillageTreasury.Cuboid zone
                : treasury.protectedZones()) {
            zones.add(serializeCuboid(zone));
        }
        section.put("protected-zones", zones);

        List<Map<String, Object>> containers = new ArrayList<>();
        for (VillageTreasury.BlockPoint point
                : treasury.containers()) {
            containers.add(serializePoint(point));
        }
        section.put("containers", containers);
        section.put("ledger", serializePoint(treasury.ledger()));

        List<Map<String, Object>> doors = new ArrayList<>();
        for (VillageTreasury.BlockPoint point : treasury.doors()) {
            doors.add(serializePoint(point));
        }
        section.put("doors", doors);
        return section;
    }

    private Map<String, Object> serializePoint(
            VillageTreasury.BlockPoint point) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", point.x());
        map.put("y", point.y());
        map.put("z", point.z());
        return map;
    }

    private Map<String, Object> serializeCuboid(
            VillageTreasury.Cuboid cuboid) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("min-x", cuboid.minX());
        map.put("max-x", cuboid.maxX());
        map.put("min-y", cuboid.minY());
        map.put("max-y", cuboid.maxY());
        map.put("min-z", cuboid.minZ());
        map.put("max-z", cuboid.maxZ());
        return map;
    }

    private List<VillageTreasury.BlockPoint> parsePointList(
            Object raw,
            String field,
            int minimum,
            int maximum) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "La clé " + field + " doit être une liste."
            );
        }
        if (list.size() < minimum || list.size() > maximum) {
            throw new IllegalArgumentException(
                    "Taille invalide pour " + field + "."
            );
        }

        List<VillageTreasury.BlockPoint> points = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            Object value = list.get(index);
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(
                        field + "[" + index + "] doit être une table."
                );
            }
            points.add(parsePoint(map, field + "[" + index + "]"));
        }
        return points;
    }

    private List<VillageTreasury.Cuboid> parseCuboidList(
            Object raw,
            String field,
            int minimum,
            int maximum) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "La clé " + field + " doit être une liste."
            );
        }
        if (list.size() < minimum || list.size() > maximum) {
            throw new IllegalArgumentException(
                    "Taille invalide pour " + field + "."
            );
        }

        List<VillageTreasury.Cuboid> cuboids = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            Object value = list.get(index);
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(
                        field + "[" + index + "] doit être une table."
                );
            }
            cuboids.add(parseCuboid(
                    map,
                    field + "[" + index + "]"
            ));
        }
        return cuboids;
    }

    private VillageTreasury.BlockPoint parsePoint(
            ConfigurationSection section,
            String field) {
        return new VillageTreasury.BlockPoint(
                sectionInt(section, "x", field),
                sectionInt(section, "y", field),
                sectionInt(section, "z", field)
        );
    }

    private VillageTreasury.Cuboid parseCuboid(
            ConfigurationSection section,
            String field) {
        return new VillageTreasury.Cuboid(
                sectionInt(section, "min-x", field),
                sectionInt(section, "max-x", field),
                sectionInt(section, "min-y", field),
                sectionInt(section, "max-y", field),
                sectionInt(section, "min-z", field),
                sectionInt(section, "max-z", field)
        );
    }

    private int sectionInt(ConfigurationSection section,
                           String key,
                           String field) {
        Object value = section.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    field + "." + key + " doit être un entier."
            );
        }
        long parsed = number.longValue();
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    field + "." + key + " est hors limites."
            );
        }
        return (int) parsed;
    }

    private VillageTreasury.BlockPoint parsePoint(
            Map<?, ?> map,
            String field) {
        return new VillageTreasury.BlockPoint(
                mapInt(map, "x", field),
                mapInt(map, "y", field),
                mapInt(map, "z", field)
        );
    }

    private VillageTreasury.Cuboid parseCuboid(
            Map<?, ?> map,
            String field) {
        return new VillageTreasury.Cuboid(
                mapInt(map, "min-x", field),
                mapInt(map, "max-x", field),
                mapInt(map, "min-y", field),
                mapInt(map, "max-y", field),
                mapInt(map, "min-z", field),
                mapInt(map, "max-z", field)
        );
    }

    private int mapInt(Map<?, ?> map,
                       String key,
                       String field) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    field + "." + key + " doit être un entier."
            );
        }
        long parsed = number.longValue();
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    field + "." + key + " est hors limites."
            );
        }
        return (int) parsed;
    }

    private ConfigurationSection requiredSection(
            ConfigurationSection section,
            String key) {
        ConfigurationSection child =
                section.getConfigurationSection(key);
        if (child == null) {
            throw new IllegalArgumentException(
                    "La clé " + key + " doit être une section."
            );
        }
        return child;
    }

    private int requiredInt(ConfigurationSection section, String key) {
        Object value = section.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    "La clé " + key + " doit être un entier."
            );
        }
        long parsed = number.longValue();
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "La clé " + key + " est hors limites."
            );
        }
        return (int) parsed;
    }

    private long nonNegativeLong(ConfigurationSection section, String key) {
        Object value = section.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    "La clé " + key + " doit être un entier long."
            );
        }
        long parsed = number.longValue();
        if (parsed < 0L) {
            throw new IllegalArgumentException(
                    "La clé " + key + " ne peut pas être négative."
            );
        }
        return parsed;
    }

    private UUID requiredUuid(ConfigurationSection section, String key) {
        String raw = requiredString(section, key, 64);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "UUID invalide pour " + key + "."
            );
        }
    }

    private String requiredString(ConfigurationSection section,
                                  String key,
                                  int maximumLength) {
        Object value = section.get(key);
        if (!(value instanceof String text)
                || text.isBlank()
                || text.length() > maximumLength) {
            throw new IllegalArgumentException(
                    "Texte invalide pour " + key + "."
            );
        }
        return text;
    }

    private int boundedInt(int value,
                           int minimum,
                           int maximum,
                           String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    "Valeur hors limites pour " + field + "."
            );
        }
        return value;
    }

    private int parseInteger(String value, String field) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Entier invalide pour " + field + "."
            );
        }
    }

    private void quarantinePrimary() {
        if (!file.isFile()) {
            return;
        }
        File quarantine = new File(
                file.getParentFile(),
                file.getName() + ".corrupt-"
                        + System.currentTimeMillis()
        );
        try {
            moveAtomically(file, quarantine);
        } catch (IOException exception) {
            logger.log(
                    Level.WARNING,
                    "[Trésor] Impossible d'isoler le fichier corrompu.",
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

    private void deleteQuietly(File target) {
        try {
            Files.deleteIfExists(target.toPath());
        } catch (IOException ignored) {
            // Le prochain enregistrement remplacera ce temporaire.
        }
    }

    public record LoadResult(
            List<VillageTreasury> treasuries,
            boolean writable) {

        public LoadResult {
            treasuries = List.copyOf(treasuries);
        }
    }

    private record DocumentResult(
            boolean readable,
            boolean safeToRewrite,
            List<VillageTreasury> treasuries) {

        private static DocumentResult unreadable() {
            return new DocumentResult(false, false, List.of());
        }
    }
}
