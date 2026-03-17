package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;

import static org.example.village.VillageLayoutPlan.*;

/**
 * Planification puis construction des lots et des rues du village.
 *
 * Cette version met davantage l'accent sur le rendu visuel :
 * routes moins propres, place plus travaillée, meilleurs raccords
 * entre lots et voirie, et petits détails de mobilier urbain.
 */
public final class Disposition {

    private Disposition() {}

    public static VillageLayoutPlan buildVillage(JavaPlugin plugin,
                                                 Location center,
                                                 int baseY,
                                                 VillageLayoutSettings settings,
                                                 List<Material> cropSeeds,
                                                 Queue<Runnable> tasks,
                                                 TerrainManager.SetBlock sb,
                                                 Random rng,
                                                 int villageId) {

        VillageLayoutPlan layout = VillageLayoutPlanner.plan(center, settings, rng);
        return buildVillage(plugin, center, baseY, settings, cropSeeds, tasks, sb, rng, villageId, layout);
    }

    public static VillageLayoutPlan buildVillage(JavaPlugin plugin,
                                                 Location center,
                                                 int baseY,
                                                 VillageLayoutSettings settings,
                                                 List<Material> cropSeeds,
                                                 Queue<Runnable> tasks,
                                                 TerrainManager.SetBlock sb,
                                                 Random rng,
                                                 int villageId,
                                                 VillageLayoutPlan layout) {

        scheduleLayout(plugin, center, baseY, settings, villageId, cropSeeds, tasks, sb, rng, layout);
        return layout;
    }

    private static void scheduleLayout(JavaPlugin plugin,
                                       Location center,
                                       int baseY,
                                       VillageLayoutSettings settings,
                                       int villageId,
                                       List<Material> cropSeeds,
                                       Queue<Runnable> q,
                                       TerrainManager.SetBlock sb,
                                       Random rng,
                                       VillageLayoutPlan layout) {

        World world = center.getWorld();
        if (world == null) {
            return;
        }

        q.addAll(buildPlaza(world, center, settings.plazaSize(), baseY, sb));
        for (StreetPlan street : layout.streets()) {
            q.addAll(buildStreet(street, baseY, sb));
        }
        q.addAll(buildStreetLanterns(world, layout.streets(), baseY, sb));
        q.addAll(buildCrossroadDetails(world, center, baseY, sb));

        int landmarkIndex = 0;
        for (LotPlan lot : layout.lots()) {
            q.addAll(prepareLotBase(lot, baseY, sb));
            q.addAll(connectLotToRoad(world, lot, baseY, sb));

            int lotBaseY = baseY + 1 + lot.terraceY();
            switch (lot.role()) {
                case CHURCH -> {
                    q.addAll(SpecialBuildings.buildChurch(world, lot, lotBaseY, sb));
                    q.addAll(buildFrontLanterns(world, lot, lotBaseY, sb));
                }
                case FORGE -> {
                    q.addAll(SpecialBuildings.buildForge(world, lot, lotBaseY, sb));
                    q.addAll(buildFrontLanterns(world, lot, lotBaseY, sb));
                }
                case HOUSE_SINGLE, HOUSE_TWO_STORY -> q.addAll(HouseBuilder.buildHouse(world, lot, lotBaseY, sb, rng));
                case FARM -> q.addAll(HouseBuilder.buildFarm(new Location(world, lot.buildX(), lotBaseY, lot.buildZ()), cropSeeds, sb, rng));
                case PEN -> q.addAll(HouseBuilder.buildPen(plugin, new Location(world, lot.buildX(), lotBaseY, lot.buildZ()), villageId, sb));
                case MARKET -> q.addAll(SpecialBuildings.buildMarketStall(world, lot, lotBaseY, sb, rng));
                case GREEN -> {
                    LandmarkType type = layout.landmarks().get(landmarkIndex % layout.landmarks().size());
                    q.addAll(SpecialBuildings.buildGreenLot(lot, lotBaseY, sb, type));
                    landmarkIndex++;
                }
                case SERVICE_YARD -> q.addAll(SpecialBuildings.buildServiceYard(world, lot, lotBaseY, sb));
                case DECOR -> q.addAll(SpecialBuildings.buildDecorLot(lot.buildX(), lotBaseY, lot.buildZ(), sb, rng));
            }
        }
    }

