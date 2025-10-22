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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * /armure : donne l’Armure du roi GIDON.
 */
public final class Armure implements CommandExecutor, Listener {

    private final JavaPlugin plugin;
    private BukkitRunnable   armorTask;

    /* ─────────────────────────── STOCKAGE ─────────────────────────── */
    private final Set<UUID> boostedPlayers          = new HashSet<>();
    private final Map<UUID, List<Wolf>> activeWolves = new HashMap<>();
    private final Map<UUID, BukkitTask> despawnTasks = new HashMap<>();

    /* ─────────────────────────── CONSTANTES ───────────────────────── */
    private static final int  GUARD_COUNT   = 4;
    private static final long DESPAWN_DELAY = 2L * 60L * 20L;

    private static final String HELMET_NAME  = ChatColor.GOLD + "Casque du roi GIDON";
    private static final String CHEST_NAME   = ChatColor.GOLD + "Plastron du roi GIDON";
    private static final String LEGGING_NAME = ChatColor.GOLD + "Pantalon du roi GIDON";
    private static final String BOOT_NAME    = ChatColor.GOLD + "Chaussures du roi GIDON";

    public Armure(JavaPlugin plugin) {
        this.plugin = plugin;

        if (plugin.getCommand("armure") != null)
            plugin.getCommand("armure").setExecutor(this);

        Bukkit.getPluginManager().registerEvents(this, plugin);
        startArmorLoop();
    }

    /* ─────────────────────────── COMMANDE ─────────────────────────── */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String lbl, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Commande réservée aux joueurs.");
            return true;
        }
        giveArmor(p);
        p.sendMessage(ChatColor.GREEN + "Tu as reçu l'Armure du roi GIDON !");
        return true;
    }

    private void giveArmor(Player p) {
        p.getInventory().addItem(createPiece(Material.NETHERITE_HELMET,     HELMET_NAME));
        p.getInventory().addItem(createPiece(Material.NETHERITE_CHESTPLATE, CHEST_NAME));
        p.getInventory().addItem(createPiece(Material.NETHERITE_LEGGINGS,   LEGGING_NAME));
        p.getInventory().addItem(createPiece(Material.NETHERITE_BOOTS,      BOOT_NAME));
    }

    private ItemStack createPiece(Material mat, String display) {
        ItemStack it = new ItemStack(mat, 1);
        ItemMeta  m  = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(display);
            m.addEnchant(Enchantment.PROTECTION, 4, true); // Protection IV
            m.addEnchant(Enchantment.UNBREAKING, 3, true); // Unbreaking III
            m.addEnchant(Enchantment.MENDING,                  1, true);
            it.setItemMeta(m);
        }
        return it;
    }

    /* ─────────────────────────── BOUCLE BUFFS ─────────────────────── */
    private void startArmorLoop() {
        armorTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    boolean full     = hasFullArmor(p);
                    boolean boosted  = boostedPlayers.contains(p.getUniqueId());
                    if (full && !boosted) {
                        applyEffects(p);
                        boostedPlayers.add(p.getUniqueId());
                    } else if (!full && boosted) {
                        removeEffects(p);
                        boostedPlayers.remove(p.getUniqueId());
                    }
                }
            }
        };
        armorTask.runTaskTimer(plugin, 20L, 20L);
    }

    private void applyEffects(Player p) {
        int d = 1_000_000;
        p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,    d, 0, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, d, 0, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, d, 0, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST,    d, 1, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,        d, 1, false, false));
    }

    private void removeEffects(Player p) {
        p.removePotionEffect(PotionEffectType.NIGHT_VISION);
        p.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
        p.removePotionEffect(PotionEffectType.WATER_BREATHING);
        p.removePotionEffect(PotionEffectType.HEALTH_BOOST);
        p.removePotionEffect(PotionEffectType.STRENGTH);
    }

    private boolean hasFullArmor(Player p) {
        ItemStack h = p.getInventory().getHelmet();
        ItemStack c = p.getInventory().getChestplate();
        ItemStack l = p.getInventory().getLeggings();
        ItemStack b = p.getInventory().getBoots();
        return isPiece(h, HELMET_NAME) && isPiece(c, CHEST_NAME)
                && isPiece(l, LEGGING_NAME) && isPiece(b, BOOT_NAME);
    }

    private boolean isPiece(ItemStack it, String name) {
        return it != null && it.getItemMeta() != null
                && name.equals(it.getItemMeta().getDisplayName());
    }

    /* ────────────────────── ATTAQUES / LOUPS ─────────────────────── */
    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!hasFullArmor(p) || e.isCancelled()) return;

        Entity damager = e.getDamager();
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Entity shooter)
            damager = shooter;

        spawnGuardWolves(p, damager);
        scheduleDespawn(p);
    }

    private void spawnGuardWolves(Player p, Entity target) {
        List<Wolf> list = activeWolves.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        list.removeIf(w -> w.isDead() || !w.isValid());

        int need = GUARD_COUNT - list.size();
        for (int i = 0; i < need; i++) {
            Wolf w = p.getWorld().spawn(p.getLocation().add((i - 1) * 1.5, 0, (i % 2 == 0 ? 1.5 : -1.5)), Wolf.class);
            w.setOwner(p);
            w.setCustomName(ChatColor.RED + "Garde du Roi");
            w.setAdult();
            if (w.getAttribute(Attribute.ARMOR) != null)
                w.getAttribute(Attribute.ARMOR).setBaseValue(40.0);
            w.setHealth(40.0);
            w.setCollarColor(DyeColor.RED);
            list.add(w);
        }

        if (target instanceof LivingEntity le) list.forEach(w -> w.setTarget(le));
    }

    private void scheduleDespawn(Player p) {
        UUID id = p.getUniqueId();
        despawnTasks.computeIfPresent(id, (k, t) -> { t.cancel(); return null; });
        BukkitTask t = Bukkit.getScheduler().runTaskLater(plugin, () -> clearWolves(p), DESPAWN_DELAY);
        despawnTasks.put(id, t);
    }

    private void clearWolves(Player p) {
        List<Wolf> list = activeWolves.remove(p.getUniqueId());
        if (list != null) list.forEach(w -> { if (w.isValid()) w.remove(); });
        BukkitTask t = despawnTasks.remove(p.getUniqueId());
        if (t != null) t.cancel();
    }
}
