package org.example.mineur;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.example.mineur.store.SessionStore;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionStoreTest {

    @Test
    void serializesAndDeserializesState() throws IOException {
        File folder = Files.createTempDirectory("mineur-store-test").toFile();
        folder.deleteOnExit();

        SessionStore store = new SessionStore(folder);

        UUID worldUid = UUID.randomUUID();
        World world = mock(World.class);
        Block block = mock(Block.class);
        when(world.getUID()).thenReturn(worldUid);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);

        Location baseLocation = new Location(world, 10, 64, 12);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(block.getLocation()).thenReturn(baseLocation);

        MiningSessionState state = new MiningSessionState();
        state.worldUid = worldUid;
        state.base = baseLocation;
        state.width = 5;
        state.length = 4;
        state.pattern = MiningPattern.QUARRY;
        state.speed = MiningSpeed.FAST;
        state.cursor = new MiningCursor(state.base, state.width, state.length);
        state.minerY = 60;
        state.owner = UUID.randomUUID();
        state.useBarrelMaster = false;
        state.selected = true;
        state.tunnelHeight = 4;
        state.containers.add(new Vector(8, 64, 9));

        store.saveAll(List.of(state));

        try (MockedStatic<Bukkit> mocked = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            mocked.when(() -> Bukkit.getWorld(worldUid)).thenReturn(world);
            mocked.when(Bukkit::getLogger).thenReturn(Logger.getAnonymousLogger());

            SessionStore reloaded = new SessionStore(folder);
            List<MiningSessionState> loaded = reloaded.load();

            assertEquals(1, loaded.size());
            MiningSessionState restored = loaded.get(0);
            assertEquals(state.worldUid, restored.worldUid);
            assertEquals(state.width, restored.width);
            assertEquals(state.length, restored.length);
            assertNotNull(restored.cursor);
            assertEquals(state.cursor.minX, restored.cursor.minX);
            assertEquals(state.tunnelHeight, restored.tunnelHeight);
            assertEquals(1, restored.containers.size());
            assertEquals(state.selected, restored.selected);
        }
    }

    @Test
    void preservesModernSessionWithoutBaseInsteadOfMigratingItToWorldOrigin() throws IOException {
        File folder = Files.createTempDirectory("mineur-store-missing-base-test").toFile();
        folder.deleteOnExit();

        UUID worldUid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        File primary = new File(folder, "sessions.yml");
        Files.writeString(primary.toPath(), """
                sessions:
                  '0':
                    schemaVersion: 4
                    id: %s
                    world: %s
                    width: 4
                    length: 4
                """.formatted(sessionId, worldUid));

        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldUid);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);

        try (MockedStatic<Bukkit> mocked = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            mocked.when(() -> Bukkit.getWorld(worldUid)).thenReturn(world);

            SessionStore store = new SessionStore(folder, Logger.getAnonymousLogger());
            List<MiningSessionState> loaded = store.load();

            assertTrue(loaded.isEmpty());

            /*
             * L'entrée invalide reste disponible pour réparation manuelle et ne
             * devient surtout pas une mine active en 0, minY, 0.
             */
            store.saveAll(List.of());
            String rewritten = Files.readString(primary.toPath());
            assertTrue(rewritten.contains(sessionId.toString()));
            assertFalse(rewritten.contains("base:"));
        }
    }

    @Test
    void quarantinesUnreadablePrimaryWithoutOverwritingEvidence() throws IOException {
        File folder = Files.createTempDirectory("mineur-store-corrupt-test").toFile();
        folder.deleteOnExit();
        File primary = new File(folder, "sessions.yml");
        Files.writeString(primary.toPath(), "sessions: [contenu-invalide");

        SessionStore store = new SessionStore(folder, Logger.getAnonymousLogger());
        List<MiningSessionState> loaded = store.load();

        assertTrue(loaded.isEmpty());
        assertFalse(primary.exists());
        File[] quarantined = folder.listFiles((directory, name) ->
                name.startsWith("sessions.yml.corrupt-"));
        assertNotNull(quarantined);
        assertEquals(1, quarantined.length);
        assertTrue(Files.readString(quarantined[0].toPath()).contains("contenu-invalide"));
    }


    @Test
    void restoresBackupWhenSessionsRootHasTheWrongYamlType() throws IOException {
        File folder = Files.createTempDirectory("mineur-store-root-type-test").toFile();
        folder.deleteOnExit();

        UUID worldUid = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        File primary = new File(folder, "sessions.yml");
        File backup = new File(folder, "sessions.yml.bak");

        /*
         * Ce YAML est syntaxiquement valide, mais sa racine métier est une
         * liste. Il ne doit surtout pas être interprété comme « zéro session ».
         */
        Files.writeString(primary.toPath(), "sessions: [entree-invalide]\n");
        Files.writeString(backup.toPath(), """
                sessions:
                  '0':
                    schemaVersion: 4
                    id: %s
                    world: %s
                    base: [10, 64, 12]
                    width: 4
                    length: 4
                    owner: %s
                """.formatted(sessionId, worldUid, ownerId));

        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldUid);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);

        try (MockedStatic<Bukkit> mocked = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            mocked.when(() -> Bukkit.getWorld(worldUid)).thenReturn(world);

            SessionStore store = new SessionStore(folder, Logger.getAnonymousLogger());
            List<MiningSessionState> loaded = store.load();

            assertEquals(1, loaded.size());
            assertEquals(sessionId, loaded.get(0).id);
            assertFalse(primary.exists());

            File[] quarantined = folder.listFiles((directory, name) ->
                    name.startsWith("sessions.yml.corrupt-"));
            assertNotNull(quarantined);
            assertEquals(1, quarantined.length);
        }
    }

}
