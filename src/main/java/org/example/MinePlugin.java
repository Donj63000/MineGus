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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.example.merchant.MerchantManager;
import org.example.forestry2.ForestryModule;
import org.example.forestry2.SharedChunkTickets;
import org.example.progression.GlobalProgressionManager;
import org.example.village.VillageEntityManager;
import org.example.village.VillageKnightManager;
import org.example.village.VillageTreasuryManager;
import org.example.tutorial.TutorialManager;

/**
 * Plugin principal.
 */
public class MinePlugin extends JavaPlugin implements Listener {

    private static final String ARMY_PERMISSION = "mineplugin.army.use";
    private static final long ARMY_LIFETIME_TICKS = 20L * 60L * 5L;

    /**
     * Une seule armée peut être active par joueur. Cette indexation évite
     * qu'une ancienne tâche de disparition supprime les gardes d'un autre
     * joueur et borne aussi le nombre d'entités créées par la commande.
     */
    private final Map<UUID, ArmyDeployment> activeArmies = new HashMap<>();

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

    /**
     * Gère les métiers (XP, /job).
     */
    private JobManager jobManager;

    /**
     * Gère le niveau global, indépendant des métiers.
     */
    private GlobalProgressionManager globalProgressionManager;

    private MerchantManager merchantManager;

    /**
     * Gère le duo de gardes royaux invoqué par /garde.
     */
    private RoyalGuardManager royalGuardManager;

    /**
     * Gère la garnison persistante créée avec le village.
     */
    private VillageKnightManager villageKnightManager;

    /**
     * Gère la chambre forte et l'impôt de chaque ville créée par /village.
     */
    private VillageTreasuryManager villageTreasuryManager;

    /**
     * Gère la visite privée /tuto et ses mondes temporaires.
     */
    private TutorialManager tutorialManager;

    public Mineur getMineur() { return mineur; }
    public Agriculture getAgriculture() { return agriculture; }
    public Foret getForet() { return foret; }
    public Eleveur getEleveur() { return eleveur; }
    public JobManager getJobManager() { return jobManager; }
    public GlobalProgressionManager getGlobalProgressionManager() { return globalProgressionManager; }
    public MerchantManager getMerchantManager() { return merchantManager; }
    public RoyalGuardManager getRoyalGuardManager() { return royalGuardManager; }
    public VillageKnightManager getVillageKnightManager() { return villageKnightManager; }
    public VillageTreasuryManager getVillageTreasuryManager() {
        return villageTreasuryManager;
    }
    public TutorialManager getTutorialManager() { return tutorialManager; }


