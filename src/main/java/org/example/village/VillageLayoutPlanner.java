package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.example.village.VillageLayoutPlan.Bounds;
import static org.example.village.VillageLayoutPlan.HouseArchetype;
import static org.example.village.VillageLayoutPlan.HouseSpec;
import static org.example.village.VillageLayoutPlan.LandmarkType;
import static org.example.village.VillageLayoutPlan.LotPlan;
import static org.example.village.VillageLayoutPlan.LotRole;
import static org.example.village.VillageLayoutPlan.RoofStyle;
import static org.example.village.VillageLayoutPlan.StreetPlan;
import static org.example.village.VillageLayoutPlan.StreetType;
import static org.example.village.VillageLayoutPlan.YardStyle;

/**
 * Produit un plan de village compact, hiérarchisé et réellement raccordé.
 *
 * <p>La composition suit une logique de village ancien : une rue principale,
 * deux rues de ceinture, des voies artisanales, une place de marché et un
 * parvis religieux. Les bâtiments sont implantés depuis le bord exact de leur
 * rue ; les chemins d'accès ne se terminent donc plus arbitrairement dans une
 * pelouse.</p>
 */
public final class VillageLayoutPlanner {
    private static final int MIN_GAP = 4;
    private static final int[] NUDGE_OFFSETS = {0, -3, 3, -6, 6, -9, 9};

    private VillageLayoutPlanner() {}

