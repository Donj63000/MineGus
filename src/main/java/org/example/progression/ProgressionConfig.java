package org.example.progression;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Configuration immuable du niveau global.
 *
 * <p>Les valeurs par défaut sont aussi présentes dans le code afin que le
 * système fonctionne immédiatement sur un serveur possédant déjà un ancien
 * {@code config.yml}. En effet, {@code saveDefaultConfig()} ne fusionne pas
 * automatiquement les nouvelles clés dans un fichier existant.</p>
 */
final class ProgressionConfig {

    static final int ABSOLUTE_MAX_LEVEL = 100;

    final boolean enabled;
    final int maxLevel;

    final double healthPercentPerLevel;
    final double oxygenPercentPerLevel;
    final double damagePercentPerLevel;
    final double miningSpeedPercentPerLevel;

    final boolean sidebarOnGain;
    final boolean actionbarFallback;
    final boolean titleOnLevelUp;
    final boolean soundOnLevelUp;
    final long hudHideDelayTicks;
    final int progressBarSegments;

    final long maxSingleAward;
    final long maxXpPerMinute;
    final Set<String> disabledWorlds;

    final boolean requirePreferredTool;
    final boolean requireBlockDrops;
    final boolean trackPlacedBlocks;
    final int placedBlocksMaxPerChunk;
    final long placedBlocksFlushTicks;
    final Map<Material, Integer> blockXp;
    final Map<Material, Integer> matureCropXp;

    final int monsterXp;
    final int animalXp;
    final int playerKillXp;
    final int otherEntityXp;
    final Map<EntityType, Integer> entityXp;
    final Map<String, Double> spawnReasonMultipliers;

    final int caughtFishXp;
    final int caughtEntityXp;
    final int breedingXp;
    final int tamingXp;
    final int tradeXp;
    final int shearingXp;

    final int enchantingBaseXp;
    final double enchantingXpPerCostLevel;

    final double smeltingXpPerItem;
    final double smeltingXpPerVanillaXp;
    final int smeltingMaxXpPerExtraction;

    final int advancementDefaultXp;
    final boolean ignoreRecipeAdvancements;
    final Map<String, Integer> advancementXp;

    private final long xpBase;
    private final long xpLinear;
    private final long xpQuadratic;
    private final long[] totalXpByLevel;
    private final long[] xpRequiredByLevel;

