package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.example.village.VillageLayoutPlan.HouseArchetype;
import static org.example.village.VillageLayoutPlan.HouseSpec;
import static org.example.village.VillageLayoutPlan.LotPlan;
import static org.example.village.VillageLayoutPlan.RoofStyle;

/**
 * Générateur principal des maisons et des petits lots annexes.
 *
 * Le but de cette version est d'obtenir un rendu plus "village de joueur" :
 * façades plus épaisses, toits lisibles, porches, petits jardins, intérieurs
 * crédibles et détails de terrain autour des bâtiments.
 */
public final class HouseBuilder {

    private HouseBuilder() {}

    public static List<Runnable> buildHouse(World world, LotPlan lot, int baseY, TerrainManager.SetBlock sb, Random rng) {
        List<Runnable> tasks = new ArrayList<>();
        HouseSpec spec = lot.houseSpec();
        if (spec == null) {
            return tasks;
        }

        VillageStyle.Palette palette = VillageStyle.medievalPalette(spec.accentMaterial());

        /*
         * Deux niveaux habitables demandent sept blocs de façade : quatre pour
         * le rez-de-chaussée, deux pour la baie haute et un bandeau sous toit.
         * Plusieurs variantes historiques ne déclaraient que cinq ou six
         * blocs ; leurs fenêtres supérieures étaient alors coupées par le toit.
         */
        int effectiveWallHeight = spec.twoStory()
                ? Math.max(7, spec.wallHeight())
                : Math.max(4, spec.wallHeight());

        HouseVolume main = new HouseVolume(
                lot.buildX(),
                lot.buildZ(),
                lot.footprintWidth(),
                lot.footprintDepth(),
                effectiveWallHeight,
                spec.roofStyle()
        );

        HouseVolume annex = annexFor(main, lot);

        // 1) Base du terrain / soubassement.
        buildFoundationSkirt(tasks, sb, main, baseY, palette, Math.min(2, Math.max(1, spec.foundationStep() + 1)));
        if (annex != null) {
            buildFoundationSkirt(tasks, sb, annex, baseY, palette, 1);
        }

        // 2) Volumes principaux.
        buildVolume(tasks, world, sb, main, baseY, lot.facing(), palette, true, spec, 0);
        if (annex != null) {
            buildVolume(tasks, world, sb, annex, baseY, lot.facing(), palette, false, spec, 1);
            stitchVolumes(tasks, sb, main, annex, baseY, palette);
        }

        // 3) Façade et entrée.
        buildDoor(tasks, world, sb, lot, baseY, palette);
        buildFacade(tasks, world, sb, lot, main, baseY, palette);
        buildPorch(tasks, world, sb, lot, baseY, palette);

        // 4) Intérieur et petits accents extérieurs.
        buildInterior(tasks, world, sb, lot, main, annex, baseY, palette);
        buildArchetypeAccent(tasks, world, sb, lot, main, baseY, palette);
        buildYard(tasks, world, sb, lot, main, baseY, palette);
        buildChimney(tasks, sb, lot, main, baseY, palette);

        // 5) Niveau supplémentaire / lucarne si la maison le demande.
        if (spec.twoStory()) {
            buildSecondFloor(tasks, world, sb, lot, main, baseY, palette);
        }
        if (spec.hasDormer()) {
            buildDormer(tasks, world, sb, lot, main, baseY, palette);
        }
        return tasks;
    }

    /**
     * Ferme orientée vers sa rue : portail, allée et outils restent du côté de
     * la façade, quelle que soit l'orientation attribuée par le planificateur.
     */
    public static List<Runnable> buildFarm(World world,
                                           LotPlan lot,
                                           int surfaceY,
                                           List<Material> crops,
                                           TerrainManager.SetBlock sb,
                                           Random rng) {
        return buildFarmGrid(
                world,
                lot.centerX(),
                lot.centerZ(),
                lot.facing(),
                surfaceY,
                crops,
                sb,
                rng
        );
    }

    /**
     * Signature historique conservée pour les autres intégrations du plugin.
     */
    public static List<Runnable> buildFarm(Location base,
                                           List<Material> crops,
                                           TerrainManager.SetBlock sb,
                                           Random rng) {
        if (base == null) {
            return List.of();
        }
        return buildFarmGrid(
                base.getWorld(),
                base.getBlockX() + 5,
                base.getBlockZ() + 5,
                BlockFace.NORTH,
                base.getBlockY(),
                crops,
                sb,
                rng
        );
    }

    private static List<Runnable> buildFarmGrid(World world,
                                                int centerX,
                                                int centerZ,
                                                BlockFace front,
                                                int surfaceY,
                                                List<Material> crops,
                                                TerrainManager.SetBlock sb,
                                                Random rng) {
        List<Runnable> tasks = new ArrayList<>();
        Random random = rng != null ? rng : new Random();
        int half = 5;

        for (int lateral = -half; lateral <= half; lateral++) {
            for (int forward = -half; forward <= half; forward++) {
                Point point = localPoint(centerX, centerZ, front, lateral, forward);
                boolean edge = Math.abs(lateral) == half || Math.abs(forward) == half;
                boolean entrance = forward == half && Math.abs(lateral) <= 1;

                if (edge) {
                    place(tasks, sb, point.x(), surfaceY, point.z(),
                            Math.floorMod(lateral + forward, 3) == 0
                                    ? Material.COARSE_DIRT
                                    : Material.PACKED_MUD);
                    if (!entrance) {
                        place(tasks, sb, point.x(), surfaceY + 1, point.z(), Material.OAK_FENCE);
                    }
                    continue;
                }

                // Une allée transversale dessert chaque planche de culture.
                if (forward == half - 1) {
                    place(tasks, sb, point.x(), surfaceY, point.z(), Material.DIRT_PATH);
                    continue;
                }

                // Canal décentré pour que le portail débouche sur une allée
                // praticable plutôt que directement dans l'eau.
                if (lateral == -1) {
                    place(tasks, sb, point.x(), surfaceY, point.z(), Material.WATER);
                    if (Math.floorMod(forward, 3) == 0) {
                        place(tasks, sb, point.x(), surfaceY + 1, point.z(), Material.LILY_PAD);
                    }
                    continue;
                }

                place(tasks, sb, point.x(), surfaceY, point.z(), Material.FARMLAND);
                Material crop = cropFor(crops, random, lateral, forward);
                place(tasks, sb, point.x(), surfaceY + 1, point.z(), crop);
                matureCrop(tasks, world, point.x(), surfaceY + 1, point.z(), crop);
            }
        }

        // Portillon et chemin d'accès alignés sur la rue.
        Point gatePoint = localPoint(centerX, centerZ, front, 0, half);
        place(tasks, sb, gatePoint.x(), surfaceY + 1, gatePoint.z(), Material.OAK_FENCE_GATE);
        gate(tasks, world, gatePoint.x(), surfaceY + 1, gatePoint.z(),
                Material.OAK_FENCE_GATE, front, false, true);
        for (int forward = half; forward <= half + 2; forward++) {
            for (int lateral = -1; lateral <= 1; lateral++) {
                Point point = localPoint(centerX, centerZ, front, lateral, forward);
                place(tasks, sb, point.x(), surfaceY, point.z(),
                        lateral == 0 ? Material.DIRT_PATH : Material.GRAVEL);
            }
        }

        // Réserve d'outils dans l'angle arrière droit.
        placeFarmDetail(tasks, sb, centerX, surfaceY + 1, centerZ, front,
                3, -3, Material.COMPOSTER);
        placeFarmDetail(tasks, sb, centerX, surfaceY + 1, centerZ, front,
                4, -3, Material.BARREL);
        placeFarmDetail(tasks, sb, centerX, surfaceY + 1, centerZ, front,
                3, -4, Material.HAY_BLOCK);
        placeFarmDetail(tasks, sb, centerX, surfaceY + 1, centerZ, front,
                4, -4, Material.CRAFTING_TABLE);

        // Épouvantail lisible depuis l'entrée.
        Point scarecrow = localPoint(centerX, centerZ, front, 2, -1);
        place(tasks, sb, scarecrow.x(), surfaceY + 1, scarecrow.z(), Material.OAK_FENCE);
        place(tasks, sb, scarecrow.x(), surfaceY + 2, scarecrow.z(), Material.OAK_FENCE);
        BlockFace right = VillageStyle.rightOf(front);
        place(tasks, sb,
                scarecrow.x() + right.getModX(),
                surfaceY + 2,
                scarecrow.z() + right.getModZ(),
                Material.OAK_FENCE);
        place(tasks, sb,
                scarecrow.x() - right.getModX(),
                surfaceY + 2,
                scarecrow.z() - right.getModZ(),
                Material.OAK_FENCE);
        place(tasks, sb, scarecrow.x(), surfaceY + 3, scarecrow.z(), Material.HAY_BLOCK);
        place(tasks, sb, scarecrow.x(), surfaceY + 4, scarecrow.z(), Material.CARVED_PUMPKIN);
        if (world != null) {
            tasks.add(() -> VillageStyle.setDirectional(
                    world,
                    scarecrow.x(),
                    surfaceY + 4,
                    scarecrow.z(),
                    Material.CARVED_PUMPKIN,
                    front
            ));
        }

        // Quatre lanternes basses marquent le périmètre sans créer de pylônes.
        for (int lateral : new int[]{-half, half}) {
            for (int forward : new int[]{-half, half}) {
                Point point = localPoint(centerX, centerZ, front, lateral, forward);
                place(tasks, sb, point.x(), surfaceY + 2, point.z(), Material.LANTERN);
            }
        }
        return tasks;
    }

