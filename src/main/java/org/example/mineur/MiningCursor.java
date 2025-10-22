package org.example.mineur;

import org.bukkit.Location;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializable cursor that keeps track of the next block to mine inside the
 * current quarry bounds.
 */
public final class MiningCursor {

    public int x;
    public int y;
    public int z;
    public int minX;
    public int minZ;
    public int width;
    public int length;
    public boolean scanXFirst = true;

    public MiningCursor(Location base, int width, int length) {
        this.minX = base.getBlockX();
        this.y = base.getBlockY();
        this.minZ = base.getBlockZ();
        this.width = Math.max(1, width);
        this.length = Math.max(1, length);
        this.x = this.minX;
        this.z = this.minZ;
    }

    private MiningCursor() {
        // Used only during deserialization
    }

    public MiningCursor copy() {
        MiningCursor copy = new MiningCursor();
        copy.x = this.x;
        copy.y = this.y;
        copy.z = this.z;
        copy.minX = this.minX;
        copy.minZ = this.minZ;
        copy.width = this.width;
        copy.length = this.length;
        copy.scanXFirst = this.scanXFirst;
        return copy;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("x", x);
        data.put("y", y);
        data.put("z", z);
        data.put("minX", minX);
        data.put("minZ", minZ);
        data.put("width", width);
        data.put("length", length);
        data.put("scanXFirst", scanXFirst);
        return data;
    }

    @SuppressWarnings("unchecked")
    public static MiningCursor fromMap(Map<String, Object> map) {
        MiningCursor cursor = new MiningCursor();
        cursor.x = ((Number) map.getOrDefault("x", 0)).intValue();
        cursor.y = ((Number) map.getOrDefault("y", 0)).intValue();
        cursor.z = ((Number) map.getOrDefault("z", 0)).intValue();
        cursor.minX = ((Number) map.getOrDefault("minX", cursor.x)).intValue();
        cursor.minZ = ((Number) map.getOrDefault("minZ", cursor.z)).intValue();
        cursor.width = Math.max(1, ((Number) map.getOrDefault("width", 1)).intValue());
        cursor.length = Math.max(1, ((Number) map.getOrDefault("length", 1)).intValue());
        Object scan = map.get("scanXFirst");
        cursor.scanXFirst = (scan instanceof Boolean b) ? b : true;
        return cursor;
    }
}