    private static List<Runnable> buildPlaza(World world, Location center, int size, int baseY, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        int ox = center.getBlockX() - size / 2;
        int oz = center.getBlockZ() - size / 2;
        int half = size / 2;

        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                int x = ox + dx;
                int z = oz + dz;
                int dist = Math.max(Math.abs(dx - half), Math.abs(dz - half));
                boolean edge = dx == 0 || dz == 0 || dx == size - 1 || dz == size - 1;
                boolean axis = dx == half || dz == half;
                Material material;
                if (edge) {
                    material = (dx + dz) % 2 == 0 ? Material.STONE_BRICKS : Material.MOSSY_STONE_BRICKS;
                } else if (axis) {
                    material = Material.POLISHED_ANDESITE;
                } else if (dist == 1) {
                    material = (x + z) % 2 == 0 ? Material.SMOOTH_STONE : Material.ANDESITE;
                } else {
                    int selector = hash(x, z, 0) % 5;
                    material = switch (selector) {
                        case 0 -> Material.POLISHED_ANDESITE;
                        case 1 -> Material.ANDESITE;
                        case 2 -> Material.STONE_BRICKS;
                        case 3 -> Material.GRAVEL;
                        default -> Material.COBBLESTONE;
                    };
                }
                place(tasks, sb, x, baseY, z, material);
            }
        }

        buildWell(tasks, world, center.getBlockX(), baseY, center.getBlockZ(), sb);

        // Bancs sur les 4 côtés.
        buildBench(tasks, world, center.getBlockX() - 3, baseY + 1, center.getBlockZ() - half + 1, BlockFace.SOUTH, sb);
        buildBench(tasks, world, center.getBlockX() + 3, baseY + 1, center.getBlockZ() - half + 1, BlockFace.SOUTH, sb);
        buildBench(tasks, world, center.getBlockX() - 3, baseY + 1, center.getBlockZ() + half - 1, BlockFace.NORTH, sb);
        buildBench(tasks, world, center.getBlockX() + 3, baseY + 1, center.getBlockZ() + half - 1, BlockFace.NORTH, sb);

        // Jardinières d'angle.
        buildPlanter(tasks, center.getBlockX() - half + 1, baseY + 1, center.getBlockZ() - half + 1, sb, true);
        buildPlanter(tasks, center.getBlockX() + half - 1, baseY + 1, center.getBlockZ() - half + 1, sb, false);
        buildPlanter(tasks, center.getBlockX() - half + 1, baseY + 1, center.getBlockZ() + half - 1, sb, false);
        buildPlanter(tasks, center.getBlockX() + half - 1, baseY + 1, center.getBlockZ() + half - 1, sb, true);

        // Lampadaires de place.
        tasks.addAll(HouseBuilder.buildLampPost(center.getBlockX() - half + 2, baseY + 1, center.getBlockZ(), sb));
        tasks.addAll(HouseBuilder.buildLampPost(center.getBlockX() + half - 2, baseY + 1, center.getBlockZ(), sb));
        return tasks;
    }

    private static void buildWell(List<Runnable> tasks, World world, int x0, int y, int z0, TerrainManager.SetBlock sb) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = x0 + dx;
                int z = z0 + dz;
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                boolean basin = Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
                place(tasks, sb, x, y - 1, z, Material.STONE_BRICKS);
                place(tasks, sb, x, y, z, edge ? Material.STONE_BRICKS : basin ? Material.WATER : Material.SMOOTH_STONE);
            }
        }

        // Poteaux d'angle.
        for (int dx : new int[]{-2, 2}) {
            for (int dz : new int[]{-2, 2}) {
                for (int dy = 1; dy <= 3; dy++) {
                    place(tasks, sb, x0 + dx, y + dy, z0 + dz, Material.STRIPPED_SPRUCE_LOG);
                }
            }
        }

        // Toit du puits.
        for (int x = x0 - 3; x <= x0 + 3; x++) {
            stair(tasks, world, sb, x, y + 4, z0 - 3, Material.SPRUCE_STAIRS, BlockFace.NORTH);
            stair(tasks, world, sb, x, y + 4, z0 + 3, Material.SPRUCE_STAIRS, BlockFace.SOUTH);
        }
        for (int z = z0 - 2; z <= z0 + 2; z++) {
            stair(tasks, world, sb, x0 - 3, y + 4, z, Material.SPRUCE_STAIRS, BlockFace.WEST);
            stair(tasks, world, sb, x0 + 3, y + 4, z, Material.SPRUCE_STAIRS, BlockFace.EAST);
        }
        for (int x = x0 - 2; x <= x0 + 2; x++) {
            slab(tasks, world, sb, x, y + 5, z0, Material.SPRUCE_SLAB, Slab.Type.TOP);
        }
        for (int z = z0 - 2; z <= z0 + 2; z++) {
            slab(tasks, world, sb, x0, y + 5, z, Material.SPRUCE_SLAB, Slab.Type.TOP);
        }

        // Seau suspendu symbolique.
        place(tasks, sb, x0, y + 4, z0, Material.CHAIN);
        place(tasks, sb, x0, y + 3, z0, Material.CAULDRON);
    }

    private static void buildBench(List<Runnable> tasks, World world, int x, int y, int z, BlockFace facing, TerrainManager.SetBlock sb) {
        place(tasks, sb, x, y - 1, z, Material.STONE_BRICKS);
        place(tasks, sb, x, y, z, Material.SPRUCE_STAIRS);
        tasks.add(() -> VillageStyle.setStair(world, x, y, z, Material.SPRUCE_STAIRS, facing, Stairs.Half.BOTTOM, Stairs.Shape.STRAIGHT));

        // Accoudoirs.
        place(tasks, sb, x + VillageStyle.leftOf(facing).getModX(), y, z + VillageStyle.leftOf(facing).getModZ(), Material.SPRUCE_TRAPDOOR);
        place(tasks, sb, x + VillageStyle.rightOf(facing).getModX(), y, z + VillageStyle.rightOf(facing).getModZ(), Material.SPRUCE_TRAPDOOR);
        tasks.add(() -> VillageStyle.setTrapdoor(world,
                x + VillageStyle.leftOf(facing).getModX(), y, z + VillageStyle.leftOf(facing).getModZ(),
                Material.SPRUCE_TRAPDOOR, facing, true, Bisected.Half.BOTTOM));
        tasks.add(() -> VillageStyle.setTrapdoor(world,
                x + VillageStyle.rightOf(facing).getModX(), y, z + VillageStyle.rightOf(facing).getModZ(),
                Material.SPRUCE_TRAPDOOR, facing, true, Bisected.Half.BOTTOM));
    }

    private static void buildPlanter(List<Runnable> tasks, int x, int y, int z, TerrainManager.SetBlock sb, boolean flowering) {
        place(tasks, sb, x, y - 1, z, Material.STONE_BRICKS);
        place(tasks, sb, x, y, z, Material.MOSS_BLOCK);
        place(tasks, sb, x, y + 1, z, flowering ? Material.FLOWERING_AZALEA : Material.AZALEA);
        place(tasks, sb, x + 1, y, z, Material.MOSS_BLOCK);
        place(tasks, sb, x - 1, y, z, Material.MOSS_BLOCK);
    }

    private static List<Runnable> buildStreet(StreetPlan street, int baseY, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        int shoulder = street.type() == StreetType.FOOTPATH ? 0 : 1;

        if (street.horizontal()) {
            int start = Math.min(street.startX(), street.endX());
            int end = Math.max(street.startX(), street.endX());
            for (int x = start; x <= end; x++) {
                for (int off = -street.halfWidth() - shoulder; off <= street.halfWidth() + shoulder; off++) {
                    int z = street.startZ() + off;
                    Material mat = Math.abs(off) > street.halfWidth()
                            ? shoulderMaterial(street.type(), x, z)
                            : pickStreetMaterial(street.type(), off, street.halfWidth(), x, z);
                    place(tasks, sb, x, baseY, z, mat);
                }
            }
        } else {
            int start = Math.min(street.startZ(), street.endZ());
            int end = Math.max(street.startZ(), street.endZ());
            for (int z = start; z <= end; z++) {
                for (int off = -street.halfWidth() - shoulder; off <= street.halfWidth() + shoulder; off++) {
                    int x = street.startX() + off;
                    Material mat = Math.abs(off) > street.halfWidth()
                            ? shoulderMaterial(street.type(), x, z)
                            : pickStreetMaterial(street.type(), off, street.halfWidth(), x, z);
                    place(tasks, sb, x, baseY, z, mat);
                }
            }
        }
        return tasks;
    }

    private static Material pickStreetMaterial(StreetType type, int offset, int halfWidth, int x, int z) {
        if (Math.abs(offset) == halfWidth && type != StreetType.FOOTPATH) {
            return (x + z) % 3 == 0 ? Material.STONE_BRICKS : Material.COBBLESTONE;
        }
        int selector = hash(x, z, halfWidth) % 8;
        return switch (type) {
            case MAIN -> switch (selector) {
                case 0 -> Material.POLISHED_ANDESITE;
                case 1, 2 -> Material.COBBLESTONE;
                case 3 -> Material.STONE_BRICKS;
                case 4, 5 -> Material.PACKED_MUD;
                case 6 -> Material.DIRT_PATH;
                default -> Material.GRAVEL;
            };
            case SIDE -> switch (selector) {
                case 0, 1, 2 -> Material.DIRT_PATH;
                case 3, 4 -> Material.PACKED_MUD;
                case 5, 6 -> Material.GRAVEL;
                default -> Material.COBBLESTONE;
            };
            case FOOTPATH -> selector % 2 == 0 ? Material.PACKED_MUD : Material.DIRT_PATH;
        };
    }

    private static Material shoulderMaterial(StreetType type, int x, int z) {
        int selector = hash(x, z, 19) % 4;
        if (type == StreetType.MAIN) {
            return switch (selector) {
                case 0 -> Material.COARSE_DIRT;
                case 1 -> Material.PACKED_MUD;
                case 2 -> Material.GRASS_BLOCK;
                default -> Material.MOSS_BLOCK;
            };
        }
        return selector % 2 == 0 ? Material.GRASS_BLOCK : Material.COARSE_DIRT;
    }

    private static List<Runnable> buildStreetLanterns(World world, List<StreetPlan> streets, int baseY, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        for (StreetPlan street : streets) {
            if (street.type() == StreetType.FOOTPATH) {
                continue;
            }
            int interval = street.type() == StreetType.MAIN ? 9 : 12;
            if (street.horizontal()) {
                int start = Math.min(street.startX(), street.endX());
                int end = Math.max(street.startX(), street.endX());
                int toggle = 0;
                for (int x = start + interval / 2; x < end; x += interval) {
                    int side = toggle++ % 2 == 0 ? -1 : 1;
                    tasks.addAll(HouseBuilder.buildLampPost(x, baseY + 1, street.startZ() + side * (street.halfWidth() + 2), sb));
                }
            } else {
                int start = Math.min(street.startZ(), street.endZ());
                int end = Math.max(street.startZ(), street.endZ());
                int toggle = 0;
                for (int z = start + interval / 2; z < end; z += interval) {
                    int side = toggle++ % 2 == 0 ? -1 : 1;
                    tasks.addAll(HouseBuilder.buildLampPost(street.startX() + side * (street.halfWidth() + 2), baseY + 1, z, sb));
                }
            }
        }
        return tasks;
    }

    private static List<Runnable> buildCrossroadDetails(World world, Location center, int baseY, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        int cx = center.getBlockX();
        int cz = center.getBlockZ();

        // Deux petits îlots décoratifs pour casser l'effet "croix parfaite".
        decorateRoadsideIsland(tasks, world, cx - 6, baseY, cz + 5, sb, true);
        decorateRoadsideIsland(tasks, world, cx + 6, baseY, cz - 5, sb, false);
        return tasks;
    }

    private static void decorateRoadsideIsland(List<Runnable> tasks,
                                               World world,
                                               int x,
                                               int y,
                                               int z,
                                               TerrainManager.SetBlock sb,
                                               boolean crate) {
        place(tasks, sb, x, y, z, Material.MOSS_BLOCK);
        place(tasks, sb, x + 1, y, z, Material.GRASS_BLOCK);
        place(tasks, sb, x, y + 1, z, crate ? Material.BARREL : Material.FLOWERING_AZALEA);
        place(tasks, sb, x + 1, y + 1, z, crate ? Material.CHEST : Material.LANTERN);
        if (!crate) {
            place(tasks, sb, x + 1, y + 2, z, Material.CHAIN);
        }
    }

    private static List<Runnable> prepareLotBase(LotPlan lot, int baseY, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        int terraceTop = baseY + lot.terraceY();
        int fullMinX = lot.minX() - lot.yardDepth();
        int fullMaxX = lot.maxX() + lot.yardDepth();
        int fullMinZ = lot.minZ() - lot.yardDepth();
        int fullMaxZ = lot.maxZ() + lot.yardDepth();

        for (int x = fullMinX; x <= fullMaxX; x++) {
            for (int z = fullMinZ; z <= fullMaxZ; z++) {
                boolean perimeter = x == fullMinX || x == fullMaxX || z == fullMinZ || z == fullMaxZ;
                Material fill = terraceTop > baseY && perimeter ? Material.STONE_BRICKS : Material.DIRT;
                for (int y = baseY - 1; y < terraceTop; y++) {
                    place(tasks, sb, x, y, z, fill);
                }
                place(tasks, sb, x, terraceTop, z, lotSurface(lot, x, z));
            }
        }

        // Retenue de terre visible si lot en terrasse.
        if (lot.terraceY() > 0) {
            for (int x = fullMinX; x <= fullMaxX; x++) {
                for (int z = fullMinZ; z <= fullMaxZ; z++) {
                    boolean edge = x == fullMinX || x == fullMaxX || z == fullMinZ || z == fullMaxZ;
                    if (!edge) {
                        continue;
                    }
                    for (int y = baseY; y < terraceTop; y++) {
                        place(tasks, sb, x, y, z, (x + z + y) % 4 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                    }
                }
            }
        }
        return tasks;
    }

    private static Material lotSurface(LotPlan lot, int x, int z) {
        boolean inner = x >= lot.minX() && x <= lot.maxX() && z >= lot.minZ() && z <= lot.maxZ();
        int selector = hash(x, z, lot.row() + lot.col()) % 6;
        if (inner) {
            return switch (lot.role()) {
                case MARKET, SERVICE_YARD -> selector % 2 == 0 ? Material.GRAVEL : Material.PACKED_MUD;
                case FARM -> Material.FARMLAND;
                case PEN -> selector % 2 == 0 ? Material.GRASS_BLOCK : Material.COARSE_DIRT;
                case CHURCH -> selector % 2 == 0 ? Material.SMOOTH_STONE : Material.POLISHED_ANDESITE;
                case FORGE -> selector % 2 == 0 ? Material.COBBLED_DEEPSLATE : Material.GRAVEL;
                default -> selector == 0 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK;
            };
        }
        return switch (lot.role()) {
            case MARKET -> selector % 2 == 0 ? Material.GRAVEL : Material.PACKED_MUD;
            case SERVICE_YARD -> selector % 2 == 0 ? Material.COARSE_DIRT : Material.GRAVEL;
            case FARM -> selector % 2 == 0 ? Material.COARSE_DIRT : Material.PACKED_MUD;
            case PEN, GREEN, DECOR -> selector % 3 == 0 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK;
            default -> selector == 0 ? Material.MOSS_BLOCK : selector == 1 ? Material.COARSE_DIRT : Material.GRASS_BLOCK;
        };
    }

    private static List<Runnable> connectLotToRoad(World world,
                                                   LotPlan lot,
                                                   int baseY,
                                                   TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        int currentX = lot.frontageX();
        int currentZ = lot.frontageZ();
        int targetX = lot.frontStepX();
        int targetZ = lot.frontStepZ();
        int distance = Math.abs(targetX - currentX) + Math.abs(targetZ - currentZ);
        int climbStart = Math.max(0, distance - Math.max(1, lot.terraceY()));
        int stepIndex = 0;

        while (currentX != targetX || currentZ != targetZ) {
            int pathY = baseY + Math.max(0, stepIndex - climbStart + 1);
            pathY = Math.min(pathY, baseY + lot.terraceY());
            addPathCell(tasks, world, sb, currentX, pathY, currentZ, lot, stepIndex == 0 || stepIndex == distance - 1);
            if (currentX != targetX) {
                currentX += Integer.compare(targetX, currentX);
            } else {
                currentZ += Integer.compare(targetZ, currentZ);
            }
            stepIndex++;
        }
        addPathCell(tasks, world, sb, targetX, baseY + lot.terraceY(), targetZ, lot, true);
        return tasks;
    }

    private static void addPathCell(List<Runnable> tasks,
                                    World world,
                                    TerrainManager.SetBlock sb,
                                    int x,
                                    int y,
                                    int z,
                                    LotPlan lot,
                                    boolean widen) {
        for (int fillY = y - 1; fillY >= Math.max(0, y - 2); fillY--) {
            place(tasks, sb, x, fillY, z, Material.STONE_BRICKS);
        }

        Material surface = switch (lot.role()) {
            case CHURCH, FORGE, MARKET -> Material.POLISHED_ANDESITE;
            case FARM, PEN -> Material.PACKED_MUD;
            default -> Material.DIRT_PATH;
        };
        place(tasks, sb, x, y, z, surface);

        if (widen) {
            BlockFace left = VillageStyle.leftOf(lot.facing());
            BlockFace right = VillageStyle.rightOf(lot.facing());
            place(tasks, sb, x + left.getModX(), y, z + left.getModZ(), surface == Material.DIRT_PATH ? Material.GRAVEL : Material.COBBLESTONE);
            place(tasks, sb, x + right.getModX(), y, z + right.getModZ(), surface == Material.DIRT_PATH ? Material.GRAVEL : Material.COBBLESTONE);
        }
    }

    private static List<Runnable> buildFrontLanterns(World world,
                                                     LotPlan lot,
                                                     int baseY,
                                                     TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        int lateralX = lot.facing().getModZ();
        int lateralZ = lot.facing().getModX();
        for (int sign : List.of(-1, 1)) {
            int x = lot.frontageX() + lateralX * sign * 2;
            int z = lot.frontageZ() + lateralZ * sign * 2;
            tasks.addAll(HouseBuilder.buildLampPost(x, baseY, z, sb));
        }
        return tasks;
    }

    private static int hash(int x, int z, int salt) {
        return Math.abs((x * 31) ^ (z * 17) ^ (salt * 13));
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
}
