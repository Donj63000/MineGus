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
        int gateGuardCount,
        int plazaGuardCount,
        int keepEntranceGuardCount,
        int keepInteriorGuardCount,
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
        int pathRefreshTicks,
        int localTargetSearchRadius,
        int hardRecoverySeconds,
        double sentryEngagementRadius,
        double sentryReturnRadius,
        double rampartEngagementRadius,
        double rampartCorridorRadius,
        double urbanCombatMargin,
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
        gateGuardCount = bounded(gateGuardCount, 0, 4);
        plazaGuardCount = bounded(plazaGuardCount, 0, 4);
        keepEntranceGuardCount = bounded(keepEntranceGuardCount, 0, 4);
        keepInteriorGuardCount = bounded(keepInteriorGuardCount, 0, 4);
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
        routeAdvanceTimeoutSeconds = bounded(
                routeAdvanceTimeoutSeconds,
                5,
                120
        );
        missingEntityLoadedChecks = bounded(missingEntityLoadedChecks, 2, 12);
        stuckSeconds = bounded(stuckSeconds, 5, 120);
        pathRefreshTicks = bounded(pathRefreshTicks, 10, 100);
        localTargetSearchRadius = bounded(localTargetSearchRadius, 0, 4);
        hardRecoverySeconds = bounded(
                hardRecoverySeconds,
                stuckSeconds,
                300
        );
        sentryEngagementRadius = bounded(
                sentryEngagementRadius,
                4.0D,
                24.0D
        );
        sentryReturnRadius = bounded(
                sentryReturnRadius,
                0.75D,
                Math.min(4.0D, sentryEngagementRadius)
        );
        rampartEngagementRadius = bounded(
                rampartEngagementRadius,
                8.0D,
                32.0D
        );
        rampartCorridorRadius = bounded(
                rampartCorridorRadius,
                1.0D,
                5.0D
        );
        urbanCombatMargin = bounded(urbanCombatMargin, 0.0D, 32.0D);
        maxHealth = bounded(maxHealth, 4.0D, 100.0D);
        meleeDamageCap = bounded(meleeDamageCap, 1.0D, 20.0D);
        arrowDamageCap = bounded(arrowDamageCap, 1.0D, 20.0D);
        movementSpeed = bounded(movementSpeed, 0.10D, 0.40D);
        followRange = bounded(
                followRange,
                Math.max(detectionRadius, rampartEngagementRadius),
                128.0D
        );
        knockbackResistance = bounded(knockbackResistance, 0.0D, 1.0D);
        incomingHostileDamageMultiplier = bounded(
                incomingHostileDamageMultiplier,
                1.0D,
                8.0D
        );
    }

    /**
     * Charge les paramètres depuis la configuration principale du plugin.
     *
     * <p>Les clés introduites par le plan version 2 disposent toutes d'une
     * valeur de repli. Une ancienne copie de {@code config.yml} reste donc
     * utilisable sans intervention manuelle.</p>
     */
    public static VillageKnightSettings from(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        FileConfiguration config = plugin.getConfig();

        return new VillageKnightSettings(
                config.getBoolean(ROOT + "enabled", true),
                config.getInt(ROOT + "urban-patrols", 3),
                config.getInt(ROOT + "soldiers-per-patrol", 4),
                config.getInt(ROOT + "posts.gate", 2),
                config.getInt(ROOT + "posts.plaza", 2),
                config.getInt(ROOT + "posts.keep-entrance", 2),
                config.getInt(ROOT + "posts.keep-interior", 2),
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
                config.getInt(ROOT + "navigation.path-refresh-ticks", 30),
                config.getInt(ROOT + "navigation.local-target-search-radius", 2),
                config.getInt(ROOT + "navigation.hard-recovery-seconds", 40),
                config.getDouble(ROOT + "leashes.sentry-engagement-radius", 10.0D),
                config.getDouble(ROOT + "leashes.sentry-return-radius", 2.0D),
                config.getDouble(ROOT + "leashes.rampart-engagement-radius", 16.0D),
                config.getDouble(ROOT + "leashes.rampart-corridor-radius", 2.5D),
                config.getDouble(ROOT + "leashes.urban-combat-margin", 8.0D),
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

    public long pathRefreshMillis() {
        return pathRefreshTicks * 50L;
    }

    public long hardRecoveryMillis() {
        return hardRecoverySeconds * 1_000L;
    }

    /**
     * Compatibilité de lecture pour le code ou les outils qui affichaient
     * auparavant un unique nombre de « postes stratégiques ».
     */
    public int strategicGuardCount() {
        return gateGuardCount
                + plazaGuardCount
                + keepEntranceGuardCount
                + keepInteriorGuardCount;
    }

    public int expectedUnitCount() {
        int meleePatrols = urbanPatrolCount * soldiersPerPatrol;
        int archers = wallArchersEnabled ? wallArcherCount : 0;
        return strategicGuardCount() + meleePatrols + archers;
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
