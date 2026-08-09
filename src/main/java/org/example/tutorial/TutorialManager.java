package org.example.tutorial;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Commande /tuto et cycle de vie complet de la visite guidée.
 *
 * <p>Une session utilise un monde privé et jetable. Les vraies commandes ne
 * sont jamais exécutées pendant la démonstration : leurs effets sont reproduits
 * visuellement dans ce bac à sable, ce qui interdit toute donnée métier ou
 * construction persistante créée par le tutoriel.</p>
 */
public final class TutorialManager implements CommandExecutor, Listener {

    public static final String USE_PERMISSION = "mineplugin.tuto.use";

    private static final String CHAT_PREFIX =
            "§b§lDonjBot §8» §f";
    private static final String SYSTEM_PREFIX =
            "§6[MineGus] §e";
    private static final String MARKER_FILE =
            ".minegus-tutorial-world";
    private static final String MARKER_MAGIC =
            "MineGus temporary tutorial world v1";
    private static final long RETRY_RETURN_DELAY_TICKS = 20L;
    private static final int MAX_RETURN_ATTEMPTS = 5;

    private final JavaPlugin plugin;
    private final TutorialSettings settings;
    private final ExecutorService ioExecutor;
    private final TutorialReturnStore returnStore;
    private final TutorialTourDirector director;
    private final Map<UUID, TutorialSession> sessions = new HashMap<>();
    private final Map<UUID, PendingStart> pendingStarts = new HashMap<>();
    private final Set<String> reservedWorldNames =
            ConcurrentHashMap.newKeySet();
    private final Set<String> knownTutorialWorldNames =
            ConcurrentHashMap.newKeySet();
    private final Path worldContainer;

    private BukkitTask monitorTask;
    private volatile boolean shuttingDown;

    public TutorialManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = TutorialSettings.from(plugin.getConfig());
        this.worldContainer = Bukkit.getWorldContainer()
                .toPath()
                .toAbsolutePath()
                .normalize();
        this.ioExecutor = Executors.newSingleThreadExecutor(
                new TutorialThreadFactory()
        );
        this.returnStore = new TutorialReturnStore(
                plugin.getDataFolder().toPath(),
                ioExecutor,
                plugin.getLogger()
        );
        this.director = new TutorialTourDirector(this);

