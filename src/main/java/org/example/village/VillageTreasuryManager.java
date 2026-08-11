package org.example.village;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Lectern;
import org.bukkit.block.data.Openable;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Exploite les salles des coffres générées par {@code /village}.
 *
 * <p>Les mondes et inventaires Bukkit sont consultés uniquement sur le thread
 * serveur. Seule la sérialisation d'un instantané immuable est envoyée sur le
 * thread d'entrées/sorties dédié.</p>
 */
public final class VillageTreasuryManager implements Listener {

    public static final String ADMIN_PERMISSION =
            "mineplugin.village.treasury.admin";

    private static final String BOSS_BAR_TITLE =
            ChatColor.GOLD + "" + ChatColor.BOLD + "Salle des coffres";
    private static final long MESSAGE_COOLDOWN_MILLIS = 1_500L;
    private static final long FULLNESS_REFRESH_MILLIS = 5_000L;

    private static final List<Material> ELIGIBLE_MATERIALS =
            buildEligibleMaterials();
    private static final List<PotionType> ELIGIBLE_POTIONS =
            buildEligiblePotions();

    private final JavaPlugin plugin;
    private final VillageTreasurySettings settings;
    private final VillageTreasuryStore store;
    private final Random random = new Random();
    private final ExecutorService ioExecutor;

    private final Map<Integer, VillageTreasury> treasuries =
            new LinkedHashMap<>();
    private final Map<Integer, BossBar> bossBars = new HashMap<>();
    private final Map<UUID, Integer> visibleRoomByPlayer =
            new HashMap<>();
    private final Map<Integer, BukkitTask> doorCloseTasks =
            new HashMap<>();
    private final Map<UUID, Long> lastDeniedMessageAt =
            new HashMap<>();
    private final Map<Integer, Double> fullnessByVillage =
            new HashMap<>();
    private final Map<Integer, Long> lastFullnessRefreshAt =
            new HashMap<>();

    private volatile boolean writable;
    private volatile boolean shuttingDown;
    private boolean dirty;
    private BukkitTask maintenanceTask;
    private BukkitTask autosaveTask;
    private BukkitTask urgentSaveTask;

