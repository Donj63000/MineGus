package org.example.progression;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HUD temporaire de progression affiché dans la sidebar, en haut à droite.
 *
 * <p>Chaque joueur reçoit un scoreboard isolé le temps de l'affichage. Le
 * scoreboard précédent est restauré uniquement si aucun autre plugin ne l'a
 * remplacé entre-temps, ce qui évite d'écraser une interface concurrente.</p>
 */
final class ProgressionHud {

    private static final String OBJECTIVE_NAME = "mg_progress";
    private static final String[] UNIQUE_SUFFIXES = {
            ChatColor.BLACK.toString(),
            ChatColor.DARK_BLUE.toString(),
            ChatColor.DARK_GREEN.toString()
    };

    private final JavaPlugin plugin;
    private final ProgressionConfig config;
    private final Map<UUID, HudSession> sessions = new HashMap<>();
    private boolean failureLogged;

    ProgressionHud(JavaPlugin plugin, ProgressionConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    void show(
            Player player,
            long gained,
            String source,
            int level,
            long totalXp,
            long currentLevelXp,
            long nextLevelXp) {
        if (player == null || gained <= 0L) {
            return;
        }

        if (!config.sidebarOnGain) {
            showFallbackActionbar(player, gained, source, level);
            return;
        }

        try {
            HudSession session = sessions.get(player.getUniqueId());
            if (session != null && player.getScoreboard() != session.scoreboard) {
                // Un autre plugin a repris la sidebar : on abandonne proprement
                // notre ancienne session sans restaurer un scoreboard obsolète.
                dispose(session, null, false);
                sessions.remove(player.getUniqueId());
                session = null;
            }

            if (session == null) {
                session = createSession(player);
                if (session == null) {
                    showFallbackActionbar(player, gained, source, level);
                    return;
                }
                sessions.put(player.getUniqueId(), session);
            }

            render(
                    session,
                    gained,
                    source,
                    level,
                    totalXp,
                    currentLevelXp,
                    nextLevelXp);
            scheduleHide(player.getUniqueId(), session);
        } catch (Throwable throwable) {
            logFailureOnce(throwable);
            hide(player.getUniqueId());
            showFallbackActionbar(player, gained, source, level);
        }
    }

    void hide(Player player) {
        if (player != null) {
            hide(player.getUniqueId());
        }
    }

    void hide(UUID playerId) {
        if (playerId == null) {
            return;
        }

        HudSession session = sessions.remove(playerId);
        if (session == null) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        dispose(session, player, true);
    }

    void shutdown() {
        for (UUID playerId : new ArrayList<>(sessions.keySet())) {
            hide(playerId);
        }
        sessions.clear();
    }

    int getActiveHudCount() {
        return sessions.size();
    }

    private HudSession createSession(Player player) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return null;
        }

        Scoreboard previous = player.getScoreboard();
        Scoreboard scoreboard = manager.getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective(
                OBJECTIVE_NAME,
                Criteria.DUMMY,
                Component.text("Progression", NamedTextColor.GOLD));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        try {
            // Paper 1.21 masque ainsi les chiffres 3/2/1 servant uniquement à
            // trier les lignes de la sidebar.
            objective.numberFormat(NumberFormat.blank());
        } catch (Throwable throwable) {
            // Certains environnements de test n'implémentent pas encore cette
            // extension. Le HUD reste utilisable, avec les nombres de tri.
        }

