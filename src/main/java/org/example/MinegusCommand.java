package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Commande /minegus : aide globale + maintenance admin.
 */
public final class MinegusCommand implements CommandExecutor {

    static final String ADMIN_PERMISSION = "mineplugin.admin";
    private static final String FIX_USAGE = "/minegus fix <forestier|golems>";
    private static final String PREFIX = ChatColor.GOLD + "[MineGus] " + ChatColor.YELLOW;

    private final JavaPlugin plugin;

    public MinegusCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !"fix".equalsIgnoreCase(args[0])) {
            sendHelp(sender, false);
            return true;
        }

        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "Tu n'as pas la permission pour utiliser /minegus fix.");
            sendHelp(sender, false);
            return true;
        }

        if (args.length < 2) {
            sendHelp(sender, true);
            return true;
        }

        String target = args[1].toLowerCase(Locale.ROOT);
        return switch (target) {
            case "forestier", "forestiers" -> {
                int removed = fixForesters();
                sender.sendMessage(ChatColor.GREEN + "MineGus: forestiers nettoyes: " + removed);
                logCleanup("forestiers", removed);
                yield true;
            }
            case "golem", "golems" -> {
                int removed = fixGolems();
                sender.sendMessage(ChatColor.GREEN + "MineGus: golems gardes nettoyes: " + removed);
                logCleanup("golems", removed);
                yield true;
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Cible inconnue pour /minegus fix : " + args[1]);
                sendHelp(sender, true);
                yield true;
            }
        };
    }

    private void sendHelp(CommandSender sender, boolean includeFixUsage) {
        for (String line : buildHelpLines()) {
            sender.sendMessage(line);
        }
        if (includeFixUsage) {
            sender.sendMessage(ChatColor.RED + "Usage: " + FIX_USAGE);
        }
    }

    static List<String> buildHelpLines() {
        List<String> lines = new ArrayList<>();
        lines.add(PREFIX + "Commandes disponibles");
        lines.add(ChatColor.GRAY + "Marquage: " + ChatColor.AQUA + "[permission]" + ChatColor.GRAY
                + " = acces restreint, " + ChatColor.RED + "[admin]" + ChatColor.GRAY + " = maintenance.");
        lines.add(ChatColor.GOLD + "/ping" + ChatColor.GRAY + " : verifie que le plugin repond.");
        lines.add(ChatColor.GOLD + "/army" + ChatColor.GRAY + " : invoque des loups et golems temporaires.");
        lines.add(ChatColor.GOLD + "/mineur" + ChatColor.GRAY
                + " : cree et pilote un mineur automatique. Voir " + ChatColor.GOLD + "/mineur aide" + ChatColor.GRAY + ".");
        lines.add(ChatColor.GOLD + "/champ" + ChatColor.GRAY + " : cree un champ automatise avec selection.");
        lines.add(ChatColor.GOLD + "/foret" + ChatColor.GRAY + " : cree une foret automatisee avec forestier.");
        lines.add(ChatColor.GOLD + "/eleveur" + ChatColor.GRAY
                + " : cree un ranch auto; " + ChatColor.GOLD + "list" + ChatColor.GRAY + " et "
                + ChatColor.GOLD + "delete <id>" + ChatColor.GRAY + " gerent les enclos.");
        lines.add(ChatColor.GOLD + "/village" + ChatColor.GRAY
                + " : genere un village; " + ChatColor.GOLD + "/village undo" + ChatColor.GRAY + " annule la derniere generation.");
        lines.add(ChatColor.GOLD + "/armure" + ChatColor.GRAY + " : donne l'armure du roi GIDON.");
        lines.add(ChatColor.GOLD + "/marchand" + ChatColor.AQUA + " [permission]" + ChatColor.GRAY
                + " : invoque le marchand; " + ChatColor.GOLD + "open" + ChatColor.GRAY + " ouvre son menu.");
        lines.add(ChatColor.GOLD + "/job" + ChatColor.GRAY + " : choisit le metier de mineur et affiche ta progression.");
        lines.add(ChatColor.GOLD + FIX_USAGE + ChatColor.RED + " [admin]" + ChatColor.GRAY
                + " : nettoie les doublons de forestiers ou de golems.");
        return lines;
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