    public VillageTreasuryManager(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.settings = VillageTreasurySettings.from(plugin);
        this.store = new VillageTreasuryStore(
                plugin.getDataFolder(),
                plugin.getLogger()
        );
        this.ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "MineGus-Tresor-IO"
            );
            thread.setDaemon(true);
            return thread;
        });

        VillageTreasuryStore.LoadResult loaded = store.load();
        writable = loaded.writable();
        for (VillageTreasury treasury : loaded.treasuries()) {
            VillageTreasury previous = treasuries.putIfAbsent(
                    treasury.villageId(),
                    treasury
            );
            if (previous != null) {
                writable = false;
                plugin.getLogger().severe(
                        "[Trésor] Identifiant de village dupliqué : "
                                + treasury.villageId()
                                + ". La persistance passe en lecture seule."
                );
            }
        }

        if (settings.enabled()) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            maintenanceTask = Bukkit.getScheduler().runTaskTimer(
                    plugin,
                    this::runMaintenance,
                    1L,
                    settings.maintenancePeriodTicks()
            );
            autosaveTask = Bukkit.getScheduler().runTaskTimer(
                    plugin,
                    this::saveIfDirtyAsync,
                    settings.autosavePeriodTicks(),
                    settings.autosavePeriodTicks()
            );
        } else {
            plugin.getLogger().info(
                    "[Trésor] Système désactivé par configuration."
            );
        }

        if (!writable) {
            plugin.getLogger().severe(
                    "[Trésor] Persistance en lecture seule : les salles "
                            + "existantes restent protégées, mais aucun impôt "
                            + "n'est généré ni sauvegardé."
            );
        } else if (!treasuries.isEmpty()) {
            plugin.getLogger().info(
                    "[Trésor] " + treasuries.size()
                            + " salle(s) des coffres restaurée(s)."
            );
        }
    }

    public boolean isEnabled() {
        return settings.enabled();
    }

    /**
     * Vérifie avant le premier bloc posé qu'une nouvelle salle pourra être
     * publiée. Ce contrôle protège notamment les inventaires d'une salle déjà
     * persistée : la génération n'a ainsi jamais l'occasion de les écraser
     * avant de découvrir une collision d'identifiant.
     *
     * <p>La validation définitive reste effectuée par
     * {@link #registerVillage(int, UUID, TreasuryBuilder.Plan)}, après que les
     * soixante coffres, le pupitre et les portes ont réellement été posés.</p>
     */
    public boolean canPrepareVillage(int villageId) {
        ensurePrimaryThread();
        return settings.enabled()
                && !shuttingDown
                && writable
                && !treasuries.containsKey(villageId)
                && treasuries.size()
                < VillageTreasuryStore.MAX_TREASURIES;
    }

    /**
     * Enregistre la salle construite dans la même transaction que le village.
     */
    public boolean registerVillage(int villageId,
                                   UUID ownerId,
                                   TreasuryBuilder.Plan plan) {
        ensurePrimaryThread();
        if (!settings.enabled() || shuttingDown || !writable
                || ownerId == null || plan == null) {
            return false;
        }

        if (treasuries.containsKey(villageId)) {
            plugin.getLogger().severe(
                    "[Trésor] Enregistrement refusé pour le village "
                            + villageId
                            + " : cet identifiant possède déjà une salle."
            );
            return false;
        }
        if (treasuries.size() >= VillageTreasuryStore.MAX_TREASURIES) {
            plugin.getLogger().severe(
                    "[Trésor] Enregistrement refusé : limite de "
                            + VillageTreasuryStore.MAX_TREASURIES
                            + " salles persistantes atteinte."
            );
            return false;
        }

        World world = plan.roomAnchor().getWorld();
        if (world == null) {
            return false;
        }
        if (plan.containers().size()
                != VillageTreasury.REQUIRED_CONTAINER_COUNT) {
            plugin.getLogger().severe(
                    "[Trésor] Enregistrement refusé pour le village "
                            + villageId + " : "
                            + plan.containers().size()
                            + " coffres au lieu de "
                            + VillageTreasury.REQUIRED_CONTAINER_COUNT + "."
            );
            return false;
        }
        if (!validateBuiltPlan(world, plan)) {
            plugin.getLogger().severe(
                    "[Trésor] Enregistrement refusé pour le village "
                            + villageId
                            + " : la structure construite est incomplète."
            );
            return false;
        }

        long now = System.currentTimeMillis();
        VillageTreasury treasury = new VillageTreasury(
                villageId,
                ownerId,
                world.getUID(),
                world.getName(),
                plan.roomBounds(),
                plan.protectedZones(),
                plan.containers(),
                plan.ledger(),
                plan.doors(),
                now,
                safeAdd(now, randomDelayMillis()),
                0,
                0L
        );

        treasuries.put(villageId, treasury);
        fullnessByVillage.put(villageId, 0.0D);
        dirty = true;
        try {
            updateLedger(treasury, world);
        } catch (RuntimeException | LinkageError failure) {
            /*
             * La chambre forte est valide et déjà publiée. Un problème
             * ponctuel de pupitre ne doit pas laisser la transaction dans un
             * état ambigu : la maintenance réparera le registre au prochain
             * passage lorsque le chunk sera disponible.
             */
            plugin.getLogger().log(
                    Level.WARNING,
                    "[Trésor] Le registre du village " + villageId
                            + " n'a pas pu être initialisé immédiatement.",
                    failure
            );
        }
        requestUrgentSave();

        plugin.getLogger().info(
                "[Trésor] Salle des coffres du village "
                        + villageId + " enregistrée avec "
                        + treasury.containers().size() + " coffres."
        );
        return true;
    }

    /**
     * Retire l'état persistant avant que {@code /village undo} restaure les
     * blocs et que le gestionnaire de garnison supprime les sentinelles.
     */
    public boolean unregisterVillage(int villageId) {
        ensurePrimaryThread();
        boolean removed = removeTreasuryInternal(villageId);
        if (removed) {
            dirty = true;
            requestUrgentSave();
        }
        return removed;
    }

    private boolean removeTreasuryInternal(int villageId) {
        VillageTreasury removed = treasuries.remove(villageId);
        cancelDoorTask(villageId);

        BossBar bar = bossBars.remove(villageId);
        if (bar != null) {
            bar.removeAll();
            bar.setVisible(false);
        }
        visibleRoomByPlayer.entrySet().removeIf(entry ->
                entry.getValue() == villageId
        );
        fullnessByVillage.remove(villageId);
        lastFullnessRefreshAt.remove(villageId);
        return removed != null;
    }

    private boolean validateBuiltPlan(
            World world,
            TreasuryBuilder.Plan plan) {
        if (!world.getUID().equals(
                plan.roomAnchor().getWorld().getUID()
        )) {
            return false;
        }

        for (VillageTreasury.BlockPoint point : plan.containers()) {
            if (!world.isChunkLoaded(point.x() >> 4, point.z() >> 4)
                    || !(world.getBlockAt(
                    point.x(),
                    point.y(),
                    point.z()
            ).getState() instanceof Container)) {
                return false;
            }
        }
        if (!(world.getBlockAt(
                plan.ledger().x(),
                plan.ledger().y(),
                plan.ledger().z()
        ).getState() instanceof Lectern)) {
            return false;
        }
        for (VillageTreasury.BlockPoint point : plan.doors()) {
            if (world.getBlockAt(
                    point.x(),
                    point.y(),
                    point.z()
            ).getType() != Material.IRON_DOOR) {
                return false;
            }
        }
        return true;
    }

    private void runMaintenance() {
        if (shuttingDown || !settings.enabled()) {
            return;
        }
        ensurePrimaryThread();

        long now = System.currentTimeMillis();
        for (VillageTreasury treasury : treasuries.values()) {
            /*
             * Après un crash, l'état « ouvert » d'une porte peut avoir été
             * sauvegardé par le monde alors que sa tâche de fermeture a été
             * perdue. Toute porte sans minuterie active est donc reverrouillée.
             */
            secureIdleDoors(treasury);
            if (writable) {
                accrueTaxes(treasury, now);
                depositPendingTaxes(treasury);
            }
        }
        updateBossBars(now);
    }

    private void accrueTaxes(VillageTreasury treasury, long now) {
        long next = treasury.nextTaxAtMillis();
        if (next <= 0L) {
            treasury.setNextTaxAtMillis(
                    safeAdd(now, randomDelayMillis())
            );
            dirty = true;
            return;
        }

        int accrued = 0;
        while (now >= next
                && accrued < settings.catchUpLimitPerCycle()) {
            if (treasury.pendingTaxes()
                    < settings.maximumPendingTaxes()) {
                treasury.setPendingTaxes(
                        treasury.pendingTaxes() + 1
                );
            }
            accrued++;
            next = safeAdd(next, randomDelayMillis());
        }

        /*
         * Une très longue absence ne doit pas provoquer des milliers de tours
         * de boucle sur le thread serveur. Le plafond d'impôts en attente est
         * conservé, puis la prochaine échéance repart depuis maintenant.
         */
        if (now >= next) {
            next = safeAdd(now, randomDelayMillis());
        }

        if (accrued > 0) {
            treasury.setNextTaxAtMillis(next);
            dirty = true;
        }
    }

    private void depositPendingTaxes(VillageTreasury treasury) {
        int attempts = Math.min(
                settings.depositsPerCycle(),
                treasury.pendingTaxes()
        );
        if (attempts <= 0) {
            return;
        }

        World world = resolveWorld(treasury);
        if (world == null) {
            return;
        }

        int depositedUnits = 0;
        long depositedItems = 0L;
        for (int index = 0; index < attempts; index++) {
            ItemStack tax = createRandomTaxItem(random, settings);
            if (!depositWholeStack(treasury, world, tax)) {
                break;
            }
            depositedUnits++;
            depositedItems += tax.getAmount();
        }

        if (depositedUnits <= 0) {
            return;
        }
        treasury.setPendingTaxes(
                treasury.pendingTaxes() - depositedUnits
        );
        treasury.addDepositedItems(depositedItems);
        dirty = true;

        double fullness = calculateFullness(treasury, world);
        fullnessByVillage.put(treasury.villageId(), fullness);
        lastFullnessRefreshAt.put(
                treasury.villageId(),
                System.currentTimeMillis()
        );
        BossBar bar = bossBars.get(treasury.villageId());
        if (bar != null) {
            bar.setProgress(fullness);
        }
        updateLedger(treasury, world);
    }

    private boolean depositWholeStack(
            VillageTreasury treasury,
            World world,
            ItemStack item) {
        List<VillageTreasury.BlockPoint> points =
                treasury.containers();
        if (points.isEmpty()) {
            return false;
        }

        int start = random.nextInt(points.size());
        for (int offset = 0; offset < points.size(); offset++) {
            VillageTreasury.BlockPoint point =
                    points.get((start + offset) % points.size());
            if (!world.isChunkLoaded(point.x() >> 4, point.z() >> 4)) {
                continue;
            }

            Block block = world.getBlockAt(
                    point.x(),
                    point.y(),
                    point.z()
            );
            if (!(block.getState() instanceof Container container)) {
                continue;
            }

            Inventory inventory = container.getInventory();
            if (insertWholeStack(inventory, item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Prépare une copie complète de l'inventaire avant de la publier. Ainsi,
     * l'impôt est soit inséré en entier, soit pas du tout : aucun reliquat ne
     * peut être perdu et aucune pile déjà présente n'est retirée par erreur.
     */
    static boolean insertWholeStack(Inventory inventory, ItemStack item) {
        if (inventory == null || item == null
                || item.getType().isAir() || item.getAmount() <= 0) {
            return false;
        }

        ItemStack[] current = inventory.getStorageContents();
        ItemStack[] updated = new ItemStack[current.length];
        for (int index = 0; index < current.length; index++) {
            updated[index] = current[index] != null
                    ? current[index].clone()
                    : null;
        }

        int remaining = item.getAmount();
        int maximum = item.getMaxStackSize();

        /*
         * On complète d'abord les piles similaires pour limiter la
         * fragmentation progressive des soixante coffres.
         */
        for (int index = 0;
             index < updated.length && remaining > 0;
             index++) {
            ItemStack existing = updated[index];
            if (existing == null || existing.getType().isAir()
                    || !existing.isSimilar(item)) {
                continue;
            }
            int slotMaximum = Math.min(
                    maximum,
                    existing.getMaxStackSize()
            );
            int moved = Math.min(
                    remaining,
                    Math.max(0, slotMaximum - existing.getAmount())
            );
            if (moved > 0) {
                existing.setAmount(existing.getAmount() + moved);
                remaining -= moved;
            }
        }

        for (int index = 0;
             index < updated.length && remaining > 0;
             index++) {
            ItemStack existing = updated[index];
            if (existing != null && !existing.getType().isAir()) {
                continue;
            }
            int moved = Math.min(remaining, maximum);
            ItemStack placed = item.clone();
            placed.setAmount(moved);
            updated[index] = placed;
            remaining -= moved;
        }

        if (remaining > 0) {
            return false;
        }

        inventory.setStorageContents(updated);
        return true;
    }

    static int capacityFor(Inventory inventory, ItemStack item) {
        if (inventory == null || item == null
                || item.getType().isAir()) {
            return 0;
        }

        int capacity = 0;
        int maximum = item.getMaxStackSize();
        for (ItemStack current : inventory.getStorageContents()) {
            if (current == null || current.getType().isAir()) {
                capacity += maximum;
            } else if (current.isSimilar(item)) {
                capacity += Math.max(
                        0,
                        Math.min(
                                maximum,
                                current.getMaxStackSize()
                        ) - current.getAmount()
                );
            }
            if (capacity >= item.getAmount()) {
                return capacity;
            }
        }
        return capacity;
    }

    /**
     * Fabrique une ressource aléatoire parmi tous les matériaux qui peuvent
     * réellement exister sous forme d'ItemStack dans cette version de Paper.
     */
    static ItemStack createRandomTaxItem(
            Random random,
            VillageTreasurySettings settings) {
        Objects.requireNonNull(random, "random");
        Objects.requireNonNull(settings, "settings");

        Material material = ELIGIBLE_MATERIALS.get(
                random.nextInt(ELIGIBLE_MATERIALS.size())
        );
        ItemStack item = new ItemStack(material);
        int maximum = Math.min(
                item.getMaxStackSize(),
                settings.maximumItemAmount()
        );
        int minimum = Math.min(
                maximum,
                settings.minimumItemAmount()
        );
        int amount = minimum;
        if (maximum > minimum) {
            amount += random.nextInt(maximum - minimum + 1);
        }
        item.setAmount(Math.max(1, amount));

        ItemMeta meta = item.getItemMeta();
        if (meta instanceof PotionMeta potionMeta
                && !ELIGIBLE_POTIONS.isEmpty()) {
            potionMeta.setBasePotionType(
                    ELIGIBLE_POTIONS.get(
                            random.nextInt(ELIGIBLE_POTIONS.size())
                    )
            );
            item.setItemMeta(potionMeta);
        }
        return item;
    }

    static List<Material> eligibleMaterials() {
        return ELIGIBLE_MATERIALS;
    }

    private static List<Material> buildEligibleMaterials() {
        List<Material> materials = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material != null
                    && !material.isLegacy()
                    && !material.isAir()
                    && material.isItem()) {
                materials.add(material);
            }
        }
        if (materials.isEmpty()) {
            throw new IllegalStateException(
                    "Aucun matériau éligible à l'impôt royal."
            );
        }
        return Collections.unmodifiableList(materials);
    }

    private static List<PotionType> buildEligiblePotions() {
        List<PotionType> potions = new ArrayList<>();
        for (PotionType type : PotionType.values()) {
            if (type != null
                    && !"UNCRAFTABLE".equals(type.name())) {
                potions.add(type);
            }
        }
        return Collections.unmodifiableList(potions);
    }

    private long randomDelayMillis() {
        long minimum = settings.minimumIntervalMillis();
        long maximum = settings.maximumIntervalMillis();
        if (maximum <= minimum) {
            return minimum;
        }
        long range = maximum - minimum + 1L;
        long offset = (long) Math.floor(random.nextDouble() * range);
        return minimum + Math.min(range - 1L, offset);
    }

    private long safeAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }

    private World resolveWorld(VillageTreasury treasury) {
        World world = Bukkit.getWorld(treasury.worldId());
        if (world != null) {
            return world;
        }
        World byName = Bukkit.getWorld(treasury.worldName());
        return byName != null
                && treasury.worldId().equals(byName.getUID())
                ? byName
                : null;
    }

    private void updateBossBars(long now) {
        Set<UUID> online = new java.util.HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            updateBossBarForPlayer(player, now);
        }

        for (UUID playerId
                : new ArrayList<>(visibleRoomByPlayer.keySet())) {
            if (online.contains(playerId)) {
                continue;
            }
            Integer villageId = visibleRoomByPlayer.remove(playerId);
            Player player = Bukkit.getPlayer(playerId);
            BossBar bar = villageId != null
                    ? bossBars.get(villageId)
                    : null;
            if (bar != null && player != null) {
                bar.removePlayer(player);
            }
        }
    }

    /**
     * Réconcilie immédiatement la barre d'un joueur. La maintenance appelle
     * aussi cette méthode afin de couvrir une téléportation réalisée par un
     * autre plugin sans événement exploitable ou un rechargement de joueur.
     */
    private void updateBossBarForPlayer(Player player, long now) {
        if (player == null) {
            return;
        }
        VillageTreasury inside = findRoom(player);
        UUID playerId = player.getUniqueId();
        Integer previous = visibleRoomByPlayer.get(playerId);
        Integer current = inside != null
                ? inside.villageId()
                : null;

        if (!Objects.equals(previous, current)) {
            if (previous != null) {
                BossBar oldBar = bossBars.get(previous);
                if (oldBar != null) {
                    oldBar.removePlayer(player);
                }
            }
            if (current != null) {
                BossBar newBar = bossBar(inside);
                newBar.addPlayer(player);
                newBar.setVisible(true);
                visibleRoomByPlayer.put(playerId, current);
            } else {
                visibleRoomByPlayer.remove(playerId);
            }
        }

        if (inside != null) {
            refreshFullnessIfNeeded(inside, now);
        }
    }

    private VillageTreasury findRoom(Player player) {
        if (player == null) {
            return null;
        }
        for (VillageTreasury treasury : treasuries.values()) {
            if (treasury.isInsideRoom(player.getLocation())) {
                return treasury;
            }
        }
        return null;
    }

    private BossBar bossBar(VillageTreasury treasury) {
        return bossBars.computeIfAbsent(
                treasury.villageId(),
                ignored -> {
                    BossBar bar = Bukkit.createBossBar(
                            BOSS_BAR_TITLE,
                            BarColor.YELLOW,
                            BarStyle.SOLID
                    );
                    bar.setProgress(
                            fullnessByVillage.getOrDefault(
                                    treasury.villageId(),
                                    0.0D
                            )
                    );
                    bar.setVisible(true);
                    return bar;
                }
        );
    }

    private void refreshFullnessIfNeeded(
            VillageTreasury treasury,
            long now) {
        long previous = lastFullnessRefreshAt.getOrDefault(
                treasury.villageId(),
                0L
        );
        if (now - previous < FULLNESS_REFRESH_MILLIS) {
            return;
        }
        World world = resolveWorld(treasury);
        if (world == null) {
            return;
        }

        double fullness = calculateFullness(treasury, world);
        fullnessByVillage.put(treasury.villageId(), fullness);
        lastFullnessRefreshAt.put(treasury.villageId(), now);
        BossBar bar = bossBars.get(treasury.villageId());
        if (bar != null) {
            bar.setProgress(fullness);
        }
    }

    private double calculateFullness(
            VillageTreasury treasury,
            World world) {
        long occupied = 0L;
        long slots = 0L;
        for (VillageTreasury.BlockPoint point
                : treasury.containers()) {
            if (!world.isChunkLoaded(point.x() >> 4, point.z() >> 4)) {
                continue;
            }
            BlockState state = world.getBlockAt(
                    point.x(),
                    point.y(),
                    point.z()
            ).getState();
            if (!(state instanceof Container container)) {
                continue;
            }
            ItemStack[] contents =
                    container.getInventory().getStorageContents();
            slots += contents.length;
            for (ItemStack item : contents) {
                if (item != null && !item.getType().isAir()) {
                    occupied++;
                }
            }
        }
        if (slots <= 0L) {
            return fullnessByVillage.getOrDefault(
                    treasury.villageId(),
                    0.0D
            );
        }
        return Math.max(
                0.0D,
                Math.min(1.0D, occupied / (double) slots)
        );
    }

    private void updateLedger(
            VillageTreasury treasury,
            World world) {
        VillageTreasury.BlockPoint point = treasury.ledger();
        if (!world.isChunkLoaded(point.x() >> 4, point.z() >> 4)) {
            return;
        }
        BlockState state = world.getBlockAt(
                point.x(),
                point.y(),
                point.z()
        ).getState();
        if (!(state instanceof Lectern lectern)) {
            return;
        }

        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        if (!(book.getItemMeta() instanceof BookMeta meta)) {
            return;
        }
        String ownerName = Bukkit.getOfflinePlayer(
                treasury.ownerId()
        ).getName();
        if (ownerName == null || ownerName.isBlank()) {
            ownerName = treasury.ownerId().toString()
                    .substring(0, 8);
        }

        long now = System.currentTimeMillis();
        long seconds = Math.max(
                0L,
                (treasury.nextTaxAtMillis() - now + 999L) / 1_000L
        );
        meta.setTitle("Grand registre des impôts");
        meta.setAuthor("Couronne de MineGus");
        meta.setGeneration(BookMeta.Generation.ORIGINAL);
        meta.setPages(
                ChatColor.GOLD + "" + ChatColor.BOLD
                        + "SALLE DES COFFRES\n\n"
                        + ChatColor.BLACK
                        + "Roi : " + ownerName + "\n"
                        + "Village : " + treasury.villageId() + "\n\n"
                        + "Ce registre appartient à la ville fondée avec "
                        + "/village.",
                ChatColor.DARK_RED + "" + ChatColor.BOLD
                        + "IMPÔT ROYAL\n\n"
                        + ChatColor.BLACK
                        + "Objets versés : "
                        + treasury.depositedItems() + "\n"
                        + "Collectes en attente : "
                        + treasury.pendingTaxes() + "\n"
                        + "Prochain impôt : environ "
                        + seconds + " s.",
                ChatColor.DARK_GREEN + "" + ChatColor.BOLD
                        + "RÉSERVES\n\n"
                        + ChatColor.BLACK
                        + treasury.containers().size()
                        + " coffres sécurisés reçoivent des ressources "
                        + "entièrement aléatoires, y compris potions et "
                        + "spawners.",
                ChatColor.DARK_BLUE + "" + ChatColor.BOLD
                        + "SENTINELLES\n\n"
                        + ChatColor.BLACK
                        + "Quatre gardes restent à leur poste. Ils "
                        + "combattent les monstres proches et ripostent "
                        + "contre tout joueur qui les attaque."
        );
        book.setItemMeta(meta);
        lectern.getSnapshotInventory().setItem(0, book);
        lectern.update(true, false);
    }

    private VillageTreasury treasuryProtecting(Block block) {
        if (block == null) {
            return null;
        }
        for (VillageTreasury treasury : treasuries.values()) {
            if (treasury.protects(block)) {
                return treasury;
            }
        }
        return null;
    }

    private VillageTreasury treasuryContainer(Block block) {
        if (block == null) {
            return null;
        }
        for (VillageTreasury treasury : treasuries.values()) {
            if (treasury.isContainer(block)) {
                return treasury;
            }
        }
        return null;
    }

    private VillageTreasury treasuryLedger(Block block) {
        if (block == null) {
            return null;
        }
        for (VillageTreasury treasury : treasuries.values()) {
            if (treasury.isLedger(block)) {
                return treasury;
            }
        }
        return null;
    }

    private VillageTreasury treasuryDoor(Block block) {
        if (block == null) {
            return null;
        }
        for (VillageTreasury treasury : treasuries.values()) {
            if (treasury.isDoor(block)) {
                return treasury;
            }
        }
        return null;
    }

    private VillageTreasury treasuryInventory(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof BlockState state) {
            VillageTreasury container =
                    treasuryContainer(state.getBlock());
            return container != null
                    ? container
                    : treasuryLedger(state.getBlock());
        }
        if (holder instanceof DoubleChest doubleChest) {
            VillageTreasury left = treasuryFromHolder(
                    doubleChest.getLeftSide()
            );
            return left != null
                    ? left
                    : treasuryFromHolder(doubleChest.getRightSide());
        }
        return null;
    }

    private VillageTreasury treasuryFromHolder(
            InventoryHolder holder) {
        return holder instanceof BlockState state
                ? treasuryContainer(state.getBlock())
                : null;
    }

    private boolean canUseTreasury(
            Player player,
            VillageTreasury treasury) {
        return player != null
                && treasury != null
                && (!settings.ownerOnlyContainers()
                || treasury.isOwner(player.getUniqueId())
                || player.hasPermission(ADMIN_PERMISSION));
    }

    private boolean canModifyStructure(Player player) {
        return player != null
                && player.hasPermission(ADMIN_PERMISSION);
    }

    private void deny(Player player, String message) {
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long previous = lastDeniedMessageAt.getOrDefault(
                player.getUniqueId(),
                0L
        );
        if (now - previous < MESSAGE_COOLDOWN_MILLIS) {
            return;
        }
        lastDeniedMessageAt.put(player.getUniqueId(), now);
        player.sendMessage(ChatColor.RED + message);
    }

    private void secureIdleDoors(VillageTreasury treasury) {
        if (treasury == null
                || doorCloseTasks.containsKey(treasury.villageId())) {
            return;
        }
        World world = resolveWorld(treasury);
        if (world != null) {
            setDoorsOpen(treasury, world, false);
        }
    }

    private void openDoors(
            VillageTreasury treasury,
            Player player) {
        World world = resolveWorld(treasury);
        if (world == null) {
            deny(player, "Le monde de la salle est indisponible.");
            return;
        }

        setDoorsOpen(treasury, world, true);
        cancelDoorTask(treasury.villageId());
        BukkitTask task = Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> {
                    doorCloseTasks.remove(treasury.villageId());
                    World currentWorld = resolveWorld(treasury);
                    if (currentWorld != null) {
                        setDoorsOpen(treasury, currentWorld, false);
                    }
                },
                settings.doorOpenSeconds() * 20L
        );
        doorCloseTasks.put(treasury.villageId(), task);
    }

    private void setDoorsOpen(
            VillageTreasury treasury,
            World world,
            boolean open) {
        for (VillageTreasury.BlockPoint point : treasury.doors()) {
            if (!world.isChunkLoaded(point.x() >> 4, point.z() >> 4)) {
                continue;
            }
            setOpenable(
                    world.getBlockAt(point.x(), point.y(), point.z()),
                    open
            );
            setOpenable(
                    world.getBlockAt(
                            point.x(),
                            point.y() + 1,
                            point.z()
                    ),
                    open
            );
        }
    }

    private void setOpenable(Block block, boolean open) {
        if (!(block.getBlockData() instanceof Openable openable)
                || openable.isOpen() == open) {
            return;
        }
        openable.setOpen(open);
        block.setBlockData(openable, false);
    }

    private void cancelDoorTask(int villageId) {
        BukkitTask task = doorCloseTasks.remove(villageId);
        if (task != null) {
            try {
                task.cancel();
            } catch (RuntimeException ignored) {
                // La fermeture n'empêche jamais le reste du nettoyage.
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onInventoryOpen(InventoryOpenEvent event) {
        VillageTreasury treasury =
                treasuryInventory(event.getInventory());
        if (treasury == null
                || !(event.getPlayer() instanceof Player player)
                || canUseTreasury(player, treasury)) {
            return;
        }
        event.setCancelled(true);
        deny(
                player,
                "Seul le roi de cette ville peut ouvrir ces coffres."
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        VillageTreasury treasury =
                treasuryInventory(event.getView().getTopInventory());
        if (treasury == null
                || !(event.getWhoClicked() instanceof Player player)
                || canUseTreasury(player, treasury)) {
            return;
        }
        event.setCancelled(true);
        deny(
                player,
                "Le trésor royal ne t'appartient pas."
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        VillageTreasury treasury =
                treasuryInventory(event.getView().getTopInventory());
        if (treasury == null
                || !(event.getWhoClicked() instanceof Player player)
                || canUseTreasury(player, treasury)) {
            return;
        }
        event.setCancelled(true);
        deny(
                player,
                "Le trésor royal ne t'appartient pas."
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (treasuryInventory(event.getSource()) != null
                || treasuryInventory(event.getDestination()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onTakeLedger(PlayerTakeLecternBookEvent event) {
        VillageTreasury treasury =
                treasuryLedger(event.getLectern().getBlock());
        if (treasury == null) {
            return;
        }
        event.setCancelled(true);
        deny(
                event.getPlayer(),
                "Le grand registre doit rester dans la salle."
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        VillageTreasury door = treasuryDoor(block);
        if (door != null) {
            event.setCancelled(true);
            /*
             * Un clic gauche ne doit jamais actionner la serrure : il est géré
             * séparément par la protection de casse. Cela évite aussi les deux
             * ouvertures involontaires provoquées par certaines interactions.
             */
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
                return;
            }
            if (!canUseTreasury(event.getPlayer(), door)) {
                deny(
                        event.getPlayer(),
                        "Seul le roi peut ouvrir la chambre forte."
                );
                return;
            }
            openDoors(door, event.getPlayer());
            return;
        }

        VillageTreasury container = treasuryContainer(block);
        if (container != null
                && !canUseTreasury(event.getPlayer(), container)) {
            event.setCancelled(true);
            deny(
                    event.getPlayer(),
                    "Seul le roi de cette ville peut ouvrir ces coffres."
            );
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        if (treasuryProtecting(event.getBlock()) == null
                || canModifyStructure(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        deny(
                event.getPlayer(),
                "La chambre forte est protégée."
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        if (treasuryProtecting(event.getBlockPlaced()) == null
                || canModifyStructure(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        deny(
                event.getPlayer(),
                "Impossible de modifier la chambre forte."
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        if (canModifyStructure(event.getPlayer())) {
            return;
        }
        boolean protectedBlock = event.getReplacedBlockStates()
                .stream()
                .map(BlockState::getBlock)
                .anyMatch(block ->
                        treasuryProtecting(block) != null
                );
        if (protectedBlock) {
            event.setCancelled(true);
            deny(
                    event.getPlayer(),
                    "Impossible de modifier la chambre forte."
            );
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block target = event.getBlockClicked()
                .getRelative(event.getBlockFace());
        if (treasuryProtecting(target) != null
                && !canModifyStructure(event.getPlayer())) {
            event.setCancelled(true);
            deny(
                    event.getPlayer(),
                    "Les fluides sont interdits dans la chambre forte."
            );
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (treasuryProtecting(event.getBlockClicked()) != null
                && !canModifyStructure(event.getPlayer())) {
            event.setCancelled(true);
            deny(
                    event.getPlayer(),
                    "La chambre forte est protégée."
            );
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (treasuryProtecting(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onEntityExplosion(EntityExplodeEvent event) {
        if (treasuryProtecting(event.getLocation().getBlock()) != null) {
            event.setCancelled(true);
            return;
        }
        event.blockList().removeIf(block ->
                treasuryProtecting(block) != null
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onBlockExplosion(BlockExplodeEvent event) {
        if (treasuryProtecting(event.getBlock()) != null) {
            event.setCancelled(true);
            return;
        }
        event.blockList().removeIf(block ->
                treasuryProtecting(block) != null
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        boolean protectedMove =
                treasuryProtecting(event.getBlock()) != null
                        || event.getBlocks().stream()
                        .anyMatch(block ->
                                treasuryProtecting(block) != null
                                        || treasuryProtecting(
                                        block.getRelative(
                                                event.getDirection()
                                        )
                                ) != null
                        );
        if (protectedMove) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        boolean protectedMove =
                treasuryProtecting(event.getBlock()) != null
                        || event.getBlocks().stream()
                        .anyMatch(block ->
                                treasuryProtecting(block) != null
                                        || treasuryProtecting(
                                        block.getRelative(
                                                event.getDirection()
                                        )
                                ) != null
                        );
        if (protectedMove) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onFluidFlow(BlockFromToEvent event) {
        if (treasuryProtecting(event.getBlock()) != null
                || treasuryProtecting(event.getToBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onBurn(BlockBurnEvent event) {
        if (treasuryProtecting(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onFade(BlockFadeEvent event) {
        if (treasuryProtecting(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onGrow(BlockGrowEvent event) {
        if (treasuryProtecting(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onSpread(BlockSpreadEvent event) {
        if (treasuryProtecting(event.getBlock()) != null
                || treasuryProtecting(event.getSource()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onForm(BlockFormEvent event) {
        if (treasuryProtecting(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onIgnite(BlockIgniteEvent event) {
        if (treasuryProtecting(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onFertilize(BlockFertilizeEvent event) {
        boolean protectedChange = event.getBlocks().stream()
                .map(BlockState::getBlock)
                .anyMatch(block -> treasuryProtecting(block) != null);
        if (protectedChange) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onStructureGrow(StructureGrowEvent event) {
        boolean protectedChange = event.getBlocks().stream()
                .map(BlockState::getBlock)
                .anyMatch(block -> treasuryProtecting(block) != null);
        if (protectedChange) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onSpongeAbsorb(SpongeAbsorbEvent event) {
        boolean protectedChange = event.getBlocks().stream()
                .map(BlockState::getBlock)
                .anyMatch(block -> treasuryProtecting(block) != null);
        if (protectedChange) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST,
            ignoreCancelled = false)
    public void onDoorRedstone(BlockRedstoneEvent event) {
        if (treasuryDoor(event.getBlock()) != null) {
            event.setNewCurrent(event.getOldCurrent());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        boolean sameBlock = event.getFrom().getWorld()
                == event.getTo().getWorld()
                && event.getFrom().getBlockX()
                == event.getTo().getBlockX()
                && event.getFrom().getBlockY()
                == event.getTo().getBlockY()
                && event.getFrom().getBlockZ()
                == event.getTo().getBlockZ();
        if (sameBlock) {
            return;
        }

        /*
         * Le calcul ne s'exécute qu'au changement de bloc, pas à chaque
         * variation de caméra, ce qui garde l'événement de mouvement léger.
         */
        updateBossBarForPlayer(
                event.getPlayer(),
                System.currentTimeMillis()
        );
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Integer villageId = visibleRoomByPlayer.remove(playerId);
        if (villageId != null) {
            BossBar bar = bossBars.get(villageId);
            if (bar != null) {
                bar.removePlayer(event.getPlayer());
            }
        }
        lastDeniedMessageAt.remove(playerId);
    }

    private void saveIfDirtyAsync() {
        if (!dirty || !writable || shuttingDown) {
            return;
        }
        ensurePrimaryThread();

        List<VillageTreasury> snapshot = snapshots();
        dirty = false;
        ioExecutor.execute(() -> {
            boolean saved = store.save(snapshot);
            if (!saved && !shuttingDown) {
                try {
                    Bukkit.getScheduler().runTask(
                            plugin,
                            () -> dirty = true
                    );
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(
                            Level.WARNING,
                            "[Trésor] Impossible de reprogrammer la "
                                    + "sauvegarde après un échec.",
                            exception
                    );
                }
            }
        });
    }

    private List<VillageTreasury> snapshots() {
        List<VillageTreasury> result = new ArrayList<>();
        for (VillageTreasury treasury : treasuries.values()) {
            result.add(treasury.snapshot());
        }
        return List.copyOf(result);
    }

    private void requestUrgentSave() {
        if (!writable || shuttingDown || urgentSaveTask != null) {
            return;
        }
        try {
            urgentSaveTask = Bukkit.getScheduler().runTask(
                    plugin,
                    () -> {
                        urgentSaveTask = null;
                        saveIfDirtyAsync();
                    }
            );
        } catch (RuntimeException failure) {
            /*
             * L'état reste marqué dirty : l'autosauvegarde périodique ou
             * l'arrêt synchrone retentera l'écriture. Surtout, l'exception ne
             * remonte pas après publication d'une salle valide.
             */
            urgentSaveTask = null;
            plugin.getLogger().log(
                    Level.WARNING,
                    "[Trésor] La sauvegarde urgente n'a pas pu être "
                            + "planifiée ; elle sera retentée.",
                    failure
            );
        }
    }

    public void shutdown() {
        if (shuttingDown) {
            return;
        }
        ensurePrimaryThread();
        shuttingDown = true;

        cancelTask(maintenanceTask);
        cancelTask(autosaveTask);
        cancelTask(urgentSaveTask);
        maintenanceTask = null;
        autosaveTask = null;
        urgentSaveTask = null;

        /*
         * Les portes sont fermées avant d'annuler leurs minuteries afin que le
         * monde ne conserve jamais une chambre forte ouverte au redémarrage.
         */
        for (VillageTreasury treasury : treasuries.values()) {
            World world = resolveWorld(treasury);
            if (world != null) {
                setDoorsOpen(treasury, world, false);
            }
        }
        for (Integer villageId
                : new ArrayList<>(doorCloseTasks.keySet())) {
            cancelDoorTask(villageId);
        }
        for (BossBar bar : bossBars.values()) {
            bar.removeAll();
            bar.setVisible(false);
        }
        bossBars.clear();
        visibleRoomByPlayer.clear();
        HandlerList.unregisterAll(this);

        if (writable && (dirty || !treasuries.isEmpty())) {
            List<VillageTreasury> finalSnapshot = snapshots();
            Future<Boolean> save = ioExecutor.submit(
                    () -> store.save(finalSnapshot)
            );
            try {
                if (!save.get(10L, TimeUnit.SECONDS)) {
                    plugin.getLogger().severe(
                            "[Trésor] La sauvegarde finale a échoué."
                    );
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                plugin.getLogger().warning(
                        "[Trésor] Sauvegarde finale interrompue."
                );
            } catch (ExecutionException
                     | java.util.concurrent.TimeoutException exception) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "[Trésor] Impossible de terminer la sauvegarde finale.",
                        exception
                );
                save.cancel(true);
            }
        }

        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(2L, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
    }

    private void cancelTask(BukkitTask task) {
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (RuntimeException ignored) {
            // L'arrêt du plugin continue même si le scheduler est déjà fermé.
        }
    }

    private void ensurePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "VillageTreasuryManager doit être utilisé "
                            + "sur le thread serveur."
            );
        }
    }
}
