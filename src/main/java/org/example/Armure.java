package org.example;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /armure : donne l'Armure du roi GIDON et gère ses effets temporaires.
 */
public final class Armure implements CommandExecutor, Listener {

    private static final String USE_PERMISSION = "mineplugin.armure.use";
    private static final int GUARD_COUNT = 4;
    private static final long DESPAWN_DELAY = 2L * 60L * 20L;
    private static final int EFFECT_DURATION_TICKS = 60;
    private static final double GUARD_MAX_HEALTH = 40.0D;

    private static final String HELMET_ID = "helmet";
    private static final String CHEST_ID = "chestplate";
    private static final String LEGGING_ID = "leggings";
    private static final String BOOT_ID = "boots";

    private static final String HELMET_NAME = ChatColor.GOLD + "Casque du roi GIDON";
    private static final String CHEST_NAME = ChatColor.GOLD + "Plastron du roi GIDON";
    private static final String LEGGING_NAME = ChatColor.GOLD + "Pantalon du roi GIDON";
    private static final String BOOT_NAME = ChatColor.GOLD + "Chaussures du roi GIDON";

    private final JavaPlugin plugin;
    private final NamespacedKey armorPieceKey;

    private final Map<UUID, List<Wolf>> activeWolves = new HashMap<>();
    private final Map<UUID, BukkitTask> despawnTasks = new HashMap<>();
    private BukkitTask armorTask;

    public Armure(JavaPlugin plugin) {
        this.plugin = plugin;
        this.armorPieceKey = new NamespacedKey(plugin, "gidon-armor-piece");

        if (plugin.getCommand("armure") != null) {
            plugin.getCommand("armure").setExecutor(this);
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        startArmorLoop();
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Commande réservée aux joueurs.");
            return true;
        }
        if (!player.hasPermission(USE_PERMISSION)) {
            player.sendMessage(ChatColor.RED
                    + "Tu n'as pas la permission de recevoir cette armure.");
            return true;
        }

        giveArmor(player);
        player.sendMessage(ChatColor.GREEN
                + "Tu as reçu l'Armure du roi GIDON !");
        return true;
    }

    private void giveArmor(Player player) {
        giveOrDrop(
                player,
                createPiece(
                        Material.NETHERITE_HELMET,
                        HELMET_NAME,
                        HELMET_ID
                )
        );
        giveOrDrop(
                player,
                createPiece(
                        Material.NETHERITE_CHESTPLATE,
                        CHEST_NAME,
                        CHEST_ID
                )
        );
        giveOrDrop(
                player,
                createPiece(
                        Material.NETHERITE_LEGGINGS,
                        LEGGING_NAME,
                        LEGGING_ID
                )
        );
        giveOrDrop(
                player,
                createPiece(
                        Material.NETHERITE_BOOTS,
                        BOOT_NAME,
                        BOOT_ID
                )
        );
    }

