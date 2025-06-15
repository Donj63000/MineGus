package org.example;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.EntityType;

import java.util.*;

/**
 * Bâtiments décoratifs : maisons pivotées, toits, cheminées.
 * Toutes les méthodes sont statiques ; les accès bloc passent par Village#setBlockTracked().
 */
public final class Batiments {

    private Batiments() {}

    private static final Random RNG = new Random();

    /* =================== API principale =================== */

    /** Construit une maison pivotée (0/90/180/270°) et renvoie la liste d’actions bloc. */
    public static List<Runnable> buildHouseRotatedActions(
            World w, Location start, int width, int depth,
            int rotationDeg, Village ctx) {

        List<Runnable> res = new ArrayList<>();
        int ox = start.getBlockX(), oy = start.getBlockY(), oz = start.getBlockZ();
        int wallH = 4;

        /* ---------- palette aléatoire ---------- */
        Material logMat    = pick(RNG, Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG);
        Material plankMat  = pick(RNG, Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS);
        Material stairMat  = switch (plankMat) {
            case SPRUCE_PLANKS -> Material.SPRUCE_STAIRS;
            case BIRCH_PLANKS  -> Material.BIRCH_STAIRS;
            default            -> Material.OAK_STAIRS;
        };
        Material windowMat = RNG.nextBoolean() ? Material.GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE;

        /* ---------- murs extérieurs + fenêtres ---------- */
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++) {
                boolean edgeX = (dx == 0 || dx == width - 1);
                boolean edgeZ = (dz == 0 || dz == depth - 1);
                if (!(edgeX || edgeZ)) continue;

                for (int h = 0; h < wallH; h++) {
                    int[] p = rotate(dx, dz, rotationDeg);
                    int fx = ox + p[0], fy = oy + 1 + h, fz = oz + p[1];

                    boolean corner = edgeX && edgeZ;
                    boolean windowLayer = (h == 1);
                    boolean frontBack = edgeZ && !edgeX && (dx == 2 || dx == width - 3);
                    boolean sides     = edgeX && !edgeZ && (dz == 2 || dz == depth - 3);

                    Material mat = (windowLayer && (frontBack || sides))
                            ? windowMat
                            : (corner ? logMat : plankMat);

                    res.add(() -> ctx.setBlockTracked(w, fx, fy, fz, mat));
                }
            }

