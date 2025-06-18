package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

import java.util.List;
import java.util.Queue;
import java.util.random.RandomGenerator;

/**
 * Muraille périphérique “stylée” :
 * └ 5 blocs de haut        (Stone / Mossy / Cracked mix)
 * └ Crenelage 1 sur 2      (WALL + SLAB)
 * └ Portes cardinales 3 × 5
 * └ Torches murales sur les piliers d’angle des portes
 *
 * Tout est poussé dans {@code Queue<Runnable>} ; la méthode reste
 * entièrement thread-safe puisqu’elle ne touche pas directement au monde.
 */
public final class WallBuilder {

    private WallBuilder() {}
    private static final int WALL_HEIGHT      = 5;
    private static final int CRENELLE_INTERVAL = 2;   // 0 1 (mur) 2 (cre) 3 4 …

    /* palette « pierre » pour casser la monotonie */
    private static final List<Material> BODY = List.of(
            Material.STONE_BRICKS,
            Material.MOSSY_STONE_BRICKS,
            Material.CRACKED_STONE_BRICKS
    );

    /**
     * @param center  centre (X / Z) du village
     * @param rx,rz   demi-dimensions internes (hors mur)
     * @param baseY   niveau du sol aplanit
     * @param wallMat matériau « principal » (sert de fallback)
     */
    public static void build(Location center, int rx, int rz, int baseY,
                             Material wallMat,
                             Queue<Runnable> q,
                             TerrainManager.SetBlock sb) {

        World w  = center.getWorld();
        int cx   = center.getBlockX();
        int cz   = center.getBlockZ();
        int yTop = baseY + WALL_HEIGHT;

        RandomGenerator R = RandomGenerator.getDefault();

        /* coordonnées extrêmes (+1 pour l’épaisseur du mur) */
        int minX = cx - rx - 1, maxX = cx + rx + 1;
        int minZ = cz - rz - 1, maxZ = cz + rz + 1;

        /* === 1. Corps du mur & créneaux ====================== */
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {

                boolean edge = (x == minX || x == maxX || z == minZ || z == maxZ);
                if (!edge) continue;

                /* ——— PORTES 3 × 5 ——— */
                boolean inNorthGate = (z == minZ) && Math.abs(x - cx) <= 1;
                boolean inSouthGate = (z == maxZ) && Math.abs(x - cx) <= 1;
                boolean inWestGate  = (x == minX) && Math.abs(z - cz) <= 1;
                boolean inEastGate  = (x == maxX) && Math.abs(z - cz) <= 1;
                if (inNorthGate || inSouthGate || inWestGate || inEastGate) {
                    /* on laisse l’ouverture vide */
                    continue;
                }

                /* Mur : 5 blocs → choix aléatoire dans la palette */
                Material bodyMat = BODY.get(R.nextInt(BODY.size()));
                for (int y = baseY + 1; y <= yTop; y++) {
                    int fx = x, fy = y, fz = z;
                    q.add(() -> sb.set(fx, fy, fz, bodyMat));
                }

                /* Créneaux : on surélève 1 bloc sur 1 colonne / 2 */
                boolean crenelle = ((x + z) % CRENELLE_INTERVAL == 0);
                if (crenelle) {
                    int fx = x, fz = z;
                    q.add(() -> sb.set(fx, yTop + 1, fz, Material.STONE_BRICK_WALL));
                    q.add(() -> sb.set(fx, yTop + 2, fz, Material.SMOOTH_STONE_SLAB));
                } else {
                    /* couronnement simple pour les intervalles */
                    int fx = x, fz = z;
                    q.add(() -> sb.set(fx, yTop + 1, fz, Material.SMOOTH_STONE_SLAB));
                }
            }
        }

        /* === 2. Torches sur les piliers latéraux des portes === */
        addGateTorchPair(q, sb, cx - 2, baseY + 3, minZ, BlockFace.SOUTH); // Nord-Ouest
        addGateTorchPair(q, sb, cx + 2, baseY + 3, minZ, BlockFace.SOUTH); // Nord-Est

        addGateTorchPair(q, sb, cx - 2, baseY + 3, maxZ, BlockFace.NORTH); // Sud-Ouest
        addGateTorchPair(q, sb, cx + 2, baseY + 3, maxZ, BlockFace.NORTH); // Sud-Est

        addGateTorchPair(q, sb, minX, baseY + 3, cz - 2, BlockFace.EAST);  // Ouest-Nord
        addGateTorchPair(q, sb, minX, baseY + 3, cz + 2, BlockFace.EAST);  // Ouest-Sud

        addGateTorchPair(q, sb, maxX, baseY + 3, cz - 2, BlockFace.WEST);  // Est-Nord
        addGateTorchPair(q, sb, maxX, baseY + 3, cz + 2, BlockFace.WEST);  // Est-Sud
    }

    /* ------------------------------------------------------------------ */
    /*  Torches murales : petit helper                                    */
    /* ------------------------------------------------------------------ */
    private static void addGateTorchPair(Queue<Runnable> q,
                                         TerrainManager.SetBlock sb,
                                         int x, int y, int z,
                                         BlockFace facing) {

        q.add(() -> sb.set(x, y, z, Material.WALL_TORCH));  // pose brute
        /* Orientation correcte : si vous voulez affiner, activez la partie
           ci-dessous ; elle nécessite un accès direct au monde, donc à
           appeler dans le thread principal (d’où la lambda Runnable). */
        /*
        q.add(() -> {
            Block b = Bukkit.getWorlds().get(0).getBlockAt(x, y, z);
            if (b.getType() == Material.WALL_TORCH) {
                WallTorch data = (WallTorch) b.getBlockData();
                data.setFacing(facing);
                b.setBlockData(data, false);
            }
        });
        */
    }
}
