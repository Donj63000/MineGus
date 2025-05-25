package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Commande /armure : donne l'Armure du roi GIDON.
 *
 * Le port complet de l'armure confère divers effets (Night Vision, Fire Res., Water Breathing, Health Boost, Strength).
 * Lorsqu'on enlève un ou plusieurs éléments de l'armure, tous ces effets sont retirés.
 *
 * De plus, si le joueur subit des dégâts et porte cette armure, il invoque 4 loups
 * de garde qui attaquent l'agresseur. Les loups disparaissent au bout de 2 minutes
 * s'il n'y a plus d'attaque subie entre-temps (timer réinitialisé à chaque coup).
 */
public final class Armure implements CommandExecutor, Listener {

    private final JavaPlugin plugin;
    private BukkitRunnable armorTask;

    // Liste des joueurs actuellement "boostés" (qui ont les effets d’armure)
    private final Set<UUID> boostedPlayers = new HashSet<>();

    // =========== GESTION DES LOUPS =============
    private static final int GUARD_COUNT = 4;                // Nombre de loups à invoquer
    private static final long DESPAWN_DELAY = 2L * 60L * 20L; // 2 minutes en ticks (2 * 60 * 20)

    // Map : Joueur -> liste de loups actuellement invoqués pour lui
    private final Map<UUID, List<Wolf>> activeWolves = new HashMap<>();
    // Map : Joueur -> Task programmée pour despawn (timer)
    private final Map<UUID, BukkitTask> despawnTasks = new HashMap<>();
    // ===========================================

    // Noms personnalisés des pièces d'armure
    private static final String HELMET_NAME  = ChatColor.GOLD + "Casque du roi GIDON";
    private static final String CHEST_NAME   = ChatColor.GOLD + "Plastron du roi GIDON";
    private static final String LEGGING_NAME = ChatColor.GOLD + "Pantalon du roi GIDON";
    private static final String BOOT_NAME    = ChatColor.GOLD + "Chaussures du roi GIDON";

    public Armure(JavaPlugin plugin) {
        this.plugin = plugin;

        // Lier la commande "/armure" à cette classe
        if (plugin.getCommand("armure") != null) {
            plugin.getCommand("armure").setExecutor(this);
        }

        // On s'enregistre comme Listener pour écouter les dégâts
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Démarrer la boucle de vérification régulière (buffs)
        startArmorLoop();
    }