    /**
     * Enclos orienté, avec abri arrière et zone centrale réellement libre pour
     * les animaux.
     */
    public static List<Runnable> buildPen(Plugin plugin,
                                          World world,
                                          LotPlan lot,
                                          int surfaceY,
                                          int villageId,
                                          TerrainManager.SetBlock sb) {
        return buildPenGrid(
                plugin,
                world,
                lot.centerX(),
                lot.centerZ(),
                lot.facing(),
                surfaceY,
                villageId,
                sb
        );
    }

    /**
     * Signature historique conservée pour les appels existants.
     */
    public static List<Runnable> buildPen(Plugin plugin,
                                          Location base,
                                          int villageId,
                                          TerrainManager.SetBlock sb) {
        if (base == null) {
            return List.of();
        }
        return buildPenGrid(
                plugin,
                base.getWorld(),
                base.getBlockX() + 4,
                base.getBlockZ() + 5,
                BlockFace.NORTH,
                base.getBlockY(),
                villageId,
                sb
        );
    }

    private static List<Runnable> buildPenGrid(Plugin plugin,
                                               World world,
                                               int centerX,
                                               int centerZ,
                                               BlockFace front,
                                               int surfaceY,
                                               int villageId,
                                               TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        int min = -4;
        int max = 5;

        for (int lateral = min; lateral <= max; lateral++) {
            for (int forward = min; forward <= max; forward++) {
                Point point = localPoint(centerX, centerZ, front, lateral, forward);
                boolean edge = lateral == min || lateral == max
                        || forward == min || forward == max;
                boolean entrance = forward == max && Math.abs(lateral) <= 1;
                int selector = Math.floorMod(point.x() * 17 + point.z() * 31, 7);
                Material ground = selector == 0
                        ? Material.COARSE_DIRT
                        : selector <= 2 ? Material.PACKED_MUD : Material.GRASS_BLOCK;
                place(tasks, sb, point.x(), surfaceY, point.z(), ground);
                if (edge && !entrance) {
                    place(tasks, sb, point.x(), surfaceY + 1, point.z(), Material.OAK_FENCE);
                }
            }
        }

        Point gatePoint = localPoint(centerX, centerZ, front, 0, max);
        place(tasks, sb, gatePoint.x(), surfaceY + 1, gatePoint.z(), Material.OAK_FENCE_GATE);
        gate(tasks, world, gatePoint.x(), surfaceY + 1, gatePoint.z(),
                Material.OAK_FENCE_GATE, front, false, true);
        for (int forward = max; forward <= max + 2; forward++) {
            Point point = localPoint(centerX, centerZ, front, 0, forward);
            place(tasks, sb, point.x(), surfaceY, point.z(), Material.PACKED_MUD);
        }

        // Abri ouvert au fond à droite : les quatre poteaux restent hors de la
        // zone de circulation centrale.
        int shedMinLateral = 1;
        int shedMaxLateral = 4;
        int shedMinForward = -3;
        int shedMaxForward = 0;
        for (int lateral = shedMinLateral; lateral <= shedMaxLateral; lateral++) {
            for (int forward = shedMinForward; forward <= shedMaxForward; forward++) {
                Point point = localPoint(centerX, centerZ, front, lateral, forward);
                place(tasks, sb, point.x(), surfaceY, point.z(), Material.PACKED_MUD);
            }
        }
        for (int lateral : new int[]{shedMinLateral, shedMaxLateral}) {
            for (int forward : new int[]{shedMinForward, shedMaxForward}) {
                Point point = localPoint(centerX, centerZ, front, lateral, forward);
                for (int dy = 1; dy <= 3; dy++) {
                    place(tasks, sb, point.x(), surfaceY + dy, point.z(), Material.STRIPPED_SPRUCE_LOG);
                }
            }
        }

        // Couverture complète en appentis, avec débord sur la façade ouverte.
        for (int lateral = shedMinLateral - 1; lateral <= shedMaxLateral + 1; lateral++) {
            for (int forward = shedMinForward - 1; forward <= shedMaxForward + 1; forward++) {
                Point point = localPoint(centerX, centerZ, front, lateral, forward);
                int roofY = surfaceY + 4
                        + Math.max(0, (shedMaxForward + 1 - forward) / 2);
                place(tasks, sb, point.x(), roofY, point.z(), Material.SPRUCE_SLAB);
                if (world != null) {
                    tasks.add(() -> VillageStyle.setSlab(
                            world,
                            point.x(),
                            roofY,
                            point.z(),
                            Material.SPRUCE_SLAB,
                            Slab.Type.TOP
                    ));
                }
            }
        }

        // Auge, fourrage et matériel regroupés sur les côtés, pas au milieu.
        placePenDetail(tasks, sb, centerX, surfaceY + 1, centerZ, front,
                -3, -2, Material.WATER_CAULDRON);
        placePenDetail(tasks, sb, centerX, surfaceY + 1, centerZ, front,
                -3, -1, Material.WATER_CAULDRON);
        placePenDetail(tasks, sb, centerX, surfaceY + 1, centerZ, front,
                -3, 1, Material.HAY_BLOCK);
        placePenDetail(tasks, sb, centerX, surfaceY + 1, centerZ, front,
                -3, 2, Material.BARREL);
        placePenDetail(tasks, sb, centerX, surfaceY + 1, centerZ, front,
                3, -2, Material.HAY_BLOCK);

        if (world != null) {
            tasks.add(() -> {
                for (int i = 0; i < 3; i++) {
                    Point spawn = localPoint(centerX, centerZ, front, -1 + i, 0);
                    var entity = world.spawnEntity(
                            new Location(world, spawn.x() + 0.5, surfaceY + 1, spawn.z() + 0.5),
                            EntityType.SHEEP
                    );
                    VillageEntityManager.tagEntity(entity, plugin, villageId);
                }
            });
        }

        return tasks;
    }

    private static void matureCrop(List<Runnable> tasks,
                                   World world,
                                   int x,
                                   int y,
                                   int z,
                                   Material crop) {
        if (world == null) {
            return;
        }
        tasks.add(() -> {
            var data = crop.createBlockData();
            if (data instanceof org.bukkit.block.data.Ageable ageable) {
                ageable.setAge(ageable.getMaximumAge());
                world.getBlockAt(x, y, z).setBlockData(ageable, false);
            }
        });
    }

    private static void placeFarmDetail(List<Runnable> tasks,
                                        TerrainManager.SetBlock sb,
                                        int centerX,
                                        int y,
                                        int centerZ,
                                        BlockFace front,
                                        int lateral,
                                        int forward,
                                        Material material) {
        Point point = localPoint(centerX, centerZ, front, lateral, forward);
        place(tasks, sb, point.x(), y, point.z(), material);
    }

    private static void placePenDetail(List<Runnable> tasks,
                                       TerrainManager.SetBlock sb,
                                       int centerX,
                                       int y,
                                       int centerZ,
                                       BlockFace front,
                                       int lateral,
                                       int forward,
                                       Material material) {
        placeFarmDetail(tasks, sb, centerX, y, centerZ, front, lateral, forward, material);
    }

    /**
     * Lampadaire compact avec potence latérale. La lanterne est suspendue sous
     * son support au lieu d'être empilée verticalement au-dessus du poteau.
     */
    public static List<Runnable> buildLampPost(int x, int y, int z, TerrainManager.SetBlock sb) {
        BlockFace arm = Math.floorMod(x * 31 + z * 17, 2) == 0
                ? BlockFace.EAST
                : BlockFace.WEST;
        return buildLampPost(x, y, z, arm, sb);
    }

    public static List<Runnable> buildLampPost(int x,
                                               int y,
                                               int z,
                                               BlockFace arm,
                                               TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        BlockFace safeArm = arm == BlockFace.NORTH
                || arm == BlockFace.SOUTH
                || arm == BlockFace.EAST
                || arm == BlockFace.WEST
                ? arm
                : BlockFace.EAST;

        place(tasks, sb, x, y - 1, z, Material.STONE_BRICKS);
        place(tasks, sb, x, y, z, Material.COBBLESTONE_WALL);
        for (int dy = 1; dy <= 3; dy++) {
            place(tasks, sb, x, y + dy, z, Material.STRIPPED_SPRUCE_LOG);
        }

        int armX = x + safeArm.getModX();
        int armZ = z + safeArm.getModZ();
        place(tasks, sb, armX, y + 3, armZ, Material.SPRUCE_FENCE);
        place(tasks, sb, armX, y + 2, armZ, Material.CHAIN);
        place(tasks, sb, armX, y + 1, armZ, Material.LANTERN);

        // Chaperon simple qui donne une terminaison plus propre au poteau.
        place(tasks, sb, x, y + 4, z, Material.SPRUCE_SLAB);
        return tasks;
    }

