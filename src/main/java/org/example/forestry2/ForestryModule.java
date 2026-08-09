package org.example.forestry2;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.example.Foret;
import org.example.MinePlugin;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Je relie les deux commandes au moteur forestier unique du plugin.
 */
public final class ForestryModule {

    private static final Object MONITOR = new Object();
    private static Installation installation;

    private ForestryModule() {
        throw new IllegalStateException("Je ne dois pas instancier ce module.");
    }

    /**
     * J'installe l'alias /bucheron sans créer un second moteur, listener ou scheduler.
     */
    public static void install(MinePlugin plugin) {
        Objects.requireNonNull(plugin, "Je dois recevoir le plugin MineGus.");
        synchronized (MONITOR) {
            if (installation != null) {
                if (installation.plugin == plugin) {
                    return;
                }
                throw new IllegalStateException("Un module forestier est déjà installé.");
            }
            if (!plugin.getConfig().getBoolean("forester-v2.enabled", true)) {
                plugin.getLogger().info("[Bucheron] L'alias v2 est désactivé.");
                return;
            }

            Foret foret = Objects.requireNonNull(plugin.getForet(),
                    "Je dois initialiser le moteur forestier avant son alias.");
            PluginCommand foretCommand = requiredCommand(plugin, "foret");
            PluginCommand lumberjackCommand = requiredCommand(plugin, "bucheron");
            CommandExecutor oldForetExecutor = foretCommand.getExecutor();
            CommandExecutor oldLumberjackExecutor = lumberjackCommand.getExecutor();
            TabCompleter oldForetCompleter = foretCommand.getTabCompleter();
            TabCompleter oldLumberjackCompleter = lumberjackCommand.getTabCompleter();
            Adapter adapter = new Adapter(foret);

            try {
                foretCommand.setExecutor(adapter);
                foretCommand.setTabCompleter(adapter);
                lumberjackCommand.setExecutor(adapter);
                lumberjackCommand.setTabCompleter(adapter);
                installation = new Installation(plugin, adapter, foretCommand,
                        lumberjackCommand, oldForetExecutor, oldLumberjackExecutor,
                        oldForetCompleter, oldLumberjackCompleter);
            } catch (RuntimeException failure) {
                restore(foretCommand, oldForetExecutor, oldForetCompleter);
                restore(lumberjackCommand, oldLumberjackExecutor, oldLumberjackCompleter);
                throw failure;
            }
        }
    }

    /**
     * Je restaure les anciens bindings ; les sauvegardes et tâches restent gérées
     * une seule fois par Foret dans le cycle de vie principal.
     */
    public static void shutdown() {
        synchronized (MONITOR) {
            if (installation == null) {
                return;
            }
            Installation current = installation;
            installation = null;
            if (current.foretCommand.getExecutor() == current.adapter) {
                restore(current.foretCommand, current.oldForetExecutor, current.oldForetCompleter);
            }
            if (current.lumberjackCommand.getExecutor() == current.adapter) {
                restore(current.lumberjackCommand, current.oldLumberjackExecutor,
                        current.oldLumberjackCompleter);
            }
        }
    }

    static CommandExecutor activeExecutorForTests() {
        synchronized (MONITOR) {
            return installation == null ? null : installation.adapter;
        }
    }

    private static PluginCommand requiredCommand(MinePlugin plugin, String name) {
        return Objects.requireNonNull(plugin.getCommand(name),
                "Je dois déclarer la commande /" + name + " dans plugin.yml.");
    }

    private static void restore(PluginCommand command,
                                CommandExecutor executor,
                                TabCompleter completer) {
        command.setExecutor(executor);
        command.setTabCompleter(completer);
    }

    private static final class Adapter implements CommandExecutor, TabCompleter {

        private static final List<String> SUBCOMMANDS = List.of(
                "creer", "statut", "liste", "reparer", "supprimer", "aide"
        );
        private final Foret delegate;

        private Adapter(Foret delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean onCommand(CommandSender sender,
                                 Command command,
                                 String label,
                                 String[] args) {
            return delegate.onCommand(sender, command, label, args);
        }

        @Override
        public List<String> onTabComplete(CommandSender sender,
                                          Command command,
                                          String alias,
                                          String[] args) {
            if (args.length != 1) {
                return List.of();
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(value -> value.startsWith(prefix)).toList();
        }
    }

    private record Installation(MinePlugin plugin,
                                Adapter adapter,
                                PluginCommand foretCommand,
                                PluginCommand lumberjackCommand,
                                CommandExecutor oldForetExecutor,
                                CommandExecutor oldLumberjackExecutor,
                                TabCompleter oldForetCompleter,
                                TabCompleter oldLumberjackCompleter) {
    }
}
