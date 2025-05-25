package org.example;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Plugin principal.
 */
public final class MinePlugin extends JavaPlugin implements Listener {

    /**
     * Liste pour suivre tous les êtres invoqués par /army
     * (loups + golems) afin de les supprimer après un délai.
     */
    private final List<UUID> guardians = new ArrayList<>();

    /**
     * Liste des golems (classe "Golem")
     * pour la commande /army (suppression au bout de 5 minutes).
     */
    private final List<Golem> golemGuards = new ArrayList<>();

    /**
     * Gère /mineur (sessions de minage persistantes).
     */
    private Mineur mineur;

    /**
     * Gère /champ (agriculture automatisée).
     */
    private Agriculture agriculture;

    /**
     * Gère /foret (forêt automatisée).
     */
    private Foret foret; // <-- Ajout pour la commande /foret

    /**
     * Gère /village (génération de village PNJ).
     */
    private Village village;

    /**
     * Gère /eleveur (enclos automatisé).
     */
    private Eleveur eleveur;

    /**
     * Gère /armure (don de l'Armure du roi GIDON).
     */
    private Armure armure;

    public Mineur getMineur() { return mineur; }
    public Agriculture getAgriculture() { return agriculture; }
    public Foret getForet() { return foret; }
    public Eleveur getEleveur() { return eleveur; }


    @Override
    public void onEnable() {
        getLogger().info("MinePlugin chargé !");
        Bukkit.getPluginManager().registerEvents(this, this);

        // Commandes /army, /ping
        if (getCommand("army") != null) {
            getCommand("army").setExecutor(this);
        }
        if (getCommand("ping") != null) {
            getCommand("ping").setExecutor(this);
        }

        // Instancie /mineur
        mineur = new Mineur(this);
        mineur.loadSavedSessions(); // Restaure les sessions de minage

        // Instancie /champ
        agriculture = new Agriculture(this);
        agriculture.loadSavedSessions(); // Restaure les champs

        // Instancie /foret
        foret = new Foret(this);
        foret.loadSavedSessions(); // Restaure les forêts (le cas échéant)

        // Instancie /village
        village = new Village(this);

        // Instancie /eleveur
        eleveur = new Eleveur(this);
        eleveur.loadSavedSessions(); // Restaure les enclos

        // Instancie /armure
        armure = new Armure(this);
    }

    @Override
    public void onDisable() {
        // Sauvegarde des sessions (mineur, champ, foret)
        if (mineur != null) {
            mineur.saveAllSessions();
            mineur.stopAllSessions();
        }
        if (agriculture != null) {
            agriculture.saveAllSessions();
            agriculture.stopAllSessions();
        }
        if (foret != null) {
            foret.saveAllSessions();
            foret.stopAllForests();
        }
        if (eleveur != null) {
            eleveur.saveAllSessions();
            eleveur.stopAllRanches();
        }

        // Nettoyage /army
        guardians.forEach(id -> {
            var e = Bukkit.getEntity(id);
            if (e != null && !e.isDead()) {
                e.remove();
            }
        });
        guardians.clear();

        golemGuards.forEach(Golem::remove);
        golemGuards.clear();
    }

    /**
     * Événement : un joueur se connecte
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 1) Messages de bienvenue
        player.sendMessage("§aBienvenue dans le monde d'Augustin !!!");
        player.sendTitle("§6§lBIENVENUE", "§eDans le monde d'Augustin", 20, 60, 40);
    }

    /**
     * Gestion des commandes : /army, /ping
     */
    @Override
    public boolean onCommand(CommandSender sender,
                             Command command,
                             String label,
                             String[] args) {

        if (command.getName().equalsIgnoreCase("army")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Cette commande doit être exécutée par un joueur.");
                return true;
            }
            spawnArmy(player);
            return true;
        }

        if (command.getName().equalsIgnoreCase("ping")) {
            sender.sendMessage("Pong !");
            return true;
        }

        return false;
    }

    /**
     * Fait apparaître 5 loups + 2 golems (commande /army)
     * qui disparaissent au bout de 5 minutes.
     */
    private void spawnArmy(Player player) {
        World world = player.getWorld();
        Location base = player.getLocation();

        // 1) 5 loups
        for (int i = 0; i < 5; i++) {
            Location spawn = base.clone().add(
                    (i % 2 == 0 ? 2 : -2),
                    0,
                    (i < 2 ? 2 : -2)
            );
            Wolf wolf = (Wolf) world.spawnEntity(spawn, EntityType.WOLF);

            wolf.setOwner(player);
            wolf.setCustomName("§bGardien de " + player.getName());
            wolf.setCustomNameVisible(true);
            wolf.setCollarColor(org.bukkit.DyeColor.LIGHT_BLUE);
            wolf.setAdult();

            guardians.add(wolf.getUniqueId());
        }

        // 2) 2 golems
        for (int i = 0; i < 2; i++) {
            Location spawn = base.clone().add(i + 1, 0, i + 1);

            // On appelle le constructeur à 2 paramètres : (plugin, Location)
            Golem golemGuard = new Golem(this, spawn);
            // Personnalise le nom comme tu veux
            golemGuard.getGolem().setCustomName("§cGolem de " + player.getName());

            golemGuards.add(golemGuard);
            guardians.add(golemGuard.getGolem().getUniqueId());
        }

        // 3) Disparition après 5 min
        new BukkitRunnable() {
            @Override
            public void run() {
                guardians.removeIf(id -> {
                    var e = Bukkit.getEntity(id);
                    if (e != null && !e.isDead()) {
                        e.remove();
                    }
                    return true;
                });

                golemGuards.forEach(Golem::remove);
                golemGuards.clear();
            }
        }.runTaskLater(this, 20L * 60 * 5);
    }
}