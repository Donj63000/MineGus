package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.example.village.VillageLayoutPlan.*;

public final class VillageLayoutPlanner {
    private static final int MIN_GAP = 4;

    private VillageLayoutPlanner() {}

    public static VillageLayoutPlan plan(Location center, VillageLayoutSettings settings, Random rng) {
        Random random = rng == null ? new Random() : rng;
        int plot = settings.maxHouseFootprint();
        int grid = plot + settings.spacing();
        int plazaHalf = settings.plazaSize() / 2;
        int cx = center.getBlockX();
        int cz = center.getBlockZ();

        int southReach = grid + plazaHalf + 14;
        int northReach = (grid * 2) + 18;
        int westReach = (grid * 2) + 18;
        int eastReach = (grid * 2) + 18;
        int westStreetZ = cz + (grid / 2) + 3;
        int eastStreetZ = cz - (grid / 2) - 2;
        int northStreetZ = cz - grid - 6;

        List<StreetPlan> streets = List.of(
                new StreetPlan(cx, cz + southReach, cx, cz - northReach, StreetType.MAIN, settings.mainStreetHalf()),
                new StreetPlan(cx - westReach, westStreetZ, cx - 3, westStreetZ, StreetType.SIDE, settings.sideStreetHalf()),
                new StreetPlan(cx + 3, eastStreetZ, cx + eastReach, eastStreetZ, StreetType.SIDE, settings.sideStreetHalf()),
                new StreetPlan(cx - grid - 10, northStreetZ, cx + grid + 14, northStreetZ, StreetType.SIDE, settings.sideStreetHalf()),
                new StreetPlan(cx - 6, cz - plazaHalf - 2, cx - 6, northStreetZ + 2, StreetType.FOOTPATH, 0)
        );

        List<LotCandidate> fixed = List.of(
                new LotCandidate(LotRole.CHURCH, cx - 6, cz - grid - 18, BlockFace.SOUTH, HouseArchetype.COTTAGE, false, 2, 3, true),
                new LotCandidate(LotRole.FORGE, cx + grid + 12, cz + (grid / 2) + 4, BlockFace.WEST, HouseArchetype.WORKSHOP_HOUSE, false, 1, 2, true),
                new LotCandidate(LotRole.FARM, cx + (grid * 2) + 12, eastStreetZ - 1, BlockFace.WEST, HouseArchetype.COTTAGE, false, 0, 4, false),
                new LotCandidate(LotRole.PEN, cx - (grid * 2) - 12, westStreetZ + 4, BlockFace.EAST, HouseArchetype.COTTAGE, false, 0, 3, false),
                new LotCandidate(LotRole.SERVICE_YARD, cx - grid - 12, cz + grid + 14, BlockFace.EAST, HouseArchetype.WORKSHOP_HOUSE, false, 0, 3, false),
                new LotCandidate(LotRole.MARKET, cx - 7, cz + plazaHalf + 8, BlockFace.NORTH, HouseArchetype.COTTAGE, false, 0, 2, true),
                new LotCandidate(LotRole.MARKET, cx + 7, cz + plazaHalf + 8, BlockFace.NORTH, HouseArchetype.COTTAGE, false, 0, 2, true),
                new LotCandidate(LotRole.GREEN, cx + grid + 16, northStreetZ + 12, BlockFace.NORTH, HouseArchetype.COTTAGE, false, 1, 2, false),
                new LotCandidate(LotRole.DECOR, cx - grid - 14, northStreetZ + 12, BlockFace.NORTH, HouseArchetype.COTTAGE, false, 1, 2, false)
        );

        List<LotCandidate> coreHouses = List.of(
                new LotCandidate(LotRole.HOUSE_SINGLE, cx - 13, cz + grid + 2, BlockFace.EAST, HouseArchetype.COTTAGE, false, 1, 3, false),
                new LotCandidate(LotRole.HOUSE_TWO_STORY, cx + 13, cz + grid - 2, BlockFace.WEST, HouseArchetype.TOWNHOUSE, false, 1, 2, false),
                new LotCandidate(LotRole.HOUSE_SINGLE, cx - 13, cz + 7, BlockFace.EAST, HouseArchetype.WORKSHOP_HOUSE, false, 0, 3, false),
                new LotCandidate(LotRole.HOUSE_TWO_STORY, cx + 13, cz + 8, BlockFace.WEST, HouseArchetype.FAMILY_HOUSE, false, 0, 3, false),
                new LotCandidate(LotRole.HOUSE_SINGLE, cx - 14, cz - 11, BlockFace.EAST, HouseArchetype.COTTAGE, false, 1, 4, false),
                new LotCandidate(LotRole.HOUSE_TWO_STORY, cx + 14, cz - 9, BlockFace.WEST, HouseArchetype.TOWNHOUSE, false, 1, 2, false),
                new LotCandidate(LotRole.HOUSE_SINGLE, cx - grid - 14, westStreetZ - 12, BlockFace.SOUTH, HouseArchetype.COTTAGE, false, 1, 4, false),
                new LotCandidate(LotRole.HOUSE_SINGLE, cx - grid - 4, westStreetZ + 12, BlockFace.NORTH, HouseArchetype.FAMILY_HOUSE, false, 0, 3, false),
                new LotCandidate(LotRole.HOUSE_SINGLE, cx + grid + 8, eastStreetZ + 12, BlockFace.NORTH, HouseArchetype.WORKSHOP_HOUSE, false, 0, 3, false),
                new LotCandidate(LotRole.HOUSE_SINGLE, cx + grid + 18, eastStreetZ - 12, BlockFace.SOUTH, HouseArchetype.COTTAGE, false, 1, 4, false)
        );
        List<LotCandidate> optionalHouses = List.of(
                new LotCandidate(LotRole.HOUSE_SINGLE, cx - (grid * 2) - 6, westStreetZ - 11, BlockFace.SOUTH, HouseArchetype.COTTAGE, true, 0, 4, false),
                new LotCandidate(LotRole.HOUSE_TWO_STORY, cx + (grid * 2) + 4, eastStreetZ + 11, BlockFace.NORTH, HouseArchetype.FAMILY_HOUSE, true, 0, 3, false),
                new LotCandidate(LotRole.HOUSE_SINGLE, cx - grid - 18, northStreetZ + 11, BlockFace.NORTH, HouseArchetype.COTTAGE, true, 1, 3, true),
                new LotCandidate(LotRole.HOUSE_SINGLE, cx + grid + 6, northStreetZ + 11, BlockFace.NORTH, HouseArchetype.WORKSHOP_HOUSE, true, 1, 3, true),
                new LotCandidate(LotRole.HOUSE_SINGLE, cx - 18, cz - grid + 16, BlockFace.EAST, HouseArchetype.COTTAGE, true, 0, 4, false),
                new LotCandidate(LotRole.HOUSE_TWO_STORY, cx + 30, cz - grid + 18, BlockFace.WEST, HouseArchetype.TOWNHOUSE, true, 0, 2, false)
        );

        int maxAvailableHouses = coreHouses.size() + optionalHouses.size();
        int targetHouseCount = Math.max(settings.houseCountMin(), Math.min(settings.houseCountMax(), maxAvailableHouses));
        if (targetHouseCount > coreHouses.size()) {
            targetHouseCount = coreHouses.size() + random.nextInt((targetHouseCount - coreHouses.size()) + 1);
        }

        List<LotCandidate> selected = new ArrayList<>(fixed);
        selected.addAll(coreHouses);
        List<LotCandidate> optionalPool = new ArrayList<>(optionalHouses);
        Collections.shuffle(optionalPool, random);
        for (LotCandidate candidate : optionalPool) {
            if (houseCount(selected) >= targetHouseCount) {
                break;
            }
            selected.add(candidate);
        }

        List<LotPlan> lots = materializeLots(selected, random);

        List<LandmarkType> landmarkPool = new ArrayList<>(List.of(LandmarkType.STATUE, LandmarkType.GARDEN, LandmarkType.CHERRY));
        Collections.shuffle(landmarkPool, random);
        List<LandmarkType> landmarks = List.copyOf(landmarkPool.subList(0, Math.min(3, landmarkPool.size())));

        Map<String, Location> anchors = createAnchors(center.getWorld(), center.getBlockY(), center, streets, lots);
        Bounds bounds = computeBounds(lots, streets, cx, cz, settings.plazaSize());

        return new VillageLayoutPlan(List.copyOf(lots), List.copyOf(streets), bounds, Map.copyOf(anchors), houseCount(lots), landmarks);
    }

