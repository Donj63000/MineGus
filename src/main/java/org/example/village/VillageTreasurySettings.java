package org.example.village;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * Paramètres validés du système d'impôt de la salle des coffres.
 */
public record VillageTreasurySettings(
        boolean enabled,
        int minimumIntervalSeconds,
        int maximumIntervalSeconds,
        long maintenancePeriodTicks,
        int maximumPendingTaxes,
        int catchUpLimitPerCycle,
        int depositsPerCycle,
        int minimumItemAmount,
        int maximumItemAmount,
        int doorOpenSeconds,
        boolean ownerOnlyContainers,
        int autosaveSeconds) {

    private static final String ROOT = "village.treasury.";

    public VillageTreasurySettings {
        minimumIntervalSeconds = bounded(
                minimumIntervalSeconds,
                1,
                3_600
        );
        maximumIntervalSeconds = bounded(
                maximumIntervalSeconds,
                minimumIntervalSeconds,
                86_400
        );
        maintenancePeriodTicks = boundedLong(
                maintenancePeriodTicks,
                1L,
                20L * 60L
        );
        maximumPendingTaxes = bounded(
                maximumPendingTaxes,
                1,
                100_000
        );
        catchUpLimitPerCycle = bounded(
                catchUpLimitPerCycle,
                1,
                4_096
        );
        depositsPerCycle = bounded(
                depositsPerCycle,
                1,
                64
        );
        minimumItemAmount = bounded(
                minimumItemAmount,
                1,
                64
        );
        maximumItemAmount = bounded(
                maximumItemAmount,
                minimumItemAmount,
                64
        );
        doorOpenSeconds = bounded(doorOpenSeconds, 1, 60);
        autosaveSeconds = bounded(autosaveSeconds, 5, 3_600);
    }

    public static VillageTreasurySettings from(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        FileConfiguration config = plugin.getConfig();
        return new VillageTreasurySettings(
                config.getBoolean(ROOT + "enabled", true),
                config.getInt(ROOT + "tax.min-interval-seconds", 1),
                config.getInt(ROOT + "tax.max-interval-seconds", 60),
                config.getLong(ROOT + "maintenance-period-ticks", 20L),
                config.getInt(ROOT + "tax.max-pending", 512),
                config.getInt(ROOT + "tax.catch-up-limit-per-cycle", 256),
                config.getInt(ROOT + "tax.deposits-per-cycle", 1),
                config.getInt(ROOT + "tax.minimum-item-amount", 1),
                config.getInt(ROOT + "tax.maximum-item-amount", 16),
                config.getInt(ROOT + "door-open-seconds", 6),
                config.getBoolean(ROOT + "owner-only-containers", true),
                config.getInt(ROOT + "autosave-seconds", 30)
        );
    }

    public long minimumIntervalMillis() {
        return minimumIntervalSeconds * 1_000L;
    }

    public long maximumIntervalMillis() {
        return maximumIntervalSeconds * 1_000L;
    }

    public long autosavePeriodTicks() {
        return autosaveSeconds * 20L;
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long boundedLong(long value,
                                    long minimum,
                                    long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
