package org.example.mineur;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MiningCursorTest {

    @Test
    void constructorEnforcesMinimumDimensions() {
        Location base = new Location(null, 10.4, 65.0, -7.6);
        MiningCursor cursor = new MiningCursor(base, 0, -3);

        assertEquals(base.getBlockX(), cursor.minX);
        assertEquals(base.getBlockY(), cursor.minY);
        assertEquals(base.getBlockY(), cursor.y);
        assertEquals(base.getBlockZ(), cursor.minZ);
        assertEquals(1, cursor.width);
        assertEquals(1, cursor.height);
        assertEquals(1, cursor.length);
        assertEquals(cursor.minX, cursor.x);
        assertEquals(cursor.minZ, cursor.z);
        assertTrue(cursor.scanXFirst);
    }

    @Test
    void copyCreatesIndependentClone() {
        MiningCursor cursor = new MiningCursor(new Location(null, 2, 70, 3), 4, 5);
        cursor.x = cursor.minX + 1;
        cursor.y -= 2;
        cursor.z = cursor.minZ + 2;
        cursor.minY = cursor.y;
        cursor.height = 3;
        cursor.scanXFirst = false;

        MiningCursor clone = cursor.copy();

        assertNotSame(cursor, clone);
        assertEquals(cursor.x, clone.x);
        assertEquals(cursor.y, clone.y);
        assertEquals(cursor.z, clone.z);
        assertEquals(cursor.minX, clone.minX);
        assertEquals(cursor.minY, clone.minY);
        assertEquals(cursor.minZ, clone.minZ);
        assertEquals(cursor.width, clone.width);
        assertEquals(cursor.height, clone.height);
        assertEquals(cursor.length, clone.length);
        assertFalse(clone.scanXFirst);

        cursor.x++;
        cursor.scanXFirst = true;
        assertNotEquals(cursor.x, clone.x);
        assertNotEquals(cursor.scanXFirst, clone.scanXFirst);
    }

    @Test
    void toMapAndFromMapRoundTripPreservesState() {
        MiningCursor cursor = new MiningCursor(new Location(null, -5, 40, 12), 6, 7);
        cursor.x = cursor.minX + 3;
        cursor.y -= 4;
        cursor.z = cursor.minZ + 4;
        cursor.minY = cursor.y;
        cursor.height = 5;
        cursor.scanXFirst = false;

        Map<String, Object> data = cursor.toMap();
        MiningCursor restored = MiningCursor.fromMap(data);

        assertEquals(cursor.x, restored.x);
        assertEquals(cursor.y, restored.y);
        assertEquals(cursor.z, restored.z);
        assertEquals(cursor.minX, restored.minX);
        assertEquals(cursor.minY, restored.minY);
        assertEquals(cursor.minZ, restored.minZ);
        assertEquals(cursor.width, restored.width);
        assertEquals(cursor.height, restored.height);
        assertEquals(cursor.length, restored.length);
        assertFalse(restored.scanXFirst);
    }

    @Test
    void fromMapAppliesDefaultsWhenValuesMissing() {
        Map<String, Object> data = new HashMap<>();
        data.put("x", 3);
        data.put("y", 50);
        data.put("z", -2);
        data.put("width", 0);
        data.put("length", 0);

        MiningCursor restored = MiningCursor.fromMap(data);

        assertEquals(3, restored.x);
        assertEquals(50, restored.y);
        assertEquals(-2, restored.z);
        assertEquals(3, restored.minX);
        assertEquals(50, restored.minY);
        assertEquals(-2, restored.minZ);
        assertEquals(1, restored.width);
        assertEquals(1, restored.height);
        assertEquals(1, restored.length);
        assertTrue(restored.scanXFirst);
    }
}
