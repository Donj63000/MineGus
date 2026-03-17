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

        Random random = rng != null ? rng : new Random();
        VillageStyle.Palette palette = VillageStyle.medievalPalette(spec.accentMaterial());

        HouseVolume main = new HouseVolume(
                lot.buildX(),
                lot.buildZ(),
                lot.footprintWidth(),
                lot.footprintDepth(),
                spec.wallHeight(),
                spec.roofStyle()
        );

        HouseVolume annex = annexFor(main, lot.facing(), spec);

        // 1) Base du terrain / soubassement.
        buildFoundationSkirt(tasks, sb, main, baseY, palette, Math.max(1, spec.foundationStep() + 1));
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
        buildYard(tasks, world, sb, lot, baseY, palette);
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
     * Petite ferme plus naturelle : bordures, outils, allées, canal d'irrigation,
     * scarecrow et variations de cultures.
     */
    public static List<Runnable> buildFarm(Location base, List<Material> crops, TerrainManager.SetBlock sb, Random rng) {
        List<Runnable> tasks = new ArrayList<>();
        Random random = rng != null ? rng : new Random();
        int ox = base.getBlockX();
        int oy = base.getBlockY();
        int oz = base.getBlockZ();
        int size = 11;
        int mid = size / 2;

        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                int x = ox + dx;
                int z = oz + dz;
                boolean edge = dx == 0 || dx == size - 1 || dz == 0 || dz == size - 1;
                boolean gate = dx == mid && dz == size - 1;

                if (edge) {
                    place(tasks, sb, x, oy, z, (dx + dz) % 2 == 0 ? Material.COARSE_DIRT : Material.PACKED_MUD);
                    if (!gate) {
                        place(tasks, sb, x, oy + 1, z, Material.OAK_FENCE);
                    }
                    continue;
                }

                if (dx == mid) {
                    place(tasks, sb, x, oy, z, Material.WATER);
                    if (dz != 1 && dz != size - 2) {
                        place(tasks, sb, x, oy + 1, z, Material.LILY_PAD);
                    }
                    continue;
                }

                if (dz == size - 2) {
                    place(tasks, sb, x, oy, z, Material.DIRT_PATH);
                    continue;
                }

                place(tasks, sb, x, oy, z, Material.FARMLAND);
                place(tasks, sb, x, oy + 1, z, cropFor(crops, random, dx, dz));
            }
        }

        // Portillon d'entrée.
        place(tasks, sb, ox + mid, oy + 1, oz + size - 1, Material.OAK_FENCE_GATE);

        // Coin outils / réserve.
        place(tasks, sb, ox + 1, oy + 1, oz + 1, Material.COMPOSTER);
        place(tasks, sb, ox + 2, oy + 1, oz + 1, Material.BARREL);
        place(tasks, sb, ox + 1, oy + 1, oz + 2, Material.HAY_BLOCK);
        place(tasks, sb, ox + 2, oy + 1, oz + 2, Material.CRAFTING_TABLE);

        // Petit scarecrow décoratif au centre des champs.
        place(tasks, sb, ox + mid - 2, oy + 1, oz + mid - 1, Material.OAK_FENCE);
        place(tasks, sb, ox + mid - 2, oy + 2, oz + mid - 1, Material.OAK_FENCE);
        place(tasks, sb, ox + mid - 3, oy + 2, oz + mid - 1, Material.OAK_FENCE);
        place(tasks, sb, ox + mid - 1, oy + 2, oz + mid - 1, Material.OAK_FENCE);
        place(tasks, sb, ox + mid - 2, oy + 3, oz + mid - 1, Material.HAY_BLOCK);
        place(tasks, sb, ox + mid - 2, oy + 4, oz + mid - 1, Material.CARVED_PUMPKIN);

        // Lanternes d'angle.
        for (int dx : new int[]{0, size - 1}) {
            for (int dz : new int[]{0, size - 1}) {
                int x = ox + dx;
                int z = oz + dz;
                place(tasks, sb, x, oy + 2, z, Material.LANTERN);
            }
        }
        return tasks;
    }

    /**
     * Enclos plus "vivant" avec sol moins plat, auge, meules de foin et petite
     * remise d'angle pour casser l'effet carré trop artificiel.
     */
    public static List<Runnable> buildPen(Plugin plugin, Location base, int villageId, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        int ox = base.getBlockX();
        int oy = base.getBlockY();
        int oz = base.getBlockZ();
        int size = 10;
        int gateX = ox + size / 2;

        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                int x = ox + dx;
                int z = oz + dz;
                boolean edge = dx == 0 || dx == size - 1 || dz == 0 || dz == size - 1;
                boolean gate = z == oz && x == gateX;

                Material ground = ((x + z) % 5 == 0) ? Material.COARSE_DIRT : ((x + z) % 3 == 0 ? Material.GRASS_BLOCK : Material.PACKED_MUD);
                place(tasks, sb, x, oy, z, edge ? Material.GRASS_BLOCK : ground);

                if (edge) {
                    place(tasks, sb, x, oy + 1, z, gate ? Material.OAK_FENCE_GATE : Material.OAK_FENCE);
                }
            }
        }

        // Abri de coin.
        int shedMinX = ox + size - 4;
        int shedMinZ = oz + size - 4;
        for (int x = shedMinX; x <= ox + size - 2; x++) {
            for (int z = shedMinZ; z <= oz + size - 2; z++) {
                place(tasks, sb, x, oy, z, Material.PACKED_MUD);
            }
        }
        for (int x : new int[]{shedMinX, ox + size - 2}) {
            for (int z : new int[]{shedMinZ, oz + size - 2}) {
                for (int y = oy + 1; y <= oy + 3; y++) {
                    place(tasks, sb, x, y, z, Material.STRIPPED_SPRUCE_LOG);
                }
            }
        }
        for (int x = shedMinX; x <= ox + size - 2; x++) {
            stair(tasks, base.getWorld(), sb, x, oy + 4, shedMinZ - 1, Material.SPRUCE_STAIRS, BlockFace.NORTH);
            stair(tasks, base.getWorld(), sb, x, oy + 4, oz + size - 1, Material.SPRUCE_STAIRS, BlockFace.SOUTH);
        }

        // Meules, auge et matériel.
        place(tasks, sb, ox + 1, oy + 1, oz + size - 2, Material.HAY_BLOCK);
        place(tasks, sb, ox + 2, oy + 1, oz + size - 2, Material.HAY_BLOCK);
        place(tasks, sb, ox + 1, oy + 1, oz + size - 3, Material.BARREL);
        place(tasks, sb, ox + 3, oy + 1, oz + 2, Material.WATER_CAULDRON);
        place(tasks, sb, ox + 4, oy + 1, oz + 2, Material.WATER_CAULDRON);
        place(tasks, sb, ox + 5, oy + 1, oz + 2, Material.CHEST);

        // Petite allée depuis la route.
        for (int z = oz - 2; z <= oz; z++) {
            place(tasks, sb, gateX, oy, z, Material.DIRT_PATH);
        }

        tasks.add(() -> {
            World world = base.getWorld();
            if (world == null) {
                return;
            }
            for (int i = 0; i < 3; i++) {
                var entity = world.spawnEntity(base.clone().add(2 + i, 1, 4), EntityType.SHEEP);
                VillageEntityManager.tagEntity(entity, plugin, villageId);
            }
        });

        return tasks;
    }

    /** Lampadaire plus détaillé, proche d'un build de joueur. */
    public static List<Runnable> buildLampPost(int x, int y, int z, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        place(tasks, sb, x, y - 1, z, Material.STONE_BRICKS);
        place(tasks, sb, x, y, z, Material.COBBLESTONE_WALL);
        place(tasks, sb, x, y + 1, z, Material.STRIPPED_SPRUCE_LOG);
        place(tasks, sb, x, y + 2, z, Material.STRIPPED_SPRUCE_LOG);
        place(tasks, sb, x, y + 3, z, Material.SPRUCE_FENCE);
        place(tasks, sb, x, y + 4, z, Material.CHAIN);
        place(tasks, sb, x, y + 5, z, Material.LANTERN);
        place(tasks, sb, x - 1, y + 3, z, Material.SPRUCE_TRAPDOOR);
        place(tasks, sb, x + 1, y + 3, z, Material.SPRUCE_TRAPDOOR);
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
                    boolean corner = corner(x, z, volume);
                    boolean lowerBand = y == baseY + 1;
                    boolean beamLine = y == baseY + volume.wallHeight();
                    if (corner || lowerBand || beamLine || framePattern(x, z, y, volume, volumeIndex)) {
                        place(tasks, sb, x, y, z, palette.timber());
                    } else if (shouldWindow(x, y, z, volume, baseY, facing, frontVolume, spec.twoStory())) {
                        place(tasks, sb, x, y, z, palette.window());
                        buildWindowBox(tasks, world, sb, x, y, z, outward(x, z, volume), palette, volumeIndex);
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
        int beamY = baseY + lot.houseSpec().wallHeight();
        BlockFace left = VillageStyle.leftOf(lot.facing());
        BlockFace right = VillageStyle.rightOf(lot.facing());
        BlockFace back = VillageStyle.opposite(lot.facing());

        // Poutre frontale.
        for (int i = -2; i <= 2; i++) {
            int bx = lot.doorX() + left.getModX() * i;
            int bz = lot.doorZ() + left.getModZ() * i;
            if (bx >= main.minX() && bx <= main.maxX() && bz >= main.minZ() && bz <= main.maxZ()) {
                place(tasks, sb, bx, beamY, bz, palette.timber());
            }
        }

        // Petite enseigne / bannière latérale pour casser les façades répétitives.
        int signX = lot.frontStepX() + left.getModX() * 2;
        int signZ = lot.frontStepZ() + left.getModZ() * 2;
        place(tasks, sb, signX, baseY + 2, signZ, lot.houseSpec().facadeVariant() % 2 == 0 ? Material.RED_BANNER : Material.YELLOW_BANNER);

        // Petite lumière chaude sur la porte.
        place(tasks, sb, lot.frontStepX(), baseY + 2, lot.frontStepZ(), Material.LANTERN);
        place(tasks, sb, lot.frontStepX(), baseY + 3, lot.frontStepZ(), Material.CHAIN);

        // Deux jardinières sous la façade si possible.
        int frontWindowLeftX = lot.doorX() + left.getModX() * 2;
        int frontWindowLeftZ = lot.doorZ() + left.getModZ() * 2;
        int frontWindowRightX = lot.doorX() + right.getModX() * 2;
        int frontWindowRightZ = lot.doorZ() + right.getModZ() * 2;
        addFlowerBox(tasks, world, sb, frontWindowLeftX, baseY + 1, frontWindowLeftZ, back, palette, Material.POPPY);
        addFlowerBox(tasks, world, sb, frontWindowRightX, baseY + 1, frontWindowRightZ, back, palette, Material.BLUE_ORCHID);
    }

    private static void buildInterior(List<Runnable> tasks,
                                      World world,
                                      TerrainManager.SetBlock sb,
                                      LotPlan lot,
                                      HouseVolume main,
                                      HouseVolume annex,
                                      int baseY,
                                      VillageStyle.Palette palette) {
        BlockFace left = VillageStyle.leftOf(lot.facing());
        BlockFace right = VillageStyle.rightOf(lot.facing());
        BlockFace back = VillageStyle.opposite(lot.facing());

        int cx = main.centerX();
        int cz = main.centerZ();

        // Tapis central.
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                place(tasks, sb, x, baseY + 1, z, lot.houseSpec().facadeVariant() % 2 == 0 ? Material.RED_CARPET : Material.GRAY_CARPET);
            }
        }

        // Table centrale + 2 chaises.
        place(tasks, sb, cx, baseY + 1, cz, Material.SPRUCE_FENCE);
        place(tasks, sb, cx, baseY + 2, cz, Material.SPRUCE_PRESSURE_PLATE);
        stair(tasks, world, sb,
                cx + left.getModX(),
                baseY + 1,
                cz + left.getModZ(),
                Material.SPRUCE_STAIRS,
                VillageStyle.rightOf(lot.facing()));
        stair(tasks, world, sb,
                cx + right.getModX(),
                baseY + 1,
                cz + right.getModZ(),
                Material.SPRUCE_STAIRS,
                VillageStyle.leftOf(lot.facing()));

        // Coin lit au fond de la maison.
        int bedFootX = main.centerX() + left.getModX() * 2 + back.getModX();
        int bedFootZ = main.centerZ() + left.getModZ() * 2 + back.getModZ();
        placeBed(tasks, world, sb, bedFootX, baseY + 1, bedFootZ, Material.RED_BED, back);

        // Rangements près de l'entrée.
        int storageX = main.minX() + 1;
        int storageZ = main.minZ() + 1;
        place(tasks, sb, storageX, baseY + 1, storageZ, Material.BARREL);
        place(tasks, sb, storageX + 1, baseY + 1, storageZ, Material.CHEST);

        // Éclairage chaud suspendu.
        place(tasks, sb, cx, baseY + 4, cz, Material.CHAIN);
        place(tasks, sb, cx, baseY + 3, cz, Material.LANTERN);

        // Mur fonctionnel selon le type d'intérieur.
        switch (lot.houseSpec().interiorVariant()) {
            case 0 -> {
                int kx = main.maxX() - 1;
                int kz = main.minZ() + 1;
                place(tasks, sb, kx, baseY + 1, kz, Material.FURNACE);
                place(tasks, sb, kx - 1, baseY + 1, kz, Material.SMOKER);
                place(tasks, sb, kx - 2, baseY + 1, kz, Material.BARREL);
                place(tasks, sb, kx - 3, baseY + 1, kz, Material.CAULDRON);
                if (world != null) {
                    tasks.add(() -> VillageStyle.setDirectional(world, kx, baseY + 1, kz, Material.FURNACE, lot.facing()));
                    tasks.add(() -> VillageStyle.setDirectional(world, kx - 1, baseY + 1, kz, Material.SMOKER, lot.facing()));
                }
            }
            case 1 -> {
                int wx = main.maxX() - 1;
                int wz = main.minZ() + 1;
                place(tasks, sb, wx, baseY + 1, wz, Material.SMITHING_TABLE);
                place(tasks, sb, wx - 1, baseY + 1, wz, Material.ANVIL);
                place(tasks, sb, wx - 2, baseY + 1, wz, Material.GRINDSTONE);
                place(tasks, sb, wx - 3, baseY + 1, wz, Material.BARREL);
            }
            case 2 -> {
                int lx = main.maxX() - 1;
                int lz = main.minZ() + 1;
                place(tasks, sb, lx, baseY + 1, lz, Material.BOOKSHELF);
                place(tasks, sb, lx - 1, baseY + 1, lz, Material.BOOKSHELF);
                place(tasks, sb, lx - 2, baseY + 1, lz, Material.LECTERN);
                place(tasks, sb, lx - 3, baseY + 1, lz, Material.BARREL);
            }
            default -> {
                int fx = main.maxX() - 2;
                int fz = main.minZ() + 1;
                place(tasks, sb, fx, baseY + 1, fz, Material.CAMPFIRE);
                place(tasks, sb, fx - 1, baseY + 1, fz, Material.BARREL);
                place(tasks, sb, fx + 1, baseY + 1, fz, Material.BARREL);
            }
        }

        // Petit bureau ou coin atelier dans l'annexe.
        if (annex != null) {
            int ax = annex.centerX();
            int az = annex.centerZ();
            place(tasks, sb, ax, baseY + 1, az, Material.CRAFTING_TABLE);
            place(tasks, sb, ax + 1, baseY + 1, az, Material.BARREL);
            place(tasks, sb, ax - 1, baseY + 1, az, Material.OAK_SLAB);
        }
    }

    private static void buildArchetypeAccent(List<Runnable> tasks,
                                             World world,
                                             TerrainManager.SetBlock sb,
                                             LotPlan lot,
                                             HouseVolume main,
                                             int baseY,
                                             VillageStyle.Palette palette) {
        int frontX = lot.frontStepX() + lot.facing().getModX() * 2;
        int frontZ = lot.frontStepZ() + lot.facing().getModZ() * 2;
        switch (lot.houseSpec().archetype()) {
            case COTTAGE -> {
                place(tasks, sb, frontX + 1, baseY + 1, frontZ, Material.FLOWER_POT);
                place(tasks, sb, frontX - 1, baseY + 1, frontZ, Material.FLOWER_POT);
            }
            case TOWNHOUSE -> {
                place(tasks, sb, frontX, baseY + 1, frontZ, Material.BARREL);
                place(tasks, sb, frontX, baseY + 2, frontZ, Material.LANTERN);
            }
            case FAMILY_HOUSE -> {
                place(tasks, sb, main.maxX(), baseY + 1, main.maxZ() - 2, Material.BARREL);
                place(tasks, sb, main.maxX() - 1, baseY + 1, main.maxZ() - 2, Material.HAY_BLOCK);
            }
            case WORKSHOP_HOUSE -> {
                place(tasks, sb, frontX, baseY, frontZ, Material.GRAVEL);
                place(tasks, sb, frontX, baseY + 1, frontZ, Material.BARREL);
                place(tasks, sb, frontX + 1, baseY + 1, frontZ, Material.CHEST);
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

        BlockFace left = VillageStyle.leftOf(lot.facing());
        BlockFace right = VillageStyle.rightOf(lot.facing());
        int px = lot.frontStepX();
        int pz = lot.frontStepZ();

        // Sol du porche.
        place(tasks, sb, px, baseY, pz, palette.floor());
        place(tasks, sb, px + left.getModX(), baseY, pz + left.getModZ(), palette.floor());
        place(tasks, sb, px + right.getModX(), baseY, pz + right.getModZ(), palette.floor());

        // Garde-corps.
        place(tasks, sb, px + left.getModX(), baseY + 1, pz + left.getModZ(), palette.fence());
        place(tasks, sb, px + right.getModX(), baseY + 1, pz + right.getModZ(), palette.fence());

        // Poteaux et petit auvent.
        for (int dy = 1; dy <= 3; dy++) {
            place(tasks, sb, px + left.getModX(), baseY + dy, pz + left.getModZ(), palette.timber());
            place(tasks, sb, px + right.getModX(), baseY + dy, pz + right.getModZ(), palette.timber());
        }
        stair(tasks, world, sb, px, baseY + 3, pz, palette.awning(), VillageStyle.opposite(lot.facing()));
        place(tasks, sb, px, baseY + 2, pz, Material.LANTERN);
    }

    private static void buildYard(List<Runnable> tasks,
                                  World world,
                                  TerrainManager.SetBlock sb,
                                  LotPlan lot,
                                  int baseY,
                                  VillageStyle.Palette palette) {
        int depth = Math.max(3, lot.yardDepth());
        BlockFace front = lot.facing();
        BlockFace left = VillageStyle.leftOf(front);
        BlockFace right = VillageStyle.rightOf(front);
        int startX = lot.frontStepX() + front.getModX();
        int startZ = lot.frontStepZ() + front.getModZ();
        Material gateMaterial = VillageStyle.fenceGateFrom(lot.houseSpec().accentMaterial());

        for (int step = 0; step < depth; step++) {
            int cx = startX + front.getModX() * step;
            int cz = startZ + front.getModZ() * step;

            // Petite allée centrale.
            place(tasks, sb, cx, baseY, cz, step == 0 ? palette.paving() : yardMaterial(lot.houseSpec().yardStyle(), step));

            // Largeur utile du jardin : 3 blocs.
            int lx = cx + left.getModX();
            int lz = cz + left.getModZ();
            int rx = cx + right.getModX();
            int rz = cz + right.getModZ();
            place(tasks, sb, lx, baseY, lz, sideYardMaterial(lot.houseSpec().yardStyle(), step, true));
            place(tasks, sb, rx, baseY, rz, sideYardMaterial(lot.houseSpec().yardStyle(), step, false));

            if (step == depth - 1) {
                // Clôture basse du jardin avec petit portillon.
                place(tasks, sb, lx, baseY + 1, lz, palette.fence());
                place(tasks, sb, rx, baseY + 1, rz, palette.fence());
                place(tasks, sb, cx, baseY + 1, cz, gateMaterial);
                if (world != null) {
                    gate(tasks, world, cx, baseY + 1, cz, gateMaterial, front, false, false);
                }
            }
        }

        // Détails selon le type de cour.
        switch (lot.houseSpec().yardStyle()) {
            case FLOWERS -> {
                place(tasks, sb, startX + left.getModX() * 2, baseY + 1, startZ + left.getModZ(), Material.POPPY);
                place(tasks, sb, startX + right.getModX() * 2, baseY + 1, startZ + right.getModZ(), Material.ALLIUM);
                place(tasks, sb, startX + left.getModX() * 2, baseY + 1, startZ + left.getModZ() * 2, Material.BLUE_ORCHID);
            }
            case WOODPILE -> {
                place(tasks, sb, startX + left.getModX() * 2, baseY, startZ + left.getModZ() * 2, Material.OAK_LOG);
                place(tasks, sb, startX + left.getModX() * 2, baseY + 1, startZ + left.getModZ() * 2, Material.OAK_LOG);
                place(tasks, sb, startX + right.getModX() * 2, baseY + 1, startZ + right.getModZ() * 2, Material.BARREL);
            }
            case FENCED -> {
                place(tasks, sb, startX + left.getModX() * 2, baseY + 1, startZ + left.getModZ() * 2, palette.fence());
                place(tasks, sb, startX + right.getModX() * 2, baseY + 1, startZ + right.getModZ() * 2, palette.fence());
                place(tasks, sb, startX, baseY + 1, startZ + front.getModZ() * 2, Material.LANTERN);
            }
            case KITCHEN_GARDEN -> {
                place(tasks, sb, startX + left.getModX() * 2, baseY, startZ + left.getModZ() * 2, Material.FARMLAND);
                place(tasks, sb, startX + left.getModX() * 2, baseY + 1, startZ + left.getModZ() * 2, Material.CARROTS);
                place(tasks, sb, startX + right.getModX() * 2, baseY, startZ + right.getModZ() * 2, Material.FARMLAND);
                place(tasks, sb, startX + right.getModX() * 2, baseY + 1, startZ + right.getModZ() * 2, Material.POTATOES);
                place(tasks, sb, startX, baseY + 1, startZ + front.getModZ() * 2, Material.COMPOSTER);
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
        for (int x = main.minX() + 1; x <= main.maxX() - 1; x++) {
            for (int z = main.minZ() + 1; z <= main.maxZ() - 1; z++) {
                place(tasks, sb, x, floorY, z, palette.floor());
            }
        }

        // Escalier simple mais lisible, orienté selon la façade.
        int startX;
        int startZ;
        BlockFace stairFacing;
        switch (lot.facing()) {
            case NORTH -> {
                startX = main.maxX() - 1;
                startZ = main.maxZ() - 2;
                stairFacing = BlockFace.NORTH;
            }
            case SOUTH -> {
                startX = main.minX() + 1;
                startZ = main.minZ() + 1;
                stairFacing = BlockFace.SOUTH;
            }
            case EAST -> {
                startX = main.minX() + 1;
                startZ = main.maxZ() - 2;
                stairFacing = BlockFace.EAST;
            }
            case WEST -> {
                startX = main.maxX() - 1;
                startZ = main.minZ() + 1;
                stairFacing = BlockFace.WEST;
            }
            default -> {
                startX = main.minX() + 1;
                startZ = main.minZ() + 1;
                stairFacing = BlockFace.SOUTH;
            }
        }

        for (int step = 0; step < 4; step++) {
            int sx = startX + stairFacing.getModX() * step;
            int sz = startZ + stairFacing.getModZ() * step;
            stair(tasks, world, sb, sx, baseY + 1 + step, sz, Material.SPRUCE_STAIRS, stairFacing);
        }

        // Petit palier.
        place(tasks, sb,
                startX + stairFacing.getModX() * 4,
                baseY + 5,
                startZ + stairFacing.getModZ() * 4,
                palette.floor());
    }

    private static void buildDormer(List<Runnable> tasks,
                                    World world,
                                    TerrainManager.SetBlock sb,
                                    LotPlan lot,
                                    HouseVolume main,
                                    int baseY,
                                    VillageStyle.Palette palette) {
        int roofBaseY = baseY + main.wallHeight() + 2;
        int x = main.centerX();
        int z = switch (lot.facing()) {
            case NORTH -> main.minZ() - 1;
            case SOUTH -> main.maxZ() + 1;
            default -> main.centerZ();
        };

        place(tasks, sb, x, roofBaseY, z, palette.window());
        stair(tasks, world, sb, x, roofBaseY + 1, z, palette.roofStairs(), VillageStyle.opposite(lot.facing()));
        slab(tasks, world, sb, x, roofBaseY + 2, z, palette.roofSlab(), Slab.Type.TOP);
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
            case SHED -> shedRoof(tasks, world, sb, volume, roofY, VillageStyle.opposite(facing), palette);
            case OFFSET_GABLE -> {
                gableRoof(tasks, world, sb, volume, roofY, facing, palette);
                int x = volume.centerX();
                int z = facing == BlockFace.NORTH ? volume.minZ() - 1 : facing == BlockFace.SOUTH ? volume.maxZ() + 1 : volume.centerZ();
                place(tasks, sb, x, roofY + 2, z, palette.window());
                stair(tasks, world, sb, x, roofY + 3, z, palette.roofStairs(), VillageStyle.opposite(facing));
            }
            case GABLE -> gableRoof(tasks, world, sb, volume, roofY, facing, palette);
        }
    }

    private static void gableRoof(List<Runnable> tasks,
                                  World world,
                                  TerrainManager.SetBlock sb,
                                  HouseVolume volume,
                                  int roofY,
                                  BlockFace facing,
                                  VillageStyle.Palette palette) {
        boolean ridgeAlongX = facing == BlockFace.NORTH || facing == BlockFace.SOUTH;
        int layers = ridgeAlongX ? (volume.footprintDepth() + 1) / 2 : (volume.footprintWidth() + 1) / 2;
        for (int layer = 0; layer < layers; layer++) {
            int y = roofY + layer;
            if (ridgeAlongX) {
                int lowZ = volume.minZ() - 1 + layer;
                int highZ = volume.maxZ() + 1 - layer;
                for (int x = volume.minX() - 1; x <= volume.maxX() + 1; x++) {
                    stair(tasks, world, sb, x, y, lowZ, palette.roofStairs(), BlockFace.NORTH);
                    stair(tasks, world, sb, x, y, highZ, palette.roofStairs(), BlockFace.SOUTH);
                }
            } else {
                int lowX = volume.minX() - 1 + layer;
                int highX = volume.maxX() + 1 - layer;
                for (int z = volume.minZ() - 1; z <= volume.maxZ() + 1; z++) {
                    stair(tasks, world, sb, lowX, y, z, palette.roofStairs(), BlockFace.WEST);
                    stair(tasks, world, sb, highX, y, z, palette.roofStairs(), BlockFace.EAST);
                }
            }
        }

        if (ridgeAlongX) {
            int ridgeZ = volume.centerZ();
            for (int x = volume.minX() - 1; x <= volume.maxX() + 1; x++) {
                slab(tasks, world, sb, x, roofY + layers, ridgeZ, palette.roofSlab(), Slab.Type.TOP);
            }
        } else {
            int ridgeX = volume.centerX();
            for (int z = volume.minZ() - 1; z <= volume.maxZ() + 1; z++) {
                slab(tasks, world, sb, ridgeX, roofY + layers, z, palette.roofSlab(), Slab.Type.TOP);
            }
        }

        // Pignons pour éviter l'effet "boîte ouverte".
        fillGable(tasks, sb, volume, roofY, facing, palette);
    }

    private static void hipRoof(List<Runnable> tasks,
                                World world,
                                TerrainManager.SetBlock sb,
                                HouseVolume volume,
                                int roofY,
                                VillageStyle.Palette palette) {
        int layers = Math.min(volume.footprintWidth(), volume.footprintDepth()) / 2 + 1;
        for (int layer = 0; layer < layers; layer++) {
            int minX = volume.minX() - 1 + layer;
            int maxX = volume.maxX() + 1 - layer;
            int minZ = volume.minZ() - 1 + layer;
            int maxZ = volume.maxZ() + 1 - layer;
            int y = roofY + layer;
            for (int x = minX; x <= maxX; x++) {
                stair(tasks, world, sb, x, y, minZ, palette.roofStairs(), BlockFace.NORTH);
                stair(tasks, world, sb, x, y, maxZ, palette.roofStairs(), BlockFace.SOUTH);
            }
            for (int z = minZ + 1; z <= maxZ - 1; z++) {
                stair(tasks, world, sb, minX, y, z, palette.roofStairs(), BlockFace.WEST);
                stair(tasks, world, sb, maxX, y, z, palette.roofStairs(), BlockFace.EAST);
            }
        }
        place(tasks, sb, volume.centerX(), roofY + layers, volume.centerZ(), palette.roofBlock());
    }

    private static void shedRoof(List<Runnable> tasks,
                                 World world,
                                 TerrainManager.SetBlock sb,
                                 HouseVolume volume,
                                 int roofY,
                                 BlockFace riseFrom,
                                 VillageStyle.Palette palette) {
        boolean alongZ = riseFrom == BlockFace.NORTH || riseFrom == BlockFace.SOUTH;
        int layers = alongZ ? volume.footprintDepth() + 1 : volume.footprintWidth() + 1;
        for (int layer = 0; layer < layers; layer++) {
            int y = roofY + layer / 2;
            if (alongZ) {
                int z = riseFrom == BlockFace.NORTH ? volume.minZ() - 1 + layer : volume.maxZ() + 1 - layer;
                for (int x = volume.minX() - 1; x <= volume.maxX() + 1; x++) {
                    stair(tasks, world, sb, x, y, z, palette.roofStairs(), riseFrom);
                }
            } else {
                int x = riseFrom == BlockFace.WEST ? volume.minX() - 1 + layer : volume.maxX() + 1 - layer;
                for (int z = volume.minZ() - 1; z <= volume.maxZ() + 1; z++) {
                    stair(tasks, world, sb, x, y, z, palette.roofStairs(), riseFrom);
                }
            }
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
        place(tasks, sb, boxX, y - 1, boxZ, palette.roofSlab());
        addFlowerBox(tasks, world, sb, boxX, y, boxZ, outward, palette, seed % 2 == 0 ? Material.FERN : Material.POPPY);
        for (BlockFace side : List.of(VillageStyle.leftOf(outward), VillageStyle.rightOf(outward))) {
            int sx = x + side.getModX();
            int sz = z + side.getModZ();
            place(tasks, sb, sx, y, sz, palette.shutter());
            if (world != null) {
                trapdoor(tasks, world, sx, y, sz, palette.shutter(), outward, true, Bisected.Half.BOTTOM);
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

    private static void fillGable(List<Runnable> tasks,
                                  TerrainManager.SetBlock sb,
                                  HouseVolume volume,
                                  int roofY,
                                  BlockFace facing,
                                  VillageStyle.Palette palette) {
        if (facing != BlockFace.NORTH && facing != BlockFace.SOUTH) {
            for (int z = volume.minZ(); z <= volume.maxZ(); z++) {
                place(tasks, sb, volume.minX(), roofY, z, palette.timber());
                place(tasks, sb, volume.maxX(), roofY, z, palette.timber());
            }
            return;
        }
        for (int x = volume.minX(); x <= volume.maxX(); x++) {
            place(tasks, sb, x, roofY, volume.minZ(), palette.timber());
            place(tasks, sb, x, roofY, volume.maxZ(), palette.timber());
        }
    }

    private static void stitchVolumes(List<Runnable> tasks,
                                      TerrainManager.SetBlock sb,
                                      HouseVolume a,
                                      HouseVolume b,
                                      int baseY,
                                      VillageStyle.Palette palette) {
        int minX = Math.max(a.minX(), b.minX());
        int maxX = Math.min(a.maxX(), b.maxX());
        int minZ = Math.max(a.minZ(), b.minZ());
        int maxZ = Math.min(a.maxZ(), b.maxZ());
        if (minX > maxX || minZ > maxZ) {
            return;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                place(tasks, sb, x, baseY, z, palette.floor());
            }
        }
    }

    private static boolean framePattern(int x, int z, int y, HouseVolume volume, int volumeIndex) {
        int localX = x - volume.minX();
        int localZ = z - volume.minZ();
        return ((localX + volumeIndex) % 3 == 0 && (z == volume.minZ() || z == volume.maxZ()))
                || ((localZ + volumeIndex) % 3 == 0 && (x == volume.minX() || x == volume.maxX()))
                || ((y - 1) % 4 == 0 && !corner(x, z, volume));
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

        if (z == volume.minZ() || z == volume.maxZ()) {
            if (frontVolume && z == frontZ(volume, facing) && Math.abs(x - volume.centerX()) <= 1 && relativeY <= 3) {
                return false;
            }
            return x > volume.minX() + 1 && x < volume.maxX() - 1 && (x - volume.minX()) % 2 == 0;
        }
        if (x == volume.minX() || x == volume.maxX()) {
            return z > volume.minZ() + 1 && z < volume.maxZ() - 1 && (z - volume.minZ()) % 2 == 0;
        }
        return false;
    }

    private static int frontZ(HouseVolume volume, BlockFace facing) {
        return facing == BlockFace.NORTH ? volume.minZ() : volume.maxZ();
    }

    private static BlockFace outward(int x, int z, HouseVolume volume) {
        if (z == volume.minZ()) return BlockFace.NORTH;
        if (z == volume.maxZ()) return BlockFace.SOUTH;
        if (x == volume.minX()) return BlockFace.WEST;
        return BlockFace.EAST;
    }

    private static HouseVolume annexFor(HouseVolume main, BlockFace facing, HouseSpec spec) {
        if (spec.archetype() == HouseArchetype.COTTAGE || spec.archetype() == HouseArchetype.TOWNHOUSE) {
            return null;
        }
        return switch (facing) {
            case NORTH, SOUTH -> new HouseVolume(main.maxX() - 3, main.minZ() + 2, 4, 4,
                    Math.max(3, main.wallHeight() - 1), RoofStyle.SHED);
            case EAST, WEST -> new HouseVolume(main.minX() + 2, main.maxZ() - 3, 4, 4,
                    Math.max(3, main.wallHeight() - 1), RoofStyle.SHED);
            default -> new HouseVolume(main.maxX() - 3, main.minZ() + 2, 4, 4,
                    Math.max(3, main.wallHeight() - 1), RoofStyle.SHED);
        };
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

    private record HouseVolume(int minX, int minZ, int footprintWidth, int footprintDepth, int wallHeight, RoofStyle roofStyle) {
        int maxX() { return minX + footprintWidth - 1; }
        int maxZ() { return minZ + footprintDepth - 1; }
        int centerX() { return (minX + maxX()) / 2; }
        int centerZ() { return (minZ + maxZ()) / 2; }
    }
}
