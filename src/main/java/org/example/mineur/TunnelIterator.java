package org.example.mineur;

import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Parcours d'un tronçon horizontal borné : largeur × hauteur × longueur.
 */
public final class TunnelIterator implements MiningIterator {

    private final World world;
    private final MiningCursor cursor;
    private final int worldMinY;
    private final int worldMaxY;
    private final long maxXExclusive;
    private final long maxYExclusive;
    private final long maxZExclusive;

    public TunnelIterator(World world, MiningCursor cursor, int tunnelHeight) {
        this.world = world;
        this.cursor = cursor;
        this.worldMinY = effectiveMinimumHeight(world);
        this.worldMaxY = effectiveMaximumHeight(world, worldMinY);

        cursor.width = Math.max(1, cursor.width);
        cursor.length = Math.max(1, cursor.length);
        cursor.height = Math.max(1, tunnelHeight);

        if (cursor.minY == 0 && cursor.y != 0) {
            cursor.minY = cursor.y;
        }
        cursor.minY = Math.max(worldMinY, Math.min(worldMaxY - 1, cursor.minY));
        int availableHeight = Math.max(1, worldMaxY - cursor.minY);
        cursor.height = Math.min(cursor.height, availableHeight);

        this.maxXExclusive = safeExclusiveEnd(cursor.minX, cursor.width);
        this.maxYExclusive = safeExclusiveEnd(cursor.minY, cursor.height);
        this.maxZExclusive = safeExclusiveEnd(cursor.minZ, cursor.length);
        normalizeCursor();
    }

    @Override
    public MiningCursor cursor() {
        return cursor;
    }

    @Override
    public boolean hasNext() {
        return !cursor.exhausted
                && cursor.z >= cursor.minZ
                && cursor.z < maxZExclusive
                && cursor.x >= cursor.minX
                && cursor.x < maxXExclusive
                && cursor.y >= cursor.minY
                && cursor.y < maxYExclusive
                && cursor.y >= worldMinY
                && cursor.y < worldMaxY;
    }

    @Override
    public Block next() {
        int inspected = 0;
        while (hasNext() && inspected < DEFAULT_SCAN_BUDGET) {
            Block block = world.getBlockAt(cursor.x, cursor.y, cursor.z);
            advance();
            inspected++;
            if (block != null && MiningBlockPolicy.isCandidate(block.getType())) {
                return block;
            }
        }
        return null;
    }

    private void normalizeCursor() {
        if (cursor.exhausted) {
            return;
        }
        if (cursor.z >= maxZExclusive) {
            return;
        }
        if (cursor.z < cursor.minZ) {
            cursor.z = cursor.minZ;
        }
        if (cursor.x < cursor.minX || cursor.x >= maxXExclusive) {
            cursor.x = cursor.minX;
        }
        if (cursor.y < cursor.minY || cursor.y >= maxYExclusive) {
            cursor.y = cursor.minY;
        }
    }

    private void advance() {
        if ((long) cursor.x + 1L < maxXExclusive) {
            cursor.x++;
            return;
        }
        cursor.x = cursor.minX;

        if ((long) cursor.y + 1L < maxYExclusive) {
            cursor.y++;
            return;
        }
        cursor.y = cursor.minY;

        if ((long) cursor.z + 1L < maxZExclusive) {
            cursor.z++;
            return;
        }

        /*
         * Une sentinelle booléenne est sérialisée avec le curseur. Elle reste
         * fiable même lorsque maxZExclusive vaut Integer.MAX_VALUE + 1.
         */
        cursor.exhausted = true;
    }

    private static long safeExclusiveEnd(int start, int size) {
        return Math.min((long) Integer.MAX_VALUE + 1L, (long) start + Math.max(1, size));
    }

    private static int effectiveMinimumHeight(World world) {
        int minimum = world.getMinHeight();
        int maximum = world.getMaxHeight();
        return maximum > minimum ? minimum : -64;
    }

    private static int effectiveMaximumHeight(World world, int minimum) {
        int maximum = world.getMaxHeight();
        return maximum > minimum ? maximum : 320;
    }
}
