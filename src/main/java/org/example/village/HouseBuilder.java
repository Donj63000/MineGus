package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.EntityType;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;   // ← import manquant corrigé
import java.util.Random;

/**
 * Constructions unitaires : puits, maisons, fermes, enclos, routes,
 * lampadaires, église.
 *
 * Toutes les décisions de placement sont prises dans {@link Disposition},
 * jamais ici.
 */
public final class HouseBuilder {

    private HouseBuilder() {}   // utilitaire statique : pas d’instance

    /* -------------------------------------------------------------- */
    /* PUITS + CLOCHETTE                                              */
    /* -------------------------------------------------------------- */

    public static List<Runnable> buildWell(Location c, TerrainManager.SetBlock sb) {
        List<Runnable> l = new ArrayList<>();
        final int ox = c.getBlockX(), oy = c.getBlockY(), oz = c.getBlockZ();

        // cuve
        for (int dx = 0; dx < 4; dx++)
            for (int dz = 0; dz < 4; dz++) {
                final int fx = ox + dx, fz = oz + dz;
                l.add(() -> sb.set(fx, oy, fz, Material.COBBLESTONE));
                if (dx > 0 && dx < 3 && dz > 0 && dz < 3)
                    l.add(() -> sb.set(fx, oy, fz, Material.WATER));
            }

        // piliers
        for (int dy = 1; dy <= 3; dy++) {
            final int y = oy + dy;
            l.add(() -> sb.set(ox    , y, oz    , Material.COBBLESTONE));
            l.add(() -> sb.set(ox + 3, y, oz    , Material.COBBLESTONE));
            l.add(() -> sb.set(ox    , y, oz + 3, Material.COBBLESTONE));
            l.add(() -> sb.set(ox + 3, y, oz + 3, Material.COBBLESTONE));
        }

        // dalle de toit
        final int roof = oy + 4;
        for (int dx = 0; dx < 4; dx++)
            for (int dz = 0; dz < 4; dz++) {
                final int fx = ox + dx, fz = oz + dz;
                l.add(() -> sb.set(fx, roof, fz, Material.COBBLESTONE_SLAB));
            }
        return l;
    }

    public static List<Runnable> buildBell(Location loc, TerrainManager.SetBlock sb) {
        final int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        return List.of(() -> sb.set(x, y, z, Material.BELL));
    }

    /* -------------------------------------------------------------- */
    /* ROUTE ORTHOGONALE                                              */
    /* -------------------------------------------------------------- */
    public static List<Runnable> buildRoad(int x0, int z0,
                                           int x1, int z1,
                                           int y, int halfWidth,
                                           List<Material> palette,
                                           TerrainManager.SetBlock sb) {

        List<Runnable> l = new ArrayList<>();

        /* segment X */
        int minX = Math.min(x0, x1);
        int maxX = Math.max(x0, x1);
        for (int x = minX; x <= maxX; x++) {
            paintStrip(l, palette, x, y, z0 - halfWidth, z0 + halfWidth, sb);
        }

        /* segment Z */
        int minZ = Math.min(z0, z1);
        int maxZ = Math.max(z0, z1);
        for (int dx = -halfWidth; dx <= halfWidth; dx++) {
            paintStrip(l, palette, x1 + dx, y, minZ, maxZ, sb);
        }

        return l;
    }

    /* -------------------------------------------------------------- */
    /*  util : peint 1 bloc de route                                  */
    /* -------------------------------------------------------------- */
    public static void paintRoad(Queue<Runnable> q,
                                 List<Material> palette,
                                 int x, int y, int z,
                                 TerrainManager.SetBlock sb) {

        Random R = new Random();
        Material m = palette.get(R.nextInt(palette.size()));
        q.add(() -> sb.set(x, y, z, m));    // le lambda implémente Runnable
    }

