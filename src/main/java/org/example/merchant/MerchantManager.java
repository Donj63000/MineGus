package org.example.merchant;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Gestionnaire du marchand personnalisé (/marchand).
 */
public final class MerchantManager implements CommandExecutor, Listener {

    private static final int MENU_SIZE = 54;
    private static final int OFFERS_PER_PAGE = 45;

    private final JavaPlugin plugin;
    private final NamespacedKey traderKey;
    private final NamespacedKey itemKey;
    private final File merchantFile;

    private String merchantId = "minegus_trader";
    private String merchantDisplayName = ChatColor.GOLD + "Marchand";
    private Sound tradeSound = Sound.ENTITY_VILLAGER_YES;
    private boolean logTrades = false;

    private final List<MerchantCategory> orderedCategories = new ArrayList<>();
    private final Map<String, MerchantCategory> categoriesById = new LinkedHashMap<>();
    private final Map<String, ResolvedOffer> offersById = new HashMap<>();

    public MerchantManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.traderKey = new NamespacedKey(plugin, "marchand_npc");
        this.itemKey = new NamespacedKey(plugin, "marchand_item");
        if (!plugin.getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
        }
        this.merchantFile = new File(plugin.getDataFolder(), "marchand.yaml");
        ensureDefaultFile();
        reloadDefinition();
        registerCommand();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        discoverExistingMerchants();
    }

    private void ensureDefaultFile() {
        if (!merchantFile.exists()) {
            plugin.saveResource("marchand.yaml", false);
        }
    }

    private void registerCommand() {
        if (plugin.getCommand("marchand") != null) {
            plugin.getCommand("marchand").setExecutor(this);
        } else {
            plugin.getLogger().warning("La commande /marchand est absente du plugin.yml");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Cette commande doit être exécutée en jeu.");
            return true;
        }
        if (!player.hasPermission("mineplugin.marchand.spawn")) {
            player.sendMessage(ChatColor.RED + "Tu n'as pas la permission pour /marchand.");
            return true;
        }
        spawnMerchant(player);
        return true;
    }

    private void spawnMerchant(Player player) {
        Villager villager = (Villager) player.getWorld().spawnEntity(player.getLocation(), EntityType.VILLAGER);
        prepareMerchantNpc(villager);
        player.sendMessage(ChatColor.GREEN + "Marchand invoqué. Clique-le pour échanger !");
    }

    private void prepareMerchantNpc(Villager villager) {
        PersistentDataContainer data = villager.getPersistentDataContainer();
        data.set(traderKey, PersistentDataType.STRING, merchantId);
        villager.setCustomName(merchantDisplayName);
        villager.setCustomNameVisible(true);
        villager.setAI(false);
        villager.setInvulnerable(true);
        villager.setCollidable(false);
        villager.setRemoveWhenFarAway(false);
        villager.setPersistent(true);
        villager.setSilent(true);
        villager.setVillagerLevel(5);
        villager.setProfession(Villager.Profession.NITWIT);
        villager.getInventory().clear();
    }

    private void reloadDefinition() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(merchantFile);
        ConfigurationSection merchantSection = yaml.getConfigurationSection("merchant");
        if (merchantSection != null) {
            this.merchantId = merchantSection.getString("id", merchantId);
            this.merchantDisplayName = ChatColor.GOLD + merchantSection.getString("display_name", "Marchand");
        }
        ConfigurationSection rules = yaml.getConfigurationSection("rules");
        if (rules != null) {
            this.logTrades = rules.getBoolean("log_trades", false);
            String soundName = rules.getString("sound_on_trade", "ENTITY_VILLAGER_YES");
            try {
                this.tradeSound = Sound.valueOf(soundName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Son inconnu pour le marchand: " + soundName);
                this.tradeSound = Sound.ENTITY_VILLAGER_YES;
            }
        }
        categoriesById.clear();
        offersById.clear();
        orderedCategories.clear();
        MaterialResolver resolver = new MaterialResolver();
        ConfigurationSection categoriesSection = yaml.getConfigurationSection("categories");
        if (categoriesSection == null) {
            plugin.getLogger().warning("Aucune catégorie définie dans marchand.yaml");
            return;
        }
        for (String id : categoriesSection.getKeys(false)) {
            ConfigurationSection catSection = categoriesSection.getConfigurationSection(id);
            if (catSection == null) continue;
            MerchantCategory category = parseCategory(id, catSection, resolver);
            if (category != null) {
                categoriesById.put(id, category);
            }
        }
        List<String> order = Collections.emptyList();
        ConfigurationSection ui = merchantSection != null ? merchantSection.getConfigurationSection("ui") : null;
        if (ui != null) {
            order = ui.getStringList("categories_order");
        }
        if (order != null) {
            for (String id : order) {
                MerchantCategory category = categoriesById.get(id);
                if (category != null) {
                    orderedCategories.add(category);
                }
            }
        }
        for (MerchantCategory category : categoriesById.values()) {
            if (!orderedCategories.contains(category)) {
                orderedCategories.add(category);
            }
        }
    }

    private MerchantCategory parseCategory(String id, ConfigurationSection section, MaterialResolver resolver) {
        String iconName = section.getString("icon", "BARRIER");
        Material icon = MaterialResolver.matchMaterial(iconName);
        if (icon == null) {
            plugin.getLogger().warning("Icône invalide pour la catégorie " + id + " : " + iconName);
            icon = Material.BARRIER;
        }
        String description = section.getString("description", "");
        IngredientDescriptor defaultInput = parseCategoryInput(section.getConfigurationSection("input"), resolver);
        List<Map<?, ?>> offerMaps = section.getMapList("offers");
        MerchantCategory category = new MerchantCategory(id, icon, description, defaultInput);
        AtomicInteger counter = new AtomicInteger();
        for (Map<?, ?> raw : offerMaps) {
            OfferDefinition definition = OfferDefinition.fromMap(raw);
            if (definition == null) {
                plugin.getLogger().warning("Offre invalide dans " + id + " (données manquantes)");
                continue;
            }
            List<IngredientRequirement> requirements = definition.buildRequirements(resolver, defaultInput);
            if (requirements.isEmpty()) {
                plugin.getLogger().warning("Offre ignorée (entrée introuvable) dans " + id);
                continue;
            }
            List<Material> outputs = resolver.resolveToList(definition.getOutToken());
            if (outputs.isEmpty()) {
                plugin.getLogger().warning("Offre ignorée (sortie introuvable) dans " + id + " : " + definition.getOutToken());
                continue;
            }
            for (Material mat : outputs) {
                String offerId = id + ":" + counter.getAndIncrement();
                ResolvedOffer offer = new ResolvedOffer(offerId, category, requirements, mat, definition.getOutQuantity(), definition.getNote());
                category.offers.add(offer);
                offersById.put(offerId, offer);
            }
        }
        return category;
    }

    private IngredientDescriptor parseCategoryInput(@Nullable ConfigurationSection inputSection, MaterialResolver resolver) {
        if (inputSection == null) {
            return null;
        }
        String type = inputSection.getString("type", "MATERIAL").toUpperCase(Locale.ROOT);
        String key = inputSection.getString("key");
        if (key == null || key.isEmpty()) {
            return null;
        }
        String token = switch (type) {
            case "TAG" -> "TAG:" + key;
            case "ALIAS" -> "ALIAS:" + key;
            default -> key;
        };
        return IngredientDescriptor.fromToken(token, resolver);
    }

    private void discoverExistingMerchants() {
        for (World world : Bukkit.getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                if (isMerchant(villager)) {
                    prepareMerchantNpc(villager);
                }
            }
        }
    }

    private boolean isMerchant(Entity entity) {
        if (!(entity instanceof Villager villager)) {
            return false;
        }
        PersistentDataContainer data = villager.getPersistentDataContainer();
        return merchantId.equals(data.get(traderKey, PersistentDataType.STRING));
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (handleMerchantInteraction(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (handleMerchantInteraction(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    private boolean handleMerchantInteraction(Player player, Entity clicked) {
        if (!isMerchant(clicked)) {
            return false;
        }
        openCategoryMenu(player);
        return true;
    }

    private void openCategoryMenu(Player player) {
        MenuHolder holder = new MenuHolder(MenuType.CATEGORIES, null, 0);
        Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, merchantDisplayName + " - Catégories");
        holder.setInventory(inv);
        for (int i = 0; i < orderedCategories.size() && i < MENU_SIZE; i++) {
            MerchantCategory category = orderedCategories.get(i);
            inv.setItem(i, buildCategoryItem(category));
        }
        player.openInventory(inv);
    }

    private ItemStack buildCategoryItem(MerchantCategory category) {
        ItemStack item = new ItemStack(category.icon);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + formatId(category.id));
        List<String> lore = new ArrayList<>();
        if (!category.description.isEmpty()) {
            lore.addAll(wrapText(ChatColor.GRAY + category.description, 40));
            lore.add("");
        }
        lore.add(ChatColor.YELLOW + "Offres: " + category.offers.size());
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "cat:" + category.id);
        item.setItemMeta(meta);
        return item;
    }

    private void openOfferMenu(Player player, MerchantCategory category, int page) {
        int maxPages = Math.max(1, (int) Math.ceil(category.offers.size() / (double) OFFERS_PER_PAGE));
        if (page < 0) page = 0;
        if (page >= maxPages) page = maxPages - 1;
        MenuHolder holder = new MenuHolder(MenuType.OFFERS, category.id, page);
        Inventory inv = Bukkit.createInventory(holder, MENU_SIZE,
                merchantDisplayName + " - " + formatId(category.id) + " (" + (page + 1) + "/" + maxPages + ")");
        holder.setInventory(inv);
        int startIndex = page * OFFERS_PER_PAGE;
        for (int slot = 0; slot < OFFERS_PER_PAGE; slot++) {
            int offerIndex = startIndex + slot;
            if (offerIndex >= category.offers.size()) {
                break;
            }
            ResolvedOffer offer = category.offers.get(offerIndex);
            inv.setItem(slot, buildOfferItem(offer));
        }
        inv.setItem(45, createNavItem(Material.BARRIER, ChatColor.RED + "Retour", "nav:back"));
        if (page > 0) {
            inv.setItem(48, createNavItem(Material.ARROW, ChatColor.YELLOW + "Page précédente", "nav:prev"));
        }
        if (page < maxPages - 1) {
            inv.setItem(50, createNavItem(Material.ARROW, ChatColor.YELLOW + "Page suivante", "nav:next"));
        }
        player.openInventory(inv);
    }

    private ItemStack createNavItem(Material material, String name, String key) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, key);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildOfferItem(ResolvedOffer offer) {
        ItemStack item = new ItemStack(offer.outputMaterial);
        int amount = Math.min(offer.outputAmount, item.getMaxStackSize());
        item.setAmount(Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + formatMaterialName(offer.outputMaterial));
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GOLD + "Résultat : " + offer.outputAmount + " x " + formatMaterialName(offer.outputMaterial));
        lore.add("");
        lore.add(ChatColor.GRAY + "Coût :");
        for (IngredientRequirement requirement : offer.requirements) {
            lore.add(ChatColor.YELLOW + " - " + requirement.amount + " x " + requirement.descriptor.displayName);
        }
        if (offer.note != null && !offer.note.isEmpty()) {
            lore.add("");
            lore.add(ChatColor.DARK_AQUA + offer.note);
        }
        lore.add("");
        lore.add(ChatColor.GREEN + "Clique pour échanger");
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "offer:" + offer.id);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        ItemStack current = event.getCurrentItem();
        if (current == null || !current.hasItemMeta()) {
            return;
        }
        String payload = current.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        if (payload == null) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (payload.startsWith("cat:")) {
            String categoryId = payload.substring(4);
            MerchantCategory category = categoriesById.get(categoryId);
            if (category != null) {
                openOfferMenu(player, category, 0);
            }
            return;
        }
        if (payload.startsWith("offer:")) {
            String offerId = payload.substring(6);
            ResolvedOffer offer = offersById.get(offerId);
            if (offer != null) {
                attemptTrade(player, offer);
            }
            return;
        }
        switch (payload) {
            case "nav:back" -> openCategoryMenu(player);
            case "nav:prev" -> {
                MerchantCategory category = categoriesById.get(holder.categoryId);
                if (category != null) {
                    openOfferMenu(player, category, holder.page - 1);
                }
            }
            case "nav:next" -> {
                MerchantCategory category = categoriesById.get(holder.categoryId);
                if (category != null) {
                    openOfferMenu(player, category, holder.page + 1);
                }
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    private void attemptTrade(Player player, ResolvedOffer offer) {
        if (!hasIngredients(player.getInventory(), offer.requirements)) {
            player.sendMessage(ChatColor.RED + "Ingrédients insuffisants.");
            return;
        }
        removeIngredients(player.getInventory(), offer.requirements);
        giveOutputs(player, offer);
        player.playSound(player.getLocation(), tradeSound, 1f, 1f);
        if (logTrades) {
            plugin.getLogger().info(player.getName() + " a échangé contre " + offer.outputAmount + "x " + offer.outputMaterial);
        }
    }

    private boolean hasIngredients(PlayerInventory inventory, List<IngredientRequirement> requirements) {
        ItemStack[] contents = inventory.getContents();
        int[] toRemove = new int[contents.length];
        for (IngredientRequirement requirement : requirements) {
            int remaining = requirement.amount;
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack stack = contents[slot];
                if (stack == null) continue;
                if (!requirement.descriptor.matches(stack.getType())) continue;
                int available = stack.getAmount() - toRemove[slot];
                if (available <= 0) continue;
                int take = Math.min(available, remaining);
                toRemove[slot] += take;
                remaining -= take;
                if (remaining <= 0) break;
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    private void removeIngredients(PlayerInventory inventory, List<IngredientRequirement> requirements) {
        ItemStack[] contents = inventory.getContents();
        int[] toRemove = new int[contents.length];
        for (IngredientRequirement requirement : requirements) {
            int remaining = requirement.amount;
            for (int slot = 0; slot < contents.length; slot++) {
                ItemStack stack = contents[slot];
                if (stack == null) continue;
                if (!requirement.descriptor.matches(stack.getType())) continue;
                int available = stack.getAmount() - toRemove[slot];
                if (available <= 0) continue;
                int take = Math.min(available, remaining);
                toRemove[slot] += take;
                remaining -= take;
                if (remaining <= 0) break;
            }
        }
        for (int slot = 0; slot < contents.length; slot++) {
            int take = toRemove[slot];
            if (take <= 0) continue;
            ItemStack stack = contents[slot];
            if (stack == null) continue;
            int newAmount = stack.getAmount() - take;
            if (newAmount <= 0) {
                inventory.clear(slot);
            } else {
                stack.setAmount(newAmount);
            }
        }
    }

    private void giveOutputs(Player player, ResolvedOffer offer) {
        int remaining = offer.outputAmount;
        while (remaining > 0) {
            int give = Math.min(remaining, offer.outputMaterial.getMaxStackSize());
            ItemStack stack = new ItemStack(offer.outputMaterial, give);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            if (!leftover.isEmpty()) {
                leftover.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            }
            remaining -= give;
        }
    }

    @EventHandler
    public void onMerchantDamage(EntityDamageEvent event) {
        if (isMerchant(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) {
                player.closeInventory();
            }
        }
    }

    private static List<String> wrapText(String text, int lineLength) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.length() + word.length() + 1 > lineLength) {
                lines.add(current.toString());
                current = new StringBuilder();
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static String formatId(String id) {
        return Arrays.stream(id.split("[_-]"))
                .filter(s -> !s.isEmpty())
                .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
    }

    private static String formatMaterialName(Material material) {
        return Arrays.stream(material.name().split("_"))
                .map(word -> word.substring(0, 1) + word.substring(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
    }

    private static final class MerchantCategory {
        private final String id;
        private final Material icon;
        private final String description;
        private final IngredientDescriptor defaultInput;
        private final List<ResolvedOffer> offers = new ArrayList<>();

        private MerchantCategory(String id, Material icon, String description, IngredientDescriptor defaultInput) {
            this.id = id;
            this.icon = icon;
            this.description = description == null ? "" : description;
            this.defaultInput = defaultInput;
        }
    }

    private static final class ResolvedOffer {
        private final String id;
        private final MerchantCategory category;
        private final List<IngredientRequirement> requirements;
        private final Material outputMaterial;
        private final int outputAmount;
        private final String note;

        private ResolvedOffer(String id, MerchantCategory category, List<IngredientRequirement> requirements,
                              Material outputMaterial, int outputAmount, String note) {
            this.id = id;
            this.category = category;
            this.requirements = requirements;
            this.outputMaterial = outputMaterial;
            this.outputAmount = outputAmount;
            this.note = note;
        }
    }

    private static final class IngredientRequirement {
        private final IngredientDescriptor descriptor;
        private final int amount;

        private IngredientRequirement(IngredientDescriptor descriptor, int amount) {
            this.descriptor = descriptor;
            this.amount = Math.max(1, amount);
        }
    }

    private static final class IngredientDescriptor {
        private final Set<Material> materials;
        private final String displayName;

        private IngredientDescriptor(Set<Material> materials) {
            this.materials = Collections.unmodifiableSet(materials);
            this.displayName = buildDisplayName(materials);
        }

        private static IngredientDescriptor fromToken(String token, MaterialResolver resolver) {
            List<Material> materials = resolver.resolveToList(token);
            if (materials.isEmpty()) {
                return null;
            }
            return new IngredientDescriptor(new LinkedHashSet<>(materials));
        }

        private boolean matches(Material material) {
            return materials.contains(material);
        }

        private static String buildDisplayName(Set<Material> materials) {
            if (materials.isEmpty()) {
                return "?";
            }
            if (materials.size() == 1) {
                return formatMaterialName(materials.iterator().next());
            }
            List<String> names = materials.stream()
                    .map(MerchantManager::formatMaterialName)
                    .limit(3)
                    .collect(Collectors.toList());
            String label = String.join(", ", names);
            if (materials.size() > 3) {
                label += " +" + (materials.size() - 3) + " autres";
            }
            return label;
        }
    }

    private enum MenuType {
        CATEGORIES,
        OFFERS
    }

    private static final class MenuHolder implements InventoryHolder {
        private final MenuType type;
        private final String categoryId;
        private final int page;
        private Inventory inventory;

        private MenuHolder(MenuType type, String categoryId, int page) {
            this.type = type;
            this.categoryId = categoryId;
            this.page = page;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class OfferDefinition {
        private final String outToken;
        private final int outQuantity;
        private final String inToken;
        private final int inQuantity;
        private final Map<String, Integer> combo;
        private final String note;

        private OfferDefinition(String outToken, int outQuantity, String inToken, int inQuantity,
                                Map<String, Integer> combo, String note) {
            this.outToken = outToken;
            this.outQuantity = outQuantity;
            this.inToken = inToken;
            this.inQuantity = inQuantity;
            this.combo = combo;
            this.note = note;
        }

        static OfferDefinition fromMap(Map<?, ?> raw) {
            if (raw == null) return null;
            Object out = raw.get("out");
            if (out == null) {
                return null;
            }
            String outToken = out.toString();
            int outQty = toInt(raw.get("out_qty"), 1);
            String inToken = raw.containsKey("in") ? Objects.toString(raw.get("in"), null) : null;
            int inQty = toInt(raw.get("in_qty"), 1);
            Map<String, Integer> combo = new LinkedHashMap<>();
            Object comboObj = raw.get("in_combo");
            if (comboObj instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String key = entry.getKey().toString();
                    int value = toInt(entry.getValue(), 0);
                    combo.put(key, value);
                }
            }
            String note = raw.containsKey("note") ? Objects.toString(raw.get("note"), null) : null;
            return new OfferDefinition(outToken, outQty, inToken, inQty, combo, note);
        }

        private static int toInt(Object obj, int def) {
            if (obj instanceof Number number) {
                return number.intValue();
            }
            if (obj != null) {
                try {
                    return Integer.parseInt(obj.toString());
                } catch (NumberFormatException ignored) {
                }
            }
            return def;
        }

        public String getOutToken() {
            return outToken;
        }

        public int getOutQuantity() {
            return Math.max(1, outQuantity);
        }

        public String getInToken() {
            return inToken;
        }

        public int getInQuantity() {
            return Math.max(1, inQuantity);
        }

        public Map<String, Integer> getCombo() {
            return combo;
        }

        public String getNote() {
            return note;
        }

        public List<IngredientRequirement> buildRequirements(MaterialResolver resolver, @Nullable IngredientDescriptor categoryInput) {
            if (!combo.isEmpty()) {
                List<IngredientRequirement> requirements = new ArrayList<>();
                for (Map.Entry<String, Integer> entry : combo.entrySet()) {
                    IngredientDescriptor descriptor = IngredientDescriptor.fromToken(entry.getKey(), resolver);
                    if (descriptor != null && entry.getValue() > 0) {
                        requirements.add(new IngredientRequirement(descriptor, entry.getValue()));
                    }
                }
                return requirements;
            }
            IngredientDescriptor descriptor;
            if (inToken != null) {
                descriptor = IngredientDescriptor.fromToken(inToken, resolver);
            } else {
                descriptor = categoryInput;
            }
            if (descriptor == null) {
                return Collections.emptyList();
            }
            return List.of(new IngredientRequirement(descriptor, getInQuantity()));
        }
    }

    private static final class MaterialResolver {
        private final Map<String, Set<Material>> aliasCache = new HashMap<>();

        List<Material> resolveToList(String token) {
            if (token == null) {
                return Collections.emptyList();
            }
            token = token.trim();
            if (token.startsWith("TAG:")) {
                String name = token.substring(4).toUpperCase(Locale.ROOT);
                return new ArrayList<>(resolveTag(name));
            }
            if (token.startsWith("ALIAS:")) {
                String alias = token.substring(6).toUpperCase(Locale.ROOT);
                return new ArrayList<>(resolveAlias(alias));
            }
            Material material = matchMaterial(token);
            if (material != null) {
                return List.of(material);
            }
            return Collections.emptyList();
        }

        static Material matchMaterial(String name) {
            if (name == null || name.isEmpty()) {
                return null;
            }
            String upper = name.toUpperCase(Locale.ROOT);
            try {
                return Material.valueOf(upper);
            } catch (IllegalArgumentException ignored) {
            }
            try {
                return Material.matchMaterial(name);
            } catch (IllegalArgumentException ignored) {
            }
            return null;
        }

        private Set<Material> resolveTag(String name) {
            try {
                Field field = Tag.class.getField(name);
                @SuppressWarnings("unchecked") Tag<Material> tag = (Tag<Material>) field.get(null);
                return new LinkedHashSet<>(tag.getValues());
            } catch (NoSuchFieldException | IllegalAccessException ignored) {
            }
            return switch (name) {
                case "ALL_SIGNS" -> filter(material -> material.name().endsWith("_SIGN") && !material.name().contains("HANGING"));
                case "ALL_HANGING_SIGNS" -> filter(material -> material.name().endsWith("_HANGING_SIGN"));
                default -> Collections.emptySet();
            };
        }

        private Set<Material> resolveAlias(String alias) {
            return aliasCache.computeIfAbsent(alias, this::createAliasSet);
        }

        private Set<Material> createAliasSet(String alias) {
            return switch (alias) {
                case "ANY_STAINED_GLASS" -> filter(material -> material.name().endsWith("_STAINED_GLASS"));
                case "ANY_CONCRETE" -> filter(material -> material.name().endsWith("_CONCRETE") && !material.name().endsWith("CONCRETE_POWDER"));
                case "ANY_TERRACOTTA" -> filter(material -> material == Material.TERRACOTTA || material.name().endsWith("_TERRACOTTA"));
                case "ANY_GLAZED_TERRACOTTA" -> filter(material -> material.name().endsWith("_GLAZED_TERRACOTTA"));
                case "ANY_CUT_COPPER" -> filter(material -> material.name().contains("CUT_COPPER"));
                case "ANY_COPPER_BULB" -> filter(material -> material.name().endsWith("COPPER_BULB"));
                case "ANY_CARPET" -> filter(material -> material.name().endsWith("_CARPET"));
                case "ANY_WOOL" -> filter(material -> material.name().endsWith("_WOOL"));
                default -> Collections.emptySet();
            };
        }

        private Set<Material> filter(java.util.function.Predicate<Material> predicate) {
            EnumSet<Material> set = EnumSet.noneOf(Material.class);
            for (Material material : Material.values()) {
                if (predicate.test(material)) {
                    set.add(material);
                }
            }
            return set;
        }
    }
}
