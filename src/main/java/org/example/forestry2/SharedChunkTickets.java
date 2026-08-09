package org.example.forestry2;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Je mutualise les tickets de chunks utilises par les differents services du plugin.
 */
public final class SharedChunkTickets {

    private static final Object MONITOR = new Object();
    private static final Map<Plugin, Map<ChunkKey, Reservation>> RESERVATIONS = new IdentityHashMap<>();

    private SharedChunkTickets() {
        throw new IllegalStateException("Je ne dois pas instancier ce gestionnaire utilitaire.");
    }

    /**
     * Je reserve logiquement un chunk pour un plugin.
     *
     * <p>Si Paper signale qu'un ticket existe deja, je conserve uniquement une
     * reservation logique non proprietaire. Je ne retirerai donc pas ce ticket
     * externe lors de la derniere liberation.</p>
     *
     * @param plugin plugin proprietaire de la reservation logique
     * @param chunk chunk a maintenir charge
     */
    public static void acquire(Plugin plugin, Chunk chunk) {
        Objects.requireNonNull(plugin, "Je dois recevoir un plugin.");
        Objects.requireNonNull(chunk, "Je dois recevoir un chunk.");
        ChunkKey key = ChunkKey.from(chunk);

        synchronized (MONITOR) {
            Map<ChunkKey, Reservation> pluginReservations = RESERVATIONS.get(plugin);
            Reservation reservation = pluginReservations == null ? null : pluginReservations.get(key);
            if (reservation != null) {
                if (reservation.references == Integer.MAX_VALUE) {
                    throw new IllegalStateException("Je ne peux pas depasser le nombre maximal de reservations.");
                }
                reservation.references++;
                return;
            }

            boolean physicalTicketAdded = chunk.addPluginChunkTicket(plugin);
            if (pluginReservations == null) {
                pluginReservations = new HashMap<>();
                RESERVATIONS.put(plugin, pluginReservations);
            }
            pluginReservations.put(key, new Reservation(chunk, physicalTicketAdded));
        }
    }

    /**
     * Je libere une reservation logique et je retire le ticket que je possede
     * lorsque sa derniere reference disparait.
     *
     * @param plugin plugin proprietaire de la reservation logique
     * @param chunk chunk a liberer
     */
    public static void release(Plugin plugin, Chunk chunk) {
        Objects.requireNonNull(plugin, "Je dois recevoir un plugin.");
        Objects.requireNonNull(chunk, "Je dois recevoir un chunk.");
        ChunkKey key = ChunkKey.from(chunk);

        synchronized (MONITOR) {
            Map<ChunkKey, Reservation> pluginReservations = RESERVATIONS.get(plugin);
            if (pluginReservations == null) {
                return;
            }
            Reservation reservation = pluginReservations.get(key);
            if (reservation == null) {
                return;
            }
            if (reservation.references > 1) {
                reservation.references--;
                return;
            }

            if (reservation.physicalTicketOwned) {
                reservation.chunk.removePluginChunkTicket(plugin);
            }
            pluginReservations.remove(key);
            removeEmptyPluginEntry(plugin, pluginReservations);
        }
    }

    /**
     * Je libere toutes les reservations d'un plugin sans toucher a celles des
     * autres instances de plugin. Je poursuis le nettoyage apres chaque echec
     * et je propage le premier une fois toutes les tentatives effectuees.
     *
     * @param plugin plugin dont je libere les reservations
     */
    public static void releaseAll(Plugin plugin) {
        Objects.requireNonNull(plugin, "Je dois recevoir un plugin.");

        synchronized (MONITOR) {
            Map<ChunkKey, Reservation> pluginReservations = RESERVATIONS.get(plugin);
            if (pluginReservations == null) {
                return;
            }

            Throwable firstFailure = null;
            for (Map.Entry<ChunkKey, Reservation> entry
                    : new ArrayList<>(pluginReservations.entrySet())) {
                Reservation reservation = entry.getValue();
                try {
                    if (reservation.physicalTicketOwned) {
                        reservation.chunk.removePluginChunkTicket(plugin);
                    }
                    pluginReservations.remove(entry.getKey());
                } catch (RuntimeException | Error failure) {
                    if (firstFailure == null) {
                        firstFailure = failure;
                    } else if (firstFailure != failure) {
                        firstFailure.addSuppressed(failure);
                    }
                }
            }

            removeEmptyPluginEntry(plugin, pluginReservations);
            rethrow(firstFailure);
        }
    }

    static void clearForTests() {
        synchronized (MONITOR) {
            RESERVATIONS.clear();
        }
    }

    private static void removeEmptyPluginEntry(Plugin plugin,
                                               Map<ChunkKey, Reservation> pluginReservations) {
        if (pluginReservations.isEmpty()) {
            RESERVATIONS.remove(plugin);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private record ChunkKey(UUID worldId, int x, int z) {

        private static ChunkKey from(Chunk chunk) {
            World world = Objects.requireNonNull(chunk.getWorld(), "Je dois trouver le monde du chunk.");
            UUID worldId = Objects.requireNonNull(world.getUID(), "Je dois trouver l'identifiant du monde.");
            return new ChunkKey(worldId, chunk.getX(), chunk.getZ());
        }
    }

    private static final class Reservation {

        private final Chunk chunk;
        private final boolean physicalTicketOwned;
        private int references = 1;

        private Reservation(Chunk chunk, boolean physicalTicketOwned) {
            this.chunk = chunk;
            this.physicalTicketOwned = physicalTicketOwned;
        }
    }
}
