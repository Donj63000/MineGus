package org.example;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.EntityType;

import java.util.*;

/**
 * Bâtiments décoratifs : maisons pivotées, toits, cheminées.
 * Toutes les méthodes sont statiques ; les accès bloc passent
 * systématiquement par {@link Village#setBlockTracked()}.
 */
public final class Batiments {

    private Batiments() {}                         // utilitaire statique

    private static final Random RNG = new Random();

    /* ───────────────────── API PRINCIPALE ───────────────────── */

    /**
     * Construit une maison pivotée (0 / 90 / 180 / 270 °) et renvoie la liste
     * d’actions bloc à exécuter.
     */
    public static List<Runnable> buildHouseRotatedActions(
            World w, Location start, int width, int depth,
            int rotationDeg, Village ctx) {

        List<Runnable> res = new ArrayList<>();
        int ox = start.getBlockX(), oy = start.getBlockY(), oz = start.getBlockZ();
        int wallH = 4;

        /* ---------- palettes aléatoires ---------- */
        List<Material> logs = List.of(
                Material.STRIPPED_OAK_LOG, Material.STRIPPED_SPRUCE_LOG,
                Material.STRIPPED_BIRCH_LOG, Material.STRIPPED_DARK_OAK_LOG);
        List<Material> planks = List.of(
                Material.OAK_PLANKS, Material.SPRUCE_PLANKS,
                Material.BIRCH_PLANKS, Material.DARK_OAK_PLANKS);

        Material logMat   = pick(RNG, logs.toArray(new Material[0]));
        Material plankMat = pick(RNG, planks.toArray(new Material[0]));

        Material foundationMat = RNG.nextBoolean()
                ? Material.COBBLESTONE
                : Material.STONE_BRICKS;

        Material stairMat = switch (plankMat) {
            case SPRUCE_PLANKS   -> Material.SPRUCE_STAIRS;
            case BIRCH_PLANKS    -> Material.BIRCH_STAIRS;
            case DARK_OAK_PLANKS -> Material.DARK_OAK_STAIRS;
            default              -> Material.OAK_STAIRS;
        };
        Material slabMat   = Material.valueOf(stairMat.name().replace("_STAIRS", "_SLAB"));
        Material windowMat = RNG.nextBoolean()
                ? Material.GLASS_PANE
                : Material.WHITE_STAINED_GLASS_PANE;

        /* ---------- fondations ---------- */
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++) {
                int[] p = rotate(dx, dz, rotationDeg);
                ctx.setBlockTracked(w, ox + p[0], oy - 1, oz + p[1], foundationMat);
            }

        /* ---------- poteaux d’angle ---------- */
        for (int h = 1; h <= 2; h++) {
            final int hh = h;
            for (int[] c : List.of(
                    new int[]{0, 0}, new int[]{width - 1, 0},
                    new int[]{0, depth - 1}, new int[]{width - 1, depth - 1})) {
                int[] p = rotate(c[0], c[1], rotationDeg);
                res.add(() -> ctx.setBlockTracked(w,
                        ox + p[0], oy + hh, oz + p[1], logMat));
            }
        }

