package org.example.village;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Gestion des PNJ / golems d'un village: spawn initial, nettoyage, quota global.
 */
public final class VillageEntityManager {

    public static final String TAG = "MINEGUS_VILLAGE_ID";
    private static final int DEFAULT_CAP = 56;
    private static final int INFINITE_TRADES = 999999;
    private static final int MERCHANT_GUARD_RADIUS = 40;
    private static final byte SPAWNER_KIND_VILLAGER = 1;
    private static final byte SPAWNER_KIND_GOLEM = 2;
    private static final int GOLEM_CAP = 4;

    private static final Map<Integer, Location> VILLAGE_CENTERS =
            new HashMap<>();
    private static final Map<Integer, Map<String, Location>> VILLAGE_ANCHORS =
            new HashMap<>();
    private static final Map<UUID, MerchantInfo> MERCHANTS = new HashMap<>();
    private static final Map<Integer, BukkitTask> CAP_TASKS = new HashMap<>();
    private static final Map<Integer, BukkitTask> EXPIRY_TASKS =
            new HashMap<>();

    /*
     * Une instance de plugin ne doit enregistrer le listener qu'une seule fois.
     * Le WeakHashMap évite de retenir une ancienne instance après un /reload.
     */
    private static final Set<Plugin> LISTENER_PLUGINS =
            Collections.newSetFromMap(new WeakHashMap<>());

    /*
     * Les clés PDC sont immuables et réutilisées pour toutes les opérations.
     * Le cache faible ne retient pas une ancienne instance du plugin après un
     * rechargement du serveur.
     */
    private static final Map<Plugin, PopulationKeys> PERSISTENT_KEYS =
            new WeakHashMap<>();

    private VillageEntityManager() {}

    /**
     * Enregistre la garde des spawners du village. Cette méthode est idempotente
     * et peut donc être appelée à la fois au démarrage du plugin et lors de la
     * configuration d'un spawner.
     */
    public static void initializePopulationListener(Plugin plugin) {
        if (plugin == null) {
            return;
        }

        synchronized (LISTENER_PLUGINS) {
            if (!LISTENER_PLUGINS.add(plugin)) {
                return;
            }
            plugin.getServer().getPluginManager().registerEvents(
                    new VillageSpawnerListener(plugin),
                    plugin
            );
        }
    }

    /**
     * Configure et marque un spawner appartenant à une ville MineGus.
     *
     * <p>Le marqueur persistant permet au listener de retrouver le village
     * après un redémarrage. Les délais sont volontairement longs et la
     * génération est limitée à une entité par cycle.</p>
     */
    public static void configureVillageSpawner(
            Plugin plugin,
            CreatureSpawner spawner,
            EntityType entityType,
            int villageId,
            VillagePopulationSettings settings) {
        if (plugin == null
                || spawner == null
                || settings == null
                || (entityType != EntityType.VILLAGER
                && entityType != EntityType.IRON_GOLEM)) {
            return;
        }

        initializePopulationListener(plugin);

        byte kind = entityType == EntityType.VILLAGER
                ? SPAWNER_KIND_VILLAGER
                : SPAWNER_KIND_GOLEM;
        int cap = kind == SPAWNER_KIND_VILLAGER
                ? settings.populationCap()
                : GOLEM_CAP;

        PersistentDataContainer data = spawner.getPersistentDataContainer();
        data.set(spawnerKindKey(plugin), PersistentDataType.BYTE, kind);
        data.set(villageIdKey(plugin), PersistentDataType.INTEGER, villageId);
        data.set(spawnerCapKey(plugin), PersistentDataType.INTEGER, cap);

        int delayMultiplier = kind == SPAWNER_KIND_VILLAGER ? 1 : 4;
        int minimumDelay = Math.min(
                72_000,
                settings.minDelayTicks() * delayMultiplier
        );
        int maximumDelay = Math.min(
                72_000,
                settings.maxDelayTicks() * delayMultiplier
        );
        maximumDelay = Math.max(minimumDelay, maximumDelay);

        /*
         * On ouvre d'abord la borne maximale : l'API refuse de descendre le
         * maximum sous le minimum actuellement enregistré dans le bloc. Le
         * couple final est ensuite appliqué dans un ordre toujours valide.
         */
        spawner.setMaxSpawnDelay(72_000);
        spawner.setMinSpawnDelay(minimumDelay);
        spawner.setMaxSpawnDelay(maximumDelay);
        spawner.setDelay(minimumDelay);
        spawner.setSpawnCount(1);
        spawner.setMaxNearbyEntities(
                kind == SPAWNER_KIND_VILLAGER
                        ? settings.maxNearbyEntities()
                        : GOLEM_CAP
        );
        spawner.setRequiredPlayerRange(settings.requiredPlayerRange());
        spawner.setSpawnRange(
                kind == SPAWNER_KIND_VILLAGER
                        ? settings.spawnRange()
                        : Math.min(2, settings.spawnRange())
        );
        spawner.setSpawnedType(entityType);
        spawner.update(true, false);
    }