        PluginCommand command = plugin.getCommand("tuto");
        if (command == null) {
            throw new IllegalStateException(
                    "La commande 'tuto' manque dans plugin.yml."
            );
        }
        command.setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);

        monitorTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::monitorSessions,
                10L,
                10L
        );

        /*
         * Ces deux opérations sont différées pour laisser les autres plugins de
         * mondes finir leur initialisation et les joueurs déjà connectés être
         * complètement disponibles.
         */
        Bukkit.getScheduler().runTask(plugin, this::recoverOnlinePlayers);
        Bukkit.getScheduler().runTaskLater(
                plugin,
                this::discoverAndCleanStaleWorlds,
                60L
        );
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    SYSTEM_PREFIX + "La visite guidée doit être lancée en jeu."
            );
            return true;
        }
        if (args.length != 0) {
            player.sendMessage(SYSTEM_PREFIX + "Utilisation : §6/tuto");
            return true;
        }
        if (!player.hasPermission(USE_PERMISSION)) {
            player.sendMessage(
                    "§cTu n'as pas la permission de lancer le tutoriel."
            );
            return true;
        }

        UUID playerId = player.getUniqueId();
        if (sessions.containsKey(playerId)) {
            endSession(playerId, EndReason.MANUAL, 0);
            return true;
        }
        if (pendingStarts.containsKey(playerId)) {
            cancelPendingStart(playerId, true);
            return true;
        }
        if (returnStore.contains(playerId)) {
            player.sendMessage(
                    SYSTEM_PREFIX
                            + "Je termine d'abord ton retour de sécurité."
            );
            recoverPlayer(player, true);
            return true;
        }
        startTutorial(player);
        return true;
    }

    /**
     * Arrêt synchrone appelé en premier depuis MinePlugin.onDisable().
     */
    public void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;

        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }

        for (UUID playerId : List.copyOf(pendingStarts.keySet())) {
            cancelPendingStart(playerId, false);
        }
        for (TutorialSession session : List.copyOf(sessions.values())) {
            stopSessionForShutdown(session);
        }

        /*
         * Les sauvegardes et suppressions déjà en file sont courtes et
         * séquentielles. Une attente bornée évite de bloquer indéfiniment
         * l'arrêt du serveur si le système de fichiers est défaillant.
         */
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5L, TimeUnit.SECONDS)) {
                plugin.getLogger().warning(
                        "Les E/S du tutoriel n'ont pas toutes terminé avant "
                                + "l'arrêt ; elles seront nettoyées au prochain "
                                + "démarrage."
                );
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }

    private void startTutorial(Player player) {
        if (shuttingDown || !settings.enabled()) {
            player.sendMessage(
                    SYSTEM_PREFIX + "Le tutoriel est désactivé actuellement."
            );
            return;
        }
        if (!player.isOnline() || player.isDead()) {
            player.sendMessage(
                    SYSTEM_PREFIX
                            + "Le tutoriel ne peut pas démarrer dans cet état."
            );
            return;
        }
        if (player.isInsideVehicle()) {
            player.sendMessage(
                    SYSTEM_PREFIX
                            + "Descends d'abord de ton véhicule, puis relance "
                            + "§6/tuto§e."
            );
            return;
        }
        if (sessions.size() + pendingStarts.size()
                >= settings.maxConcurrentSessions()) {
            player.sendMessage(
                    SYSTEM_PREFIX
                            + "Toutes les visites sont occupées. Réessaie dans "
                            + "quelques instants."
            );
            return;
        }

        UUID playerId = player.getUniqueId();
        TutorialPlayerSnapshot snapshot =
                TutorialPlayerSnapshot.capture(player);
        String worldName = createWorldName(playerId);
        PendingStart request = new PendingStart(
                playerId,
                snapshot,
                worldName
        );

        pendingStarts.put(playerId, request);
        reservedWorldNames.add(worldName);
        returnStore.put(playerId, snapshot);

        player.sendMessage(
                SYSTEM_PREFIX
                        + "DonjBot prépare un monde rien que pour toi... "
                        + "§7(/tuto pour annuler)"
        );
        player.playSound(
                player.getLocation(),
                Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                0.8F,
                1.2F
        );

        /*
         * La destination de retour est durable AVANT la création du monde et
         * toute téléportation. Le même exécuteur série garantit l'ordre disque.
         */
        returnStore.saveAsync()
                .thenRunAsync(
                        () -> prepareWorldDirectory(request),
                        ioExecutor
                )
                .whenComplete((ignored, failure) ->
                        runOnMainThread(() ->
                                completeWorldPreparation(request, failure)
                        )
                );
    }

    private void completeWorldPreparation(
            PendingStart request,
            Throwable failure) {
        PendingStart current = pendingStarts.get(request.playerId());
        if (current != request || request.cancelled() || shuttingDown) {
            cleanupCancelledRequest(request);
            return;
        }
        if (failure != null) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Impossible de préparer le monde du tutoriel pour "
                            + request.playerId(),
                    unwrapCompletionFailure(failure)
            );
            abortPendingStart(
                    request,
                    "La préparation du tutoriel a échoué. Rien n'a été modifié."
            );
            return;
        }

        Player player = Bukkit.getPlayer(request.playerId());
        if (player == null || !player.isOnline()) {
            cancelPendingStart(request.playerId(), false);
            return;
        }
        /*
         * La préparation est asynchrone : le joueur peut mourir ou monter dans
         * un véhicule entre la commande et la création du monde. Continuer dans
         * cet état risquerait une téléportation refusée ou un démontage non
         * restaurable, donc la visite est annulée avant toute modification.
         */
        if (player.isDead() || player.isInsideVehicle()) {
            abortPendingStart(
                    request,
                    "Ton état a changé pendant la préparation ; relance /tuto "
                            + "quand tu es vivant et à pied."
            );
            return;
        }

        World world;
        try {
            WorldCreator creator = new WorldCreator(request.worldName());
            creator.environment(World.Environment.NORMAL);
            creator.type(WorldType.NORMAL);
            creator.seed(settings.seedForNewWorld());
            creator.generateStructures(false);
            creator.generator(new TutorialWorldGenerator());
            world = creator.createWorld();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Création du monde tutoriel impossible : "
                            + request.worldName(),
                    exception
            );
            abortPendingStart(
                    request,
                    "Le monde du tutoriel n'a pas pu être créé."
            );
            return;
        }

        if (world == null) {
            abortPendingStart(
                    request,
                    "Le serveur a refusé de créer le monde du tutoriel."
            );
            return;
        }

        /*
         * La configuration peut être refusée par une implémentation serveur ou
         * par un autre plugin. Tant que la session n'est pas publiée, tout échec
         * repasse par l'annulation transactionnelle et supprime le monde privé.
         */
        try {
            configureTutorialWorld(world);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Configuration du monde tutoriel impossible : "
                            + request.worldName(),
                    exception
            );
            abortPendingStart(
                    request,
                    "Le monde du tutoriel n'a pas pu être configuré."
            );
            return;
        }

        knownTutorialWorldNames.add(world.getName());
        TutorialSession session = new TutorialSession(
                request.playerId(),
                request.snapshot(),
                world
        );
        pendingStarts.remove(request.playerId());
        sessions.put(request.playerId(), session);

        try {
            applyScene(
                    session,
                    TutorialSceneBuilder.baseScene(),
                    success -> {
                        if (!success) {
                            failSession(
                                    session,
                                    "Le décor d'accueil n'a pas pu être chargé."
                            );
                            return;
                        }
                        enterPreparedWorld(session);
                    }
            );
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Initialisation du décor du tutoriel impossible.",
                    exception
            );
            failSession(
                    session,
                    "Le décor d'accueil n'a pas pu être initialisé."
            );
        }
    }

    private void enterPreparedWorld(TutorialSession session) {
        if (!isCurrent(session)) {
            return;
        }
        Player player = Bukkit.getPlayer(session.playerId());
        if (player == null || !player.isOnline()) {
            endSession(session.playerId(), EndReason.DISCONNECT, 0);
            return;
        }

        preparePlayerForTutorial(player);
        ArmorStand donjBot;
        try {
            donjBot = spawnDonjBot(
                    session,
                    TutorialSceneBuilder.npcWelcomeLocation(session.world())
            );
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Impossible de faire apparaître DonjBot.",
                    exception
            );
            restoreAfterFailedEntry(session, player);
            return;
        }

        Location playerLocation =
                TutorialSceneBuilder.Station.WELCOME.location(session.world());
        if (!teleportInternally(session, player, playerLocation)) {
            donjBot.remove();
            restoreAfterFailedEntry(session, player);
            return;
        }

        BossBar bossBar = Bukkit.createBossBar(
                "§bDonjBot §7— §fBienvenue",
                BarColor.BLUE,
                BarStyle.SEGMENTED_10
        );
        /*
         * La barre est enregistrée immédiatement : si une implémentation serveur
         * refuse ensuite une propriété ou l'ajout du joueur, failSession pourra
         * quand même la retirer et ne laissera aucune ressource orpheline.
         */
        session.bossBar(bossBar);
        bossBar.setProgress(0.03D);
        bossBar.addPlayer(player);
        session.markEntered();

        player.sendMessage(
                "§8§m--------------------------------------------------"
        );
        player.sendMessage(
                SYSTEM_PREFIX
                        + "Visite privée lancée. Refais §6/tuto§e à tout "
                        + "moment pour revenir exactement à ton point de départ."
        );
        player.sendMessage(
                "§8§m--------------------------------------------------"
        );
        director.start(session);
    }

    private void restoreAfterFailedEntry(
            TutorialSession session,
            Player player) {
        restorePlayerState(session.returnSnapshot(), player);
        failSession(
                session,
                "La téléportation vers le tutoriel a été refusée."
        );
    }

    private void preparePlayerForTutorial(Player player) {
        player.closeInventory();
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
        player.setExhaustion(0.0F);
        player.setFireTicks(0);
        player.setFallDistance(0.0F);
    }

    private void cancelPendingStart(UUID playerId, boolean notify) {
        PendingStart request = pendingStarts.remove(playerId);
        if (request == null) {
            return;
        }
        request.cancel();
        returnStore.remove(playerId);
        safeSaveReturnStore();

        Player player = Bukkit.getPlayer(playerId);
        if (notify && player != null && player.isOnline()) {
            player.sendMessage(
                    SYSTEM_PREFIX
                            + "Préparation annulée. Tu n'as pas bougé d'un bloc."
            );
        }
        cleanupCancelledRequest(request);
    }

    private void abortPendingStart(
            PendingStart request,
            String playerMessage) {
        if (pendingStarts.get(request.playerId()) != request) {
            cleanupCancelledRequest(request);
            return;
        }
        pendingStarts.remove(request.playerId());
        request.cancel();
        returnStore.remove(request.playerId());
        safeSaveReturnStore();

        Player player = Bukkit.getPlayer(request.playerId());
        if (player != null && player.isOnline()) {
            player.sendMessage("§c[MineGus] " + playerMessage);
        }
        cleanupCancelledRequest(request);
    }

    private void cleanupCancelledRequest(PendingStart request) {
        String worldName = request.worldName();
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            /*
             * Ne jamais supprimer le dossier d'un monde encore enregistré par
             * Bukkit. cleanupWorld ne lance l'effacement qu'après un
             * déchargement réussi ; en cas d'échec, le nettoyage de démarrage
             * reprendra le travail sans risquer de corrompre le monde chargé.
             */
            if (world.getPlayers().isEmpty()) {
                cleanupWorld(world);
            }
            return;
        }
        submitWorldDeletion(worldName);
    }

    void endSession(UUID playerId, EndReason reason, int attempt) {
        TutorialSession session = sessions.get(playerId);
        if (session == null) {
            return;
        }

        if (attempt == 0) {
            boolean firstRequest = session.beginEnding();
            if (!firstRequest && reason != EndReason.MANUAL) {
                return;
            }

            /*
             * Un second /tuto après un refus de téléportation redémarre
             * volontairement le cycle de retour. L'annulation idempotente évite
             * alors que deux chaînes de tentatives s'exécutent en parallèle.
             */
            session.cancelRuntime();
            releaseChunkTickets(session);
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            detachDisconnectedSession(session);
            return;
        }

        Location destination = session.returnSnapshot().resolveLocation(
                attempt == 0 ? plugin.getLogger() : null
        );
        boolean returned = destination != null
                && teleportInternally(session, player, destination);

        if (!returned) {
            if (!shuttingDown && attempt < MAX_RETURN_ATTEMPTS) {
                BukkitTask retry = Bukkit.getScheduler().runTaskLater(
                        plugin,
                        () -> endSession(
                                playerId,
                                reason,
                                attempt + 1
                        ),
                        RETRY_RETURN_DELAY_TICKS
                );
                session.trackTask(retry);
                if (attempt == 0) {
                    player.sendMessage(
                            destination == null
                                    ? "§c[MineGus] Ton monde d'origine n'est "
                                            + "pas encore chargé ; nouvelle "
                                            + "tentative automatique..."
                                    : "§c[MineGus] Le retour a été refusé ; "
                                            + "nouvelle tentative automatique..."
                    );
                }
                return;
            }

            plugin.getLogger().severe(
                    "Impossible de ramener " + player.getName()
                            + " après le tutoriel. Sa destination reste dans "
                            + "tutorial-returns.yml."
            );
            player.sendMessage(
                    "§c[MineGus] Le retour est momentanément bloqué. "
                            + "Ta position d'origine est sauvegardée ; relance "
                            + "§6/tuto§c pour réessayer."
            );
            if (shuttingDown) {
                detachDisconnectedSession(session);
            }
            return;
        }

        boolean stateRestored =
                restorePlayerState(session.returnSnapshot(), player);
        sessions.remove(playerId);
        if (stateRestored) {
            returnStore.remove(playerId);
            safeSaveReturnStore();
        } else {
            player.sendMessage(
                    "§c[MineGus] La position est restaurée, mais certains "
                            + "attributs du joueur n'ont pas pu l'être. "
                            + "La sauvegarde de récupération est conservée."
            );
        }

        if (stateRestored && reason == EndReason.MANUAL) {
            player.sendMessage(
                    SYSTEM_PREFIX
                            + "Visite quittée. Te revoilà exactement là où tu "
                            + "étais !"
            );
        } else if (stateRestored && reason == EndReason.COMPLETED) {
            player.sendMessage(
                    SYSTEM_PREFIX
                            + "Tutoriel terminé. Bonne aventure sur MineGus !"
            );
            player.sendTitle(
                    "§6§lÀ toi de jouer !",
                    "§eDonjBot croit très fort en tes futurs blocs.",
                    10,
                    60,
                    20
            );
        } else if (stateRestored && reason == EndReason.ERROR) {
            player.sendMessage(
                    "§c[MineGus] La visite a été interrompue proprement."
            );
        }

        cleanupWorld(session.world());
    }

    private void stopSessionForShutdown(TutorialSession session) {
        if (!session.ending()) {
            session.beginEnding();
        }
        session.cancelRuntime();
        releaseChunkTickets(session);

        Player player = Bukkit.getPlayer(session.playerId());
        boolean returned = false;
        if (player != null && player.isOnline()) {
            Location destination = session.returnSnapshot()
                    .resolveLocation(plugin.getLogger());
            returned = destination != null
                    && teleportInternally(session, player, destination);
            if (returned
                    && restorePlayerState(session.returnSnapshot(), player)) {
                returnStore.remove(session.playerId());
                safeSaveReturnStore();
            }
        }

        sessions.remove(session.playerId());
        /*
         * En cas d'échec, l'entrée de récupération est conservée et le monde
         * reste chargé s'il contient encore le joueur. Le serveur enregistrera
         * alors sa position et le prochain démarrage le restaurera.
         */
        if (returned || session.world().getPlayers().isEmpty()) {
            cleanupWorldImmediately(session.world());
        }
    }

    private boolean restorePlayerState(
            TutorialPlayerSnapshot snapshot,
            Player player) {
        try {
            snapshot.restoreState(player);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Impossible de restaurer entièrement l'état de "
                            + player.getName() + " après le tutoriel.",
                    exception
            );
            return false;
        }
    }

    /**
     * Détache une visite interrompue par une déconnexion sans supprimer la
     * destination persistée. Le prochain PlayerJoinEvent effectuera le retour.
     */
    private void detachDisconnectedSession(TutorialSession session) {
        if (sessions.get(session.playerId()) != session) {
            return;
        }
        if (!session.ending()) {
            session.beginEnding();
        }
        session.cancelRuntime();
        releaseChunkTickets(session);
        sessions.remove(session.playerId());
        cleanupWorld(session.world());
    }

    void completeSession(TutorialSession session) {
        if (isCurrent(session)) {
            endSession(session.playerId(), EndReason.COMPLETED, 0);
        }
    }

    void failSession(TutorialSession session, String message) {
        if (!isCurrent(session)) {
            return;
        }
        Player player = Bukkit.getPlayer(session.playerId());
        if (player != null && player.isOnline() && message != null) {
            player.sendMessage("§c[MineGus] " + message);
        }
        endSession(session.playerId(), EndReason.ERROR, 0);
    }

    private void recoverOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (returnStore.contains(player.getUniqueId())) {
                recoverPlayer(player, false);
            }
        }
    }

    private void recoverPlayer(Player player, boolean notify) {
        UUID playerId = player.getUniqueId();
        if (sessions.containsKey(playerId)
                || pendingStarts.containsKey(playerId)) {
            return;
        }

        TutorialPlayerSnapshot snapshot = returnStore.get(playerId);
        if (snapshot == null) {
            return;
        }
        Location destination = snapshot.resolveLocation(plugin.getLogger());
        if (destination == null) {
            player.sendMessage(
                    "§c[MineGus] Aucun monde de retour n'est chargé. "
                            + "Un administrateur doit vérifier le serveur."
            );
            return;
        }

        boolean wasInTutorialWorld =
                isLikelyTutorialWorldName(player.getWorld().getName());
        boolean returned = player.teleport(
                destination,
                PlayerTeleportEvent.TeleportCause.PLUGIN
        );
        if (!returned) {
            player.sendMessage(
                    "§c[MineGus] Le retour de sécurité a été refusé. "
                            + "Il sera retenté à la prochaine connexion."
            );
            return;
        }

        if (!restorePlayerState(snapshot, player)) {
            player.sendMessage(
                    "§c[MineGus] La position a été restaurée, mais l'état du "
                            + "joueur reste à vérifier. La sauvegarde de "
                            + "récupération est conservée."
            );
            return;
        }

        returnStore.remove(playerId);
        safeSaveReturnStore();
        if (notify || wasInTutorialWorld) {
            player.sendMessage(
                    SYSTEM_PREFIX
                            + "Retour de sécurité effectué à ta position "
                            + "d'origine."
            );
        }
    }

    private void safeSaveReturnStore() {
        if (ioExecutor.isShutdown()) {
            return;
        }
        returnStore.saveAsync().exceptionally(failure -> {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Sauvegarde de récupération du tutoriel impossible.",
                    unwrapCompletionFailure(failure)
            );
            return null;
        });
    }

    private void configureTutorialWorld(World world) {
        world.setAutoSave(false);
        world.setDifficulty(Difficulty.NORMAL);
        world.setPVP(false);
        world.setTime(settings.worldTime());
        world.setStorm(false);
        world.setThundering(false);
        world.setSpawnFlags(false, false);
        world.setSpawnLocation(
                -24,
                TutorialWorldGenerator.WALK_Y,
                -42
        );

        setGameRule(world, GameRule.DO_DAYLIGHT_CYCLE, false);
        setGameRule(world, GameRule.DO_WEATHER_CYCLE, false);
        setGameRule(world, GameRule.DO_MOB_SPAWNING, false);
        setGameRule(world, GameRule.MOB_GRIEFING, false);
        setGameRule(world, GameRule.DO_FIRE_TICK, false);
        setGameRule(world, GameRule.KEEP_INVENTORY, true);
        setGameRule(world, GameRule.DO_IMMEDIATE_RESPAWN, true);
        setGameRule(world, GameRule.SPAWN_CHUNK_RADIUS, 0);
        setGameRule(world, GameRule.RANDOM_TICK_SPEED, 0);

        world.getWorldBorder().setCenter(120.0D, 0.0D);
        world.getWorldBorder().setSize(settings.worldBorderSize());
        world.getWorldBorder().setDamageAmount(0.0D);
        world.getWorldBorder().setWarningDistance(8);
    }

    private <T> void setGameRule(
            World world,
            GameRule<T> rule,
            T value) {
        try {
            world.setGameRule(rule, value);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Règle de jeu non appliquée : " + rule.getName(),
                    exception
            );
        }
    }

    private void prepareWorldDirectory(PendingStart request) {
        if (request.cancelled()) {
            return;
        }
        Path directory = resolveOwnedWorldPath(request.worldName());
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) {
                throw new IOException(
                        "Le dossier du monde est un lien symbolique."
                );
            }
            String marker = MARKER_MAGIC + System.lineSeparator()
                    + "world=" + request.worldName() + System.lineSeparator()
                    + "player=" + request.playerId() + System.lineSeparator()
                    + "created=" + Instant.now() + System.lineSeparator();
            Files.writeString(
                    directory.resolve(MARKER_FILE),
                    marker,
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Préparation du dossier " + request.worldName()
                            + " impossible",
                    exception
            );
        }
    }

    private String createWorldName(UUID playerId) {
        String compactUuid = playerId.toString().replace("-", "");
        String candidate = settings.worldNamePrefix() + compactUuid;
        int suffix = 0;
        while (Bukkit.getWorld(candidate) != null
                || Files.exists(worldContainer.resolve(candidate))
                || reservedWorldNames.contains(candidate)) {
            suffix++;
            candidate = settings.worldNamePrefix()
                    + compactUuid
                    + "_"
                    + suffix;
        }
        return candidate;
    }

    private Path resolveOwnedWorldPath(String worldName) {
        if (worldName == null
                || !worldName.startsWith(settings.worldNamePrefix())
                || worldName.contains("/")
                || worldName.contains("\\")
                || worldName.contains("..")) {
            throw new IllegalArgumentException(
                    "Nom de monde tutoriel non sûr : " + worldName
            );
        }
        Path path = worldContainer.resolve(worldName)
                .toAbsolutePath()
                .normalize();
        if (!worldContainer.equals(path.getParent())) {
            throw new IllegalArgumentException(
                    "Le monde tutoriel sort du dossier des mondes."
            );
        }
        return path;
    }

    private boolean hasValidMarker(String worldName) {
        try {
            Path directory = resolveOwnedWorldPath(worldName);
            Path marker = directory.resolve(MARKER_FILE);
            if (Files.isSymbolicLink(directory)
                    || Files.isSymbolicLink(marker)
                    || !Files.isRegularFile(
                            marker,
                            LinkOption.NOFOLLOW_LINKS
                    )) {
                return false;
            }
            List<String> lines = Files.readAllLines(
                    marker,
                    StandardCharsets.UTF_8
            );
            return !lines.isEmpty()
                    && MARKER_MAGIC.equals(lines.get(0))
                    && lines.stream().anyMatch(
                            line -> line.equals("world=" + worldName)
                    );
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private void cleanupWorld(World world) {
        if (world == null) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> attemptWorldUnload(world, 0),
                1L
        );
    }

    private void attemptWorldUnload(World world, int attempt) {
        if (world == null || Bukkit.getWorld(world.getUID()) == null) {
            if (world != null) {
                submitWorldDeletion(world.getName());
            }
            return;
        }
        if (!world.getPlayers().isEmpty()) {
            if (attempt < 5 && !shuttingDown) {
                Bukkit.getScheduler().runTaskLater(
                        plugin,
                        () -> attemptWorldUnload(world, attempt + 1),
                        20L
                );
            }
            return;
        }

        world.removePluginChunkTickets(plugin);
        boolean unloaded = Bukkit.unloadWorld(world, false);
        if (unloaded) {
            submitWorldDeletion(world.getName());
        } else if (attempt < 5 && !shuttingDown) {
            Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> attemptWorldUnload(world, attempt + 1),
                    20L
            );
        } else {
            plugin.getLogger().warning(
                    "Le monde tutoriel '" + world.getName()
                            + "' n'a pas pu être déchargé. Il sera nettoyé "
                            + "au prochain démarrage."
            );
        }
    }

    private void cleanupWorldImmediately(World world) {
        if (world == null) {
            return;
        }
        world.removePluginChunkTickets(plugin);
        if (world.getPlayers().isEmpty()
                && Bukkit.unloadWorld(world, false)) {
            submitWorldDeletion(world.getName());
        }
    }

    private void submitWorldDeletion(String worldName) {
        if (worldName == null) {
            return;
        }
        reservedWorldNames.remove(worldName);
        if (ioExecutor.isShutdown()) {
            return;
        }
        CompletableFuture.runAsync(
                () -> deleteOwnedWorldDirectory(worldName),
                ioExecutor
        ).exceptionally(failure -> {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Suppression du monde tutoriel '" + worldName
                            + "' impossible. Un nouveau nettoyage sera tenté "
                            + "au démarrage.",
                    unwrapCompletionFailure(failure)
            );
            return null;
        });
    }

    private void deleteOwnedWorldDirectory(String worldName) {
        if (reservedWorldNames.contains(worldName)
                || !hasValidMarker(worldName)) {
            return;
        }
        Path directory = resolveOwnedWorldPath(worldName);
        if (!Files.isDirectory(
                directory,
                LinkOption.NOFOLLOW_LINKS
        ) || Files.isSymbolicLink(directory)) {
            return;
        }

        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(
                        Path file,
                        BasicFileAttributes attributes)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(
                        Path dir,
                        IOException failure)
                        throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            knownTutorialWorldNames.remove(worldName);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Suppression de " + directory + " impossible",
                    exception
            );
        }
    }

    private void discoverAndCleanStaleWorlds() {
        if (shuttingDown) {
            return;
        }

        Set<String> activeNames = new HashSet<>(reservedWorldNames);
        for (World world : List.copyOf(Bukkit.getWorlds())) {
            String name = world.getName();
            if (!isLikelyTutorialWorldName(name)
                    || activeNames.contains(name)
                    || !hasValidMarker(name)) {
                continue;
            }
            knownTutorialWorldNames.add(name);

            for (Player player : List.copyOf(world.getPlayers())) {
                if (returnStore.contains(player.getUniqueId())) {
                    recoverPlayer(player, true);
                } else {
                    Location fallback = findSafeFallbackWorld(world);
                    if (fallback != null) {
                        player.teleport(
                                fallback,
                                PlayerTeleportEvent.TeleportCause.PLUGIN
                        );
                    }
                }
            }
            if (world.getPlayers().isEmpty()
                    && Bukkit.unloadWorld(world, false)) {
                submitWorldDeletion(name);
            }
        }

        Set<String> loadedNames = new HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            loadedNames.add(world.getName());
        }
        CompletableFuture.runAsync(
                () -> scanUnloadedWorldDirectories(loadedNames),
                ioExecutor
        ).exceptionally(failure -> {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Recherche des anciens mondes tutoriel incomplète.",
                    unwrapCompletionFailure(failure)
            );
            return null;
        });
    }

    private void scanUnloadedWorldDirectories(Set<String> loadedNames) {
        if (!Files.isDirectory(worldContainer)) {
            return;
        }
        try (var children = Files.list(worldContainer)) {
            children
                    .filter(path -> Files.isDirectory(
                            path,
                            LinkOption.NOFOLLOW_LINKS
                    ))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(path -> path.getFileName().toString())
                    .filter(this::isLikelyTutorialWorldName)
                    .filter(name -> !loadedNames.contains(name))
                    .filter(name -> !reservedWorldNames.contains(name))
                    .filter(this::hasValidMarker)
                    .forEach(this::deleteOwnedWorldDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Lecture du dossier des mondes impossible",
                    exception
            );
        }
    }

    private boolean isLikelyTutorialWorldName(String worldName) {
        return worldName != null
                && worldName.startsWith(settings.worldNamePrefix());
    }

    private boolean isTutorialWorld(World world) {
        if (world == null) {
            return false;
        }
        String worldName = world.getName();
        if (knownTutorialWorldNames.contains(worldName)) {
            return true;
        }
        boolean active = sessions.values().stream()
                .anyMatch(session ->
                        session.world().getUID().equals(world.getUID())
                );
        if (active) {
            return true;
        }

        /*
         * Après un redémarrage, un gestionnaire de mondes peut charger un ancien
         * bac à sable avant notre nettoyage différé. Le marqueur signé permet de
         * le reconnaître immédiatement sans se fier au seul préfixe.
         */
        if (isLikelyTutorialWorldName(worldName)
                && hasValidMarker(worldName)) {
            knownTutorialWorldNames.add(worldName);
            return true;
        }
        return false;
    }

    private Location findSafeFallbackWorld(World excluded) {
        return Bukkit.getWorlds().stream()
                .filter(world -> excluded == null
                        || !world.getUID().equals(excluded.getUID()))
                .filter(world -> !isTutorialWorld(world))
                .map(World::getSpawnLocation)
                .findFirst()
                .orElse(null);
    }

    private void releaseChunkTickets(TutorialSession session) {
        World world = session.world();
        if (Bukkit.getWorld(world.getUID()) == null) {
            session.clearChunkTickets();
            return;
        }
        for (long key : session.chunkTickets()) {
            world.removePluginChunkTicket(
                    TutorialSession.chunkX(key),
                    TutorialSession.chunkZ(key),
                    plugin
            );
        }
        session.clearChunkTickets();
    }

    private void runOnMainThread(Runnable task) {
        if (shuttingDown || !plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    private record PendingStart(
            UUID playerId,
            TutorialPlayerSnapshot snapshot,
            String worldName,
            AtomicBoolean cancelledFlag) {

        PendingStart(
                UUID playerId,
                TutorialPlayerSnapshot snapshot,
                String worldName) {
            this(
                    playerId,
                    snapshot,
                    worldName,
                    new AtomicBoolean(false)
            );
        }

        boolean cancelled() {
            return cancelledFlag.get();
        }

        void cancel() {
            cancelledFlag.set(true);
        }
    }

    enum EndReason {
        MANUAL,
        COMPLETED,
        DISCONNECT,
        ERROR
    }

    private static final class TutorialThreadFactory
            implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(
                    runnable,
                    "MineGus-Tutorial-IO"
            );
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) ->
                    Bukkit.getLogger().log(
                            Level.SEVERE,
                            "Erreur non interceptée du stockage tutoriel.",
                            failure
                    )
            );
            return thread;
        }
    }

    /* ====================================================================== */
    /* API interne utilisée par le metteur en scène.                           */
    /* ====================================================================== */

    boolean isCurrent(TutorialSession session) {
        return session != null
                && !session.ending()
                && sessions.get(session.playerId()) == session
                && Bukkit.getWorld(session.world().getUID()) != null;
    }

    long recallDistanceBlocks() {
        return Math.round(settings.recallDistance());
    }

    Player player(TutorialSession session) {
        if (!isCurrent(session)) {
            return null;
        }
        Player player = Bukkit.getPlayer(session.playerId());
        return player != null && player.isOnline() ? player : null;
    }

    int beginPhase(TutorialSession session) {
        return session.nextPhase();
    }

    int autoExitDelayTicks() {
        return settings.autoExitDelayTicks();
    }

    void later(
            TutorialSession session,
            int phase,
            long delayTicks,
            Runnable action) {
        if (!isCurrent(session)) {
            return;
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> {
                    if (isCurrent(session) && session.isPhase(phase)) {
                        try {
                            action.run();
                        } catch (RuntimeException exception) {
                            plugin.getLogger().log(
                                    Level.SEVERE,
                                    "Erreur dans le scénario du tutoriel.",
                                    exception
                            );
                            failSession(
                                    session,
                                    "DonjBot a perdu une page de son script."
                            );
                        }
                    }
                },
                Math.max(0L, delayTicks)
        );
        session.trackTask(task);
    }

    void speak(TutorialSession session, String message) {
        Player player = player(session);
        if (player == null) {
            return;
        }
        faceBotTowardPlayer(session, player);
        player.sendMessage(CHAT_PREFIX + message);
        player.playSound(
                player.getLocation(),
                Sound.BLOCK_NOTE_BLOCK_PLING,
                0.45F,
                1.35F
        );
        ArmorStand bot = session.donjBot();
        if (bot != null) {
            bot.getWorld().spawnParticle(
                    Particle.END_ROD,
                    bot.getEyeLocation().add(0.0D, 0.2D, 0.0D),
                    3,
                    0.12D,
                    0.12D,
                    0.12D,
                    0.0D
            );
        }
    }

    void showCommand(TutorialSession session, String command) {
        Player player = player(session);
        ArmorStand bot = session.donjBot();
        if (player == null || bot == null) {
            return;
        }

        player.sendMessage(
                "§8[§bDonjBot exécute§8] §6§l" + command
        );
        player.playSound(
                player.getLocation(),
                Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                0.8F,
                1.2F
        );
        bot.setRightArmPose(new EulerAngle(
                Math.toRadians(-120.0D),
                0.0D,
                Math.toRadians(8.0D)
        ));
        bot.getWorld().spawnParticle(
                Particle.ENCHANT,
                bot.getEyeLocation(),
                24,
                0.45D,
                0.55D,
                0.45D,
                0.15D
        );

        int phase = session.currentPhase();
        later(session, phase, 8L, () ->
                bot.setRightArmPose(new EulerAngle(
                        Math.toRadians(-8.0D),
                        0.0D,
                        Math.toRadians(4.0D)
                ))
        );
    }

    void chapter(
            TutorialSession session,
            String title,
            String subtitle,
            double progress) {
        Player player = player(session);
        if (player == null) {
            return;
        }
        player.sendTitle(
                "§b§l" + title,
                "§f" + subtitle,
                10,
                45,
                10
        );
        BossBar bossBar = session.bossBar();
        if (bossBar != null) {
            bossBar.setTitle("§bDonjBot §7— §f" + title);
            bossBar.setProgress(Math.max(0.0D, Math.min(1.0D, progress)));
        }
    }

    void magicalEffect(TutorialSession session, Location center) {
        if (!isCurrent(session) || center == null) {
            return;
        }
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        world.spawnParticle(
                Particle.ENCHANT,
                center,
                90,
                2.8D,
                1.8D,
                2.8D,
                0.35D
        );
        world.spawnParticle(
                Particle.END_ROD,
                center,
                32,
                2.0D,
                1.2D,
                2.0D,
                0.04D
        );
        Player player = player(session);
        if (player != null) {
            player.playSound(
                    player.getLocation(),
                    Sound.BLOCK_BEACON_ACTIVATE,
                    0.75F,
                    1.25F
            );
        }
    }

    void applyScene(
            TutorialSession session,
            List<TutorialSceneBuilder.Placement> placements,
            Consumer<Boolean> completion) {
        Objects.requireNonNull(placements, "placements");
        Objects.requireNonNull(completion, "completion");
        if (!isCurrent(session)) {
            return;
        }
        if (placements.isEmpty()) {
            invokeSceneCompletion(session, completion, true);
            return;
        }

        Set<Long> chunks = new HashSet<>();
        for (TutorialSceneBuilder.Placement placement : placements) {
            chunks.add(chunkKey(
                    placement.x() >> 4,
                    placement.z() >> 4
            ));
        }

        List<CompletableFuture<Chunk>> loads = new ArrayList<>(chunks.size());
        for (long key : chunks) {
            loads.add(session.world().getChunkAtAsync(
                    chunkX(key),
                    chunkZ(key),
                    true
            ));
        }

        AtomicBoolean completed = new AtomicBoolean(false);
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> {
                    if (completed.compareAndSet(false, true)
                            && isCurrent(session)) {
                        plugin.getLogger().warning(
                                "Chargement des chunks du tutoriel trop long "
                                        + "pour " + session.playerId()
                        );
                        invokeSceneCompletion(
                                session,
                                completion,
                                false
                        );
                    }
                },
                settings.chunkLoadTimeoutTicks()
        );
        session.trackTask(timeout);

        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) ->
                        runOnMainThread(() -> {
                            if (completed.get() || !isCurrent(session)) {
                                return;
                            }
                            if (failure != null) {
                                if (completed.compareAndSet(false, true)) {
                                    timeout.cancel();
                                    plugin.getLogger().log(
                                            Level.WARNING,
                                            "Préchargement d'un décor du "
                                                    + "tutoriel impossible.",
                                            unwrapCompletionFailure(failure)
                                    );
                                    invokeSceneCompletion(
                                            session,
                                            completion,
                                            false
                                    );
                                }
                                return;
                            }

                            timeout.cancel();
                            for (long key : chunks) {
                                int chunkX = chunkX(key);
                                int chunkZ = chunkZ(key);
                                session.world().addPluginChunkTicket(
                                        chunkX,
                                        chunkZ,
                                        plugin
                                );
                                session.trackChunkTicket(chunkX, chunkZ);
                            }
                            applyPlacementsInBatches(
                                    session,
                                    placements,
                                    completed,
                                    completion
                            );
                        })
                );
    }

    private void applyPlacementsInBatches(
            TutorialSession session,
            List<TutorialSceneBuilder.Placement> placements,
            AtomicBoolean completed,
            Consumer<Boolean> completion) {
        BukkitRunnable builder = new BukkitRunnable() {
            private int cursor;

            @Override
            public void run() {
                if (!isCurrent(session)) {
                    cancel();
                    return;
                }

                long deadline = System.nanoTime()
                        + settings.buildBudgetNanos();
                int applied = 0;
                try {
                    while (cursor < placements.size()
                            && applied < settings.blocksPerTick()
                            && System.nanoTime() < deadline) {
                        placements.get(cursor++).apply(session.world());
                        applied++;
                    }
                } catch (RuntimeException exception) {
                    cancel();
                    if (completed.compareAndSet(false, true)) {
                        plugin.getLogger().log(
                                Level.SEVERE,
                                "Application d'un décor du tutoriel impossible.",
                                exception
                        );
                        invokeSceneCompletion(
                                session,
                                completion,
                                false
                        );
                    }
                    return;
                }

                if (cursor >= placements.size()) {
                    cancel();
                    if (completed.compareAndSet(false, true)) {
                        invokeSceneCompletion(
                                session,
                                completion,
                                true
                        );
                    }
                }
            }
        };
        BukkitTask task = builder.runTaskTimer(plugin, 1L, 1L);
        session.trackTask(task);
    }

    /**
     * Exécute la suite d'un décor sans laisser une exception de scénario bloquer
     * silencieusement la session dans le planificateur Bukkit.
     */
    private void invokeSceneCompletion(
            TutorialSession session,
            Consumer<Boolean> completion,
            boolean success) {
        try {
            completion.accept(success);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Erreur pendant la finalisation d'un décor du tutoriel.",
                    exception
            );
            failSession(
                    session,
                    "DonjBot n'a pas pu terminer cette démonstration."
            );
        }
    }

    void walkTo(
            TutorialSession session,
            TutorialSceneBuilder.Station destination,
            Runnable completion) {
        if (!isCurrent(session)) {
            return;
        }
        List<Location> route = TutorialSceneBuilder.route(
                session.world(),
                session.station(),
                destination
        );
        walkRoute(session, route, () -> {
            session.station(destination);
            completion.run();
        });
    }

    private void walkRoute(
            TutorialSession session,
            List<Location> route,
            Runnable completion) {
        ArmorStand bot = session.donjBot();
        if (bot == null || route.isEmpty()) {
            failSession(session, "DonjBot s'est volatilisé.");
            return;
        }

        List<Location> points = route.stream()
                .map(Location::clone)
                .toList();

        BukkitRunnable movement = new BukkitRunnable() {
            private int index;
            private int consecutiveTeleportFailures;
            private long animationTick;

            @Override
            public void run() {
                if (!isCurrent(session)) {
                    cancel();
                    return;
                }
                ArmorStand currentBot = session.donjBot();
                if (currentBot == null) {
                    cancel();
                    failSession(session, "DonjBot s'est volatilisé.");
                    return;
                }
                if (index >= points.size()) {
                    resetBotPose(currentBot);
                    cancel();
                    try {
                        completion.run();
                    } catch (RuntimeException exception) {
                        plugin.getLogger().log(
                                Level.SEVERE,
                                "Erreur à la fin d'un déplacement de DonjBot.",
                                exception
                        );
                        failSession(
                                session,
                                "DonjBot a perdu la suite de son itinéraire."
                        );
                    }
                    return;
                }

                Location current = currentBot.getLocation();
                Location target = points.get(index);
                Vector delta = target.toVector().subtract(current.toVector());
                delta.setY(0.0D);
                double distance = delta.length();

                if (distance <= settings.walkSpeed()) {
                    Location reached = target.clone();
                    reached.setYaw(current.getYaw());
                    if (!moveBot(currentBot, reached)) {
                        return;
                    }
                    index++;
                    return;
                }

                Vector step = delta.normalize()
                        .multiply(settings.walkSpeed());
                Location next = current.clone().add(step);
                next.setY(TutorialWorldGenerator.WALK_Y);
                next.setYaw(yawFor(step));
                next.setPitch(0.0F);
                if (!moveBot(currentBot, next)) {
                    return;
                }

                double swing = Math.sin(animationTick * 0.55D) * 0.65D;
                currentBot.setRightArmPose(new EulerAngle(
                        swing,
                        0.0D,
                        0.08D
                ));
                currentBot.setLeftArmPose(new EulerAngle(
                        -swing,
                        0.0D,
                        -0.08D
                ));
                currentBot.setRightLegPose(new EulerAngle(
                        -swing,
                        0.0D,
                        0.0D
                ));
                currentBot.setLeftLegPose(new EulerAngle(
                        swing,
                        0.0D,
                        0.0D
                ));

                if (animationTick % 7L == 0L) {
                    currentBot.getWorld().spawnParticle(
                            Particle.END_ROD,
                            next.clone().add(0.0D, 0.2D, 0.0D),
                            1,
                            0.15D,
                            0.05D,
                            0.15D,
                            0.0D
                    );
                }
                animationTick++;
            }

            /**
             * Les téléportations d'entités peuvent être annulées par un plugin
             * de protection. Une annulation isolée est tolérée ; une série
             * persistante interrompt la visite au lieu de bloquer DonjBot.
             */
            private boolean moveBot(ArmorStand currentBot, Location target) {
                if (currentBot.teleport(target)) {
                    consecutiveTeleportFailures = 0;
                    return true;
                }
                consecutiveTeleportFailures++;
                if (consecutiveTeleportFailures >= 20) {
                    cancel();
                    failSession(
                            session,
                            "Un plugin empêche DonjBot de poursuivre la visite."
                    );
                }
                return false;
            }
        };
        BukkitTask task = movement.runTaskTimer(plugin, 1L, 1L);
        session.trackTask(task);
    }

    boolean teleportPair(
            TutorialSession session,
            Location botLocation,
            Location playerLocation) {
        Player player = player(session);
        ArmorStand bot = session.donjBot();
        if (player == null || bot == null
                || botLocation == null || playerLocation == null) {
            return false;
        }

        session.world().spawnParticle(
                Particle.PORTAL,
                bot.getLocation().add(0.0D, 1.0D, 0.0D),
                70,
                0.8D,
                1.0D,
                0.8D,
                0.7D
        );
        if (!bot.teleport(botLocation)) {
            return false;
        }
        boolean teleported = teleportInternally(
                session,
                player,
                playerLocation
        );
        if (teleported) {
            session.world().spawnParticle(
                    Particle.PORTAL,
                    playerLocation.clone().add(0.0D, 1.0D, 0.0D),
                    70,
                    0.8D,
                    1.0D,
                    0.8D,
                    0.7D
            );
            player.playSound(
                    player.getLocation(),
                    Sound.ENTITY_ENDERMAN_TELEPORT,
                    0.65F,
                    1.25F
            );
        }
        return teleported;
    }

    <T extends Entity> T spawnDemoEntity(
            TutorialSession session,
            Location location,
            Class<T> type,
            Consumer<T> configurator) {
        if (!isCurrent(session)) {
            return null;
        }
        T entity = session.world().spawn(
                location,
                type,
                spawned -> {
                    /*
                     * L'entité est suivie avant sa configuration : si un setter
                     * ou un plugin tiers lève une exception, le nettoyage de la
                     * session pourra malgré tout la supprimer.
                     */
                    session.trackEntity(spawned);
                    spawned.setPersistent(false);
                    configurator.accept(spawned);
                }
        );
        session.trackEntity(entity);
        return entity;
    }

    ArmorStand spawnLabel(
            TutorialSession session,
            Location location,
            String text) {
        return spawnDemoEntity(
                session,
                location,
                ArmorStand.class,
                stand -> {
                    stand.setVisible(false);
                    stand.setMarker(true);
                    stand.setGravity(false);
                    stand.setInvulnerable(true);
                    stand.setSilent(true);
                    stand.setCustomName(text);
                    stand.setCustomNameVisible(true);
                }
        );
    }

    void sparkle(
            TutorialSession session,
            Location location,
            Particle particle,
            int amount) {
        if (!isCurrent(session) || location == null) {
            return;
        }
        session.world().spawnParticle(
                particle,
                location,
                amount,
                1.3D,
                1.0D,
                1.3D,
                0.08D
        );
    }

    void breakBlockDemo(
            TutorialSession session,
            Location location) {
        if (!isCurrent(session) || location == null) {
            return;
        }
        Block block = location.getBlock();
        Material original = block.getType();
        /*
         * Particle.BLOCK exige des données de bloc cohérentes. Une étape déjà
         * jouée ou interrompue peut avoir laissé de l'air à cet emplacement.
         */
        if (original.isAir()) {
            return;
        }
        session.world().spawnParticle(
                Particle.BLOCK,
                block.getLocation().add(0.5D, 0.5D, 0.5D),
                42,
                0.35D,
                0.35D,
                0.35D,
                original.createBlockData()
        );
        Player player = player(session);
        if (player != null) {
            player.playSound(
                    block.getLocation(),
                    Sound.BLOCK_STONE_BREAK,
                    0.9F,
                    0.9F
            );
        }
        block.setType(Material.AIR, false);
    }

    private ArmorStand spawnDonjBot(
            TutorialSession session,
            Location location) {
        ArmorStand bot = session.world().spawn(
                location,
                ArmorStand.class,
                stand -> {
                    /*
                     * Même principe que pour les acteurs de démonstration :
                     * enregistrer d'abord garantit un nettoyage transactionnel.
                     */
                    session.trackEntity(stand);
                    stand.setArms(true);
                    stand.setBasePlate(false);
                    stand.setGravity(false);
                    stand.setVisible(true);
                    stand.setSmall(false);
                    stand.setMarker(false);
                    stand.setInvulnerable(true);
                    stand.setSilent(true);
                    stand.setPersistent(false);
                    stand.setCustomName("§b§lDonjBot §7[Guide magique]");
                    stand.setCustomNameVisible(true);
                    stand.setGlowing(true);
                    stand.setCollidable(false);
                    stand.setHeadPose(new EulerAngle(
                            Math.toRadians(-4.0D),
                            0.0D,
                            0.0D
                    ));
                }
        );

        EntityEquipment equipment = bot.getEquipment();
        if (equipment != null) {
            equipment.setHelmet(new ItemStack(
                    Material.LIGHT_BLUE_STAINED_GLASS
            ));
            equipment.setChestplate(coloredLeather(
                    Material.LEATHER_CHESTPLATE,
                    Color.fromRGB(40, 150, 205)
            ));
            equipment.setLeggings(coloredLeather(
                    Material.LEATHER_LEGGINGS,
                    Color.fromRGB(30, 75, 120)
            ));
            equipment.setBoots(coloredLeather(
                    Material.LEATHER_BOOTS,
                    Color.fromRGB(230, 190, 45)
            ));
            equipment.setItemInMainHand(
                    new ItemStack(Material.BLAZE_ROD)
            );
            equipment.setItemInOffHand(
                    new ItemStack(Material.COMPASS)
            );
        }

        session.donjBot(bot);
        return bot;
    }

    private ItemStack coloredLeather(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(color);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void faceBotTowardPlayer(
            TutorialSession session,
            Player player) {
        ArmorStand bot = session.donjBot();
        if (bot == null || !bot.getWorld().equals(player.getWorld())) {
            return;
        }
        Vector direction = player.getEyeLocation().toVector()
                .subtract(bot.getEyeLocation().toVector());
        direction.setY(0.0D);
        if (direction.lengthSquared() < 0.0001D) {
            return;
        }
        Location location = bot.getLocation();
        location.setYaw(yawFor(direction));
        bot.teleport(location);
    }

    private void resetBotPose(ArmorStand bot) {
        bot.setRightArmPose(new EulerAngle(
                Math.toRadians(-8.0D),
                0.0D,
                Math.toRadians(4.0D)
        ));
        bot.setLeftArmPose(new EulerAngle(
                Math.toRadians(4.0D),
                0.0D,
                Math.toRadians(-4.0D)
        ));
        bot.setRightLegPose(EulerAngle.ZERO);
        bot.setLeftLegPose(EulerAngle.ZERO);
    }

    private boolean teleportInternally(
            TutorialSession session,
            Player player,
            Location destination) {
        if (destination == null || destination.getWorld() == null) {
            return false;
        }
        session.beginInternalTeleport();
        try {
            return player.teleport(
                    destination,
                    PlayerTeleportEvent.TeleportCause.PLUGIN
            );
        } finally {
            session.endInternalTeleport();
        }
    }

    private static float yawFor(Vector direction) {
        return (float) Math.toDegrees(
                Math.atan2(-direction.getX(), direction.getZ())
        );
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFF_FFFFL);
    }

    private static int chunkX(long key) {
        return (int) (key >> 32);
    }

    private static int chunkZ(long key) {
        return (int) key;
    }

    /* ====================================================================== */
    /* Surveillance et protections du bac à sable.                            */
    /* ====================================================================== */

    private void monitorSessions() {
        for (TutorialSession session : List.copyOf(sessions.values())) {
            if (!session.entered() || session.ending()) {
                continue;
            }
            Player player = Bukkit.getPlayer(session.playerId());
            ArmorStand bot = session.donjBot();
            if (player == null || !player.isOnline()) {
                endSession(session.playerId(), EndReason.DISCONNECT, 0);
                continue;
            }
            if (bot == null) {
                failSession(session, "DonjBot s'est volatilisé.");
                continue;
            }

            boolean wrongWorld = !player.getWorld()
                    .getUID()
                    .equals(session.world().getUID());
            boolean tooFar = !wrongWorld
                    && player.getLocation().distanceSquared(bot.getLocation())
                    > settings.recallDistanceSquared();
            if (!wrongWorld && !tooFar) {
                continue;
            }

            Location destination = safeLocationNearBot(bot);
            if (teleportInternally(session, player, destination)
                    && session.maySendRecallNotice(
                            System.currentTimeMillis()
                    )) {
                speak(
                        session,
                        "Hop ! Téléportation anti-touriste-perdu. "
                                + "Reste à moins de §b"
                                + Math.round(settings.recallDistance())
                                + " blocs§f de mes circuits !"
                );
            }
        }
    }

    private Location safeLocationNearBot(ArmorStand bot) {
        Location botLocation = bot.getLocation();
        Vector side = botLocation.getDirection()
                .setY(0.0D)
                .normalize()
                .crossProduct(new Vector(0.0D, 1.0D, 0.0D))
                .multiply(1.4D);
        Location destination = botLocation.clone().add(side);
        destination.setY(TutorialWorldGenerator.WALK_Y);
        destination.setYaw(botLocation.getYaw());
        destination.setPitch(0.0F);
        return destination;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        TutorialSession session =
                sessions.get(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }
        if (!isTutoCommand(event.getMessage())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    CHAT_PREFIX
                            + "Pendant la visite, garde tes commandes dans ta "
                            + "poche. §6/tuto§f permet de quitter."
            );
        }
    }

    static boolean isTutoCommand(String rawMessage) {
        if (rawMessage == null) {
            return false;
        }
        String normalized = rawMessage.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        int space = normalized.indexOf(' ');
        if (space >= 0) {
            normalized = normalized.substring(0, space);
        }
        normalized = normalized.toLowerCase(Locale.ROOT);

        /*
         * Seule la commande nue ou le namespace réel du plugin sont admis.
         * Retirer n'importe quel namespace rendrait /autre-plugin:tuto capable
         * de contourner les protections de la visite.
         */
        return "tuto".equals(normalized)
                || "mineplugin:tuto".equals(normalized);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTeleport(PlayerTeleportEvent event) {
        TutorialSession session =
                sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            if (!session.isInternalTeleport()) {
                event.setCancelled(true);
            }
            return;
        }

        /*
         * Les mondes de visite sont strictement privés. Un spectateur, une
         * commande d'administration ou un plugin de téléportation ne peut pas
         * introduire un second joueur dans le bac à sable d'une session.
         */
        Location destination = event.getTo();
        if (destination != null
                && isTutorialWorld(destination.getWorld())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    SYSTEM_PREFIX
                            + "Ce monde de tutoriel appartient à une autre "
                            + "visite privée."
            );
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPortal(PlayerPortalEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())
                || isTutorialWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
            event.setDropItems(false);
            event.setExpToDrop(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())
                || isTutorialWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (isTutorialWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (isTutorialWorld(event.getLocation().getWorld())) {
            event.setCancelled(true);
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())
                || isTutorialWorld(event.getRightClicked().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player
                && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (isTutorialWorld(event.getEntity().getWorld())
                || event.getEntity() instanceof Player player
                && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFoodLevel(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player
                && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            event.setFoodLevel(20);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (isTutorialWorld(event.getLocation().getWorld())
                && event.getSpawnReason()
                != CreatureSpawnEvent.SpawnReason.CUSTOM) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTarget(EntityTargetEvent event) {
        if (isTutorialWorld(event.getEntity().getWorld())
                || event.getTarget() != null
                && isTutorialWorld(event.getTarget().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onWeather(WeatherChangeEvent event) {
        if (event.toWeatherState()
                && isTutorialWorld(event.getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        TutorialSession session =
                sessions.get(event.getPlayer().getUniqueId());
        if (session != null
                && !session.isInternalTeleport()
                && !event.getPlayer().getWorld()
                .getUID()
                .equals(session.world().getUID())) {
            failSession(
                    session,
                    "Un autre plugin a déplacé la visite."
            );
        }
    }

    /**
     * Certains gestionnaires de mondes chargent leurs mondes après les plugins.
     * Dès que le vrai monde d'origine apparaît, une récupération qui attendait
     * peut donc reprendre sans exiger une nouvelle reconnexion.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        UUID loadedWorldId = event.getWorld().getUID();
        String loadedWorldName = event.getWorld().getName();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (shuttingDown || Bukkit.getWorld(loadedWorldId) == null) {
                return;
            }

            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID playerId = player.getUniqueId();
                TutorialPlayerSnapshot snapshot = returnStore.get(playerId);
                if (snapshot == null
                        || (!snapshot.worldId().equals(loadedWorldId)
                        && !snapshot.worldName().equals(loadedWorldName))) {
                    continue;
                }

                TutorialSession session = sessions.get(playerId);
                if (session != null && session.ending()) {
                    endSession(playerId, EndReason.MANUAL, 0);
                } else if (session == null) {
                    recoverPlayer(player, true);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> recoverPlayer(event.getPlayer(), true),
                1L
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        handleDisconnect(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();

        /*
         * Un autre plugin peut encore annuler le kick après ce listener. Le
         * contrôle au tick suivant évite de détacher à tort une visite dont le
         * joueur est finalement resté connecté ; PlayerQuitEvent couvre le cas
         * normal où la déconnexion est déjà effective.
         */
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                handleDisconnect(playerId);
            }
        });
    }

    private void handleDisconnect(UUID playerId) {
        if (pendingStarts.containsKey(playerId)) {
            cancelPendingStart(playerId, false);
            return;
        }

        TutorialSession session = sessions.get(playerId);
        if (session != null) {
            detachDisconnectedSession(session);
        }
    }
}
