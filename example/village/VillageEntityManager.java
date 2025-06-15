package org.example.village;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Gestion des PNJ / golems d’un village : spawn initial, nettoyage, quota global.
 */
public final class VillageEntityManager {

    public static final String TAG = "MINEGUS_VILLAGE_ID";
    private VillageEntityManager() {}

    /* ====== Quota global ====== */
    private static final int DEFAULT_CAP = 100;

    /* professions « génériques » utilisées pour la population de base */
    private static final List<Villager.Profession> GENERIC = List.of(
            Villager.Profession.FARMER,
            Villager.Profession.TOOLSMITH,
            Villager.Profession.CLERIC,
            Villager.Profession.FLETCHER,
            Villager.Profession.LIBRARIAN,
            Villager.Profession.MASON
    );

    /* ------------------- SPAWN INITIAL ------------------- */
    public static void spawnInitial(Plugin plugin,
                                    Location center,
                                    int villageId,
                                    int ttlTicks) {

        World w = center.getWorld();
        Random R = new Random();

        // 6 PNJ génériques
        for (int i = 0; i < 6; i++) {
            Villager v = (Villager) w.spawnEntity(randAround(center), EntityType.VILLAGER);
            v.setProfession(GENERIC.get(R.nextInt(GENERIC.size())));
            tagEntity(v, plugin, villageId);
        }

        /* PNJ spécialisés */
        spawnNamed(w, plugin, randAround(center), "§eMineur",      Villager.Profession.TOOLSMITH, villageId);
        spawnNamed(w, plugin, randAround(center), "§6Bûcheron",    Villager.Profession.FLETCHER,  villageId);
        spawnNamed(w, plugin, randAround(center), "§aAgriculteur", Villager.Profession.FARMER,    villageId);
        spawnNamed(w, plugin, randAround(center), "§dÉleveur",     Villager.Profession.SHEPHERD,  villageId);

        /* 2 golems d’office */
        for (int i = 0; i < 2; i++) {
            IronGolem g = (IronGolem) w.spawnEntity(randAround(center), EntityType.IRON_GOLEM);
            g.setPlayerCreated(true);
            tagEntity(g, plugin, villageId);
        }

        /* nettoyage final (expire au bout de ttlTicks) */
        new BukkitRunnable() {
            @Override public void run() { cleanup(plugin, villageId); }
        }.runTaskLater(plugin, ttlTicks);

        /* démarrage de la tâche « quota » (100 NPC max) */
        startCapTask(plugin, center, DEFAULT_CAP);
    }

    /* ------------------- TÂCHE QUOTA (100 NPC) ------------------- */
    /** Lance (ou relance) la vérification périodique du quota de NPC pour ce village. */
    public static void startCapTask(Plugin plugin, Location center, int cap) {
        int villageId = center.getBlockX() ^ center.getBlockZ() ^ center.getWorld().getUID().hashCode();

        /* vérifie toutes les 10 s */
        new BukkitRunnable() {
            @Override public void run() { enforceCap(plugin, villageId, center, cap); }
        }.runTaskTimer(plugin, 200L, 200L);
    }

    /** Supprime les NPC excédentaires jusqu’à atteindre {@code cap}. */
    private static void enforceCap(Plugin plugin, int villageId,
                                   Location pivot, int cap) {

        List<LivingEntity> tagged = new ArrayList<>();
        for (World w : plugin.getServer().getWorlds()) {
            w.getEntities().stream()
                    .filter(e -> e.hasMetadata(TAG)
                            && e.getMetadata(TAG).get(0).asInt() == villageId
                            && e instanceof LivingEntity le
                            && !(le instanceof Player))            // on ignore les joueurs
                    .map(e -> (LivingEntity) e)
                    .forEach(tagged::add);
        }

        if (tagged.size() <= cap) return;

        /* trie par distance décroissante à la place : on supprime d’abord les plus loin */
        tagged.sort(Comparator.comparingDouble(e -> -e.getLocation().distanceSquared(pivot)));

        for (int i = cap; i < tagged.size(); i++) tagged.get(i).remove();
    }

    /* ------------------- UTIL PUBLIC ------------------- */
    public static void cleanup(Plugin p, int villageId) {
        for (World w : p.getServer().getWorlds()) {
            w.getEntities().stream()
                    .filter(e -> e.hasMetadata(TAG)
                            && e.getMetadata(TAG).get(0).asInt() == villageId)
                    .forEach(Entity::remove);
        }
    }

    public static void tagEntity(Entity e, Plugin p, int id) {
        e.setPersistent(true);
        e.setMetadata(TAG, new FixedMetadataValue(p, id));
    }

    /* ------------------- PRIVÉS ------------------- */
    private static Location randAround(Location c) {
        Random R = new Random();
        return c.clone().add(R.nextInt(10) - 5, 1, R.nextInt(10) - 5);
    }

    private static void spawnNamed(World w, Plugin p, Location l,
                                   String name, Villager.Profession prof, int id) {
        Villager v = (Villager) w.spawnEntity(l, EntityType.VILLAGER);
        v.setCustomName(name);
        v.setCustomNameVisible(true);
        v.setProfession(prof);
        tagEntity(v, p, id);
    }
}
