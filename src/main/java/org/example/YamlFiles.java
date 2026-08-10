package org.example;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Outils communs pour écrire les petits fichiers YAML du plugin sans laisser
 * un fichier partiellement écrit après un arrêt brutal du processus.
 */
public final class YamlFiles {

    private YamlFiles() {
        // Classe utilitaire : aucune instance n'est nécessaire.
    }

    /**
     * Écrit d'abord dans un fichier temporaire situé sur le même volume, puis
     * remplace la cible en une seule opération lorsque le système le permet.
     *
     * @return {@code true} si le fichier cible a bien été remplacé.
     */
    public static boolean saveAtomically(
            YamlConfiguration yaml,
            File target,
            Logger logger,
            String description) {
        Objects.requireNonNull(yaml, "yaml");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(logger, "logger");

        Path targetPath = target.toPath().toAbsolutePath();
        Path parent = targetPath.getParent();
        Path temporary = null;

        try {
            if (parent == null) {
                throw new IOException("Le fichier cible n'a pas de dossier parent.");
            }
            Files.createDirectories(parent);
            temporary = Files.createTempFile(
                    parent,
                    targetPath.getFileName().toString() + ".",
                    ".tmp"
            );
            yaml.save(temporary.toFile());

            /*
             * Une sauvegarde lisible facilite la récupération manuelle lorsque
             * le fichier principal a été modifié par erreur.
             */
            if (Files.isRegularFile(targetPath)) {
                Path backup = targetPath.resolveSibling(
                        targetPath.getFileName().toString() + ".bak"
                );
                try {
                    Files.copy(
                            targetPath,
                            backup,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                } catch (IOException backupFailure) {
                    logger.log(
                            Level.WARNING,
                            "Impossible de mettre à jour la sauvegarde de "
                                    + description + ".",
                            backupFailure
                    );
                }
            }

            try {
                Files.move(
                        temporary,
                        targetPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(
                        temporary,
                        targetPath,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
            return true;
        } catch (IOException failure) {
            logger.log(
                    Level.SEVERE,
                    "Impossible de sauvegarder " + description + ".",
                    failure
            );
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    logger.log(
                            Level.FINE,
                            "Impossible de supprimer le fichier YAML temporaire.",
                            cleanupFailure
                    );
                }
            }
        }
    }
}
