package org.example.village;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageKnightStoreTest {

    @Test
    void savesAndReloadsEveryPersistentKnightField() throws IOException {
        File folder = Files.createTempDirectory("village-knights-store").toFile();
        folder.deleteOnExit();
        VillageKnightGarrison expected = garrison(7);
        VillageKnightStore store = new VillageKnightStore(
                folder,
                quietLogger()
        );

        assertTrue(store.save(List.of(expected)));
        VillageKnightStore.LoadResult result = store.load();

        assertTrue(result.writable());
        assertEquals(1, result.garrisons().size());
        VillageKnightGarrison actual = result.garrisons().get(0);
        assertEquals(expected.villageId(), actual.villageId());
        assertEquals(expected.worldId(), actual.worldId());
        assertEquals(expected.worldName(), actual.worldName());
        assertEquals(expected.center(), actual.center());
        assertEquals(expected.radiusX(), actual.radiusX());
        assertEquals(expected.radiusZ(), actual.radiusZ());
        assertEquals(expected.createdAtMillis(), actual.createdAtMillis());

        VillageKnightGarrison.PatrolRoute route = actual.route(3);
        assertNotNull(route);
        assertEquals(VillageKnightGarrison.RouteKind.URBAN, route.kind());
        assertEquals(1, route.waypointIndex());
        assertEquals(4_321L, route.lastAdvanceAtMillis());
        assertEquals(2, route.waypoints().size());

        VillageKnightGarrison.KnightSlot slot = actual.slots().iterator().next();
        VillageKnightGarrison.KnightSlot expectedSlot =
                expected.slots().iterator().next();
        assertEquals(expectedSlot.slotId(), slot.slotId());
        assertEquals(expectedSlot.entityId(), slot.entityId());
        assertEquals(expectedSlot.unitKind(), slot.unitKind());
        assertEquals(expectedSlot.formationIndex(), slot.formationIndex());
        assertEquals(expectedSlot.respawnAtMillis(), slot.respawnAtMillis());
        assertEquals(expectedSlot.lastKnownPosition(), slot.lastKnownPosition());
    }

    @Test
    void restoresTheBackupAndQuarantinesAnUnreadablePrimary() throws IOException {
        File folder = Files.createTempDirectory("village-knights-backup").toFile();
        folder.deleteOnExit();
        VillageKnightStore store = new VillageKnightStore(
                folder,
                quietLogger()
        );

        assertTrue(store.save(List.of(garrison(1))));
        assertTrue(store.save(List.of(garrison(2))));
        File primary = new File(folder, "village-knights.yml");
        File backup = new File(folder, "village-knights.yml.bak");
        assertTrue(backup.isFile());
        Files.writeString(primary.toPath(), "garrisons: [contenu-invalide\n");

        VillageKnightStore.LoadResult result = new VillageKnightStore(
                folder,
                quietLogger()
        ).load();

        assertTrue(result.writable());
        assertEquals(1, result.garrisons().size());
        assertEquals(1, result.garrisons().get(0).villageId());
        assertFalse(primary.exists());
        File[] quarantined = folder.listFiles((directory, name) ->
                name.startsWith("village-knights.yml.corrupt-"));
        assertNotNull(quarantined);
        assertEquals(1, quarantined.length);
        assertTrue(Files.readString(quarantined[0].toPath())
                .contains("contenu-invalide"));
    }

    private static VillageKnightGarrison garrison(int villageId) {
        VillageKnightGarrison.BlockPoint first =
                new VillageKnightGarrison.BlockPoint(10, 65, 20);
        VillageKnightGarrison.BlockPoint second =
                new VillageKnightGarrison.BlockPoint(16, 65, 24);
        VillageKnightGarrison.PatrolRoute route =
                new VillageKnightGarrison.PatrolRoute(
                        3,
                        VillageKnightGarrison.RouteKind.URBAN,
                        List.of(first, second),
                        1,
                        4_321L
                );
        VillageKnightGarrison.KnightSlot slot =
                new VillageKnightGarrison.KnightSlot(
                        UUID.randomUUID(),
                        3,
                        VillageKnightGarrison.UnitKind.SWORDSMAN,
                        2,
                        UUID.randomUUID(),
                        9_876L,
                        second
                );
        return new VillageKnightGarrison(
                villageId,
                UUID.randomUUID(),
                "world_" + villageId,
                first,
                32,
                28,
                1_234L,
                List.of(route),
                List.of(slot)
        );
    }

    private static Logger quietLogger() {
        // Ici, je garde le test lisible : la corruption est volontaire et vérifiée par les assertions.
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }
}
