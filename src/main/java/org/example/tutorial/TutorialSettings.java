package org.example.tutorial;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Configuration immuable du tutoriel.
 *
 * <p>Toutes les valeurs sont bornées ici : le reste du système peut ainsi
 * travailler avec des invariants simples, même si un administrateur écrit une
 * valeur aberrante dans config.yml.</p>
 */
public record TutorialSettings(
        boolean enabled,
        int maxConcurrentSessions,
        double recallDistance,
        double walkSpeed,
        int blocksPerTick,
        long buildBudgetNanos,
        int chunkLoadTimeoutTicks,
        int autoExitDelayTicks,
        String worldNamePrefix,
        double worldBorderSize,
        long worldTime,
        long configuredSeed) {

    private static final Pattern UNSAFE_WORLD_CHARACTER =
            Pattern.compile("[^a-zA-Z0-9_-]");

    public TutorialSettings {
        maxConcurrentSessions = clamp(maxConcurrentSessions, 1, 32);
        recallDistance = clamp(recallDistance, 5.0D, 64.0D);
        walkSpeed = clamp(walkSpeed, 0.06D, 0.40D);
        blocksPerTick = clamp(blocksPerTick, 100, 8_000);
        buildBudgetNanos = clamp(
                buildBudgetNanos,
                1_000_000L,
                25_000_000L
        );
        chunkLoadTimeoutTicks = clamp(
                chunkLoadTimeoutTicks,
                100,
                2_400
        );
        autoExitDelayTicks = clamp(autoExitDelayTicks, 0, 12_000);
        worldNamePrefix = sanitizeWorldPrefix(worldNamePrefix);
        worldBorderSize = clamp(worldBorderSize, 400.0D, 8_000.0D);
        worldTime = Math.floorMod(worldTime, 24_000L);
    }

    /**
     * Lit et valide la section tutorial du fichier de configuration.
     */
    public static TutorialSettings from(FileConfiguration config) {
        Objects.requireNonNull(config, "config");

        double budgetMs = config.getDouble(
                "tutorial.build-time-budget-ms",
                6.0D
        );
        int timeoutSeconds = config.getInt(
                "tutorial.chunk-load-timeout-seconds",
                20
        );
        int autoExitSeconds = config.getInt(
                "tutorial.auto-exit-delay-seconds",
                15
        );

        return new TutorialSettings(
                config.getBoolean("tutorial.enabled", true),
                config.getInt("tutorial.max-concurrent-sessions", 4),
                config.getDouble("tutorial.recall-distance", 15.0D),
                config.getDouble(
                        "tutorial.npc-walk-speed-blocks-per-tick",
                        0.17D
                ),
                config.getInt("tutorial.blocks-per-tick", 1_000),
                Math.round(budgetMs * 1_000_000.0D),
                Math.multiplyExact(clamp(timeoutSeconds, 5, 120), 20),
                Math.multiplyExact(clamp(autoExitSeconds, 0, 600), 20),
                config.getString(
                        "tutorial.world.name-prefix",
                        "minegus_tuto_"
                ),
                config.getDouble("tutorial.world.border-size", 800.0D),
                config.getLong("tutorial.world.time", 6_000L),
                config.getLong("tutorial.world.seed", 0L)
        );
    }

    /**
     * Produit une graine différente par session quand la valeur configurée est
     * zéro. Une graine explicite rend au contraire le paysage reproductible.
     */
    public long seedForNewWorld() {
        if (configuredSeed != 0L) {
            return configuredSeed;
        }

        /*
         * Zéro signifie « graine automatique » dans la configuration. Ne jamais
         * le retourner évite qu'une valeur générée soit réinterprétée comme le
         * marqueur spécial lors d'un diagnostic ou d'une future sérialisation.
         */
        long generated;
        do {
            generated = ThreadLocalRandom.current().nextLong();
        } while (generated == 0L);
        return generated;
    }

    public double recallDistanceSquared() {
        return recallDistance * recallDistance;
    }

    /**
     * Le préfixe est volontairement ASCII et court. Cela évite les séparateurs
     * de chemin, les noms réservés et les dossiers trop longs sur Windows.
     */
    static String sanitizeWorldPrefix(String rawPrefix) {
        String value = rawPrefix == null
                ? ""
                : rawPrefix.trim().toLowerCase(Locale.ROOT);
        value = UNSAFE_WORLD_CHARACTER.matcher(value).replaceAll("_");
        value = value.replaceAll("_+", "_");

        if (value.isBlank() || "_".equals(value) || "-".equals(value)) {
            value = "minegus_tuto_";
        }
        if (!value.endsWith("_")) {
            value += "_";
        }
        if (value.length() > 24) {
            value = value.substring(0, 23) + "_";
        }
        return value;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clamp(
            double value,
            double minimum,
            double maximum) {
        if (!Double.isFinite(value)) {
            return minimum;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