    private static final class VillageSpawnerListener implements Listener {
        private final Plugin plugin;

        private VillageSpawnerListener(Plugin plugin) {
            this.plugin = plugin;
        }

        @EventHandler(ignoreCancelled = true)
        public void onSpawnerSpawn(SpawnerSpawnEvent event) {
            CreatureSpawner spawner = event.getSpawner();
            if (spawner == null) {
                return;
            }
            PersistentDataContainer data =
                    spawner.getPersistentDataContainer();

            Byte kind = data.get(
                    spawnerKindKey(plugin),
                    PersistentDataType.BYTE
            );
            Integer villageId = data.get(
                    villageIdKey(plugin),
                    PersistentDataType.INTEGER
            );
            Integer configuredCap = data.get(
                    spawnerCapKey(plugin),
                    PersistentDataType.INTEGER
            );
            if (kind == null || villageId == null) {
                return;
            }

            Entity entity = event.getEntity();
            boolean villagerSpawn = kind == SPAWNER_KIND_VILLAGER
                    && entity instanceof Villager;
            boolean golemSpawn = kind == SPAWNER_KIND_GOLEM
                    && entity instanceof IronGolem;
            if (!villagerSpawn && !golemSpawn) {
                /*
                 * Un joueur a pu modifier le type du spawner sans retirer ses
                 * données persistantes. On bloque alors cette génération plutôt
                 * que de laisser contourner le quota.
                 */
                event.setCancelled(true);
                return;
            }

            int cap = configuredCap != null
                    ? Math.max(1, Math.min(120, configuredCap))
                    : (villagerSpawn ? DEFAULT_CAP : GOLEM_CAP);
            int current = countVillageEntities(
                    plugin,
                    entity.getWorld(),
                    villageId,
                    villagerSpawn ? Villager.class : IronGolem.class
            );
            if (current >= cap) {
                event.setCancelled(true);
                return;
            }

            tagEntity(entity, plugin, villageId);
            if (entity instanceof Villager villager) {
                List<Villager.Profession> professions =
                        genericProfessions();
                int index = Math.floorMod(
                        villager.getUniqueId().hashCode(),
                        professions.size()
                );
                villager.setProfession(professions.get(index));
                villager.setAdult();
                villager.setRemoveWhenFarAway(false);
            } else if (entity instanceof IronGolem golem) {
                golem.setPlayerCreated(true);
                golem.setRemoveWhenFarAway(false);
            }
        }
    }

    private static int countVillageEntities(Plugin plugin,
                                            World world,
                                            int villageId,
                                            Class<? extends Entity> type) {
        int count = 0;
        for (Entity entity : world.getEntities()) {
            if (type.isInstance(entity)
                    && isVillageEntity(entity, plugin, villageId)) {
                count++;
            }
        }
        return count;
    }

    private static PopulationKeys persistentKeys(Plugin plugin) {
        synchronized (PERSISTENT_KEYS) {
            return PERSISTENT_KEYS.computeIfAbsent(
                    plugin,
                    owner -> new PopulationKeys(
                            new NamespacedKey(owner, "village_id"),
                            new NamespacedKey(
                                    owner,
                                    "village_spawner_kind"
                            ),
                            new NamespacedKey(
                                    owner,
                                    "village_spawner_cap"
                            )
                    )
            );
        }
    }

    private static NamespacedKey villageIdKey(Plugin plugin) {
        return persistentKeys(plugin).villageId();
    }

    private static NamespacedKey spawnerKindKey(Plugin plugin) {
        return persistentKeys(plugin).spawnerKind();
    }

    private static NamespacedKey spawnerCapKey(Plugin plugin) {
        return persistentKeys(plugin).spawnerCap();
    }

    private record PopulationKeys(
            NamespacedKey villageId,
            NamespacedKey spawnerKind,
            NamespacedKey spawnerCap) {
    }

