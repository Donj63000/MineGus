package org.example.mineur;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MiningSessionStateTest {

    @Test
    void rejectsModernSessionWithoutValidBase() {
        World world = world();
        Map<String, Object> serialized = new LinkedHashMap<>();
        serialized.put("schemaVersion", MiningSessionState.CURRENT_SCHEMA_VERSION);
        serialized.put("world", world.getUID().toString());
        serialized.put("cursor", Map.of("x", 10, "y", 64, "z", 10));

        assertThrows(
                IllegalArgumentException.class,
                () -> MiningSessionState.fromMap(world, serialized)
        );
    }

    @Test
    void capsUntrustedSerializedLists() {
        World world = world();
        Map<String, Object> serialized = baseMap(world);

        List<List<Integer>> containers = new ArrayList<>();
        List<String> trusted = new ArrayList<>();
        for (int index = 0; index < 2_000; index++) {
            containers.add(List.of(index, 64, index));
            trusted.add(UUID.randomUUID().toString());
        }
        serialized.put("containers", containers);
        serialized.put("trusted", trusted);

        MiningSessionState state = MiningSessionState.fromMap(world, serialized);

        assertEquals(256, state.containers.size());
        assertEquals(256, state.trusted.size());
    }

    @Test
    void roundTripsCabinStructureBounds() {
        World world = world();
        MiningSessionState state = new MiningSessionState();
        state.worldUid = world.getUID();
        state.base = new Location(world, 10, 64, 12);
        state.width = 8;
        state.length = 9;
        state.structureVersion = 1;
        state.structureBounds = new MiningSessionState.StructureBounds(
                2,
                26,
                65,
                84,
                4,
                29
        );

        MiningSessionState restored = MiningSessionState.fromMap(world, state.toMap());

        assertEquals(1, restored.structureVersion);
        assertNotNull(restored.structureBounds);
        assertEquals(state.structureBounds, restored.structureBounds);
    }

    @Test
    void ignoresForgedStructureBoundsFarFromMine() {
        World world = world();
        Map<String, Object> serialized = baseMap(world);
        serialized.put("structureVersion", 1);
        serialized.put(
                "structureBounds",
                List.of(100_000, 100_010, 65, 80, 100_000, 100_010)
        );

        MiningSessionState restored = MiningSessionState.fromMap(world, serialized);

        assertEquals(0, restored.structureVersion);
        assertNull(restored.structureBounds);
    }

    @Test
    void rollsBackPendingCursorExactlyOnce() {
        MiningSessionState state = new MiningSessionState();
        state.cursor = new MiningCursor(new Location(null, 0, 64, 0), 4, 4);
        state.pendingCursor = state.cursor.copy();
        state.cursor.x = 3;
        state.cursor.y = 60;
        state.cursor.exhausted = true;

        state.rollbackPendingCursor();

        assertEquals(0, state.cursor.x);
        assertEquals(64, state.cursor.y);
        assertEquals(false, state.cursor.exhausted);
        assertNull(state.pendingCursor);

        // Un second appel est volontairement sans effet.
        state.cursor.x = 2;
        state.rollbackPendingCursor();
        assertEquals(2, state.cursor.x);
    }

    private Map<String, Object> baseMap(World world) {
        Map<String, Object> serialized = new LinkedHashMap<>();
        serialized.put("schemaVersion", MiningSessionState.CURRENT_SCHEMA_VERSION);
        serialized.put("id", UUID.randomUUID().toString());
        serialized.put("world", world.getUID().toString());
        serialized.put("base", List.of(10, 64, 12));
        serialized.put("width", 4);
        serialized.put("length", 4);
        return serialized;
    }

    private World world() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        return world;
    }
}