    /* -------------------------------------------------------------- */
    /*  util : bande verticale de route                                */
    /* -------------------------------------------------------------- */
    public static void paintStrip(Queue<Runnable> q,
                                  List<Material> palette,
                                  int fx, int fy,
                                  int fz0, int fz1,
                                  TerrainManager.SetBlock sb) {

        Random R = new Random();
        int minZ = Math.min(fz0, fz1);
        int maxZ = Math.max(fz0, fz1);
        for (int z = minZ; z <= maxZ; z++) {
            final int fz = z;
            Material m = palette.get(R.nextInt(palette.size()));
            q.add(() -> sb.set(fx, fy, fz, m));
        }
    }

    // PATCH 2-A
    private static void paintStrip(List<Runnable> q, List<Material> palette,
                                   int fx, int fy, int fz0, int fz1,
                                   TerrainManager.SetBlock sb) {
        Random R = new Random();
        int min = Math.min(fz0, fz1), max = Math.max(fz0, fz1);
        for (int z = min; z <= max; z++) {
            Material m = palette.get(R.nextInt(palette.size()));
            int fz = z;
            q.add(() -> sb.set(fx, fy, fz, m));
        }
    }

    /* -------------------------------------------------------------- */
    /* CHAMP (farmland + eau + cultures)                              */
    /* -------------------------------------------------------------- */
    public static List<Runnable> buildFarm(Location base,
                                           List<Material> crops,
                                           TerrainManager.SetBlock sb,
                                           Random rng) {

        List<Runnable> l = new ArrayList<>();
        Random R = rng != null ? rng : new Random();
        final int ox = base.getBlockX(), oy = base.getBlockY(), oz = base.getBlockZ();

        /* cadre 9 × 9 en troncs */
        for (int dx = 0; dx <= 8; dx++)
            for (int dz = 0; dz <= 8; dz++) {
                boolean edge = dx == 0 || dx == 8 || dz == 0 || dz == 8;
                if (!edge) continue;
                final int fx = ox + dx, fz = oz + dz;
                l.add(() -> sb.set(fx, oy, fz, Material.OAK_LOG));
            }

        /* intérieur : water + farmland + plants */
        for (int dx = 1; dx < 8; dx++)
            for (int dz = 1; dz < 8; dz++) {
                final int fx = ox + dx, fz = oz + dz;
                if (dx == 4 && dz == 4) {
                    l.add(() -> sb.set(fx, oy, fz, Material.WATER));
                } else {
                    l.add(() -> sb.set(fx, oy, fz, Material.FARMLAND));

                    Material seed = crops.get(R.nextInt(crops.size()));
                    Material plant = switch (seed) {
                        case WHEAT_SEEDS -> Material.WHEAT;
                        case CARROT      -> Material.CARROTS;
                        case POTATO      -> Material.POTATOES;
                        case BEETROOT_SEEDS -> Material.BEETROOTS;
                        default          -> Material.WHEAT;
                    };
                    l.add(() -> sb.set(fx, oy + 1, fz, plant));
                }
            }
        return l;
    }

    /* -------------------------------------------------------------- */
    /* ENCLÔS À ANIMAUX (moutons)                                     */
    /* -------------------------------------------------------------- */
    public static List<Runnable> buildPen(Plugin plugin,
                                          Location base,
                                          int villageId,
                                          TerrainManager.SetBlock sb) {

        List<Runnable> l = new ArrayList<>();
        final int ox = base.getBlockX(), oy = base.getBlockY(), oz = base.getBlockZ();

        /* clôture 6 × 6 */
        for (int dx = 0; dx <= 6; dx++)
            for (int dz = 0; dz <= 6; dz++) {
                boolean edge = dx == 0 || dx == 6 || dz == 0 || dz == 6;
                if (!edge) continue;
                final int fx = ox + dx, fz = oz + dz;
                l.add(() -> sb.set(fx, oy + 1, fz, Material.OAK_FENCE));
            }

        /* herbe */
        for (int dx = 1; dx < 6; dx++)
            for (int dz = 1; dz < 6; dz++) {
                final int fx = ox + dx, fz = oz + dz;
                l.add(() -> sb.set(fx, oy, fz, Material.GRASS_BLOCK));
            }

        /* trois moutons tagués */
        l.add(() -> {
            World w = base.getWorld();
            Random R = new Random();
            for (int i = 0; i < 3; i++) {
                Location loc = base.clone()
                        .add(1.5 + R.nextDouble() * 3, 1,
                                1.5 + R.nextDouble() * 3);
                var e = w.spawnEntity(loc, EntityType.SHEEP);
                e.setMetadata(VillageEntityManager.TAG,
                        new FixedMetadataValue(plugin, villageId));
            }
        });

        return l;
    }

