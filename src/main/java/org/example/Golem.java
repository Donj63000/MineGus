package org.example;

import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.IronGolem;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.lang.reflect.Method;

/**
 * Golem sentinelle.
 *
 * <p>Depuis Paper 1.20, {@link IronGolem} possède une méthode
 * {@code setAnger(int)}. Lorsque disponible, le constructeur l’utilise
 * pour appliquer 600 ticks de colère (30 s).</p>
 */
public final class Golem {

    private static final double DEFAULT_RADIUS = 15.0;
    private static final long   TICK_RATE      = 40L; // 2 s

    private final JavaPlugin plugin;
    private final IronGolem  golem;
    private final Location   home;
    private final double     radius;
    private final boolean    ignoreVertical;

    /* ──────────────────────────── CTORS ─────────────────────────────── */
    public Golem(JavaPlugin plugin, Location spawn) {
        this(plugin, spawn, DEFAULT_RADIUS, false);
    }

    public Golem(JavaPlugin plugin, Location spawn, double radius) {
        this(plugin, spawn, radius, false);
    }

    public Golem(JavaPlugin plugin, Location spawn, double radius, boolean ignoreVertical) {
        this.plugin = plugin;
        this.home   = spawn.clone();
        this.radius = radius;
        this.ignoreVertical = ignoreVertical;

        this.golem = spawn.getWorld().spawn(spawn, IronGolem.class, g -> {
            g.setCustomName("Garde du champ");
            g.setCustomNameVisible(true);
            g.setPlayerCreated(true);
            // ↑ non‑hostile envers les joueurs

            // 30 s de colère si la méthode existe (Paper 1.20+)
            try {
                Method m = g.getClass().getMethod("setAnger", int.class);
                m.invoke(g, 600);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception ignored) {
            }

            // Vitesse 0.35 si l’attribut existe sur la version courante
            if (g.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
                g.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.35);
            }
        });

        startGuardTask();
    }

    public IronGolem getGolem() { return golem; }

    public void remove() {
        if (golem != null && !golem.isDead()) golem.remove();
    }

    /* ─────────────────────── BOUCLE DE GARDE ────────────────────────── */
    private void startGuardTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (golem == null || golem.isDead()) { cancel(); return; }
                if (golem.getTarget() != null && !golem.getTarget().isDead()) return;

                Location cur = golem.getLocation();
                double distSq = ignoreVertical
                        ? Math.pow(cur.getX() - home.getX(), 2) + Math.pow(cur.getZ() - home.getZ(), 2)
                        : cur.distanceSquared(home);

                if (distSq <= radius * radius) return;

                /* ─ Path‑finding Paper (si dispo) ─ */
                boolean ok = false;
                try { ok = golem.getPathfinder().moveTo(home, 1.0); }
                catch (NoSuchMethodError | UnsupportedOperationException ignored) { }

                if (!ok) golem.teleport(home);
            }
        }.runTaskTimer(plugin, TICK_RATE, TICK_RATE);
    }
}
