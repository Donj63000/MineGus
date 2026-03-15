package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;

import static org.example.village.VillageLayoutPlan.*;

/**
 * Planification puis construction des lots et des rues du village.
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

        q.addAll(buildPlaza(center, settings.plazaSize(), baseY, sb));
        for (StreetPlan street : layout.streets()) {
            q.addAll(buildStreet(street, baseY, sb));
        }
        q.addAll(buildStreetLanterns(layout.streets(), baseY, sb));

        int landmarkIndex = 0;
        for (LotPlan lot : layout.lots()) {
            q.addAll(prepareLotBase(lot, baseY, sb));
            q.addAll(connectLotToRoad(lot, baseY, sb));

            int lotBaseY = baseY + 1 + lot.terraceY();
            switch (lot.role()) {
                case CHURCH -> {
                    q.addAll(SpecialBuildings.buildChurch(world, lot, lotBaseY, sb));
                    q.addAll(buildFrontLanterns(lot, lotBaseY, sb));
                }
                case FORGE -> {
                    q.addAll(SpecialBuildings.buildForge(world, lot, lotBaseY, sb));
                    q.addAll(buildFrontLanterns(lot, lotBaseY, sb));
                }
                case HOUSE_SINGLE, HOUSE_TWO_STORY ->
                        q.addAll(HouseBuilder.buildHouse(world, lot, lotBaseY, sb, rng));
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

    private static List<Runnable> buildPlaza(Location center, int size, int baseY, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        int ox = center.getBlockX() - size / 2;
        int oz = center.getBlockZ() - size / 2;
        int half = size / 2;

        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                int x = ox + dx;
                int z = oz + dz;
                boolean edge = dx == 0 || dz == 0 || dx == size - 1 || dz == size - 1;
                boolean axis = dx == half || dz == half;
                Material material = edge ? Material.STONE_BRICKS : axis ? Material.SMOOTH_STONE : Material.POLISHED_ANDESITE;
                place(tasks, sb, x, baseY, z, material);
            }
        }

        buildWell(tasks, center.getBlockX(), baseY, center.getBlockZ(), sb);
        buildBench(tasks, center.getBlockX() - 3, baseY + 1, center.getBlockZ() - half + 1, BlockFace.SOUTH, sb);
        buildBench(tasks, center.getBlockX() + 3, baseY + 1, center.getBlockZ() - half + 1, BlockFace.SOUTH, sb);
        buildBench(tasks, center.getBlockX() - 3, baseY + 1, center.getBlockZ() + half - 1, BlockFace.NORTH, sb);
        buildBench(tasks, center.getBlockX() + 3, baseY + 1, center.getBlockZ() + half - 1, BlockFace.NORTH, sb);
        buildPlanter(tasks, center.getBlockX() - half + 1, baseY + 1, center.getBlockZ() - half + 1, sb);
        buildPlanter(tasks, center.getBlockX() + half - 1, baseY + 1, center.getBlockZ() - half + 1, sb);
        buildPlanter(tasks, center.getBlockX() - half + 1, baseY + 1, center.getBlockZ() + half - 1, sb);
        buildPlanter(tasks, center.getBlockX() + half - 1, baseY + 1, center.getBlockZ() + half - 1, sb);
        return tasks;
    }

    private static void buildWell(List<Runnable> tasks, int x0, int y, int z0, TerrainManager.SetBlock sb) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = x0 + dx;
                int z = z0 + dz;
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                boolean basin = Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
                place(tasks, sb, x, y, z, edge ? Material.STONE_BRICKS : basin ? Material.WATER : Material.SMOOTH_STONE);
            }
        }
        for (int dx : new int[]{-2, 2}) {
            for (int dz : new int[]{-2, 2}) {
                for (int dy = 1; dy <= 3; dy++) {
                    place(tasks, sb, x0 + dx, y + dy, z0 + dz, Material.STONE_BRICK_WALL);
                }
                place(tasks, sb, x0 + dx, y + 4, z0 + dz, Material.LANTERN);
            }
        }
        place(tasks, sb, x0, y + 4, z0, Material.CHAIN);
        place(tasks, sb, x0, y + 3, z0, Material.BELL);
    }

    private static void buildBench(List<Runnable> tasks, int x, int y, int z, BlockFace facing, TerrainManager.SetBlock sb) {
        place(tasks, sb, x, y - 1, z, Material.STONE_BRICKS);
        place(tasks, sb, x, y, z, Material.SPRUCE_STAIRS);
        place(tasks, sb, x - 1, y, z, Material.SPRUCE_TRAPDOOR);
        place(tasks, sb, x + 1, y, z, Material.SPRUCE_TRAPDOOR);
    }

    private static void buildPlanter(List<Runnable> tasks, int x, int y, int z, TerrainManager.SetBlock sb) {
        place(tasks, sb, x, y - 1, z, Material.STONE_BRICKS);
        place(tasks, sb, x, y, z, Material.OAK_FENCE);
        place(tasks, sb, x, y + 1, z, Material.FLOWERING_AZALEA);
    }

    private static List<Runnable> buildStreet(StreetPlan street, int baseY, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        if (street.horizontal()) {
            int start = Math.min(street.startX(), street.endX());
            int end = Math.max(street.startX(), street.endX());
            for (int x = start; x <= end; x++) {
                for (int off = -street.halfWidth(); off <= street.halfWidth(); off++) {
                    int z = street.startZ() + off;
                    place(tasks, sb, x, baseY, z, pickStreetMaterial(street.type(), off, street.halfWidth(), x, z));
                }
            }
        } else {
            int start = Math.min(street.startZ(), street.endZ());
            int end = Math.max(street.startZ(), street.endZ());
            for (int z = start; z <= end; z++) {
                for (int off = -street.halfWidth(); off <= street.halfWidth(); off++) {
                    int x = street.startX() + off;
                    place(tasks, sb, x, baseY, z, pickStreetMaterial(street.type(), off, street.halfWidth(), x, z));
                }
            }
        }
        return tasks;
    }

    private static Material pickStreetMaterial(StreetType type, int offset, int halfWidth, int x, int z) {
        if (Math.abs(offset) == halfWidth && type != StreetType.FOOTPATH) {
            return Material.COBBLESTONE;
        }
        int selector = Math.floorMod((x * 17) + (z * 31), 8);
        return switch (type) {
            case MAIN -> switch (selector) {
                case 0, 1 -> Material.POLISHED_ANDESITE;
                case 2, 3 -> Material.COBBLESTONE;
                case 4 -> Material.STONE_BRICKS;
                case 5, 6 -> Material.PACKED_MUD;
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

    private static List<Runnable> buildStreetLanterns(List<StreetPlan> streets, int baseY, TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        for (StreetPlan street : streets) {
            if (street.type() == StreetType.FOOTPATH) {
                continue;
            }
            int interval = street.type() == StreetType.MAIN ? 10 : 12;
            if (street.horizontal()) {
                int start = Math.min(street.startX(), street.endX());
                int end = Math.max(street.startX(), street.endX());
                for (int x = start + interval / 2; x < end; x += interval) {
                    tasks.addAll(HouseBuilder.buildLampPost(x, baseY + 1, street.startZ() - street.halfWidth() - 2, sb));
                }
            } else {
                int start = Math.min(street.startZ(), street.endZ());
                int end = Math.max(street.startZ(), street.endZ());
                for (int z = start + interval / 2; z < end; z += interval) {
                    tasks.addAll(HouseBuilder.buildLampPost(street.startX() - street.halfWidth() - 2, baseY + 1, z, sb));
                }
            }
        }
        return tasks;
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

        if (lot.terraceY() > 0) {
            for (int x = fullMinX; x <= fullMaxX; x++) {
                for (int z = fullMinZ; z <= fullMaxZ; z++) {
                    boolean edge = x == fullMinX || x == fullMaxX || z == fullMinZ || z == fullMaxZ;
                    if (!edge) {
                        continue;
                    }
                    for (int y = baseY; y < terraceTop; y++) {
                        place(tasks, sb, x, y, z, Material.STONE_BRICKS);
                    }
                }
            }
        }
        return tasks;
    }

    private static Material lotSurface(LotPlan lot, int x, int z) {
        if (x >= lot.minX() && x <= lot.maxX() && z >= lot.minZ() && z <= lot.maxZ()) {
            return switch (lot.role()) {
                case MARKET, SERVICE_YARD -> Material.GRAVEL;
                case FARM -> Material.FARMLAND;
                case PEN -> Material.GRASS_BLOCK;
                default -> Material.GRASS_BLOCK;
            };
        }
        return switch (lot.role()) {
            case MARKET -> Material.GRAVEL;
            case SERVICE_YARD -> Material.COARSE_DIRT;
            case FARM -> Material.COARSE_DIRT;
            case PEN, GREEN, DECOR -> Material.GRASS_BLOCK;
            default -> Material.GRASS_BLOCK;
        };
    }

    private static List<Runnable> connectLotToRoad(LotPlan lot,
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
            addPathCell(tasks, sb, currentX, pathY, currentZ, lot);
            if (currentX != targetX) {
                currentX += Integer.compare(targetX, currentX);
            } else {
                currentZ += Integer.compare(targetZ, currentZ);
            }
            stepIndex++;
        }
        addPathCell(tasks, sb, targetX, baseY + lot.terraceY(), targetZ, lot);
        return tasks;
    }

    private static void addPathCell(List<Runnable> tasks, TerrainManager.SetBlock sb, int x, int y, int z, LotPlan lot) {
        for (int fillY = y - 1; fillY >= Math.max(0, y - 2); fillY--) {
            place(tasks, sb, x, fillY, z, Material.STONE_BRICKS);
        }
        Material surface = switch (lot.role()) {
            case CHURCH, FORGE, MARKET -> Material.POLISHED_ANDESITE;
            case FARM, PEN -> Material.PACKED_MUD;
            default -> Material.DIRT_PATH;
        };
        place(tasks, sb, x, y, z, surface);
    }

    private static List<Runnable> buildFrontLanterns(LotPlan lot,
                                                     int baseY,
                                                     TerrainManager.SetBlock sb) {
        List<Runnable> tasks = new ArrayList<>();
        int lateralX = lot.facing().getModZ();
        int lateralZ = lot.facing().getModX();
        for (int sign : List.of(-1, 1)) {
            int x = lot.frontageX() + lateralX * sign * 2;
            int z = lot.frontageZ() + lateralZ * sign * 2;
            tasks.add(() -> sb.set(x, baseY, z, Material.STONE_BRICKS));
            tasks.add(() -> sb.set(x, baseY + 1, z, Material.COBBLESTONE_WALL));
            tasks.add(() -> sb.set(x, baseY + 2, z, Material.CHAIN));
            tasks.add(() -> sb.set(x, baseY + 3, z, Material.LANTERN));
        }
        return tasks;
    }

    private static void place(List<Runnable> tasks, TerrainManager.SetBlock sb, int x, int y, int z, Material material) {
        tasks.add(() -> sb.set(x, y, z, material));
    }
}