    /* -------------------------------------------------------------- */
    /* LAMPADAIRE (poteau + lanterne)                                 */
    /* -------------------------------------------------------------- */
    public static List<Runnable> buildLampPost(int x, int y, int z,
                                               TerrainManager.SetBlock sb) {

        List<Runnable> l = new ArrayList<>();
        /* poteau */
        for (int dy = 0; dy <= 3; dy++) {
            final int fy = y + dy;
            l.add(() -> sb.set(x, fy, z, Material.OAK_LOG));
        }
        /* chaîne + lanterne */
        l.add(() -> sb.set(x, y + 4, z, Material.CHAIN));
        l.add(() -> sb.set(x, y + 3, z, Material.LANTERN));
        return l;
    }

    /* -------------------------------------------------------------- */
    /* MAISON STANDARD (refonte 2025‑06)                              */
    /* -------------------------------------------------------------- */
    public static List<Runnable> buildHouse(Plugin plugin,
                                            Location base, int size, int rot,
                                            List<Material> logs,
                                            List<Material> planks,
                                            List<Material> roofs,
                                            TerrainManager.SetBlock sb,
                                            Random rng,
                                            int villageId) {

        List<Runnable> tasks = new ArrayList<>();

        /* --- coordonnées absolues --- */
        final int ox = base.getBlockX();
        final int oy = base.getBlockY();
        final int oz = base.getBlockZ();

        /* --- matériaux aléatoires / palettes --- */
        final int  wallHeight = (size <= 7 ? 4 : 5);
        final Material fundMat   = Material.STONE_BRICKS;
        final Material windowMat = Material.GLASS;
        final Material roofMat   = pickRandom(roofs, rng, Material.STONE_BRICK_STAIRS);
        final Material floorMat  = planks.get(rng.nextInt(planks.size()));
        final Material logMat    = logs.get(rng.nextInt(logs.size()));
        final Material wallMat   = planks.get(rng.nextInt(planks.size()));

        /* 1) fondations + plancher */
        for (int dx = 0; dx < size; dx++)
            for (int dz = 0; dz < size; dz++) {
                int[] p = rotate(dx, dz, rot);
                int fx = ox + p[0], fz = oz + p[1];
                tasks.add(() -> sb.set(fx, oy - 1, fz, fundMat));
                tasks.add(() -> sb.set(fx, oy    , fz, floorMat));
            }

        /* 2) murs + fenêtres */
        for (int dx = 0; dx < size; dx++)
            for (int dz = 0; dz < size; dz++) {
                boolean edge   = dx == 0 || dz == 0 || dx == size - 1 || dz == size - 1;
                if (!edge) continue;
                boolean corner = (dx == 0 || dx == size - 1) && (dz == 0 || dz == size - 1);

                for (int h = 1; h <= wallHeight; h++) {
                    int[] p = rotate(dx, dz, rot);
                    int fx = ox + p[0], fy = oy + h, fz = oz + p[1];

                    boolean windowLayer = (h == 2 || h == 3) && !corner;
                    boolean evenPos = ((rot == 0 || rot == 180) ? dx : dz) % 2 == 0;
                    boolean putWindow = windowLayer && evenPos;

                    Material m = putWindow ? windowMat : (corner ? logMat : wallMat);
                    tasks.add(() -> sb.set(fx, fy, fz, m));
                }
            }

        /* 3) porte + perron réel */
        int[] doorL = switch (rot) {
            case 0   -> new int[]{size / 2, size - 1};
            case 90  -> new int[]{0,        size / 2};
            case 180 -> new int[]{size / 2, 0       };
            case 270 -> new int[]{size - 1, size / 2};
            default  -> new int[]{size / 2, size - 1};
        };
        placeDoorWithPorch(tasks, base, rot, doorL, fundMat, sb);

        /* 4) toit en escaliers (palette roof) */
        buildStairRoof(tasks, base, size, rot, wallHeight, roofMat, sb);

        /* 5) éclairage intérieur */
        int[] centre = rotate(size / 2, size / 2, rot);
        int cx = ox + centre[0], cz = oz + centre[1];
        tasks.add(() -> sb.set(cx, oy + wallHeight, cz, Material.CHAIN));
        tasks.add(() -> sb.set(cx, oy + wallHeight - 1, cz, Material.LANTERN));

        /* 6) mobilier plus riche */
        tasks.addAll(decorateInterior(base, size, rot, sb, rng));

        return tasks;
    }

