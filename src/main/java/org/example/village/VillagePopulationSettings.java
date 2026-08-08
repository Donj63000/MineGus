package org.example.village;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;

/**
 * Configuration validée de la population générée par {@code /village}.
 *
 * <p>Les bornes empêchent une configuration erronée de créer des centaines
 * d'entités par minute ou, à l'inverse, des spawners actifs à chaque tick.
 * Toutes les valeurs sont exprimées en ticks serveur lorsque leur nom se
 * termine par {@code Ticks}.</p>
 */
public record VillagePopulationSettings(
        int initialResidents,
        int villagerSpawnerCount,
        int populationCap,
        int minDelayTicks,
        int maxDelayTicks,
        int maxNearbyEntities,
        int requiredPlayerRange,
        int spawnRange,
        int lifetimeTicks) {

    private static final int MIN_NAMED_AND_GUARDS = 12;

    public VillagePopulationSettings {
        initialResidents = clamp(initialResidents, 0, 40);
        villagerSpawnerCount = clamp(villagerSpawnerCount, 0, 16);

        /*
         * Le quota doit toujours pouvoir contenir les marchands nommés,
         * le maire, les artisans et les résidents initiaux. Cela évite que la
         * première passe de limitation supprime aussitôt des PNJ importants.
         */
        int minimumCap = Math.min(
                120,
                initialResidents + MIN_NAMED_AND_GUARDS
        );
        populationCap = clamp(populationCap, minimumCap, 120);

        minDelayTicks = clamp(minDelayTicks, 200, 72_000);
        maxDelayTicks = clamp(maxDelayTicks, minDelayTicks, 72_000);
        maxNearbyEntities = clamp(maxNearbyEntities, 1, 12);
        requiredPlayerRange = clamp(requiredPlayerRange, 8, 64);
        spawnRange = clamp(spawnRange, 1, 6);
        lifetimeTicks = clamp(lifetimeTicks, 0, 1_728_000);
    }

    /**
     * Charge la configuration avec des valeurs par défaut adaptées à une ville
     * d'environ trente maisons.
     */
    public static VillagePopulationSettings from(
            FileConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return new VillagePopulationSettings(
                configuration.getInt(
                        "village.population.initial-residents",
                        14
                ),
                configuration.getInt(
                        "village.population.villager-spawners",
                        8
                ),
                configuration.getInt(
                        "village.population.cap",
                        56
                ),
                configuration.getInt(
                        "village.population.spawner.min-delay-ticks",
                        1_200
                ),
                configuration.getInt(
                        "village.population.spawner.max-delay-ticks",
                        2_400
                ),
                configuration.getInt(
                        "village.population.spawner.max-nearby-entities",
                        4
                ),
                configuration.getInt(
                        "village.population.spawner.required-player-range",
                        24
                ),
                configuration.getInt(
                        "village.population.spawner.spawn-range",
                        3
                ),
                clamp(
                        configuration.getInt(
                                "village.population.lifetime-minutes",
                                0
                        ),
                        0,
                        1_440
                ) * 20 * 60
        );
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
