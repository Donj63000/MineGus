package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.Queue;

public final class TerrainManager {

    private TerrainManager() {}

    @FunctionalInterface
    public interface SetBlock {
        void set(int x, int y, int z, Material mat);
    }

    /** Remblaye et aplanit la zone autour de {@code center}. */
    public static void prepareGround(Location center, int rx, int rz, int baseY,
                                     Queue<Runnable> q, SetBlock sb) {

        World w = center.getWorld();

        for (int dx = -rx - 2; dx <= rx + 2; dx++) {
            for (int dz = -rz - 2; dz <= rz + 2; dz++) {
                int x = center.getBlockX() + dx;
                int z = center.getBlockZ() + dz;
                int topY = w.getHighestBlockYAt(x, z);

                // remplissage (Dirt) sous le niveau cible
                for (int y = topY; y < baseY; y++) {
                    int fx = x, fy = y, fz = z;
                    q.add(() -> sb.set(fx, fy, fz, Material.DIRT));
                }
                // suppression au‑dessus
                for (int y = baseY + 1; y <= topY; y++) {
                    int fx = x, fy = y, fz = z;
                    q.add(() -> sb.set(fx, fy, fz, Material.AIR));
                }
                // surface
                int fx = x, fz = z;
                q.add(() -> sb.set(fx, baseY, fz, Material.GRASS_BLOCK));
            }
        }
    }

    /** Vérifie qu’aucun bloc solide n’empiète dans le périmètre. */
    public static boolean isAreaClear(World w, Location c, int rx, int rz) {
        for (int dx = -rx; dx <= rx; dx++) {
            for (int dz = -rz; dz <= rz; dz++) {
                int x = c.getBlockX() + dx;
                int z = c.getBlockZ() + dz;
                int y = w.getHighestBlockYAt(x, z);
                Material m = w.getBlockAt(x, y, z).getType();
                if (m != Material.GRASS_BLOCK && m != Material.AIR) return false;
            }
        }
        return true;
    }
}
