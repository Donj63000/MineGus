package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Commande /minegus fix &lt;forestier|golems&gt; pour maintenance.
 */
public final class MinegusCommand implements CommandExecutor {

    private final JavaPlugin plugin;

    public MinegusCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2 || !"fix".equalsIgnoreCase(args[0])) {
            sendUsage(sender);
            return true;
        }

        String target = args[1].toLowerCase(Locale.ROOT);
        return switch (target) {
            case "forestier", "forestiers" -> {
                int removed = fixForesters();
                sender.sendMessage(ChatColor.GREEN + "Minegus: forestiers nettoyés: " + removed);
                logCleanup("forestiers", removed);
                yield true;
            }
            case "golem", "golems" -> {
                int removed = fixGolems();
                sender.sendMessage(ChatColor.GREEN + "Minegus: golems gardes nettoyés: " + removed);
                logCleanup("golems", removed);
                yield true;
            }
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "Usage: /minegus fix <forestier|golems>");
    }

    private int fixForesters() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            Map<String, List<Villager>> byHut = world.getEntitiesByClass(Villager.class).stream()
                    .filter(this::isMinegusForester)
                    .collect(Collectors.groupingBy(this::hutIdFor));

            for (Map.Entry<String, List<Villager>> entry : byHut.entrySet()) {
                String hutId = entry.getKey();
                if (hutId == null) continue;
                List<Villager> villagers = entry.getValue();
                if (villagers.size() <= 1) continue;

                villagers.sort(Comparator.comparing(Villager::getUniqueId));
                boolean kept = false;
                for (Villager villager : villagers) {
                    if (!villager.isValid() || villager.isDead()) {
                        villager.remove();
                        removed++;
                        continue;
                    }
                    if (!kept) {
                        kept = true;
                        continue;
                    }
                    villager.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    private int fixGolems() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            Map<String, List<IronGolem>> byHut = world.getEntitiesByClass(IronGolem.class).stream()
                    .filter(this::isTaggedGuard)
                    .collect(Collectors.groupingBy(this::guardHutFor));

            for (Map.Entry<String, List<IronGolem>> entry : byHut.entrySet()) {
                String hutId = entry.getKey();
                if (hutId == null) continue;
                List<IronGolem> golems = entry.getValue();
                if (golems.size() <= 1) continue;

                golems.sort(Comparator.comparing(IronGolem::getUniqueId));
                boolean kept = false;
                for (IronGolem golem : golems) {
                    if (!golem.isValid() || golem.isDead()) {
                        golem.remove();
                        removed++;
                        continue;
                    }
                    if (!kept) {
                        kept = true;
                        continue;
                    }
                    golem.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    private boolean isMinegusForester(Villager villager) {
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        String type = pdc.get(Keys.workerType(), PersistentDataType.STRING);
        return "FORESTER".equals(type) && hutIdFor(villager) != null;
    }

    private String hutIdFor(Villager villager) {
        return villager.getPersistentDataContainer().get(Keys.hutId(), PersistentDataType.STRING);
    }

    private boolean isTaggedGuard(IronGolem golem) {
        return guardHutFor(golem) != null;
    }

    private String guardHutFor(IronGolem golem) {
        PersistentDataContainer pdc = golem.getPersistentDataContainer();
        return pdc.get(Keys.guardFor(), PersistentDataType.STRING);
    }

    private void logCleanup(String type, int removed) {
        if (plugin != null) {
            plugin.getLogger().info(() -> "[Minegus] Nettoyage " + type + ": " + removed);
        }
    }
}