    @Override
    public void onEnable() {
        getLogger().info("MinePlugin chargé !");
        getLogger().info("Copyright By V.G");
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        Keys.init(this);

        // Commandes /army, /ping
        if (getCommand("army") != null) {
            getCommand("army").setExecutor(this);
        }
        if (getCommand("ping") != null) {
            getCommand("ping").setExecutor(this);
        }
        if (getCommand("minegus") != null) {
            getCommand("minegus").setExecutor(new MinegusCommand(this));
        }

        // Ici, j'initialise les gardes avant les autres systèmes qui peuvent les cibler.
        royalGuardManager = new RoyalGuardManager(this);
        if (getCommand("garde") != null) {
            getCommand("garde").setExecutor(royalGuardManager);
            getCommand("garde").setTabCompleter(royalGuardManager);
        }

        /*
         * La garnison doit être prête avant /village : la dernière action de
         * génération pourra ainsi enregistrer immédiatement ses itinéraires.
         */
        villageKnightManager = new VillageKnightManager(this);

        /*
         * Le trésor est prêt avant /village afin que la dernière action de la
         * transaction puisse enregistrer immédiatement la chambre forte.
         */
        villageTreasuryManager = new VillageTreasuryManager(this);

        // Instancie le gestionnaire de métiers (/job) avant /mineur pour
        // que les sessions restaurées récupèrent immédiatement leur bonus.
        jobManager = new JobManager(this);
        jobManager.initializeOnlinePlayers();

        /*
         * Le niveau global est volontairement instancié séparément du métier
         * Mineur : données, courbe, bonus et commande ne se mélangent pas.
         */
        globalProgressionManager = new GlobalProgressionManager(this);
        globalProgressionManager.initializeOnlinePlayers();

        // Instancie /mineur
        mineur = new Mineur(this);
        mineur.loadSavedSessions(); // Restaure les sessions de minage

        // Instancie /champ
        agriculture = new Agriculture(this);
        agriculture.loadSavedSessions(); // Restaure les champs

        // Je conserve un seul moteur forestier et le module v2 lui ajoute son alias sûr.
        foret = new Foret(this);
        foret.loadSavedSessions();

        // Instancie /village
        village = new Village(this);

        // Instancie /eleveur
        eleveur = new Eleveur(this);
        eleveur.loadSavedSessions(); // Restaure les enclos

        // Instancie /armure
        armure = new Armure(this);

        try {
            merchantManager = new MerchantManager(this);
        } catch (RuntimeException | LinkageError failure) {
            merchantManager = null;
            getLogger().log(
                    Level.WARNING,
                    "MerchantManager désactivé dans cet environnement.",
                    failure
            );
        }

        // Population persistante et surveillance des marchands du village.
        try {
            VillageEntityManager.initializePopulationListener(this);
            VillageEntityManager.startMerchantGuardTask(this);
        } catch (RuntimeException | LinkageError failure) {
            getLogger().log(
                    Level.WARNING,
                    "VillageEntityManager désactivé dans cet environnement.",
                    failure
            );
        }

        // J'installe le module après tous les services dont il partage les tickets de chunks.
        ForestryModule.install(this);

        /*
         * Le tutoriel est initialisé en dernier : toutes les commandes qu'il
         * présente sont alors déjà prêtes. Ses démonstrations restent toutefois
         * isolées et n'appellent jamais directement ces gestionnaires.
         */
        tutorialManager = new TutorialManager(this);
    }

    @Override
    public void onDisable() {
        /*
         * Les joueurs du tutoriel sont ramenés avant l'arrêt des autres
         * modules et avant que Bukkit ne commence à décharger les mondes.
         */
        if (tutorialManager != null) {
            tutorialManager.shutdown();
        }

        // Je fige et sauvegarde d'abord le moteur forestier actif.
        ForestryModule.shutdown();
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
            foret.pauseAllForests();
        }
        if (eleveur != null) {
            eleveur.saveAllSessions();
            eleveur.stopAllRanches();
        }
        if (village != null) {
            village.shutdown();
        }
        if (armure != null) {
            armure.shutdown();
        }
        if (jobManager != null) {
            jobManager.shutdown();
        }
        if (globalProgressionManager != null) {
            globalProgressionManager.shutdown();
        }
        if (merchantManager != null) {
            merchantManager.shutdown();
        }
        if (villageTreasuryManager != null) {
            villageTreasuryManager.shutdown();
        }
        if (villageKnightManager != null) {
            villageKnightManager.shutdown();
        }
        if (royalGuardManager != null) {
            royalGuardManager.shutdown();
        }

        /*
         * Je purge uniquement après l'arrêt normal de tous les détenteurs. Cette
         * dernière passe couvre une exception de nettoyage sans retirer trop tôt
         * un ticket encore utilisé par le mineur.
         */
        try {
            SharedChunkTickets.releaseAll(this);
        } catch (RuntimeException failure) {
            getLogger().severe("Je n'ai pas pu libérer tous les tickets de chunks: "
                    + failure.getMessage());
        }

