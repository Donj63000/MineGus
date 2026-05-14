package org.example.mineur;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TunnelIteratorTest {

    @Test
    void minesHorizontalBoundsWidthHeightLength() {
        World world = mock(World.class);
        Block first = block(world, 10, 20, 30, Material.STONE);
        Block skippedAir = block(world, 11, 20, 30, Material.AIR);
        Block second = block(world, 10, 21, 30, Material.DIAMOND_ORE);
        Block skippedBedrock = block(world, 11, 21, 30, Material.BEDROCK);
        Block third = block(world, 10, 20, 31, Material.DEEPSLATE);
        Block skippedBarrier = block(world, 11, 20, 31, Material.BARRIER);
        Block fourth = block(world, 10, 21, 31, Material.IRON_ORE);
        Block fifth = block(world, 11, 21, 31, Material.COPPER_ORE);

        Map<Coord, Block> blocks = new HashMap<>();
        blocks.put(new Coord(10, 20, 30), first);
        blocks.put(new Coord(11, 20, 30), skippedAir);
        blocks.put(new Coord(10, 21, 30), second);
        blocks.put(new Coord(11, 21, 30), skippedBedrock);
        blocks.put(new Coord(10, 20, 31), third);
        blocks.put(new Coord(11, 20, 31), skippedBarrier);
        blocks.put(new Coord(10, 21, 31), fourth);
        blocks.put(new Coord(11, 21, 31), fifth);
        when(world.getBlockAt(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> blocks.get(new Coord(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2))));

        MiningCursor cursor = new MiningCursor(new Location(world, 10, 20, 30), 2, 2);
        TunnelIterator iterator = new TunnelIterator(world, cursor, 2);

        assertSame(first, iterator.next());
        assertSame(second, iterator.next());
        assertSame(third, iterator.next());
        assertSame(fourth, iterator.next());
        assertSame(fifth, iterator.next());
        assertFalse(iterator.hasNext());
        assertNull(iterator.next());
        assertEquals(10, cursor.x);
        assertEquals(20, cursor.y);
        assertEquals(32, cursor.z);
    }

    @Test
    void normalizesOutOfBoundsCursorBeforeMining() {
        World world = mock(World.class);
        Block block = block(world, 4, 12, 7, Material.STONE);
        when(world.getBlockAt(4, 12, 7)).thenReturn(block);

        MiningCursor cursor = new MiningCursor(new Location(world, 4, 12, 7), 1, 1);
        cursor.x = 99;
        cursor.y = 99;
        cursor.z = 7;

        TunnelIterator iterator = new TunnelIterator(world, cursor, 1);

        Block mined = iterator.next();

        assertNotNull(mined);
        assertEquals(Material.STONE, mined.getType());
        assertFalse(iterator.hasNext());
    }

    private Block block(World world, int x, int y, int z, Material type) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        when(block.getType()).thenReturn(type);
        return block;
    }

    private record Coord(int x, int y, int z) {
    }
}