    private ProgressionConfig(JavaPlugin plugin) {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("progression");

        this.enabled = getBoolean(root, "enabled", true);
        this.maxLevel = clamp(getInt(root, "max-level", ABSOLUTE_MAX_LEVEL), 2, ABSOLUTE_MAX_LEVEL);

        this.xpBase = clamp(getLong(root, "xp-levelup.base", 200L), 1L, 1_000_000_000L);
        this.xpLinear = clamp(getLong(root, "xp-levelup.linear", 50L), 0L, 1_000_000_000L);
        this.xpQuadratic = clamp(getLong(root, "xp-levelup.quadratic", 4L), 0L, 1_000_000_000L);

        this.healthPercentPerLevel = clamp(getDouble(root,
                "bonuses.health-percent-per-level", 1.0D), 0.0D, 10.0D);
        this.oxygenPercentPerLevel = clamp(getDouble(root,
                "bonuses.oxygen-percent-per-level", 1.0D), 0.0D, 10.0D);
        this.damagePercentPerLevel = clamp(getDouble(root,
                "bonuses.damage-percent-per-level", 1.0D), 0.0D, 10.0D);
        this.miningSpeedPercentPerLevel = clamp(getDouble(root,
                "bonuses.mining-speed-percent-per-level", 1.0D), 0.0D, 10.0D);

        this.sidebarOnGain = getBoolean(root, "display.sidebar-on-gain", true);
        this.actionbarFallback = getBoolean(root, "display.actionbar-fallback", true);
        this.titleOnLevelUp = getBoolean(root, "display.title-on-levelup", true);
        this.soundOnLevelUp = getBoolean(root, "display.sound-on-levelup", true);
        this.hudHideDelayTicks = clamp(getLong(root,
                "display.hud-hide-delay-seconds", 4L), 1L, 60L) * 20L;
        this.progressBarSegments = clamp(getInt(root,
                "display.progress-bar-segments", 10), 5, 20);

        this.maxSingleAward = clamp(getLong(root,
                "anti-abuse.max-single-award", 2_000L), 1L, 1_000_000L);
        this.maxXpPerMinute = clamp(getLong(root,
                "anti-abuse.max-xp-per-minute", 5_000L), 0L, 100_000_000L);
        this.disabledWorlds = loadWorldSet(root, "anti-abuse.disabled-worlds");

        this.requirePreferredTool = getBoolean(root,
                "sources.blocks.require-preferred-tool", true);
        this.requireBlockDrops = getBoolean(root,
                "sources.blocks.require-drops", true);
        this.trackPlacedBlocks = getBoolean(root,
                "sources.blocks.track-placed-blocks", true);
        this.placedBlocksMaxPerChunk = clamp(getInt(root,
                "sources.blocks.placed-blocks-max-per-chunk", 16_384), 256, 65_536);
        this.placedBlocksFlushTicks = clamp(getLong(root,
                "sources.blocks.placed-blocks-flush-seconds", 5L), 1L, 300L) * 20L;

        this.blockXp = loadMaterialXpMap(
                plugin,
                root,
                "sources.blocks.xp-per-block",
                defaultBlockXp());
        this.matureCropXp = loadMaterialXpMap(
                plugin,
                root,
                "sources.farming.xp-per-mature-crop",
                defaultCropXp());

        this.monsterXp = clamp(getInt(root,
                "sources.combat.fallback.monster", 10), 0, 100_000);
        this.animalXp = clamp(getInt(root,
                "sources.combat.fallback.animal", 3), 0, 100_000);
        this.playerKillXp = clamp(getInt(root,
                "sources.combat.fallback.player", 0), 0, 100_000);
        this.otherEntityXp = clamp(getInt(root,
                "sources.combat.fallback.other", 0), 0, 100_000);
        this.entityXp = loadEntityXpMap(
                plugin,
                root,
                "sources.combat.xp-per-entity",
                defaultEntityXp());
        this.spawnReasonMultipliers = loadStringDoubleMap(
                plugin,
                root,
                "sources.combat.spawn-reason-multipliers",
                defaultSpawnReasonMultipliers(),
                0.0D,
                10.0D);

        this.caughtFishXp = clamp(getInt(root,
                "sources.fishing.caught-fish-xp", 5), 0, 100_000);
        this.caughtEntityXp = clamp(getInt(root,
                "sources.fishing.caught-entity-xp", 2), 0, 100_000);
        this.breedingXp = clamp(getInt(root,
                "sources.breeding.xp", 8), 0, 100_000);
        this.tamingXp = clamp(getInt(root,
                "sources.taming.xp", 20), 0, 100_000);
        this.tradeXp = clamp(getInt(root,
                "sources.trading.xp", 4), 0, 100_000);
        this.shearingXp = clamp(getInt(root,
                "sources.shearing.xp", 3), 0, 100_000);

        this.enchantingBaseXp = clamp(getInt(root,
                "sources.enchanting.base-xp", 5), 0, 100_000);
        this.enchantingXpPerCostLevel = clamp(getDouble(root,
                "sources.enchanting.xp-per-cost-level", 0.5D), 0.0D, 1_000.0D);

        this.smeltingXpPerItem = clamp(getDouble(root,
                "sources.smelting.xp-per-item", 0.25D), 0.0D, 1_000.0D);
        this.smeltingXpPerVanillaXp = clamp(getDouble(root,
                "sources.smelting.xp-per-vanilla-xp", 1.0D), 0.0D, 1_000.0D);
        this.smeltingMaxXpPerExtraction = clamp(getInt(root,
                "sources.smelting.max-xp-per-extraction", 100), 0, 100_000);

        this.advancementDefaultXp = clamp(getInt(root,
                "sources.advancements.default-xp", 50), 0, 100_000);
        this.ignoreRecipeAdvancements = getBoolean(root,
                "sources.advancements.ignore-recipes", true);
        this.advancementXp = loadStringIntMap(
                plugin,
                root,
                "sources.advancements.xp-per-advancement",
                defaultAdvancementXp());

        this.totalXpByLevel = new long[maxLevel + 1];
        this.xpRequiredByLevel = new long[maxLevel + 1];
        buildCurve();
    }

    static ProgressionConfig load(JavaPlugin plugin) {
        return new ProgressionConfig(plugin);
    }

    long getTotalXpForLevel(int level) {
        return totalXpByLevel[clamp(level, 1, maxLevel)];
    }

