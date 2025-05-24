package org.example;  // adapte si ton package diffère

import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.IronGolem;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Golem sentinelle d’un champ.
 * - Pas de propriétaire : il ne suit aucun joueur.
 * - Reste dans un rayon défini autour de son point d’apparition.
 * - Si attaqué, il se bat normalement ; sinon, il patrouille/jardin.
 */
public final class Golem {

    private static final double DEFAULT_RADIUS = 15.0; // rayon par défaut
    private static final long   TICK_RATE   = 40L;    // 2 s entre chaque check

    private final JavaPlugin plugin;
    private final IronGolem  golem;
    private final Location   home;                    // centre de garde
    private final double     radius;                  // rayon de garde
    private final boolean    ignoreVertical;          // ignore la hauteur ?

    /*------------------------------------------------------------
     * Constructeur : spawne un golem « Garde du champ »
     *-----------------------------------------------------------*/
    public Golem(JavaPlugin plugin, Location spawn) {
        this(plugin, spawn, DEFAULT_RADIUS, false);
    }

    /** Constructeur avec rayon personnalisé */
    public Golem(JavaPlugin plugin, Location spawn, double radius) {
        this(plugin, spawn, radius, false);
    }

    /** Constructeur complet */
    public Golem(JavaPlugin plugin,
                 Location spawn,
                 double radius,
                 boolean ignoreVertical) {
        this.plugin = plugin;
        this.home = spawn.clone();
        this.radius = radius;
        this.ignoreVertical = ignoreVertical;

        this.golem = spawn.getWorld().spawn(spawn, IronGolem.class, g -> {
            g.setCustomName("Garde du champ");
            g.setCustomNameVisible(true);
            g.setPlayerCreated(true);                      // non hostile joueurs
            g.getAttribute(Attribute.MOVEMENT_SPEED)
                    .setBaseValue(0.35);
        });

        startGuardTask();
    }

    /*------------------------------------------------------------
     * API publique
     *-----------------------------------------------------------*/
    /** Récupère l’entité golem (utile pour vérifier la vie, l’UUID, etc.) */
    public IronGolem getGolem() { return golem; }

    /** Supprime proprement le golem */
    public void remove() {
        if (golem != null && !golem.isDead()) {
            golem.remove();
        }
    }

    /*------------------------------------------------------------
     * Boucle de garde : toutes les 2 s
     *-----------------------------------------------------------*/
    private void startGuardTask() {
        new BukkitRunnable() {
            @Override public void run() {
                if (golem == null || golem.isDead()) { cancel(); return; }

                // S'il est engagé contre une cible valide, on ne touche à rien
                if (golem.getTarget() != null && !golem.getTarget().isDead())
                    return;

                Location current = golem.getLocation();
                double distSq;
                if (ignoreVertical) {
                    double dx = current.getX() - home.getX();
                    double dz = current.getZ() - home.getZ();
                    distSq = dx * dx + dz * dz;
                } else {
                    distSq = current.distanceSquared(home);
                }
                if (distSq <= radius * radius) return; // déjà proche

                // Tente un path-finding vers home
                boolean pathing = golem.getPathfinder().moveTo(home, 1.0);
                if (!pathing) {
                    // Dernier recours : téléportation douce
                    golem.teleport(home);
                }
            }
        }.runTaskTimer(plugin, TICK_RATE, TICK_RATE);
    }
}
