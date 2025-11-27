package org.example.village;

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

    /* ------------------- SPAWN INITIAL ------------------- */
    public static void spawnInitial(Plugin plugin,
                                    Location center,
                                    int villageId,
                                    int ttlTicks) {

        World w = center.getWorld();
        Random R = new Random();

        // 6 PNJ génériques
        for (int i = 0; i < 6; i++) {
            Villager v = (Villager) w.spawnEntity(randAround(center), EntityType.VILLAGER);
            v.setProfession(GENERIC.get(R.nextInt(GENERIC.size())));
            tagEntity(v, plugin, villageId);
        }

        /* PNJ spécialisés */
        Villager miner       = spawnNamed(w, plugin, randAround(center), "§eMineur",      Villager.Profession.TOOLSMITH, villageId);
        Villager lumberjack  = spawnNamed(w, plugin, randAround(center), "§6Bûcheron",    Villager.Profession.FLETCHER,  villageId);
        Villager farmer      = spawnNamed(w, plugin, randAround(center), "§aAgriculteur", Villager.Profession.FARMER,    villageId);
        spawnNamed(w, plugin, randAround(center), "§dÉleveur",     Villager.Profession.SHEPHERD,  villageId);

        // configuration des boutiques
        setupMinerTrades(miner);
        setupLumberjackTrades(lumberjack);
        setupFarmerTrades(farmer);

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
     * Boutique de l'Agriculteur : vend de la nourriture contre des diamants.
     */
    private static void setupFarmerTrades(Villager villager) {
        if (villager == null) return;

        List<MerchantRecipe> recipes = new ArrayList<>();

        recipes.add(diamondTrade(new ItemStack(Material.BREAD, 32), 1));

        recipes.add(diamondTrade(new ItemStack(Material.COOKED_BEEF, 16), 1));
        recipes.add(diamondTrade(new ItemStack(Material.COOKED_PORKCHOP, 16), 1));

        recipes.add(diamondTrade(new ItemStack(Material.CARROT, 32), 1));
        recipes.add(diamondTrade(new ItemStack(Material.POTATO, 32), 1));

        recipes.add(diamondTrade(new ItemStack(Material.GOLDEN_CARROT, 16), 2));

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

    /* ------------------- UTIL PUBLIC ------------------- */
    public static void cleanup(Plugin p, int villageId) {
        GateGuardManager.stopGuardTask(villageId);
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
