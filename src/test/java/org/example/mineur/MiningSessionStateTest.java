package org.example.mineur;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MiningSessionStateTest {

    @Test
    void roundTripsCurrentTunnelInfrastructureStateAndDeduplicatesSeals() {
        World world = world();
        MiningSessionState state = new MiningSessionState();
        state.worldUid = world.getUID();
        state.base = new Location(world, 10, 64, 12);
        state.width = 10;
        state.length = 10;
        state.cursor = new MiningCursor(state.base, state.width, state.length);
        state.cursor.height = 3;
        state.tunnelInfrastructureVersion = 1;
        state.tunnelArrivalRoom = true;
        state.tunnelOriginBounds = new MiningSessionState.StructureBounds(
                10,
                521,
                64,
                95,
                22,
                533
        );
        state.preparedTunnelSection = "10:64:12:SOUTH:0";
        state.pendingTunnelInfrastructureSection = "10:64:22:SOUTH:1";

        Vector firstSeal = new Vector(9, 64, 12);
        Vector secondSeal = new Vector(20, 66, 22);
        state.tunnelFrontierSeals.add(firstSeal);
        state.tunnelFrontierSeals.add(firstSeal.clone());
        state.tunnelFrontierSeals.add(secondSeal);

        Vector firstForeignFrontierSeal = new Vector(9, 65, 13);
        Vector secondForeignFrontierSeal = new Vector(19, 63, 22);
        state.tunnelForeignFrontierSeals.add(firstForeignFrontierSeal);
        state.tunnelForeignFrontierSeals.add(firstForeignFrontierSeal.clone());
        state.tunnelForeignFrontierSeals.add(secondForeignFrontierSeal);

        Vector firstPermanentSeal = new Vector(10, 63, 13);
        Vector secondPermanentSeal = new Vector(19, 67, 21);
        state.tunnelPermanentSeals.add(firstPermanentSeal);
        state.tunnelPermanentSeals.add(firstPermanentSeal.clone());
        state.tunnelPermanentSeals.add(secondPermanentSeal);

        Vector firstPendingFrontierSeal = new Vector(11, 63, 14);
        Vector secondPendingFrontierSeal = new Vector(18, 67, 20);
        state.pendingTunnelFrontierSeals.add(firstPendingFrontierSeal);
        state.pendingTunnelFrontierSeals.add(firstPendingFrontierSeal.clone());
        state.pendingTunnelFrontierSeals.add(secondPendingFrontierSeal);

        Vector firstPendingPermanentSeal = new Vector(12, 64, 15);
        Vector secondPendingPermanentSeal = new Vector(20, 66, 21);
        state.pendingTunnelPermanentSeals.add(firstPendingPermanentSeal);
        state.pendingTunnelPermanentSeals.add(firstPendingPermanentSeal.clone());
        state.pendingTunnelPermanentSeals.add(secondPendingPermanentSeal);

        Map<String, Object> serialized = state.toMap();

        assertEquals(10, serialized.get("schemaVersion"));
        assertEquals(2, ((List<?>) serialized.get("tunnelFrontierSeals")).size());
        assertEquals(2, ((List<?>) serialized.get("tunnelForeignFrontierSeals")).size());
        assertEquals(2, ((List<?>) serialized.get("tunnelPermanentSeals")).size());
        assertEquals(2, ((List<?>) serialized.get("pendingTunnelFrontierSeals")).size());
        assertEquals(2, ((List<?>) serialized.get("pendingTunnelPermanentSeals")).size());

        MiningSessionState restored = MiningSessionState.fromMap(world, serialized);

        assertEquals(1, restored.tunnelInfrastructureVersion);
        assertTrue(restored.tunnelArrivalRoom);
        assertEquals(state.tunnelOriginBounds, restored.tunnelOriginBounds);
        assertEquals(state.preparedTunnelSection, restored.preparedTunnelSection);
        assertEquals(
                state.pendingTunnelInfrastructureSection,
                restored.pendingTunnelInfrastructureSection
        );
        assertEquals(List.of(firstSeal, secondSeal), restored.tunnelFrontierSeals);
        assertEquals(
                List.of(firstForeignFrontierSeal, secondForeignFrontierSeal),
                restored.tunnelForeignFrontierSeals
        );
        assertEquals(
                List.of(firstPermanentSeal, secondPermanentSeal),
                restored.tunnelPermanentSeals
        );
        assertEquals(
                List.of(firstPendingFrontierSeal, secondPendingFrontierSeal),
                restored.pendingTunnelFrontierSeals
        );
        assertEquals(
                List.of(firstPendingPermanentSeal, secondPendingPermanentSeal),
                restored.pendingTunnelPermanentSeals
        );
    }

    @Test
    void rejectsForgedTunnelSealsFarFromCursor() {
        World world = world();
        MiningSessionState state = new MiningSessionState();
        state.worldUid = world.getUID();
        state.base = new Location(world, 10, 64, 12);
        state.width = 4;
        state.length = 4;
        state.cursor = new MiningCursor(state.base, state.width, state.length);
        state.cursor.height = 3;

        Map<String, Object> serialized = state.toMap();
        serialized.put(
                "tunnelFrontierSeals",
                List.of(
                        List.of(11, 64, 13),
                        List.of(100_000, 64, 100_000)
                )
        );
        serialized.put(
                "tunnelPermanentSeals",
                List.of(
                        List.of(12, 65, 14),
                        List.of(-100_000, 64, -100_000)
                )
        );
        serialized.put(
                "tunnelForeignFrontierSeals",
                List.of(
                        List.of(10, 65, 13),
                        List.of(100_000, 64, 100_000)
                )
        );
        serialized.put(
                "pendingTunnelFrontierSeals",
                List.of(
                        List.of(9, 66, 15),
                        List.of(100_000, 64, -100_000)
                )
        );
        serialized.put(
                "pendingTunnelPermanentSeals",
                List.of(
                        List.of(13, 63, 16),
                        List.of(-100_000, 64, 100_000)
                )
        );

        MiningSessionState restored = MiningSessionState.fromMap(world, serialized);

        assertEquals(List.of(new Vector(11, 64, 13)), restored.tunnelFrontierSeals);
        assertEquals(
                List.of(new Vector(10, 65, 13)),
                restored.tunnelForeignFrontierSeals
        );
        assertEquals(List.of(new Vector(12, 65, 14)), restored.tunnelPermanentSeals);
        assertEquals(
                List.of(new Vector(9, 66, 15)),
                restored.pendingTunnelFrontierSeals
        );
        assertEquals(
                List.of(new Vector(13, 63, 16)),
                restored.pendingTunnelPermanentSeals
        );
    }

    @Test
    void loadsSchemaEightWithoutPendingTunnelInfrastructureJournal() {
        World world = world();
        Map<String, Object> serialized = baseMap(world);
        serialized.put("schemaVersion", 8);

        MiningSessionState restored = MiningSessionState.fromMap(world, serialized);

        assertEquals("", restored.pendingTunnelInfrastructureSection);
        assertTrue(restored.pendingTunnelFrontierSeals.isEmpty());
        assertTrue(restored.pendingTunnelPermanentSeals.isEmpty());
    }

    @Test
    void loadsSchemaNineWithoutTunnelOriginOrForeignFrontierSeals() {
        World world = world();
        Map<String, Object> serialized = baseMap(world);
        serialized.put("schemaVersion", 9);

        MiningSessionState restored = MiningSessionState.fromMap(world, serialized);

        assertNull(restored.tunnelOriginBounds);
        assertTrue(restored.tunnelForeignFrontierSeals.isEmpty());
    }

    @Test
    void rejectsTunnelOriginBoundsFarFromInitialMine() {
        World world = world();
        Map<String, Object> serialized = baseMap(world);
        serialized.put(
                "tunnelOriginBounds",
                List.of(100_000, 100_010, 64, 70, 100_000, 100_010)
        );

        MiningSessionState restored = MiningSessionState.fromMap(world, serialized);

        assertNull(restored.tunnelOriginBounds);
    }

    @Test
    void rejectsInvalidTunnelOriginBounds() {
        World world = world();

        Map<String, Object> oversized = baseMap(world);
        oversized.put(
                "tunnelOriginBounds",
                List.of(10, 522, 64, 70, 12, 20)
        );
        assertNull(MiningSessionState.fromMap(world, oversized).tunnelOriginBounds);

        Map<String, Object> outsideWorld = baseMap(world);
        outsideWorld.put(
                "tunnelOriginBounds",
                List.of(10, 20, -65, 70, 12, 20)
        );
        assertNull(MiningSessionState.fromMap(world, outsideWorld).tunnelOriginBounds);

        Map<String, Object> malformed = baseMap(world);
        malformed.put(
                "tunnelOriginBounds",
                List.of(10, 20.5D, 64, 70, 12, 20)
        );
        assertNull(MiningSessionState.fromMap(world, malformed).tunnelOriginBounds);
    }

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
    void marksLegacyPauseForOneTimeRecovery() {
        World world = world();
        Map<String, Object> serialized = baseMap(world);
        serialized.put("schemaVersion", 5);
        serialized.put("paused", true);

        MiningSessionState state = MiningSessionState.fromMap(world, serialized);

        assertTrue(state.paused);
        assertEquals(MiningSessionState.PauseReason.LEGACY_UNKNOWN, state.pauseReason);
        assertTrue(state.resumeLegacyUnknownPause());
        assertFalse(state.paused);
        assertEquals(MiningSessionState.PauseReason.NONE, state.pauseReason);
        assertFalse(state.resumeLegacyUnknownPause());
    }

    @Test
    void keepsCorruptedModernPauseDisabledInsteadOfAutoResumingIt() {
        World world = world();
        Map<String, Object> serialized = baseMap(world);
        serialized.put("paused", true);

        MiningSessionState state = MiningSessionState.fromMap(world, serialized);

        assertTrue(state.paused);
        assertEquals(MiningSessionState.PauseReason.ERROR, state.pauseReason);
        assertFalse(state.resumeLegacyUnknownPause());
    }

    @Test
    void roundTripsExplicitManualPauseReason() {
        World world = world();
        MiningSessionState state = new MiningSessionState();
        state.worldUid = world.getUID();
        state.base = new Location(world, 10, 64, 12);
        state.width = 4;
        state.length = 4;
        state.pause(MiningSessionState.PauseReason.MANUAL);

        MiningSessionState restored = MiningSessionState.fromMap(world, state.toMap());

        assertTrue(restored.paused);
        assertEquals(MiningSessionState.PauseReason.MANUAL, restored.pauseReason);
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
