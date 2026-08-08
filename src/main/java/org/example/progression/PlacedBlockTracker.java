package org.example.progression;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mémorise les blocs posés ou générés artificiellement qui donnent de l'XP.
 *
 * <p>Le marquage est stocké dans le {@link PersistentDataContainer} du chunk :
 * une roche ou un minerai posé puis récupéré après un redémarrage ne peut donc
 * pas être recyclé à l'infini. Les écritures NBT sont regroupées et vidées
 * périodiquement ainsi qu'au déchargement du chunk.</p>
 */
final class PlacedBlockTracker {

    private static final byte SATURATED = 1;

    private final JavaPlugin plugin;
    private final boolean enabled;
    private final int maxEntriesPerChunk;
    private final org.bukkit.NamespacedKey positionsKey;
    private final org.bukkit.NamespacedKey saturatedKey;
    private final Map<ChunkKey, ChunkState> states = new HashMap<>();

    private BukkitTask flushTask;
    private boolean persistenceWarningLogged;

    PlacedBlockTracker(
            JavaPlugin plugin,
            boolean enabled,
            int maxEntriesPerChunk,
            long flushTicks) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.maxEntriesPerChunk = Math.max(256, maxEntriesPerChunk);
        this.positionsKey = new org.bukkit.NamespacedKey(
                plugin,
                "progression_placed_blocks");
        this.saturatedKey = new org.bukkit.NamespacedKey(
                plugin,
                "progression_placed_saturated");

