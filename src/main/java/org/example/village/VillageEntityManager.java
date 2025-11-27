package org.example.village;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Gestion des PNJ / golems d’un village : spawn initial, nettoyage, quota global.
 */
public final class VillageEntityManager {

    public static final String TAG = "MINEGUS_VILLAGE_ID";
    private VillageEntityManager() {}

    /* ====== Quota global ====== */
    private static final int DEFAULT_CAP = 40;

    /** Nombre d'utilisations très élevé pour les trades des PNJ spéciaux. */
    private static final int INFINITE_TRADES = 999999;

    /* professions « génériques » utilisées pour la population de base */
    private static final List<Villager.Profession> GENERIC = List.of(
            Villager.Profession.FARMER,
            Villager.Profession.TOOLSMITH,
            Villager.Profession.CLERIC,
            Villager.Profession.FLETCHER,
            Villager.Profession.LIBRARIAN,
            Villager.Profession.MASON
    );

    /** Centre de chaque village (pour TP / respawn des marchands). */
    private static final Map<Integer, Location> VILLAGE_CENTERS = new HashMap<>();

    /** Marchands spéciaux du village (mineur, bucheron, agriculteur, eleveur, forgeron). */
    private static final Map<UUID, MerchantInfo> MERCHANTS = new HashMap<>();

