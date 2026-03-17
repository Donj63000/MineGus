package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;

import java.util.List;
import java.util.Queue;
import java.util.random.RandomGenerator;

/**
 * Muraille plus lisible visuellement :
 * - variation de pierres,
 * - créneaux et chemin de ronde,
 * - tours d'angle plus marquées,
 * - gatehouse plus massif.
 */
public final class WallBuilder {

    private WallBuilder() {}

    private static final int WALL_HEIGHT = 8;
    private static final int WALL_THICKNESS = 3;
    private static final int GATE_HALF_WIDTH = 2;
    private static final int TOWER_RADIUS = 2;

    private static final List<Material> BODY = List.of(
            Material.STONE_BRICKS,
            Material.MOSSY_STONE_BRICKS,
            Material.CRACKED_STONE_BRICKS,
            Material.COBBLESTONE
    );

    public static void build(Location center, int rx, int rz, int baseY,
                             Material wallMat,
                             Queue<Runnable> q,
                             TerrainManager.SetBlock sb) {

        World world = center.getWorld();
        int cx = center.getBlockX();
        int cz = center.getBlockZ();
        int minX = cx - rx - WALL_THICKNESS;
        int maxX = cx + rx + WALL_THICKNESS;
        int minZ = cz - rz - WALL_THICKNESS;
        int maxZ = cz + rz + WALL_THICKNESS;
        RandomGenerator random = RandomGenerator.getDefault();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean ring = x < minX + WALL_THICKNESS || x > maxX - WALL_THICKNESS
                        || z < minZ + WALL_THICKNESS || z > maxZ - WALL_THICKNESS;
                if (!ring) {
                    continue;
                }
                boolean southGate = z >= maxZ - WALL_THICKNESS + 1 && Math.abs(x - cx) <= GATE_HALF_WIDTH;
                if (southGate) {
                    continue;
                }

                for (int y = baseY + 1; y <= baseY + WALL_HEIGHT; y++) {
                    int fx = x;
                    int fy = y;
                    int fz = z;
                    Material body = pickWallMaterial(wallMat, random, x, y, z);
                    q.add(() -> sb.set(fx, fy, fz, body));
                }

                // Petit fruit de mur / contreforts simples pour casser la masse.
                if ((x + z) % 7 == 0 && isOuterFace(x, z, minX, maxX, minZ, maxZ)) {
                    int bx = x < cx ? x + 1 : x > cx ? x - 1 : x;
                    int bz = z < cz ? z + 1 : z > cz ? z - 1 : z;
                    q.add(() -> sb.set(bx, baseY + 1, bz, Material.STONE_BRICK_WALL));
                    q.add(() -> sb.set(bx, baseY + 2, bz, Material.STONE_BRICK_WALL));
                }

                // Meurtrières ponctuelles.
                if ((x + z) % 9 == 0 && isOuterFace(x, z, minX, maxX, minZ, maxZ)) {
                    int slitY = baseY + 4;
                    int bx = x < cx ? x : x > cx ? x : x;
                    int bz = z < cz ? z : z > cz ? z : z;
                    q.add(() -> sb.set(bx, slitY, bz, Material.IRON_BARS));
                }
            }
        }

        addCrenellations(q, sb, minX, maxX, minZ, maxZ, baseY);
        addWallWalk(q, world, sb, minX, maxX, minZ, maxZ, baseY);
        buildTower(q, world, sb, minX, minZ, baseY);
        buildTower(q, world, sb, minX, maxZ, baseY);
        buildTower(q, world, sb, maxX, minZ, baseY);
        buildTower(q, world, sb, maxX, maxZ, baseY);
        buildGatehouse(q, world, sb, cx, maxZ - 1, baseY);
    }

    private static Material pickWallMaterial(Material primary, RandomGenerator random, int x, int y, int z) {
        if ((x + y + z) % 11 == 0) {
            return Material.MOSSY_STONE_BRICKS;
        }
        if ((x * 3 + z * 5 + y) % 13 == 0) {
            return Material.CRACKED_STONE_BRICKS;
        }
        if ((x + z) % 7 == 0) {
            return Material.COBBLESTONE;
        }
        return primary != null ? primary : BODY.get(random.nextInt(BODY.size()));
    }

    private static boolean isOuterFace(int x, int z, int minX, int maxX, int minZ, int maxZ) {
        return x == minX || x == maxX || z == minZ || z == maxZ;
    }

    private static void addCrenellations(Queue<Runnable> q,
                                         TerrainManager.SetBlock sb,
                                         int minX, int maxX, int minZ, int maxZ, int baseY) {
        int top = baseY + WALL_HEIGHT + 1;
        for (int x = minX; x <= maxX; x++) {
            if (x % 2 == 0) {
                final int fx = x;
                q.add(() -> sb.set(fx, top, minZ, Material.STONE_BRICKS));
                q.add(() -> sb.set(fx, top, maxZ, Material.STONE_BRICKS));
            }
        }
        for (int z = minZ; z <= maxZ; z++) {
            if (z % 2 == 0) {
                final int fz = z;
                q.add(() -> sb.set(minX, top, fz, Material.STONE_BRICKS));
                q.add(() -> sb.set(maxX, top, fz, Material.STONE_BRICKS));
            }
        }
    }

    private static void addWallWalk(Queue<Runnable> q,
                                    World world,
                                    TerrainManager.SetBlock sb,
                                    int minX, int maxX, int minZ, int maxZ, int baseY) {
        int walkY = baseY + WALL_HEIGHT;
        for (int x = minX + 1; x <= maxX - 1; x++) {
            final int fx = x;
            q.add(() -> sb.set(fx, walkY, minZ + 1, Material.STONE_BRICK_SLAB));
            q.add(() -> sb.set(fx, walkY, maxZ - 1, Material.STONE_BRICK_SLAB));
            q.add(() -> VillageStyle.setSlab(world, fx, walkY, minZ + 1, Material.STONE_BRICK_SLAB, Slab.Type.TOP));
            q.add(() -> VillageStyle.setSlab(world, fx, walkY, maxZ - 1, Material.STONE_BRICK_SLAB, Slab.Type.TOP));
        }
        for (int z = minZ + 1; z <= maxZ - 1; z++) {
            final int fz = z;
            q.add(() -> sb.set(minX + 1, walkY, fz, Material.STONE_BRICK_SLAB));
            q.add(() -> sb.set(maxX - 1, walkY, fz, Material.STONE_BRICK_SLAB));
            q.add(() -> VillageStyle.setSlab(world, minX + 1, walkY, fz, Material.STONE_BRICK_SLAB, Slab.Type.TOP));
            q.add(() -> VillageStyle.setSlab(world, maxX - 1, walkY, fz, Material.STONE_BRICK_SLAB, Slab.Type.TOP));
        }
    }

    private static void buildTower(Queue<Runnable> q,
                                   World world,
                                   TerrainManager.SetBlock sb,
                                   int x,
                                   int z,
                                   int baseY) {
        int top = baseY + WALL_HEIGHT + 3;
        for (int dx = -TOWER_RADIUS; dx <= TOWER_RADIUS; dx++) {
            for (int dz = -TOWER_RADIUS; dz <= TOWER_RADIUS; dz++) {
                boolean shell = Math.abs(dx) == TOWER_RADIUS || Math.abs(dz) == TOWER_RADIUS;
                if (!shell) {
                    continue;
                }
                for (int y = baseY + 1; y <= top; y++) {
                    int fx = x + dx;
                    int fy = y;
                    int fz = z + dz;
                    q.add(() -> sb.set(fx, fy, fz, (fx + fy + fz) % 4 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS));
                }
            }
        }

        // Plateforme et garde-corps.
        int platformY = top;
        for (int dx = -TOWER_RADIUS + 1; dx <= TOWER_RADIUS - 1; dx++) {
            for (int dz = -TOWER_RADIUS + 1; dz <= TOWER_RADIUS - 1; dz++) {
                int fx = x + dx;
                int fz = z + dz;
                q.add(() -> sb.set(fx, platformY, fz, Material.STONE_BRICK_SLAB));
                q.add(() -> VillageStyle.setSlab(world, fx, platformY, fz, Material.STONE_BRICK_SLAB, Slab.Type.TOP));
            }
        }

        // Toit en pavillon.
        for (int layer = 0; layer < 2; layer++) {
            int roofY = top + layer + 1;
            for (int dx = -TOWER_RADIUS - 1 + layer; dx <= TOWER_RADIUS + 1 - layer; dx++) {
                final int fx = x + dx;
                final int northZ = z - TOWER_RADIUS - 1 + layer;
                final int southZ = z + TOWER_RADIUS + 1 - layer;
                q.add(() -> sb.set(fx, roofY, northZ, Material.DARK_OAK_STAIRS));
                q.add(() -> sb.set(fx, roofY, southZ, Material.DARK_OAK_STAIRS));
                q.add(() -> VillageStyle.setStair(world, fx, roofY, northZ, Material.DARK_OAK_STAIRS, BlockFace.NORTH, Stairs.Half.BOTTOM, Stairs.Shape.STRAIGHT));
                q.add(() -> VillageStyle.setStair(world, fx, roofY, southZ, Material.DARK_OAK_STAIRS, BlockFace.SOUTH, Stairs.Half.BOTTOM, Stairs.Shape.STRAIGHT));
            }
        }
        final int topX = x;
        final int topZ = z;
        q.add(() -> sb.set(topX, top + 3, topZ, Material.DARK_OAK_SLAB));
        q.add(() -> VillageStyle.setSlab(world, topX, top + 3, topZ, Material.DARK_OAK_SLAB, Slab.Type.TOP));
        q.add(() -> sb.set(topX, baseY + 3, topZ - TOWER_RADIUS, Material.LANTERN));
    }

    private static void buildGatehouse(Queue<Runnable> q,
                                       World world,
                                       TerrainManager.SetBlock sb,
                                       int centerX,
                                       int gateZ,
                                       int baseY) {
        int left = centerX - GATE_HALF_WIDTH - 3;
        int right = centerX + GATE_HALF_WIDTH + 3;
        int frontZ = gateZ + 3;
        int backZ = gateZ - 3;

        // Corps principal du portail.
        for (int x = left; x <= right; x++) {
            for (int z = backZ; z <= frontZ; z++) {
                boolean corridor = Math.abs(x - centerX) <= GATE_HALF_WIDTH && z >= gateZ - 1 && z <= frontZ;
                if (corridor) {
                    continue;
                }
                for (int y = baseY + 1; y <= baseY + WALL_HEIGHT + 3; y++) {
                    int fx = x;
                    int fy = y;
                    int fz = z;
                    q.add(() -> sb.set(fx, fy, fz, (fx + fy + fz) % 5 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS));
                }
            }
        }

        // Arche de passage.
        for (int x = centerX - GATE_HALF_WIDTH - 1; x <= centerX + GATE_HALF_WIDTH + 1; x++) {
            final int fx = x;
            q.add(() -> sb.set(fx, baseY + WALL_HEIGHT + 1, gateZ, Material.STONE_BRICK_STAIRS));
            q.add(() -> VillageStyle.setStair(world, fx, baseY + WALL_HEIGHT + 1, gateZ, Material.STONE_BRICK_STAIRS, BlockFace.SOUTH, Stairs.Half.BOTTOM, Stairs.Shape.STRAIGHT));
        }

        // Portcullis / herse décorative.
        for (int x = centerX - GATE_HALF_WIDTH; x <= centerX + GATE_HALF_WIDTH; x++) {
            final int fx = x;
            q.add(() -> sb.set(fx, baseY + 1, gateZ + 1, Material.IRON_BARS));
            q.add(() -> sb.set(fx, baseY + 2, gateZ + 1, Material.IRON_BARS));
            q.add(() -> sb.set(fx, baseY + 3, gateZ + 1, Material.IRON_BARS));
        }

        // Sol du passage.
        for (int z = gateZ - 2; z <= frontZ; z++) {
            for (int x = centerX - GATE_HALF_WIDTH; x <= centerX + GATE_HALF_WIDTH; x++) {
                final int fx = x;
                final int fz = z;
                q.add(() -> sb.set(fx, baseY, fz, (fx + fz) % 2 == 0 ? Material.POLISHED_ANDESITE : Material.COBBLESTONE));
            }
        }

        // Bannières et braseros.
        q.add(() -> sb.set(left, baseY + 4, gateZ, Material.LANTERN));
        q.add(() -> sb.set(right, baseY + 4, gateZ, Material.LANTERN));
        q.add(() -> sb.set(left + 1, baseY + 5, gateZ - 1, Material.RED_BANNER));
        q.add(() -> sb.set(right - 1, baseY + 5, gateZ - 1, Material.RED_BANNER));
        q.add(() -> sb.set(left + 1, baseY + 1, gateZ + 2, Material.CAMPFIRE));
        q.add(() -> sb.set(right - 1, baseY + 1, gateZ + 2, Material.CAMPFIRE));
    }
}
