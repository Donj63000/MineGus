package org.example.village;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageTreasuryStoreTest {

    @Test
    void savesAndReloadsEveryPersistentTreasuryField() throws IOException {
        File folder = Files.createTempDirectory(
                "village-treasury-store"
        ).toFile();
        folder.deleteOnExit();
        VillageTreasury expected = treasury(27);
        VillageTreasuryStore store = new VillageTreasuryStore(
                folder,
                quietLogger()
        );

        assertTrue(store.save(List.of(expected)));
        VillageTreasuryStore.LoadResult loaded = store.load();

        assertTrue(loaded.writable());
        assertEquals(1, loaded.treasuries().size());
        VillageTreasury actual = loaded.treasuries().get(0);
        assertEquals(expected.villageId(), actual.villageId());
        assertEquals(expected.ownerId(), actual.ownerId());
        assertEquals(expected.worldId(), actual.worldId());
        assertEquals(expected.worldName(), actual.worldName());
        assertEquals(expected.roomBounds(), actual.roomBounds());
        assertEquals(expected.protectedZones(), actual.protectedZones());
        assertEquals(expected.containers(), actual.containers());
        assertEquals(expected.ledger(), actual.ledger());
        assertEquals(expected.doors(), actual.doors());
        assertEquals(expected.createdAtMillis(), actual.createdAtMillis());
        assertEquals(expected.nextTaxAtMillis(), actual.nextTaxAtMillis());
        assertEquals(expected.pendingTaxes(), actual.pendingTaxes());
        assertEquals(expected.depositedItems(), actual.depositedItems());
    }

    @Test
    void savesAndReloadsAnEmptyTreasurySection() throws IOException {
        File folder = Files.createTempDirectory(
                "village-treasury-empty"
        ).toFile();
        folder.deleteOnExit();
        VillageTreasuryStore store = new VillageTreasuryStore(
                folder,
                quietLogger()
        );

        assertTrue(store.save(List.of()));
        VillageTreasuryStore.LoadResult loaded = store.load();

        assertTrue(loaded.writable());
        assertTrue(loaded.treasuries().isEmpty());
    }

    @Test
    void refusesDuplicateVillageIdentifiers() throws IOException {
        File folder = Files.createTempDirectory(
                "village-treasury-duplicates"
        ).toFile();
        folder.deleteOnExit();
        VillageTreasuryStore store = new VillageTreasuryStore(
                folder,
                quietLogger()
        );

        assertFalse(store.save(List.of(treasury(9), treasury(9))));
    }

    @Test
    void restoresAnOrphanBackupWhenThePrimaryFileIsMissing()
            throws IOException {
        File folder = Files.createTempDirectory(
                "village-treasury-orphan-backup"
        ).toFile();
        folder.deleteOnExit();
        VillageTreasuryStore store = new VillageTreasuryStore(
                folder,
                quietLogger()
        );
        assertTrue(store.save(List.of(treasury(31))));

        File primary = new File(folder, "village-treasuries.yml");
        File backup = new File(folder, "village-treasuries.yml.bak");
        Files.copy(
                primary.toPath(),
                backup.toPath(),
                StandardCopyOption.REPLACE_EXISTING
        );
        assertTrue(primary.delete());

        VillageTreasuryStore.LoadResult loaded =
                new VillageTreasuryStore(folder, quietLogger()).load();

        assertTrue(loaded.writable());
        assertEquals(1, loaded.treasuries().size());
        assertEquals(31, loaded.treasuries().get(0).villageId());
    }

    @Test
    void refusesToRewriteAnUnknownSchema() throws IOException {
        File folder = Files.createTempDirectory(
                "village-treasury-future-schema"
        ).toFile();
        folder.deleteOnExit();
        Files.writeString(
                new File(folder, "village-treasuries.yml").toPath(),
                "schema-version: 999\ntreasuries: {}\n"
        );

        VillageTreasuryStore.LoadResult loaded =
                new VillageTreasuryStore(folder, quietLogger()).load();

        assertFalse(loaded.writable());
        assertTrue(loaded.treasuries().isEmpty());
    }

    private static VillageTreasury treasury(int villageId) {
        List<VillageTreasury.BlockPoint> containers = new ArrayList<>();
        for (int index = 0; index < 60; index++) {
            containers.add(new VillageTreasury.BlockPoint(
                    100 + index,
                    45,
                    -80
            ));
        }
        VillageTreasury.Cuboid room = new VillageTreasury.Cuboid(
                90,
                170,
                40,
                55,
                -90,
                -60
        );
        return new VillageTreasury(
                villageId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "world_" + villageId,
                room,
                List.of(room),
                containers,
                new VillageTreasury.BlockPoint(120, 45, -75),
                List.of(
                        new VillageTreasury.BlockPoint(119, 45, -61),
                        new VillageTreasury.BlockPoint(120, 45, -61)
                ),
                1_234L,
                5_678L,
                9,
                321L
        );
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger(
                "VillageTreasuryStoreTest-" + UUID.randomUUID()
        );
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.OFF);
        return logger;
    }
}