        if (enabled) {
            this.flushTask = Bukkit.getScheduler().runTaskTimer(
                    plugin,
                    this::flushDirtyLoadedChunks,
                    Math.max(20L, flushTicks),
                    Math.max(20L, flushTicks));
        }
    }

    boolean isEnabled() {
        return enabled;
    }

    /**
     * Marque un bloc comme non éligible à l'XP de minage.
     */
    void mark(Block block) {
        if (!enabled || block == null) {
            return;
        }

        ChunkState state = stateFor(block.getChunk());
        if (state.saturated) {
            return;
        }

        if (state.positions.add(encodePosition(block))) {
            state.dirty = true;
            if (state.positions.size() >= maxEntriesPerChunk) {
                // Un chunk de construction massif ne doit pas produire un NBT
                // sans limite. Une fois saturé, tout minage XP y est neutralisé.
                state.positions.clear();
                state.saturated = true;
            }
        }
    }

    /**
     * Retourne {@code true} si le bloc était marqué, puis retire son marqueur.
     *
     * <p>Un chunk saturé reste volontairement saturé : cette stratégie sûre
     * empêche qu'un très grand bâtiment en pierre devienne une ferme à XP.</p>
     */
    boolean consume(Block block) {
        if (!enabled || block == null) {
            return false;
        }

        ChunkState state = stateFor(block.getChunk());
        if (state.saturated) {
            return true;
        }

        boolean removed = state.positions.remove(encodePosition(block));
        if (removed) {
            state.dirty = true;
        }
        return removed;
    }

    /**
     * Transfère atomiquement les marqueurs d'un ensemble déplacé par piston.
     *
     * <p>La photographie préalable est indispensable : dans une ligne de
     * blocs, la destination du premier est souvent la source du suivant. Un
     * transfert bloc par bloc pourrait alors absorber le marqueur que nous
     * venons juste de déplacer.</p>
     */
    void moveAll(
            Iterable<? extends Block> movedBlocks,
            BlockFace movementDirection) {
        if (!enabled
                || movedBlocks == null
                || movementDirection == null) {
            return;
        }

        ArrayList<BlockMovement> movements = new ArrayList<>();
        for (Block source : movedBlocks) {
            if (source == null) {
                continue;
            }
            movements.add(new BlockMovement(
                    source,
                    source.getRelative(movementDirection),
                    isMarked(source)));
        }

        // Retirer d'abord toutes les anciennes positions, puis seulement
        // inscrire les destinations afin de gérer correctement les recouvrements.
        for (BlockMovement movement : movements) {
            if (movement.artificial()) {
                consume(movement.source());
            }
        }
        for (BlockMovement movement : movements) {
            if (movement.artificial()) {
                mark(movement.destination());
            }
        }
    }

    /**
     * Sauvegarde puis retire le cache associé au chunk en cours de déchargement.
     */
    void onChunkUnload(Chunk chunk) {
        if (!enabled || chunk == null) {
            return;
        }

        ChunkKey key = ChunkKey.of(chunk);
        ChunkState state = states.remove(key);
        if (state != null && state.dirty) {
            flush(chunk, state);
        }
    }

    void shutdown() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        flushDirtyLoadedChunks();
        states.clear();
    }

    static int encodePosition(int x, int y, int z) {
        /*
         * 20 bits pour Y et 4 bits pour chaque coordonnée locale X/Z.
         * L'encodage sert uniquement de clé d'égalité et reste donc stable
         * pour les hauteurs négatives comme pour les mondes personnalisés.
         */
        return ((y & 0xFFFFF) << 8)
                | ((z & 0x0F) << 4)
                | (x & 0x0F);
    }

    private static int encodePosition(Block block) {
        return encodePosition(block.getX(), block.getY(), block.getZ());
    }

    boolean isMarked(Block block) {
        if (!enabled || block == null) {
            return false;
        }
        ChunkState state = stateFor(block.getChunk());
        return state.saturated
                || state.positions.contains(encodePosition(block));
    }

    private ChunkState stateFor(Chunk chunk) {
        ChunkKey key = ChunkKey.of(chunk);
        return states.computeIfAbsent(key, ignored -> load(chunk));
    }

    private ChunkState load(Chunk chunk) {
        ChunkState state = new ChunkState();
        try {
            PersistentDataContainer pdc = chunk.getPersistentDataContainer();
            Byte saturated = pdc.get(saturatedKey, PersistentDataType.BYTE);
            state.saturated = saturated != null && saturated == SATURATED;

            if (!state.saturated) {
                int[] encoded = pdc.get(
                        positionsKey,
                        PersistentDataType.INTEGER_ARRAY);
                if (encoded != null) {
                    for (int value : encoded) {
                        state.positions.add(value);
                        if (state.positions.size() >= maxEntriesPerChunk) {
                            state.positions.clear();
                            state.saturated = true;
                            state.dirty = true;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable throwable) {
            logPersistenceWarning(throwable);
        }
        return state;
    }

    private void flushDirtyLoadedChunks() {
        if (!enabled || states.isEmpty()) {
            return;
        }

        for (Map.Entry<ChunkKey, ChunkState> entry
                : new ArrayList<>(states.entrySet())) {
            ChunkState state = entry.getValue();
            if (!state.dirty) {
                continue;
            }

            ChunkKey key = entry.getKey();
            World world = Bukkit.getWorld(key.worldId());
            if (world == null || !world.isChunkLoaded(key.x(), key.z())) {
                // ChunkUnloadEvent effectue normalement la dernière écriture.
                // Si le monde a déjà disparu, conserver l'état en mémoire évite
                // de prétendre à tort que les données ont été persistées.
                continue;
            }
            flush(world.getChunkAt(key.x(), key.z()), state);
        }
    }

    private void flush(Chunk chunk, ChunkState state) {
        try {
            PersistentDataContainer pdc = chunk.getPersistentDataContainer();
            if (state.saturated) {
                pdc.set(saturatedKey, PersistentDataType.BYTE, SATURATED);
                pdc.remove(positionsKey);
            } else {
                pdc.remove(saturatedKey);
                if (state.positions.isEmpty()) {
                    pdc.remove(positionsKey);
                } else {
                    int[] encoded = state.positions.stream()
                            .mapToInt(Integer::intValue)
                            .sorted()
                            .toArray();
                    pdc.set(
                            positionsKey,
                            PersistentDataType.INTEGER_ARRAY,
                            encoded);
                }
            }
            state.dirty = false;
        } catch (Throwable throwable) {
            logPersistenceWarning(throwable);
        }
    }

    private void logPersistenceWarning(Throwable throwable) {
        if (persistenceWarningLogged) {
            return;
        }
        persistenceWarningLogged = true;
        plugin.getLogger().warning(
                "[Progression] Le marquage persistant des blocs poses "
                        + "n'est pas disponible dans cet environnement ("
                        + throwable.getClass().getSimpleName()
                        + "). La protection reste active en memoire.");
    }

    private record BlockMovement(
            Block source,
            Block destination,
            boolean artificial) {
    }

    private record ChunkKey(UUID worldId, int x, int z) {
        static ChunkKey of(Chunk chunk) {
            return new ChunkKey(
                    chunk.getWorld().getUID(),
                    chunk.getX(),
                    chunk.getZ());
        }
    }

    private static final class ChunkState {
        private final Set<Integer> positions = new HashSet<>();
        private boolean saturated;
        private boolean dirty;
    }
}
