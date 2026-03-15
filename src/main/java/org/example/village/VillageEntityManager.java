package org.example.village;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Gestion des PNJ / golems d'un village: spawn initial, nettoyage, quota global.
 */
public final class VillageEntityManager {

    public static final String TAG = "MINEGUS_VILLAGE_ID";
    private static final int DEFAULT_CAP = 40;
    private static final int INFINITE_TRADES = 999999;
    private static final int MERCHANT_GUARD_RADIUS = 40;

    private static final Map<Integer, Location> VILLAGE_CENTERS = new HashMap<>();
    private static final Map<Integer, Map<String, Location>> VILLAGE_ANCHORS = new HashMap<>();
    private static final Map<UUID, MerchantInfo> MERCHANTS = new HashMap<>();

    private VillageEntityManager() {}

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
        spawnInitial(plugin, center, Map.of(), villageId, ttlTicks);
    }

    public static void spawnInitial(Plugin plugin,
                                    Location center,
                                    Map<String, Location> anchors,
                                    int villageId,
                                    int ttlTicks) {

        VILLAGE_CENTERS.put(villageId, center.clone());
        VILLAGE_ANCHORS.put(villageId, anchors != null ? new HashMap<>(anchors) : Map.of());

        Location spawnCenter = anchorOr(anchors, "plaza", center);
        World world = spawnCenter.getWorld();
        if (world == null) {
            return;
        }
        Random random = new Random();

        for (int i = 0; i < 6; i++) {
            Villager villager = (Villager) world.spawnEntity(randAround(spawnCenter), EntityType.VILLAGER);
            List<Villager.Profession> genericProfessions = genericProfessions();
            villager.setProfession(genericProfessions.get(random.nextInt(genericProfessions.size())));
            tagEntity(villager, plugin, villageId);
        }

        Villager miner = spawnNamed(world, plugin,
                aroundAnchor(anchorOr(anchors, "service_yard", anchorOr(anchors, "gate", spawnCenter))),
                "§eMineur", Villager.Profession.TOOLSMITH, villageId);
        Villager lumberjack = spawnNamed(world, plugin,
                aroundAnchor(anchorOr(anchors, "gate", spawnCenter)),
                "§6Bûcheron", Villager.Profession.FLETCHER, villageId);
        Villager farmer = spawnNamed(world, plugin,
                aroundAnchor(anchorOr(anchors, "farm", spawnCenter)),
                "§aAgriculteur", Villager.Profession.FARMER, villageId);
        Villager breeder = spawnNamed(world, plugin,
                aroundAnchor(anchorOr(anchors, "pen", spawnCenter)),
                "§dÉleveur", Villager.Profession.SHEPHERD, villageId);
        Villager blacksmith = spawnNamed(world, plugin,
                aroundAnchor(anchorOr(anchors, "forge", spawnCenter)),
                "§cForgeron", Villager.Profession.ARMORER, villageId);

        setupMinerTrades(miner);
        setupLumberjackTrades(lumberjack);
        setupFarmerTrades(farmer);
        setupBreederTrades(breeder);
        setupBlacksmithTrades(blacksmith);

        registerMerchant(miner, villageId, MerchantType.MINER);
        registerMerchant(lumberjack, villageId, MerchantType.LUMBERJACK);
        registerMerchant(farmer, villageId, MerchantType.FARMER);
        registerMerchant(breeder, villageId, MerchantType.BREEDER);
        registerMerchant(blacksmith, villageId, MerchantType.BLACKSMITH);

        for (int i = 0; i < 2; i++) {
            IronGolem golem = (IronGolem) world.spawnEntity(aroundAnchor(anchorOr(anchors, "plaza", spawnCenter)), EntityType.IRON_GOLEM);
            golem.setPlayerCreated(true);
            tagEntity(golem, plugin, villageId);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                cleanup(plugin, villageId);
            }
        }.runTaskLater(plugin, ttlTicks);

        startCapTask(plugin, spawnCenter, DEFAULT_CAP);
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

    public static void startCapTask(Plugin plugin, Location center, int cap) {
        int villageId = computeVillageId(center);
        new BukkitRunnable() {
            @Override
            public void run() {
                enforceCap(plugin, villageId, center, cap);
            }
        }.runTaskTimer(plugin, 200L, 200L);
    }

    private static void enforceCap(Plugin plugin, int villageId, Location pivot, int cap) {
        List<LivingEntity> tagged = new ArrayList<>();
        for (World world : plugin.getServer().getWorlds()) {
            world.getEntities().stream()
                    .filter(entity -> entity.hasMetadata(TAG)
                            && entity.getMetadata(TAG).get(0).asInt() == villageId
                            && entity instanceof LivingEntity livingEntity
                            && !(livingEntity instanceof Player))
                    .map(entity -> (LivingEntity) entity)
                    .forEach(tagged::add);
        }

        if (tagged.size() <= cap) {
            return;
        }

        tagged.sort(Comparator.comparingDouble(entity -> -entity.getLocation().distanceSquared(pivot)));
        for (int i = cap; i < tagged.size(); i++) {
            tagged.get(i).remove();
        }
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
        Iterator<Map.Entry<UUID, MerchantInfo>> it = MERCHANTS.entrySet().iterator();
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

            if (!(entity instanceof Villager villager)) {
                respawnMerchant(plugin, info);
                it.remove();
                continue;
            }

            if (villager.isDead() || !villager.isValid()) {
                respawnMerchant(plugin, info);
                it.remove();
                continue;
            }

            Location center = VILLAGE_CENTERS.get(info.villageId);
            if (center == null || center.getWorld() == null) {
                continue;
            }
            Location anchor = merchantAnchor(info.villageId, info.type, center);

            if (!villager.getWorld().equals(center.getWorld())) {
                villager.teleport(anchor);
                continue;
            }

            double maxDist2 = MERCHANT_GUARD_RADIUS * MERCHANT_GUARD_RADIUS;
            if (villager.getLocation().distanceSquared(center) > maxDist2) {
                villager.teleport(anchor);
            }
        }
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

    public static void cleanup(Plugin plugin, int villageId) {
        GateGuardManager.stopGuardTask(villageId);
        VILLAGE_CENTERS.remove(villageId);
        VILLAGE_ANCHORS.remove(villageId);
        MERCHANTS.entrySet().removeIf(entry -> entry.getValue().villageId == villageId);
        for (World world : plugin.getServer().getWorlds()) {
            world.getEntities().stream()
                    .filter(entity -> entity.hasMetadata(TAG)
                            && entity.getMetadata(TAG).get(0).asInt() == villageId)
                    .forEach(Entity::remove);
        }
    }

    public static int computeVillageId(Location center) {
        World world = center.getWorld();
        int worldHash = world != null ? world.getUID().hashCode() : 0;
        return center.getBlockX() ^ center.getBlockZ() ^ worldHash;
    }

    public static void tagEntity(Entity entity, Plugin plugin, int id) {
        entity.setPersistent(true);
        entity.setMetadata(TAG, new FixedMetadataValue(plugin, id));
    }

    private static Location randAround(Location center) {
        Random random = new Random();
        return center.clone().add(random.nextInt(10) - 5, 1, random.nextInt(10) - 5);
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

    private static Location aroundAnchor(Location anchor) {
        return randAround(anchor.clone().add(0.5, 0, 0.5));
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
            case LUMBERJACK -> anchorOr(anchors, "gate", fallback);
            case FARMER -> anchorOr(anchors, "farm", fallback);
            case BREEDER -> anchorOr(anchors, "pen", fallback);
            case BLACKSMITH -> anchorOr(anchors, "forge", fallback);
        };
        return anchor.clone().add(0.5, 1, 0.5);
    }

    private static Villager spawnNamed(World world, Plugin plugin, Location location,
                                       String name, Villager.Profession profession, int villageId) {
        Villager villager = (Villager) world.spawnEntity(location, EntityType.VILLAGER);
        villager.setCustomName(name);
        villager.setCustomNameVisible(true);
        villager.setProfession(profession);
        tagEntity(villager, plugin, villageId);
        return villager;
    }
}