        /* ---------- plancher ---------- */
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++) {
                int[] p = rotate(dx, dz, rotationDeg);
                res.add(() -> ctx.setBlockTracked(w, ox + p[0], oy, oz + p[1], plankMat));
            }

        /* ---------- porte + lanterne ---------- */
        int[] door = rotate(width / 2, 0, rotationDeg);
        res.add(() -> ctx.setBlockTracked(w, ox + door[0], oy + 1, oz + door[1], Material.OAK_DOOR));
        res.add(() -> ctx.setBlockTracked(w, ox + door[0], oy + 2, oz + door[1], Material.OAK_DOOR));
        res.add(() -> ctx.setBlockTracked(w, ox + door[0], oy + 3, oz + door[1], Material.LANTERN));

        /* ---------- intérieur amélioré ---------- */
        addInterior(res, w, ctx, ox, oy, oz, width, depth, rotationDeg);

        /* ---------- spawner PNJ (si quota) ---------- */
        int[] centre = rotate(width / 2, depth / 2, rotationDeg);
        if (ctx.shouldPlaceSpawner()) {
            res.add(ctx.createSpawnerAction(w,
                    ox + centre[0], oy + 1, oz + centre[1],
                    EntityType.VILLAGER));
        }

        /* ---------- toit + éventuelle cheminée ---------- */
        int roofBaseY = oy + wallH + 1;
        res.addAll(buildRoof(w, ox, oz, roofBaseY, width, depth, rotationDeg, stairMat, plankMat, ctx));

        if (RNG.nextBoolean()) {
            res.addAll(buildChimney(w, ox, oz, roofBaseY + width / 2, rotationDeg, ctx));
        }
        return res;
    }

    /* =================== Décoration intérieure =================== */
    private static void addInterior(List<Runnable> res, World w, Village ctx,
                                    int ox, int oy, int oz,
                                    int width, int depth, int rot) {

        // coordonnées relatives pivotées
        int[] bed     = rotate(2,               2,               rot);
        int[] craft   = rotate(width - 3,       2,               rot);
        int[] chest   = rotate(width - 3,       depth - 3,       rot);
        int[] furnace = rotate(2,               depth - 3,       rot);
        int[] barrel  = rotate(width / 2,       depth - 2,       rot);
        int[] lantern = rotate(width / 2,       depth / 2,       rot);
        int[] table   = rotate(width / 2,       2,               rot);
        int[] carpet1 = rotate(width / 2 - 1,   depth / 2,       rot);
        int[] carpet2 = rotate(width / 2 + 1,   depth / 2,       rot);

        res.add(() -> ctx.setBlockTracked(w, ox + bed[0],     oy + 1, oz + bed[1],     Material.WHITE_BED));
        res.add(() -> ctx.setBlockTracked(w, ox + craft[0],   oy + 1, oz + craft[1],   Material.CRAFTING_TABLE));
        res.add(() -> ctx.setBlockTracked(w, ox + chest[0],   oy + 1, oz + chest[1],   Material.CHEST));
        res.add(() -> ctx.setBlockTracked(w, ox + furnace[0], oy + 1, oz + furnace[1], Material.BLAST_FURNACE));
        res.add(() -> ctx.setBlockTracked(w, ox + barrel[0],  oy + 1, oz + barrel[1],  Material.BARREL));
        res.add(() -> ctx.setBlockTracked(w, ox + table[0],   oy + 1, oz + table[1],   Material.OAK_PRESSURE_PLATE));
        res.add(() -> ctx.setBlockTracked(w, ox + lantern[0], oy + 3, oz + lantern[1], Material.LANTERN));
        res.add(() -> ctx.setBlockTracked(w, ox + carpet1[0], oy + 1, oz + carpet1[1], Material.RED_CARPET));
        res.add(() -> ctx.setBlockTracked(w, ox + carpet2[0], oy + 1, oz + carpet2[1], Material.RED_CARPET));
    }

    /* =================== Toit + débord =================== */
    private static List<Runnable> buildRoof(
            World w, int ox, int oz, int baseY,
            int width, int depth, int rot,
            Material stairMat, Material fillMat, Village ctx) {

        List<Runnable> a = new ArrayList<>();
        int layers = width / 2;

        for (int layer = 0; layer < layers; layer++) {
            int y = baseY + layer;
            int x1 = layer, x2 = width - 1 - layer;
            int z1 = 0, z2 = depth - 1;

            /* avant + arrière */
            for (int x = x1; x <= x2; x++) {
                boolean left  = (x == x1);
                boolean right = (x == x2);

                int[] pFront = rotate(x, z1, rot);
                BlockFace fFront = rotFace(BlockFace.NORTH, rot);
                a.add(() -> stair(w,
                        ox + pFront[0], y, oz + pFront[1],
                        fFront, left, right, stairMat, ctx));

                int[] pBack = rotate(x, z2, rot);
                BlockFace fBack = rotFace(BlockFace.SOUTH, rot);
                a.add(() -> stair(w,
                        ox + pBack[0], y, oz + pBack[1],
                        fBack, left, right, stairMat, ctx));
            }

            /* remplissage intérieur */
            for (int ix = x1 + 1; ix <= x2 - 1; ix++)
                for (int iz = z1 + 1; iz <= z2 - 1; iz++) {
                    int[] p = rotate(ix, iz, rot);
                    int fx = ox + p[0], fz = oz + p[1];
                    a.add(() -> ctx.setBlockTracked(w, fx, y, fz, fillMat));
                }
        }

        /* gouttière (escalier retourné sous la première couche) */
        int overY = baseY - 1;
        for (int x = -1; x <= width; x++) {
            int[] pN = rotate(x, -1, rot);
            int[] pS = rotate(x, depth, rot);
            a.add(() -> ctx.setBlockTracked(w, ox + pN[0], overY, oz + pN[1], stairMat));
            a.add(() -> ctx.setBlockTracked(w, ox + pS[0], overY, oz + pS[1], stairMat));
        }
        for (int z = 0; z < depth; z++) {
            int[] pW = rotate(-1, z, rot);
            int[] pE = rotate(width, z, rot);
            a.add(() -> ctx.setBlockTracked(w, ox + pW[0], overY, oz + pW[1], stairMat));
            a.add(() -> ctx.setBlockTracked(w, ox + pE[0], overY, oz + pE[1], stairMat));
        }
        return a;
    }

    /* =================== Cheminée =================== */
    private static List<Runnable> buildChimney(
            World w, int ox, int oz, int topY, int rot, Village ctx) {

        List<Runnable> a = new ArrayList<>();
        /* position : arrière‑gauche (hors mur) */
        int[] base = rotate(1, -1, rot);
        for (int dy = 0; dy <= 3; dy++) {
            int fx = ox + base[0], fy = topY + dy, fz = oz + base[1];
            a.add(() -> ctx.setBlockTracked(w, fx, fy, fz, Material.BRICKS));
        }
        /* feu de camp qui fume */
        int fx = ox + base[0], fz = oz + base[1], fy = topY + 4;
        a.add(() -> ctx.setBlockTracked(w, fx, fy, fz, Material.CAMPFIRE));
        return a;
    }

    /* =================== Helpers =================== */
    public static int[] computeHouseBounds(int ox, int oz, int w, int d, int a) {
        return switch (a) {
            case 0   -> new int[]{ox,         ox + w - 1, oz,          oz + d - 1};
            case 90  -> new int[]{ox,         ox + d - 1, oz - w + 1,  oz         };
            case 180 -> new int[]{ox - w + 1, ox,         oz - d + 1,  oz         };
            case 270 -> new int[]{ox - d + 1, ox,         oz,          oz + w - 1};
            default  -> new int[]{ox,         ox + w - 1, oz,          oz + d - 1};
        };
    }

    private static int[] rotate(int dx, int dz, int a) {
        return switch (a) {
            case 90  -> new int[]{ dz, -dx };
            case 180 -> new int[]{ -dx, -dz };
            case 270 -> new int[]{ -dz,  dx };
            default  -> new int[]{  dx,  dz };
        };
    }

    private static BlockFace rotFace(BlockFace f, int a) {
        List<BlockFace> cycle = List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST);
        int i = cycle.indexOf(f);
        return cycle.get((i + a / 90 + 4) % 4);
    }

    private static void stair(World w, int x, int y, int z,
                              BlockFace f, boolean left, boolean right,
                              Material stairMat, Village ctx) {
        ctx.setBlockTracked(w, x, y, z, stairMat);
        Block b = w.getBlockAt(x, y, z);
        if (b.getType() != stairMat) return;
        Stairs s = (Stairs) b.getBlockData();
        s.setFacing(f);
        if (left)  s.setShape(Stairs.Shape.OUTER_LEFT);
        if (right) s.setShape(Stairs.Shape.OUTER_RIGHT);
        b.setBlockData(s, false);
    }

    @SafeVarargs
    private static <T> T pick(Random r, T... vals) { return vals[r.nextInt(vals.length)]; }
}
