package org.example.tutorial;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistance atomique des destinations de retour du tutoriel.
 *
 * <p>Une entrée est écrite avant la première téléportation. Elle n'est retirée
 * qu'après le retour réussi : un crash peut donc au pire provoquer un second
 * retour à la connexion suivante, jamais une perte de position.</p>
 */
public final class TutorialReturnStore {

    private static final String ROOT = "players";

    private final Object lock = new Object();
    private final Path file;
    private final Executor ioExecutor;
    private final Logger logger;
    private final Map<UUID, TutorialPlayerSnapshot> entries =
            new LinkedHashMap<>();

    public TutorialReturnStore(
            Path dataFolder,
            Executor ioExecutor,
            Logger logger) {
        this.file = Objects.requireNonNull(dataFolder, "dataFolder")
                .resolve("tutorial-returns.yml");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.logger = Objects.requireNonNull(logger, "logger");
        load();
    }

    public TutorialPlayerSnapshot get(UUID playerId) {
        synchronized (lock) {
            return entries.get(playerId);
        }
    }

    public boolean contains(UUID playerId) {
        synchronized (lock) {
            return entries.containsKey(playerId);
        }
    }

    public void put(UUID playerId, TutorialPlayerSnapshot snapshot) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(snapshot, "snapshot");
        synchronized (lock) {
            entries.put(playerId, snapshot);
        }
    }

    public TutorialPlayerSnapshot remove(UUID playerId) {
        synchronized (lock) {
            return entries.remove(playerId);
        }
    }

    public Map<UUID, TutorialPlayerSnapshot> snapshot() {
        synchronized (lock) {
            return Collections.unmodifiableMap(
                    new LinkedHashMap<>(entries)
            );
        }
    }

    /**
     * Enfile une sauvegarde complète sur l'exécuteur série du tutoriel.
     */
    public CompletableFuture<Void> saveAsync() {
        Map<UUID, TutorialPlayerSnapshot> copy;
        synchronized (lock) {
            copy = new LinkedHashMap<>(entries);
        }
        return CompletableFuture.runAsync(() -> writeSnapshot(copy), ioExecutor);
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (IOException | InvalidConfigurationException exception) {
            quarantineCorruptFile(exception);
            return;
        }

        ConfigurationSection root = yaml.getConfigurationSection(ROOT);
        if (root == null) {
            return;
        }

        for (String rawId : root.getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(rawId);
            } catch (IllegalArgumentException ignored) {
                logger.warning(
                        "Entrée de récupération tutoriel ignorée : UUID invalide '"
                                + rawId + "'."
                );
                continue;
            }

            ConfigurationSection section =
                    root.getConfigurationSection(rawId);
            if (section == null) {
                continue;
            }

            try {
                TutorialPlayerSnapshot snapshot =
                        TutorialPlayerSnapshot.readFrom(section);
                if (snapshot != null) {
                    entries.put(playerId, snapshot);
                }
            } catch (RuntimeException exception) {
                logger.log(
                        Level.WARNING,
                        "Entrée de récupération tutoriel invalide pour "
                                + playerId,
                        exception
                );
            }
        }
    }

    private void writeSnapshot(
            Map<UUID, TutorialPlayerSnapshot> snapshot) {
        Path parent = file.getParent();
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");

        try {
            Files.createDirectories(parent);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("format-version", 1);
            yaml.set("saved-at", Instant.now().toString());

            for (Map.Entry<UUID, TutorialPlayerSnapshot> entry
                    : snapshot.entrySet()) {
                ConfigurationSection section = yaml.createSection(
                        ROOT + "." + entry.getKey()
                );
                entry.getValue().writeTo(section);
            }

            yaml.save(temporary.toFile());
            /*
             * force(true) demande au système de pousser aussi les métadonnées.
             * Le fichier est minuscule, le coût reste sur le thread d'E/S dédié.
             */
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE)) {
                channel.force(true);
            }

            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException exception) {
            logger.log(
                    Level.SEVERE,
                    "Impossible de sauvegarder les retours du tutoriel.",
                    exception
            );
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                logger.log(
                        Level.FINE,
                        "Nettoyage du fichier temporaire impossible.",
                        cleanupFailure
                );
            }
            throw new IllegalStateException(
                    "Sauvegarde des retours tutoriel impossible",
                    exception
            );
        }
    }

    private void quarantineCorruptFile(Exception cause) {
        Path backup = file.resolveSibling(
                file.getFileName()
                        + ".corrupt-"
                        + System.currentTimeMillis()
                        + ".bak"
        );
        try {
            Files.move(
                    file,
                    backup,
                    StandardCopyOption.REPLACE_EXISTING
            );
            logger.log(
                    Level.SEVERE,
                    "tutorial-returns.yml est invalide et a été déplacé vers "
                            + backup.getFileName(),
                    cause
            );
        } catch (IOException moveFailure) {
            logger.log(
                    Level.SEVERE,
                    "tutorial-returns.yml est invalide et n'a pas pu être "
                            + "mis en quarantaine.",
                    moveFailure
            );
        }
    }
}