    long getXpRequiredForNextLevel(int level) {
        int safeLevel = clamp(level, 1, maxLevel);
        return safeLevel >= maxLevel ? 0L : xpRequiredByLevel[safeLevel];
    }

    int getLevelForXp(long xp) {
        long safeXp = clampXp(xp);
        int low = 1;
        int high = maxLevel;
        int result = 1;

        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (totalXpByLevel[middle] <= safeXp) {
                result = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return result;
    }

    long clampXp(long xp) {
        return clamp(xp, 0L, getMaximumXp());
    }

    long getMaximumXp() {
        return totalXpByLevel[maxLevel];
    }

    int getBlockXp(Material material) {
        return blockXp.getOrDefault(material, 0);
    }

    int getMatureCropXp(Material material) {
        return matureCropXp.getOrDefault(material, 0);
    }

    int getConfiguredEntityXp(EntityType type) {
        return entityXp.getOrDefault(type, -1);
    }

    double getSpawnReasonMultiplier(String spawnReason) {
        if (spawnReason == null || spawnReason.isBlank()) {
            return 1.0D;
        }
        return spawnReasonMultipliers.getOrDefault(
                spawnReason.toUpperCase(Locale.ROOT),
                1.0D);
    }

    int getAdvancementXp(NamespacedKey key) {
        if (key == null) {
            return advancementDefaultXp;
        }
        return advancementXp.getOrDefault(
                key.toString().toLowerCase(Locale.ROOT),
                advancementDefaultXp);
    }

    private void buildCurve() {
        totalXpByLevel[1] = 0L;
        for (int level = 1; level < maxLevel; level++) {
            long step = level - 1L;
            long linearPart = saturatingMultiply(xpLinear, step);
            long quadraticPart = saturatingMultiply(
                    xpQuadratic,
                    saturatingMultiply(step, step));
            long required = saturatingAdd(
                    xpBase,
                    saturatingAdd(linearPart, quadraticPart));

            xpRequiredByLevel[level] = Math.max(1L, required);
            totalXpByLevel[level + 1] = saturatingAdd(
                    totalXpByLevel[level],
                    xpRequiredByLevel[level]);
        }
    }

    private static Map<Material, Integer> defaultBlockXp() {
        EnumMap<Material, Integer> values = new EnumMap<>(Material.class);

        putMaterial(values, "STONE", 1);
        putMaterial(values, "COBBLESTONE", 1);
        putMaterial(values, "DEEPSLATE", 1);
        putMaterial(values, "TUFF", 1);
        putMaterial(values, "NETHERRACK", 1);
        putMaterial(values, "END_STONE", 2);
        putMaterial(values, "OBSIDIAN", 5);

        putMaterial(values, "COAL_ORE", 3);
        putMaterial(values, "DEEPSLATE_COAL_ORE", 4);
        putMaterial(values, "COPPER_ORE", 4);
        putMaterial(values, "DEEPSLATE_COPPER_ORE", 5);
        putMaterial(values, "IRON_ORE", 5);
        putMaterial(values, "DEEPSLATE_IRON_ORE", 6);
        putMaterial(values, "GOLD_ORE", 7);
        putMaterial(values, "DEEPSLATE_GOLD_ORE", 8);
        putMaterial(values, "REDSTONE_ORE", 6);
        putMaterial(values, "DEEPSLATE_REDSTONE_ORE", 7);
        putMaterial(values, "LAPIS_ORE", 7);
        putMaterial(values, "DEEPSLATE_LAPIS_ORE", 8);
        putMaterial(values, "DIAMOND_ORE", 12);
        putMaterial(values, "DEEPSLATE_DIAMOND_ORE", 14);
        putMaterial(values, "EMERALD_ORE", 15);
        putMaterial(values, "DEEPSLATE_EMERALD_ORE", 17);
        putMaterial(values, "NETHER_QUARTZ_ORE", 4);
        putMaterial(values, "NETHER_GOLD_ORE", 6);
        putMaterial(values, "ANCIENT_DEBRIS", 25);

        String[] woods = {
                "OAK_LOG", "SPRUCE_LOG", "BIRCH_LOG", "JUNGLE_LOG",
                "ACACIA_LOG", "DARK_OAK_LOG", "MANGROVE_LOG",
                "CHERRY_LOG", "PALE_OAK_LOG", "CRIMSON_STEM",
                "WARPED_STEM", "CRIMSON_HYPHAE", "WARPED_HYPHAE"
        };
        for (String wood : woods) {
            putMaterial(values, wood, 2);
        }
        return values;
    }

    private static Map<Material, Integer> defaultCropXp() {
        EnumMap<Material, Integer> values = new EnumMap<>(Material.class);
        putMaterial(values, "WHEAT", 2);
        putMaterial(values, "CARROTS", 2);
        putMaterial(values, "POTATOES", 2);
        putMaterial(values, "BEETROOTS", 2);
        putMaterial(values, "NETHER_WART", 3);
        putMaterial(values, "COCOA", 2);
        putMaterial(values, "TORCHFLOWER_CROP", 3);
        putMaterial(values, "PITCHER_CROP", 3);
        return values;
    }

    private static Map<EntityType, Integer> defaultEntityXp() {
        EnumMap<EntityType, Integer> values = new EnumMap<>(EntityType.class);
        putEntity(values, "ENDER_DRAGON", 1_000);
        putEntity(values, "WITHER", 750);
        putEntity(values, "WARDEN", 500);
        putEntity(values, "ELDER_GUARDIAN", 100);
        putEntity(values, "RAVAGER", 60);
        putEntity(values, "EVOKER", 40);
        putEntity(values, "PIGLIN_BRUTE", 30);
        putEntity(values, "GHAST", 18);
        putEntity(values, "BREEZE", 16);
        putEntity(values, "BLAZE", 15);
        putEntity(values, "SHULKER", 15);
        putEntity(values, "VINDICATOR", 15);
        putEntity(values, "WITHER_SKELETON", 14);
        putEntity(values, "ZOGLIN", 14);
        putEntity(values, "ENDERMAN", 12);
        putEntity(values, "GUARDIAN", 12);
        putEntity(values, "PHANTOM", 12);
        putEntity(values, "HOGLIN", 12);
        putEntity(values, "CREEPER", 12);
        putEntity(values, "BOGGED", 12);
        return values;
    }

    private static Map<String, Double> defaultSpawnReasonMultipliers() {
        Map<String, Double> values = new HashMap<>();
        values.put("SPAWNER", 0.25D);
        values.put("TRIAL_SPAWNER", 0.50D);
        values.put("SPAWNER_EGG", 0.0D);
        values.put("DISPENSE_EGG", 0.0D);
        values.put("BREEDING", 0.0D);
        values.put("BUILD_IRONGOLEM", 0.0D);
        values.put("BUILD_SNOWMAN", 0.0D);
        values.put("COMMAND", 0.0D);
        values.put("CUSTOM", 0.0D);
        return values;
    }

    private static Map<String, Integer> defaultAdvancementXp() {
        Map<String, Integer> values = new HashMap<>();
        values.put("minecraft:end/kill_dragon", 250);
        values.put("minecraft:nether/summon_wither", 200);
        values.put("minecraft:adventure/hero_of_the_village", 150);
        values.put("minecraft:adventure/kill_all_mobs", 300);
        values.put("minecraft:husbandry/balanced_diet", 150);
        values.put("minecraft:nether/all_effects", 250);
        return values;
    }

    private static Map<Material, Integer> loadMaterialXpMap(
            JavaPlugin plugin,
            ConfigurationSection root,
            String path,
            Map<Material, Integer> fallback) {

        ConfigurationSection section = root != null
                ? root.getConfigurationSection(path)
                : null;
        if (section == null) {
            return Collections.unmodifiableMap(new EnumMap<>(fallback));
        }

        EnumMap<Material, Integer> values = new EnumMap<>(Material.class);
        for (String rawKey : section.getKeys(false)) {
            Material material = Material.matchMaterial(rawKey);
            if (material == null) {
                plugin.getLogger().warning(
                        "[Progression] Materiau inconnu dans " + path + ": " + rawKey);
                continue;
            }
            int xp = clamp(section.getInt(rawKey, 0), 0, 100_000);
            if (xp > 0) {
                values.put(material, xp);
            }
        }
        return Collections.unmodifiableMap(values);
    }

    private static Map<EntityType, Integer> loadEntityXpMap(
            JavaPlugin plugin,
            ConfigurationSection root,
            String path,
            Map<EntityType, Integer> fallback) {

        ConfigurationSection section = root != null
                ? root.getConfigurationSection(path)
                : null;
        if (section == null) {
            return Collections.unmodifiableMap(new EnumMap<>(fallback));
        }

        EnumMap<EntityType, Integer> values = new EnumMap<>(EntityType.class);
        for (String rawKey : section.getKeys(false)) {
            EntityType type = matchEntityType(rawKey);
            if (type == null) {
                plugin.getLogger().warning(
                        "[Progression] Type d'entite inconnu dans " + path + ": " + rawKey);
                continue;
            }
            int xp = clamp(section.getInt(rawKey, 0), 0, 100_000);
            // Une valeur explicite à 0 désactive bien cette entité et doit
            // donc écraser le fallback correspondant.
            values.put(type, xp);
        }
        return Collections.unmodifiableMap(values);
    }

    private static Map<String, Double> loadStringDoubleMap(
            JavaPlugin plugin,
            ConfigurationSection root,
            String path,
            Map<String, Double> fallback,
            double minimum,
            double maximum) {

        ConfigurationSection section = root != null
                ? root.getConfigurationSection(path)
                : null;
        if (section == null) {
            return Collections.unmodifiableMap(new HashMap<>(fallback));
        }

        Map<String, Double> values = new HashMap<>();
        for (String rawKey : section.getKeys(false)) {
            double value = section.getDouble(rawKey, Double.NaN);
            if (!Double.isFinite(value)) {
                plugin.getLogger().warning(
                        "[Progression] Valeur numerique invalide dans " + path + ": " + rawKey);
                continue;
            }
            values.put(
                    rawKey.toUpperCase(Locale.ROOT),
                    clamp(value, minimum, maximum));
        }
        return Collections.unmodifiableMap(values);
    }

    private static Map<String, Integer> loadStringIntMap(
            JavaPlugin plugin,
            ConfigurationSection root,
            String path,
            Map<String, Integer> fallback) {

        ConfigurationSection section = root != null
                ? root.getConfigurationSection(path)
                : null;
        if (section == null) {
            return Collections.unmodifiableMap(new HashMap<>(fallback));
        }

        Map<String, Integer> values = new HashMap<>();
        for (String rawKey : section.getKeys(false)) {
            int xp = clamp(section.getInt(rawKey, 0), 0, 100_000);
            String normalized = normalizeNamespacedPath(rawKey);
            if (normalized == null) {
                plugin.getLogger().warning(
                        "[Progression] Avancement invalide dans " + path + ": " + rawKey);
                continue;
            }
            values.put(normalized, xp);
        }
        return Collections.unmodifiableMap(values);
    }

    private static Set<String> loadWorldSet(
            ConfigurationSection root,
            String path) {
        if (root == null) {
            return Set.of();
        }
        Set<String> values = new HashSet<>();
        for (String world : root.getStringList(path)) {
            if (world != null && !world.isBlank()) {
                values.add(world.toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(values);
    }

    private static String normalizeNamespacedPath(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        String normalized = rawKey.trim().toLowerCase(Locale.ROOT);
        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized;
        }
        return normalized;
    }

    private static void putMaterial(
            Map<Material, Integer> target,
            String name,
            int xp) {
        Material material = Material.matchMaterial(name);
        if (material != null) {
            target.put(material, xp);
        }
    }

    private static void putEntity(
            Map<EntityType, Integer> target,
            String name,
            int xp) {
        EntityType type = matchEntityType(name);
        if (type != null) {
            target.put(type, xp);
        }
    }

    private static EntityType matchEntityType(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String normalized = rawName.trim()
                .toUpperCase(Locale.ROOT)
                .replace("MINECRAFT:", "");
        try {
            return EntityType.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean getBoolean(
            ConfigurationSection section,
            String path,
            boolean fallback) {
        return section != null ? section.getBoolean(path, fallback) : fallback;
    }

    private static int getInt(
            ConfigurationSection section,
            String path,
            int fallback) {
        return section != null ? section.getInt(path, fallback) : fallback;
    }

    private static long getLong(
            ConfigurationSection section,
            String path,
            long fallback) {
        return section != null ? section.getLong(path, fallback) : fallback;
    }

    private static double getDouble(
            ConfigurationSection section,
            String path,
            double fallback) {
        return section != null ? section.getDouble(path, fallback) : fallback;
    }

    private static long saturatingAdd(long left, long right) {
        if (left >= Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long saturatingMultiply(long left, long right) {
        if (left == 0L || right == 0L) {
            return 0L;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) {
            return minimum;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