        HudSession session = new HudSession(
                scoreboard,
                previous,
                objective);
        try {
            player.setScoreboard(scoreboard);
            return session;
        } catch (Throwable throwable) {
            dispose(session, null, false);
            throw throwable;
        }
    }

    private void render(
            HudSession session,
            long gained,
            String source,
            int level,
            long totalXp,
            long currentLevelXp,
            long nextLevelXp) {
        for (String oldLine : session.lines) {
            session.scoreboard.resetScores(oldLine);
        }
        session.lines.clear();

        boolean maximum = level >= config.maxLevel
                || nextLevelXp <= currentLevelXp;
        double progress = maximum
                ? 1.0D
                : (double) Math.max(0L, totalXp - currentLevelXp)
                / (double) Math.max(1L, nextLevelXp - currentLevelXp);
        progress = Math.max(0.0D, Math.min(1.0D, progress));

        Component title = Component.text(
                maximum
                        ? "Niveau " + level + "  MAX"
                        : "Niveau " + level,
                maximum ? NamedTextColor.GREEN : NamedTextColor.GOLD);
        session.objective.displayName(title);

        String safeSource = sanitizeSource(source);
        String gainLine = ChatColor.GREEN + "+" + formatNumber(gained)
                + " XP" + ChatColor.DARK_GRAY + " • "
                + ChatColor.GRAY + safeSource;
        String barLine = buildProgressBar(progress, maximum);
        String xpLine;
        if (maximum) {
            xpLine = ChatColor.GREEN + formatNumber(totalXp)
                    + ChatColor.GRAY + " XP au total";
        } else {
            long inLevel = Math.max(0L, totalXp - currentLevelXp);
            long required = Math.max(1L, nextLevelXp - currentLevelXp);
            xpLine = ChatColor.AQUA + formatNumber(inLevel)
                    + ChatColor.DARK_GRAY + "/"
                    + ChatColor.AQUA + formatNumber(required)
                    + ChatColor.GRAY + " XP";
        }

        addLine(session, gainLine, 3, 0);
        addLine(session, barLine, 2, 1);
        addLine(session, xpLine, 1, 2);
    }

    private void addLine(
            HudSession session,
            String visibleLine,
            int score,
            int uniqueIndex) {
        String entry = visibleLine
                + UNIQUE_SUFFIXES[Math.max(0,
                Math.min(UNIQUE_SUFFIXES.length - 1, uniqueIndex))];
        session.objective.getScore(entry).setScore(score);
        session.lines.add(entry);
    }

    private String buildProgressBar(double progress, boolean maximum) {
        int filled = maximum
                ? config.progressBarSegments
                : (int) Math.floor(progress * config.progressBarSegments);
        filled = Math.max(0, Math.min(config.progressBarSegments, filled));

        StringBuilder line = new StringBuilder();
        line.append(ChatColor.GREEN);
        line.append("■".repeat(filled));
        line.append(ChatColor.DARK_GRAY);
        line.append("■".repeat(config.progressBarSegments - filled));
        line.append(ChatColor.GRAY)
                .append(' ')
                .append((int) Math.floor(progress * 100.0D))
                .append('%');
        return line.toString();
    }

    private void scheduleHide(UUID playerId, HudSession session) {
        if (session.hideTask != null) {
            session.hideTask.cancel();
        }
        session.hideTask = Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> hide(playerId),
                config.hudHideDelayTicks);
    }

    private void dispose(
            HudSession session,
            Player player,
            boolean restorePrevious) {
        if (session.hideTask != null) {
            session.hideTask.cancel();
            session.hideTask = null;
        }

        if (restorePrevious
                && player != null
                && player.getScoreboard() == session.scoreboard
                && session.previousScoreboard != null) {
            try {
                player.setScoreboard(session.previousScoreboard);
            } catch (Throwable throwable) {
                logFailureOnce(throwable);
            }
        }

        try {
            session.objective.unregister();
        } catch (Throwable throwable) {
            // L'objectif peut déjà avoir été invalidé par l'environnement.
        }
    }

    private void showFallbackActionbar(
            Player player,
            long gained,
            String source,
            int level) {
        if (!config.actionbarFallback || player == null) {
            return;
        }
        player.sendActionBar(Component.text(
                "+" + formatNumber(gained)
                        + " XP • " + sanitizeSource(source)
                        + " • Niveau " + level,
                NamedTextColor.AQUA));
    }

    private static String sanitizeSource(String source) {
        String value = source == null || source.isBlank()
                ? "Progression"
                : source;
        value = ChatColor.stripColor(value);
        if (value == null) {
            value = "Progression";
        }
        value = value.replaceAll("[\\p{Cntrl}\\r\\n]", " ").trim();
        if (value.length() > 24) {
            value = value.substring(0, 23) + "…";
        }
        return value.isBlank() ? "Progression" : value;
    }

    static String formatNumber(long value) {
        String raw = Long.toString(Math.max(0L, value));
        StringBuilder formatted = new StringBuilder(raw.length() + raw.length() / 3);
        int firstGroup = raw.length() % 3;
        if (firstGroup == 0) {
            firstGroup = 3;
        }

        formatted.append(raw, 0, firstGroup);
        for (int index = firstGroup; index < raw.length(); index += 3) {
            formatted.append(' ')
                    .append(raw, index, Math.min(index + 3, raw.length()));
        }
        return formatted.toString();
    }

    private void logFailureOnce(Throwable throwable) {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        plugin.getLogger().warning(
                "[Progression] Sidebar temporaire indisponible ("
                        + throwable.getClass().getSimpleName()
                        + "). L'actionbar de secours sera utilisee.");
    }

    private static final class HudSession {
        private final Scoreboard scoreboard;
        private final Scoreboard previousScoreboard;
        private final Objective objective;
        private final List<String> lines = new ArrayList<>();
        private BukkitTask hideTask;

        private HudSession(
                Scoreboard scoreboard,
                Scoreboard previousScoreboard,
                Objective objective) {
            this.scoreboard = scoreboard;
            this.previousScoreboard = previousScoreboard;
            this.objective = objective;
        }
    }
}
