package org.example.tutorial;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * État exclusivement en mémoire d'une visite en cours.
 *
 * <p>Chaque session possède son monde, ses entités et ses tâches. Cette
 * propriété rend le nettoyage idempotent et empêche une visite de toucher à
 * celle d'un autre joueur.</p>
 */
final class TutorialSession {

    private final UUID playerId;
    private final TutorialPlayerSnapshot returnSnapshot;
    private final World world;
    private final String worldName;
    private final Set<UUID> spawnedEntities = new LinkedHashSet<>();
    private final Set<BukkitTask> tasks = new LinkedHashSet<>();
    private final Set<Long> chunkTickets = new LinkedHashSet<>();

    private BossBar bossBar;
    private UUID donjBotId;
    private TutorialSceneBuilder.Station station =
            TutorialSceneBuilder.Station.WELCOME;
    private int phaseToken;
    private int internalTeleports;
    private long lastRecallNoticeAt;
    private boolean entered;
    private boolean ending;

    TutorialSession(
            UUID playerId,
            TutorialPlayerSnapshot returnSnapshot,
            World world) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.returnSnapshot =
                Objects.requireNonNull(returnSnapshot, "returnSnapshot");
        this.world = Objects.requireNonNull(world, "world");
        this.worldName = world.getName();
    }

    UUID playerId() {
        return playerId;
    }

    TutorialPlayerSnapshot returnSnapshot() {
        return returnSnapshot;
    }

    World world() {
        return world;
    }

    String worldName() {
        return worldName;
    }

    TutorialSceneBuilder.Station station() {
        return station;
    }

    void station(TutorialSceneBuilder.Station station) {
        this.station = Objects.requireNonNull(station, "station");
    }

    boolean entered() {
        return entered;
    }

    void markEntered() {
        entered = true;
    }

    boolean ending() {
        return ending;
    }

    boolean beginEnding() {
        if (ending) {
            return false;
        }
        ending = true;
        phaseToken++;
        return true;
    }

    int nextPhase() {
        return ++phaseToken;
    }

    int currentPhase() {
        return phaseToken;
    }

    boolean isPhase(int token) {
        return !ending && phaseToken == token;
    }

    void beginInternalTeleport() {
        internalTeleports++;
    }

    void endInternalTeleport() {
        internalTeleports = Math.max(0, internalTeleports - 1);
    }

    boolean isInternalTeleport() {
        return internalTeleports > 0;
    }

    boolean maySendRecallNotice(long nowMillis) {
        if (nowMillis - lastRecallNoticeAt < 4_000L) {
            return false;
        }
        lastRecallNoticeAt = nowMillis;
        return true;
    }

    void bossBar(BossBar bossBar) {
        this.bossBar = bossBar;
    }

    BossBar bossBar() {
        return bossBar;
    }

    void donjBot(ArmorStand armorStand) {
        Objects.requireNonNull(armorStand, "armorStand");
        donjBotId = armorStand.getUniqueId();
        trackEntity(armorStand);
    }

    ArmorStand donjBot() {
        if (donjBotId == null) {
            return null;
        }
        Entity entity = Bukkit.getEntity(donjBotId);
        return entity instanceof ArmorStand armorStand
                && armorStand.isValid()
                && !armorStand.isDead()
                ? armorStand
                : null;
    }

    void trackEntity(Entity entity) {
        if (entity != null) {
            spawnedEntities.add(entity.getUniqueId());
        }
    }

    boolean trackChunkTicket(int chunkX, int chunkZ) {
        return chunkTickets.add(chunkKey(chunkX, chunkZ));
    }

    Set<Long> chunkTickets() {
        return Set.copyOf(chunkTickets);
    }

    void clearChunkTickets() {
        chunkTickets.clear();
    }

    static int chunkX(long key) {
        return (int) (key >> 32);
    }

    static int chunkZ(long key) {
        return (int) key;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFF_FFFFL);
    }

    void trackTask(BukkitTask task) {
        if (task != null) {
            tasks.add(task);
        }
    }

    /**
     * Annule toutes les actions futures avant de retirer les acteurs visuels.
     * La méthode accepte plusieurs appels afin de simplifier tous les chemins
     * d'erreur et d'arrêt du plugin.
     */
    void cancelRuntime() {
        for (BukkitTask task : tasks) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        tasks.clear();

        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }

        for (UUID entityId : spawnedEntities) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
        }
        spawnedEntities.clear();
        donjBotId = null;
    }
}
