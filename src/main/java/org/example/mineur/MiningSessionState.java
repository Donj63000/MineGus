package org.example.mineur;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Serializable state for a miner session. Keeps only the lightweight data
 * necessary to resume the mining loop after a reload.
 */
public final class MiningSessionState {

    public UUID id = UUID.randomUUID();
    public UUID worldUid;
    public Location base;
    public int width;
    public int length;
    public MiningPattern pattern = MiningPattern.QUARRY;
    public MiningSpeed speed = MiningSpeed.NORMAL;
    public MiningCursor cursor;
    public double minerY;
    public UUID owner;
    public final List<Vector> containers = new ArrayList<>();
    public boolean useBarrelMaster = true;
    public boolean paused = false;
    public final Set<UUID> trusted = new HashSet<>();

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id.toString());
        map.put("world", worldUid.toString());
        map.put("base", List.of(base.getBlockX(), base.getBlockY(), base.getBlockZ()));
        map.put("width", width);
        map.put("length", length);
        map.put("pattern", pattern.name());
        map.put("speed", speed.name());
        map.put("cursor", cursor != null ? cursor.toMap() : null);
        map.put("minerY", minerY);
        map.put("owner", owner != null ? owner.toString() : "");
        List<List<Integer>> cont = new ArrayList<>();
        for (Vector v : containers) {
            cont.add(List.of(v.getBlockX(), v.getBlockY(), v.getBlockZ()));
        }
        map.put("containers", cont);
        map.put("useBarrelMaster", useBarrelMaster);
        map.put("paused", paused);
        List<String> trustedList = new ArrayList<>();
        for (UUID uuid : trusted) {
            trustedList.add(uuid.toString());
        }
        map.put("trusted", trustedList);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static MiningSessionState fromMap(World world, Map<String, Object> map) {
        MiningSessionState state = new MiningSessionState();
        state.id = UUID.fromString(Objects.toString(map.get("id")));
        state.worldUid = UUID.fromString(Objects.toString(map.get("world")));
        List<?> baseList = (List<?>) map.get("base");
        int baseX = ((Number) baseList.get(0)).intValue();
        int baseY = ((Number) baseList.get(1)).intValue();
        int baseZ = ((Number) baseList.get(2)).intValue();
        state.base = new Location(world, baseX, baseY, baseZ);
        state.width = ((Number) map.getOrDefault("width", 1)).intValue();
        state.length = ((Number) map.getOrDefault("length", 1)).intValue();
        Object patternObj = map.get("pattern");
        if (patternObj instanceof String patternName) {
            state.pattern = MiningPattern.valueOf(patternName.toUpperCase());
        }
        Object speedObj = map.get("speed");
        if (speedObj instanceof String speedName) {
            state.speed = MiningSpeed.valueOf(speedName.toUpperCase());
        }
        Object cursorObj = map.get("cursor");
        if (cursorObj instanceof Map<?, ?> cursorMap) {
            state.cursor = MiningCursor.fromMap((Map<String, Object>) cursorMap);
        }
        state.minerY = ((Number) map.getOrDefault("minerY", baseY)).doubleValue();
        String ownerStr = Objects.toString(map.getOrDefault("owner", ""), "");
        state.owner = ownerStr.isEmpty() ? null : UUID.fromString(ownerStr);
        Object containersObj = map.get("containers");
        if (containersObj instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof List<?> coords && coords.size() >= 3) {
                    int cx = ((Number) coords.get(0)).intValue();
                    int cy = ((Number) coords.get(1)).intValue();
                    int cz = ((Number) coords.get(2)).intValue();
                    state.containers.add(new Vector(cx, cy, cz));
                }
            }
        }
        Object useBarrel = map.get("useBarrelMaster");
        state.useBarrelMaster = useBarrel instanceof Boolean b ? b : true;
        Object pausedObj = map.get("paused");
        state.paused = pausedObj instanceof Boolean b ? b : false;
        Object trustedObj = map.get("trusted");
        if (trustedObj instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof String s && !s.isEmpty()) {
                    state.trusted.add(UUID.fromString(s));
                }
            }
        }
        if (state.cursor == null) {
            state.cursor = new MiningCursor(state.base, state.width, state.length);
            state.cursor.y = state.base.getBlockY();
        }
        return state;
    }
}