    private static void buildVolume(List<Runnable> tasks,
                                    World world,
                                    TerrainManager.SetBlock sb,
                                    HouseVolume volume,
                                    int baseY,
                                    BlockFace facing,
                                    VillageStyle.Palette palette,
                                    boolean frontVolume,
                                    HouseSpec spec,
                                    int volumeIndex) {

        // Sol et soubassement.
        for (int x = volume.minX(); x <= volume.maxX(); x++) {
            for (int z = volume.minZ(); z <= volume.maxZ(); z++) {
                place(tasks, sb, x, baseY - 1, z, mixedFoundation(palette, x, z));
                place(tasks, sb, x, baseY, z, palette.floor());
            }
        }

        // Murs.
        for (int y = baseY + 1; y <= baseY + volume.wallHeight(); y++) {
            for (int x = volume.minX(); x <= volume.maxX(); x++) {
                for (int z = volume.minZ(); z <= volume.maxZ(); z++) {
                    if (!perimeter(x, z, volume)) {
                        continue;
                    }
                    boolean volumeCorner = corner(x, z, volume);
                    boolean lowerBand = y == baseY + 1;
                    boolean beamLine = y == baseY + volume.wallHeight();
                    boolean window = shouldWindow(
                            x,
                            y,
                            z,
                            volume,
                            baseY,
                            facing,
                            frontVolume,
                            spec.twoStory()
                    );

                    /*
                     * Les baies ont priorité sur le colombage décoratif. Dans
                     * l'ancienne boucle, une poutre calculée sur les mêmes
                     * coordonnées remplaçait aléatoirement un demi-vitrage et
                     * produisait les formes en L visibles sur les façades.
                     */
                    if (volumeCorner || lowerBand || beamLine) {
                        place(tasks, sb, x, y, z, palette.timber());
                    } else if (window) {
                        place(tasks, sb, x, y, z, palette.window());

                        // Une jardinière par baie verticale de deux blocs.
                        int relativeY = y - baseY;
                        if (relativeY == 2 || relativeY == 5) {
                            buildWindowBox(
                                    tasks,
                                    world,
                                    sb,
                                    x,
                                    y,
                                    z,
                                    outward(x, z, volume),
                                    palette,
                                    volumeIndex + x + z
                            );
                        }
                    } else if (framePattern(
                            x,
                            z,
                            y,
                            baseY,
                            volume,
                            volumeIndex
                    )) {
                        place(tasks, sb, x, y, z, palette.timber());
                    } else {
                        place(tasks, sb, x, y, z, palette.wallFill());
                    }
                }
            }
        }

        // Anneau de toiture / débord.
        addRoofEaves(tasks, world, sb, volume, baseY + volume.wallHeight() + 1, facing, palette);
        buildRoof(tasks, world, sb, volume, baseY + volume.wallHeight() + 1, facing, palette);
    }

    private static void buildDoor(List<Runnable> tasks,
                                  World world,
                                  TerrainManager.SetBlock sb,
                                  LotPlan lot,
                                  int baseY,
                                  VillageStyle.Palette palette) {
        int doorX = lot.doorX();
        int doorZ = lot.doorZ();
        BlockFace left = VillageStyle.leftOf(lot.facing());
        BlockFace right = VillageStyle.rightOf(lot.facing());

        // Encadrement épais.
        for (int dy = 1; dy <= 3; dy++) {
            place(tasks, sb, doorX + left.getModX(), baseY + dy, doorZ + left.getModZ(), palette.timber());
            place(tasks, sb, doorX + right.getModX(), baseY + dy, doorZ + right.getModZ(), palette.timber());
        }
        place(tasks, sb, doorX, baseY + 3, doorZ, palette.timber());

        // Porte double bloc correctement orientée.
        place(tasks, sb, doorX, baseY + 1, doorZ, palette.door());
        place(tasks, sb, doorX, baseY + 2, doorZ, palette.door());
        if (world != null) {
            tasks.add(() -> VillageStyle.setDoor(world, doorX, baseY + 1, doorZ, palette.door(), lot.facing(), Bisected.Half.BOTTOM));
            tasks.add(() -> VillageStyle.setDoor(world, doorX, baseY + 2, doorZ, palette.door(), lot.facing(), Bisected.Half.TOP));
        }

        // Seuil et marche.
        place(tasks, sb, lot.frontStepX(), baseY, lot.frontStepZ(), palette.paving());
        place(tasks, sb, lot.frontStepX(), baseY - 1, lot.frontStepZ(), palette.foundationPrimary());
        stair(tasks, world, sb,
                lot.frontStepX() + lot.facing().getModX(),
                baseY,
                lot.frontStepZ() + lot.facing().getModZ(),
                Material.STONE_BRICK_STAIRS,
                lot.facing());
    }

    private static void buildFacade(List<Runnable> tasks,
                                    World world,
                                    TerrainManager.SetBlock sb,
                                    LotPlan lot,
                                    HouseVolume main,
                                    int baseY,
                                    VillageStyle.Palette palette) {
        int beamY = baseY + main.wallHeight();
        BlockFace left = VillageStyle.leftOf(lot.facing());
        BlockFace right = VillageStyle.rightOf(lot.facing());

        // Poutre frontale centrée sur la porte.
        for (int i = -2; i <= 2; i++) {
            int bx = lot.doorX() + left.getModX() * i;
            int bz = lot.doorZ() + left.getModZ() * i;
            if (bx >= main.minX() && bx <= main.maxX()
                    && bz >= main.minZ() && bz <= main.maxZ()) {
                place(tasks, sb, bx, beamY, bz, palette.timber());
            }
        }

        /*
         * Les accents se rapprochent de la porte lorsqu'une marquise est
         * présente. À l'ancien décalage de deux blocs, les poteaux du porche
         * remplaçaient la bannière, la chaîne et les deux jardinières.
         */
        boolean hasPorch = lot.houseSpec().hasPorch();
        int accentOffset = hasPorch ? 1 : 2;

        // Bannière murale réellement adossée à la façade.
        int signX = lot.doorX()
                + left.getModX() * accentOffset
                + lot.facing().getModX();
        int signZ = lot.doorZ()
                + left.getModZ() * accentOffset
                + lot.facing().getModZ();
        Material banner = lot.houseSpec().facadeVariant() % 2 == 0
                ? Material.RED_WALL_BANNER
                : Material.YELLOW_WALL_BANNER;
        place(tasks, sb, signX, baseY + 2, signZ, banner);
        if (world != null) {
            tasks.add(() -> VillageStyle.setDirectional(
                    world, signX, baseY + 2, signZ, banner, lot.facing()));
        }

        /*
         * Sous un porche, la lanterne est suspendue directement à la marquise
         * posée ensuite à Y+3. Sans porche, une chaîne assure son support.
         */
        int lightX = lot.doorX()
                + right.getModX() * accentOffset
                + lot.facing().getModX();
        int lightZ = lot.doorZ()
                + right.getModZ() * accentOffset
                + lot.facing().getModZ();
        if (!hasPorch) {
            place(tasks, sb, lightX, baseY + 3, lightZ, Material.CHAIN);
        }
        place(tasks, sb, lightX, baseY + 2, lightZ, Material.LANTERN);

        /*
         * Les jardinières sont repoussées au-delà des poteaux du porche quand
         * la largeur de façade le permet. Les petits volumes utilisent la
         * travée intérieure : les hauteurs restent distinctes des accents.
         */
        int facadeRadius = (lot.facing() == BlockFace.NORTH
                || lot.facing() == BlockFace.SOUTH)
                ? Math.max(1, (main.footprintWidth() - 1) / 2)
                : Math.max(1, (main.footprintDepth() - 1) / 2);
        int planterOffset = hasPorch
                ? (facadeRadius >= 3 ? 3 : 1)
                : Math.min(2, facadeRadius);
        int leftPlanterX = lot.doorX()
                + left.getModX() * planterOffset
                + lot.facing().getModX();
        int leftPlanterZ = lot.doorZ()
                + left.getModZ() * planterOffset
                + lot.facing().getModZ();
        int rightPlanterX = lot.doorX()
                + right.getModX() * planterOffset
                + lot.facing().getModX();
        int rightPlanterZ = lot.doorZ()
                + right.getModZ() * planterOffset
                + lot.facing().getModZ();
        addFlowerBox(tasks, world, sb,
                leftPlanterX, baseY, leftPlanterZ,
                lot.facing(), palette, Material.POPPY);
        addFlowerBox(tasks, world, sb,
                rightPlanterX, baseY, rightPlanterZ,
                lot.facing(), palette, Material.BLUE_ORCHID);
    }

