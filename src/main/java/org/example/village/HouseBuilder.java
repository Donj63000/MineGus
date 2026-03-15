package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.EntityType;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.example.village.VillageLayoutPlan.HouseArchetype;
import static org.example.village.VillageLayoutPlan.HouseSpec;
import static org.example.village.VillageLayoutPlan.LotPlan;
import static org.example.village.VillageLayoutPlan.RoofStyle;

/**
 * Generateur de maisons medievales et petits lots annexes.
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
        HouseVolume main = new HouseVolume(lot.buildX(), lot.buildZ(), lot.footprintWidth(), lot.footprintDepth(), spec.wallHeight(), spec.roofStyle());
        buildFoundationSkirt(tasks, sb, main, baseY, palette, spec.foundationStep());
        buildVolume(tasks, world, sb, main, baseY, lot.facing(), palette, true);
        if (spec.archetype() == HouseArchetype.FAMILY_HOUSE) {
            buildVolume(tasks, world, sb, annexFor(main, lot.facing()), baseY, lot.facing(), palette, false);
        }
        buildDoor(tasks, world, sb, lot, baseY, palette);
        buildFacade(tasks, world, sb, lot, baseY, palette);
        buildPorch(tasks, world, sb, lot, baseY, palette);
        buildInterior(tasks, world, sb, lot, main, baseY, palette);
        buildArchetypeAccent(tasks, world, sb, lot, baseY, palette);
        buildYard(tasks, world, sb, lot, baseY, palette);
        buildChimney(tasks, sb, lot, main, baseY);
        if (spec.twoStory()) {
            buildSecondFloor(tasks, world, sb, lot, main, baseY, palette);
        }
        if (spec.hasDormer()) {
            buildDormer(tasks, world, sb, lot, main, baseY, palette);
        }
        return tasks;
    }

    public static List<Runnable> buildFarm(Location base, List<Material> crops, TerrainManager.SetBlock sb, Random rng) {
        List<Runnable> tasks = new ArrayList<>();
        Random random = rng != null ? rng : new Random();
        int ox = base.getBlockX();
        int oy = base.getBlockY();
        int oz = base.getBlockZ();
        for (int dx = 0; dx < 9; dx++) {
            for (int dz = 0; dz < 9; dz++) {
                int x = ox + dx;
                int z = oz + dz;
                boolean edge = dx == 0 || dx == 8 || dz == 0 || dz == 8;
                if (edge) {
                    place(tasks, sb, x, oy, z, (dx + dz) % 2 == 0 ? Material.OAK_LOG : Material.COBBLESTONE);
                    if ((dx == 0 || dx == 8) && (dz == 0 || dz == 8)) {
                        place(tasks, sb, x, oy + 1, z, Material.OAK_FENCE);
                        place(tasks, sb, x, oy + 2, z, Material.LANTERN);
                    }
                } else if (dx == 4 && dz == 4) {
                    place(tasks, sb, x, oy, z, Material.WATER);
                    place(tasks, sb, x, oy + 1, z, Material.LILY_PAD);
                } else {
                    place(tasks, sb, x, oy, z, Material.FARMLAND);
                    place(tasks, sb, x, oy + 1, z, cropFor(crops, random));
                }
            }
        }
        return tasks;
    }

    public static List<Runnable> buildPen(Plugin plugin, Location base, int villageId, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        int ox = base.getBlockX();
        int oy = base.getBlockY();
        int oz = base.getBlockZ();
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                int x = ox + dx;
                int z = oz + dz;
                boolean edge = dx == 0 || dx == 7 || dz == 0 || dz == 7;
                place(tasks, sb, x, oy, z, Material.GRASS_BLOCK);
                if (edge) {
                    place(tasks, sb, x, oy + 1, z, (dx == 3 && dz == 0) ? Material.OAK_FENCE_GATE : Material.OAK_FENCE);
                }
            }
        }
        place(tasks, sb, ox + 1, oy + 1, oz + 1, Material.HAY_BLOCK);
        place(tasks, sb, ox + 6, oy + 1, oz + 5, Material.HAY_BLOCK);
        place(tasks, sb, ox + 4, oy + 1, oz + 2, Material.WATER_CAULDRON);
        tasks.add(() -> {
            World world = base.getWorld();
            if (world == null) {
                return;
            }
            for (int i = 0; i < 3; i++) {
                var entity = world.spawnEntity(base.clone().add(2 + i, 1, 3), EntityType.SHEEP);
                entity.setMetadata(VillageEntityManager.TAG, new FixedMetadataValue(plugin, villageId));
            }
        });
        return tasks;
    }

    public static List<Runnable> buildLampPost(int x, int y, int z, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        place(tasks, sb, x, y, z, Material.STONE_BRICKS);
        place(tasks, sb, x, y + 1, z, Material.COBBLESTONE_WALL);
        place(tasks, sb, x, y + 2, z, Material.CHAIN);
        place(tasks, sb, x, y + 3, z, Material.LANTERN);
        return tasks;
    }

    private static void buildVolume(List<Runnable> tasks,
                                    World world,
                                    TerrainManager.SetBlock sb,
                                    HouseVolume volume,
                                    int baseY,
                                    BlockFace facing,
                                    VillageStyle.Palette palette,
                                    boolean doorFace) {
        for (int x = volume.minX(); x <= volume.maxX(); x++) {
            for (int z = volume.minZ(); z <= volume.maxZ(); z++) {
                place(tasks, sb, x, baseY - 1, z, perimeter(x, z, volume) ? palette.foundationPrimary() : palette.foundationAccent());
                place(tasks, sb, x, baseY, z, palette.floor());
            }
        }

        for (int y = baseY + 1; y <= baseY + volume.wallHeight(); y++) {
            for (int x = volume.minX(); x <= volume.maxX(); x++) {
                for (int z = volume.minZ(); z <= volume.maxZ(); z++) {
                    if (!perimeter(x, z, volume)) {
                        continue;
                    }
                    boolean corner = corner(x, z, volume);
                    if (corner || ((x + z + y) % 4 == 0)) {
                        place(tasks, sb, x, y, z, palette.timber());
                    } else if (shouldWindow(x, y, z, volume, baseY, facing, doorFace)) {
                        place(tasks, sb, x, y, z, palette.window());
                        place(tasks, sb, x, y - 1, z, palette.roofSlab());
                        addShutters(tasks, world, sb, x, y, z, outward(x, z, volume), palette);
                    } else {
                        place(tasks, sb, x, y, z, palette.wallFill());
                    }
                }
            }
        }

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
        place(tasks, sb, doorX, baseY + 1, doorZ, palette.door());
        place(tasks, sb, doorX, baseY + 2, doorZ, palette.door());
        if (world != null) {
            tasks.add(() -> VillageStyle.setDoor(world, doorX, baseY + 1, doorZ, palette.door(), lot.facing(), Bisected.Half.BOTTOM));
            tasks.add(() -> VillageStyle.setDoor(world, doorX, baseY + 2, doorZ, palette.door(), lot.facing(), Bisected.Half.TOP));
        }
        place(tasks, sb, lot.frontStepX(), baseY, lot.frontStepZ(), palette.paving());
        place(tasks, sb, lot.frontStepX(), baseY - 1, lot.frontStepZ(), palette.foundationPrimary());
    }

    private static void buildFacade(List<Runnable> tasks,
                                    World world,
                                    TerrainManager.SetBlock sb,
                                    LotPlan lot,
                                    int baseY,
                                    VillageStyle.Palette palette) {
        int beamY = baseY + lot.houseSpec().wallHeight();
        BlockFace left = VillageStyle.leftOf(lot.facing());
        BlockFace right = VillageStyle.rightOf(lot.facing());
        int leftX = lot.doorX() + left.getModX() * 2;
        int leftZ = lot.doorZ() + left.getModZ() * 2;
        int rightX = lot.doorX() + right.getModX() * 2;
        int rightZ = lot.doorZ() + right.getModZ() * 2;
        if (leftX == rightX) {
            for (int z = Math.min(leftZ, rightZ); z <= Math.max(leftZ, rightZ); z++) {
                place(tasks, sb, leftX, beamY, z, palette.timber());
            }
        } else {
            for (int x = Math.min(leftX, rightX); x <= Math.max(leftX, rightX); x++) {
                place(tasks, sb, x, beamY, leftZ, palette.timber());
            }
        }
        place(tasks, sb, lot.frontStepX(), baseY + 3, lot.frontStepZ(), Material.CHAIN);
        place(tasks, sb, lot.frontStepX(), baseY + 2, lot.frontStepZ(), Material.LANTERN);
        if (lot.houseSpec().facadeVariant() % 2 == 0) {
            int awningX = lot.frontStepX();
            int awningZ = lot.frontStepZ();
            stair(tasks, world, sb, awningX, baseY + 3, awningZ, palette.awning(), VillageStyle.opposite(lot.facing()));
        }
        int bannerX = lot.frontStepX() + VillageStyle.leftOf(lot.facing()).getModX() * 2;
        int bannerZ = lot.frontStepZ() + VillageStyle.leftOf(lot.facing()).getModZ() * 2;
        place(tasks, sb, bannerX, baseY + 2, bannerZ, lot.houseSpec().facadeVariant() % 2 == 0 ? Material.RED_BANNER : Material.YELLOW_BANNER);
    }

    private static void buildInterior(List<Runnable> tasks,
                                      World world,
                                      TerrainManager.SetBlock sb,
                                      LotPlan lot,
                                      HouseVolume main,
                                      int baseY,
                                      VillageStyle.Palette palette) {
        int cx = (main.minX() + main.maxX()) / 2;
        int cz = (main.minZ() + main.maxZ()) / 2;
        place(tasks, sb, cx, baseY + 1, cz, Material.CRAFTING_TABLE);

        int lx = cx + VillageStyle.leftOf(lot.facing()).getModX();
        int lz = cz + VillageStyle.leftOf(lot.facing()).getModZ();
        int rx = cx + VillageStyle.rightOf(lot.facing()).getModX();
        int rz = cz + VillageStyle.rightOf(lot.facing()).getModZ();
        stair(tasks, world, sb, lx, baseY + 1, lz, Material.OAK_STAIRS, VillageStyle.rightOf(lot.facing()));
        stair(tasks, world, sb, rx, baseY + 1, rz, Material.OAK_STAIRS, VillageStyle.leftOf(lot.facing()));

        int bedX = main.minX() + 1;
        int bedZ = main.maxZ() - 2;
        place(tasks, sb, bedX, baseY + 1, bedZ, Material.WHITE_BED);
        place(tasks, sb, bedX + 1, baseY + 1, bedZ, Material.WHITE_BED);

        switch (lot.houseSpec().interiorVariant()) {
            case 0 -> {
                place(tasks, sb, main.maxX() - 1, baseY + 1, main.minZ() + 1, Material.FURNACE);
                place(tasks, sb, main.maxX() - 2, baseY + 1, main.minZ() + 1, Material.BARREL);
            }
            case 1 -> {
                place(tasks, sb, main.maxX() - 1, baseY + 1, main.minZ() + 1, Material.SMITHING_TABLE);
                place(tasks, sb, main.maxX() - 2, baseY + 1, main.minZ() + 1, Material.ANVIL);
            }
            default -> {
                place(tasks, sb, main.maxX() - 1, baseY + 1, main.minZ() + 1, Material.BOOKSHELF);
                place(tasks, sb, main.maxX() - 2, baseY + 1, main.minZ() + 1, Material.LECTERN);
            }
        }
        place(tasks, sb, main.minX() + 1, baseY + 1, main.minZ() + 1, Material.BARREL);
        place(tasks, sb, cx, baseY + lot.houseSpec().wallHeight() - 1, cz, Material.LANTERN);
        place(tasks, sb, cx - 1, baseY + 1, cz + 1, Material.SPRUCE_PRESSURE_PLATE);
        place(tasks, sb, cx + 1, baseY + 1, cz + 1, Material.SPRUCE_PRESSURE_PLATE);
        place(tasks, sb, cx, baseY + 1, cz + 1, Material.SPRUCE_SLAB);
    }

    private static void buildArchetypeAccent(List<Runnable> tasks,
                                             World world,
                                             TerrainManager.SetBlock sb,
                                             LotPlan lot,
                                             int baseY,
                                             VillageStyle.Palette palette) {
        switch (lot.houseSpec().archetype()) {
            case COTTAGE -> {
                place(tasks, sb, lot.minX() + 1, baseY + 1, lot.maxZ() - 1, Material.FLOWER_POT);
                place(tasks, sb, lot.maxX() - 1, baseY + 1, lot.maxZ() - 1, Material.FLOWER_POT);
            }
            case TOWNHOUSE -> place(tasks, sb, lot.frontStepX() + VillageStyle.leftOf(lot.facing()).getModX() * 2,
                    baseY + 2, lot.frontStepZ() + VillageStyle.leftOf(lot.facing()).getModZ() * 2, Material.RED_BANNER);
            case FAMILY_HOUSE -> place(tasks, sb, lot.maxX(), baseY + 1, lot.maxZ() - 2, Material.BARREL);
            case WORKSHOP_HOUSE -> {
                int yardX = lot.frontStepX() + VillageStyle.rightOf(lot.facing()).getModX() * 2;
                int yardZ = lot.frontStepZ() + VillageStyle.rightOf(lot.facing()).getModZ() * 2;
                place(tasks, sb, yardX, baseY, yardZ, Material.GRAVEL);
                place(tasks, sb, yardX, baseY + 1, yardZ, Material.BARREL);
                place(tasks, sb, yardX + VillageStyle.rightOf(lot.facing()).getModX(), baseY + 1,
                        yardZ + VillageStyle.rightOf(lot.facing()).getModZ(), Material.CHEST);
            }
        }
    }

    private static void buildFoundationSkirt(List<Runnable> tasks,
                                             TerrainManager.SetBlock sb,
                                             HouseVolume main,
                                             int baseY,
                                             VillageStyle.Palette palette,
                                             int steps) {
        for (int step = 1; step <= steps; step++) {
            int minX = main.minX() - step;
            int maxX = main.maxX() + step;
            int minZ = main.minZ() - step;
            int maxZ = main.maxZ() + step;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
                    if (edge) {
                        place(tasks, sb, x, baseY - step, z, step == 1 ? palette.foundationPrimary() : palette.foundationAccent());
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
        place(tasks, sb, px, baseY, pz, palette.floor());
        place(tasks, sb, px + left.getModX(), baseY, pz + left.getModZ(), palette.floor());
        place(tasks, sb, px + right.getModX(), baseY, pz + right.getModZ(), palette.floor());
        place(tasks, sb, px + left.getModX(), baseY + 1, pz + left.getModZ(), palette.fence());
        place(tasks, sb, px + right.getModX(), baseY + 1, pz + right.getModZ(), palette.fence());
        stair(tasks, world, sb, px, baseY + 2, pz, palette.awning(), VillageStyle.opposite(lot.facing()));
    }

    private static void buildYard(List<Runnable> tasks,
                                  World world,
                                  TerrainManager.SetBlock sb,
                                  LotPlan lot,
                                  int baseY,
                                  VillageStyle.Palette palette) {
        int depth = Math.max(2, lot.yardDepth());
        BlockFace front = lot.facing();
        BlockFace left = VillageStyle.leftOf(front);
        BlockFace right = VillageStyle.rightOf(front);
        int startX = lot.frontStepX() + front.getModX();
        int startZ = lot.frontStepZ() + front.getModZ();
        for (int step = 0; step < depth; step++) {
            int cx = startX + front.getModX() * step;
            int cz = startZ + front.getModZ() * step;
            place(tasks, sb, cx, baseY, cz, step == 0 ? palette.paving() : yardMaterial(lot.houseSpec().yardStyle(), step));
            if (step == depth - 1) {
                place(tasks, sb, cx + left.getModX(), baseY + 1, cz + left.getModZ(), palette.fence());
                place(tasks, sb, cx + right.getModX(), baseY + 1, cz + right.getModZ(), palette.fence());
            }
        }

        switch (lot.houseSpec().yardStyle()) {
            case FLOWERS -> {
                place(tasks, sb, startX + left.getModX() * 2, baseY + 1, startZ + left.getModZ() * 2, Material.POPPY);
                place(tasks, sb, startX + right.getModX() * 2, baseY + 1, startZ + right.getModZ() * 2, Material.BLUE_ORCHID);
            }
            case WOODPILE -> {
                place(tasks, sb, startX + left.getModX() * 2, baseY, startZ + left.getModZ() * 2, Material.OAK_LOG);
                place(tasks, sb, startX + right.getModX() * 2, baseY + 1, startZ + right.getModZ() * 2, Material.BARREL);
            }
            case FENCED -> {
                place(tasks, sb, startX + left.getModX() * 2, baseY + 1, startZ + left.getModZ() * 2, palette.fence());
                place(tasks, sb, startX + right.getModX() * 2, baseY + 1, startZ + right.getModZ() * 2, palette.fence());
            }
            case KITCHEN_GARDEN -> {
                place(tasks, sb, startX + left.getModX() * 2, baseY, startZ + left.getModZ() * 2, Material.FARMLAND);
                place(tasks, sb, startX + left.getModX() * 2, baseY + 1, startZ + left.getModZ() * 2, Material.CARROTS);
                place(tasks, sb, startX + right.getModX() * 2, baseY, startZ + right.getModZ() * 2, Material.FARMLAND);
                place(tasks, sb, startX + right.getModX() * 2, baseY + 1, startZ + right.getModZ() * 2, Material.POTATOES);
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

    private static void buildChimney(List<Runnable> tasks, TerrainManager.SetBlock sb, LotPlan lot, HouseVolume main, int baseY) {
        if (lot.houseSpec().archetype() == HouseArchetype.TOWNHOUSE && lot.houseSpec().facadeVariant() == 0) {
            return;
        }
        BlockFace back = VillageStyle.opposite(lot.facing());
        BlockFace side = lot.houseSpec().facadeVariant() % 2 == 0 ? VillageStyle.leftOf(lot.facing()) : VillageStyle.rightOf(lot.facing());
        int x = back == BlockFace.WEST ? main.minX() + 1 : back == BlockFace.EAST ? main.maxX() - 1 : (side == BlockFace.WEST ? main.minX() + 1 : main.maxX() - 1);
        int z = back == BlockFace.NORTH ? main.minZ() + 1 : back == BlockFace.SOUTH ? main.maxZ() - 1 : (side == BlockFace.NORTH ? main.minZ() + 1 : main.maxZ() - 1);
        for (int y = baseY + 1; y <= baseY + main.wallHeight() + 4; y++) {
            place(tasks, sb, x, y, z, Material.BRICKS);
        }
        place(tasks, sb, x, baseY + main.wallHeight() + 5, z, Material.CAMPFIRE);
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
        int sx = lot.facing() == BlockFace.WEST ? main.maxX() - 1 : main.minX() + 1;
        int sz = lot.facing() == BlockFace.NORTH ? main.maxZ() - 2 : main.minZ() + 1;
        for (int step = 0; step < 4; step++) {
            stair(tasks, world, sb, sx, baseY + 1 + step, sz + step, palette.roofStairs(), BlockFace.SOUTH);
        }
    }

    private static void buildDormer(List<Runnable> tasks,
                                    World world,
                                    TerrainManager.SetBlock sb,
                                    LotPlan lot,
                                    HouseVolume main,
                                    int baseY,
                                    VillageStyle.Palette palette) {
        int roofBaseY = baseY + main.wallHeight() + 2;
        int x = (main.minX() + main.maxX()) / 2;
        int z = lot.facing() == BlockFace.NORTH ? main.minZ() - 1 : main.maxZ() + 1;
        place(tasks, sb, x, roofBaseY, z, palette.window());
        stair(tasks, world, sb, x, roofBaseY + 1, z, palette.roofStairs(), VillageStyle.opposite(lot.facing()));
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
                int x = (volume.minX() + volume.maxX()) / 2;
                int z = facing == BlockFace.NORTH ? volume.minZ() - 1 : facing == BlockFace.SOUTH ? volume.maxZ() + 1 : (volume.minZ() + volume.maxZ()) / 2;
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
            int ridgeZ = (volume.minZ() + volume.maxZ()) / 2;
            for (int x = volume.minX() - 1; x <= volume.maxX() + 1; x++) {
                place(tasks, sb, x, roofY + layers, ridgeZ, palette.roofSlab());
            }
        } else {
            int ridgeX = (volume.minX() + volume.maxX()) / 2;
            for (int z = volume.minZ() - 1; z <= volume.maxZ() + 1; z++) {
                place(tasks, sb, ridgeX, roofY + layers, z, palette.roofSlab());
            }
        }
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
        place(tasks, sb, (volume.minX() + volume.maxX()) / 2, roofY + layers, (volume.minZ() + volume.maxZ()) / 2, palette.roofBlock());
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

    private static void addShutters(List<Runnable> tasks,
                                    World world,
                                    TerrainManager.SetBlock sb,
                                    int x,
                                    int y,
                                    int z,
                                    BlockFace outward,
                                    VillageStyle.Palette palette) {
        for (BlockFace side : List.of(VillageStyle.leftOf(outward), VillageStyle.rightOf(outward))) {
            int sx = x + side.getModX();
            int sz = z + side.getModZ();
            place(tasks, sb, sx, y, sz, palette.shutter());
            if (world != null) {
                tasks.add(() -> VillageStyle.setTrapdoor(world, sx, y, sz, palette.shutter(), outward, true));
            }
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

    private static boolean perimeter(int x, int z, HouseVolume volume) {
        return x == volume.minX() || x == volume.maxX() || z == volume.minZ() || z == volume.maxZ();
    }

    private static boolean corner(int x, int z, HouseVolume volume) {
        return (x == volume.minX() || x == volume.maxX()) && (z == volume.minZ() || z == volume.maxZ());
    }

    private static boolean shouldWindow(int x, int y, int z, HouseVolume volume, int baseY, BlockFace facing, boolean doorFace) {
        int relativeY = y - baseY;
        if (relativeY < 2 || relativeY > Math.max(3, volume.wallHeight() - 1)) {
            return false;
        }
        if (z == volume.minZ() || z == volume.maxZ()) {
            if (!doorFace && z == frontZ(volume, facing)) {
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

    private static HouseVolume annexFor(HouseVolume main, BlockFace facing) {
        return switch (facing) {
            case NORTH, SOUTH -> new HouseVolume(main.maxX() - 3, main.minZ() + 2, 4, 4, Math.max(3, main.wallHeight() - 1), RoofStyle.SHED);
            case EAST, WEST -> new HouseVolume(main.minX() + 2, main.maxZ() - 3, 4, 4, Math.max(3, main.wallHeight() - 1), RoofStyle.SHED);
            default -> new HouseVolume(main.maxX() - 3, main.minZ() + 2, 4, 4, Math.max(3, main.wallHeight() - 1), RoofStyle.SHED);
        };
    }

    private static Material cropFor(List<Material> crops, Random random) {
        if (crops == null || crops.isEmpty()) return Material.WHEAT;
        Material seed = crops.get(random.nextInt(crops.size()));
        return switch (seed) {
            case WHEAT_SEEDS -> Material.WHEAT;
            case CARROT -> Material.CARROTS;
            case POTATO -> Material.POTATOES;
            case BEETROOT_SEEDS -> Material.BEETROOTS;
            default -> Material.WHEAT;
        };
    }

    private static void place(List<Runnable> tasks, TerrainManager.SetBlock sb, int x, int y, int z, Material material) {
        tasks.add(() -> sb.set(x, y, z, material));
    }

    private record HouseVolume(int minX, int minZ, int footprintWidth, int footprintDepth, int wallHeight, RoofStyle roofStyle) {
        int maxX() { return minX + footprintWidth - 1; }
        int maxZ() { return minZ + footprintDepth - 1; }
    }
}