    /* -------------------------------------------------------------- */
    /* SURCHARGE courte (x‑y‑z) pour compat Disposition               */
    /* -------------------------------------------------------------- */
    public static List<Runnable> buildHouse(int x, int y, int z,
                                            int size, int rot,
                                            List<Material> roadPalette,
                                            List<Material> roofPalette,
                                            List<Material> wallLogs,
                                            List<Material> wallPlanks,
                                            TerrainManager.SetBlock sb) {

        return buildHouse(null,
                new Location(null, x, y, z),
                size, rot,
                wallLogs, wallPlanks, roofPalette,
                sb, new Random(), 0);
    }

    /* -------------------------------------------------------------- */
    /* util rotation                                                  */
    /* -------------------------------------------------------------- */
    private static int[] rotate(int dx, int dz, int rot) {
        return switch (rot) {
            case 90  -> new int[]{ dz, -dx};
            case 180 -> new int[]{-dx, -dz};
            case 270 -> new int[]{-dz,  dx};
            default  -> new int[]{ dx,  dz};
        };
    }

    private static BlockFace mapFacing(BlockFace face, int rot) {
        int dx = 0, dz = 0;
        switch (face) {
            case EAST  -> { dx = 1;  dz = 0; }
            case WEST  -> { dx = -1; dz = 0; }
            case SOUTH -> { dx = 0;  dz = 1; }
            case NORTH -> { dx = 0;  dz = -1; }
            default    -> { dx = 0;  dz = -1; }
        }
        int[] r = rotate(dx, dz, rot);
        return faceFromDelta(r[0], r[1]);
    }

    private static BlockFace faceFromDelta(int dx, int dz) {
        if (dx == 0 && dz == -1) return BlockFace.NORTH;
        if (dx == 0 && dz == 1)  return BlockFace.SOUTH;
        if (dx == 1 && dz == 0)  return BlockFace.EAST;
        if (dx == -1 && dz == 0) return BlockFace.WEST;
        return BlockFace.NORTH;
    }

    private static Material pickRandom(List<Material> mats, Random rng, Material fallback) {
        if (mats == null || mats.isEmpty()) {
            return fallback;
        }
        Random r = rng != null ? rng : new Random();
        return mats.get(r.nextInt(mats.size()));
    }

    private static void buildStairRoof(List<Runnable> tasks,
                                       Location base,
                                       int size, int rot,
                                       int wallHeight,
                                       Material roofMat,
                                       TerrainManager.SetBlock sb) {

        int roofStartY = base.getBlockY() + wallHeight + 1;
        int levels = size / 2;

        for (int level = 0; level < levels; level++) {
            int y = roofStartY + level;
            int min = level, max = size - 1 - level;

            for (int dx = min; dx <= max; dx++) {
                addStair(tasks, base, sb, rot, dx, min, y, roofMat, BlockFace.NORTH);
                addStair(tasks, base, sb, rot, dx, max, y, roofMat, BlockFace.SOUTH);
            }
            for (int dz = min + 1; dz <= max - 1; dz++) {
                addStair(tasks, base, sb, rot, min, dz, y, roofMat, BlockFace.WEST);
                addStair(tasks, base, sb, rot, max, dz, y, roofMat, BlockFace.EAST);
            }
        }

        int[] centre = rotate(size / 2, size / 2, rot);
        int fx = base.getBlockX() + centre[0];
        int fz = base.getBlockZ() + centre[1];
        int topY = roofStartY + levels;
        tasks.add(() -> sb.set(fx, topY, fz, roofMat));
    }