    public static VillageLayoutPlan plan(Location center, VillageLayoutSettings settings, Random rng) {
        if (center == null) {
            throw new IllegalArgumentException("Le centre du village est obligatoire.");
        }
        if (settings == null) {
            throw new IllegalArgumentException("Les paramètres du village sont obligatoires.");
        }

        Random random = rng == null ? new Random() : rng;
        int unit = settings.effectiveLotSpacing();
        int plazaHalf = settings.effectivePlazaSize() / 2;
        int cx = center.getBlockX();
        int cz = center.getBlockZ();

        int southLaneZ = cz + unit + 10;
        int northLaneZ = cz - unit - 10;
        int churchLaneZ = cz - (unit * 2) - 10;
        int outerStreetX = unit * 2 + 8;
        int farHouseX = unit * 3 - 2;
        int marketStreetZ = cz + plazaHalf + 2;
        int mainSouthZ = cz + unit * 3 + 4;

        List<StreetPlan> streets = List.of(
                // Épine dorsale : du portail sud au parvis de l'église.
                new StreetPlan(cx, mainSouthZ, cx, churchLaneZ, StreetType.MAIN, settings.mainStreetHalf()),

                // Deux rues de quartier forment une boucle lisible autour du centre.
                new StreetPlan(cx - farHouseX - 8, southLaneZ,
                        cx + farHouseX + 8, southLaneZ,
                        StreetType.SIDE, settings.sideStreetHalf()),
                new StreetPlan(cx - outerStreetX - 8, northLaneZ,
                        cx + outerStreetX + 8, northLaneZ,
                        StreetType.SIDE, settings.sideStreetHalf()),
                new StreetPlan(cx - outerStreetX, southLaneZ,
                        cx - outerStreetX, northLaneZ,
                        StreetType.SIDE, settings.sideStreetHalf()),
                new StreetPlan(cx + outerStreetX, southLaneZ,
                        cx + outerStreetX, northLaneZ,
                        StreetType.SIDE, settings.sideStreetHalf()),

                // Voies fonctionnelles courtes : marché, artisans et parvis.
                new StreetPlan(cx - plazaHalf - 14, marketStreetZ,
                        cx + plazaHalf + 14, marketStreetZ,
                        StreetType.SIDE, settings.sideStreetHalf()),
                new StreetPlan(cx - plazaHalf - 12, churchLaneZ,
                        cx + plazaHalf + 12, churchLaneZ,
                        StreetType.SIDE, settings.sideStreetHalf()),
                new StreetPlan(cx - outerStreetX, cz,
                        cx - plazaHalf - 10, cz,
                        StreetType.FOOTPATH, 0),
                new StreetPlan(cx + plazaHalf + 10, cz + 5,
                        cx + outerStreetX, cz + 5,
                        StreetType.FOOTPATH, 0)
        );

        int mainEdge = settings.mainStreetHalf() + 1;

        List<LotCandidate> fixed = List.of(
                new LotCandidate(LotRole.CHURCH, cx, churchLaneZ - 1, BlockFace.SOUTH,
                        HouseArchetype.COTTAGE, false, terrace(settings, 2), 4, true),
                new LotCandidate(LotRole.FORGE, cx + outerStreetX + 1, cz + 5, BlockFace.WEST,
                        HouseArchetype.WORKSHOP_HOUSE, false, terrace(settings, 1), 3, true),
                new LotCandidate(LotRole.INN, cx + mainEdge, cz - plazaHalf + 1, BlockFace.WEST,
                        HouseArchetype.TOWNHOUSE, false, 0, 3, true),
                new LotCandidate(LotRole.BAKERY, cx - mainEdge, cz - plazaHalf + 1, BlockFace.EAST,
                        HouseArchetype.COTTAGE, false, 0, 3, true),
                new LotCandidate(LotRole.FARM, cx + southLaneZ - cz + 2, southLaneZ + 1, BlockFace.NORTH,
                        HouseArchetype.COTTAGE, false, 0, 4, false),
                new LotCandidate(LotRole.PEN, cx - (southLaneZ - cz + 4), southLaneZ + 1, BlockFace.NORTH,
                        HouseArchetype.COTTAGE, false, 0, 4, false),
                new LotCandidate(LotRole.SERVICE_YARD, cx - outerStreetX - 1, cz, BlockFace.EAST,
                        HouseArchetype.WORKSHOP_HOUSE, false, 0, 3, false),
                new LotCandidate(LotRole.MARKET, cx - plazaHalf - 4, marketStreetZ + 1, BlockFace.NORTH,
                        HouseArchetype.COTTAGE, false, 0, 2, true),
                new LotCandidate(LotRole.MARKET, cx + plazaHalf + 4, marketStreetZ + 1, BlockFace.NORTH,
                        HouseArchetype.COTTAGE, false, 0, 2, true),
                new LotCandidate(LotRole.GREEN, cx - unit * 2, northLaneZ + 1, BlockFace.NORTH,
                        HouseArchetype.COTTAGE, false, terrace(settings, 1), 2, false),
                new LotCandidate(LotRole.DECOR, cx + unit * 2, northLaneZ + 1, BlockFace.NORTH,
                        HouseArchetype.COTTAGE, false, terrace(settings, 1), 2, false)
        );

        List<LotCandidate> coreHouses = List.of(
                // Deux silhouettes ferment la perspective depuis le portail.
                new LotCandidate(LotRole.HOUSE_SINGLE, cx - mainEdge, southLaneZ + unit + 6, BlockFace.EAST,
                        HouseArchetype.COTTAGE, false, terrace(settings, 1), 3, false),
                new LotCandidate(LotRole.HOUSE_TWO_STORY, cx + mainEdge, southLaneZ + unit + 6, BlockFace.WEST,
                        HouseArchetype.TOWNHOUSE, false, terrace(settings, 1), 3, false),

                // Deux maisons accompagnent la montée vers l'église.
                new LotCandidate(LotRole.HOUSE_TWO_STORY, cx - mainEdge, northLaneZ - 12, BlockFace.EAST,
                        HouseArchetype.FAMILY_HOUSE, false, terrace(settings, 1), 3, false),
                new LotCandidate(LotRole.HOUSE_TWO_STORY, cx + mainEdge, northLaneZ - 12, BlockFace.WEST,
                        HouseArchetype.TOWNHOUSE, false, terrace(settings, 1), 2, false),

                // Front nord de la rue méridionale.
                new LotCandidate(LotRole.HOUSE_TWO_STORY, cx - farHouseX, southLaneZ - 1, BlockFace.SOUTH,
                        HouseArchetype.TOWNHOUSE, false, 0, 3, true),
                new LotCandidate(LotRole.HOUSE_SINGLE, cx - unit - 7, southLaneZ - 1, BlockFace.SOUTH,
                        HouseArchetype.FAMILY_HOUSE, false, 0, 3, false),
                new LotCandidate(LotRole.HOUSE_SINGLE, cx + unit + 8, southLaneZ - 1, BlockFace.SOUTH,
                        HouseArchetype.WORKSHOP_HOUSE, false, 0, 3, false),
                new LotCandidate(LotRole.HOUSE_SINGLE, cx + farHouseX, southLaneZ - 1, BlockFace.SOUTH,
                        HouseArchetype.COTTAGE, false, 0, 4, true),

                // Front sud, proche des champs et de l'enclos.
                new LotCandidate(LotRole.HOUSE_SINGLE, cx - unit + 5, southLaneZ + 1, BlockFace.NORTH,
                        HouseArchetype.COTTAGE, false, 0, 4, false),
                new LotCandidate(LotRole.HOUSE_TWO_STORY, cx + unit - 8, southLaneZ + 1, BlockFace.NORTH,
                        HouseArchetype.FAMILY_HOUSE, false, 0, 3, false)
        );

        List<LotCandidate> optionalHouses = List.of(
                new LotCandidate(LotRole.HOUSE_SINGLE, cx - unit - 8, northLaneZ - 1, BlockFace.SOUTH,
                        HouseArchetype.COTTAGE, true, terrace(settings, 1), 3, false),
                new LotCandidate(LotRole.HOUSE_TWO_STORY, cx + unit + 8, northLaneZ - 1, BlockFace.SOUTH,
                        HouseArchetype.FAMILY_HOUSE, true, terrace(settings, 1), 3, false),
                new LotCandidate(LotRole.HOUSE_SINGLE, cx - unit + 2, northLaneZ + 1, BlockFace.NORTH,
                        HouseArchetype.WORKSHOP_HOUSE, true, 0, 3, false),
                new LotCandidate(LotRole.HOUSE_SINGLE, cx + unit - 2, northLaneZ + 1, BlockFace.NORTH,
                        HouseArchetype.TOWNHOUSE, true, 0, 2, false),
                new LotCandidate(LotRole.HOUSE_SINGLE, cx - outerStreetX - 1, cz - unit + 3, BlockFace.EAST,
                        HouseArchetype.COTTAGE, true, 0, 3, true),
                new LotCandidate(LotRole.HOUSE_SINGLE, cx + outerStreetX + 1, cz - unit + 6, BlockFace.WEST,
                        HouseArchetype.WORKSHOP_HOUSE, true, 0, 3, true)
        );

        int maxAvailableHouses = coreHouses.size() + optionalHouses.size();
        int configuredMax = Math.min(settings.houseCountMax(), maxAvailableHouses);
        int configuredMin = Math.min(configuredMax, Math.max(settings.houseCountMin(), coreHouses.size()));
        int targetHouseCount = configuredMin;
        if (configuredMax > configuredMin) {
            // Biais léger vers un village fourni sans rendre la configuration inopérante.
            int midpoint = (configuredMin + configuredMax + 1) / 2;
            targetHouseCount = midpoint + random.nextInt(configuredMax - midpoint + 1);
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
        for (LotPlan lot : lots) {
            if (!hasRoadAccess(lot, streets)) {
                throw new IllegalStateException("Lot sans accès réel à une rue : " + lot.role()
                        + " @ " + lot.centerX() + "," + lot.centerZ());
            }
        }

        List<LandmarkType> landmarkPool = new ArrayList<>(
                List.of(LandmarkType.STATUE, LandmarkType.GARDEN, LandmarkType.CHERRY));
        Collections.shuffle(landmarkPool, random);
        List<LandmarkType> landmarks = List.copyOf(landmarkPool);

        Map<String, Location> anchors = createAnchors(
                center.getWorld(), center.getBlockY(), center, streets, lots);
        Bounds bounds = computeBounds(
                lots, streets, cx, cz, settings.effectivePlazaSize());

        return new VillageLayoutPlan(
                List.copyOf(lots),
                List.copyOf(streets),
                bounds,
                Map.copyOf(anchors),
                houseCount(lots),
                landmarks
        );
    }

    /**
     * Vérification géométrique locale conservée pour compatibilité.
     */
    public static boolean hasRoadAccess(LotPlan lot) {
        return switch (lot.facing()) {
            case NORTH -> lot.frontageZ() < lot.minZ();
            case SOUTH -> lot.frontageZ() > lot.maxZ();
            case EAST -> lot.frontageX() > lot.maxX();
            case WEST -> lot.frontageX() < lot.minX();
            default -> false;
        };
    }

    /**
     * Vérifie en plus que la façade touche réellement une chaussée planifiée.
     */
    public static boolean hasRoadAccess(LotPlan lot, List<StreetPlan> streets) {
        return hasRoadAccess(lot)
                && streets != null
                && streets.stream().anyMatch(street ->
                street.contains(lot.frontageX(), lot.frontageZ(), 0));
    }

    private static List<LotPlan> materializeLots(List<LotCandidate> candidates, Random random) {
        List<LotPlan> lots = new ArrayList<>();
        int index = 0;

        for (LotCandidate candidate : candidates) {
            HouseSpec spec = createSpecForCandidate(candidate, random);
            LotPlan resolved = resolveCandidate(index, candidate, spec, lots);
            if (resolved == null && candidate.optional()) {
                continue;
            }
            if (resolved == null) {
                throw new IllegalStateException("Impossible d'implanter le lot obligatoire " + candidate.role());
            }
            lots.add(resolved);
            index++;
        }
        return lots;
    }

    private static LotPlan resolveCandidate(int index,
                                            LotCandidate candidate,
                                            HouseSpec originalSpec,
                                            List<LotPlan> existing) {
        List<HouseSpec> specs = originalSpec == null
                ? Collections.singletonList(null)
                : List.of(originalSpec, stabilizeSpec(originalSpec));

        for (HouseSpec spec : specs) {
            for (int offset : NUDGE_OFFSETS) {
                int roadX = candidate.roadX();
                int roadZ = candidate.roadZ();
                if (candidate.facing() == BlockFace.NORTH || candidate.facing() == BlockFace.SOUTH) {
                    roadX += offset;
                } else {
                    roadZ += offset;
                }

                LotPlan probe = createLotPlan(
                        index / 4,
                        index % 4,
                        roadX,
                        roadZ,
                        candidate.role(),
                        candidate.facing(),
                        spec,
                        candidate.terraceY(),
                        candidate.yardDepth(),
                        candidate.cornerLot()
                );
                if (!overlapsExisting(existing, probe)) {
                    return probe;
                }
            }
        }
        return null;
    }

    private static boolean overlapsExisting(List<LotPlan> lots, LotPlan probe) {
        return lots.stream().anyMatch(existing -> existing.overlapsWithGap(probe, MIN_GAP));
    }

    private static HouseSpec stabilizeSpec(HouseSpec spec) {
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

    private static HouseSpec createSpecForCandidate(LotCandidate candidate, Random random) {
        return switch (candidate.role()) {
            case HOUSE_SINGLE -> createHouseSpec(candidate.archetype(), false, candidate.terraceY(), random);
            case HOUSE_TWO_STORY -> createHouseSpec(candidate.archetype(), true, candidate.terraceY(), random);
            case INN -> new HouseSpec(
                    HouseArchetype.TOWNHOUSE,
                    11,
                    11,
                    7,
                    true,
                    RoofStyle.GABLE,
                    2,
                    0,
                    Material.DARK_OAK_PLANKS,
                    1,
                    2,
                    true,
                    true,
                    candidate.terraceY(),
                    YardStyle.FENCED
            );
            case BAKERY -> new HouseSpec(
                    HouseArchetype.COTTAGE,
                    9,
                    9,
                    5,
                    false,
                    RoofStyle.OFFSET_GABLE,
                    2,
                    0,
                    Material.SPRUCE_PLANKS,
                    2,
                    0,
                    true,
                    true,
                    candidate.terraceY(),
                    YardStyle.KITCHEN_GARDEN
            );
            default -> null;
        };
    }

    private static Map<String, Location> createAnchors(World world,
                                                        int y,
                                                        Location center,
                                                        List<StreetPlan> streets,
                                                        List<LotPlan> lots) {
        Map<String, Location> anchors = new HashMap<>();
        anchors.put("center", center.clone());
        anchors.put("plaza", center.clone());

        streets.stream()
                .filter(street -> street.type() == StreetType.MAIN)
                .findFirst()
                .ifPresent(main -> anchors.put(
                        "gate",
                        new Location(world, main.startX(), y, main.startZ())
                ));

        putLotAnchor(anchors, "church", world, y, lots, LotRole.CHURCH);
        putLotAnchor(anchors, "forge", world, y, lots, LotRole.FORGE);
        putLotAnchor(anchors, "inn", world, y, lots, LotRole.INN);
        putLotAnchor(anchors, "bakery", world, y, lots, LotRole.BAKERY);
        putLotAnchor(anchors, "market", world, y, lots, LotRole.MARKET);
        putLotAnchor(anchors, "farm", world, y, lots, LotRole.FARM);
        putLotAnchor(anchors, "pen", world, y, lots, LotRole.PEN);
        putLotAnchor(anchors, "service_yard", world, y, lots, LotRole.SERVICE_YARD);

        Location mayor = anchors.getOrDefault("inn", center).clone();
        anchors.put("mayor", mayor);
        return anchors;
    }

    private static void putLotAnchor(Map<String, Location> anchors,
                                     String key,
                                     World world,
                                     int y,
                                     List<LotPlan> lots,
                                     LotRole role) {
        lots.stream()
                .filter(lot -> lot.role() == role)
                .findFirst()
                .map(lot -> new Location(
                        world,
                        lot.centerX() + 0.5,
                        y + lot.terraceY() + 1,
                        lot.centerZ() + 0.5
                ))
                .ifPresent(location -> anchors.put(key, location));
    }

    private static int houseCount(List<?> lotsOrCandidates) {
        int count = 0;
        for (Object item : lotsOrCandidates) {
            if (item instanceof LotCandidate candidate) {
                if (candidate.role() == LotRole.HOUSE_SINGLE
                        || candidate.role() == LotRole.HOUSE_TWO_STORY) {
                    count++;
                }
            } else if (item instanceof LotPlan lot && lot.isHouse()) {
                count++;
            }
        }
        return count;
    }

    private static Bounds computeBounds(List<LotPlan> lots,
                                        List<StreetPlan> streets,
                                        int centerX,
                                        int centerZ,
                                        int plazaSize) {
        int half = plazaSize / 2;
        int minX = centerX - half;
        int maxX = centerX + half;
        int minZ = centerZ - half;
        int maxZ = centerZ + half;

        for (LotPlan lot : lots) {
            minX = Math.min(minX, lot.siteMinX());
            maxX = Math.max(maxX, lot.siteMaxX());
            minZ = Math.min(minZ, lot.siteMinZ());
            maxZ = Math.max(maxZ, lot.siteMaxZ());
        }
        for (StreetPlan street : streets) {
            minX = Math.min(minX, street.minX() - street.halfWidth() - 1);
            maxX = Math.max(maxX, street.maxX() + street.halfWidth() + 1);
            minZ = Math.min(minZ, street.minZ() - street.halfWidth() - 1);
            maxZ = Math.max(maxZ, street.maxZ() + street.halfWidth() + 1);
        }
        return new Bounds(minX, maxX, minZ, maxZ);
    }

    private static HouseSpec createHouseSpec(HouseArchetype archetype,
                                             boolean forceTwoStory,
                                             int terraceY,
                                             Random random) {
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

    private static LotPlan createLotPlan(int row,
                                         int col,
                                         int roadX,
                                         int roadZ,
                                         LotRole role,
                                         BlockFace facing,
                                         HouseSpec spec,
                                         int terraceY,
                                         int yardDepth,
                                         boolean cornerLot) {
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
            int[] dimensions = sizes.getOrDefault(role, new int[]{8, 8, 3});
            width = dimensions[0];
            depth = dimensions[1];
            frontSetback = dimensions[2];
            lateralOffset = 0;
        }

        int buildX;
        int buildZ;
        int frontageX = roadX;
        int frontageZ = roadZ;

        switch (facing) {
            case NORTH -> {
                buildX = roadX - (width / 2) + lateralOffset;
                buildZ = roadZ + frontSetback;
                frontageZ = roadZ - 1;
                frontageX = roadX + lateralOffset;
            }
            case SOUTH -> {
                buildX = roadX - (width / 2) + lateralOffset;
                buildZ = roadZ - frontSetback - depth;
                frontageZ = roadZ + 1;
                frontageX = roadX + lateralOffset;
            }
            case EAST -> {
                buildX = roadX - frontSetback - width;
                buildZ = roadZ - (depth / 2) + lateralOffset;
                frontageX = roadX + 1;
                frontageZ = roadZ + lateralOffset;
            }
            case WEST -> {
                buildX = roadX + frontSetback;
                buildZ = roadZ - (depth / 2) + lateralOffset;
                frontageX = roadX - 1;
                frontageZ = roadZ + lateralOffset;
            }
            default -> throw new IllegalArgumentException("Orientation de lot non horizontale : " + facing);
        }

        int actualCenterX = buildX + (width - 1) / 2;
        int actualCenterZ = buildZ + (depth - 1) / 2;

        return new LotPlan(
                row,
                col,
                actualCenterX,
                actualCenterZ,
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

    private static int terrace(VillageLayoutSettings settings, int desired) {
        return Math.min(Math.max(0, desired), settings.terrainMaxStep());
    }

    private record LotCandidate(
            LotRole role,
            int roadX,
            int roadZ,
            BlockFace facing,
            HouseArchetype archetype,
            boolean optional,
            int terraceY,
            int yardDepth,
            boolean cornerLot
    ) { }
}
