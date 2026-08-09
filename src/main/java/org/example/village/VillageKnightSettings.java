package org.example.village;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * Paramètres bornés du système de chevaliers du village.
 *
 * <p>Chaque valeur provenant de {@code config.yml} est limitée ici. Une erreur
 * de configuration ne peut ainsi ni créer des centaines d'entités, ni lancer
 * des recherches de monstres sur une zone démesurée.</p>
 */
public record VillageKnightSettings(
        boolean enabled,
        int urbanPatrolCount,
        int soldiersPerPatrol,
        int strategicGuardCount,
        boolean wallArchersEnabled,
        int wallArcherCount,
        int respawnDelaySeconds,
        int maintenancePeriodTicks,
        int scansPerCycle,
        double detectionRadius,
        double assistanceRadius,
        double combatLeashRadius,
        double protectionMargin,
        int maxRespondersPerThreat,
        double patrolSpeed,
        double waypointArrivalRadius,
        int routeAdvanceTimeoutSeconds,
        int missingEntityLoadedChecks,
        int stuckSeconds,
        double maxHealth,
        double meleeDamageCap,
        double arrowDamageCap,
        double movementSpeed,
        double followRange,
        double knockbackResistance,
        double incomingHostileDamageMultiplier
) {

    private static final String ROOT = "village.knights.";

    public VillageKnightSettings {
        urbanPatrolCount = bounded(urbanPatrolCount, 0, 6);
        soldiersPerPatrol = bounded(soldiersPerPatrol, 3, 5);
        strategicGuardCount = bounded(strategicGuardCount, 0, 8);
        wallArcherCount = bounded(wallArcherCount, 0, 8);
        respawnDelaySeconds = bounded(respawnDelaySeconds, 5, 3_600);
        maintenancePeriodTicks = bounded(maintenancePeriodTicks, 5, 40);
        scansPerCycle = bounded(scansPerCycle, 1, 16);
        detectionRadius = bounded(detectionRadius, 4.0D, 64.0D);
        assistanceRadius = bounded(assistanceRadius, detectionRadius, 96.0D);
        combatLeashRadius = bounded(combatLeashRadius, assistanceRadius, 128.0D);
        protectionMargin = bounded(protectionMargin, 0.0D, 64.0D);
        maxRespondersPerThreat = bounded(maxRespondersPerThreat, 1, 8);
        patrolSpeed = bounded(patrolSpeed, 0.20D, 1.50D);
        waypointArrivalRadius = bounded(waypointArrivalRadius, 1.0D, 8.0D);
        routeAdvanceTimeoutSeconds = bounded(routeAdvanceTimeoutSeconds, 5, 120);
        missingEntityLoadedChecks = bounded(missingEntityLoadedChecks, 2, 12);
        stuckSeconds = bounded(stuckSeconds, 5, 120);
        maxHealth = bounded(maxHealth, 4.0D, 100.0D);
        meleeDamageCap = bounded(meleeDamageCap, 1.0D, 20.0D);
        arrowDamageCap = bounded(arrowDamageCap, 1.0D, 20.0D);
        movementSpeed = bounded(movementSpeed, 0.10D, 0.40D);
        followRange = bounded(followRange, detectionRadius, 128.0D);
        knockbackResistance = bounded(knockbackResistance, 0.0D, 1.0D);
        incomingHostileDamageMultiplier = bounded(
                incomingHostileDamageMultiplier,
                1.0D,
                8.0D
        );
    }

    /**
     * Charge les paramètres depuis la configuration principale du plugin.
     */
    public static VillageKnightSettings from(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        FileConfiguration config = plugin.getConfig();

        return new VillageKnightSettings(
                config.getBoolean(ROOT + "enabled", true),
                config.getInt(ROOT + "urban-patrols", 3),
                config.getInt(ROOT + "soldiers-per-patrol", 4),
                config.getInt(ROOT + "strategic-guards", 4),
                config.getBoolean(ROOT + "wall-archers.enabled", true),
                config.getInt(ROOT + "wall-archers.count", 4),
                config.getInt(ROOT + "respawn-delay-seconds", 60),
                config.getInt(ROOT + "maintenance-period-ticks", 10),
                config.getInt(ROOT + "scans-per-cycle", 4),
                config.getDouble(ROOT + "detection-radius", 24.0D),
                config.getDouble(ROOT + "assistance-radius", 36.0D),
                config.getDouble(ROOT + "combat-leash-radius", 48.0D),
                config.getDouble(ROOT + "protection-margin", 16.0D),
                config.getInt(ROOT + "max-responders-per-threat", 3),
                config.getDouble(ROOT + "patrol-speed", 0.85D),
                config.getDouble(ROOT + "waypoint-arrival-radius", 2.75D),
                config.getInt(ROOT + "route-advance-timeout-seconds", 25),
                config.getInt(ROOT + "missing-entity-loaded-checks", 3),
                config.getInt(ROOT + "stuck-seconds", 20),
                config.getDouble(ROOT + "attributes.max-health", 26.0D),
                config.getDouble(ROOT + "attributes.melee-damage-cap", 6.0D),
                config.getDouble(ROOT + "attributes.arrow-damage-cap", 5.0D),
                config.getDouble(ROOT + "attributes.movement-speed", 0.27D),
                config.getDouble(ROOT + "attributes.follow-range", 40.0D),
                config.getDouble(ROOT + "attributes.knockback-resistance", 0.25D),
                config.getDouble(
                        ROOT + "attributes.incoming-hostile-damage-multiplier",
                        2.75D
                )
        );
    }

    public long respawnDelayMillis() {
        return respawnDelaySeconds * 1_000L;
    }

    public long routeAdvanceTimeoutMillis() {
        return routeAdvanceTimeoutSeconds * 1_000L;
    }

    public long stuckMillis() {
        return stuckSeconds * 1_000L;
    }

    public int expectedUnitCount() {
        int meleePatrols = urbanPatrolCount * soldiersPerPatrol;
        int archers = wallArchersEnabled ? wallArcherCount : 0;
        return strategicGuardCount + meleePatrols + archers;
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double bounded(double value,
                                  double minimum,
                                  double maximum) {
        if (!Double.isFinite(value)) {
            return minimum;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