    public static boolean hasRoadAccess(LotPlan lot) {
        return switch (lot.facing()) {
            case NORTH -> lot.frontageZ() < lot.minZ();
            case SOUTH -> lot.frontageZ() > lot.maxZ();
            case EAST -> lot.frontageX() > lot.maxX();
            case WEST -> lot.frontageX() < lot.minX();
            default -> false;
        };
    }

    private static List<LotPlan> materializeLots(List<LotCandidate> candidates, Random random) {
        List<LotPlan> lots = new ArrayList<>();
        int row = 0;
        List<LotCandidate> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingInt(LotCandidate::centerZ).thenComparingInt(LotCandidate::centerX));
        for (LotCandidate candidate : ordered) {
            HouseSpec spec = candidate.role == LotRole.HOUSE_SINGLE || candidate.role == LotRole.HOUSE_TWO_STORY
                    ? createHouseSpec(candidate.archetype, candidate.role == LotRole.HOUSE_TWO_STORY, candidate.terraceY, random)
                    : null;
            LotPlan lot = createLotPlan(row / 4, row % 4, candidate.centerX, candidate.centerZ,
                    candidate.role, candidate.facing, spec, candidate.terraceY, candidate.yardDepth, candidate.cornerLot, random);
            if (overlapsExisting(lots, lot)) {
                lot = createLotPlan(row / 4, row % 4, candidate.centerX, candidate.centerZ,
                        candidate.role, candidate.facing, stabilizeSpec(spec), candidate.terraceY, candidate.yardDepth, candidate.cornerLot, new Random(0));
            }
            if (overlapsExisting(lots, lot) && (candidate.optional || candidate.role == LotRole.GREEN || candidate.role == LotRole.DECOR)) {
                continue;
            }
            lots.add(lot);
            row++;
        }
        return lots;
    }

    private static boolean overlapsExisting(List<LotPlan> lots, LotPlan probe) {
        return lots.stream().anyMatch(existing -> existing.overlapsWithGap(probe, MIN_GAP));
    }

    private static HouseSpec stabilizeSpec(HouseSpec spec) {
        if (spec == null) {
            return null;
        }
        return new HouseSpec(
                spec.archetype(),
                spec.footprintWidth(),
                spec.footprintDepth(),
                spec.wallHeight(),
                spec.twoStory(),
                spec.roofStyle(),
                spec.frontSetback(),
                0,
                spec.accentMaterial(),
                spec.facadeVariant(),
                spec.interiorVariant(),
                spec.hasPorch(),
                spec.hasDormer(),
                spec.foundationStep(),
                spec.yardStyle()
        );
    }

    private static Map<String, Location> createAnchors(World world, int y, Location center, List<StreetPlan> streets, List<LotPlan> lots) {
        Map<String, Location> anchors = new HashMap<>();
        anchors.put("center", center.clone());
        anchors.put("plaza", center.clone());
        StreetPlan mainStreet = streets.stream().filter(street -> street.type() == StreetType.MAIN).findFirst().orElse(null);
        if (mainStreet != null) {
            anchors.put("gate", new Location(world, mainStreet.startX(), y, mainStreet.startZ() - 2));
        }
        anchors.put("church", lotAnchor(world, y, lots, LotRole.CHURCH));
        anchors.put("forge", lotAnchor(world, y, lots, LotRole.FORGE));
        anchors.put("market", lotAnchor(world, y, lots, LotRole.MARKET));
        anchors.put("farm", lotAnchor(world, y, lots, LotRole.FARM));
        anchors.put("pen", lotAnchor(world, y, lots, LotRole.PEN));
        anchors.put("service_yard", lotAnchor(world, y, lots, LotRole.SERVICE_YARD));
        return anchors;
    }

    private static int houseCount(List<?> lotsOrCandidates) {
        int count = 0;
        for (Object item : lotsOrCandidates) {
            if (item instanceof LotCandidate candidate) {
                if (candidate.role == LotRole.HOUSE_SINGLE || candidate.role == LotRole.HOUSE_TWO_STORY) {
                    count++;
                }
            } else if (item instanceof LotPlan lot && lot.isHouse()) {
                count++;
            }
        }
        return count;
    }

    private static Bounds computeBounds(List<LotPlan> lots, List<StreetPlan> streets, int centerX, int centerZ, int plazaSize) {
        int minX = centerX - plazaSize;
        int maxX = centerX + plazaSize;
        int minZ = centerZ - plazaSize;
        int maxZ = centerZ + plazaSize;

        for (LotPlan lot : lots) {
            minX = Math.min(minX, lot.minX() - lot.yardDepth());
            maxX = Math.max(maxX, lot.maxX() + lot.yardDepth());
            minZ = Math.min(minZ, lot.minZ() - lot.yardDepth());
            maxZ = Math.max(maxZ, lot.maxZ() + lot.yardDepth());
        }
        for (StreetPlan street : streets) {
            minX = Math.min(minX, Math.min(street.startX(), street.endX()) - street.halfWidth());
            maxX = Math.max(maxX, Math.max(street.startX(), street.endX()) + street.halfWidth());
            minZ = Math.min(minZ, Math.min(street.startZ(), street.endZ()) - street.halfWidth());
            maxZ = Math.max(maxZ, Math.max(street.startZ(), street.endZ()) + street.halfWidth());
        }
        return new Bounds(minX, maxX, minZ, maxZ);
    }

    private static Location lotAnchor(World world, int y, List<LotPlan> lots, LotRole role) {
        return lots.stream()
                .filter(lot -> lot.role() == role)
                .findFirst()
                .map(lot -> new Location(world, lot.centerX(), y + lot.terraceY(), lot.centerZ()))
                .orElse(new Location(world, 0, y, 0));
    }

    private static HouseSpec createHouseSpec(HouseArchetype archetype, boolean forceTwoStory, int terraceY, Random random) {
        Material accent = VillageStyle.pickAccentPlanks(random);
        return switch (archetype) {
            case COTTAGE -> new HouseSpec(
                    archetype,
                    8,
                    8 + random.nextInt(2),
                    4,
                    false,
                    random.nextBoolean() ? RoofStyle.GABLE : RoofStyle.HIP,
                    2 + random.nextInt(2),
                    random.nextInt(3) - 1,
                    accent,
                    random.nextInt(3),
                    random.nextInt(4),
                    true,
                    random.nextBoolean(),
                    terraceY,
                    random.nextBoolean() ? YardStyle.FLOWERS : YardStyle.KITCHEN_GARDEN
            );
            case TOWNHOUSE -> new HouseSpec(
                    archetype,
                    7,
                    9,
                    6,
                    true,
                    random.nextBoolean() ? RoofStyle.GABLE : RoofStyle.SHED,
                    1 + random.nextInt(2),
                    random.nextBoolean() ? 0 : 1,
                    accent,
                    random.nextInt(3),
                    random.nextInt(4),
                    true,
                    true,
                    terraceY,
                    YardStyle.FENCED
            );
            case FAMILY_HOUSE -> new HouseSpec(
                    archetype,
                    10,
                    9 + random.nextInt(2),
                    5,
                    forceTwoStory || random.nextBoolean(),
                    random.nextBoolean() ? RoofStyle.OFFSET_GABLE : RoofStyle.HIP,
                    2,
                    random.nextInt(3) - 1,
                    accent,
                    random.nextInt(3),
                    random.nextInt(4),
                    true,
                    random.nextBoolean(),
                    terraceY,
                    random.nextBoolean() ? YardStyle.FENCED : YardStyle.KITCHEN_GARDEN
            );
            case WORKSHOP_HOUSE -> new HouseSpec(
                    archetype,
                    9,
                    8 + random.nextInt(2),
                    5,
                    forceTwoStory && random.nextBoolean(),
                    random.nextBoolean() ? RoofStyle.SHED : RoofStyle.GABLE,
                    2,
                    random.nextInt(3) - 1,
                    accent,
                    random.nextInt(3),
                    random.nextInt(4),
                    random.nextBoolean(),
                    random.nextBoolean(),
                    terraceY,
                    random.nextBoolean() ? YardStyle.WOODPILE : YardStyle.FENCED
            );
        };
    }

    private static LotPlan createLotPlan(int row, int col, int centerX, int centerZ,
                                         LotRole role, BlockFace facing, HouseSpec spec,
                                         int terraceY, int yardDepth, boolean cornerLot, Random random) {
        int width;
        int depth;
        int frontSetback;
        int lateralOffset;

        if (spec != null) {
            width = spec.footprintWidth();
            depth = spec.footprintDepth();
            frontSetback = spec.frontSetback();
            lateralOffset = spec.lateralOffset();
        } else {
            Map<LotRole, int[]> sizes = new EnumMap<>(LotRole.class);
            sizes.put(LotRole.CHURCH, new int[]{15, 19, 4});
            sizes.put(LotRole.FORGE, new int[]{13, 11, 3});
            sizes.put(LotRole.FARM, new int[]{11, 11, 3});
            sizes.put(LotRole.PEN, new int[]{10, 10, 3});
            sizes.put(LotRole.MARKET, new int[]{7, 7, 2});
            sizes.put(LotRole.GREEN, new int[]{8, 8, 3});
            sizes.put(LotRole.SERVICE_YARD, new int[]{10, 9, 3});
            sizes.put(LotRole.DECOR, new int[]{7, 7, 3});
            int[] dims = sizes.getOrDefault(role, new int[]{8, 8, 3});
            width = dims[0];
            depth = dims[1];
            frontSetback = dims[2];
            lateralOffset = role == LotRole.GREEN || role == LotRole.DECOR ? random.nextInt(3) - 1 : 0;
        }

        int buildX;
        int buildZ;
        int frontageX = centerX;
        int frontageZ = centerZ;

        switch (facing) {
            case NORTH -> {
                buildX = centerX - (width / 2) + lateralOffset;
                buildZ = centerZ + frontSetback;
                frontageZ = centerZ - 1;
                frontageX = centerX + lateralOffset;
            }
            case SOUTH -> {
                buildX = centerX - (width / 2) + lateralOffset;
                buildZ = centerZ - frontSetback - depth;
                frontageZ = centerZ + 1;
                frontageX = centerX + lateralOffset;
            }
            case EAST -> {
                buildX = centerX - frontSetback - width;
                buildZ = centerZ - (depth / 2) + lateralOffset;
                frontageX = centerX + 1;
                frontageZ = centerZ + lateralOffset;
            }
            case WEST -> {
                buildX = centerX + frontSetback;
                buildZ = centerZ - (depth / 2) + lateralOffset;
                frontageX = centerX - 1;
                frontageZ = centerZ + lateralOffset;
            }
            default -> {
                buildX = centerX - (width / 2);
                buildZ = centerZ + frontSetback;
            }
        }

        return new LotPlan(
                row,
                col,
                centerX,
                centerZ,
                role,
                facing,
                buildX,
                buildZ,
                width,
                depth,
                frontageX,
                frontageZ,
                spec,
                terraceY,
                yardDepth,
                cornerLot
        );
    }

    private record LotCandidate(
            LotRole role,
            int centerX,
            int centerZ,
            BlockFace facing,
            HouseArchetype archetype,
            boolean optional,
            int terraceY,
            int yardDepth,
            boolean cornerLot
    ) {}
}
