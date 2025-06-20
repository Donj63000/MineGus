package org.example;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.EntityType;

import java.util.*;

/**
 * Bâtiments décoratifs : maisons pivotées, toits, cheminées.
 * Toutes les méthodes sont statiques ; les accès blocs passent
 * systématiquement par {@link Village#setBlockTracked}.
 */
public final class Batiments {

    private Batiments() {}
    private static final Random RNG = new Random();
    private static final int N_LAYOUTS = 4;

    /*══════════════════════ API PRINCIPALE ══════════════════════*/
    public static List<Runnable> buildHouseRotatedActions(
            World w, Location start,
            int width, int depth,
            int rotationDeg,
            Village ctx,
            boolean habitation) {

        List<Runnable> res = new ArrayList<>();
        int ox = start.getBlockX();
        int oy = start.getBlockY();
        int oz = start.getBlockZ();
        int wallH = 4;                          // murs pleins

        /* ---------- palette aléatoire ---------- */
        Material logMat   = pick(RNG,
                Material.STRIPPED_OAK_LOG,
                Material.STRIPPED_SPRUCE_LOG,
                Material.STRIPPED_BIRCH_LOG,
                Material.STRIPPED_DARK_OAK_LOG);
        Material plankMat = pick(RNG,
                Material.OAK_PLANKS, Material.SPRUCE_PLANKS,
                Material.BIRCH_PLANKS, Material.DARK_OAK_PLANKS);

        /* dérivés cohérents */
        Material foundationMat = RNG.nextBoolean() ? Material.COBBLESTONE : Material.STONE_BRICKS;
        Material stairMat      = Material.valueOf(plankMat.name().replace("_PLANKS", "_STAIRS"));
        Material slabMat       = Material.valueOf(plankMat.name().replace("_PLANKS", "_SLAB"));
        Material windowMat     = RNG.nextBoolean() ? Material.GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE;
        Material doorMat       = doorFromPlanks(plankMat);
        Material shutterMat    = trapdoorFromPlanks(plankMat);

        /* ---------- fondations ---------- */
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++) {
                int[] p = rotate(dx, dz, rotationDeg);
                ctx.setBlockTracked(w, ox + p[0], oy - 1, oz + p[1], foundationMat);
            }

        /* ---------- poteaux d’angle (pleine hauteur) ---------- */
        for (int h = 1; h <= wallH + 1; h++) {
            final int hh = h;
            for (int[] c : List.of(
                    new int[]{0, 0}, new int[]{width - 1, 0},
                    new int[]{0, depth - 1}, new int[]{width - 1, depth - 1})) {
                int[] p = rotate(c[0], c[1], rotationDeg);
                res.add(() -> ctx.setBlockTracked(w, ox + p[0], oy + hh, oz + p[1], logMat));
            }
        }

