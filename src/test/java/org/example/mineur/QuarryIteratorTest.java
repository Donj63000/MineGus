package org.example.mineur;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;

import static org.junit.jupiter.api.Assertions.*;

@Disabled("MockBukkit 1.21.x ne fournit pas encore les registres nécessaires (Biome registry)")
class QuarryIteratorTest {

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("quarry-world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void nextSkipsAirAndBedrockAndStopsBelowLimit() {
        MiningCursor cursor = new MiningCursor(new Location(world, 0, 65, 0), 2, 1);
        cursor.scanXFirst = true;

        world.getBlockAt(0, 65, 0).setType(Material.STONE);
        world.getBlockAt(1, 65, 0).setType(Material.AIR);
        world.getBlockAt(0, 64, 0).setType(Material.BEDROCK);
        world.getBlockAt(1, 64, 0).setType(Material.GOLD_ORE);

        QuarryIterator iterator = new QuarryIterator(world, cursor, 64);

        assertTrue(iterator.hasNext());

        Block first = iterator.next();
        assertNotNull(first);
        assertEquals(Material.STONE, first.getType());

        Block second = iterator.next();
        assertNotNull(second);
        assertEquals(Material.GOLD_ORE, second.getType());

        assertFalse(iterator.hasNext());
        assertNull(iterator.next());
        assertEquals(63, cursor.y);
        assertEquals(cursor.minX, cursor.x);
        assertEquals(cursor.minZ, cursor.z);
    }
}
