package org.example.mineur;

import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VeinFirstIteratorTest {

    @Test
    void minesNearbyVeinBeforeResumingNormalRoute() {
        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(256);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);

        Map<Coord, Block> blocks = new HashMap<>();
        Block normal = putBlock(blocks, world, 0, 64, 0, Material.STONE);
        Block firstOre = putBlock(blocks, world, 1, 64, 0, Material.DIAMOND_ORE);
        Block secondOre = putBlock(blocks, world, 2, 64, 0, Material.DIAMOND_ORE);
        Block afterVein = putBlock(blocks, world, 8, 64, 0, Material.DEEPSLATE);

        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int x = invocation.getArgument(0);
            int y = invocation.getArgument(1);
            int z = invocation.getArgument(2);
            return blocks.computeIfAbsent(new Coord(x, y, z), coord -> block(world, coord.x(), coord.y(), coord.z(), Material.STONE));
        });

        MiningCursor cursor = new MiningCursor(new Location(world, 0, 64, 0), 1, 1);
        MiningIterator delegate = new FixedIterator(cursor, normal, afterVein);
        VeinFirstIterator iterator = new VeinFirstIterator(world, delegate, 3, 10);

        assertTrue(iterator.hasNext());
        assertSame(firstOre, iterator.next());
        assertSame(secondOre, iterator.next());
        assertSame(normal, iterator.next());
        assertSame(afterVein, iterator.next());
        assertFalse(iterator.hasNext());
    }

    @Test
    void respectsVeinBlockLimit() {
        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(256);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);

        Map<Coord, Block> blocks = new HashMap<>();
        Block normal = putBlock(blocks, world, 0, 64, 0, Material.STONE);
        Block firstOre = putBlock(blocks, world, 1, 64, 0, Material.GOLD_ORE);
        putBlock(blocks, world, 2, 64, 0, Material.GOLD_ORE);

        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int x = invocation.getArgument(0);
            int y = invocation.getArgument(1);
            int z = invocation.getArgument(2);
            return blocks.computeIfAbsent(new Coord(x, y, z), coord -> block(world, coord.x(), coord.y(), coord.z(), Material.STONE));
        });

        MiningCursor cursor = new MiningCursor(new Location(world, 0, 64, 0), 1, 1);
        MiningIterator delegate = new FixedIterator(cursor, normal);
        VeinFirstIterator iterator = new VeinFirstIterator(world, delegate, 3, 1);

        assertSame(firstOre, iterator.next());
        assertSame(normal, iterator.next());
        assertFalse(iterator.hasNext());
    }

    private Block putBlock(Map<Coord, Block> blocks, World world, int x, int y, int z, Material type) {
        Block block = block(world, x, y, z, type);
        blocks.put(new Coord(x, y, z), block);
        return block;
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

    private static final class FixedIterator implements MiningIterator {
        private final MiningCursor cursor;
        private final Block[] blocks;
        private int index;

        private FixedIterator(MiningCursor cursor, Block... blocks) {
            this.cursor = cursor;
            this.blocks = blocks;
        }

        @Override
        public Block next() {
            if (!hasNext()) {
                return null;
            }
            return blocks[index++];
        }

        @Override
        public boolean hasNext() {
            return index < blocks.length;
        }

        @Override
        public MiningCursor cursor() {
            return cursor;
        }
    }
}