        /* ---------- ceinture horizontale sous toiture ---------- */
        int beamY = oy + wallH + 1;
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++) {
                boolean edge = dx == 0 || dx == width - 1 || dz == 0 || dz == depth - 1;
                if (!edge) continue;
                int[] p = rotate(dx, dz, rotationDeg);
                BlockFace baseDir = (dz == 0) ? BlockFace.NORTH :
                        (dz == depth - 1) ? BlockFace.SOUTH :
                                (dx == 0) ? BlockFace.WEST : BlockFace.EAST;
                BlockFace outward = rotFace(baseDir, rotationDeg);
                final boolean eastWest =
                        outward == BlockFace.NORTH || outward == BlockFace.SOUTH;
                res.add(() -> {
                    ctx.setBlockTracked(w, ox + p[0], beamY, oz + p[1], logMat);
                    /* petite orientation de l’écorce pour que les fibres suivent l’arête */
                    Block b = w.getBlockAt(ox + p[0], beamY, oz + p[1]);
                    if (b.getBlockData() instanceof Orientable o) {
                        o.setAxis(eastWest ? Axis.X : Axis.Z);
                        b.setBlockData(o, false);
                    }
                });
            }

        /* ---------- murs + fenêtres ---------- */
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++) {
                boolean edgeX = dx == 0 || dx == width - 1;
                boolean edgeZ = dz == 0 || dz == depth - 1;
                if (!(edgeX || edgeZ)) continue;

                /* direction “dehors” AVANT rotation */
                BlockFace baseDir = (dz == 0) ? BlockFace.NORTH :
                        (dz == depth - 1) ? BlockFace.SOUTH :
                                (dx == 0) ? BlockFace.WEST : BlockFace.EAST;
                BlockFace outward = rotFace(baseDir, rotationDeg);

                for (int h = 0; h < wallH; h++) {
                    final int hh = h;
                    int[] p = rotate(dx, dz, rotationDeg);
                    int fx = ox + p[0], fy = oy + 1 + hh, fz = oz + p[1];

                    /* fenêtres à mi‑hauteur, deux cases de marge */
                    boolean windowLayer = hh == 1;
                    boolean marginOK = (baseDir == BlockFace.NORTH || baseDir == BlockFace.SOUTH)
                            ? dx >= 2 && dx <= width - 3
                            : dz >= 2 && dz <= depth - 3;

                    if (windowLayer && marginOK) {
                        res.add(() -> ctx.setBlockTracked(w, fx, fy, fz, windowMat));

                        /* appui */
                        res.add(windowSillAction(w, fx, fy - 1, fz, stairMat, outward));

                        /* volets */
                        addShutters(res, w, ctx, fx, fy, fz, outward, shutterMat);
                    } else {
                        res.add(() -> ctx.setBlockTracked(w, fx, fy, fz, plankMat));
                    }
                }
            }

        /* ---------- plancher ---------- */
        for (int dx = 0; dx < width; dx++)
            for (int dz = 0; dz < depth; dz++) {
                int[] p = rotate(dx, dz, rotationDeg);
                res.add(() -> ctx.setBlockTracked(w, ox + p[0], oy, oz + p[1], plankMat));
            }

        /* ---------- porte + lanterne de façade ---------- */
        int[] doorRel = rotate(width / 2, 0, rotationDeg);
        BlockFace outward = rotFace(BlockFace.NORTH, rotationDeg);
        Door bottom = (Door) doorMat.createBlockData();
        bottom.setFacing(outward);
        bottom.setHalf(Bisected.Half.BOTTOM);
        Door top = (Door) doorMat.createBlockData();
        top.setFacing(outward);
        top.setHalf(Bisected.Half.TOP);
        res.add(() -> ctx.setBlockTracked(w, ox + doorRel[0], oy + 1, oz + doorRel[1], bottom));
        res.add(() -> ctx.setBlockTracked(w, ox + doorRel[0], oy + 2, oz + doorRel[1], top));
        res.add(() -> ctx.setBlockTracked(w, ox + doorRel[0], oy + 3, oz + doorRel[1], Material.LANTERN));

        /* ---------- perron 2 × 2 ---------- */
        int[] front = switch (rotationDeg) {
            case 0 -> new int[]{0, -1};
            case 90 -> new int[]{1, 0};
            case 180 -> new int[]{0, 1};
            case 270 -> new int[]{-1, 0};
            default -> new int[]{0, -1};
        };
        int px = ox + doorRel[0] + front[0];
        int pz = oz + doorRel[1] + front[1];
        for (int dx = 0; dx <= 1; dx++)
            for (int dz = 0; dz <= 1; dz++) {
                final int fx = px + dx, fz = pz + dz;
                res.add(() -> ctx.setBlockTracked(w, fx, oy,     fz, Material.OAK_SLAB));
                res.add(() -> ctx.setBlockTracked(w, fx, oy - 1, fz, foundationMat));
            }

        /* ---------- aménagement intérieur ---------- */
        addInterior(res, w, ctx, ox, oy, oz, width, depth, rotationDeg);

        /* ---------- éventuel spawner PNJ ---------- */
        int[] center = rotate(width / 2, depth / 2, rotationDeg);
        if (habitation && ctx.shouldPlaceSpawner()) {
            res.add(ctx.createSpawnerAction(
                    w, ox + center[0], oy + 1, oz + center[1],
                    EntityType.VILLAGER));
        }

        /* ---------- toit & cheminée ---------- */
        int roofBaseY = oy + wallH + 2; // +1 à cause du bandeau
        res.addAll(buildRoof(w, ox, oz, roofBaseY, width, depth,
                rotationDeg, stairMat, plankMat, slabMat, ctx));

        return res;
    }

    /*═══════════════════ DÉCOR INTÉRIEUR ═══════════════════*/
    private static void addInterior(List<Runnable> res, World w, Village ctx,
                                    int ox, int oy, int oz,
                                    int width, int depth, int rot) {

        switch (RNG.nextInt(N_LAYOUTS)) {
            case 0 -> layoutSimple(res, w, ctx, ox, oy, oz, width, depth, rot);
            case 1 -> layoutKitchen(res, w, ctx, ox, oy, oz, width, depth, rot);
            case 2 -> layoutWorkshop(res, w, ctx, ox, oy, oz, width, depth, rot);
            default -> layoutLibrary(res, w, ctx, ox, oy, oz, width, depth, rot);
        }

        addAmbientLighting(res, w, ctx, ox, oy, oz, width, depth, rot);
    }

    private static void layoutSimple(List<Runnable> res, World w, Village ctx,
                                     int ox, int oy, int oz,
                                     int width, int depth, int rot) {
        List<Pair> items = List.of(
                new Pair(2,             2,             Material.WHITE_BED),
                new Pair(width - 3,     2,             Material.CRAFTING_TABLE),
                new Pair(width - 3,     depth - 3,     Material.CHEST),
                new Pair(2,             depth - 3,     Material.BLAST_FURNACE),
                new Pair(width / 2,     depth - 2,     Material.BARREL),
                new Pair(width / 2,     2,             Material.FLOWER_POT)
        );
        items.forEach(p -> place(res, w, ctx, ox, oy, oz, rot, p));
    }

    private static void layoutKitchen(List<Runnable> res, World w, Village ctx,
                                      int ox, int oy, int oz,
                                      int width, int depth, int rot) {
        List<Pair> items = List.of(
                new Pair(2,             2,             Material.WHITE_BED),
                new Pair(width - 3,     2,             Material.SMOKER),
                new Pair(width - 3,     depth - 3,     Material.BARREL),
                new Pair(2,             depth - 3,     Material.CAULDRON)
        );
        items.forEach(p -> place(res, w, ctx, ox, oy, oz, rot, p));
        maybeJobSite(res, w, ctx, ox, oy, oz, rot,
                width / 2, depth - 2, Material.COMPOSTER);
    }

    private static void layoutWorkshop(List<Runnable> res, World w, Village ctx,
                                       int ox, int oy, int oz,
                                       int width, int depth, int rot) {
        List<Pair> items = List.of(
                new Pair(2,             2,             Material.WHITE_BED),
                new Pair(width - 3,     2,             Material.SMITHING_TABLE),
                new Pair(width - 3,     depth - 3,     Material.BLAST_FURNACE),
                new Pair(2,             depth - 3,     Material.ANVIL)
        );
        items.forEach(p -> place(res, w, ctx, ox, oy, oz, rot, p));
        maybeJobSite(res, w, ctx, ox, oy, oz, rot,
                width / 2, depth - 2, Material.GRINDSTONE);
    }

    private static void layoutLibrary(List<Runnable> res, World w, Village ctx,
                                      int ox, int oy, int oz,
                                      int width, int depth, int rot) {
        List<Pair> items = List.of(
                new Pair(2,             2,             Material.WHITE_BED),
                new Pair(width - 3,     depth - 3,     Material.CHEST),
                new Pair(width / 2,     depth - 2,     Material.BOOKSHELF)
        );
        items.forEach(p -> place(res, w, ctx, ox, oy, oz, rot, p));
        maybeJobSite(res, w, ctx, ox, oy, oz, rot,
                width - 3, 2, Material.LECTERN);
    }

    private static void place(List<Runnable> res, World w, Village ctx,
                              int ox, int oy, int oz, int rot, Pair p) {
        int[] c = rotate(p.x(), p.z(), rot);
        res.add(() -> ctx.setBlockTracked(w, ox + c[0], oy + 1, oz + c[1], p.mat()));
    }

    private static void maybeJobSite(List<Runnable> res, World w, Village ctx,
                                     int ox, int oy, int oz, int rot,
                                     int dx, int dz, Material m) {
        if (RNG.nextBoolean()) {
            place(res, w, ctx, ox, oy, oz, rot, new Pair(dx, dz, m));
        }
    }

    private static void addAmbientLighting(List<Runnable> res, World w, Village ctx,
                                           int ox, int oy, int oz,
                                           int width, int depth, int rot) {
        int[] c = rotate(width / 2, depth / 2, rot);
        res.add(() -> ctx.setBlockTracked(w, ox + c[0], oy + 4, oz + c[1], Material.LANTERN));
    }

    private record Pair(int x, int z, Material mat) {}

    /*═════════════════════ TOIT ═════════════════════*/
    private static List<Runnable> buildRoof(
            World w, int ox, int oz, int baseY,
            int width, int depth, int rot,
            Material stairMat, Material fillMat,
            Material slabMat, Village ctx) {

        List<Runnable> a = new ArrayList<>();
        final int layers = (width - 1) / 2;
        final boolean doubleRidge = (width % 2 == 0);

        for (int layer = 0; layer < layers; layer++) {
            int y = baseY + layer;
            int x1 = layer, x2 = width - 1 - layer;
            int z1 = 0, z2 = depth - 1;

            /* avant / arrière */
            for (int x = x1; x <= x2; x++) {
                boolean left  = x == x1, right = x == x2;

                int[] front = rotate(x, z1, rot);
                int[] back  = rotate(x, z2, rot);

                a.add(() -> stair(w, ox + front[0], y, oz + front[1],
                        rotFace(BlockFace.NORTH, rot), left, right, stairMat, ctx));
                a.add(() -> stair(w, ox + back[0],  y, oz + back[1],
                        rotFace(BlockFace.SOUTH, rot), left, right, stairMat, ctx));
            }

            /* remplissage intérieur */
            for (int ix = x1 + 1; ix <= x2 - 1; ix++)
                for (int iz = z1 + 1; iz <= z2 - 1; iz++) {
                    int[] p = rotate(ix, iz, rot);
                    a.add(() -> ctx.setBlockTracked(w,
                            ox + p[0], y, oz + p[1], fillMat));
                }
        }

        /* gouttière */
        int overY = baseY - 1;
        for (int x = -1; x <= width; x++) {
            int[] n = rotate(x, -1, rot);
            int nx = n[0];
            int nz = n[1];
            int[] s = rotate(x, depth, rot);
            int sx = s[0];
            int sz = s[1];
            a.add(() -> ctx.setBlockTracked(w, ox + nx, overY, oz + nz, stairMat));
            a.add(() -> ctx.setBlockTracked(w, ox + sx, overY, oz + sz, stairMat));
        }
        for (int z = 0; z < depth; z++) {
            int[] wv = rotate(-1,   z, rot);
            int wvx = wv[0];
            int wvz = wv[1];
            int[] ev = rotate(width, z, rot);
            int evx = ev[0];
            int evz = ev[1];
            a.add(() -> ctx.setBlockTracked(w, ox + wvx, overY, oz + wvz, stairMat));
            a.add(() -> ctx.setBlockTracked(w, ox + evx, overY, oz + evz, stairMat));
        }

        /* faîtage */
        int ridgeY = baseY + layers;
        for (int dx = 0; dx < width; dx++) {
            boolean ridgePos = dx == layers || (doubleRidge && dx == layers + 1);

            if (ridgePos) {
                int[] p = rotate(dx, -1, rot);
                a.add(() -> ctx.setBlockTracked(w, ox + p[0], ridgeY, oz + p[1], slabMat));

                if (doubleRidge) {
                    int[] p2 = rotate(dx, 0, rot);
                    a.add(() -> ctx.setBlockTracked(w, ox + p2[0], ridgeY, oz + p2[1], slabMat));
                }
            }

            if (dx > 0 && dx < width - 1) {
                int[] p2 = rotate(dx, 0, rot);
                a.add(() -> ctx.setBlockTracked(w, ox + p2[0], ridgeY - 1, oz + p2[1], slabMat));
            }
        }

        /* cheminée éventuelle */
        a.addAll(buildChimneyAfterRoof(w, ox, oz, baseY, rot, layers, doubleRidge, ctx));

        /* soutènement sous la gouttière */
        int supportY = baseY - 2;
        for (int x = -1; x <= width; x++) {
            int[] n = rotate(x, -1, rot);
            int nx = n[0];
            int nz = n[1];
            int[] s = rotate(x, depth, rot);
            int sx = s[0];
            int sz = s[1];
            a.add(() -> ctx.setBlockTracked(w, ox + nx, supportY, oz + nz, fillMat));
            a.add(() -> ctx.setBlockTracked(w, ox + sx, supportY, oz + sz, fillMat));
        }
        for (int z = 0; z < depth; z++) {
            int[] wv = rotate(-1, z, rot);
            int wvx = wv[0];
            int wvz = wv[1];
            int[] ev = rotate(width, z, rot);
            int evx = ev[0];
            int evz = ev[1];
            a.add(() -> ctx.setBlockTracked(w, ox + wvx, supportY, oz + wvz, fillMat));
            a.add(() -> ctx.setBlockTracked(w, ox + evx, supportY, oz + evz, fillMat));
        }

        /* jambages aux coins de la gouttière */
        int[][] corners = { { -1, -1 }, { width, -1 }, { -1, depth }, { width, depth } };
        for (int[] c : corners) {
            int[] p = rotate(c[0], c[1], rot);
            for (int y = baseY - 2; y <= baseY - 1; y++) {
                int px = ox + p[0];
                int pz = oz + p[1];
                int py = y;
                a.add(() -> ctx.setBlockTracked(w, px, py, pz, fillMat));
            }
        }

        return a;
    }

    /*════════════════════ CHEMINÉE ════════════════════*/
    private static List<Runnable> buildChimneyAfterRoof(
            World w, int ox, int oz, int baseY,
            int rot, int layers, boolean doubleRidge, Village ctx) {

        List<Runnable> a = new ArrayList<>();
        if (!RNG.nextBoolean()) return a;

        int ridgeY = baseY + layers;

        int[] base = rotate(1, -1, rot); // arrière‑gauche
        for (int dy = 0; dy <= 3; dy++) {
            int fx = ox + base[0], fy = ridgeY + dy, fz = oz + base[1];
            a.add(() -> ctx.setBlockTracked(w, fx, fy, fz, Material.BRICKS));
        }
        int fx = ox + base[0], fz = oz + base[1], fy = ridgeY + 4;
        a.add(() -> ctx.setBlockTracked(w, fx, fy, fz, Material.CAMPFIRE));

        /* dalle par-dessus */
        int slabY = ridgeY + 5;
        a.add(() -> ctx.setBlockTracked(w, fx, slabY, fz, Material.BRICK_SLAB));

        /* ouverture latérale pour la fumée */
        int[] side = rotate(1, 0, rot);
        int hx = fx + side[0];
        int hz = fz + side[1];
        int holeY = ridgeY + 3;
        a.add(() -> ctx.setBlockTracked(w, hx, holeY, hz, Material.AIR));

        return a;
    }

    /*════════════ volets & appuis de fenêtre ═══════════*/
    private static Runnable windowSillAction(World w, int x, int y, int z,
                                             Material stairMat, BlockFace outward) {
        return () -> {
            Block sill = w.getBlockAt(x, y, z);
            sill.setType(stairMat, false);
            Stairs st = (Stairs) sill.getBlockData();
            st.setHalf(Stairs.Half.TOP);
            st.setFacing(outward);
            sill.setBlockData(st, false);
        };
    }

    private static void addShutters(List<Runnable> res, World w, Village ctx,
                                    int x, int y, int z, BlockFace outward,
                                    Material shutterMat) {

        BlockFace left  = rotateCCW(outward);
        BlockFace right = rotateCW(outward);

        for (BlockFace side : List.of(left, right)) {
            int sx = x + side.getModX();
            int sz = z + side.getModZ();
            res.add(() -> {
                ctx.setBlockTracked(w, sx, y, sz, shutterMat);
                Block b = w.getBlockAt(sx, y, sz);
                if (b.getBlockData() instanceof TrapDoor td) {
                    td.setFacing(outward);
                    td.setOpen(true);
                    b.setBlockData(td, false);
                }
            });
        }
    }

    /*════════════════════ HELPERS ════════════════════*/
    private static int[] rotate(int dx, int dz, int a) {
        return switch (a) {
            case 90  -> new int[]{ dz, -dx };
            case 180 -> new int[]{ -dx, -dz };
            case 270 -> new int[]{ -dz,  dx };
            default  -> new int[]{  dx,  dz };
        };
    }
    private static BlockFace rotFace(BlockFace f, int a) {
        List<BlockFace> cycle = List.of(BlockFace.NORTH, BlockFace.EAST,
                BlockFace.SOUTH, BlockFace.WEST);
        int i = cycle.indexOf(f);
        return cycle.get((i + a / 90 + 4) % 4);
    }
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

    private static Material doorFromPlanks(Material planks) {
        return Material.valueOf(planks.name().replace("_PLANKS", "_DOOR"));
    }
    private static Material trapdoorFromPlanks(Material planks) {
        return Material.valueOf(planks.name().replace("_PLANKS", "_TRAPDOOR"));
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

    public static int[] computeHouseBounds(int ox, int oz, int w, int d, int a) {
        return switch (a) {
            case 0   -> new int[]{ox,         ox + w - 1, oz,          oz + d - 1};
            case 90  -> new int[]{ox,         ox + d - 1, oz - w + 1,  oz         };
            case 180 -> new int[]{ox - w + 1, ox,         oz - d + 1,  oz         };
            case 270 -> new int[]{ox - d + 1, ox,         oz,          oz + w - 1};
            default  -> new int[]{ox,         ox + w - 1, oz,          oz + d - 1};
        };
    }
}
