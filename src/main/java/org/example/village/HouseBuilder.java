package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
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
        Random R = new Random();

        /* segment X */
        int minX = Math.min(x0, x1);
        int maxX = Math.max(x0, x1);
        for (int x = minX; x <= maxX; x++)
            for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                final int fx = x, fz = z0 + dz;
                Material m = palette.get(R.nextInt(palette.size()));
                l.add(() -> sb.set(fx, y, fz, m));
            }

        /* segment Z */
        int minZ = Math.min(z0, z1);
        int maxZ = Math.max(z0, z1);
        for (int z = minZ; z <= maxZ; z++)
            for (int dx = -halfWidth; dx <= halfWidth; dx++) {
                final int fx = x1 + dx, fz = z;
                Material m = palette.get(R.nextInt(palette.size()));
                l.add(() -> sb.set(fx, y, fz, m));
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
    /* CHAMP (farmland + eau + cultures)                              */
    /* -------------------------------------------------------------- */
    public static List<Runnable> buildFarm(Location base,
                                           List<Material> crops,
                                           TerrainManager.SetBlock sb) {

        List<Runnable> l = new ArrayList<>();
        Random R = new Random();
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
        final Material windowMat = Material.GLASS_PANE;
        final Material roofMat   = roofs.get(rng.nextInt(roofs.size()));
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

        /* 3) porte + perron */
        int[] doorL = switch (rot) {
            case 0   -> new int[]{size / 2, size - 1};
            case 90  -> new int[]{0,        size / 2};
            case 180 -> new int[]{size / 2, 0       };
            case 270 -> new int[]{size - 1, size / 2};
            default  -> new int[]{size / 2, size - 1};
        };
        {
            int[] p = rotate(doorL[0], doorL[1], rot);
            int fx = ox + p[0], fz = oz + p[1];
            tasks.add(() -> sb.set(fx, oy + 1, fz, Material.AIR));
            tasks.add(() -> sb.set(fx, oy + 2, fz, Material.AIR));

            int[] front = switch (rot) {
                case 0   -> new int[]{ 0,  1};
                case 90  -> new int[]{-1,  0};
                case 180 -> new int[]{ 0, -1};
                case 270 -> new int[]{ 1,  0};
                default  -> new int[]{ 0,  1};
            };
            int sx = fx + front[0], sz = fz + front[1];
            tasks.add(() -> sb.set(sx, oy,   sz, Material.OAK_SLAB));
            tasks.add(() -> sb.set(sx, oy-1, sz, fundMat));
        }

        /* 4) toit pyramidal */
        int roofBaseY = oy + wallHeight + 1;
        int layers = size / 2 + 1;
        for (int layer = 0; layer < layers; layer++) {
            int y = roofBaseY + layer;
            int min = layer, max = size - 1 - layer;
            for (int dx2 = min; dx2 <= max; dx2++)
                for (int dz2 = min; dz2 <= max; dz2++) {
                    boolean edge = dx2 == min || dx2 == max || dz2 == min || dz2 == max;
                    if (!edge) continue;
                    int[] p = rotate(dx2, dz2, rot);
                    int fx = ox + p[0], fz = oz + p[1];
                    tasks.add(() -> sb.set(fx, y, fz, roofMat));
                }
        }

        /* 5) éclairage intérieur */
        int[] centre = rotate(size / 2, size / 2, rot);
        int cx = ox + centre[0], cz = oz + centre[1];
        tasks.add(() -> sb.set(cx, oy + wallHeight + 1, cz, Material.CHAIN));
        tasks.add(() -> sb.set(cx, oy + wallHeight    , cz, Material.LANTERN));

        /* 6) mobilier de base */
        int[] chestL = rotate(1, 1, rot);
        tasks.add(() -> sb.set(ox + chestL[0], oy + 1, oz + chestL[1], Material.CHEST));
        int[] craftL = rotate(size - 2, 1, rot);
        tasks.add(() -> sb.set(ox + craftL[0], oy + 1, oz + craftL[1], Material.CRAFTING_TABLE));

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
}