    // ----------------------------------------------------------------------
    // GESTION DE LA COMMANDE /ARMURE
    // ----------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Cette commande doit être exécutée par un joueur.");
            return true;
        }

        if (!command.getName().equalsIgnoreCase("armure")) {
            return false;
        }

        giveArmor(player);
        player.sendMessage(ChatColor.GREEN + "Tu as reçu l'Armure du roi GIDON !");
        return true;
    }

    /**
     * Donne la panoplie complète de l'armure personnalisée.
     */
    private void giveArmor(Player player) {
        player.getInventory().addItem(createPiece(Material.NETHERITE_HELMET,     HELMET_NAME));
        player.getInventory().addItem(createPiece(Material.NETHERITE_CHESTPLATE, CHEST_NAME));
        player.getInventory().addItem(createPiece(Material.NETHERITE_LEGGINGS,   LEGGING_NAME));
        player.getInventory().addItem(createPiece(Material.NETHERITE_BOOTS,      BOOT_NAME));
    }

    /**
     * Construit une pièce d'armure enchantée (Protection 4, Unbreaking 3, Mending 1).
     */
    private ItemStack createPiece(Material material, String customName) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(customName);

            // Enchantements normalisés
            meta.addEnchant(Enchantment.PROTECTION, 4, true);
            meta.addEnchant(Enchantment.DENSITY, 3, true); // Unbreaking
            meta.addEnchant(Enchantment.MENDING, 1, true);

            item.setItemMeta(meta);
        }
        return item;
    }

    // ----------------------------------------------------------------------
    // BOUCLE DE VÉRIFICATION POUR EFFETS D’ARMURE
    // ----------------------------------------------------------------------
    private void startArmorLoop() {
        armorTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    boolean hasFullArmor = hasFullArmor(p);
                    boolean isBoosted = boostedPlayers.contains(p.getUniqueId());

                    // S'il porte l'armure complète et n'est pas encore "boosté"
                    if (hasFullArmor && !isBoosted) {
                        applyEffects(p);
                        boostedPlayers.add(p.getUniqueId());
                    }
                    // S'il a retiré l'armure mais qu'il est encore "boosté"
                    else if (!hasFullArmor && isBoosted) {
                        removeEffects(p);
                        boostedPlayers.remove(p.getUniqueId());
                    }
                }
            }
        };
        // Lancé toutes les secondes (20 ticks)
        armorTask.runTaskTimer(plugin, 20L, 20L);
    }

    /**
     * Applique les buffs de l'armure (longue durée).
     */
    private void applyEffects(Player player) {
        int duration = 1_000_000; // Durée très longue (~ 13h)
        player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,    duration, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, duration, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, duration, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST,    duration, 1, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,        duration, 1, false, false));
    }

    /**
     * Retire tous les buffs liés à l'armure.
     */
    private void removeEffects(Player player) {
        player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        player.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
        player.removePotionEffect(PotionEffectType.WATER_BREATHING);
        player.removePotionEffect(PotionEffectType.HEALTH_BOOST);
        player.removePotionEffect(PotionEffectType.STRENGTH);
    }

    /**
     * Détermine si le joueur porte bien toutes les pièces
     * de l'armure du roi GIDON (casque, plastron, pantalon, bottes).
     */
    private boolean hasFullArmor(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        ItemStack chest  = player.getInventory().getChestplate();
        ItemStack legs   = player.getInventory().getLeggings();
        ItemStack boots  = player.getInventory().getBoots();

        return isPiece(helmet, HELMET_NAME)
                && isPiece(chest,  CHEST_NAME)
                && isPiece(legs,   LEGGING_NAME)
                && isPiece(boots,  BOOT_NAME);
    }

    private boolean isPiece(ItemStack item, String expectedName) {
        if (item == null) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && expectedName.equals(meta.getDisplayName());
    }

    // ----------------------------------------------------------------------
    // GESTION DES ATTAQUES → APPARITION DES LOUPS
    // ----------------------------------------------------------------------
    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        // Vérifie que la cible est un joueur
        if (!(e.getEntity() instanceof Player player)) return;

        // Le joueur doit porter l'armure complète
        if (!hasFullArmor(player)) return;

        // Si les dégâts sont annulés, rien ne se passe
        if (e.isCancelled()) return;

        // Identifie le "damager" (si flèche, boule de feu, etc. on récupère le vrai tireur)
        Entity damager = e.getDamager();
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Entity ent) {
            damager = ent;
        }

        // 1) Fait apparaître / réactualise les loups
        spawnGuardWolves(player, damager);

        // 2) Relance le timer de despawn (2 minutes après la dernière attaque)
        scheduleDespawn(player);
    }

    /**
     * Fait apparaître au total 4 loups autour du joueur et leur donne l'agresseur en cible.
     */
    private void spawnGuardWolves(Player player, Entity target) {
        // Récupère la liste de loups déjà actifs pour ce joueur
        List<Wolf> wolves = activeWolves.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());

        // Retire les loups morts ou invalides (précédent combat)
        wolves.removeIf(w -> w.isDead() || !w.isValid());

        // Combien en manque-t-il pour atteindre GUARD_COUNT ?
        int toSpawn = GUARD_COUNT - wolves.size();
        if (toSpawn > 0) {
            for (int i = 0; i < toSpawn; i++) {
                Wolf wolf = (Wolf) player.getWorld().spawnEntity(
                        player.getLocation().add((i - 1) * 1.5, 0, (i % 2 == 0 ? 1.5 : -1.5)),
                        EntityType.WOLF
                );
                wolf.setOwner(player);
                wolf.setCustomName(ChatColor.RED + "Garde du Roi");
                wolf.setAdult();
                wolf.getAttribute(Attribute.ARMOR).setBaseValue(40.0);
                wolf.setHealth(40.0);
                wolf.setCollarColor(DyeColor.RED);

                wolves.add(wolf);
            }
        }

        // Met à jour la cible
        if (target instanceof LivingEntity le) {
            for (Wolf w : wolves) {
                w.setTarget(le);
            }
        }
    }

    /**
     * Programme un "despawn" des loups dans 2 minutes si aucune nouvelle attaque entre-temps.
     */
    private void scheduleDespawn(Player player) {
        UUID uuid = player.getUniqueId();

        // Annule le timer précédent (s'il existe) pour le relancer
        if (despawnTasks.containsKey(uuid)) {
            despawnTasks.get(uuid).cancel();
        }

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> clearWolves(player), DESPAWN_DELAY);
        despawnTasks.put(uuid, task);
    }

    /**
     * Supprime physiquement les loups associés à ce joueur.
     */
    private void clearWolves(Player player) {
        UUID uuid = player.getUniqueId();

        // Retire la liste de loups depuis la map
        List<Wolf> wolves = activeWolves.remove(uuid);
        if (wolves != null) {
            for (Wolf w : wolves) {
                if (w != null && w.isValid()) {
                    w.remove(); // disparition sans animation de mort
                }
            }
        }

        // Retire la tâche associée
        BukkitTask despawnTask = despawnTasks.remove(uuid);
        if (despawnTask != null) {
            despawnTask.cancel();
        }
    }

    // ----------------------------------------------------------------------
    // CLEANUP (appelé si on disable le plugin, par ex.)
    // ----------------------------------------------------------------------
    public void stopArmorLoop() {
        // Arrête la boucle
        if (armorTask != null) {
            armorTask.cancel();
        }
        // Retire les buffs de tous les joueurs boostés
        for (UUID uuid : boostedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                removeEffects(p);
            }
        }
        boostedPlayers.clear();

        // Retire tous les loups
        for (UUID uuid : activeWolves.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                clearWolves(p);
            }
        }
        activeWolves.clear();
        despawnTasks.clear();
    }
}
