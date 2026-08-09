package org.example.tutorial;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Capture l'état que le tutoriel modifie temporairement.
 *
 * <p>L'inventaire, l'expérience et les effets de potion ne sont volontairement
 * pas copiés : le tutoriel ne les modifie jamais. Éviter leur sérialisation
 * réduit fortement le risque de duplication après un crash.</p>
 */
public record TutorialPlayerSnapshot(
        UUID worldId,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        GameMode gameMode,
        boolean allowFlight,
        boolean flying,
        boolean invulnerable,
        float walkSpeed,
        float flySpeed,
        int foodLevel,
        float saturation,
        float exhaustion,
        int fireTicks,
        float fallDistance
) {

    private static final String DEFAULT_WORLD_NAME = "world";
    private static final double MAX_WORLD_COORDINATE = 29_999_984.0D;

    public TutorialPlayerSnapshot {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(worldName, "worldName");
        Objects.requireNonNull(gameMode, "gameMode");
    }

    /**
     * Prend une photographie cohérente du joueur avant toute modification.
     */
    public static TutorialPlayerSnapshot capture(Player player) {
        Objects.requireNonNull(player, "player");
        Location location = player.getLocation();
        World world = Objects.requireNonNull(location.getWorld(), "world");

        return new TutorialPlayerSnapshot(
                world.getUID(),
                world.getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch(),
                player.getGameMode(),
                player.getAllowFlight(),
                player.isFlying(),
                player.isInvulnerable(),
                player.getWalkSpeed(),
                player.getFlySpeed(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExhaustion(),
                player.getFireTicks(),
                player.getFallDistance()
        );
    }

    /**
     * Retrouve la destination originale sans charger arbitrairement un monde.
     *
     * <p>Le monde est d'abord recherché par UUID, puis par nom. S'il n'est pas
     * chargé, aucune destination de remplacement n'est choisie : la sauvegarde
     * de récupération doit rester intacte jusqu'à ce que le vrai monde
     * redevienne disponible. Ce choix évite de perdre définitivement la
     * position exacte d'un joueur provenant d'un monde géré par un autre
     * plugin.</p>
     */
    public Location resolveLocation(Logger logger) {
        World world = Bukkit.getWorld(worldId);
        if (world == null) {
            world = Bukkit.getWorld(worldName);
        }

        if (world == null) {
            if (logger != null) {
                logger.warning(
                        "Le monde d'origine '" + worldName + "' (" + worldId
                                + ") n'est pas chargé ; le retour exact est "
                                + "reporté et reste sauvegardé."
                );
            }
            return null;
        }

        Location spawn = world.getSpawnLocation();
        double safeX = safeCoordinate(x, spawn.getX());
        double safeZ = safeCoordinate(z, spawn.getZ());
        double safeY = finiteOr(y, spawn.getY());
        safeY = Math.max(world.getMinHeight() + 1.0D,
                Math.min(world.getMaxHeight() - 2.0D, safeY));
        float safePitch = Math.max(
                -90.0F,
                Math.min(90.0F, finiteOr(pitch, 0.0F))
        );

        return new Location(
                world,
                safeX,
                safeY,
                safeZ,
                finiteOr(yaw, 0.0F),
                safePitch
        );
    }

    /**
     * Restaure uniquement les propriétés modifiées par le tutoriel.
     *
     * <p>La téléportation est gérée séparément par le gestionnaire, qui doit
     * marquer l'opération comme interne pour ne pas être bloquée par ses propres
     * protections.</p>
     */
    public void restoreState(Player player) {
        Objects.requireNonNull(player, "player");

        player.setGameMode(gameMode);
        player.setAllowFlight(allowFlight);
        player.setFlying(allowFlight && flying);
        player.setInvulnerable(invulnerable);
        player.setWalkSpeed(clampSpeed(walkSpeed, 0.2F));
        player.setFlySpeed(clampSpeed(flySpeed, 0.1F));
        player.setFoodLevel(Math.max(0, Math.min(20, foodLevel)));
        player.setSaturation(Math.max(
                0.0F,
                Math.min(20.0F, finiteOr(saturation, 5.0F))
        ));
        player.setExhaustion(Math.max(
                0.0F,
                Math.min(40.0F, finiteOr(exhaustion, 0.0F))
        ));
        player.setFireTicks(Math.max(0, fireTicks));
        player.setFallDistance(Math.max(
                0.0F,
                finiteOr(fallDistance, 0.0F)
        ));
    }

    /**
     * Écrit une entrée YAML indépendante de l'API entité.
     */
    public void writeTo(ConfigurationSection section) {
        Objects.requireNonNull(section, "section");

        section.set("world-uuid", worldId.toString());
        section.set("world-name", worldName);
        section.set("x", x);
        section.set("y", y);
        section.set("z", z);
        section.set("yaw", yaw);
        section.set("pitch", pitch);
        section.set("game-mode", gameMode.name());
        section.set("allow-flight", allowFlight);
        section.set("flying", flying);
        section.set("invulnerable", invulnerable);
        section.set("walk-speed", walkSpeed);
        section.set("fly-speed", flySpeed);
        section.set("food-level", foodLevel);
        section.set("saturation", saturation);
        section.set("exhaustion", exhaustion);
        section.set("fire-ticks", fireTicks);
        section.set("fall-distance", fallDistance);
    }

    /**
     * Lit une entrée YAML avec des valeurs de repli sûres.
     */
    public static TutorialPlayerSnapshot readFrom(ConfigurationSection section) {
        Objects.requireNonNull(section, "section");

        UUID worldId = parseUuid(section.getString("world-uuid"));
        String worldName = section.getString("world-name", DEFAULT_WORLD_NAME);
        if (worldId == null) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return null;
            }
            worldId = world.getUID();
        }

        GameMode gameMode = parseGameMode(
                section.getString("game-mode", GameMode.SURVIVAL.name())
        );

        return new TutorialPlayerSnapshot(
                worldId,
                worldName,
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw"),
                (float) section.getDouble("pitch"),
                gameMode,
                section.getBoolean("allow-flight"),
                section.getBoolean("flying"),
                section.getBoolean("invulnerable"),
                (float) section.getDouble("walk-speed", 0.2D),
                (float) section.getDouble("fly-speed", 0.1D),
                section.getInt("food-level", 20),
                (float) section.getDouble("saturation", 5.0D),
                (float) section.getDouble("exhaustion", 0.0D),
                section.getInt("fire-ticks", 0),
                (float) section.getDouble("fall-distance", 0.0D)
        );
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static GameMode parseGameMode(String raw) {
        if (raw == null) {
            return GameMode.SURVIVAL;
        }
        try {
            return GameMode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return GameMode.SURVIVAL;
        }
    }

    private static float clampSpeed(float value, float fallback) {
        if (!Float.isFinite(value)) {
            return fallback;
        }
        return Math.max(-1.0F, Math.min(1.0F, value));
    }

    private static double safeCoordinate(double value, double fallback) {
        double finite = finiteOr(value, fallback);
        return Math.max(
                -MAX_WORLD_COORDINATE,
                Math.min(MAX_WORLD_COORDINATE, finite)
        );
    }

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static float finiteOr(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }
}
