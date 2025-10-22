package org.example.mineur.builders;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Minimal lighting helper to keep the quarry safe.
 */
public final class TorchPlacer {

    private TorchPlacer() {
    }

    public static void placeTorchIfNeeded(World world, Block supportBlock) {
        if (world == null || supportBlock == null) {
            return;
        }
        Block above = supportBlock.getRelative(0, 1, 0);
        if (!supportBlock.getType().isSolid()) {
            return;
        }
        if (!above.getType().isAir()) {
            return;
        }
        above.setType(Material.TORCH, false);
    }
}
