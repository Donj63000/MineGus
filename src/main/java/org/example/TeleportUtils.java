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

        if (Bukkit.isPrimaryThread()) {
            return entity.teleport(location);
        }
        if (TELEPORT_ASYNC == null) {
            /*
             * Appeler Entity#teleport hors du thread principal corrompt l'état
             * Bukkit. En l'absence de l'API Paper asynchrone, l'échec explicite
             * est plus sûr qu'un repli synchrone illégal.
             */
            return false;
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
            /*
             * Même règle en cas d'échec réflexif : le code appelant pourra
             * réessayer sur le thread principal sans déclencher d'accès monde
             * concurrent.
             */
            return false;
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
