package org.example;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.lang.reflect.Method;

/**
 * Méthodes utilitaires liées à la téléportation.
 */
public final class TeleportUtils {

    private TeleportUtils() {
        // Utility class
    }

    /**
     * Tente d'appeler teleportAsync via réflexion.
     * Si indisponible, utilise teleport(Location).
     *
     * @param entity   entité à téléporter
     * @param location destination
     */
    public static void safeTeleport(Entity entity, Location location) {
        try {
            Method teleportAsync = Entity.class.getMethod("teleportAsync", Location.class);
            teleportAsync.invoke(entity, location);
        } catch (NoSuchMethodException e) {
            entity.teleport(location);
        } catch (Exception e) {
            entity.teleport(location);
        }
    }
}
