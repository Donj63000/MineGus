package org.example;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.lang.reflect.Method;

/**
 * Méthodes utilitaires liées à la téléportation.
 */
public final class TeleportUtils {

    private static final Method TELEPORT_ASYNC = findTeleportAsync();

    private TeleportUtils() {
        // Classe utilitaire.
    }

    /**
     * Téléporte sans rechercher la méthode Paper à chaque animation.
     *
     * <p>Sur le thread principal, la téléportation synchrone est préférable :
     * les chunks du mineur possèdent déjà un ticket et aucune future asynchrone
     * ne peut revenir écraser sa rotation. Hors thread principal, Paper
     * {@code teleportAsync} reste utilisé lorsqu'il est disponible.</p>
     *
     * @return {@code false} si les paramètres sont invalides ou si une
     *         téléportation synchrone est refusée par un événement.
     */
    public static boolean safeTeleport(Entity entity, Location location) {
        if (entity == null || location == null || location.getWorld() == null) {
            return false;
        }

        if (Bukkit.isPrimaryThread() || TELEPORT_ASYNC == null) {
            return entity.teleport(location);
        }

        try {
            /*
             * L'appel asynchrone renvoie une future Paper. Il est volontairement
             * non bloquant ici ; « true » signifie que la requête a été soumise.
             * Les usages du mineur s'exécutent, eux, toujours sur le thread
             * principal et reçoivent donc le résultat booléen exact.
             */
            TELEPORT_ASYNC.invoke(entity, location);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return entity.teleport(location);
        }
    }

    private static Method findTeleportAsync() {
        try {
            return Entity.class.getMethod("teleportAsync", Location.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
