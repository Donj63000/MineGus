package org.example.mineur.store;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.example.mineur.MiningCursor;
import org.example.mineur.MiningPattern;
import org.example.mineur.MiningSessionState;
import org.example.mineur.MiningSpeed;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lecture et écriture résilientes de {@code sessions.yml}.
 *
 * <p>Les entrées dont le monde n'est pas encore chargé, ou dont une partie est
 * momentanément illisible, sont conservées telles quelles lors des sauvegardes.
 * Un démarrage ne peut donc plus effacer silencieusement une session d'un monde
 * chargé plus tard par un gestionnaire de mondes.</p>
 */
public final class SessionStore {

    private final File file;
    private final File backupFile;
    private final Logger logger;

    /**
     * Entrées non matérialisables à ce démarrage, réinjectées à la sauvegarde.
     * L'ordre d'insertion stabilise le diff YAML et facilite les diagnostics.
     */
    private final Map<String, Map<String, Object>> preservedEntries = new LinkedHashMap<>();

    /**
     * Après une restauration depuis .bak, la première sauvegarde ne doit pas
     * remplacer cette bonne sauvegarde par le fichier primaire corrompu.
     */
    private boolean preserveBackupOnNextSave;

    public SessionStore(File dataFolder) {
        this(dataFolder, Logger.getLogger("MineGus"));
    }

