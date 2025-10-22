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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        state.useBarrelMaster = true;
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
            assertEquals(1, restored.containers.size());
        }
    }
}