    private static void addStair(List<Runnable> tasks,
                                 Location base,
                                 TerrainManager.SetBlock sb,
                                 int rot,
                                 int localX, int localZ,
                                 int y,
                                 Material mat,
                                 BlockFace localFacing) {

        int[] p = rotate(localX, localZ, rot);
        int fx = base.getBlockX() + p[0];
        int fz = base.getBlockZ() + p[1];

        tasks.add(() -> sb.set(fx, y, fz, mat));

        World world = base.getWorld();
        if (world != null) {
            BlockFace facing = mapFacing(localFacing, rot);
            tasks.add(() -> {
                BlockData data = mat.createBlockData();
                if (data instanceof Stairs stairs) {
                    stairs.setFacing(facing);
                    world.getBlockAt(fx, y, fz).setBlockData(stairs, false);
                }
            });
        }
    }

    private static void placeDoorWithPorch(List<Runnable> tasks,
                                           Location base,
                                           int rot,
                                           int[] doorLocal,
                                           Material fundMat,
                                           TerrainManager.SetBlock sb) {

        int ox = base.getBlockX();
        int oy = base.getBlockY();
        int oz = base.getBlockZ();

        int[] p = rotate(doorLocal[0], doorLocal[1], rot);
        int fx = ox + p[0];
        int fz = oz + p[1];

        tasks.add(() -> sb.set(fx, oy + 1, fz, Material.OAK_DOOR));
        tasks.add(() -> sb.set(fx, oy + 2, fz, Material.OAK_DOOR));

        int[] front = switch (rot) {
            case 0   -> new int[]{ 0,  1};
            case 90  -> new int[]{-1,  0};
            case 180 -> new int[]{ 0, -1};
            case 270 -> new int[]{ 1,  0};
            default  -> new int[]{ 0,  1};
        };
        int px = fx + front[0];
        int pz = fz + front[1];
        tasks.add(() -> sb.set(px, oy, pz, Material.SMOOTH_STONE_SLAB));
        tasks.add(() -> sb.set(px, oy - 1, pz, fundMat));

        World world = base.getWorld();
        if (world != null) {
            BlockFace facing = faceFromDelta(front[0], front[1]);
            tasks.add(() -> applyDoorData(world, fx, oy + 1, fz, facing, Bisected.Half.BOTTOM));
            tasks.add(() -> applyDoorData(world, fx, oy + 2, fz, facing, Bisected.Half.TOP));
        }
    }

    private static void applyDoorData(World world, int x, int y, int z,
                                      BlockFace facing, Bisected.Half half) {
        BlockData data = Material.OAK_DOOR.createBlockData();
        if (data instanceof Door door) {
            door.setFacing(facing);
            door.setHalf(half);
            world.getBlockAt(x, y, z).setBlockData(door, false);
        }
    }

    private static void placeBed(List<Runnable> tasks, Location base,
                                 TerrainManager.SetBlock sb,
                                 int xFoot, int y, int zFoot,
                                 BlockFace facing) {

        int headX = xFoot + facing.getModX();
        int headZ = zFoot + facing.getModZ();

        tasks.add(() -> sb.set(xFoot, y, zFoot, Material.WHITE_BED));
        tasks.add(() -> sb.set(headX, y, headZ, Material.WHITE_BED));

        World world = base.getWorld();
        if (world != null) {
            tasks.add(() -> applyBedData(world, xFoot, y, zFoot, facing, Bed.Part.FOOT));
            tasks.add(() -> applyBedData(world, headX, y, headZ, facing, Bed.Part.HEAD));
        }
    }

    private static void applyBedData(World world, int x, int y, int z,
                                     BlockFace facing, Bed.Part part) {
        BlockData data = Material.WHITE_BED.createBlockData();
        if (data instanceof Bed bed) {
            bed.setFacing(facing);
            bed.setPart(part);
            world.getBlockAt(x, y, z).setBlockData(bed, false);
        }
    }

