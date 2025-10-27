package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.List;
import java.util.Queue;
import java.util.random.RandomGenerator;

/**
 * Muraille périphérique renforcée :
 * └ 5 blocs de haut        (Stone / Mossy / Cracked mix)
 * └ Crenelage 1 sur 2      (WALL + SLAB)
 * └ Une seule porte principale (sud)
 * └ Chemin de ronde + tours d’angle + gatehouse
 *
 * Tout est poussé dans {@code Queue<Runnable>} ; la méthode reste
 * entièrement thread-safe puisqu’elle ne touche pas directement au monde.
 */
public final class WallBuilder {

    private WallBuilder() {}
    private static final int WALL_HEIGHT      = 5;
    private static final int CRENELLE_INTERVAL = 2;   // 0 1 (mur) 2 (cre) 3 4 …
    private static final int GATE_HALF_WIDTH  = 2;
    private static final int TOWER_RADIUS     = 1;
    private static final int TOWER_EXTRA_HEIGHT = 3;

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

                /* ——— Porte principale (sud uniquement) ——— */
                boolean inSouthGate = (z == maxZ) && Math.abs(x - cx) <= GATE_HALF_WIDTH;
                if (inSouthGate) {
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

        /* chemin de ronde intérieur */
        addInnerWalkway(q, sb, minX, maxX, minZ, maxZ, baseY);

        /* tours d'angle */
        buildCornerTowers(q, sb, minX, maxX, minZ, maxZ, baseY);

        /* gatehouse + déco unique */
        buildGatehouse(q, sb, cx, maxZ, baseY);
        addGateLighting(q, sb, cx, maxZ, baseY);
    }

    private static void addInnerWalkway(Queue<Runnable> q,
                                        TerrainManager.SetBlock sb,
                                        int minX, int maxX,
                                        int minZ, int maxZ,
                                        int baseY) {
        int y = baseY + WALL_HEIGHT;
        int innerMinX = minX + 1;
        int innerMaxX = maxX - 1;
        int innerMinZ = minZ + 1;
        int innerMaxZ = maxZ - 1;

        for (int x = innerMinX; x <= innerMaxX; x++) {
            int fx = x;
            q.add(() -> sb.set(fx, y, innerMinZ, Material.STONE_BRICKS));
            q.add(() -> sb.set(fx, y, innerMaxZ, Material.STONE_BRICKS));
        }
        for (int z = innerMinZ; z <= innerMaxZ; z++) {
            int fz = z;
            q.add(() -> sb.set(innerMinX, y, fz, Material.STONE_BRICKS));
            q.add(() -> sb.set(innerMaxX, y, fz, Material.STONE_BRICKS));
        }
    }

    private static void buildCornerTowers(Queue<Runnable> q,
                                          TerrainManager.SetBlock sb,
                                          int minX, int maxX,
                                          int minZ, int maxZ,
                                          int baseY) {
        buildTower(q, sb, minX, minZ, baseY);
        buildTower(q, sb, minX, maxZ, baseY);
        buildTower(q, sb, maxX, minZ, baseY);
        buildTower(q, sb, maxX, maxZ, baseY);
    }

    private static void buildTower(Queue<Runnable> q,
                                   TerrainManager.SetBlock sb,
                                   int cx, int cz,
                                   int baseY) {
        int top = baseY + WALL_HEIGHT + TOWER_EXTRA_HEIGHT;
        for (int dx = -TOWER_RADIUS; dx <= TOWER_RADIUS; dx++) {
            for (int dz = -TOWER_RADIUS; dz <= TOWER_RADIUS; dz++) {
                int fx = cx + dx;
                int fz = cz + dz;
                for (int y = baseY + 1; y <= top; y++) {
                    int fy = y;
                    q.add(() -> sb.set(fx, fy, fz, Material.STONE_BRICKS));
                }
            }
        }

        int roofY = top + 1;
        for (int dx = -TOWER_RADIUS - 1; dx <= TOWER_RADIUS + 1; dx++) {
            for (int dz = -TOWER_RADIUS - 1; dz <= TOWER_RADIUS + 1; dz++) {
                int fx = cx + dx;
                int fz = cz + dz;
                q.add(() -> sb.set(fx, roofY, fz, Material.SMOOTH_STONE_SLAB));
            }
        }

        int torchY = roofY + 1;
        q.add(() -> sb.set(cx, torchY, cz, Material.LANTERN));
    }

    private static void buildGatehouse(Queue<Runnable> q,
                                       TerrainManager.SetBlock sb,
                                       int centerX, int gateZ,
                                       int baseY) {
        int outerMinX = centerX - GATE_HALF_WIDTH - 1;
        int outerMaxX = centerX + GATE_HALF_WIDTH + 1;
        int outerMinZ = gateZ - 1;
        int outerMaxZ = gateZ + 2;
        int roofY = baseY + WALL_HEIGHT + 2;

        for (int x = outerMinX; x <= outerMaxX; x++) {
            for (int z = outerMinZ; z <= outerMaxZ; z++) {
                boolean corridor = (Math.abs(x - centerX) <= GATE_HALF_WIDTH)
                        && (z >= gateZ && z <= gateZ + 1);
                if (corridor) {
                    continue;
                }
                boolean wall = (x == outerMinX || x == outerMaxX || z == outerMinZ || z == outerMaxZ);
                if (!wall) {
                    continue;
                }
                for (int y = baseY + 1; y <= roofY; y++) {
                    int fy = y;
                    boolean archOpening = (y <= baseY + WALL_HEIGHT)
                            && (z == gateZ || z == gateZ + 1)
                            && Math.abs(x - centerX) <= GATE_HALF_WIDTH;
                    if (archOpening) {
                        continue;
                    }
                    Material mat = (y == roofY) ? Material.SMOOTH_STONE_SLAB : Material.STONE_BRICKS;
                    int fx = x, fz = z;
                    q.add(() -> sb.set(fx, fy, fz, mat));
                }
            }
        }

        /* rampe intérieure pour rejoindre la route centrale */
        int walkwayY = baseY + 1;
        for (int dz = 1; dz <= 3; dz++) {
            int z = gateZ + dz;
            for (int x = centerX - GATE_HALF_WIDTH; x <= centerX + GATE_HALF_WIDTH; x++) {
                int fx = x, fz = z;
                q.add(() -> sb.set(fx, walkwayY, fz, Material.STONE_BRICKS));
            }
        }
    }

    private static void addGateLighting(Queue<Runnable> q,
                                        TerrainManager.SetBlock sb,
                                        int centerX, int gateZ,
                                        int baseY) {
        int torchY = baseY + 3;
        int left = centerX - GATE_HALF_WIDTH - 1;
        int right = centerX + GATE_HALF_WIDTH + 1;
        q.add(() -> sb.set(left, torchY, gateZ - 1, Material.TORCH));
        q.add(() -> sb.set(right, torchY, gateZ - 1, Material.TORCH));
    }
}
