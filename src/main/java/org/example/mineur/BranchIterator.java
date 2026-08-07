package org.example.mineur;

import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Parcours du motif BRANCH :
 * une galerie centrale dans l'axe X et des branches régulières dans l'axe Z.
 */
public final class BranchIterator implements MiningIterator {

    private final World world;
    private final MiningCursor cursor;
    private final int worldMinY;
    private final int worldMaxY;
    private final int stopY;
    private final int branchSpacing;
    private final int galleryStartZ;
    private final int galleryEndZ;
    private final long maxXExclusive;
    private final long maxZExclusive;

    public BranchIterator(World world,
                          MiningCursor cursor,
                          int stopY,
                          int branchSpacing,
                          int galleryWidth) {
        this.world = world;
        this.cursor = cursor;
        this.worldMinY = effectiveMinimumHeight(world);
        this.worldMaxY = effectiveMaximumHeight(world, worldMinY);
        this.stopY = Math.max(worldMinY, Math.min(stopY, worldMaxY - 1));
        this.branchSpacing = Math.max(1, branchSpacing);

        cursor.width = Math.max(1, cursor.width);
        cursor.length = Math.max(1, cursor.length);
        this.maxXExclusive = safeExclusiveEnd(cursor.minX, cursor.width);
        this.maxZExclusive = safeExclusiveEnd(cursor.minZ, cursor.length);

        int effectiveWidth = Math.min(cursor.length, Math.max(1, galleryWidth));
        this.galleryStartZ = Math.max(0, (cursor.length - effectiveWidth) / 2);
        this.galleryEndZ = galleryStartZ + effectiveWidth - 1;
        normalizeCursor();
    }

    @Override
    public MiningCursor cursor() {
        return cursor;
    }

    @Override
    public boolean hasNext() {
        return !cursor.exhausted
                && cursor.y >= stopY
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

            if (block != null
                    && isInPattern(block)
                    && MiningBlockPolicy.isCandidate(block.getType())) {
                return block;
            }
        }
        return null;
    }

    private boolean isInPattern(Block block) {
        long relativeX = (long) block.getX() - cursor.minX;
        long relativeZ = (long) block.getZ() - cursor.minZ;

        if (relativeZ >= galleryStartZ && relativeZ <= galleryEndZ) {
            return true;
        }
        return relativeX >= 0L && relativeX % branchSpacing == 0L;
    }

    private void normalizeCursor() {
        if (cursor.exhausted) {
            return;
        }
        if (cursor.x < cursor.minX || cursor.x >= maxXExclusive) {
            cursor.x = cursor.minX;
        }
        if (cursor.z < cursor.minZ || cursor.z >= maxZExclusive) {
            cursor.z = cursor.minZ;
        }
        if (cursor.y >= worldMaxY) {
            cursor.y = worldMaxY - 1;
        }
    }

    private void advance() {
        if (cursor.scanXFirst) {
            if ((long) cursor.x + 1L < maxXExclusive) {
                cursor.x++;
                return;
            }
            cursor.x = cursor.minX;
            if ((long) cursor.z + 1L < maxZExclusive) {
                cursor.z++;
                return;
            }
            cursor.z = cursor.minZ;
            finishLayer();
            return;
        }

        if ((long) cursor.z + 1L < maxZExclusive) {
            cursor.z++;
            return;
        }
        cursor.z = cursor.minZ;
        if ((long) cursor.x + 1L < maxXExclusive) {
            cursor.x++;
            return;
        }
        cursor.x = cursor.minX;
        finishLayer();
    }

    private void finishLayer() {
        if (cursor.y <= stopY) {
            cursor.exhausted = true;
            return;
        }
        cursor.y--;
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
