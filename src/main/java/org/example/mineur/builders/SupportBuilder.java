package org.example.mineur.builders;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Places occasional stone supports for visual variety and safety.
 */
public final class SupportBuilder {

    private SupportBuilder() {
    }

    public static void placeSupportColumn(World world, Block topBlock, int depth) {
        if (world == null || topBlock == null) {
            return;
        }
        Block current = topBlock;
        for (int i = 0; i < depth; i++) {
            if (!current.getType().isAir()) {
                break;
            }
            current = current.getRelative(0, -1, 0);
        }
        for (int i = 0; i < depth; i++) {
            Block target = current.getRelative(0, i, 0);
            if (target.getType().isAir()) {
                target.setType(Material.STONE_BRICKS, false);
            }
        }
    }
}