    private static void buildInterior(List<Runnable> tasks,
                                      World world,
                                      TerrainManager.SetBlock sb,
                                      LotPlan lot,
                                      HouseVolume main,
                                      HouseVolume annex,
                                      int baseY,
                                      VillageStyle.Palette palette) {
        BlockFace front = lot.facing();
        BlockFace left = VillageStyle.leftOf(front);
        BlockFace right = VillageStyle.rightOf(front);
        BlockFace back = VillageStyle.opposite(front);

        int cx = main.centerX();
        int cz = main.centerZ();

        // Tapis central en laissant le mobilier écraser proprement les dalles.
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                place(tasks, sb, x, baseY + 1, z,
                        lot.houseSpec().facadeVariant() % 2 == 0
                                ? Material.RED_CARPET
                                : Material.GRAY_CARPET);
            }
        }

        // Table et assises orientées dans le repère local de la façade.
        place(tasks, sb, cx, baseY + 1, cz, Material.SPRUCE_FENCE);
        place(tasks, sb, cx, baseY + 2, cz, Material.SPRUCE_PRESSURE_PLATE);
        stair(tasks, world, sb,
                cx + left.getModX(), baseY + 1, cz + left.getModZ(),
                Material.SPRUCE_STAIRS, right);
        stair(tasks, world, sb,
                cx + right.getModX(), baseY + 1, cz + right.getModZ(),
                Material.SPRUCE_STAIRS, left);

        // Lit au fond à gauche : les deux blocs restent toujours à l'intérieur,
        // y compris pour les façades est et ouest.
        Point bedFoot = localPoint(main, front, -2, -1);
        placeBed(tasks, world, sb,
                bedFoot.x(), baseY + 1, bedFoot.z(),
                Material.RED_BED, back);

        // Rangements près de l'entrée, sans dépendre des coordonnées absolues.
        Point storageA = localPoint(main, front, -2, 1);
        Point storageB = localPoint(main, front, -1, 1);
        place(tasks, sb, storageA.x(), baseY + 1, storageA.z(), Material.BARREL);
        place(tasks, sb, storageB.x(), baseY + 1, storageB.z(), Material.CHEST);

        // Sous un étage, le plancher sert directement de support à la lanterne.
        // Dans une maison basse, une chaîne est ajoutée sous la charpente.
        if (!lot.houseSpec().twoStory()) {
            place(tasks, sb, cx, baseY + 4, cz, Material.CHAIN);
        }
        place(tasks, sb, cx, baseY + 3, cz, Material.LANTERN);

        Point workA = localPoint(main, front, 2, -2);
        Point workB = localPoint(main, front, 1, -2);
        Point workC = localPoint(main, front, 0, -2);
        switch (lot.houseSpec().interiorVariant()) {
            case 0 -> {
                place(tasks, sb, workA.x(), baseY + 1, workA.z(), Material.FURNACE);
                place(tasks, sb, workB.x(), baseY + 1, workB.z(), Material.SMOKER);
                place(tasks, sb, workC.x(), baseY + 1, workC.z(), Material.CAULDRON);
                if (world != null) {
                    tasks.add(() -> VillageStyle.setDirectional(
                            world, workA.x(), baseY + 1, workA.z(),
                            Material.FURNACE, front));
                    tasks.add(() -> VillageStyle.setDirectional(
                            world, workB.x(), baseY + 1, workB.z(),
                            Material.SMOKER, front));
                }
            }
            case 1 -> {
                place(tasks, sb, workA.x(), baseY + 1, workA.z(), Material.SMITHING_TABLE);
                place(tasks, sb, workB.x(), baseY + 1, workB.z(), Material.ANVIL);
                place(tasks, sb, workC.x(), baseY + 1, workC.z(), Material.GRINDSTONE);
            }
            case 2 -> {
                place(tasks, sb, workA.x(), baseY + 1, workA.z(), Material.BOOKSHELF);
                place(tasks, sb, workB.x(), baseY + 1, workB.z(), Material.BOOKSHELF);
                place(tasks, sb, workC.x(), baseY + 1, workC.z(), Material.LECTERN);
            }
            default -> {
                place(tasks, sb, workA.x(), baseY + 1, workA.z(), Material.CAMPFIRE);
                place(tasks, sb, workB.x(), baseY + 1, workB.z(), Material.BARREL);
                place(tasks, sb, workC.x(), baseY + 1, workC.z(), Material.BARREL);
            }
        }

        // L'aile devient une pièce réellement reliée au volume principal.
        if (annex != null) {
            int ax = annex.centerX();
            int az = annex.centerZ();
            place(tasks, sb, ax, baseY + 1, az, Material.CRAFTING_TABLE);
            place(tasks, sb,
                    ax + right.getModX(), baseY + 1,
                    az + right.getModZ(), Material.BARREL);
            slab(tasks, world, sb,
                    ax + left.getModX(), baseY + 1,
                    az + left.getModZ(), Material.OAK_SLAB, Slab.Type.BOTTOM);
        }
    }

    private static void buildArchetypeAccent(List<Runnable> tasks,
                                             World world,
                                             TerrainManager.SetBlock sb,
                                             LotPlan lot,
                                             HouseVolume main,
                                             int baseY,
                                             VillageStyle.Palette palette) {
        BlockFace front = lot.facing();
        BlockFace left = VillageStyle.leftOf(front);
        BlockFace right = VillageStyle.rightOf(front);
        int sideSpan = front == BlockFace.NORTH || front == BlockFace.SOUTH
                ? main.footprintWidth()
                : main.footprintDepth();
        int sideOffset = sideSpan / 2 + 1;

        int sideSign;
        if (lot.hasWing() && lot.wingSide() == right) {
            sideSign = -1;
        } else if (lot.hasWing() && lot.wingSide() == left) {
            sideSign = 1;
        } else {
            sideSign = lot.houseSpec().facadeVariant() % 2 == 0 ? 1 : -1;
        }

        Point display = localPoint(main, front, sideSign * sideOffset, -1);
        Point neighbour = localPoint(main, front, sideSign * sideOffset, 0);

        switch (lot.houseSpec().archetype()) {
            case COTTAGE -> {
                place(tasks, sb, display.x(), baseY, display.z(), Material.FLOWER_POT);
                place(tasks, sb, neighbour.x(), baseY, neighbour.z(), Material.POTTED_DANDELION);
            }
            case TOWNHOUSE -> {
                place(tasks, sb, display.x(), baseY, display.z(), Material.BARREL);
                place(tasks, sb, display.x(), baseY + 1, display.z(), Material.LANTERN);
            }
            case FAMILY_HOUSE -> {
                place(tasks, sb, display.x(), baseY, display.z(), Material.BARREL);
                place(tasks, sb, neighbour.x(), baseY, neighbour.z(), Material.HAY_BLOCK);
            }
            case WORKSHOP_HOUSE -> {
                place(tasks, sb, display.x(), baseY - 1, display.z(), Material.GRAVEL);
                place(tasks, sb, display.x(), baseY, display.z(), Material.BARREL);
                place(tasks, sb, neighbour.x(), baseY, neighbour.z(), Material.CHEST);
            }
        }
    }

    private static void buildFoundationSkirt(List<Runnable> tasks,
                                             TerrainManager.SetBlock sb,
                                             HouseVolume volume,
                                             int baseY,
                                             VillageStyle.Palette palette,
                                             int steps) {
        for (int step = 1; step <= steps; step++) {
            int minX = volume.minX() - step;
            int maxX = volume.maxX() + step;
            int minZ = volume.minZ() - step;
            int maxZ = volume.maxZ() + step;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
                    if (edge) {
                        place(tasks, sb, x, baseY - step, z, (step + x + z) % 3 == 0 ? palette.foundationAccent() : palette.foundationPrimary());
                    }
                }
            }
        }
    }

    private static void buildPorch(List<Runnable> tasks,
                                   World world,
                                   TerrainManager.SetBlock sb,
                                   LotPlan lot,
                                   int baseY,
                                   VillageStyle.Palette palette) {
        if (!lot.houseSpec().hasPorch()) {
            return;
        }

        BlockFace front = lot.facing();
        BlockFace left = VillageStyle.leftOf(front);
        int centerX = lot.frontStepX();
        int centerZ = lot.frontStepZ();

        // Perron d'un seul bloc de profondeur : les petites parcelles ne
        // voient plus leur porche déborder sur la chaussée.
        for (int lateral = -2; lateral <= 2; lateral++) {
            int x = centerX + left.getModX() * lateral;
            int z = centerZ + left.getModZ() * lateral;
            place(tasks, sb, x, baseY, z, palette.floor());
        }

        // Deux poteaux suffisent à soutenir la marquise et laissent toute la
        // largeur centrale disponible.
        for (int lateral : List.of(-2, 2)) {
            int x = centerX + left.getModX() * lateral;
            int z = centerZ + left.getModZ() * lateral;
            for (int dy = 1; dy <= 3; dy++) {
                place(tasks, sb, x, baseY + dy, z, palette.timber());
            }
        }

        /*
         * Aucun garde-corps n'est posé sur le plan du mur : l'ancienne version
         * remplaçait à cet endroit le remplissage de façade. Les deux poteaux
         * latéraux jouent déjà le rôle de limite visuelle du petit perron.
         */

        // Marquise adossée au mur, avec un seul rang extérieur.
        for (int lateral = -2; lateral <= 2; lateral++) {
            int x = centerX + left.getModX() * lateral;
            int z = centerZ + left.getModZ() * lateral;
            slab(tasks, world, sb,
                    x - front.getModX(),
                    baseY + 4,
                    z - front.getModZ(),
                    palette.roofSlab(),
                    Slab.Type.TOP);
            stair(tasks, world, sb,
                    x,
                    baseY + 3,
                    z,
                    palette.awning(),
                    front);
        }
        place(tasks, sb, centerX, baseY + 3, centerZ, Material.LANTERN);
    }

    private static void buildYard(List<Runnable> tasks,
                                  World world,
                                  TerrainManager.SetBlock sb,
                                  LotPlan lot,
                                  HouseVolume main,
                                  int baseY,
                                  VillageStyle.Palette palette) {
        BlockFace front = lot.facing();
        BlockFace left = VillageStyle.leftOf(front);
        BlockFace right = VillageStyle.rightOf(front);

        // Petite allée entre le seuil et la rue. La dernière cellule appartient
        // à la chaussée et reste sous la responsabilité de Disposition.
        int roadDistance = Math.abs(lot.frontageX() - lot.frontStepX())
                + Math.abs(lot.frontageZ() - lot.frontStepZ());
        int privatePathLength = Math.max(1, roadDistance - 1);
        for (int step = 0; step < privatePathLength; step++) {
            int x = lot.frontStepX() + front.getModX() * step;
            int z = lot.frontStepZ() + front.getModZ() * step;
            place(tasks, sb, x, baseY - 1, z,
                    step == 0 ? palette.paving() : Material.DIRT_PATH);

            // Deux bandes végétales seulement sur la partie privée du chemin.
            if (step < privatePathLength - 1 || privatePathLength == 1) {
                for (int sign : List.of(-1, 1)) {
                    int sx = x + left.getModX() * sign;
                    int sz = z + left.getModZ() * sign;
                    place(tasks, sb, sx, baseY - 1, sz,
                            sideYardMaterial(
                                    lot.houseSpec().yardStyle(),
                                    step,
                                    sign < 0));
                }
            }
        }

        // Le vrai jardin se place sur un côté de la maison, à l'opposé de
        // l'aile secondaire. Ainsi, aucun potager ne traverse la rue.
        int sideSpan = front == BlockFace.NORTH || front == BlockFace.SOUTH
                ? main.footprintWidth()
                : main.footprintDepth();
        int firstOutside = sideSpan / 2 + 1;
        int sideSign;
        if (lot.hasWing() && lot.wingSide() == right) {
            sideSign = -1;
        } else if (lot.hasWing() && lot.wingSide() == left) {
            sideSign = 1;
        } else {
            sideSign = lot.houseSpec().facadeVariant() % 2 == 0 ? 1 : -1;
        }

        int gardenDepth = Math.max(2, Math.min(3, lot.yardDepth()));
        for (int outward = 0; outward < gardenDepth; outward++) {
            int lateral = sideSign * (firstOutside + outward);
            for (int forward = -2; forward <= 1; forward++) {
                Point point = localPoint(main, front, lateral, forward);
                place(tasks, sb, point.x(), baseY - 1, point.z(),
                        yardMaterial(lot.houseSpec().yardStyle(), outward + forward + 2));

                boolean outerFence = outward == gardenDepth - 1;
                boolean endFence = forward == -2 || forward == 1;
                boolean entrance = forward == 1 && outward == 1;
                if ((outerFence || endFence) && !entrance) {
                    place(tasks, sb, point.x(), baseY, point.z(), palette.fence());
                }
            }
        }

        int detailLateral = sideSign * (firstOutside + 1);
        Point detailA = localPoint(main, front, detailLateral, -1);
        Point detailB = localPoint(main, front, detailLateral, 0);
        switch (lot.houseSpec().yardStyle()) {
            case FLOWERS -> {
                place(tasks, sb, detailA.x(), baseY, detailA.z(), Material.POPPY);
                place(tasks, sb, detailB.x(), baseY, detailB.z(), Material.ALLIUM);
                Point flowerC = localPoint(main, front,
                        sideSign * firstOutside, 0);
                place(tasks, sb, flowerC.x(), baseY, flowerC.z(), Material.BLUE_ORCHID);
            }
            case WOODPILE -> {
                place(tasks, sb, detailA.x(), baseY, detailA.z(), Material.OAK_LOG);
                place(tasks, sb, detailA.x(), baseY + 1, detailA.z(), Material.OAK_LOG);
                place(tasks, sb, detailB.x(), baseY, detailB.z(), Material.BARREL);
                if (world != null) {
                    org.bukkit.Axis axis = front == BlockFace.NORTH || front == BlockFace.SOUTH
                            ? org.bukkit.Axis.Z
                            : org.bukkit.Axis.X;
                    tasks.add(() -> VillageStyle.setLogAxis(
                            world, detailA.x(), baseY, detailA.z(), Material.OAK_LOG, axis));
                    tasks.add(() -> VillageStyle.setLogAxis(
                            world, detailA.x(), baseY + 1, detailA.z(), Material.OAK_LOG, axis));
                }
            }
            case FENCED -> {
                place(tasks, sb, detailA.x(), baseY, detailA.z(), Material.HAY_BLOCK);
                place(tasks, sb, detailB.x(), baseY, detailB.z(), Material.COMPOSTER);
                place(tasks, sb, detailB.x(), baseY + 1, detailB.z(), Material.LANTERN);
            }
            case KITCHEN_GARDEN -> {
                place(tasks, sb, detailA.x(), baseY - 1, detailA.z(), Material.FARMLAND);
                place(tasks, sb, detailA.x(), baseY, detailA.z(), Material.CARROTS);
                matureCrop(tasks, world, detailA.x(), baseY, detailA.z(), Material.CARROTS);
                place(tasks, sb, detailB.x(), baseY - 1, detailB.z(), Material.FARMLAND);
                place(tasks, sb, detailB.x(), baseY, detailB.z(), Material.POTATOES);
                matureCrop(tasks, world, detailB.x(), baseY, detailB.z(), Material.POTATOES);
            }
        }
    }

    private static Material yardMaterial(VillageLayoutPlan.YardStyle yardStyle, int step) {
        return switch (yardStyle) {
            case FLOWERS -> step % 2 == 0 ? Material.GRASS_BLOCK : Material.MOSS_BLOCK;
            case WOODPILE -> Material.COARSE_DIRT;
            case FENCED -> Material.GRAVEL;
            case KITCHEN_GARDEN -> Material.PACKED_MUD;
        };
    }

    private static Material sideYardMaterial(VillageLayoutPlan.YardStyle yardStyle, int step, boolean leftSide) {
        return switch (yardStyle) {
            case FLOWERS -> leftSide ? Material.MOSS_BLOCK : Material.GRASS_BLOCK;
            case WOODPILE -> leftSide ? Material.COARSE_DIRT : Material.PACKED_MUD;
            case FENCED -> (step + (leftSide ? 1 : 0)) % 2 == 0 ? Material.GRASS_BLOCK : Material.MOSS_BLOCK;
            case KITCHEN_GARDEN -> Material.GRASS_BLOCK;
        };
    }

    private static void buildChimney(List<Runnable> tasks,
                                     TerrainManager.SetBlock sb,
                                     LotPlan lot,
                                     HouseVolume main,
                                     int baseY,
                                     VillageStyle.Palette palette) {
        if (lot.houseSpec().archetype() == HouseArchetype.TOWNHOUSE && lot.houseSpec().facadeVariant() == 0) {
            return;
        }
        BlockFace back = VillageStyle.opposite(lot.facing());
        BlockFace side = lot.houseSpec().facadeVariant() % 2 == 0 ? VillageStyle.leftOf(lot.facing()) : VillageStyle.rightOf(lot.facing());
        int x = back == BlockFace.WEST ? main.minX() + 1 : back == BlockFace.EAST ? main.maxX() - 1 : (side == BlockFace.WEST ? main.minX() + 1 : main.maxX() - 1);
        int z = back == BlockFace.NORTH ? main.minZ() + 1 : back == BlockFace.SOUTH ? main.maxZ() - 1 : (side == BlockFace.NORTH ? main.minZ() + 1 : main.maxZ() - 1);
        for (int y = baseY + 1; y <= baseY + main.wallHeight() + 4; y++) {
            place(tasks, sb, x, y, z, (y + x + z) % 3 == 0 ? Material.COBBLESTONE : Material.BRICKS);
        }
        place(tasks, sb, x, baseY + main.wallHeight() + 5, z, Material.CAMPFIRE);
        place(tasks, sb, x, baseY + main.wallHeight() + 6, z, Material.IRON_BARS);
    }

    private static void buildSecondFloor(List<Runnable> tasks,
                                         World world,
                                         TerrainManager.SetBlock sb,
                                         LotPlan lot,
                                         HouseVolume main,
                                         int baseY,
                                         VillageStyle.Palette palette) {
        int floorY = baseY + 4;
        BlockFace front = lot.facing();
        Point stairStart = localPoint(main, front, 2, -2);

        // Le plancher réserve les deux dernières cases de la volée afin de ne
        // pas reboucher l'escalier.
        for (int x = main.minX() + 1; x <= main.maxX() - 1; x++) {
            for (int z = main.minZ() + 1; z <= main.maxZ() - 1; z++) {
                boolean stairOpening = false;
                for (int step = 2; step <= 3; step++) {
                    int sx = stairStart.x() + front.getModX() * step;
                    int sz = stairStart.z() + front.getModZ() * step;
                    if (x == sx && z == sz) {
                        stairOpening = true;
                        break;
                    }
                }
                if (!stairOpening) {
                    place(tasks, sb, x, floorY, z, palette.floor());
                }
            }
        }

        for (int step = 0; step < 4; step++) {
            int sx = stairStart.x() + front.getModX() * step;
            int sz = stairStart.z() + front.getModZ() * step;
            stair(tasks, world, sb, sx, baseY + 1 + step, sz,
                    Material.SPRUCE_STAIRS, front);
        }

        int landingX = stairStart.x() + front.getModX() * 4;
        int landingZ = stairStart.z() + front.getModZ() * 4;
        place(tasks, sb, landingX, floorY, landingZ, palette.floor());

        BlockFace left = VillageStyle.leftOf(front);
        place(tasks, sb,
                landingX + left.getModX(), floorY + 1,
                landingZ + left.getModZ(), palette.fence());

        // Mobilier léger à l'étage pour éviter une coque vide.
        Point upperStorage = localPoint(main, front, -2, -1);
        place(tasks, sb, upperStorage.x(), floorY + 1,
                upperStorage.z(), Material.BOOKSHELF);
        Point upperSeat = localPoint(main, front, -1, 1);
        stair(tasks, world, sb, upperSeat.x(), floorY + 1,
                upperSeat.z(), Material.SPRUCE_STAIRS,
                VillageStyle.opposite(front));
    }

    private static void buildDormer(List<Runnable> tasks,
                                    World world,
                                    TerrainManager.SetBlock sb,
                                    LotPlan lot,
                                    HouseVolume main,
                                    int baseY,
                                    VillageStyle.Palette palette) {
        int roofBaseY = baseY + main.wallHeight() + 2;
        BlockFace front = lot.facing();
        BlockFace back = VillageStyle.opposite(front);
        BlockFace left = VillageStyle.leftOf(front);
        BlockFace right = VillageStyle.rightOf(front);

        int frontX = switch (front) {
            case EAST -> main.maxX() + 1;
            case WEST -> main.minX() - 1;
            default -> main.centerX();
        };
        int frontZ = switch (front) {
            case NORTH -> main.minZ() - 1;
            case SOUTH -> main.maxZ() + 1;
            default -> main.centerZ();
        };

        /*
         * La lucarne est un vrai petit volume de trois blocs de profondeur :
         * façade vitrée, joues latérales, fond et couverture. L'ancienne
         * version ne contenait qu'une rangée de trois blocs et deux escaliers ;
         * depuis le côté, on regardait directement à travers.
         */
        for (int depth = 0; depth <= 2; depth++) {
            int centerX = frontX + back.getModX() * depth;
            int centerZ = frontZ + back.getModZ() * depth;
            int leftX = centerX + left.getModX();
            int leftZ = centerZ + left.getModZ();
            int rightX = centerX + right.getModX();
            int rightZ = centerZ + right.getModZ();

            // Plancher continu raccordé à la couverture principale.
            place(
                    tasks,
                    sb,
                    centerX,
                    roofBaseY - 1,
                    centerZ,
                    palette.roofBlock()
            );
            place(
                    tasks,
                    sb,
                    leftX,
                    roofBaseY - 1,
                    leftZ,
                    palette.roofBlock()
            );
            place(
                    tasks,
                    sb,
                    rightX,
                    roofBaseY - 1,
                    rightZ,
                    palette.roofBlock()
            );

            // Joues latérales fermées.
            for (int dy = 0; dy <= 1; dy++) {
                place(
                        tasks,
                        sb,
                        leftX,
                        roofBaseY + dy,
                        leftZ,
                        dy == 0 ? palette.timber() : palette.wallFill()
                );
                place(
                        tasks,
                        sb,
                        rightX,
                        roofBaseY + dy,
                        rightZ,
                        dy == 0 ? palette.timber() : palette.wallFill()
                );
            }

            if (depth == 0) {
                // Baie verticale en verre plein, encadrée par les montants.
                place(
                        tasks,
                        sb,
                        centerX,
                        roofBaseY,
                        centerZ,
                        palette.window()
                );
                place(
                        tasks,
                        sb,
                        centerX,
                        roofBaseY + 1,
                        centerZ,
                        palette.window()
                );
            } else if (depth == 2) {
                // Le fond ferme le volume côté faîtage.
                place(
                        tasks,
                        sb,
                        centerX,
                        roofBaseY,
                        centerZ,
                        palette.timber()
                );
                place(
                        tasks,
                        sb,
                        centerX,
                        roofBaseY + 1,
                        centerZ,
                        palette.wallFill()
                );
            }

            /*
             * Noyau plein sous les rampants : les escaliers latéraux montent
             * vers le centre, puis une dalle soutenue termine le faîtage.
             */
            place(
                    tasks,
                    sb,
                    leftX,
                    roofBaseY + 2,
                    leftZ,
                    palette.roofBlock()
            );
            place(
                    tasks,
                    sb,
                    centerX,
                    roofBaseY + 2,
                    centerZ,
                    palette.roofBlock()
            );
            place(
                    tasks,
                    sb,
                    rightX,
                    roofBaseY + 2,
                    rightZ,
                    palette.roofBlock()
            );
            stair(
                    tasks,
                    world,
                    sb,
                    leftX,
                    roofBaseY + 2,
                    leftZ,
                    palette.roofStairs(),
                    right
            );
            stair(
                    tasks,
                    world,
                    sb,
                    rightX,
                    roofBaseY + 2,
                    rightZ,
                    palette.roofStairs(),
                    left
            );
            slab(
                    tasks,
                    world,
                    sb,
                    centerX,
                    roofBaseY + 3,
                    centerZ,
                    palette.roofSlab(),
                    Slab.Type.BOTTOM
            );
        }

        // Appui saillant sous la baie, visuellement porté par la toiture.
        slab(
                tasks,
                world,
                sb,
                frontX,
                roofBaseY - 1,
                frontZ,
                palette.roofSlab(),
                Slab.Type.BOTTOM
        );
    }

    private static void buildRoof(List<Runnable> tasks,
                                  World world,
                                  TerrainManager.SetBlock sb,
                                  HouseVolume volume,
                                  int roofY,
                                  BlockFace facing,
                                  VillageStyle.Palette palette) {
        switch (volume.roofStyle()) {
            case HIP -> hipRoof(tasks, world, sb, volume, roofY, palette);
            case SHED -> shedRoof(tasks, world, sb, volume, roofY,
                    VillageStyle.opposite(facing), palette);
            case OFFSET_GABLE, GABLE -> gableRoof(
                    tasks, world, sb, volume, roofY, facing, palette);
        }
    }

    private static void gableRoof(List<Runnable> tasks,
                                  World world,
                                  TerrainManager.SetBlock sb,
                                  HouseVolume volume,
                                  int roofY,
                                  BlockFace facing,
                                  VillageStyle.Palette palette) {
        VillageRoofBuilder.buildGable(
                tasks,
                world,
                sb,
                volume.minX(),
                volume.maxX(),
                volume.minZ(),
                volume.maxZ(),
                roofY,
                facing,
                palette.roofBlock(),
                palette.roofStairs(),
                palette.roofSlab(),
                palette.wallFill(),
                palette.timber(),
                palette.window()
        );
    }

    private static void hipRoof(List<Runnable> tasks,
                                World world,
                                TerrainManager.SetBlock sb,
                                HouseVolume volume,
                                int roofY,
                                VillageStyle.Palette palette) {
        VillageRoofBuilder.buildHip(
                tasks,
                world,
                sb,
                volume.minX(),
                volume.maxX(),
                volume.minZ(),
                volume.maxZ(),
                roofY,
                palette.roofBlock(),
                palette.roofStairs(),
                palette.roofSlab()
        );
    }

    private static void shedRoof(List<Runnable> tasks,
                                 World world,
                                 TerrainManager.SetBlock sb,
                                 HouseVolume volume,
                                 int roofY,
                                 BlockFace riseFrom,
                                 VillageStyle.Palette palette) {
        BlockFace lowSide = switch (riseFrom) {
            case NORTH, SOUTH, EAST, WEST -> riseFrom;
            default -> BlockFace.NORTH;
        };
        BlockFace highDirection = VillageStyle.opposite(lowSide);
        boolean alongZ = lowSide == BlockFace.NORTH
                || lowSide == BlockFace.SOUTH;
        int layers = alongZ
                ? volume.footprintDepth() + 2
                : volume.footprintWidth() + 2;
        int highY = roofY;

        /*
         * Pente à un demi-bloc par case : une rangée d'escalier est suivie
         * d'une rangée pleine au même niveau. Ce rythme donne un appentis moins
         * haut qu'un pignon tout en formant une membrane continue. L'ancienne
         * version ne posait que des escaliers isolés et ressemblait, vue de
         * côté, à plusieurs poutres flottantes.
         */
        for (int layer = 0; layer < layers; layer++) {
            int currentY = roofY + layer / 2;
            highY = currentY;
            boolean stairRow = layer % 2 == 0 && layer < layers - 1;

            if (alongZ) {
                int z = lowSide == BlockFace.NORTH
                        ? volume.minZ() - 1 + layer
                        : volume.maxZ() + 1 - layer;

                for (int x = volume.minX() - 1;
                     x <= volume.maxX() + 1;
                     x++) {
                    for (int supportY = roofY;
                         supportY <= currentY;
                         supportY++) {
                        place(
                                tasks,
                                sb,
                                x,
                                supportY,
                                z,
                                palette.roofBlock()
                        );
                    }
                    if (stairRow) {
                        stair(
                                tasks,
                                world,
                                sb,
                                x,
                                currentY,
                                z,
                                palette.roofStairs(),
                                highDirection
                        );
                    }
                }

                if (z >= volume.minZ() && z <= volume.maxZ()) {
                    fillShedColumn(
                            tasks,
                            sb,
                            volume.minX(),
                            z,
                            roofY,
                            currentY,
                            palette
                    );
                    fillShedColumn(
                            tasks,
                            sb,
                            volume.maxX(),
                            z,
                            roofY,
                            currentY,
                            palette
                    );
                }
            } else {
                int x = lowSide == BlockFace.WEST
                        ? volume.minX() - 1 + layer
                        : volume.maxX() + 1 - layer;

                for (int z = volume.minZ() - 1;
                     z <= volume.maxZ() + 1;
                     z++) {
                    for (int supportY = roofY;
                         supportY <= currentY;
                         supportY++) {
                        place(
                                tasks,
                                sb,
                                x,
                                supportY,
                                z,
                                palette.roofBlock()
                        );
                    }
                    if (stairRow) {
                        stair(
                                tasks,
                                world,
                                sb,
                                x,
                                currentY,
                                z,
                                palette.roofStairs(),
                                highDirection
                        );
                    }
                }

                if (x >= volume.minX() && x <= volume.maxX()) {
                    fillShedColumn(
                            tasks,
                            sb,
                            x,
                            volume.minZ(),
                            roofY,
                            currentY,
                            palette
                    );
                    fillShedColumn(
                            tasks,
                            sb,
                            x,
                            volume.maxZ(),
                            roofY,
                            currentY,
                            palette
                    );
                }
            }
        }

        /*
         * Le mur haut rejoint la sous-face sur toute sa largeur et porte un
         * couvre-joint en dalles. Aucune rangée terminale ne flotte.
         */
        if (alongZ) {
            int highZ = lowSide == BlockFace.NORTH
                    ? volume.maxZ()
                    : volume.minZ();
            int capZ = highZ + highDirection.getModZ();
            for (int x = volume.minX(); x <= volume.maxX(); x++) {
                fillShedColumn(
                        tasks,
                        sb,
                        x,
                        highZ,
                        roofY,
                        highY,
                        palette
                );
                slab(
                        tasks,
                        world,
                        sb,
                        x,
                        highY + 1,
                        capZ,
                        palette.roofSlab(),
                        Slab.Type.BOTTOM
                );
            }
        } else {
            int highX = lowSide == BlockFace.WEST
                    ? volume.maxX()
                    : volume.minX();
            int capX = highX + highDirection.getModX();
            for (int z = volume.minZ(); z <= volume.maxZ(); z++) {
                fillShedColumn(
                        tasks,
                        sb,
                        highX,
                        z,
                        roofY,
                        highY,
                        palette
                );
                slab(
                        tasks,
                        world,
                        sb,
                        capX,
                        highY + 1,
                        z,
                        palette.roofSlab(),
                        Slab.Type.BOTTOM
                );
            }
        }
    }

    private static void fillShedColumn(List<Runnable> tasks,
                                       TerrainManager.SetBlock sb,
                                       int x,
                                       int z,
                                       int fromY,
                                       int roofSurfaceY,
                                       VillageStyle.Palette palette) {
        for (int y = fromY; y < roofSurfaceY; y++) {
            Material material = y == roofSurfaceY - 1
                    ? palette.timber()
                    : palette.wallFill();
            place(tasks, sb, x, y, z, material);
        }
    }

    private static void buildWindowBox(List<Runnable> tasks,
                                       World world,
                                       TerrainManager.SetBlock sb,
                                       int x,
                                       int y,
                                       int z,
                                       BlockFace outward,
                                       VillageStyle.Palette palette,
                                       int seed) {
        int boxX = x + outward.getModX();
        int boxZ = z + outward.getModZ();

        // Appui extérieur sous la fenêtre, puis végétation au niveau du vitrage.
        slab(
                tasks,
                world,
                sb,
                boxX,
                y - 1,
                boxZ,
                palette.roofSlab(),
                Slab.Type.BOTTOM
        );
        place(
                tasks,
                sb,
                boxX,
                y,
                boxZ,
                seed % 2 == 0 ? Material.FERN : Material.POPPY
        );

        /*
         * Les volets sont placés un bloc devant la façade puis décalés sur les
         * côtés. Ils ne remplacent donc plus la maçonnerie, un montant de
         * colombage ou le second bloc de la baie verticale.
         */
        for (BlockFace side : List.of(
                VillageStyle.leftOf(outward),
                VillageStyle.rightOf(outward))) {
            int sx = x + outward.getModX() + side.getModX();
            int sz = z + outward.getModZ() + side.getModZ();

            for (int dy = 0; dy <= 1; dy++) {
                place(tasks, sb, sx, y + dy, sz, palette.shutter());
                if (world != null) {
                    trapdoor(
                            tasks,
                            world,
                            sx,
                            y + dy,
                            sz,
                            palette.shutter(),
                            outward,
                            true,
                            Bisected.Half.BOTTOM
                    );
                }
            }
        }
    }

    private static void addFlowerBox(List<Runnable> tasks,
                                     World world,
                                     TerrainManager.SetBlock sb,
                                     int x,
                                     int y,
                                     int z,
                                     BlockFace facing,
                                     VillageStyle.Palette palette,
                                     Material flower) {
        place(tasks, sb, x, y, z, palette.roofSlab());
        place(tasks, sb, x, y + 1, z, flower);
        if (world != null) {
            slab(tasks, world, sb, x, y, z, palette.roofSlab(), Slab.Type.BOTTOM);
        }
    }

    private static void addRoofEaves(List<Runnable> tasks,
                                     World world,
                                     TerrainManager.SetBlock sb,
                                     HouseVolume volume,
                                     int roofY,
                                     BlockFace facing,
                                     VillageStyle.Palette palette) {
        for (int x = volume.minX() - 1; x <= volume.maxX() + 1; x++) {
            slab(tasks, world, sb, x, roofY - 1, volume.minZ() - 1, palette.roofSlab(), Slab.Type.TOP);
            slab(tasks, world, sb, x, roofY - 1, volume.maxZ() + 1, palette.roofSlab(), Slab.Type.TOP);
        }
        for (int z = volume.minZ(); z <= volume.maxZ(); z++) {
            slab(tasks, world, sb, volume.minX() - 1, roofY - 1, z, palette.roofSlab(), Slab.Type.TOP);
            slab(tasks, world, sb, volume.maxX() + 1, roofY - 1, z, palette.roofSlab(), Slab.Type.TOP);
        }
    }

    private static void stitchVolumes(List<Runnable> tasks,
                                      TerrainManager.SetBlock sb,
                                      HouseVolume main,
                                      HouseVolume annex,
                                      int baseY,
                                      VillageStyle.Palette palette) {
        int overlapMinX = Math.max(main.minX(), annex.minX());
        int overlapMaxX = Math.min(main.maxX(), annex.maxX());
        int overlapMinZ = Math.max(main.minZ(), annex.minZ());
        int overlapMaxZ = Math.min(main.maxZ(), annex.maxZ());
        if (overlapMinX > overlapMaxX || overlapMinZ > overlapMaxZ) {
            return;
        }

        for (int x = overlapMinX; x <= overlapMaxX; x++) {
            for (int z = overlapMinZ; z <= overlapMaxZ; z++) {
                place(tasks, sb, x, baseY, z, palette.floor());
            }
        }

        // Ouvre une vraie communication dans le mur commun. Auparavant les
        // deux volumes se superposaient sans passage et formaient une masse de
        // murs/toits au centre de la maison.
        if (overlapMinX == overlapMaxX) {
            int centerZ = (overlapMinZ + overlapMaxZ) / 2;
            for (int z = Math.max(overlapMinZ + 1, centerZ - 1);
                 z <= Math.min(overlapMaxZ - 1, centerZ + 1);
                 z++) {
                for (int y = baseY + 1; y <= baseY + 3; y++) {
                    place(tasks, sb, overlapMinX, y, z, Material.AIR);
                }
            }
        } else if (overlapMinZ == overlapMaxZ) {
            int centerX = (overlapMinX + overlapMaxX) / 2;
            for (int x = Math.max(overlapMinX + 1, centerX - 1);
                 x <= Math.min(overlapMaxX - 1, centerX + 1);
                 x++) {
                for (int y = baseY + 1; y <= baseY + 3; y++) {
                    place(tasks, sb, x, y, overlapMinZ, Material.AIR);
                }
            }
        }
    }

    private static boolean framePattern(int x,
                                        int z,
                                        int y,
                                        int baseY,
                                        HouseVolume volume,
                                        int volumeIndex) {
        int localX = x - volume.minX();
        int localZ = z - volume.minZ();
        int relativeY = y - baseY;
        boolean northSouthWall = z == volume.minZ()
                || z == volume.maxZ();
        boolean eastWestWall = x == volume.minX()
                || x == volume.maxX();

        /*
         * Le bandeau d'étage dépend de la hauteur locale de la maison, et non
         * de l'altitude absolue du monde. Les montants restent réguliers ; les
         * fenêtres, calculées avant cette méthode, conservent toujours leur
         * baie complète.
         */
        boolean floorTie = relativeY == 4 && volume.wallHeight() >= 6;
        boolean northSouthPost = northSouthWall
                && localX > 0
                && localX < volume.footprintWidth() - 1
                && Math.floorMod(localX + volumeIndex, 3) == 0;
        boolean eastWestPost = eastWestWall
                && localZ > 0
                && localZ < volume.footprintDepth() - 1
                && Math.floorMod(localZ + volumeIndex, 3) == 0;

        return floorTie || northSouthPost || eastWestPost;
    }

    private static boolean perimeter(int x, int z, HouseVolume volume) {
        return x == volume.minX() || x == volume.maxX() || z == volume.minZ() || z == volume.maxZ();
    }

    private static boolean corner(int x, int z, HouseVolume volume) {
        return (x == volume.minX() || x == volume.maxX()) && (z == volume.minZ() || z == volume.maxZ());
    }

    private static boolean shouldWindow(int x,
                                        int y,
                                        int z,
                                        HouseVolume volume,
                                        int baseY,
                                        BlockFace facing,
                                        boolean frontVolume,
                                        boolean twoStory) {
        int relativeY = y - baseY;
        boolean groundWindowBand = relativeY == 2 || relativeY == 3;
        boolean upperWindowBand = twoStory && (relativeY == 5 || relativeY == 6);
        if (!groundWindowBand && !upperWindowBand) {
            return false;
        }

        boolean onNorthSouthWall = z == volume.minZ() || z == volume.maxZ();
        boolean onEastWestWall = x == volume.minX() || x == volume.maxX();
        if (!onNorthSouthWall && !onEastWestWall) {
            return false;
        }

        boolean frontFace = switch (facing) {
            case NORTH -> z == volume.minZ();
            case SOUTH -> z == volume.maxZ();
            case EAST -> x == volume.maxX();
            case WEST -> x == volume.minX();
            default -> false;
        };

        // Réserve une baie de trois blocs autour de la porte, y compris pour
        // les façades est/ouest qui n'étaient auparavant pas protégées.
        if (frontVolume && frontFace && relativeY <= 3) {
            int lateralDistance = facing == BlockFace.NORTH || facing == BlockFace.SOUTH
                    ? Math.abs(x - volume.centerX())
                    : Math.abs(z - volume.centerZ());
            if (lateralDistance <= 1) {
                return false;
            }
        }

        if (onNorthSouthWall) {
            return isWindowBay(
                    x - volume.minX(),
                    volume.footprintWidth()
            );
        }
        return isWindowBay(
                z - volume.minZ(),
                volume.footprintDepth()
        );
    }

    /**
     * Répartit des baies symétriques sans dépendre de la parité du bâtiment.
     */
    private static boolean isWindowBay(int localCoordinate, int length) {
        if (length < 5) {
            return false;
        }

        int first = 2;
        int last = length - 3;
        if (localCoordinate == first || localCoordinate == last) {
            return true;
        }

        return length >= 10 && localCoordinate == (length - 1) / 2;
    }

    private static BlockFace outward(int x, int z, HouseVolume volume) {
        if (z == volume.minZ()) return BlockFace.NORTH;
        if (z == volume.maxZ()) return BlockFace.SOUTH;
        if (x == volume.minX()) return BlockFace.WEST;
        return BlockFace.EAST;
    }

    private static HouseVolume annexFor(HouseVolume main, LotPlan lot) {
        if (!lot.hasWing()) {
            return null;
        }

        BlockFace wingSide = lot.wingSide();
        BlockFace front = lot.facing();
        int annexHeight = Math.max(3, main.wallHeight() - 1);

        if (front == BlockFace.NORTH || front == BlockFace.SOUTH) {
            int minX = wingSide == BlockFace.WEST
                    ? main.minX() - 3
                    : main.maxX();
            int minZ = front == BlockFace.NORTH
                    ? main.maxZ() - 4
                    : main.minZ();
            return new HouseVolume(minX, minZ, 4, 5, annexHeight, RoofStyle.SHED);
        }

        int minX = front == BlockFace.EAST
                ? main.minX()
                : main.maxX() - 4;
        int minZ = wingSide == BlockFace.NORTH
                ? main.minZ() - 3
                : main.maxZ();
        return new HouseVolume(minX, minZ, 5, 4, annexHeight, RoofStyle.SHED);
    }

    private static Point localPoint(int centerX,
                                    int centerZ,
                                    BlockFace front,
                                    int lateral,
                                    int forward) {
        BlockFace safeFront = front == BlockFace.NORTH
                || front == BlockFace.SOUTH
                || front == BlockFace.EAST
                || front == BlockFace.WEST
                ? front
                : BlockFace.SOUTH;
        BlockFace right = VillageStyle.rightOf(safeFront);
        return new Point(
                centerX + right.getModX() * lateral + safeFront.getModX() * forward,
                centerZ + right.getModZ() * lateral + safeFront.getModZ() * forward
        );
    }

    private static Point localPoint(HouseVolume volume,
                                    BlockFace front,
                                    int lateral,
                                    int forward) {
        return localPoint(
                volume.centerX(),
                volume.centerZ(),
                front,
                lateral,
                forward
        );
    }

    private static void placeLocal(List<Runnable> tasks,
                                   TerrainManager.SetBlock sb,
                                   int originX,
                                   int y,
                                   int originZ,
                                   BlockFace front,
                                   int lateral,
                                   int forward,
                                   Material material) {
        BlockFace right = VillageStyle.rightOf(front);
        int x = originX + right.getModX() * lateral + front.getModX() * forward;
        int z = originZ + right.getModZ() * lateral + front.getModZ() * forward;
        place(tasks, sb, x, y, z, material);
    }

    private static Material cropFor(List<Material> crops, Random random, int dx, int dz) {
        if (crops == null || crops.isEmpty()) {
            return Material.WHEAT;
        }
        Material seed = crops.get(Math.floorMod(dx * 7 + dz * 11 + random.nextInt(4), crops.size()));
        return switch (seed) {
            case WHEAT_SEEDS -> Material.WHEAT;
            case CARROT -> Material.CARROTS;
            case POTATO -> Material.POTATOES;
            case BEETROOT_SEEDS -> Material.BEETROOTS;
            default -> Material.WHEAT;
        };
    }

    private static Material mixedFoundation(VillageStyle.Palette palette, int x, int z) {
        return Math.floorMod(x * 31 + z * 17, 4) == 0 ? palette.foundationAccent() : palette.foundationPrimary();
    }

    private static void placeBed(List<Runnable> tasks,
                                 World world,
                                 TerrainManager.SetBlock sb,
                                 int x,
                                 int y,
                                 int z,
                                 Material bedMaterial,
                                 BlockFace facing) {
        int headX = x + facing.getModX();
        int headZ = z + facing.getModZ();
        place(tasks, sb, x, y, z, bedMaterial);
        place(tasks, sb, headX, y, headZ, bedMaterial);
        if (world != null) {
            tasks.add(() -> VillageStyle.setBed(world, x, y, z, bedMaterial, facing, Bed.Part.FOOT));
            tasks.add(() -> VillageStyle.setBed(world, headX, y, headZ, bedMaterial, facing, Bed.Part.HEAD));
        }
    }

    private static void gate(List<Runnable> tasks,
                             World world,
                             int x,
                             int y,
                             int z,
                             Material material,
                             BlockFace facing,
                             boolean open,
                             boolean inWall) {
        if (world != null) {
            tasks.add(() -> VillageStyle.setGate(world, x, y, z, material, facing, open, inWall));
        }
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
        if (world != null) {
            tasks.add(() -> VillageStyle.setSlab(world, x, y, z, material, type));
        }
    }

    private static void trapdoor(List<Runnable> tasks,
                                 World world,
                                 int x,
                                 int y,
                                 int z,
                                 Material material,
                                 BlockFace facing,
                                 boolean open,
                                 Bisected.Half half) {
        if (world != null) {
            tasks.add(() -> VillageStyle.setTrapdoor(world, x, y, z, material, facing, open, half));
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
        if (world != null) {
            tasks.add(() -> VillageStyle.setStair(world, x, y, z, material, facing, Stairs.Half.BOTTOM, Stairs.Shape.STRAIGHT));
        }
    }

    private static void place(List<Runnable> tasks, TerrainManager.SetBlock sb, int x, int y, int z, Material material) {
        tasks.add(() -> sb.set(x, y, z, material));
    }

    private record Point(int x, int z) {}

    private record HouseVolume(int minX, int minZ, int footprintWidth, int footprintDepth, int wallHeight, RoofStyle roofStyle) {
        int maxX() { return minX + footprintWidth - 1; }
        int maxZ() { return minZ + footprintDepth - 1; }
        int centerX() { return (minX + maxX()) / 2; }
        int centerZ() { return (minZ + maxZ()) / 2; }
    }
}