        /* ---------- murs + fenêtres ---------- */
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++) {
                boolean edgeX = dx == 0 || dx == width - 1;
                boolean edgeZ = dz == 0 || dz == depth - 1;
                if (!(edgeX || edgeZ)) continue;

                for (int h = 0; h < wallH; h++) {
                    final int hh = h;
                    int[] p = rotate(dx, dz, rotationDeg);
                    int fx = ox + p[0], fy = oy + 1 + hh, fz = oz + p[1];

                    boolean corner = edgeX && edgeZ;
                    if (corner) continue;                // déjà géré par poteau

                    boolean windowLayer = hh == 1;
                    boolean frontBack = edgeZ && !edgeX && (dx == 2 || dx == width - 3);
                    boolean sideWalls = edgeX && !edgeZ && (dz == 2 || dz == depth - 3);
                    boolean putWindow = windowLayer && (frontBack || sideWalls);

                    if (putWindow) {
                        res.add(() -> ctx.setBlockTracked(w, fx, fy, fz, windowMat));

                        /* appui de fenêtre en escalier inversé + volets */
                        res.add(() -> {
                            Block sill = w.getBlockAt(fx, fy - 1, fz);
                            sill.setType(stairMat, false);
                            Stairs st = (Stairs) sill.getBlockData();
                            st.setHalf(Stairs.Half.TOP);
                            st.setFacing(rotFace(BlockFace.NORTH, rotationDeg));
                            sill.setBlockData(st, false);
                        });
                        addShutters(res, w, ctx, fx, fy, fz,
                                rotFace(BlockFace.NORTH, rotationDeg));
                    } else {
                        Material wallMat = plankMat;
                        res.add(() -> ctx.setBlockTracked(w, fx, fy, fz, wallMat));
                    }
                }
            }

        /* ---------- plancher ---------- */
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++) {
                int[] p = rotate(dx, dz, rotationDeg);
                res.add(() -> ctx.setBlockTracked(w,
                        ox + p[0], oy, oz + p[1], plankMat));
            }

        /* ---------- porte + lanterne ---------- */
        int[] door = rotate(width / 2, 0, rotationDeg);
        res.add(() -> ctx.setBlockTracked(w, ox + door[0], oy + 1, oz + door[1], Material.OAK_DOOR));
        res.add(() -> ctx.setBlockTracked(w, ox + door[0], oy + 2, oz + door[1], Material.OAK_DOOR));
        res.add(() -> ctx.setBlockTracked(w, ox + door[0], oy + 3, oz + door[1], Material.LANTERN));

        /* ---------- petit perron (2 × 2) ---------- */
        int[] frontVec = switch (rotationDeg) {
            case 0   -> new int[]{0, -1};
            case 90  -> new int[]{1,  0};
            case 180 -> new int[]{0,  1};
            case 270 -> new int[]{-1, 0};
            default  -> new int[]{0, -1};
        };
        int px = ox + door[0] + frontVec[0];
        int pz = oz + door[1] + frontVec[1];

        for (int dx = 0; dx <= 1; dx++) {
            final int ddx = dx;
            for (int dz = 0; dz <= 1; dz++) {
                final int ddz = dz;
                res.add(() -> ctx.setBlockTracked(w,
                        px + ddx, oy,     pz + ddz, Material.OAK_SLAB));
                res.add(() -> ctx.setBlockTracked(w,
                        px + ddx, oy - 1, pz + ddz, foundationMat));
            }
        }
        for (int h = 1; h <= 2; h++) {
            final int hh = h;
            res.add(() -> ctx.setBlockTracked(w, px,     oy + hh, pz,     logMat));
            res.add(() -> ctx.setBlockTracked(w, px + 1, oy + hh, pz + 1, logMat));
        }
        for (int dx = 0; dx <= 1; dx++) {
            final int ddx = dx;
            res.add(() -> ctx.setBlockTracked(w,
                    px + ddx, oy + 3, pz + (ddx == 0 ? 1 : 0), slabMat));
        }

        /* ---------- intérieur ---------- */
        addInterior(res, w, ctx, ox, oy, oz, width, depth, rotationDeg);

        /* ---------- éventuel spawner PNJ ---------- */
        int[] center = rotate(width / 2, depth / 2, rotationDeg);
        if (ctx.shouldPlaceSpawner()) {
            res.add(ctx.createSpawnerAction(w,
                    ox + center[0], oy + 1, oz + center[1],
                    EntityType.VILLAGER));
        }

        /* ---------- toit + cheminée ---------- */
        int roofBaseY = oy + wallH + 1;
        res.addAll(buildRoof(w, ox, oz, roofBaseY, width, depth,
                rotationDeg, stairMat, plankMat, slabMat, ctx));

        if (RNG.nextBoolean()) {
            res.addAll(buildChimney(w, ox, oz,
                    roofBaseY + width / 2, rotationDeg, ctx));
        }
        return res;
    }

    /* ─────────────────── DÉCOR INTÉRIEUR ─────────────────── */
    private static void addInterior(List<Runnable> res, World w, Village ctx,
                                    int ox, int oy, int oz,
                                    int width, int depth, int rot) {

        int[] bed     = rotate(2,             2,             rot);
        int[] craft   = rotate(width - 3,     2,             rot);
        int[] chest   = rotate(width - 3,     depth - 3,     rot);
        int[] furnace = rotate(2,             depth - 3,     rot);
        int[] barrel  = rotate(width / 2,     depth - 2,     rot);
        int[] lantern = rotate(width / 2,     depth / 2,     rot);
        int[] table   = rotate(width / 2,     2,             rot);

        res.add(() -> ctx.setBlockTracked(w, ox + bed[0],     oy + 1, oz + bed[1],     Material.WHITE_BED));
        res.add(() -> ctx.setBlockTracked(w, ox + craft[0],   oy + 1, oz + craft[1],   Material.CRAFTING_TABLE));
        res.add(() -> ctx.setBlockTracked(w, ox + chest[0],   oy + 1, oz + chest[1],   Material.CHEST));
        res.add(() -> ctx.setBlockTracked(w, ox + furnace[0], oy + 1, oz + furnace[1], Material.BLAST_FURNACE));
        res.add(() -> ctx.setBlockTracked(w, ox + barrel[0],  oy + 1, oz + barrel[1],  Material.BARREL));
        res.add(() -> ctx.setBlockTracked(w, ox + table[0],   oy + 1, oz + table[1],   Material.OAK_PRESSURE_PLATE));
        res.add(() -> ctx.setBlockTracked(w, ox + lantern[0], oy + 3, oz + lantern[1], Material.LANTERN));
    }

    /* ──────────────────────── TOIT ──────────────────────── */
    private static List<Runnable> buildRoof(
            World w, int ox, int oz, int baseY,
            int width, int depth, int rot,
            Material stairMat, Material fillMat,
            Material slabMat, Village ctx) {

        List<Runnable> a = new ArrayList<>();
        int layers = width / 2;

        for (int layer = 0; layer < layers; layer++) {
            int y = baseY + layer;
            int x1 = layer, x2 = width - 1 - layer;
            int z1 = 0, z2 = depth - 1;

            /* avant / arrière */
            for (int x = x1; x <= x2; x++) {
                boolean left = x == x1, right = x == x2;

                int[] front = rotate(x, z1, rot);
                int[] back  = rotate(x, z2, rot);

                a.add(() -> stair(w, ox + front[0], y, oz + front[1],
                        rotFace(BlockFace.NORTH, rot), left, right, stairMat, ctx));
                a.add(() -> stair(w, ox + back[0],  y, oz + back[1],
                        rotFace(BlockFace.SOUTH, rot), left, right, stairMat, ctx));
            }

            /* remplissage sous le faîtage */
            for (int ix = x1 + 1; ix <= x2 - 1; ix++)
                for (int iz = z1 + 1; iz <= z2 - 1; iz++) {
                    int[] p = rotate(ix, iz, rot);
                    a.add(() -> ctx.setBlockTracked(w,
                            ox + p[0], y, oz + p[1], fillMat));
                }
        }

        /* gouttières (escalier retourné) */
        int overY = baseY - 1;
        for (int x = -1; x <= width; x++) {
            int[] north = rotate(x, -1, rot);
            int[] south = rotate(x, depth, rot);
            a.add(() -> ctx.setBlockTracked(w,
                    ox + north[0], overY, oz + north[1], stairMat));
            a.add(() -> ctx.setBlockTracked(w,
                    ox + south[0], overY, oz + south[1], stairMat));
        }
        for (int z = 0; z < depth; z++) {
            int[] west  = rotate(-1,   z, rot);
            int[] east  = rotate(width, z, rot);
            a.add(() -> ctx.setBlockTracked(w,
                    ox + west[0], overY, oz + west[1], stairMat));
            a.add(() -> ctx.setBlockTracked(w,
                    ox + east[0], overY, oz + east[1], stairMat));
        }

        /* faîtage */
        int ridgeY = baseY + layers;
        for (int dx = 0; dx < width; dx++) {
            int[] p = rotate(dx, -1, rot);
            a.add(() -> ctx.setBlockTracked(w,
                    ox + p[0], ridgeY, oz + p[1], slabMat));
        }
        /* anti‑spawn sous la faîtière */
        for (int dx = 1; dx < width - 1; dx++) {
            int[] p = rotate(dx, 0, rot);
            a.add(() -> ctx.setBlockTracked(w,
                    ox + p[0], ridgeY - 1, oz + p[1], slabMat));
        }
        return a;
    }

    /* ─────────────────────── CHEMINÉE ─────────────────────── */
    private static List<Runnable> buildChimney(
            World w, int ox, int oz, int topY, int rot, Village ctx) {

        List<Runnable> a = new ArrayList<>();
        int[] base = rotate(1, -1, rot);          // arrière‑gauche
        for (int dy = 0; dy <= 3; dy++) {
            int fx = ox + base[0], fy = topY + dy, fz = oz + base[1];
            a.add(() -> ctx.setBlockTracked(w, fx, fy, fz, Material.BRICKS));
        }
        /* feu de camp fumant */
        int fx = ox + base[0], fz = oz + base[1], fy = topY + 4;
        a.add(() -> ctx.setBlockTracked(w, fx, fy, fz, Material.CAMPFIRE));
        return a;
    }

    /* ─────────────── volets (trapdoors) ─────────────── */
    private static void addShutters(List<Runnable> res, World w, Village ctx,
                                    int x, int y, int z, BlockFace face) {

        BlockFace left  = rotateCCW(face);  // antihoraire
        BlockFace right = rotateCW(face);   // horaire

        for (BlockFace side : List.of(left, right)) {
            int sx = x + side.getModX();
            int sz = z + side.getModZ();
            res.add(() -> ctx.setBlockTracked(w, sx, y, sz, Material.OAK_TRAPDOOR));
        }
    }

    /* ───────────────────────── HELPERS ───────────────────────── */

    private static int[] rotate(int dx, int dz, int a) {
        return switch (a) {
            case 90  -> new int[]{ dz, -dx };
            case 180 -> new int[]{ -dx, -dz };
            case 270 -> new int[]{ -dz,  dx };
            default  -> new int[]{  dx,  dz };
        };
    }

    private static BlockFace rotFace(BlockFace f, int a) {
        List<BlockFace> cycle = List.of(
                BlockFace.NORTH, BlockFace.EAST,
                BlockFace.SOUTH, BlockFace.WEST);
        int i = cycle.indexOf(f);
        return cycle.get((i + a / 90 + 4) % 4);
    }

    /* rotation horaire / antihoraire (compatibilité 1.20.x) */
    private static BlockFace rotateCW(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.EAST;
            case EAST  -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST  -> BlockFace.NORTH;
            default    -> f;
        };
    }
    private static BlockFace rotateCCW(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.WEST;
            case WEST  -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            case EAST  -> BlockFace.NORTH;
            default    -> f;
        };
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
    private static <T> T pick(Random r, T... vals) {
        return vals[r.nextInt(vals.length)];
    }
}
