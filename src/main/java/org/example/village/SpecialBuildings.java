package org.example.village;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.example.village.VillageLayoutPlan.LandmarkType;
import static org.example.village.VillageLayoutPlan.LotPlan;

/**
 * Bâtiments spéciaux du village.
 *
 * Ces bâtiments servent de repères visuels forts pour éviter l'effet
 * "copier/coller". Chacun reçoit une silhouette plus marquée et un
 * aménagement interne simple mais crédible.
 */
public final class SpecialBuildings {

    private SpecialBuildings() {}

    public static List<Runnable> buildChurch(World world, LotPlan lot, int baseY, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        int minX = lot.buildX();
        int minZ = lot.buildZ();
        int maxX = minX + lot.footprintWidth() - 1;
        int maxZ = minZ + lot.footprintDepth() - 1;
        int centerX = (minX + maxX) / 2;
        int centerZ = (minZ + maxZ) / 2;
        int towerX = minX + 1;
        int towerZ = maxZ - 5;
        int naveTop = baseY + 8;

        // Fondation et sol intérieur.
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                place(tasks, sb, x, baseY - 1, z, (x + z) % 3 == 0 ? Material.STONE_BRICKS : Material.MOSSY_STONE_BRICKS);
                boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
                boolean aisle = Math.abs(x - centerX) <= 1;
                place(tasks, sb, x, baseY, z, edge ? Material.POLISHED_ANDESITE : aisle ? Material.SMOOTH_STONE : Material.STONE_BRICKS);
            }
        }

        // Murs de la nef.
        for (int y = baseY + 1; y <= naveTop; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean shell = x == minX || x == maxX || z == minZ || z == maxZ;
                    if (!shell) {
                        continue;
                    }
                    /*
                     * Ici, je limite la baie à la largeur réelle de la porte.
                     * Les deux cellules latérales restent ainsi maçonnées et
                     * empêchent de contourner le battant lorsqu'il est fermé.
                     */
                    boolean frontDoor = z == maxZ && x == centerX && y <= baseY + 2;
                    boolean stained = (x == minX || x == maxX)
                            && y >= baseY + 2 && y <= baseY + 5
                            && ((z == minZ + 4) || (z == maxZ - 4) || (z == centerZ));
                    if (frontDoor) {
                        continue;
                    }
                    place(tasks, sb, x, y, z, stained ? Material.BLUE_STAINED_GLASS : Material.STONE_BRICKS);
                }
            }
        }

        // Contreforts latéraux.
        for (int z = minZ + 3; z <= maxZ - 3; z += 3) {
            for (int y = baseY + 1; y <= baseY + 5; y++) {
                place(tasks, sb, minX - 1, y, z, Material.STONE_BRICK_WALL);
                place(tasks, sb, maxX + 1, y, z, Material.STONE_BRICK_WALL);
            }
        }

        // Clocher.
        for (int y = baseY + 1; y <= baseY + 12; y++) {
            for (int x = towerX; x <= towerX + 4; x++) {
                for (int z = towerZ; z <= towerZ + 4; z++) {
                    boolean shell = x == towerX || x == towerX + 4 || z == towerZ || z == towerZ + 4;
                    if (!shell) {
                        continue;
                    }
                    boolean opening = y >= baseY + 8 && y <= baseY + 10 && (x == towerX + 2 || z == towerZ + 2);
                    if (!opening) {
                        place(tasks, sb, x, y, z, y > baseY + 9 ? Material.COBBLESTONE : Material.STONE_BRICKS);
                    }
                }
            }
        }

        VillageRoofBuilder.buildGable(
                tasks,
                world,
                sb,
                minX,
                maxX,
                minZ,
                maxZ,
                naveTop + 1,
                BlockFace.SOUTH,
                Material.DARK_OAK_PLANKS,
                Material.DARK_OAK_STAIRS,
                Material.DARK_OAK_SLAB,
                Material.STONE_BRICKS,
                Material.STRIPPED_DARK_OAK_LOG,
                Material.BLUE_STAINED_GLASS
        );
        buildTowerRoof(tasks, world, sb, towerX + 2, towerZ + 2, baseY + 13);

        // Porte principale.
        place(tasks, sb, centerX, baseY + 1, maxZ, Material.SPRUCE_DOOR);
        place(tasks, sb, centerX, baseY + 2, maxZ, Material.SPRUCE_DOOR);
        tasks.add(() -> VillageStyle.setDoor(world, centerX, baseY + 1, maxZ, Material.SPRUCE_DOOR, BlockFace.SOUTH, Bisected.Half.BOTTOM));
        tasks.add(() -> VillageStyle.setDoor(world, centerX, baseY + 2, maxZ, Material.SPRUCE_DOOR, BlockFace.SOUTH, Bisected.Half.TOP));

        // Escaliers d'entrée.
        stair(tasks, world, sb, centerX, baseY, maxZ + 1, Material.STONE_BRICK_STAIRS, BlockFace.SOUTH);
        stair(tasks, world, sb, centerX - 1, baseY, maxZ + 1, Material.STONE_BRICK_STAIRS, BlockFace.SOUTH);
        stair(tasks, world, sb, centerX + 1, baseY, maxZ + 1, Material.STONE_BRICK_STAIRS, BlockFace.SOUTH);

        // Bancs intérieurs.
        for (int z = minZ + 4; z <= maxZ - 4; z += 2) {
            stair(tasks, world, sb, centerX - 3, baseY + 1, z, Material.SPRUCE_STAIRS, BlockFace.EAST);
            stair(tasks, world, sb, centerX - 2, baseY + 1, z, Material.SPRUCE_STAIRS, BlockFace.EAST);
            stair(tasks, world, sb, centerX + 2, baseY + 1, z, Material.SPRUCE_STAIRS, BlockFace.WEST);
            stair(tasks, world, sb, centerX + 3, baseY + 1, z, Material.SPRUCE_STAIRS, BlockFace.WEST);
        }

        // Autel / chœur.
        place(tasks, sb, centerX, baseY + 1, minZ + 2, Material.QUARTZ_BLOCK);
        place(tasks, sb, centerX - 1, baseY + 1, minZ + 2, Material.QUARTZ_BLOCK);
        place(tasks, sb, centerX + 1, baseY + 1, minZ + 2, Material.QUARTZ_BLOCK);
        place(tasks, sb, centerX, baseY + 2, minZ + 2, Material.CANDLE);
        place(tasks, sb, centerX - 1, baseY + 2, minZ + 2, Material.CANDLE);
        place(tasks, sb, centerX + 1, baseY + 2, minZ + 2, Material.CANDLE);
        place(tasks, sb, centerX, baseY + 3, minZ + 1, Material.GOLD_BLOCK);
        place(tasks, sb, centerX, baseY + 4, minZ + 1, Material.LANTERN);
        place(tasks, sb, centerX, baseY + 5, minZ + 1, Material.CHAIN);

        // Lustre au centre de la nef.
        place(tasks, sb, centerX, baseY + 6, centerZ, Material.CHAIN);
        place(tasks, sb, centerX, baseY + 5, centerZ, Material.LANTERN);
        place(tasks, sb, centerX - 1, baseY + 5, centerZ, Material.LANTERN);
        place(tasks, sb, centerX + 1, baseY + 5, centerZ, Material.LANTERN);

        return tasks;
    }

    public static List<Runnable> buildForge(World world, LotPlan lot, int baseY, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        int minX = lot.buildX();
        int minZ = lot.buildZ();
        int maxX = minX + lot.footprintWidth() - 1;
        int maxZ = minZ + lot.footprintDepth() - 1;
        int centerX = (minX + maxX) / 2;
        int centerZ = (minZ + maxZ) / 2;

        // Sol et fondations.
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                place(tasks, sb, x, baseY - 1, z, Material.COBBLESTONE);
                boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
                place(tasks, sb, x, baseY, z, edge ? Material.STONE_BRICKS : ((x + z) % 2 == 0 ? Material.COBBLED_DEEPSLATE : Material.GRAVEL));
            }
        }

        // Murs avec large ouverture frontale.
        for (int y = baseY + 1; y <= baseY + 5; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean shell = x == minX || x == maxX || z == minZ || z == maxZ;
                    boolean frontOpen = x == minX && Math.abs(z - centerZ) <= 1 && y <= baseY + 3;
                    boolean sideOpen = z == maxZ && x >= centerX - 2 && x <= centerX + 1 && y <= baseY + 3;
                    if (shell && !frontOpen && !sideOpen) {
                        place(tasks, sb, x, y, z, y == baseY + 5 ? Material.STRIPPED_SPRUCE_LOG : Material.STONE_BRICKS);
                    }
                }
            }
        }

        // Couverture fermée : l'ancienne forge ne possédait que deux lignes
        // d'escaliers et restait ouverte au ciel sur presque toute sa largeur.
        VillageRoofBuilder.buildGable(
                tasks,
                world,
                sb,
                minX,
                maxX,
                minZ,
                maxZ,
                baseY + 6,
                lot.facing(),
                Material.SPRUCE_PLANKS,
                Material.SPRUCE_STAIRS,
                Material.SPRUCE_SLAB,
                Material.STONE_BRICKS,
                Material.STRIPPED_SPRUCE_LOG,
                Material.IRON_BARS
        );

        // Appentis latéral / atelier extérieur.
        buildLeanTo(tasks, world, sb, maxX - 4, maxZ, baseY + 4, BlockFace.SOUTH);
        place(tasks, sb, maxX - 2, baseY + 1, maxZ - 1, Material.ANVIL);
        place(tasks, sb, maxX - 3, baseY + 1, maxZ - 1, Material.GRINDSTONE);
        place(tasks, sb, maxX - 4, baseY + 1, maxZ - 1, Material.BARREL);

        // Poste de travail intérieur.
        place(tasks, sb, centerX, baseY + 1, minZ + 2, Material.BLAST_FURNACE);
        place(tasks, sb, centerX + 1, baseY + 1, minZ + 2, Material.SMITHING_TABLE);
        place(tasks, sb, centerX - 1, baseY + 1, minZ + 2, Material.ANVIL);
        place(tasks, sb, minX + 2, baseY + 1, maxZ - 2, Material.BARREL);
        place(tasks, sb, minX + 3, baseY + 1, maxZ - 2, Material.CHEST);
        place(tasks, sb, minX + 4, baseY + 1, maxZ - 2, Material.LAVA_CAULDRON);

        // Cheminée plus marquée.
        for (int y = baseY + 1; y <= baseY + 8; y++) {
            place(tasks, sb, maxX - 1, y, minZ + 1, y % 2 == 0 ? Material.COBBLESTONE : Material.BRICKS);
        }
        place(tasks, sb, maxX - 1, baseY + 9, minZ + 1, Material.CAMPFIRE);
        place(tasks, sb, maxX - 1, baseY + 10, minZ + 1, Material.IRON_BARS);

        // Cour de forge devant.
        for (int x = minX - 3; x <= minX - 1; x++) {
            for (int z = centerZ - 2; z <= centerZ + 2; z++) {
                place(tasks, sb, x, baseY, z, (x + z) % 2 == 0 ? Material.GRAVEL : Material.COARSE_DIRT);
            }
        }
        place(tasks, sb, minX - 2, baseY + 1, centerZ - 1, Material.ANVIL);
        place(tasks, sb, minX - 3, baseY + 1, centerZ, Material.BARREL);
        place(tasks, sb, minX - 1, baseY + 1, centerZ + 1, Material.HAY_BLOCK);
        return tasks;
    }

    public static List<Runnable> buildMarketStall(World world, LotPlan lot, int baseY, TerrainManager.SetBlock sb, Random rng) {
        List<Runnable> tasks = new ArrayList<>();
        int minX = lot.buildX();
        int minZ = lot.buildZ();
        int maxX = minX + lot.footprintWidth() - 1;
        int maxZ = minZ + lot.footprintDepth() - 1;
        Random random = rng != null ? rng : new Random();

        Material[] stripe = random.nextBoolean()
                ? new Material[]{Material.RED_WOOL, Material.WHITE_WOOL}
                : new Material[]{Material.GREEN_WOOL, Material.WHITE_WOOL};

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                place(tasks, sb, x, baseY - 1, z, Material.COBBLESTONE);
                place(tasks, sb, x, baseY, z, (x + z) % 2 == 0 ? Material.SPRUCE_PLANKS : Material.OAK_PLANKS);
            }
        }

        // Poteaux.
        for (int x : new int[]{minX, maxX}) {
            for (int z : new int[]{minZ, maxZ}) {
                for (int y = baseY + 1; y <= baseY + 3; y++) {
                    place(tasks, sb, x, y, z, Material.SPRUCE_LOG);
                }
            }
        }

        // Toile rayée en pavillon, plus lisible qu'un plafond parfaitement
        // plat. Chaque rangée forme une pente complète jusqu'au faîtage.
        int canopyLayers = Math.max(2, (maxZ - minZ + 3) / 2);
        for (int layer = 0; layer < canopyLayers; layer++) {
            int northZ = minZ - 1 + layer;
            int southZ = maxZ + 1 - layer;
            int roofY = baseY + 4 + layer;
            for (int x = minX - 1; x <= maxX + 1; x++) {
                Material cloth = stripe[Math.floorMod(x - minX + layer, stripe.length)];
                place(tasks, sb, x, roofY, northZ, cloth);
                if (southZ != northZ) {
                    place(tasks, sb, x, roofY, southZ, cloth);
                }
            }
        }
        /*
         * Le faîtage reste au niveau de la dernière rangée de toile. Placé un
         * bloc plus haut, il flottait au-dessus de l'étal.
         */
        for (int x = minX - 1; x <= maxX + 1; x++) {
            slab(tasks, world, sb, x,
                    baseY + 4 + canopyLayers - 1,
                    (minZ + maxZ) / 2,
                    Material.SPRUCE_SLAB,
                    Slab.Type.TOP);
        }

        // Comptoir et marchandises.
        place(tasks, sb, minX + 1, baseY + 1, maxZ - 1, Material.BARREL);
        place(tasks, sb, minX + 2, baseY + 1, maxZ - 1, Material.CHEST);
        place(tasks, sb, maxX - 1, baseY + 1, maxZ - 1, random.nextBoolean() ? Material.PUMPKIN : Material.MELON);
        place(tasks, sb, maxX - 2, baseY + 1, maxZ - 1, Material.HAY_BLOCK);
        place(tasks, sb, (minX + maxX) / 2, baseY + 1, minZ + 1, random.nextBoolean() ? Material.CAKE : Material.BARREL);
        place(tasks, sb, (minX + maxX) / 2, baseY + 2, minZ + 1, Material.LANTERN);
        place(tasks, sb, (minX + maxX) / 2, baseY + 3, minZ + 1, Material.CHAIN);
        return tasks;
    }

    /**
     * Donne à la grande maison son identité d'auberge sans dupliquer le
     * générateur de maison : enseigne, marquise et petite terrasse latérale.
     */
    public static List<Runnable> decorateInn(World world,
                                             LotPlan lot,
                                             int baseY,
                                             TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        int frontRadius = frontRadius(lot);
        int sideRadius = sideRadius(lot);
        BlockFace front = lot.facing();

        LocalPoint sign = localPoint(lot, -3, frontRadius + 1);
        place(tasks, sb, sign.x(), baseY + 3, sign.z(), Material.OAK_WALL_SIGN);
        tasks.add(() -> VillageStyle.setDirectional(
                world, sign.x(), baseY + 3, sign.z(),
                Material.OAK_WALL_SIGN, front));

        LocalPoint banner = localPoint(lot, 3, frontRadius + 1);
        place(tasks, sb, banner.x(), baseY + 3, banner.z(), Material.RED_WALL_BANNER);
        tasks.add(() -> VillageStyle.setDirectional(
                world, banner.x(), baseY + 3, banner.z(),
                Material.RED_WALL_BANNER, front));

        // Marquise continue, assez haute pour ne pas couper l'entrée.
        for (int lateral = -2; lateral <= 2; lateral++) {
            for (int forward = frontRadius + 1; forward <= frontRadius + 2; forward++) {
                LocalPoint point = localPoint(lot, lateral, forward);
                slab(tasks, world, sb, point.x(), baseY + 4, point.z(),
                        Material.DARK_OAK_SLAB, Slab.Type.TOP);
            }
        }
        LocalPoint lantern = localPoint(lot, 0, frontRadius + 2);
        place(tasks, sb, lantern.x(), baseY + 3, lantern.z(), Material.LANTERN);

        // Terrasse de trois blocs sur le côté le plus éloigné des autres
        // volumes. Le lot possède déjà une réserve de jardin suffisante.
        int terraceSide = lot.wingSide() == VillageStyle.rightOf(front) ? -1 : 1;
        int terraceLateral = terraceSide * (sideRadius + 2);
        for (int lateral = terraceLateral - 1; lateral <= terraceLateral + 1; lateral++) {
            for (int forward = -1; forward <= 1; forward++) {
                LocalPoint point = localPoint(lot, lateral, forward);
                place(tasks, sb, point.x(), baseY, point.z(),
                        Math.floorMod(lateral + forward, 2) == 0
                                ? Material.GRAVEL
                                : Material.PACKED_MUD);
            }
        }

        LocalPoint table = localPoint(lot, terraceLateral, 0);
        place(tasks, sb, table.x(), baseY + 1, table.z(), Material.SPRUCE_FENCE);
        place(tasks, sb, table.x(), baseY + 2, table.z(), Material.SPRUCE_PRESSURE_PLATE);
        for (int forward : new int[]{-1, 1}) {
            LocalPoint chair = localPoint(lot, terraceLateral, forward);
            stair(tasks, world, sb, chair.x(), baseY + 1, chair.z(),
                    Material.SPRUCE_STAIRS,
                    forward < 0 ? front : front.getOppositeFace());
        }
        LocalPoint barrel = localPoint(lot, terraceLateral + terraceSide, 1);
        place(tasks, sb, barrel.x(), baseY + 1, barrel.z(), Material.BARREL);

        return tasks;
    }

    /**
     * Identité de boulangerie : auvent clair, étal de pain et four de briques
     * latéral. Les détails restent dans la réserve de cour du lot.
     */
    public static List<Runnable> decorateBakery(World world,
                                                LotPlan lot,
                                                int baseY,
                                                TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        int frontRadius = frontRadius(lot);
        int sideRadius = sideRadius(lot);
        BlockFace front = lot.facing();

        LocalPoint sign = localPoint(lot, -2, frontRadius + 1);
        place(tasks, sb, sign.x(), baseY + 3, sign.z(), Material.OAK_WALL_SIGN);
        tasks.add(() -> VillageStyle.setDirectional(
                world, sign.x(), baseY + 3, sign.z(),
                Material.OAK_WALL_SIGN, front));

        // Auvent rayé au-dessus de la fenêtre de vente.
        for (int lateral = -1; lateral <= 2; lateral++) {
            LocalPoint point = localPoint(lot, lateral, frontRadius + 1);
            Material cloth = Math.floorMod(lateral, 2) == 0
                    ? Material.YELLOW_WOOL
                    : Material.WHITE_WOOL;
            place(tasks, sb, point.x(), baseY + 4, point.z(), cloth);
        }

        LocalPoint counter = localPoint(lot, 2, frontRadius + 1);
        place(tasks, sb, counter.x(), baseY + 1, counter.z(), Material.BARREL);
        place(tasks, sb, counter.x(), baseY + 2, counter.z(), Material.CAKE);
        LocalPoint sacks = localPoint(lot, -2, frontRadius + 1);
        place(tasks, sb, sacks.x(), baseY + 1, sacks.z(), Material.HAY_BLOCK);

        // Four extérieur compact du côté opposé à l'aile éventuelle.
        int ovenSide = lot.wingSide() == VillageStyle.rightOf(front) ? -1 : 1;
        int ovenLateral = ovenSide * (sideRadius + 2);
        for (int lateral = ovenLateral - 1; lateral <= ovenLateral + 1; lateral++) {
            for (int forward = -2; forward <= 0; forward++) {
                LocalPoint point = localPoint(lot, lateral, forward);
                boolean edge = lateral == ovenLateral - 1
                        || lateral == ovenLateral + 1
                        || forward == -2;
                place(tasks, sb, point.x(), baseY + 1, point.z(),
                        edge ? Material.BRICKS : Material.SMOOTH_STONE);
                if (edge) {
                    place(tasks, sb, point.x(), baseY + 2, point.z(), Material.BRICKS);
                }
            }
        }
        LocalPoint firebox = localPoint(lot, ovenLateral, -1);
        place(tasks, sb, firebox.x(), baseY + 1, firebox.z(), Material.SMOKER);
        tasks.add(() -> VillageStyle.setDirectional(
                world, firebox.x(), baseY + 1, firebox.z(),
                Material.SMOKER, front));
        for (int dy = 3; dy <= 5; dy++) {
            place(tasks, sb, firebox.x(), baseY + dy, firebox.z(), Material.BRICKS);
        }
        place(tasks, sb, firebox.x(), baseY + 6, firebox.z(), Material.CAMPFIRE);

        return tasks;
    }


    /**
     * Renforce la silhouette de la mairie : façade civique, balcon, cloche,
     * panneau d'affichage et petite salle du conseil. La structure principale
     * reste construite par {@link HouseBuilder}, ce qui conserve la même
     * grammaire architecturale que les maisons voisines.
     */
    public static List<Runnable> decorateTownHall(World world,
                                                  LotPlan lot,
                                                  int baseY,
                                                  TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        BlockFace front = lot.facing();
        int frontRadius = frontRadius(lot);
        int sideRadius = sideRadius(lot);

        // Perron municipal et tapis d'entrée.
        for (int forward = frontRadius + 1; forward <= frontRadius + 3; forward++) {
            for (int lateral = -2; lateral <= 2; lateral++) {
                LocalPoint point = localPoint(lot, lateral, forward);
                Material paving = Math.floorMod(lateral + forward, 3) == 0
                        ? Material.POLISHED_ANDESITE
                        : Material.STONE_BRICKS;
                place(tasks, sb, point.x(), baseY, point.z(), paving);
            }
        }
        for (int lateral = -1; lateral <= 1; lateral++) {
            LocalPoint step = localPoint(lot, lateral, frontRadius + 1);
            stair(tasks, world, sb, step.x(), baseY + 1, step.z(),
                    Material.STONE_BRICK_STAIRS, front);
        }

        // Balcon central : les deux poutres latérales le relient visuellement
        // à la façade et évitent l'effet de dalle flottante.
        for (int lateral = -3; lateral <= 3; lateral++) {
            LocalPoint balcony = localPoint(lot, lateral, frontRadius + 1);
            slab(tasks, world, sb, balcony.x(), baseY + 5, balcony.z(),
                    Material.DARK_OAK_SLAB, Slab.Type.TOP);
            if (Math.abs(lateral) == 3) {
                for (int y = baseY + 1; y <= baseY + 4; y++) {
                    place(tasks, sb, balcony.x(), y, balcony.z(),
                            Material.STRIPPED_DARK_OAK_LOG);
                }
            }
            if (lateral != 0) {
                place(tasks, sb, balcony.x(), baseY + 6, balcony.z(),
                        Material.DARK_OAK_FENCE);
            }
        }

        LocalPoint bell = localPoint(lot, 0, frontRadius + 1);
        place(tasks, sb, bell.x(), baseY + 7, bell.z(), Material.CHAIN);
        place(tasks, sb, bell.x(), baseY + 6, bell.z(), Material.BELL);
        tasks.add(() -> VillageStyle.setDirectional(
                world, bell.x(), baseY + 6, bell.z(),
                Material.BELL, front));

        // Bannières municipales symétriques.
        for (int lateral : new int[]{-4, 4}) {
            LocalPoint banner = localPoint(lot, lateral, frontRadius + 1);
            place(tasks, sb, banner.x(), baseY + 4, banner.z(),
                    Material.BLUE_WALL_BANNER);
            tasks.add(() -> VillageStyle.setDirectional(
                    world, banner.x(), baseY + 4, banner.z(),
                    Material.BLUE_WALL_BANNER, front));
        }

        // Panneau d'affichage public protégé par un petit auvent.
        int boardSide = lot.wingSide() == VillageStyle.rightOf(front) ? -1 : 1;
        int boardLateral = boardSide * (sideRadius + 2);
        for (int forward = -1; forward <= 1; forward++) {
            LocalPoint board = localPoint(lot, boardLateral, forward);
            place(tasks, sb, board.x(), baseY + 1, board.z(),
                    Material.STRIPPED_SPRUCE_LOG);
            place(tasks, sb, board.x(), baseY + 2, board.z(),
                    Material.OAK_WALL_SIGN);
            tasks.add(() -> VillageStyle.setDirectional(
                    world, board.x(), baseY + 2, board.z(),
                    Material.OAK_WALL_SIGN,
                    boardSide < 0
                            ? VillageStyle.leftOf(front)
                            : VillageStyle.rightOf(front)));
            slab(tasks, world, sb, board.x(), baseY + 3, board.z(),
                    Material.SPRUCE_SLAB, Slab.Type.TOP);
        }
        LocalPoint noticeLantern = localPoint(lot, boardLateral, 0);
        place(tasks, sb, noticeLantern.x(), baseY + 4,
                noticeLantern.z(), Material.LANTERN);

        // Mobilier intérieur de la salle du conseil.
        LocalPoint lectern = localPoint(lot, 0, -frontRadius + 2);
        place(tasks, sb, lectern.x(), baseY + 1, lectern.z(),
                Material.LECTERN);
        tasks.add(() -> VillageStyle.setDirectional(
                world, lectern.x(), baseY + 1, lectern.z(),
                Material.LECTERN, front));

        for (int lateral = -3; lateral <= 3; lateral++) {
            LocalPoint table = localPoint(lot, lateral, 0);
            place(tasks, sb, table.x(), baseY + 1, table.z(),
                    lateral == 0 ? Material.CARTOGRAPHY_TABLE : Material.SPRUCE_SLAB);
        }
        for (int lateral : new int[]{-3, 3}) {
            for (int forward : new int[]{-2, 2}) {
                LocalPoint chair = localPoint(lot, lateral, forward);
                stair(tasks, world, sb, chair.x(), baseY + 1, chair.z(),
                        Material.SPRUCE_STAIRS,
                        forward < 0 ? front : front.getOppositeFace());
            }
        }

        return tasks;
    }

    /**
     * Ajoute au logis du charpentier une cour de travail lisible : appentis,
     * établi, chevalets et réserves de bois. Les éléments restent dans le
     * jardin du lot afin de ne jamais empiéter sur la ruelle.
     */
    public static List<Runnable> decorateCarpenter(World world,
                                                   LotPlan lot,
                                                   int baseY,
                                                   TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        BlockFace front = lot.facing();
        int frontRadius = frontRadius(lot);
        int sideRadius = sideRadius(lot);
        int yardSide = lot.wingSide() == VillageStyle.rightOf(front) ? -1 : 1;
        int yardLateral = yardSide * (sideRadius + 2);

        // Cour tassée.
        for (int lateral = yardLateral - 2;
             lateral <= yardLateral + 2;
             lateral++) {
            for (int forward = -3; forward <= 3; forward++) {
                LocalPoint point = localPoint(lot, lateral, forward);
                Material ground = Math.floorMod(lateral * 7 + forward, 4) == 0
                        ? Material.GRAVEL
                        : Material.PACKED_MUD;
                place(tasks, sb, point.x(), baseY, point.z(), ground);
            }
        }

        // Appentis autonome orienté dans le repère local du bâtiment.
        BlockFace sideFace = yardSide < 0
                ? VillageStyle.leftOf(front)
                : VillageStyle.rightOf(front);
        for (int forward : new int[]{-3, 3}) {
            LocalPoint post = localPoint(lot, yardLateral + yardSide, forward);
            for (int y = baseY + 1; y <= baseY + 4; y++) {
                place(tasks, sb, post.x(), y, post.z(),
                        Material.STRIPPED_SPRUCE_LOG);
            }
        }
        for (int lateral = yardLateral - 2;
             lateral <= yardLateral + 2;
             lateral++) {
            for (int forward = -4; forward <= 4; forward++) {
                LocalPoint roof = localPoint(lot, lateral, forward);
                int rise = lateral == yardLateral - yardSide * 2 ? 1 : 0;
                place(tasks, sb, roof.x(), baseY + 5 + rise, roof.z(),
                        Material.SPRUCE_PLANKS);
                if (lateral == yardLateral + yardSide * 2) {
                    stair(tasks, world, sb, roof.x(), baseY + 5, roof.z(),
                            Material.SPRUCE_STAIRS, sideFace.getOppositeFace());
                }
            }
        }

        // Poste de débit et établis.
        LocalPoint saw = localPoint(lot, yardLateral, 0);
        place(tasks, sb, saw.x(), baseY + 1, saw.z(),
                Material.STONECUTTER);
        tasks.add(() -> VillageStyle.setDirectional(
                world, saw.x(), baseY + 1, saw.z(),
                Material.STONECUTTER, front));
        LocalPoint workbench = localPoint(lot, yardLateral, -2);
        place(tasks, sb, workbench.x(), baseY + 1, workbench.z(),
                Material.CRAFTING_TABLE);
        LocalPoint barrel = localPoint(lot, yardLateral, 2);
        place(tasks, sb, barrel.x(), baseY + 1, barrel.z(),
                Material.BARREL);

        // Deux chevalets matérialisent des pièces en cours d'assemblage.
        for (int forward : new int[]{-1, 1}) {
            for (int lateral = yardLateral - 1;
                 lateral <= yardLateral + 1;
                 lateral++) {
                LocalPoint beam = localPoint(lot, lateral, forward);
                place(tasks, sb, beam.x(), baseY + 2, beam.z(),
                        Material.OAK_SLAB);
            }
            LocalPoint leftSupport = localPoint(lot, yardLateral - 1, forward);
            LocalPoint rightSupport = localPoint(lot, yardLateral + 1, forward);
            place(tasks, sb, leftSupport.x(), baseY + 1,
                    leftSupport.z(), Material.OAK_FENCE);
            place(tasks, sb, rightSupport.x(), baseY + 1,
                    rightSupport.z(), Material.OAK_FENCE);
        }

        // Tas de grumes orientées selon la façade.
        for (int forward = -3; forward <= 3; forward += 2) {
            LocalPoint log = localPoint(lot, yardLateral + yardSide, forward);
            place(tasks, sb, log.x(), baseY + 1, log.z(),
                    Material.STRIPPED_OAK_LOG);
            tasks.add(() -> VillageStyle.setLogAxis(
                    world, log.x(), baseY + 1, log.z(),
                    Material.STRIPPED_OAK_LOG,
                    front == BlockFace.NORTH || front == BlockFace.SOUTH
                            ? org.bukkit.Axis.Z
                            : org.bukkit.Axis.X));
        }

        LocalPoint sign = localPoint(lot, -2, frontRadius + 1);
        place(tasks, sb, sign.x(), baseY + 3, sign.z(),
                Material.SPRUCE_WALL_SIGN);
        tasks.add(() -> VillageStyle.setDirectional(
                world, sign.x(), baseY + 3, sign.z(),
                Material.SPRUCE_WALL_SIGN, front));

        return tasks;
    }

    /**
     * Construit une écurie traversante en pierre et colombage. L'intérieur
     * conserve un couloir central de trois blocs, quatre stalles, du fourrage
     * et un point d'eau ; l'entrée est volontairement large pour les chevaux.
     */
    public static List<Runnable> buildStable(World world,
                                             LotPlan lot,
                                             int baseY,
                                             TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        BlockFace front = lot.facing();
        int frontRadius = frontRadius(lot);
        int sideRadius = sideRadius(lot);
        int minX = lot.buildX();
        int minZ = lot.buildZ();
        int maxX = minX + lot.footprintWidth() - 1;
        int maxZ = minZ + lot.footprintDepth() - 1;

        // Fondations et sol drainant.
        for (int lateral = -sideRadius; lateral <= sideRadius; lateral++) {
            for (int forward = -frontRadius; forward <= frontRadius; forward++) {
                LocalPoint point = localPoint(lot, lateral, forward);
                place(tasks, sb, point.x(), baseY - 1, point.z(),
                        Material.COBBLESTONE);
                boolean aisle = Math.abs(lateral) <= 1;
                Material floor = aisle
                        ? (Math.floorMod(forward, 3) == 0
                                ? Material.PACKED_MUD
                                : Material.GRAVEL)
                        : Material.SPRUCE_PLANKS;
                place(tasks, sb, point.x(), baseY, point.z(), floor);
            }
        }

        // Soubassement et ossature. L'entrée frontale reste ouverte sur trois
        // blocs de large et quatre de haut.
        for (int y = baseY + 1; y <= baseY + 4; y++) {
            for (int lateral = -sideRadius; lateral <= sideRadius; lateral++) {
                for (int forward : new int[]{-frontRadius, frontRadius}) {
                    boolean entrance = forward == frontRadius
                            && Math.abs(lateral) <= 1;
                    if (entrance) {
                        continue;
                    }
                    LocalPoint point = localPoint(lot, lateral, forward);
                    boolean post = Math.abs(lateral) == sideRadius
                            || Math.floorMod(lateral + sideRadius, 4) == 0;
                    Material wall = post
                            ? Material.STRIPPED_DARK_OAK_LOG
                            : (y == baseY + 1
                                    ? Material.COBBLESTONE
                                    : Material.WHITE_TERRACOTTA);
                    place(tasks, sb, point.x(), y, point.z(), wall);
                }
            }
            for (int forward = -frontRadius + 1;
                 forward < frontRadius;
                 forward++) {
                for (int lateral : new int[]{-sideRadius, sideRadius}) {
                    LocalPoint point = localPoint(lot, lateral, forward);
                    boolean post = Math.floorMod(
                            forward + frontRadius,
                            4
                    ) == 0;
                    Material wall = post
                            ? Material.STRIPPED_DARK_OAK_LOG
                            : (y == baseY + 1
                                    ? Material.COBBLESTONE
                                    : Material.SPRUCE_PLANKS);
                    place(tasks, sb, point.x(), y, point.z(), wall);
                }
            }
        }

        // Linteau massif au-dessus du portail.
        for (int lateral = -2; lateral <= 2; lateral++) {
            LocalPoint lintel = localPoint(lot, lateral, frontRadius);
            place(tasks, sb, lintel.x(), baseY + 5, lintel.z(),
                    Material.STRIPPED_DARK_OAK_LOG);
        }

        // Quatre stalles latérales et leur porte basse.
        int stallDepth = Math.max(3, frontRadius - 2);
        for (int side : new int[]{-1, 1}) {
            int dividerLateral = side * 2;
            for (int forward = -stallDepth; forward <= stallDepth; forward++) {
                LocalPoint divider = localPoint(lot, dividerLateral, forward);
                place(tasks, sb, divider.x(), baseY + 1, divider.z(),
                        Material.DARK_OAK_FENCE);
            }
            for (int forward : new int[]{-2, 3}) {
                for (int lateral = 2;
                     lateral < sideRadius;
                     lateral++) {
                    LocalPoint divider = localPoint(
                            lot,
                            side * lateral,
                            forward
                    );
                    place(tasks, sb, divider.x(), baseY + 1, divider.z(),
                            Material.DARK_OAK_FENCE);
                }
            }

            for (int forward : new int[]{-4, 1}) {
                LocalPoint gate = localPoint(lot, dividerLateral, forward);
                place(tasks, sb, gate.x(), baseY + 1, gate.z(),
                        Material.DARK_OAK_FENCE_GATE);
                tasks.add(() -> VillageStyle.setDirectional(
                        world, gate.x(), baseY + 1, gate.z(),
                        Material.DARK_OAK_FENCE_GATE, front));
            }
        }

        // Fourrage, sellerie et eau.
        for (int side : new int[]{-1, 1}) {
            LocalPoint hay = localPoint(
                    lot,
                    side * (sideRadius - 1),
                    -frontRadius + 2
            );
            place(tasks, sb, hay.x(), baseY + 1, hay.z(),
                    Material.HAY_BLOCK);
            place(tasks, sb, hay.x(), baseY + 2, hay.z(),
                    Material.HAY_BLOCK);
        }
        LocalPoint water = localPoint(lot, sideRadius - 1, frontRadius - 2);
        place(tasks, sb, water.x(), baseY + 1, water.z(),
                Material.WATER_CAULDRON);
        LocalPoint tack = localPoint(lot, -sideRadius + 1, frontRadius - 2);
        place(tasks, sb, tack.x(), baseY + 1, tack.z(),
                Material.BARREL);
        place(tasks, sb, tack.x(), baseY + 2, tack.z(),
                Material.TRIPWIRE_HOOK);
        tasks.add(() -> VillageStyle.setDirectional(
                world, tack.x(), baseY + 2, tack.z(),
                Material.TRIPWIRE_HOOK,
                VillageStyle.rightOf(front)));

        VillageRoofBuilder.buildGable(
                tasks,
                world,
                sb,
                minX,
                maxX,
                minZ,
                maxZ,
                baseY + 6,
                front,
                Material.DARK_OAK_PLANKS,
                Material.DARK_OAK_STAIRS,
                Material.DARK_OAK_SLAB,
                Material.SPRUCE_PLANKS,
                Material.STRIPPED_DARK_OAK_LOG,
                Material.DARK_OAK_FENCE
        );

        // Enseigne et lanterne à l'extérieur du portail.
        LocalPoint sign = localPoint(lot, -3, frontRadius + 1);
        place(tasks, sb, sign.x(), baseY + 3, sign.z(),
                Material.DARK_OAK_WALL_SIGN);
        tasks.add(() -> VillageStyle.setDirectional(
                world, sign.x(), baseY + 3, sign.z(),
                Material.DARK_OAK_WALL_SIGN, front));
        LocalPoint lantern = localPoint(lot, 3, frontRadius + 1);
        place(tasks, sb, lantern.x(), baseY + 3, lantern.z(),
                Material.LANTERN);

        return tasks;
    }

    public static List<Runnable> buildGreenLot(LotPlan lot, int baseY, TerrainManager.SetBlock sb, LandmarkType type) {
        List<Runnable> tasks = new ArrayList<>();
        for (int x = lot.minX(); x <= lot.maxX(); x++) {
            for (int z = lot.minZ(); z <= lot.maxZ(); z++) {
                boolean edge = x == lot.minX() || x == lot.maxX() || z == lot.minZ() || z == lot.maxZ();
                boolean cross = x == lot.centerX() || z == lot.centerZ();
                place(tasks, sb, x, baseY, z, edge ? Material.MOSS_BLOCK : cross ? Material.PACKED_MUD : Material.GRASS_BLOCK);
            }
        }
        tasks.addAll(buildLandmark(type, lot.centerX(), baseY + 1, lot.centerZ(), sb));
        place(tasks, sb, lot.minX(), baseY + 1, lot.minZ(), Material.LANTERN);
        place(tasks, sb, lot.maxX(), baseY + 1, lot.maxZ(), Material.LANTERN);
        place(tasks, sb, lot.minX(), baseY + 1, lot.maxZ(), Material.LANTERN);
        place(tasks, sb, lot.maxX(), baseY + 1, lot.minZ(), Material.LANTERN);
        return tasks;
    }

    public static List<Runnable> buildServiceYard(World world, LotPlan lot, int baseY, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        for (int x = lot.minX(); x <= lot.maxX(); x++) {
            for (int z = lot.minZ(); z <= lot.maxZ(); z++) {
                boolean stripe = (x + z) % 3 == 0;
                place(tasks, sb, x, baseY, z, stripe ? Material.GRAVEL : Material.COARSE_DIRT);
            }
        }

        // Coin stockage.
        place(tasks, sb, lot.minX() + 1, baseY + 1, lot.minZ() + 1, Material.BARREL);
        place(tasks, sb, lot.minX() + 2, baseY + 1, lot.minZ() + 1, Material.CHEST);
        place(tasks, sb, lot.minX() + 1, baseY + 1, lot.minZ() + 2, Material.HAY_BLOCK);

        // Petit chariot/atelier.
        int wx = lot.centerX();
        int wz = lot.centerZ();
        place(tasks, sb, wx - 1, baseY + 1, wz, Material.BARREL);
        place(tasks, sb, wx + 1, baseY + 1, wz, Material.BARREL);
        place(tasks, sb, wx, baseY + 1, wz, Material.CHEST);
        stair(tasks, world, sb, wx - 1, baseY + 1, wz - 1, Material.OAK_STAIRS, BlockFace.SOUTH);
        stair(tasks, world, sb, wx + 1, baseY + 1, wz - 1, Material.OAK_STAIRS, BlockFace.SOUTH);
        place(tasks, sb, wx, baseY + 2, wz, Material.SPRUCE_TRAPDOOR);

        // Atelier ouvert.
        place(tasks, sb, lot.maxX() - 1, baseY + 1, lot.maxZ() - 1, Material.CRAFTING_TABLE);
        place(tasks, sb, lot.maxX() - 2, baseY + 1, lot.maxZ() - 1, Material.SMITHING_TABLE);
        place(tasks, sb, lot.maxX() - 3, baseY + 1, lot.maxZ() - 1, Material.GRINDSTONE);
        place(tasks, sb, lot.centerX(), baseY + 1, lot.maxZ() - 2, Material.CAMPFIRE);
        return tasks;
    }

    public static List<Runnable> buildDecorLot(int baseX, int baseY, int baseZ,
                                               TerrainManager.SetBlock sb,
                                               Random rng) {
        Random random = rng != null ? rng : new Random();
        LandmarkType type = LandmarkType.values()[random.nextInt(LandmarkType.values().length)];
        List<Runnable> tasks = new ArrayList<>(buildLandmark(type, baseX + 3, baseY, baseZ + 3, sb));
        if (random.nextBoolean()) {
            place(tasks, sb, baseX + 1, baseY, baseZ + 1, Material.GRAVEL);
            place(tasks, sb, baseX + 1, baseY + 1, baseZ + 1, Material.BARREL);
            place(tasks, sb, baseX + 2, baseY + 1, baseZ + 1, Material.CHEST);
        }
        return tasks;
    }

    public static List<Runnable> buildLandmark(LandmarkType type, int x, int y, int z,
                                               TerrainManager.SetBlock sb) {
        return switch (type) {
            case STATUE -> statue(x, y, z, sb);
            case GARDEN -> garden(x, y, z, sb);
            case CHERRY -> cherry(x, y, z, sb);
        };
    }

    private static List<Runnable> statue(int x, int y, int z, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                place(tasks, sb, x + dx, y, z + dz, Material.STONE_BRICKS);
            }
        }
        place(tasks, sb, x, y + 1, z, Material.STONE_BRICK_WALL);
        place(tasks, sb, x, y + 2, z, Material.STONE_BRICK_WALL);
        place(tasks, sb, x, y + 3, z, Material.CHISELED_STONE_BRICKS);
        place(tasks, sb, x, y + 4, z, Material.LANTERN);
        place(tasks, sb, x - 1, y + 1, z, Material.STONE_BRICK_WALL);
        place(tasks, sb, x + 1, y + 1, z, Material.STONE_BRICK_WALL);
        return tasks;
    }

    private static List<Runnable> garden(int x, int y, int z, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                boolean entrance = dz == 2 && dx == 0;
                place(tasks, sb, x + dx, y, z + dz,
                        edge ? Material.COARSE_DIRT : Material.MOSS_BLOCK);
                if (edge && !entrance) {
                    // La bordure se trouve au-dessus du sol ; l'ancienne
                    // version enterrait les murets dans le terrain.
                    place(tasks, sb, x + dx, y + 1, z + dz, Material.COBBLESTONE_WALL);
                }
            }
        }
        place(tasks, sb, x, y + 1, z, Material.FLOWERING_AZALEA);
        place(tasks, sb, x - 1, y + 1, z, Material.PEONY);
        place(tasks, sb, x + 1, y + 1, z, Material.LILAC);
        place(tasks, sb, x, y + 1, z - 1, Material.LANTERN);
        return tasks;
    }

    private static List<Runnable> cherry(int x, int y, int z, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                place(tasks, sb, x + dx, y, z + dz, Material.STONE_BRICKS);
            }
        }
        for (int dy = 1; dy <= 4; dy++) {
            place(tasks, sb, x, y + dy, z, Material.CHERRY_LOG);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= 3) {
                    place(tasks, sb, x + dx, y + 4, z + dz, Material.CHERRY_LEAVES);
                }
            }
        }
        place(tasks, sb, x, y + 5, z, Material.CHERRY_LEAVES);
        place(tasks, sb, x - 1, y + 1, z + 2, Material.PINK_PETALS);
        place(tasks, sb, x + 1, y + 1, z - 2, Material.PINK_PETALS);
        return tasks;
    }

    private static int frontRadius(LotPlan lot) {
        return (lot.facing() == BlockFace.NORTH || lot.facing() == BlockFace.SOUTH)
                ? (lot.footprintDepth() - 1) / 2
                : (lot.footprintWidth() - 1) / 2;
    }

    private static int sideRadius(LotPlan lot) {
        return (lot.facing() == BlockFace.NORTH || lot.facing() == BlockFace.SOUTH)
                ? (lot.footprintWidth() - 1) / 2
                : (lot.footprintDepth() - 1) / 2;
    }

    private static LocalPoint localPoint(LotPlan lot, int lateral, int forward) {
        BlockFace front = lot.facing();
        BlockFace right = VillageStyle.rightOf(front);
        return new LocalPoint(
                lot.centerX() + right.getModX() * lateral + front.getModX() * forward,
                lot.centerZ() + right.getModZ() * lateral + front.getModZ() * forward
        );
    }

    private static void buildTowerRoof(List<Runnable> tasks,
                                       World world,
                                       TerrainManager.SetBlock sb,
                                       int centerX,
                                       int centerZ,
                                       int y) {
        VillageRoofBuilder.buildHip(
                tasks,
                world,
                sb,
                centerX - 2,
                centerX + 2,
                centerZ - 2,
                centerZ + 2,
                y,
                Material.DARK_OAK_PLANKS,
                Material.DARK_OAK_STAIRS,
                Material.DARK_OAK_SLAB
        );
        place(tasks, sb, centerX, y + 4, centerZ, Material.LIGHTNING_ROD);
    }

    private static void buildLeanTo(List<Runnable> tasks,
                                    World world,
                                    TerrainManager.SetBlock sb,
                                    int x,
                                    int z,
                                    int y,
                                    BlockFace facing) {
        BlockFace outward = facing == null ? BlockFace.SOUTH : facing;
        BlockFace inward = VillageStyle.opposite(outward);

        /*
         * L'appentis est une petite couverture pleine de trois blocs de
         * profondeur. L'ancienne version ne posait que deux lignes
         * d'escaliers, ce qui produisait deux barres horizontales flottantes.
         */
        for (int dx = 0; dx < 4; dx++) {
            for (int depth = 0; depth <= 2; depth++) {
                int roofX = x + dx + outward.getModX() * depth;
                int roofZ = z + outward.getModZ() * depth;
                int roofY = y + (depth == 0 ? 1 : 0);

                place(tasks, sb, roofX, roofY, roofZ, Material.SPRUCE_PLANKS);
                if (depth == 0 || depth == 2) {
                    stair(
                            tasks,
                            world,
                            sb,
                            roofX,
                            roofY,
                            roofZ,
                            Material.SPRUCE_STAIRS,
                            inward
                    );
                }
            }
        }

        // Deux poteaux d'angle portent réellement la rive extérieure.
        for (int dx : new int[]{0, 3}) {
            int postX = x + dx + outward.getModX() * 2;
            int postZ = z + outward.getModZ() * 2;
            for (int postY = y - 3; postY <= y - 1; postY++) {
                place(
                        tasks,
                        sb,
                        postX,
                        postY,
                        postZ,
                        Material.STRIPPED_SPRUCE_LOG
                );
            }
            place(tasks, sb, postX, y, postZ, Material.SPRUCE_SLAB);
        }
    }

    private static void stair(List<Runnable> tasks,
                              World world,
                              TerrainManager.SetBlock sb,
                              int x,
                              int y,
                              int z,
                              Material material,
                              BlockFace facing) {
        place(tasks, sb, x, y, z, material);
        tasks.add(() -> VillageStyle.setStair(world, x, y, z, material, facing, Stairs.Half.BOTTOM, Stairs.Shape.STRAIGHT));
    }

    private static void slab(List<Runnable> tasks,
                             World world,
                             TerrainManager.SetBlock sb,
                             int x,
                             int y,
                             int z,
                             Material material,
                             Slab.Type type) {
        place(tasks, sb, x, y, z, material);
        tasks.add(() -> VillageStyle.setSlab(world, x, y, z, material, type));
    }

    private static void place(List<Runnable> tasks, TerrainManager.SetBlock sb, int x, int y, int z, Material material) {
        tasks.add(() -> sb.set(x, y, z, material));
    }

    private record LocalPoint(int x, int z) {}
}
