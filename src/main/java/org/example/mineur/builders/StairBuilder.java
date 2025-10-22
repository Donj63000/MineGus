package org.example.mineur.builders;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.BlockFace;

/**
 * Builds a simple staircase along one edge of the quarry.
 */
public final class StairBuilder {

    private StairBuilder() {
    }

    public static void ensureStair(World world, Block start, BlockFace facing, int depth) {
        if (world == null || start == null) {
            return;
        }
        Block current = start;
        for (int i = 0; i < depth; i++) {
            Block stairBlock = current.getRelative(0, -i, 0);
            if (!stairBlock.getType().isAir()) {
                continue;
            }
            stairBlock.setType(Material.OAK_STAIRS, false);
            BlockData data = stairBlock.getBlockData();
            if (data instanceof Stairs stairs) {
                stairs.setFacing(facing);
                stairs.setHalf(Stairs.Half.BOTTOM);
                stairBlock.setBlockData(stairs, false);
            }
        }
    }
}
