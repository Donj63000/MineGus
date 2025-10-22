package org.example.mineur.store;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.example.mineur.MiningCursor;
import org.example.mineur.MiningPattern;
import org.example.mineur.MiningSessionState;
import org.example.mineur.MiningSpeed;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read/write helper around sessions.yml.
 */
public final class SessionStore {

    private final File file;
    private final YamlConfiguration yaml;

    public SessionStore(File dataFolder) {
        this.file = new File(dataFolder, "sessions.yml");
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    public List<MiningSessionState> load() {
        List<MiningSessionState> list = new ArrayList<>();
        ConfigurationSection root = yaml.getConfigurationSection("sessions");
        if (root == null) {
            return list;
        }

        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }

            String worldId = section.getString("world");
            if (worldId == null) {
                continue;
            }
            World world = Bukkit.getWorld(UUID.fromString(worldId));
            if (world == null) {
                continue;
            }

            Map<String, Object> raw = new LinkedHashMap<>();
            for (String entry : section.getKeys(false)) {
                raw.put(entry, section.get(entry));
            }

            MiningSessionState state;
            if (!raw.containsKey("cursor")) {
                state = migrateLegacy(world, raw);
            } else {
                state = MiningSessionState.fromMap(world, raw);
            }

            if (state != null) {
                list.add(state);
            }
        }
        return list;
    }

    public void saveAll(List<MiningSessionState> sessions) {
        yaml.set("sessions", null);
        int index = 0;
        for (MiningSessionState state : sessions) {
            yaml.createSection("sessions." + index, state.toMap());
            index++;
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private MiningSessionState migrateLegacy(World world, Map<String, Object> raw) {
        int baseX = ((Number) raw.getOrDefault("x", 0)).intValue();
        int baseY = ((Number) raw.getOrDefault("y", 0)).intValue();
        int baseZ = ((Number) raw.getOrDefault("z", 0)).intValue();
        int width = ((Number) raw.getOrDefault("width", 1)).intValue();
        int length = ((Number) raw.getOrDefault("length", 1)).intValue();
        double minerY = ((Number) raw.getOrDefault("minerY", baseY)).doubleValue();

        MiningSessionState state = new MiningSessionState();
        state.worldUid = world.getUID();
        state.base = world.getBlockAt(baseX, baseY, baseZ).getLocation();
        state.width = width;
        state.length = length;
        state.pattern = MiningPattern.QUARRY;
        state.speed = MiningSpeed.NORMAL;
        state.cursor = new MiningCursor(state.base, width, length);
        state.cursor.y = (int) Math.round(minerY);
        state.cursor.x = state.cursor.minX;
        state.cursor.z = state.cursor.minZ;
        state.minerY = minerY;
        state.useBarrelMaster = false;
        Bukkit.getLogger().info("[mineur] migration de session v1 -> v2 effectuée");
        return state;
    }
}
