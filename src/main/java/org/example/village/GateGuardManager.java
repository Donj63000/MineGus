package org.example.village;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.IronGolem;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Maintient deux golems gardiens à l'entrée principale du village.
 */
public final class GateGuardManager {

    private GateGuardManager() {}

    private static final int REQUIRED_GUARDS = 2;
    private static final double MAX_DISTANCE_SQ = 400.0; // 20 blocs
    private static final double SCAN_RADIUS = 32.0;
    private static final List<Vector> OFFSETS = List.of(
            new Vector(-1.5, 0, -0.5),
            new Vector(1.5, 0, -0.5)
    );
    private static final String GUARD_TAG = "MG_VILLAGE_GUARD";
    private static final String GUARD_NAME_PLAIN = "Gardien du village";
    private static final String GUARD_DISPLAY_NAME = ChatColor.BLUE + GUARD_NAME_PLAIN;

    private static final Map<Integer, GateGuardTask> TASKS = new HashMap<>();

    public static void ensureGuards(JavaPlugin plugin, Location gateCenter, int villageId) {
        stopGuardTask(villageId);
        GateGuardTask task = new GateGuardTask(plugin, gateCenter.clone(), villageId);
        task.runTaskTimer(plugin, 40L, 40L);
        TASKS.put(villageId, task);
    }

    public static void stopGuardTask(int villageId) {
        GateGuardTask task = TASKS.remove(villageId);
        if (task != null) {
            task.cancel();
            task.despawn();
        }
    }

    private static final class GateGuardTask extends BukkitRunnable {
        private final JavaPlugin plugin;
        private final Location gateCenter;
        private final int villageId;
        private final List<UUID> guardians = new ArrayList<>();

        GateGuardTask(JavaPlugin plugin, Location gateCenter, int villageId) {
            this.plugin = plugin;
            this.gateCenter = gateCenter;
            this.villageId = villageId;
        }

        @Override
        public void run() {
            syncExistingGuards();

            while (guardians.size() < REQUIRED_GUARDS) {
                spawnGuard(guardians.size());
            }

            for (int i = 0; i < guardians.size(); i++) {
                UUID id = guardians.get(i);
                Entity entity = Bukkit.getEntity(id);
                if (!(entity instanceof IronGolem golem) || !golem.isValid()) {
                    continue;
                }
                keepNearGate(golem, OFFSETS.get(i % OFFSETS.size()));
            }
        }

        private void spawnGuard(int index) {
            World world = gateCenter.getWorld();
            if (world == null) {
                return;
            }
            Vector offset = OFFSETS.get(index % OFFSETS.size());
            Location spawnLoc = gateCenter.clone().add(offset);
            IronGolem golem = (IronGolem) world.spawnEntity(spawnLoc, EntityType.IRON_GOLEM);
            VillageEntityManager.tagEntity(golem, plugin, villageId);
            applyGuardMetadata(golem);
            guardians.add(golem.getUniqueId());
        }

        private void syncExistingGuards() {
            guardians.removeIf(uuid -> {
                Entity entity = Bukkit.getEntity(uuid);
                return !(entity instanceof IronGolem golem) || golem.isDead() || !golem.isValid();
            });

            World world = gateCenter.getWorld();
            if (world == null) {
                guardians.clear();
                return;
            }

            List<IronGolem> candidates = world.getNearbyEntities(gateCenter, SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS).stream()
                    .filter(IronGolem.class::isInstance)
                    .map(IronGolem.class::cast)
                    .filter(this::isGuardCandidate)
                    .sorted(Comparator.comparingDouble(golem -> golem.getLocation().distanceSquared(gateCenter)))
                    .toList();

            Set<UUID> adopted = new LinkedHashSet<>();
            int selectionCount = 0;
            for (IronGolem golem : candidates) {
                if (selectionCount < REQUIRED_GUARDS) {
                    VillageEntityManager.tagEntity(golem, plugin, villageId);
                    applyGuardMetadata(golem);
                    adopted.add(golem.getUniqueId());
                    selectionCount++;
                } else {
                    golem.remove();
                }
            }

            guardians.removeIf(id -> !adopted.contains(id));
            for (UUID id : adopted) {
                if (!guardians.contains(id)) {
                    guardians.add(id);
                }
            }
        }

        private boolean isGuardCandidate(IronGolem golem) {
            if (golem.isDead() || !golem.isValid()) {
                return false;
            }
            if (golem.getScoreboardTags().contains(GUARD_TAG)) {
                return true;
            }
            String name = golem.getCustomName();
            if (name == null) {
                return false;
            }
            String stripped = ChatColor.stripColor(name);
            return GUARD_NAME_PLAIN.equals(stripped);
        }

        private void applyGuardMetadata(IronGolem golem) {
            golem.setCustomName(GUARD_DISPLAY_NAME);
            golem.setCustomNameVisible(true);
            golem.setPlayerCreated(true);
            golem.setRemoveWhenFarAway(false);
            golem.addScoreboardTag(GUARD_TAG);
        }

        private void keepNearGate(IronGolem golem, Vector offset) {
            Location target = gateCenter.clone().add(offset);
            if (golem.getLocation().distanceSquared(target) > MAX_DISTANCE_SQ) {
                golem.teleport(target);
            }
        }

        void despawn() {
            for (UUID uuid : guardians) {
                Entity entity = Bukkit.getEntity(uuid);
                if (entity != null) {
                    entity.remove();
                }
            }
            guardians.clear();
        }
    }
}
