package org.example.village;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * Bâtiments spéciaux du village (église simple, etc.).
 */
public final class SpecialBuildings {

    private SpecialBuildings() {
    }

    /**
    * Église compacte (9x9) tenant sur un lot standard.
    */
    public static List<Runnable> buildChurch(int baseX, int baseY, int baseZ,
                                             TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();

        int width = 9;
        int length = 9;
        int height = 6;

        int minX = baseX;
        int minZ = baseZ;
        int maxX = baseX + width - 1;
        int maxZ = baseZ + length - 1;

        /* sol en pierre lisse */
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int fx = x, fz = z;
                tasks.add(() -> sb.set(fx, baseY, fz, Material.SMOOTH_STONE));
            }
        }

        /* murs + vitraux simples */
        for (int y = 1; y <= height; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
                    if (!edge) {
                        continue;
                    }
                    Material mat = Material.STONE_BRICKS;
                    if (y >= 3 && y <= 4 && (x == baseX + width / 2 || z == baseZ + length / 2)) {
                        mat = Material.BLUE_STAINED_GLASS_PANE;
                    }
                    int fx = x, fy = baseY + y, fz = z;
                    Material finalMat = mat;
                    tasks.add(() -> sb.set(fx, fy, fz, finalMat));
                }
            }
        }

        /* toit simple */
        for (int level = 0; level < 3; level++) {
            int inset = level;
            int roofY = baseY + height + level;
            int insetVal = inset;
            for (int x = minX + inset; x <= maxX - inset; x++) {
                int fx = x;
                tasks.add(() -> sb.set(fx, roofY, minZ + insetVal, Material.OAK_LOG));
                tasks.add(() -> sb.set(fx, roofY, maxZ - insetVal, Material.OAK_LOG));
            }
            for (int z = minZ + inset; z <= maxZ - inset; z++) {
                int fz = z;
                tasks.add(() -> sb.set(minX + insetVal, roofY, fz, Material.OAK_LOG));
                tasks.add(() -> sb.set(maxX - insetVal, roofY, fz, Material.OAK_LOG));
            }
        }

        /* croix sur le toit */
        int crossY = baseY + height + 3;
        int cx = baseX + width / 2;
        int cz = baseZ + length / 2;
        tasks.add(() -> sb.set(cx, crossY, cz, Material.OAK_LOG));
        tasks.add(() -> sb.set(cx, crossY + 1, cz, Material.OAK_LOG));
        tasks.add(() -> sb.set(cx - 1, crossY + 1, cz, Material.OAK_PLANKS));
        tasks.add(() -> sb.set(cx + 1, crossY + 1, cz, Material.OAK_PLANKS));

        /* intérieur : bancs + autel */
        for (int z = minZ + 2; z <= maxZ - 2; z += 2) {
            int fz = z;
            tasks.add(() -> sb.set(cx - 2, baseY + 1, fz, Material.SPRUCE_STAIRS));
            tasks.add(() -> sb.set(cx + 2, baseY + 1, fz, Material.SPRUCE_STAIRS));
        }

        int altarZ = maxZ - 2;
        tasks.add(() -> sb.set(cx, baseY + 1, altarZ, Material.QUARTZ_BLOCK));
        tasks.add(() -> sb.set(cx, baseY + 2, altarZ, Material.CANDLE));
        tasks.add(() -> sb.set(cx, baseY + 2, altarZ - 1, Material.CANDLE));

        return tasks;
    }

    /**
     * Forge simple (9x9) avec poste de travail complet.
     */
    public static List<Runnable> buildForge(int baseX, int baseY, int baseZ,
                                            TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();

        int size = 9;
        int max = size - 1;

        /* sol cobble */
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                int fx = baseX + dx, fz = baseZ + dz;
                tasks.add(() -> sb.set(fx, baseY, fz, Material.COBBLESTONE));
            }
        }

        /* murets bas */
        for (int y = 1; y <= 3; y++) {
            for (int dx = 0; dx < size; dx++) {
                for (int dz = 0; dz < size; dz++) {
                    boolean edge = dx == 0 || dz == 0 || dx == max || dz == max;
                    if (!edge) continue;
                    int fx = baseX + dx, fy = baseY + y, fz = baseZ + dz;
                    tasks.add(() -> sb.set(fx, fy, fz, Material.STONE_BRICKS));
                }
            }
        }

        int cx = baseX + size / 2;
        int cz = baseZ + size / 2;

        /* équipements */
        tasks.add(() -> sb.set(cx, baseY + 1, cz, Material.BLAST_FURNACE));
        tasks.add(() -> sb.set(cx + 1, baseY + 1, cz, Material.ANVIL));
        tasks.add(() -> sb.set(cx - 1, baseY + 1, cz, Material.SMITHING_TABLE));
        tasks.add(() -> sb.set(cx, baseY + 1, cz + 1, Material.IRON_BLOCK));

        /* cheminée simple */
        int chimneyX = baseX + max - 1;
        int chimneyZ = baseZ + max - 1;
        tasks.add(() -> sb.set(chimneyX, baseY + 1, chimneyZ, Material.COBBLESTONE_WALL));
        tasks.add(() -> sb.set(chimneyX, baseY + 2, chimneyZ, Material.COBBLESTONE_WALL));
        tasks.add(() -> sb.set(chimneyX, baseY + 3, chimneyZ, Material.CAMPFIRE));

        /* toiture légère en slabs */
        for (int dx = 1; dx < size - 1; dx++) {
            for (int dz = 1; dz < size - 1; dz++) {
                if (dx == 1 || dx == size - 2 || dz == 1 || dz == size - 2) {
                    int fx = baseX + dx, fz = baseZ + dz;
                    tasks.add(() -> sb.set(fx, baseY + 4, fz, Material.OAK_LOG));
                }
            }
        }

        return tasks;
    }
}
