package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.enchantments.Enchantment;


/**
 * Commande /armure : donne l'Armure du roi GIDON.
 * Le port complet de l'armure confère divers effets :
 *  - Night Vision
 *  - Fire Resistance
 *  - Water Breathing
 *  - Health Boost
 *  - Strength
 * Lorsqu'on enlève un ou plusieurs éléments de l'armure,
 * tous ces effets sont retirés.
 */
public final class Armure implements CommandExecutor {

    private final JavaPlugin plugin;
    private BukkitRunnable armorTask;

    // Liste des joueurs actuellement "boostés" (qui ont les effets)
    private final Set<UUID> boostedPlayers = new HashSet<>();

    // Noms personnalisés des pièces d'armure
    private static final String HELMET_NAME   = ChatColor.GOLD + "Casque du roi GIDON";
    private static final String CHEST_NAME    = ChatColor.GOLD + "Plastron du roi GIDON";
    private static final String LEGGING_NAME  = ChatColor.GOLD + "Pantalon du roi GIDON";
    private static final String BOOT_NAME     = ChatColor.GOLD + "Chaussures du roi GIDON";

    public Armure(JavaPlugin plugin) {
        this.plugin = plugin;

        // Lier la commande "/armure" à cette classe
        if (plugin.getCommand("armure") != null) {
            plugin.getCommand("armure").setExecutor(this);
        }

        // Démarrer la boucle de vérification régulière
        startArmorLoop();
    }

    /**
     * Gestion de la commande /armure.
     */
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

            // Enchantements standards :
            // PROTECTION_ENVIRONMENTAL => "Protection"
            // UNBREAKING => "Unbreaking"
            // MENDING => "Mending"
            meta.addEnchant(Enchantment.PROTECTION, 4, true);
            meta.addEnchant(Enchantment.UNBREAKING, 3, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);

            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Boucle qui vérifie périodiquement si un joueur
     * a l'armure complète ou non, et applique/enlève les buffs.
     */
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
                    // S'il a retiré l'armure mais qu'il est encore marqué "boosté"
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

    /**
     * Vérifie que l'ItemStack correspond à la bonne pièce
     * (null => faux, ou nom différent => faux).
     */
    private boolean isPiece(ItemStack item, String expectedName) {
        if (item == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && expectedName.equals(meta.getDisplayName());
    }

    /**
     * Méthode publique à appeler dans le onDisable() du plugin
     * pour stopper la boucle et nettoyer les effets sur les joueurs.
     */
    public void stopArmorLoop() {
        if (armorTask != null) {
            armorTask.cancel();
        }
        // Retire les effets de tous ceux qui étaient boostés
        for (UUID uuid : boostedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                removeEffects(p);
            }
        }
        boostedPlayers.clear();
    }
}