    private enum MerchantType {
        MINER,
        LUMBERJACK,
        FARMER,
        BREEDER,
        BLACKSMITH
    }

    private static class MerchantInfo {
        final int villageId;
        final MerchantType type;

        MerchantInfo(int villageId, MerchantType type) {
            this.villageId = villageId;
            this.type = type;
        }
    }

    private static void registerMerchant(Villager villager, int villageId, MerchantType type) {
        if (villager == null) {
            return;
        }

        Iterator<Map.Entry<UUID, MerchantInfo>> it = MERCHANTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, MerchantInfo> entry = it.next();
            MerchantInfo info = entry.getValue();
            if (info.villageId == villageId && info.type == type) {
                UUID oldId = entry.getKey();
                for (World world : Bukkit.getWorlds()) {
                    Entity entity = world.getEntity(oldId);
                    if (entity != null) {
                        entity.remove();
                        break;
                    }
                }
                it.remove();
                break;
            }
        }

        MERCHANTS.put(villager.getUniqueId(), new MerchantInfo(villageId, type));
    }

    public static void spawnInitial(Plugin plugin,
                                    Location center,
                                    int villageId,
                                    int ttlTicks) {
        spawnInitial(
                plugin,
                center,
                Map.of(),
                villageId,
                compatibilityPopulationSettings(ttlTicks)
        );
    }

    public static void spawnInitial(Plugin plugin,
                                    Location center,
                                    Map<String, Location> anchors,
                                    int villageId,
                                    int ttlTicks) {
        spawnInitial(
                plugin,
                center,
                anchors,
                villageId,
                compatibilityPopulationSettings(ttlTicks)
        );
    }

    /**
     * Crée une population initiale répartie entre les quartiers de la ville.
     * Les habitants génériques occupent prioritairement les maisons tandis que
     * les PNJ nommés apparaissent près de leur lieu de travail.
     */
    public static void spawnInitial(
            Plugin plugin,
            Location center,
            Map<String, Location> anchors,
            int villageId,
            VillagePopulationSettings settings) {
        if (plugin == null || center == null || settings == null) {
            return;
        }

        initializePopulationListener(plugin);
        VILLAGE_CENTERS.put(villageId, center.clone());
        VILLAGE_ANCHORS.put(villageId, cloneAnchors(anchors));

        Location spawnCenter = anchorOr(anchors, "plaza", center);
        World world = spawnCenter.getWorld();
        if (world == null) {
            return;
        }

        Random random = new Random(
                ((long) villageId << 32) ^ world.getSeed()
        );
        List<Location> residentAnchors =
                collectResidentAnchors(anchors, spawnCenter);

        for (int i = 0; i < settings.initialResidents(); i++) {
            Location anchor = residentAnchors.get(
                    i % residentAnchors.size()
            );
            Location spawnLocation = findSafeSpawn(
                    anchor,
                    random,
                    3
            );
            Villager villager = (Villager) world.spawnEntity(
                    spawnLocation,
                    EntityType.VILLAGER
            );
            List<Villager.Profession> professions =
                    genericProfessions();
            villager.setProfession(professions.get(
                    random.nextInt(professions.size())
            ));
            villager.setAdult();
            villager.setRemoveWhenFarAway(false);
            tagEntity(villager, plugin, villageId);
        }

        Villager miner = spawnNamedAtAnchor(
                world,
                plugin,
                anchorOr(
                        anchors,
                        "service_yard",
                        anchorOr(anchors, "gate", spawnCenter)
                ),
                "§eMineur",
                Villager.Profession.TOOLSMITH,
                villageId,
                random
        );
        Villager lumberjack = spawnNamedAtAnchor(
                world,
                plugin,
                anchorOr(anchors, "carpenter",
                        anchorOr(anchors, "gate", spawnCenter)),
                "§6Bûcheron",
                Villager.Profession.FLETCHER,
                villageId,
                random
        );
        Villager farmer = spawnNamedAtAnchor(
                world,
                plugin,
                anchorOr(anchors, "farm", spawnCenter),
                "§aAgriculteur",
                Villager.Profession.FARMER,
                villageId,
                random
        );
        Villager breeder = spawnNamedAtAnchor(
                world,
                plugin,
                anchorOr(anchors, "pen", spawnCenter),
                "§dÉleveur",
                Villager.Profession.SHEPHERD,
                villageId,
                random
        );
        Villager blacksmith = spawnNamedAtAnchor(
                world,
                plugin,
                anchorOr(anchors, "forge", spawnCenter),
                "§cForgeron",
                Villager.Profession.ARMORER,
                villageId,
                random
        );

        setupMinerTrades(miner);
        setupLumberjackTrades(lumberjack);
        setupFarmerTrades(farmer);
        setupBreederTrades(breeder);
        setupBlacksmithTrades(blacksmith);

        registerMerchant(
                miner,
                villageId,
                MerchantType.MINER
        );
        registerMerchant(
                lumberjack,
                villageId,
                MerchantType.LUMBERJACK
        );
        registerMerchant(
                farmer,
                villageId,
                MerchantType.FARMER
        );
        registerMerchant(
                breeder,
                villageId,
                MerchantType.BREEDER
        );
        registerMerchant(
                blacksmith,
                villageId,
                MerchantType.BLACKSMITH
        );

        // PNJ civils supplémentaires : ils donnent une fonction visible aux
        // nouveaux bâtiments, sans recevoir les échanges spéciaux du plugin.
        spawnNamedAtAnchor(
                world,
                plugin,
                anchorOr(anchors, "mayor", spawnCenter),
                "§bMaire",
                Villager.Profession.LIBRARIAN,
                villageId,
                random
        );
        spawnNamedAtAnchor(
                world,
                plugin,
                anchorOr(anchors, "church", spawnCenter),
                "§fPrêtre",
                Villager.Profession.CLERIC,
                villageId,
                random
        );
        spawnNamedAtAnchor(
                world,
                plugin,
                anchorOr(anchors, "carpenter", spawnCenter),
                "§6Charpentier",
                Villager.Profession.FLETCHER,
                villageId,
                random
        );
        spawnNamedAtAnchor(
                world,
                plugin,
                anchorOr(anchors, "stable", spawnCenter),
                "§ePalefrenier",
                Villager.Profession.LEATHERWORKER,
                villageId,
                random
        );
        spawnNamedAtAnchor(
                world,
                plugin,
                anchorOr(anchors, "bakery", spawnCenter),
                "§6Boulangère",
                Villager.Profession.FARMER,
                villageId,
                random
        );

        for (int i = 0; i < 2; i++) {
            Location golemSpawn = findSafeSpawn(
                    anchorOr(anchors, "plaza", spawnCenter),
                    random,
                    4
            );
            IronGolem golem = (IronGolem) world.spawnEntity(
                    golemSpawn,
                    EntityType.IRON_GOLEM
            );
            golem.setPlayerCreated(true);
            golem.setRemoveWhenFarAway(false);
            tagEntity(golem, plugin, villageId);
        }

        scheduleExpiry(
                plugin,
                villageId,
                settings.lifetimeTicks()
        );
        startCapTask(
                plugin,
                villageId,
                spawnCenter,
                settings.populationCap()
        );
    }

    private static VillagePopulationSettings
    compatibilityPopulationSettings(int ttlTicks) {
        return new VillagePopulationSettings(
                6,
                4,
                DEFAULT_CAP,
                1_200,
                2_400,
                4,
                24,
                3,
                Math.max(0, ttlTicks)
        );
    }

    private static MerchantRecipe diamondTrade(ItemStack result, int diamondCost) {
        MerchantRecipe recipe = new MerchantRecipe(result, 0, INFINITE_TRADES, false, 0, 0.0f);
        recipe.addIngredient(new ItemStack(Material.DIAMOND, diamondCost));
        return recipe;
    }

    private static void setupLumberjackTrades(Villager villager) {
        if (villager == null) {
            return;
        }

        List<MerchantRecipe> recipes = new ArrayList<>();
        recipes.add(diamondTrade(new ItemStack(Material.OAK_LOG, 32), 1));
        recipes.add(diamondTrade(new ItemStack(Material.SPRUCE_LOG, 32), 1));
        recipes.add(diamondTrade(new ItemStack(Material.BIRCH_LOG, 32), 1));
        recipes.add(diamondTrade(new ItemStack(Material.OAK_PLANKS, 64), 1));
        recipes.add(diamondTrade(new ItemStack(Material.SPRUCE_PLANKS, 64), 1));
        recipes.add(diamondTrade(new ItemStack(Material.BIRCH_PLANKS, 64), 1));

        villager.setVillagerLevel(5);
        villager.setRecipes(recipes);
    }

    private static void setupFarmerTrades(Villager villager) {
        if (villager == null) {
            return;
        }

        List<MerchantRecipe> recipes = new ArrayList<>();
        recipes.add(diamondTrade(new ItemStack(Material.BREAD, 32), 1));
        recipes.add(diamondTrade(new ItemStack(Material.CARROT, 32), 1));
        recipes.add(diamondTrade(new ItemStack(Material.POTATO, 32), 1));
        recipes.add(diamondTrade(new ItemStack(Material.BEETROOT, 32), 1));
        recipes.add(diamondTrade(new ItemStack(Material.WHEAT, 32), 1));
        recipes.add(diamondTrade(new ItemStack(Material.GOLDEN_CARROT, 16), 2));

        villager.setVillagerLevel(5);
        villager.setRecipes(recipes);
    }

    private static void setupBreederTrades(Villager villager) {
        if (villager == null) {
            return;
        }

        List<MerchantRecipe> recipes = new ArrayList<>();
        recipes.add(diamondTrade(new ItemStack(Material.LEATHER, 24), 1));
        recipes.add(diamondTrade(new ItemStack(Material.WHITE_WOOL, 32), 1));
        recipes.add(diamondTrade(new ItemStack(Material.FEATHER, 32), 1));
        recipes.add(diamondTrade(new ItemStack(Material.EGG, 16), 1));
        recipes.add(diamondTrade(new ItemStack(Material.COOKED_BEEF, 16), 1));
        recipes.add(diamondTrade(new ItemStack(Material.COOKED_PORKCHOP, 16), 1));
        recipes.add(diamondTrade(new ItemStack(Material.HAY_BLOCK, 16), 1));
        recipes.add(diamondTrade(new ItemStack(Material.LEAD, 4), 2));
        recipes.add(diamondTrade(new ItemStack(Material.SADDLE, 1), 3));

        villager.setVillagerLevel(5);
        villager.setRecipes(recipes);
    }

    private static void setupBlacksmithTrades(Villager villager) {
        if (villager == null) {
            return;
        }

        List<MerchantRecipe> recipes = new ArrayList<>();
        recipes.add(diamondTrade(new ItemStack(Material.IRON_SWORD, 1), 1));
        recipes.add(diamondTrade(new ItemStack(Material.IRON_AXE, 1), 1));
        recipes.add(diamondTrade(new ItemStack(Material.IRON_PICKAXE, 1), 1));
        recipes.add(diamondTrade(new ItemStack(Material.IRON_HELMET, 1), 1));
        recipes.add(diamondTrade(new ItemStack(Material.IRON_CHESTPLATE, 1), 1));
        recipes.add(diamondTrade(new ItemStack(Material.IRON_LEGGINGS, 1), 1));
        recipes.add(diamondTrade(new ItemStack(Material.IRON_BOOTS, 1), 1));
        recipes.add(diamondTrade(new ItemStack(Material.DIAMOND_SWORD, 1), 2));
        recipes.add(diamondTrade(new ItemStack(Material.DIAMOND_PICKAXE, 1), 3));
        recipes.add(diamondTrade(new ItemStack(Material.DIAMOND_CHESTPLATE, 1), 4));

        villager.setVillagerLevel(5);
        villager.setRecipes(recipes);
    }

    private static void setupMinerTrades(Villager villager) {
        if (villager == null) {
            return;
        }

        List<MerchantRecipe> recipes = new ArrayList<>();
        recipes.add(diamondTrade(new ItemStack(Material.COAL, 64), 1));
        recipes.add(diamondTrade(new ItemStack(Material.IRON_INGOT, 32), 1));
        recipes.add(diamondTrade(new ItemStack(Material.GOLD_INGOT, 24), 2));
        recipes.add(diamondTrade(new ItemStack(Material.REDSTONE, 48), 1));
        recipes.add(diamondTrade(new ItemStack(Material.LAPIS_LAZULI, 32), 1));

        villager.setVillagerLevel(5);
        villager.setRecipes(recipes);
    }

    public static void startCapTask(Plugin plugin,
                                    Location center,
                                    int cap) {
        if (center == null) {
            return;
        }
        startCapTask(
                plugin,
                computeVillageId(center),
                center,
                cap
        );
    }

    /**
     * Démarre une seule tâche de quota par village. Une régénération au même
     * endroit remplace la tâche précédente au lieu d'empiler des timers.
     */
    public static void startCapTask(Plugin plugin,
                                    int villageId,
                                    Location center,
                                    int cap) {
        if (plugin == null
                || center == null
                || center.getWorld() == null) {
            return;
        }

        BukkitTask previous = CAP_TASKS.remove(villageId);
        if (previous != null) {
            previous.cancel();
        }

        int safeCap = Math.max(1, Math.min(120, cap));
        Location pivot = center.clone();
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                enforceCap(
                        plugin,
                        villageId,
                        pivot,
                        safeCap
                );
            }
        }.runTaskTimer(plugin, 200L, 200L);
        CAP_TASKS.put(villageId, task);
    }

    private static void enforceCap(Plugin plugin,
                                   int villageId,
                                   Location pivot,
                                   int cap) {
        World villageWorld = pivot.getWorld();
        if (villageWorld == null) {
            return;
        }

        List<Villager> villagers = new ArrayList<>();
        for (Entity entity : villageWorld.getEntities()) {
            if (entity instanceof Villager villager
                    && isVillageEntity(entity, plugin, villageId)) {
                villagers.add(villager);
            }
        }

        int overflow = villagers.size() - cap;
        if (overflow <= 0) {
            return;
        }

        /*
         * Les PNJ nommés portent les métiers et échanges propres au plugin :
         * ils sont toujours préservés. Parmi les habitants génériques, les plus
         * éloignés du centre sont retirés en premier.
         */
        villagers.stream()
                .filter(villager -> villager.getCustomName() == null)
                .sorted(Comparator.comparingDouble(
                        (Villager villager) ->
                                villager.getLocation()
                                        .distanceSquared(pivot)
                ).reversed())
                .limit(overflow)
                .forEach(Entity::remove);
    }

    public static void startMerchantGuardTask(Plugin plugin) {
        new BukkitRunnable() {
            @Override
            public void run() {
                guardMerchants(plugin);
            }
        }.runTaskTimer(plugin, 20L * 10, 20L * 10);
    }

    private static void guardMerchants(Plugin plugin) {
        List<MerchantInfo> merchantsToRespawn = new ArrayList<>();
        Iterator<Map.Entry<UUID, MerchantInfo>> it =
                MERCHANTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, MerchantInfo> entry = it.next();
            UUID uuid = entry.getKey();
            MerchantInfo info = entry.getValue();

            Entity entity = null;
            for (World world : Bukkit.getWorlds()) {
                entity = world.getEntity(uuid);
                if (entity != null) {
                    break;
                }
            }

            if (!(entity instanceof Villager villager)
                    || villager.isDead()
                    || !villager.isValid()) {
                /*
                 * Le respawn ajoute une nouvelle entrée dans MERCHANTS. Il est
                 * donc différé après l'itération afin de ne jamais modifier la
                 * carte pendant que son Iterator est actif.
                 */
                it.remove();
                merchantsToRespawn.add(info);
                continue;
            }

            Location center = VILLAGE_CENTERS.get(info.villageId);
            if (center == null || center.getWorld() == null) {
                continue;
            }
            Location anchor = merchantAnchor(
                    info.villageId,
                    info.type,
                    center
            );

            if (!villager.getWorld().equals(center.getWorld())) {
                villager.teleport(anchor);
                continue;
            }

            double maxDist2 =
                    MERCHANT_GUARD_RADIUS * MERCHANT_GUARD_RADIUS;
            if (villager.getLocation().distanceSquared(center) > maxDist2) {
                villager.teleport(anchor);
            }
        }

        merchantsToRespawn.forEach(info ->
                respawnMerchant(plugin, info));
    }

    private static void respawnMerchant(Plugin plugin, MerchantInfo info) {
        Location center = VILLAGE_CENTERS.get(info.villageId);
        if (center == null) {
            return;
        }
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        String name;
        Villager.Profession profession;
        switch (info.type) {
            case MINER -> {
                name = "§eMineur";
                profession = Villager.Profession.TOOLSMITH;
            }
            case LUMBERJACK -> {
                name = "§6Bûcheron";
                profession = Villager.Profession.FLETCHER;
            }
            case FARMER -> {
                name = "§aAgriculteur";
                profession = Villager.Profession.FARMER;
            }
            case BREEDER -> {
                name = "§dÉleveur";
                profession = Villager.Profession.SHEPHERD;
            }
            case BLACKSMITH -> {
                name = "§cForgeron";
                profession = Villager.Profession.ARMORER;
            }
            default -> {
                return;
            }
        }

        Location spawnLoc = merchantAnchor(info.villageId, info.type, center);
        Villager villager = spawnNamed(world, plugin, spawnLoc, name, profession, info.villageId);

        switch (info.type) {
            case MINER -> setupMinerTrades(villager);
            case LUMBERJACK -> setupLumberjackTrades(villager);
            case FARMER -> setupFarmerTrades(villager);
            case BREEDER -> setupBreederTrades(villager);
            case BLACKSMITH -> setupBlacksmithTrades(villager);
        }

        registerMerchant(villager, info.villageId, info.type);
    }

    private static void scheduleExpiry(Plugin plugin,
                                       int villageId,
                                       int lifetimeTicks) {
        BukkitTask previous = EXPIRY_TASKS.remove(villageId);
        if (previous != null) {
            previous.cancel();
        }
        if (lifetimeTicks <= 0) {
            return;
        }

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                EXPIRY_TASKS.remove(villageId);
                cleanup(plugin, villageId);
            }
        }.runTaskLater(plugin, lifetimeTicks);
        EXPIRY_TASKS.put(villageId, task);
    }

    public static void cleanup(Plugin plugin, int villageId) {
        GateGuardManager.stopGuardTask(villageId);
        VILLAGE_CENTERS.remove(villageId);
        VILLAGE_ANCHORS.remove(villageId);
        MERCHANTS.entrySet().removeIf(
                entry -> entry.getValue().villageId == villageId
        );

        BukkitTask capTask = CAP_TASKS.remove(villageId);
        if (capTask != null) {
            capTask.cancel();
        }
        BukkitTask expiryTask = EXPIRY_TASKS.remove(villageId);
        if (expiryTask != null) {
            expiryTask.cancel();
        }

        if (plugin == null) {
            return;
        }
        for (World world : plugin.getServer().getWorlds()) {
            world.getEntities().stream()
                    .filter(entity ->
                            isVillageEntity(
                                    entity,
                                    plugin,
                                    villageId
                            ))
                    .forEach(Entity::remove);
        }
    }

    public static int computeVillageId(Location center) {
        World world = center.getWorld();
        int worldHash = world != null
                ? world.getUID().hashCode()
                : 0;
        return center.getBlockX()
                ^ center.getBlockZ()
                ^ worldHash;
    }

    /**
     * Associe une entité au village dans les métadonnées volatiles et dans le
     * conteneur persistant. Le second marquage survit aux redémarrages.
     */
    public static void tagEntity(Entity entity, Plugin plugin, int id) {
        if (entity == null || plugin == null) {
            return;
        }

        entity.setPersistent(true);
        entity.setMetadata(
                TAG,
                new FixedMetadataValue(plugin, id)
        );
        entity.getPersistentDataContainer().set(
                villageIdKey(plugin),
                PersistentDataType.INTEGER,
                id
        );
    }

    private static boolean isVillageEntity(Entity entity,
                                           Plugin plugin,
                                           int villageId) {
        if (entity == null || plugin == null) {
            return false;
        }

        Integer persistedId = entity.getPersistentDataContainer().get(
                villageIdKey(plugin),
                PersistentDataType.INTEGER
        );
        if (persistedId != null && persistedId == villageId) {
            return true;
        }

        for (MetadataValue value : entity.getMetadata(TAG)) {
            Plugin owner = value.getOwningPlugin();
            if ((owner == null || owner == plugin)
                    && value.asInt() == villageId) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Location> cloneAnchors(
            Map<String, Location> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            return Map.of();
        }

        Map<String, Location> copy = new LinkedHashMap<>();
        anchors.forEach((key, location) -> {
            if (key != null && location != null) {
                copy.put(key, location.clone());
            }
        });
        return copy;
    }

    private static List<Location> collectResidentAnchors(
            Map<String, Location> anchors,
            Location fallback) {
        List<Location> result = new ArrayList<>();
        if (anchors != null) {
            anchors.entrySet().stream()
                    .filter(entry ->
                            entry.getKey() != null
                                    && entry.getKey().startsWith(
                                    "residence_"
                            )
                                    && entry.getValue() != null)
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .map(Location::clone)
                    .forEach(result::add);

            for (String key : List.of(
                    "plaza",
                    "market",
                    "town_hall",
                    "inn",
                    "bakery",
                    "carpenter",
                    "stable",
                    "church")) {
                Location anchor = anchors.get(key);
                if (anchor != null) {
                    result.add(anchor.clone());
                }
            }
        }

        if (result.isEmpty()) {
            result.add(fallback.clone());
        }
        return result;
    }

    /**
     * Cherche une colonne solide avec deux blocs libres. Le balayage vertical
     * absorbe les terrasses et les petites différences de niveau du plan.
     */
    private static Location findSafeSpawn(Location anchor,
                                          Random random,
                                          int radius) {
        World world = anchor.getWorld();
        if (world == null) {
            return anchor.clone();
        }

        int safeRadius = Math.max(0, Math.min(6, radius));
        int attempts = Math.max(12, (safeRadius * 2 + 1) * 2);
        for (int attempt = 0; attempt < attempts; attempt++) {
            int x = anchor.getBlockX()
                    + (safeRadius == 0
                    ? 0
                    : random.nextInt(safeRadius * 2 + 1)
                    - safeRadius);
            int z = anchor.getBlockZ()
                    + (safeRadius == 0
                    ? 0
                    : random.nextInt(safeRadius * 2 + 1)
                    - safeRadius);

            for (int offsetY = 4; offsetY >= -2; offsetY--) {
                int feetY = anchor.getBlockY() + offsetY;
                if (feetY <= world.getMinHeight()
                        || feetY + 1 >= world.getMaxHeight()) {
                    continue;
                }

                Block floor = world.getBlockAt(x, feetY - 1, z);
                Block feet = world.getBlockAt(x, feetY, z);
                Block head = world.getBlockAt(x, feetY + 1, z);
                if (floor.getType().isSolid()
                        && feet.isPassable()
                        && head.isPassable()
                        && !feet.isLiquid()
                        && !head.isLiquid()) {
                    return new Location(
                            world,
                            x + 0.5D,
                            feetY,
                            z + 0.5D,
                            random.nextFloat() * 360.0F,
                            0.0F
                    );
                }
            }
        }

        return new Location(
                world,
                anchor.getBlockX() + 0.5D,
                anchor.getBlockY() + 1.0D,
                anchor.getBlockZ() + 0.5D
        );
    }

    private static Villager spawnNamedAtAnchor(
            World world,
            Plugin plugin,
            Location anchor,
            String name,
            Villager.Profession profession,
            int villageId,
            Random random) {
        return spawnNamed(
                world,
                plugin,
                findSafeSpawn(anchor, random, 3),
                name,
                profession,
                villageId
        );
    }

    private static List<Villager.Profession> genericProfessions() {
        return List.of(
                Villager.Profession.FARMER,
                Villager.Profession.TOOLSMITH,
                Villager.Profession.CLERIC,
                Villager.Profession.FLETCHER,
                Villager.Profession.LIBRARIAN,
                Villager.Profession.MASON
        );
    }

    private static Location anchorOr(Map<String, Location> anchors, String key, Location fallback) {
        if (anchors != null && anchors.get(key) != null) {
            return anchors.get(key);
        }
        return fallback;
    }

    private static Location merchantAnchor(int villageId, MerchantType type, Location fallback) {
        Map<String, Location> anchors = VILLAGE_ANCHORS.getOrDefault(villageId, Map.of());
        Location anchor = switch (type) {
            case MINER -> anchorOr(anchors, "service_yard", anchorOr(anchors, "gate", fallback));
            case LUMBERJACK -> anchorOr(
                    anchors,
                    "carpenter",
                    anchorOr(anchors, "gate", fallback)
            );
            case FARMER -> anchorOr(anchors, "farm", fallback);
            case BREEDER -> anchorOr(anchors, "pen", fallback);
            case BLACKSMITH -> anchorOr(anchors, "forge", fallback);
        };
        return anchor.clone().add(0.5, 1, 0.5);
    }

    private static Villager spawnNamed(World world,
                                       Plugin plugin,
                                       Location location,
                                       String name,
                                       Villager.Profession profession,
                                       int villageId) {
        world.getChunkAt(location).load();
        Villager villager = (Villager) world.spawnEntity(
                location,
                EntityType.VILLAGER
        );
        villager.setCustomName(name);
        villager.setCustomNameVisible(true);
        villager.setProfession(profession);
        villager.setAdult();
        villager.setRemoveWhenFarAway(false);
        tagEntity(villager, plugin, villageId);
        return villager;
    }
}