        // Nettoyage de toutes les armées transitoires.
        for (UUID ownerId : new ArrayList<>(activeArmies.keySet())) {
            dismissArmy(ownerId);
        }
    }

    /**
     * Événement : un joueur se connecte
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 1) Messages de bienvenue
        player.sendMessage("§aBienvenue dans le monde de MineGus !");
        player.sendTitle("§6§lBienvenue dans le monde de MineGus !", "§eAmuse-toi bien ;)", 20, 60, 40);

    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        /*
         * Les gardes temporaires n'ont aucune utilité sans leur propriétaire
         * et ne doivent pas occuper des chunks pendant cinq minutes de plus.
         */
        dismissArmy(event.getPlayer().getUniqueId());
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
            if (!player.hasPermission(ARMY_PERMISSION)) {
                player.sendMessage("§cTu n'as pas la permission d'utiliser /army.");
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
        UUID ownerId = player.getUniqueId();

        /*
         * Réinvoquer /army remplace proprement l'ancienne escouade au lieu
         * d'empiler sans limite des entités et des tâches de nettoyage.
         */
        boolean replaced = activeArmies.containsKey(ownerId);
        dismissArmy(ownerId);

        World world = player.getWorld();
        Location base = player.getLocation();
        List<UUID> wolves = new ArrayList<>();
        List<Golem> golems = new ArrayList<>();
        ArmyDeployment deployment = new ArmyDeployment(wolves, golems);

        try {
            int[][] wolfOffsets = {
                    {2, 2},
                    {-2, 2},
                    {2, -2},
                    {-2, -2},
                    {0, 3}
            };
            for (int[] offset : wolfOffsets) {
                Location spawn = base.clone().add(
                        offset[0],
                        0,
                        offset[1]
                );
                Wolf wolf = (Wolf) world.spawnEntity(
                        spawn,
                        EntityType.WOLF
                );
                /*
                 * L'identifiant est enregistré avant toute personnalisation :
                 * une exception ultérieure restera ainsi nettoyable.
                 */
                wolves.add(wolf.getUniqueId());
                wolf.setOwner(player);
                wolf.setCustomName("§bGardien de " + player.getName());
                wolf.setCustomNameVisible(true);
                wolf.setCollarColor(org.bukkit.DyeColor.LIGHT_BLUE);
                wolf.setAdult();
                /*
                 * L'armée est éphémère et ne doit pas être conservée dans un
                 * chunk après un crash du serveur.
                 */
                wolf.setPersistent(false);
            }

            for (int index = 0; index < 2; index++) {
                Location spawn = base.clone().add(
                        index + 1,
                        0,
                        index + 1
                );
                Golem golem = new Golem(this, spawn);
                golems.add(golem);
                golem.getGolem().setCustomName(
                        "§cGolem de " + player.getName()
                );
                golem.getGolem().setPersistent(false);
            }

            activeArmies.put(ownerId, deployment);
            deployment.cleanupTask = Bukkit.getScheduler().runTaskLater(
                    this,
                    () -> dismissArmyIfCurrent(ownerId, deployment),
                    ARMY_LIFETIME_TICKS
            );
        } catch (RuntimeException failure) {
            activeArmies.remove(ownerId, deployment);
            cleanupDeployment(deployment, true);
            getLogger().log(
                    Level.SEVERE,
                    "Impossible d'invoquer l'armée de " + player.getName() + ".",
                    failure
            );
            player.sendMessage("§cL'armée n'a pas pu être invoquée.");
            return;
        }

        if (replaced) {
            player.sendMessage("§eTon ancienne armée a été remplacée.");
        }
        player.sendMessage("§aTon armée restera active pendant 5 minutes.");
    }

    private void dismissArmyIfCurrent(
            UUID ownerId,
            ArmyDeployment expectedDeployment) {
        if (activeArmies.get(ownerId) != expectedDeployment) {
            return;
        }
        activeArmies.remove(ownerId);
        /*
         * La tâche est déjà en cours : ne pas tenter de l'annuler depuis son
         * propre callback.
         */
        cleanupDeployment(expectedDeployment, false);
    }

    private void dismissArmy(UUID ownerId) {
        ArmyDeployment deployment = activeArmies.remove(ownerId);
        if (deployment != null) {
            cleanupDeployment(deployment, true);
        }
    }

    private void cleanupDeployment(
            ArmyDeployment deployment,
            boolean cancelTask) {
        if (cancelTask
                && deployment.cleanupTask != null
                && !deployment.cleanupTask.isCancelled()) {
            deployment.cleanupTask.cancel();
        }

        for (UUID entityId : deployment.wolfIds) {
            var entity = Bukkit.getEntity(entityId);
            if (entity != null && !entity.isDead()) {
                entity.remove();
            }
        }
        deployment.wolfIds.clear();

        for (Golem golem : deployment.golems) {
            golem.remove();
        }
        deployment.golems.clear();
    }

    private static final class ArmyDeployment {
        private final List<UUID> wolfIds;
        private final List<Golem> golems;
        private BukkitTask cleanupTask;

        private ArmyDeployment(
                List<UUID> wolfIds,
                List<Golem> golems) {
            this.wolfIds = wolfIds;
            this.golems = golems;
        }
    }

}