    /** Rayon max autorisé avant de TP un marchand vers le centre. */
    private static final int MERCHANT_GUARD_RADIUS = 40;

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
        if (villager == null) return;
        MERCHANTS.put(villager.getUniqueId(), new MerchantInfo(villageId, type));
    }

    /* ------------------- SPAWN INITIAL ------------------- */
    public static void spawnInitial(Plugin plugin,
                                    Location center,
                                    int villageId,
                                    int ttlTicks) {

        // Mémoriser le centre du village pour ce villageId
        VILLAGE_CENTERS.put(villageId, center.clone());

        World w = center.getWorld();
        Random R = new Random();

        // 6 PNJ génériques
        for (int i = 0; i < 6; i++) {
            Villager v = (Villager) w.spawnEntity(randAround(center), EntityType.VILLAGER);
            v.setProfession(GENERIC.get(R.nextInt(GENERIC.size())));
            tagEntity(v, plugin, villageId);
        }

        /* PNJ spécialisés + marchands */
        Villager miner       = spawnNamed(w, plugin, randAround(center), "§eMineur",      Villager.Profession.TOOLSMITH, villageId);
        Villager lumberjack  = spawnNamed(w, plugin, randAround(center), "§6Bûcheron",    Villager.Profession.FLETCHER,  villageId);
        Villager farmer      = spawnNamed(w, plugin, randAround(center), "§aAgriculteur", Villager.Profession.FARMER,    villageId);
        Villager breeder     = spawnNamed(w, plugin, randAround(center), "§dÉleveur",     Villager.Profession.SHEPHERD,  villageId);
        Villager blacksmith  = spawnNamed(w, plugin, randAround(center), "§cForgeron",    Villager.Profession.ARMORER,   villageId);

        // configuration des boutiques
        setupMinerTrades(miner);
        setupLumberjackTrades(lumberjack);
        setupFarmerTrades(farmer);
        setupBreederTrades(breeder);
        setupBlacksmithTrades(blacksmith);

        registerMerchant(miner,      villageId, MerchantType.MINER);
        registerMerchant(lumberjack, villageId, MerchantType.LUMBERJACK);
        registerMerchant(farmer,     villageId, MerchantType.FARMER);
        registerMerchant(breeder,    villageId, MerchantType.BREEDER);
        registerMerchant(blacksmith, villageId, MerchantType.BLACKSMITH);

        /* 2 golems d’office */
        for (int i = 0; i < 2; i++) {
            IronGolem g = (IronGolem) w.spawnEntity(randAround(center), EntityType.IRON_GOLEM);
            g.setPlayerCreated(true);
            tagEntity(g, plugin, villageId);
        }

        /* nettoyage final (expire au bout de ttlTicks) */
        new BukkitRunnable() {
            @Override public void run() { cleanup(plugin, villageId); }
        }.runTaskLater(plugin, ttlTicks);

        /* démarrage de la tâche « quota » (100 NPC max) */
        startCapTask(plugin, center, DEFAULT_CAP);
    }

    /**
     * Crée un trade : X diamants -> item "result".
     * Le joueur est volontairement gagnant dans l'échange.
     */
    private static MerchantRecipe diamondTrade(ItemStack result, int diamondCost) {
        MerchantRecipe recipe = new MerchantRecipe(result, 0, INFINITE_TRADES, false, 0, 0.0f);
        recipe.addIngredient(new ItemStack(Material.DIAMOND, diamondCost));
        return recipe;
    }

    /**
     * Boutique du Bûcheron : vend du bois contre des diamants.
     */
    private static void setupLumberjackTrades(Villager villager) {
        if (villager == null) return;

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

    /**
     * Boutique de l'Agriculteur : vend des ressources végétales / nourriture de culture.
     */
    private static void setupFarmerTrades(Villager villager) {
        if (villager == null) return;

        List<MerchantRecipe> recipes = new ArrayList<>();

        // 1 diamant -> 32 pain
        recipes.add(diamondTrade(new ItemStack(Material.BREAD, 32), 1));

        // 1 diamant -> 32 carottes / patates / betteraves
        recipes.add(diamondTrade(new ItemStack(Material.CARROT, 32), 1));
        recipes.add(diamondTrade(new ItemStack(Material.POTATO, 32), 1));
        recipes.add(diamondTrade(new ItemStack(Material.BEETROOT, 32), 1));

        // 1 diamant -> 32 blé
        recipes.add(diamondTrade(new ItemStack(Material.WHEAT, 32), 1));

        // 2 diamants -> 16 carottes dorées (très fortes)
        recipes.add(diamondTrade(new ItemStack(Material.GOLDEN_CARROT, 16), 2));

        villager.setVillagerLevel(5);
        villager.setRecipes(recipes);
    }

    /**
     * Boutique de l'Éleveur : vend des ressources liées aux animaux.
     */
    private static void setupBreederTrades(Villager villager) {
        if (villager == null) return;

        List<MerchantRecipe> recipes = new ArrayList<>();

        // Ressources animales "brutes"
        recipes.add(diamondTrade(new ItemStack(Material.LEATHER, 24), 1));       // 1 diamant -> 24 cuir
        recipes.add(diamondTrade(new ItemStack(Material.WHITE_WOOL, 32), 1));    // 1 diamant -> 32 laine blanche
        recipes.add(diamondTrade(new ItemStack(Material.FEATHER, 32), 1));       // 1 diamant -> 32 plumes
        recipes.add(diamondTrade(new ItemStack(Material.EGG, 16), 1));           // 1 diamant -> 16 oeufs

        // Nourriture issue des animaux
        recipes.add(diamondTrade(new ItemStack(Material.COOKED_BEEF, 16), 1));   // 1 diamant -> 16 steaks cuits
        recipes.add(diamondTrade(new ItemStack(Material.COOKED_PORKCHOP, 16), 1)); // 1 diamant -> 16 côtelettes

        // Ressources "élevage"
        recipes.add(diamondTrade(new ItemStack(Material.HAY_BLOCK, 16), 1));     // 1 diamant -> 16 bottes de foin
        recipes.add(diamondTrade(new ItemStack(Material.LEAD, 4), 2));           // 2 diamants -> 4 laisses
        recipes.add(diamondTrade(new ItemStack(Material.SADDLE, 1), 3));         // 3 diamants -> 1 selle

        villager.setVillagerLevel(5);
        villager.setRecipes(recipes);
    }

    /**
     * Boutique du Forgeron : vend du stuff (principalement fer + quelques items diamant).
     */
    private static void setupBlacksmithTrades(Villager villager) {
        if (villager == null) return;

        List<MerchantRecipe> recipes = new ArrayList<>();

        // Stuff fer très rentable
        recipes.add(diamondTrade(new ItemStack(Material.IRON_SWORD, 1), 1));
        recipes.add(diamondTrade(new ItemStack(Material.IRON_AXE, 1), 1));
        recipes.add(diamondTrade(new ItemStack(Material.IRON_PICKAXE, 1), 1));

        recipes.add(diamondTrade(new ItemStack(Material.IRON_HELMET, 1), 1));
        recipes.add(diamondTrade(new ItemStack(Material.IRON_CHESTPLATE, 1), 1));
        recipes.add(diamondTrade(new ItemStack(Material.IRON_LEGGINGS, 1), 1));
        recipes.add(diamondTrade(new ItemStack(Material.IRON_BOOTS, 1), 1));

        // Quelques pièces diamant, encore "gagnantes"
        recipes.add(diamondTrade(new ItemStack(Material.DIAMOND_SWORD, 1), 2));
        recipes.add(diamondTrade(new ItemStack(Material.DIAMOND_PICKAXE, 1), 3));
        recipes.add(diamondTrade(new ItemStack(Material.DIAMOND_CHESTPLATE, 1), 4));

        villager.setVillagerLevel(5);
        villager.setRecipes(recipes);
    }

    /**
     * Boutique du Mineur : vend des minerais / ressources minières contre des diamants.
     */
    private static void setupMinerTrades(Villager villager) {
        if (villager == null) return;

        List<MerchantRecipe> recipes = new ArrayList<>();

        recipes.add(diamondTrade(new ItemStack(Material.COAL, 64), 1));
        recipes.add(diamondTrade(new ItemStack(Material.IRON_INGOT, 32), 1));
        recipes.add(diamondTrade(new ItemStack(Material.GOLD_INGOT, 24), 2));
        recipes.add(diamondTrade(new ItemStack(Material.REDSTONE, 48), 1));
        recipes.add(diamondTrade(new ItemStack(Material.LAPIS_LAZULI, 32), 1));

        villager.setVillagerLevel(5);
        villager.setRecipes(recipes);
    }

    /* ------------------- TÂCHE QUOTA (100 NPC) ------------------- */
    /** Lance (ou relance) la vérification périodique du quota de NPC pour ce village. */
    public static void startCapTask(Plugin plugin, Location center, int cap) {
        int villageId = computeVillageId(center);

        /* vérifie toutes les 10 s */
        new BukkitRunnable() {
            @Override public void run() { enforceCap(plugin, villageId, center, cap); }
        }.runTaskTimer(plugin, 200L, 200L);
    }

    /** Supprime les NPC excédentaires jusqu’à atteindre {@code cap}. */
    private static void enforceCap(Plugin plugin, int villageId,
                                   Location pivot, int cap) {

        List<LivingEntity> tagged = new ArrayList<>();
        for (World w : plugin.getServer().getWorlds()) {
            w.getEntities().stream()
                    .filter(e -> e.hasMetadata(TAG)
                            && e.getMetadata(TAG).get(0).asInt() == villageId
                            && e instanceof LivingEntity le
                            && !(le instanceof Player))            // on ignore les joueurs
                    .map(e -> (LivingEntity) e)
                    .forEach(tagged::add);
        }

        if (tagged.size() <= cap) return;

        /* trie par distance décroissante à la place : on supprime d’abord les plus loin */
        tagged.sort(Comparator.comparingDouble(e -> -e.getLocation().distanceSquared(pivot)));

        for (int i = cap; i < tagged.size(); i++) tagged.get(i).remove();
    }

    /* ------------------- SURVEILLANCE MARCHANDS ------------------- */
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
                if (entity != null) break;
            }

            // S'il n'y a plus d'entité ou que ce n'est plus un Villager -> respawn
            if (!(entity instanceof Villager villager)) {
                respawnMerchant(plugin, info);
                it.remove();
                continue;
            }

            // S'il est mort / invalide -> respawn
            if (villager.isDead() || !villager.isValid()) {
                respawnMerchant(plugin, info);
                it.remove();
                continue;
            }

            // Contrôle de la distance au centre du village
            Location center = VILLAGE_CENTERS.get(info.villageId);
            if (center == null) continue;
            World centerWorld = center.getWorld();
            if (centerWorld == null) continue;

            if (!villager.getWorld().equals(centerWorld)) {
                villager.teleport(center.clone().add(0.5, 1, 0.5));
                continue;
            }

            double maxDist2 = MERCHANT_GUARD_RADIUS * MERCHANT_GUARD_RADIUS;
            if (villager.getLocation().distanceSquared(center) > maxDist2) {
                villager.teleport(center.clone().add(0.5, 1, 0.5));
            }
        }
    }

    private static void respawnMerchant(Plugin plugin, MerchantInfo info) {
        Location center = VILLAGE_CENTERS.get(info.villageId);
        if (center == null) return;

        World world = center.getWorld();
        if (world == null) return;

        String name;
        Villager.Profession profession;

        switch (info.type) {
            case MINER:
                name = "§eMineur";
                profession = Villager.Profession.TOOLSMITH;
                break;
            case LUMBERJACK:
                name = "§6Bûcheron";
                profession = Villager.Profession.FLETCHER;
                break;
            case FARMER:
                name = "§aAgriculteur";
                profession = Villager.Profession.FARMER;
                break;
            case BREEDER:
                name = "§dÉleveur";
                profession = Villager.Profession.SHEPHERD;
                break;
            case BLACKSMITH:
                name = "§cForgeron";
                profession = Villager.Profession.ARMORER;
                break;
            default:
                return;
        }

        Location spawnLoc = center.clone().add(0.5, 1, 0.5);

        // respawn du villageois avec le bon nom / métier / tag village
        Villager villager = spawnNamed(world, plugin, spawnLoc, name, profession, info.villageId);

        // réapplique les trades selon son type
        switch (info.type) {
            case MINER:
                setupMinerTrades(villager);
                break;
            case LUMBERJACK:
                setupLumberjackTrades(villager);
                break;
            case FARMER:
                setupFarmerTrades(villager);
                break;
            case BREEDER:
                setupBreederTrades(villager);
                break;
            case BLACKSMITH:
                setupBlacksmithTrades(villager);
                break;
        }

        // réenregistrer ce nouveau PNJ comme marchand
        registerMerchant(villager, info.villageId, info.type);
    }

    /* ------------------- UTIL PUBLIC ------------------- */
    public static void cleanup(Plugin p, int villageId) {
        GateGuardManager.stopGuardTask(villageId);
        VILLAGE_CENTERS.remove(villageId);
        MERCHANTS.entrySet().removeIf(entry -> entry.getValue().villageId == villageId);
        for (World w : p.getServer().getWorlds()) {
            w.getEntities().stream()
                    .filter(e -> e.hasMetadata(TAG)
                            && e.getMetadata(TAG).get(0).asInt() == villageId)
                    .forEach(Entity::remove);
        }
    }

    public static int computeVillageId(Location center) {
        World world = center.getWorld();
        int worldHash = world != null ? world.getUID().hashCode() : 0;
        return center.getBlockX() ^ center.getBlockZ() ^ worldHash;
    }

    public static void tagEntity(Entity e, Plugin p, int id) {
        e.setPersistent(true);
        e.setMetadata(TAG, new FixedMetadataValue(p, id));
    }

    /* ------------------- PRIVÉS ------------------- */
    private static Location randAround(Location c) {
        Random R = new Random();
        return c.clone().add(R.nextInt(10) - 5, 1, R.nextInt(10) - 5);
    }

    private static Villager spawnNamed(World w, Plugin p, Location l,
                                       String name, Villager.Profession prof, int id) {
        Villager v = (Villager) w.spawnEntity(l, EntityType.VILLAGER);
        v.setCustomName(name);
        v.setCustomNameVisible(true);
        v.setProfession(prof);
        tagEntity(v, p, id);
        return v;
    }
}
