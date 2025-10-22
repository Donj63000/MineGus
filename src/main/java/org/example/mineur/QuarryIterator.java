package org.example.mineur;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Simple quarry iterator that sweeps the rectangular prism layer by layer.
 */
public final class QuarryIterator implements MiningIterator {

    private final World world;
    private final MiningCursor cursor;
    private final int stopY;

    public QuarryIterator(World world, MiningCursor cursor, int stopY) {
        this.world = world;
        this.cursor = cursor;
        this.stopY = stopY;
    }

    @Override
    public MiningCursor cursor() {
        return cursor;
    }

    @Override
    public boolean hasNext() {
        return cursor.y >= stopY;
    }

    @Override
    public Block next() {
        while (cursor.y >= stopY) {
            Block block = world.getBlockAt(cursor.x, cursor.y, cursor.z);
            advance();
            if (!block.getType().isAir() && block.getType() != Material.BEDROCK) {
                return block;
            }
        }
        return null;
    }

    private void advance() {
        if (cursor.scanXFirst) {
            cursor.x++;
            if (cursor.x >= cursor.minX + cursor.width) {
                cursor.x = cursor.minX;
                cursor.z++;
                if (cursor.z >= cursor.minZ + cursor.length) {
                    cursor.z = cursor.minZ;
                    cursor.y--;
                }
            }
        } else {
            cursor.z++;
            if (cursor.z >= cursor.minZ + cursor.length) {
                cursor.z = cursor.minZ;
                cursor.x++;
                if (cursor.x >= cursor.minX + cursor.width) {
                    cursor.x = cursor.minX;
                    cursor.y--;
                }
            }
        }
    }
}
