package org.example.mineur;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Iterator for the TUNNEL pattern.
 *
 * Contrary to a quarry, this pattern does not dig downward until stop-at-y. It
 * mines a bounded horizontal section: width x height x length. The cursor stores
 * the current position and the tunnel floor in minY, so the session can be saved
 * and resumed cleanly.
 */
public final class TunnelIterator implements MiningIterator {

    private final World world;
    private final MiningCursor cursor;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public TunnelIterator(World world, MiningCursor cursor, int tunnelHeight) {
        this.world = world;
        this.cursor = cursor;
        this.cursor.height = Math.max(1, tunnelHeight);
        if (this.cursor.minY == 0 && this.cursor.y != 0) {
            this.cursor.minY = this.cursor.y;
        }
        this.maxX = this.cursor.minX + Math.max(1, this.cursor.width);
        this.maxY = this.cursor.minY + Math.max(1, this.cursor.height);
        this.maxZ = this.cursor.minZ + Math.max(1, this.cursor.length);
        normalizeCursor();
    }

    @Override
    public MiningCursor cursor() {
        return cursor;
    }

    @Override
    public boolean hasNext() {
        return cursor.z < maxZ
                && cursor.z >= cursor.minZ
                && cursor.x >= cursor.minX
                && cursor.x < maxX
                && cursor.y >= cursor.minY
                && cursor.y < maxY;
    }

    @Override
    public Block next() {
        while (hasNext()) {
            Block block = world.getBlockAt(cursor.x, cursor.y, cursor.z);
            advance();
            if (isMineable(block.getType())) {
                return block;
            }
        }
        return null;
    }

    private void normalizeCursor() {
        if (cursor.z >= maxZ) {
            return;
        }
        if (cursor.z < cursor.minZ) {
            cursor.z = cursor.minZ;
        }
        if (cursor.x < cursor.minX || cursor.x >= maxX) {
            cursor.x = cursor.minX;
        }
        if (cursor.y < cursor.minY || cursor.y >= maxY) {
            cursor.y = cursor.minY;
        }
    }

    private void advance() {
        cursor.x++;
        if (cursor.x >= maxX) {
            cursor.x = cursor.minX;
            cursor.y++;
            if (cursor.y >= maxY) {
                cursor.y = cursor.minY;
                cursor.z++;
            }
        }
    }

    private boolean isMineable(Material type) {
        return type != null
                && !isAir(type)
                && type != Material.BEDROCK
                && type != Material.BARRIER
                && type != Material.COMMAND_BLOCK
                && type != Material.CHAIN_COMMAND_BLOCK
                && type != Material.REPEATING_COMMAND_BLOCK
                && type != Material.STRUCTURE_BLOCK
                && type != Material.JIGSAW
                && type != Material.END_PORTAL
                && type != Material.END_PORTAL_FRAME
                && type != Material.NETHER_PORTAL;
    }

    private boolean isAir(Material type) {
        return type == Material.AIR
                || type == Material.CAVE_AIR
                || type == Material.VOID_AIR;
    }
}
