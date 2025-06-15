package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.Queue;

/** Génère la muraille périphérique (pierre + dalles). */
public final class WallBuilder {

    private WallBuilder() {}

    public static void build(Location center, int rx, int rz, int baseY,
                             Material wallMaterial,
                             Queue<Runnable> q,
                             TerrainManager.SetBlock sb) {

        World w = center.getWorld();

        for (int dx = -rx - 1; dx <= rx + 1; dx++) {
            for (int dz = -rz - 1; dz <= rz + 1; dz++) {

                boolean edge = Math.abs(dx) == rx + 1 || Math.abs(dz) == rz + 1;
                if (!edge) continue;

                int x = center.getBlockX() + dx;
                int z = center.getBlockZ() + dz;
                int groundY = w.getHighestBlockYAt(x, z);

                // colonnes de murs
                for (int y = groundY + 1; y <= baseY + 5; y++) {
                    int fx = x, fy = y, fz = z;
                    q.add(() -> sb.set(fx, fy, fz, wallMaterial));
                }
                // dalle de couronnement
                int fx = x, fz = z, topY = baseY + 6;
                q.add(() -> sb.set(fx, topY, fz, Material.SMOOTH_STONE_SLAB));
            }
        }
    }
}
