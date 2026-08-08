package org.example.village;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Stairs;

import java.util.ArrayList;
import java.util.List;

import static org.example.village.VillageLayoutPlan.PassagePlan;
import static org.example.village.VillageLayoutPlan.PassageStyle;

/**
 * Construit les passages couverts qui ponctuent les ruelles médiévales.
 *
 * <p>Chaque ouvrage conserve un gabarit libre de trois blocs de haut. Les
 * fondations et les piles restent hors de l'axe de circulation afin que les
 * villageois, golems et joueurs puissent réellement emprunter la venelle.</p>
 */
public final class MedievalPassageBuilder {

    private MedievalPassageBuilder() {}

    public static List<Runnable> build(World world,
                                       PassagePlan passage,
                                       int streetY,
                                       TerrainManager.SetBlock setBlock) {
        List<Runnable> tasks = new ArrayList<>();
        if (passage == null || setBlock == null) {
            return tasks;
        }

        PassagePalette palette = palette(passage.style());
        boolean horizontal = passage.horizontalStreet();
        int perpendicularX = horizontal ? 0 : 1;
        int perpendicularZ = horizontal ? 1 : 0;
        int alongX = horizontal ? 1 : 0;
        int alongZ = horizontal ? 0 : 1;
        int offset = passage.postOffset();

        // Deux piles épaisses, ancrées sous le niveau de la rue.
        for (int sign : new int[]{-1, 1}) {
            int postX = passage.centerX()
                    + perpendicularX * offset * sign;
            int postZ = passage.centerZ()
                    + perpendicularZ * offset * sign;

            place(
                    tasks,
                    setBlock,
                    postX,
                    streetY - 1,
                    postZ,
                    Material.COBBLESTONE
            );
            place(
                    tasks,
                    setBlock,
                    postX,
                    streetY,
                    postZ,
                    palette.plinth()
            );
            for (int dy = 1; dy <= 4; dy++) {
                Material material = dy == 1
                        ? palette.plinth()
                        : palette.pillar();
                place(
                        tasks,
                        setBlock,
                        postX,
                        streetY + dy,
                        postZ,
                        material
                );
            }

            // Contrefort court du côté extérieur de la ruelle.
            int braceX = postX + perpendicularX * sign;
            int braceZ = postZ + perpendicularZ * sign;
            place(
                    tasks,
                    setBlock,
                    braceX,
                    streetY,
                    braceZ,
                    palette.plinth()
            );
            place(
                    tasks,
                    setBlock,
                    braceX,
                    streetY + 1,
                    braceZ,
                    palette.wall()
            );
        }

        /*
         * Traverse porteuse. L'orientation des bûches est appliquée dans une
         * tâche distincte après la pose du matériau.
         */
        Axis beamAxis = horizontal ? Axis.Z : Axis.X;
        for (int across = -offset; across <= offset; across++) {
            int x = passage.centerX() + perpendicularX * across;
            int z = passage.centerZ() + perpendicularZ * across;
            place(
                    tasks,
                    setBlock,
                    x,
                    streetY + 4,
                    z,
                    palette.beam()
            );
            tasks.add(() -> VillageStyle.setLogAxis(
                    world,
                    x,
                    streetY + 4,
                    z,
                    palette.beam(),
                    beamAxis
            ));
        }

        /*
         * Petit étage-pont fermé sur les côtés. Il donne l'impression qu'une
         * pièce relie deux maisons sans ajouter de collision au rez-de-chaussée.
         */
        for (int along = -1; along <= 1; along++) {
            for (int across = -offset; across <= offset; across++) {
                int x = passage.centerX()
                        + alongX * along
                        + perpendicularX * across;
                int z = passage.centerZ()
                        + alongZ * along
                        + perpendicularZ * across;
                Material floor = Math.abs(across) == offset
                        ? palette.beam()
                        : palette.floor();
                place(
                        tasks,
                        setBlock,
                        x,
                        streetY + 5,
                        z,
                        floor
                );
            }
        }

        // Pignons latéraux et meurtrières vitrées.
        for (int sign : new int[]{-1, 1}) {
            int wallX = passage.centerX()
                    + perpendicularX * offset * sign;
            int wallZ = passage.centerZ()
                    + perpendicularZ * offset * sign;
            for (int along = -1; along <= 1; along++) {
                int x = wallX + alongX * along;
                int z = wallZ + alongZ * along;
                place(
                        tasks,
                        setBlock,
                        x,
                        streetY + 6,
                        z,
                        along == 0
                                ? Material.YELLOW_STAINED_GLASS
                                : palette.wall()
                );
            }
        }

        /*
         * Toiture à deux versants. Les marches regardent vers l'extérieur ;
         * une ligne de dalles ferme le faîtage et empêche tout trou visible.
         */
        BlockFace firstSlope = horizontal
                ? BlockFace.NORTH
                : BlockFace.WEST;
        BlockFace secondSlope = VillageStyle.opposite(firstSlope);
        for (int along = -2; along <= 2; along++) {
            int firstX = passage.centerX()
                    + alongX * along
                    - perpendicularX * (offset + 1);
            int firstZ = passage.centerZ()
                    + alongZ * along
                    - perpendicularZ * (offset + 1);
            int secondX = passage.centerX()
                    + alongX * along
                    + perpendicularX * (offset + 1);
            int secondZ = passage.centerZ()
                    + alongZ * along
                    + perpendicularZ * (offset + 1);

            place(
                    tasks,
                    setBlock,
                    firstX,
                    streetY + 6,
                    firstZ,
                    palette.roofStairs()
            );
            place(
                    tasks,
                    setBlock,
                    secondX,
                    streetY + 6,
                    secondZ,
                    palette.roofStairs()
            );
            tasks.add(() -> VillageStyle.setStair(
                    world,
                    firstX,
                    streetY + 6,
                    firstZ,
                    palette.roofStairs(),
                    firstSlope,
                    Stairs.Half.BOTTOM,
                    Stairs.Shape.STRAIGHT
            ));
            tasks.add(() -> VillageStyle.setStair(
                    world,
                    secondX,
                    streetY + 6,
                    secondZ,
                    palette.roofStairs(),
                    secondSlope,
                    Stairs.Half.BOTTOM,
                    Stairs.Shape.STRAIGHT
            ));

            int ridgeX = passage.centerX() + alongX * along;
            int ridgeZ = passage.centerZ() + alongZ * along;
            place(
                    tasks,
                    setBlock,
                    ridgeX,
                    streetY + 7,
                    ridgeZ,
                    palette.roofSlab()
            );
        }

        // Éclairage déporté : le centre conserve trois blocs de hauteur libre.
        int lanternX = passage.centerX()
                + alongX
                + perpendicularX * Math.max(1, offset - 1);
        int lanternZ = passage.centerZ()
                + alongZ
                + perpendicularZ * Math.max(1, offset - 1);
        place(
                tasks,
                setBlock,
                lanternX,
                streetY + 3,
                lanternZ,
                Material.CHAIN
        );
        place(
                tasks,
                setBlock,
                lanternX,
                streetY + 2,
                lanternZ,
                Material.LANTERN
        );

        /*
         * Nettoyage final du gabarit central. Il est volontairement programmé
         * après les détails pour qu'aucune chaîne ou décoration ne bouche la
         * voie principale.
         */
        for (int along = -1; along <= 1; along++) {
            for (int dy = 1; dy <= 3; dy++) {
                int x = passage.centerX() + alongX * along;
                int z = passage.centerZ() + alongZ * along;
                place(
                        tasks,
                        setBlock,
                        x,
                        streetY + dy,
                        z,
                        Material.AIR
                );
            }
        }

        return tasks;
    }

    private static PassagePalette palette(PassageStyle style) {
        if (style == PassageStyle.STONE) {
            return new PassagePalette(
                    Material.STONE_BRICKS,
                    Material.MOSSY_STONE_BRICKS,
                    Material.STRIPPED_DARK_OAK_LOG,
                    Material.DARK_OAK_PLANKS,
                    Material.DEEPSLATE_BRICK_STAIRS,
                    Material.DEEPSLATE_BRICK_SLAB
            );
        }
        return new PassagePalette(
                Material.COBBLESTONE,
                Material.WHITE_TERRACOTTA,
                Material.STRIPPED_SPRUCE_LOG,
                Material.SPRUCE_PLANKS,
                Material.DARK_OAK_STAIRS,
                Material.DARK_OAK_SLAB
        );
    }

    private static void place(List<Runnable> tasks,
                              TerrainManager.SetBlock setBlock,
                              int x,
                              int y,
                              int z,
                              Material material) {
        tasks.add(() -> setBlock.set(x, y, z, material));
    }

    private record PassagePalette(
            Material plinth,
            Material wall,
            Material pillar,
            Material floor,
            Material roofStairs,
            Material roofSlab
    ) {
        Material beam() {
            return pillar;
        }
    }
}