    public SessionStore(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "sessions.yml");
        this.backupFile = new File(dataFolder, "sessions.yml.bak");
        this.logger = logger != null ? logger : Logger.getLogger(SessionStore.class.getName());
    }

    public synchronized List<MiningSessionState> load() {
        preservedEntries.clear();
        preserveBackupOnNextSave = false;

        LoadResult primary = loadFrom(file);
        if (primary.readable()) {
            preservedEntries.putAll(primary.preserved());
            return primary.sessions();
        }

        /*
         * Ne jamais recopier un fichier primaire corrompu vers .bak lors de la
         * prochaine sauvegarde. On le met de côté afin de conserver une preuve
         * exploitable par l'administrateur et de permettre une réparation propre.
         */
        preserveBackupOnNextSave = true;
        quarantineUnreadablePrimary();

        if (!backupFile.isFile()) {
            logger.severe("[Mineur] Aucune sauvegarde sessions.yml.bak exploitable ; "
                    + "les sessions actives démarrent vides sans écraser le fichier mis en quarantaine.");
            return new ArrayList<>();
        }

        logger.warning("[Mineur] sessions.yml est illisible ; tentative de restauration depuis sessions.yml.bak.");
        LoadResult backup = loadFrom(backupFile);
        if (!backup.readable()) {
            logger.severe("[Mineur] La sauvegarde sessions.yml.bak est elle aussi illisible.");
            return new ArrayList<>();
        }

        preservedEntries.putAll(backup.preserved());
        preserveBackupOnNextSave = true;
        logger.warning("[Mineur] Sessions restaurées depuis sessions.yml.bak. "
                + "Le prochain enregistrement réparera sessions.yml.");
        return backup.sessions();
    }

    private void quarantineUnreadablePrimary() {
        if (!file.isFile()) {
            return;
        }

        File parent = file.getParentFile();
        File quarantine = parent != null
                ? new File(parent, file.getName() + ".corrupt-" + System.currentTimeMillis())
                : new File(file.getPath() + ".corrupt-" + System.currentTimeMillis());
        try {
            try {
                Files.move(file.toPath(), quarantine.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(file.toPath(), quarantine.toPath());
            }
            logger.severe("[Mineur] Fichier de sessions corrompu déplacé vers "
                    + quarantine.getAbsolutePath() + ".");
        } catch (IOException exception) {
            /*
             * Le drapeau preserveBackupOnNextSave empêche malgré tout ce fichier
             * illisible de remplacer une éventuelle sauvegarde valide.
             */
            logger.log(Level.SEVERE,
                    "[Mineur] Impossible de mettre sessions.yml corrompu en quarantaine ; "
                            + "il sera remplacé sans être copié vers .bak.",
                    exception);
        }
    }

    private LoadResult loadFrom(File source) {
        List<MiningSessionState> sessions = new ArrayList<>();
        Map<String, Map<String, Object>> preserved = new LinkedHashMap<>();

        /*
         * L'absence du fichier au premier démarrage représente un document vide
         * valide ; elle ne doit pas déclencher une restauration obsolète.
         */
        if (!source.isFile()) {
            return new LoadResult(true, sessions, preserved);
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(source);
        } catch (IOException | InvalidConfigurationException | RuntimeException exception) {
            logger.log(Level.SEVERE, "[Mineur] Impossible de lire " + source.getName() + ".", exception);
            return new LoadResult(false, sessions, preserved);
        }

        /*
         * Un document valide contenant « sessions: {} » doit rester vide.
         * Recharger automatiquement la sauvegarde dans ce cas ressusciterait
         * des sessions volontairement supprimées.
         */
        ConfigurationSection root = yaml.getConfigurationSection("sessions");
        if (root == null) {
            /*
             * Un fichier vide ou dépourvu de clé « sessions » est valide. En
             * revanche, une clé présente avec un scalaire ou une liste indique
             * une corruption de structure : la considérer comme zéro session
             * écraserait la sauvegarde valide au prochain enregistrement.
             */
            if (yaml.get("sessions") != null) {
                logger.severe("[Mineur] Structure invalide dans " + source.getName()
                        + " : « sessions » doit être une section YAML.");
                return new LoadResult(false, sessions, preserved);
            }
            return new LoadResult(true, sessions, preserved);
        }

        Set<UUID> loadedIds = new HashSet<>();
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                logger.warning("[Mineur] Session " + key + " ignorée : section YAML invalide.");
                continue;
            }

            Map<String, Object> raw = sectionToMap(section);
            try {
                String worldId = section.getString("world");
                UUID worldUid = parseUuid(worldId);
                if (worldUid == null) {
                    logger.warning("[Mineur] Session " + key
                            + " conservée sans activation : UUID de monde invalide.");
                    preserved.put(key, raw);
                    continue;
                }

                World world = Bukkit.getWorld(worldUid);
                if (world == null) {
                    logger.warning("[Mineur] Session " + key + " différée : monde "
                            + worldUid + " non chargé.");
                    preserved.put(key, raw);
                    continue;
                }

                /*
                 * Le format historique est reconnu uniquement par ses trois
                 * coordonnées numériques et l'absence de version moderne.
                 * Sans cette détection stricte, une session v4 corrompue privée
                 * de « base » pouvait être migrée silencieusement vers l'origine
                 * du monde puis commencer à miner au mauvais endroit.
                 */
                MiningSessionState state;
                if (isLegacySession(raw)) {
                    state = migrateLegacy(world, raw);
                } else {
                    state = MiningSessionState.fromMap(world, raw);
                }

                if (state == null) {
                    logger.warning("[Mineur] Session " + key
                            + " conservée sans activation : données incomplètes.");
                    preserved.put(key, raw);
                    continue;
                }
                if (!loadedIds.add(state.id)) {
                    logger.warning("[Mineur] Session " + key
                            + " ignorée : identifiant dupliqué " + state.id + ".");
                    continue;
                }
                if (state.owner == null) {
                    state.paused = true;
                    logger.warning("[Mineur] Session " + state.id
                            + " chargée en pause car aucun propriétaire n'est enregistré.");
                }
                sessions.add(state);
            } catch (RuntimeException exception) {
                preserved.put(key, raw);
                logger.log(Level.WARNING,
                        "[Mineur] Session " + key
                                + " conservée sans bloquer les autres sessions.",
                        exception);
            }
        }
        return new LoadResult(true, sessions, preserved);
    }

    public synchronized void saveAll(List<MiningSessionState> sessions) {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("sessions");

        int index = 0;
        Set<UUID> serializedIds = new HashSet<>();
        if (sessions != null) {
            for (MiningSessionState state : sessions) {
                if (state == null) {
                    continue;
                }
                try {
                    Map<String, Object> serialized = state.toMap();
                    root.createSection(Integer.toString(index++), serialized);
                    if (state.id != null) {
                        serializedIds.add(state.id);
                    }
                } catch (RuntimeException exception) {
                    logger.log(Level.WARNING,
                            "[Mineur] Session invalide non sauvegardée"
                                    + (state.id != null ? " (" + state.id + ")" : "") + ".",
                            exception);
                }
            }
        }

        /*
         * Ne jamais supprimer les sessions d'un monde non chargé. Une entrée
         * possédant désormais le même UUID qu'une session active est toutefois
         * remplacée par la version active, plus récente.
         */
        for (Map.Entry<String, Map<String, Object>> entry : preservedEntries.entrySet()) {
            Map<String, Object> raw = entry.getValue();
            UUID preservedId = parseUuid(raw != null && raw.get("id") != null
                    ? raw.get("id").toString()
                    : null);
            if (preservedId != null && serializedIds.contains(preservedId)) {
                continue;
            }
            try {
                root.createSection(Integer.toString(index++), raw);
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING,
                        "[Mineur] Entrée différée " + entry.getKey()
                                + " impossible à réécrire.",
                        exception);
            }
        }

        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            logger.severe("[Mineur] Impossible de créer le dossier de sauvegarde " + parent + ".");
            return;
        }

        File temporary = parent != null
                ? new File(parent, file.getName() + ".tmp")
                : new File(file.getPath() + ".tmp");
        boolean saved = false;
        try {
            yaml.save(temporary);

            if (file.isFile() && !preserveBackupOnNextSave) {
                Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            try {
                Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
            saved = true;
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "[Mineur] Impossible de sauvegarder sessions.yml.", exception);
        } finally {
            if (!saved) {
                try {
                    Files.deleteIfExists(temporary.toPath());
                } catch (IOException cleanupException) {
                    logger.log(Level.FINE,
                            "[Mineur] Nettoyage du fichier temporaire impossible.",
                            cleanupException);
                }
            }
        }

        if (saved) {
            preserveBackupOnNextSave = false;
        }
    }

    private Map<String, Object> sectionToMap(ConfigurationSection section) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection child) {
                result.put(key, sectionToMap(child));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * Reconnaît exclusivement le format historique non versionné.
     */
    private boolean isLegacySession(Map<String, Object> raw) {
        return raw != null
                && !raw.containsKey("schemaVersion")
                && !raw.containsKey("base")
                && !raw.containsKey("cursor")
                && raw.get("x") instanceof Number
                && raw.get("y") instanceof Number
                && raw.get("z") instanceof Number;
    }

    private MiningSessionState migrateLegacy(World world, Map<String, Object> raw) {
        int minimumY = effectiveMinimumHeight(world);
        int maximumY = effectiveMaximumHeight(world, minimumY);
        int baseX = number(raw.get("x"), 0);
        int baseY = number(raw.get("y"), minimumY);
        int baseZ = number(raw.get("z"), 0);
        int width = Math.max(1, number(raw.get("width"), 1));
        int length = Math.max(1, number(raw.get("length"), 1));
        double minerY = decimal(raw.get("minerY"), baseY);

        baseY = Math.max(minimumY, Math.min(maximumY - 1, baseY));

        MiningSessionState state = new MiningSessionState();
        state.worldUid = world.getUID();
        state.base = world.getBlockAt(baseX, baseY, baseZ).getLocation();
        state.width = width;
        state.length = length;
        state.pattern = MiningPattern.QUARRY;
        state.speed = MiningSpeed.NORMAL;
        state.cursor = new MiningCursor(state.base, width, length);
        state.cursor.y = Math.max(minimumY, Math.min(baseY, (int) Math.round(minerY)));
        state.cursor.x = state.cursor.minX;
        state.cursor.z = state.cursor.minZ;
        state.minerY = minerY;
        state.useBarrelMaster = false;
        state.owner = parseUuid(raw.get("owner") != null ? raw.get("owner").toString() : null);
        logger.info("[Mineur] Migration d'une session v1 vers le format v4 effectuée.");
        return state;
    }

    private int effectiveMinimumHeight(World world) {
        int minimum = world.getMinHeight();
        int maximum = world.getMaxHeight();
        return maximum > minimum ? minimum : -64;
    }

    private int effectiveMaximumHeight(World world, int minimum) {
        int maximum = world.getMaxHeight();
        return maximum > minimum ? maximum : 320;
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private int number(Object value, int fallback) {
        if (!(value instanceof Number number)) {
            return fallback;
        }

        /*
         * Number.longValue() tronque silencieusement les BigInteger et
         * BigDecimal hors plage. On borne donc la valeur décimale avant toute
         * conversion afin qu'un YAML hostile ne puisse pas reboucler vers une
         * coordonnée apparemment valide.
         */
        double result = number.doubleValue();
        if (Double.isNaN(result)) {
            return fallback;
        }
        if (result <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (result >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) result;
    }

    private double decimal(Object value, double fallback) {
        if (!(value instanceof Number number)) {
            return fallback;
        }
        double result = number.doubleValue();
        return Double.isFinite(result) ? result : fallback;
    }

    private record LoadResult(boolean readable,
                              List<MiningSessionState> sessions,
                              Map<String, Map<String, Object>> preserved) {
    }
}
