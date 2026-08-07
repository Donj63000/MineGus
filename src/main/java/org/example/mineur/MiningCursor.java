package org.example.mineur;

import org.bukkit.Location;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Curseur sérialisable qui désigne la prochaine coordonnée à examiner dans
 * les limites du chantier courant.
 */
public final class MiningCursor {

    public int x;
    public int y;
    public int z;
    public int minX;
    public int minY;
    public int minZ;
    public int width;
    public int height;
    public int length;
    public boolean scanXFirst = true;

    /**
     * Marque explicitement la fin du parcours.
     *
     * <p>Une sentinelle dédiée évite les débordements lorsque la dernière
     * coordonnée se trouve à {@link Integer#MAX_VALUE} et permet de reprendre
     * correctement une section terminée après un redémarrage.</p>
     */
    public boolean exhausted = false;

    public MiningCursor(Location base, int width, int length) {
        this.minX = base.getBlockX();
        this.minY = base.getBlockY();
        this.y = base.getBlockY();
        this.minZ = base.getBlockZ();
        this.width = Math.max(1, width);
        this.height = 1;
        this.length = Math.max(1, length);
        this.x = this.minX;
        this.z = this.minZ;
    }

    private MiningCursor() {
        // Constructeur réservé à la désérialisation.
    }

    public MiningCursor copy() {
        MiningCursor copy = new MiningCursor();
        copy.copyFrom(this);
        return copy;
    }

    /**
     * Recopie toutes les coordonnées dans l'instance courante.
     *
     * <p>Cette méthode sert notamment à restaurer atomiquement le curseur
     * lorsqu'une pause intervient entre la sélection et la casse d'un bloc.</p>
     */
    public void copyFrom(MiningCursor source) {
        if (source == null) {
            return;
        }
        this.x = source.x;
        this.y = source.y;
        this.z = source.z;
        this.minX = source.minX;
        this.minY = source.minY;
        this.minZ = source.minZ;
        this.width = Math.max(1, source.width);
        this.height = Math.max(1, source.height);
        this.length = Math.max(1, source.length);
        this.scanXFirst = source.scanXFirst;
        this.exhausted = source.exhausted;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("x", x);
        data.put("y", y);
        data.put("z", z);
        data.put("minX", minX);
        data.put("minY", minY);
        data.put("minZ", minZ);
        data.put("width", width);
        data.put("height", height);
        data.put("length", length);
        data.put("scanXFirst", scanXFirst);
        data.put("exhausted", exhausted);
        return data;
    }

    public static MiningCursor fromMap(Map<String, Object> map) {
        MiningCursor cursor = new MiningCursor();
        Map<String, Object> safeMap = map != null ? map : Map.of();
        cursor.x = intValue(safeMap.get("x"), 0);
        cursor.y = intValue(safeMap.get("y"), 0);
        cursor.z = intValue(safeMap.get("z"), 0);
        cursor.minX = intValue(safeMap.get("minX"), cursor.x);
        cursor.minY = intValue(safeMap.get("minY"), cursor.y);
        cursor.minZ = intValue(safeMap.get("minZ"), cursor.z);
        cursor.width = Math.max(1, intValue(safeMap.get("width"), 1));
        cursor.height = Math.max(1, intValue(safeMap.get("height"), 1));
        cursor.length = Math.max(1, intValue(safeMap.get("length"), 1));
        Object scan = safeMap.get("scanXFirst");
        cursor.scanXFirst = !(scan instanceof Boolean value) || value;
        Object exhausted = safeMap.get("exhausted");
        cursor.exhausted = exhausted instanceof Boolean value && value;
        return cursor;
    }

    private static int intValue(Object value, int fallback) {
        if (!(value instanceof Number number)) {
            return fallback;
        }
        double decimal = number.doubleValue();
        if (!Double.isFinite(decimal)) {
            return fallback;
        }
        if (decimal <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (decimal >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) decimal;
    }
}
