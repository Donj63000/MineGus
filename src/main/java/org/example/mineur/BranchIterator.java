package org.example.mineur;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Iterator pour le pattern BRANCH :
 * - Une grande galerie principale au milieu de la zone (dans l'axe X).
 * - Des branches perpendiculaires régulières (tous les N blocs sur X).
 *
 * On réutilise le MiningCursor pour le balayage, mais on ne renvoie que
 * les blocs qui appartiennent réellement au pattern.
 */
public final class BranchIterator implements MiningIterator {

    private final World world;
    private final MiningCursor cursor;
    private final int stopY;
    private final int branchSpacing;
    private final int galleryHalfWidth;

    public BranchIterator(World world,
                          MiningCursor cursor,
                          int stopY,
                          int branchSpacing,
                          int galleryWidth) {
        this.world = world;
        this.cursor = cursor;
        this.stopY = stopY;
        this.branchSpacing = Math.max(1, branchSpacing);
        int safeGalleryWidth = Math.max(1, galleryWidth);
        this.galleryHalfWidth = (safeGalleryWidth - 1) / 2;
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

            if (block.getType().isAir() || block.getType() == Material.BEDROCK) {
                continue;
            }

            if (isInPattern(block)) {
                return block;
            }
        }
        return null;
    }

    private boolean isInPattern(Block block) {
        int rx = block.getX() - cursor.minX; // 0 .. width - 1
        int rz = block.getZ() - cursor.minZ; // 0 .. length - 1

        // Galerie principale : bande centrée en Z
        int length = Math.max(1, cursor.length);
        int midZ = length / 2;
        if (Math.abs(rz - midZ) <= galleryHalfWidth) {
            return true;
        }

        // Branches : tous les branchSpacing blocs en X, sur toute la longueur
        if (rx >= 0 && branchSpacing > 0 && rx % branchSpacing == 0) {
            return true;
        }

        return false;
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
