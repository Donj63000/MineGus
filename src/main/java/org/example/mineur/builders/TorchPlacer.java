package org.example.mineur.builders;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;

/**
 * Minimal lighting helper to keep the quarry safe.
 */
public final class TorchPlacer {

    private TorchPlacer() {
    }

    public static void placeWallTorch(World world, Block supportBlock, BlockFace facing) {
        if (world == null || supportBlock == null || facing == null) {
            return;
        }
        if (!supportBlock.getType().isSolid()) {
            /*
             * Ne pas écraser un bloc non solide (panneau, eau, redstone...).
             * Le support est préparé explicitement par l'appelant.
             */
            return;
        }
        Block target = supportBlock.getRelative(facing);
        if (!target.getType().isAir()) {
            return;
        }
        target.setType(Material.WALL_TORCH, false);
        if (target.getBlockData() instanceof Directional directional) {
            directional.setFacing(facing);
            target.setBlockData(directional, false);
        }
    }
}