    private void giveOrDrop(Player player, ItemStack item) {
        /*
         * addItem retourne les objets qui n'ont pas pu entrer dans l'inventaire.
         * Les déposer évite une perte silencieuse lorsque celui-ci est plein.
         */
        player.getInventory()
                .addItem(item)
                .values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(
                        player.getLocation(),
                        leftover
                ));
    }

    private ItemStack createPiece(
            Material material,
            String displayName,
            String pieceId) {
        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            meta.addEnchant(Enchantment.PROTECTION, 4, true);
            meta.addEnchant(Enchantment.UNBREAKING, 3, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);
            /*
             * Le marqueur PDC empêche une simple copie du nom à l'enclume
             * d'activer les pouvoirs de l'armure.
             */
            meta.getPersistentDataContainer().set(
                    armorPieceKey,
                    PersistentDataType.STRING,
                    pieceId
            );
            item.setItemMeta(meta);
        }
        return item;
    }

    private void startArmorLoop() {
        armorTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.hasPermission(USE_PERMISSION)
                                && hasFullArmor(player)) {
                            applyEffects(player);
                        }
                    }
                },
                20L,
                20L
        );
    }

    private void applyEffects(Player player) {
        /*
         * Les effets sont courts et renouvelés tant que l'ensemble est porté.
         * Ils expirent donc seuls après retrait sans supprimer un effet plus
         * long fourni par une potion, une balise ou un autre plugin.
         */
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.NIGHT_VISION,
                EFFECT_DURATION_TICKS,
                0,
                false,
                false
        ));
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.FIRE_RESISTANCE,
                EFFECT_DURATION_TICKS,
                0,
                false,
                false
        ));
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.WATER_BREATHING,
                EFFECT_DURATION_TICKS,
                0,
                false,
                false
        ));
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.HEALTH_BOOST,
                EFFECT_DURATION_TICKS,
                1,
                false,
                false
        ));
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.STRENGTH,
                EFFECT_DURATION_TICKS,
                1,
                false,
                false
        ));
    }

    private boolean hasFullArmor(Player player) {
        return isPiece(
                player.getInventory().getHelmet(),
                Material.NETHERITE_HELMET,
                HELMET_ID
        ) && isPiece(
                player.getInventory().getChestplate(),
                Material.NETHERITE_CHESTPLATE,
                CHEST_ID
        ) && isPiece(
                player.getInventory().getLeggings(),
                Material.NETHERITE_LEGGINGS,
                LEGGING_ID
        ) && isPiece(
                player.getInventory().getBoots(),
                Material.NETHERITE_BOOTS,
                BOOT_ID
        );
    }

    private boolean isPiece(
            ItemStack item,
            Material expectedMaterial,
            String expectedPieceId) {
        if (item == null || item.getType() != expectedMaterial) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        String pieceId = meta.getPersistentDataContainer().get(
                armorPieceKey,
                PersistentDataType.STRING
        );
        return expectedPieceId.equals(pieceId);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !player.hasPermission(USE_PERMISSION)
                || !hasFullArmor(player)) {
            return;
        }

        Entity attacker = event.getDamager();
        if (attacker instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter) {
            attacker = shooter;
        }

        /*
         * Un projectile du joueur ne doit jamais retourner ses propres gardes
         * contre lui.
         */
        if (attacker.getUniqueId().equals(player.getUniqueId())) {
            return;
        }

        spawnGuardWolves(player, attacker);
        scheduleDespawn(player.getUniqueId());
    }

    private void spawnGuardWolves(Player player, Entity target) {
        List<Wolf> wolves = activeWolves.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new ArrayList<>()
        );
        wolves.removeIf(wolf -> {
            boolean unusable = wolf.isDead()
                    || !wolf.isValid()
                    || !wolf.getWorld().getUID().equals(
                            player.getWorld().getUID()
                    );
            if (unusable && wolf.isValid()) {
                wolf.remove();
            }
            return unusable;
        });

        int missing = GUARD_COUNT - wolves.size();
        for (int index = 0; index < missing; index++) {
            Wolf wolf = null;
            try {
                wolf = player.getWorld().spawn(
                        player.getLocation().clone().add(
                                (index - 1) * 1.5D,
                                0.0D,
                                index % 2 == 0 ? 1.5D : -1.5D
                        ),
                        Wolf.class
                );
                /*
                 * La liste devient propriétaire de l'entité immédiatement :
                 * le bloc catch pourra toujours effectuer un rollback complet.
                 */
                wolves.add(wolf);
                wolf.setOwner(player);
                wolf.setCustomName(ChatColor.RED + "Garde du Roi");
                wolf.setAdult();
                wolf.setCollarColor(DyeColor.RED);
                /*
                 * Ces gardes sont transitoires : ils ne doivent pas survivre à
                 * un crash ou être sérialisés dans les chunks.
                 */
                wolf.setPersistent(false);

                AttributeInstance armor =
                        wolf.getAttribute(Attribute.ARMOR);
                if (armor != null) {
                    armor.setBaseValue(40.0D);
                }

                /*
                 * setHealth(40) lève une IllegalArgumentException sur un loup
                 * dont la vie maximale vanilla est encore à 20. On augmente
                 * donc d'abord l'attribut correspondant.
                 */
                AttributeInstance maximumHealth =
                        wolf.getAttribute(Attribute.MAX_HEALTH);
                if (maximumHealth != null) {
                    maximumHealth.setBaseValue(GUARD_MAX_HEALTH);
                    wolf.setHealth(Math.min(
                            GUARD_MAX_HEALTH,
                            maximumHealth.getValue()
                    ));
                }
            } catch (RuntimeException failure) {
                if (wolf != null) {
                    wolves.remove(wolf);
                    if (wolf.isValid()) {
                        wolf.remove();
                    }
                }
                plugin.getLogger().log(
                        java.util.logging.Level.WARNING,
                        "Impossible de créer un garde de l'Armure GIDON.",
                        failure
                );
                break;
            }
        }

        if (target instanceof LivingEntity livingTarget) {
            for (Wolf wolf : wolves) {
                wolf.setTarget(livingTarget);
            }
        }
    }

    private void scheduleDespawn(UUID ownerId) {
        BukkitTask previous = despawnTasks.remove(ownerId);
        if (previous != null) {
            previous.cancel();
        }

        BukkitTask task = Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> clearWolves(ownerId),
                DESPAWN_DELAY
        );
        despawnTasks.put(ownerId, task);
    }

    private void clearWolves(UUID ownerId) {
        List<Wolf> wolves = activeWolves.remove(ownerId);
        if (wolves != null) {
            for (Wolf wolf : wolves) {
                if (wolf.isValid()) {
                    wolf.remove();
                }
            }
        }

        BukkitTask task = despawnTasks.remove(ownerId);
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clearWolves(event.getPlayer().getUniqueId());
    }

    /**
     * Arrête les tâches et retire toutes les entités transitoires du module.
     */
    public void shutdown() {
        if (armorTask != null) {
            armorTask.cancel();
            armorTask = null;
        }

        for (BukkitTask task : new ArrayList<>(despawnTasks.values())) {
            task.cancel();
        }
        despawnTasks.clear();

        for (UUID ownerId : new ArrayList<>(activeWolves.keySet())) {
            clearWolves(ownerId);
        }
        activeWolves.clear();
    }
}