    private static void placeChair(List<Runnable> tasks, Location base,
                                   TerrainManager.SetBlock sb,
                                   int x, int y, int z,
                                   BlockFace facing) {

        tasks.add(() -> sb.set(x, y, z, Material.OAK_STAIRS));

        World world = base.getWorld();
        if (world != null) {
            tasks.add(() -> {
                BlockData data = Material.OAK_STAIRS.createBlockData();
                if (data instanceof Stairs stairs) {
                    stairs.setFacing(facing);
                    world.getBlockAt(x, y, z).setBlockData(stairs, false);
                }
            });
        }
    }

    private static void applyWallTorch(World world, int x, int y, int z, BlockFace facing) {
        BlockData data = Material.WALL_TORCH.createBlockData();
        if (data instanceof Directional directional) {
            directional.setFacing(facing);
            world.getBlockAt(x, y, z).setBlockData(directional, false);
        }
    }

    private static List<Runnable> decorateInterior(Location base, int size, int rot,
                                                   TerrainManager.SetBlock sb,
                                                   Random rng) {
        List<Runnable> tasks = new ArrayList<>();
        World world = base.getWorld();

        int ox = base.getBlockX();
        int oy = base.getBlockY();
        int oz = base.getBlockZ();

        int floorY = oy + 1;
        int innerMin = 1;
        int innerMax = size - 2;

        /* table centrale + chaises orientées */
        int[] centre = rotate(size / 2, size / 2, rot);
        int cx = ox + centre[0];
        int cz = oz + centre[1];
        tasks.add(() -> sb.set(cx, floorY, cz, Material.OAK_SLAB));
        placeChair(tasks, base, sb, cx - 1, floorY, cz, BlockFace.EAST);
        placeChair(tasks, base, sb, cx + 1, floorY, cz, BlockFace.WEST);
        placeChair(tasks, base, sb, cx, floorY, cz - 1, BlockFace.SOUTH);
        placeChair(tasks, base, sb, cx, floorY, cz + 1, BlockFace.NORTH);

        /* coin repos : vrai lit */
        int[] bedLocal = rotate(innerMin + 1, innerMin, rot);
        int bedX = ox + bedLocal[0];
        int bedZ = oz + bedLocal[1];
        placeBed(tasks, base, sb, bedX, floorY, bedZ, mapFacing(BlockFace.SOUTH, rot));

        /* coin cuisine */
        int[] furnaceL = rotate(innerMax, innerMin, rot);
        tasks.add(() -> sb.set(ox + furnaceL[0], floorY, oz + furnaceL[1], Material.FURNACE));
        int[] craftingL = rotate(innerMax, innerMin + 1, rot);
        tasks.add(() -> sb.set(ox + craftingL[0], floorY, oz + craftingL[1], Material.CRAFTING_TABLE));

        /* coffre + déco */
        int[] chestL = rotate(innerMin, innerMax, rot);
        int chestX = ox + chestL[0];
        int chestZ = oz + chestL[1];
        tasks.add(() -> sb.set(chestX, floorY, chestZ, Material.CHEST));
        tasks.add(() -> sb.set(chestX, floorY + 1, chestZ, Material.FLOWER_POT));

        /* éclairage mural orienté */
        int[] northTorch = rotate(size / 2, innerMin, rot);
        int tnX = ox + northTorch[0];
        int tnZ = oz + northTorch[1];
        tasks.add(() -> sb.set(tnX, floorY + 2, tnZ, Material.WALL_TORCH));

        int[] southTorch = rotate(size / 2, innerMax, rot);
        int tsX = ox + southTorch[0];
        int tsZ = oz + southTorch[1];
        tasks.add(() -> sb.set(tsX, floorY + 2, tsZ, Material.WALL_TORCH));

        if (world != null) {
            tasks.add(() -> applyWallTorch(world, tnX, floorY + 2, tnZ, mapFacing(BlockFace.SOUTH, rot)));
            tasks.add(() -> applyWallTorch(world, tsX, floorY + 2, tsZ, mapFacing(BlockFace.NORTH, rot)));
        }

        return tasks;
    }
}
