package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Queue;
import java.util.Random;

/**
 * Classe utilitaire ; toutes les méthodes sont statiques.
 */
public final class Disposition {

    private Disposition() {}

    /** Point d’entrée appelé par {@link org.example.Village}. */
    public static void buildVillage(JavaPlugin plugin,
                                    Location center,
                                    int rows, int cols, int baseY,
                                    int smallSize, int bigSize, int spacing, int roadHalf,
                                    List<Material> wallLogs,     List<Material> wallPlanks,
                                    List<Material> roofPalette,  List<Material> roadPalette,
                                    List<Material> cropSeeds,
                                    Queue<Runnable> tasks,
                                    TerrainManager.SetBlock sb,
                                    int villageId) {

        scheduleLayout(plugin, center, rows, cols, baseY, villageId,
                smallSize, spacing, roadHalf,
                roadPalette, roofPalette, wallLogs, wallPlanks, cropSeeds,
                tasks, sb);
    }

    /* ------------------------------------------------------------------ */
    /*  Implémentation détaillée                                          */
    /* ------------------------------------------------------------------ */
    private static void scheduleLayout(JavaPlugin plugin,
                                       Location center,
                                       int rows, int cols, int baseY, int villageId,
                                       int smallSize, int spacing, int roadHalf,
                                       List<Material> roadPalette, List<Material> roofPalette,
                                       List<Material> wallLogs,     List<Material> wallPlanks,
                                       List<Material> cropSeeds,
                                       Queue<Runnable> q,
                                       TerrainManager.SetBlock sb) {

        int lot  = smallSize;                // largeur standard d’une maison
        int grid = lot + spacing;            // centre-à-centre (lot + rue)
        int cx   = center.getBlockX();       // repère 0,0 = centre de la place
        int cz   = center.getBlockZ();
        Random rng  = new Random();

        /* --- grille routes + lots --- */
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {

                int baseX = cx + (c - (cols - 1) / 2) * grid;
                int baseZ = cz + (r - (rows - 1) / 2) * grid;

                /* 1) bande de route (axe N‑S) */
                int roadX = baseX;
                HouseBuilder.paintStrip(q, roadPalette,
                        roadX, baseY,
                        baseZ - roadHalf, baseZ + roadHalf, sb);

                /* 2) choix bâtiment sur le lot */
                int lotX = baseX - lot / 2;
                int lotZ = baseZ - lot / 2;
                double roll = rng.nextDouble();

                if (roll < 0.60) {
                    q.addAll(HouseBuilder.buildHouse(
                            plugin,
                            new Location(center.getWorld(), lotX, baseY + 1, lotZ),
                            smallSize, rng.nextInt(4),
                            wallLogs, wallPlanks, roofPalette,
                            sb, rng, villageId));

                } else if (roll < 0.85) {
                    q.addAll(HouseBuilder.buildFarm(
                            new Location(center.getWorld(), lotX, baseY + 1, lotZ),
                            cropSeeds, sb));

                } else {
                    q.addAll(HouseBuilder.buildPen(
                            plugin,
                            new Location(center.getWorld(), lotX, baseY + 1, lotZ),
                            villageId, sb));
                }
            }
        }

        /* --- lampadaires aux carrefours --- */
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int x = cx + (c - (cols - 1) / 2) * grid;
                int z = cz + (r - (rows - 1) / 2) * grid;
                q.addAll(HouseBuilder.buildLampPost(x, baseY + 1, z, sb));
            }
        }
    }
}
