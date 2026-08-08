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
import static org.example.village.VillageLayoutPlan.PassagePlan;
import static org.example.village.VillageLayoutPlan.PassageStyle;
import static org.example.village.VillageLayoutPlan.RoofStyle;
import static org.example.village.VillageLayoutPlan.StreetPlan;
import static org.example.village.VillageLayoutPlan.StreetType;
import static org.example.village.VillageLayoutPlan.YardStyle;

/**
 * Produit un plan de ville médiévale dense, hiérarchisé et réellement raccordé.
 *
 * <p>La composition conserve une grand-rue lisible, mais ajoute des fronts
 * bâtis continus, deux axes de quartier, quatre ruelles et plusieurs passages
 * couverts. Les paramètres {@code rows} et {@code cols} pilotent désormais
 * réellement le nombre de positions candidates et la largeur du bourg.</p>
 */
public final class VillageLayoutPlanner {
    private static final int MIN_GAP = 4;
    private static final int[] NUDGE_OFFSETS = {
            0, -2, 2, -4, 4, -6, 6, -8, 8, -10, 10
    };

    private VillageLayoutPlanner() {}

    public static VillageLayoutPlan plan(Location center,
                                         VillageLayoutSettings settings,
                                         Random rng) {
        if (center == null) {
            throw new IllegalArgumentException(
                    "Le centre du village est obligatoire."
            );
        }
        if (settings == null) {
            throw new IllegalArgumentException(
                    "Les paramètres du village sont obligatoires."
            );
        }

        Random random = rng == null ? new Random() : rng;
        int unit = settings.effectiveLotSpacing();
        int plazaHalf = settings.effectivePlazaSize() / 2;
        int cx = center.getBlockX();
        int cz = center.getBlockZ();

        /*
         * Avec sept colonnes par défaut, la ville gagne deux vrais quartiers
         * latéraux sans simplement étirer les pelouses entre les bâtiments.
         */
        int columnRadius = Math.max(2, (settings.cols() - 1) / 2);
        int southLaneZ = cz + unit + 10;
        int northLaneZ = cz - unit - 10;
        int churchLaneZ = cz - (unit * 2) - 10;
        int outerStreetX = unit * columnRadius + 8;
        int farHouseX = outerStreetX + Math.max(8, unit / 2);
        int marketStreetZ = cz + plazaHalf + 2;
        int mainSouthZ = cz + unit * 3 + 4;
        int sideNorthZ = northLaneZ - 10;
        int sideSouthZ = southLaneZ + 10;
        int streetEdgeX = farHouseX + 10;

        List<StreetPlan> streets = new ArrayList<>();

        // Épine dorsale : du portail sud au parvis religieux.
        streets.add(new StreetPlan(
                cx,
                mainSouthZ,
                cx,
                churchLaneZ,
                StreetType.MAIN,
                settings.mainStreetHalf()
        ));

        // Rues de quartier : elles forment une boucle complète et praticable.
        streets.add(new StreetPlan(
                cx - streetEdgeX,
                southLaneZ,
                cx + streetEdgeX,
                southLaneZ,
                StreetType.SIDE,
                settings.sideStreetHalf()
        ));
        streets.add(new StreetPlan(
                cx - outerStreetX - 10,
                northLaneZ,
                cx + outerStreetX + 10,
                northLaneZ,
                StreetType.SIDE,
                settings.sideStreetHalf()
        ));
        streets.add(new StreetPlan(
                cx - outerStreetX,
                sideSouthZ,
                cx - outerStreetX,
                sideNorthZ,
                StreetType.SIDE,
                settings.sideStreetHalf()
        ));
        streets.add(new StreetPlan(
                cx + outerStreetX,
                sideSouthZ,
                cx + outerStreetX,
                sideNorthZ,
                StreetType.SIDE,
                settings.sideStreetHalf()
        ));

        // Voies fonctionnelles : marché, parvis et liaisons artisanales.
        streets.add(new StreetPlan(
                cx - plazaHalf - 14,
                marketStreetZ,
                cx + plazaHalf + 14,
                marketStreetZ,
                StreetType.SIDE,
                settings.sideStreetHalf()
        ));
        streets.add(new StreetPlan(
                cx - plazaHalf - 12,
                churchLaneZ,
                cx + plazaHalf + 12,
                churchLaneZ,
                StreetType.SIDE,
                settings.sideStreetHalf()
        ));

        addMedievalAlleys(
                streets,
                cx,
                cz,
                outerStreetX,
                plazaHalf
        );

        int mainEdge = settings.mainStreetHalf() + 1;

        List<LotCandidate> fixed = new ArrayList<>();
        fixed.add(new LotCandidate(
                LotRole.CHURCH,
                cx,
                churchLaneZ - 1,
                BlockFace.SOUTH,
                HouseArchetype.COTTAGE,
                false,
                terrace(settings, 2),
                4,
                true
        ));
        fixed.add(new LotCandidate(
                LotRole.TOWN_HALL,
                cx,
                northLaneZ + 1,
                BlockFace.NORTH,
                HouseArchetype.TOWNHOUSE,
                false,
                terrace(settings, 1),
                2,
                true
        ));
        fixed.add(new LotCandidate(
                LotRole.FORGE,
                cx + outerStreetX + 1,
                cz + 4,
                BlockFace.WEST,
                HouseArchetype.WORKSHOP_HOUSE,
                false,
                terrace(settings, 1),
                3,
                true
        ));
        fixed.add(new LotCandidate(
                LotRole.CARPENTER,
                cx - outerStreetX - 1,
                cz + unit - 2,
                BlockFace.EAST,
                HouseArchetype.WORKSHOP_HOUSE,
                false,
                0,
                2,
                false
        ));
        fixed.add(new LotCandidate(
                LotRole.STABLE,
                cx + outerStreetX + 1,
                cz + unit + 4,
                BlockFace.WEST,
                HouseArchetype.WORKSHOP_HOUSE,
                false,
                0,
                2,
                false
        ));
        fixed.add(new LotCandidate(
                LotRole.INN,
                cx + mainEdge,
                cz - plazaHalf + 1,
                BlockFace.WEST,
                HouseArchetype.TOWNHOUSE,
                false,
                0,
                3,
                true
        ));
        fixed.add(new LotCandidate(
                LotRole.BAKERY,
                cx - mainEdge,
                cz - plazaHalf + 1,
                BlockFace.EAST,
                HouseArchetype.COTTAGE,
                false,
                0,
                3,
                true
        ));
        fixed.add(new LotCandidate(
                LotRole.FARM,
                cx + unit + 12,
                southLaneZ + 1,
                BlockFace.NORTH,
                HouseArchetype.COTTAGE,
                false,
                0,
                4,
                false
        ));
        fixed.add(new LotCandidate(
                LotRole.PEN,
                cx - unit - 14,
                southLaneZ + 1,
                BlockFace.NORTH,
                HouseArchetype.COTTAGE,
                false,
                0,
                4,
                false
        ));
        fixed.add(new LotCandidate(
                LotRole.SERVICE_YARD,
                cx - outerStreetX - 1,
                cz - 2,
                BlockFace.EAST,
                HouseArchetype.WORKSHOP_HOUSE,
                false,
                0,
                3,
                false
        ));
        fixed.add(new LotCandidate(
                LotRole.MARKET,
                cx - plazaHalf - 4,
                marketStreetZ + 1,
                BlockFace.NORTH,
                HouseArchetype.COTTAGE,
                false,
                0,
                2,
                true
        ));
        fixed.add(new LotCandidate(
                LotRole.MARKET,
                cx + plazaHalf + 4,
                marketStreetZ + 1,
                BlockFace.NORTH,
                HouseArchetype.COTTAGE,
                false,
                0,
                2,
                true
        ));
        fixed.add(new LotCandidate(
                LotRole.GREEN,
                cx - unit * 2,
                northLaneZ + 1,
                BlockFace.NORTH,
                HouseArchetype.COTTAGE,
                false,
                terrace(settings, 1),
                2,
                false
        ));
        fixed.add(new LotCandidate(
                LotRole.DECOR,
                cx + unit * 2,
                northLaneZ + 1,
                BlockFace.NORTH,
                HouseArchetype.COTTAGE,
                false,
                terrace(settings, 1),
                2,
                false
        ));

        /*
         * Le noyau résidentiel est obligatoire : il ferme les perspectives
         * principales même lorsque houseCountMin est configuré très bas.
         */
        List<LotCandidate> coreHouses = List.of(
                new LotCandidate(
                        LotRole.HOUSE_SINGLE,
                        cx - mainEdge,
                        southLaneZ + unit + 6,
                        BlockFace.EAST,
                        HouseArchetype.COTTAGE,
                        false,
                        terrace(settings, 1),
                        2,
                        false
                ),
                new LotCandidate(
                        LotRole.HOUSE_TWO_STORY,
                        cx + mainEdge,
                        southLaneZ + unit + 6,
                        BlockFace.WEST,
                        HouseArchetype.TOWNHOUSE,
                        false,
                        terrace(settings, 1),
                        2,
                        false
                ),
                new LotCandidate(
                        LotRole.HOUSE_TWO_STORY,
                        cx - mainEdge,
                        northLaneZ - 12,
                        BlockFace.EAST,
                        HouseArchetype.FAMILY_HOUSE,
                        false,
                        terrace(settings, 1),
                        2,
                        false
                ),
                new LotCandidate(
                        LotRole.HOUSE_TWO_STORY,
                        cx + mainEdge,
                        northLaneZ - 12,
                        BlockFace.WEST,
                        HouseArchetype.TOWNHOUSE,
                        false,
                        terrace(settings, 1),
                        2,
                        false
                ),
                new LotCandidate(
                        LotRole.HOUSE_SINGLE,
                        cx - unit - 7,
                        southLaneZ - 1,
                        BlockFace.SOUTH,
                        HouseArchetype.FAMILY_HOUSE,
                        false,
                        0,
                        2,
                        false
                ),
                new LotCandidate(
                        LotRole.HOUSE_SINGLE,
                        cx + unit + 8,
                        southLaneZ - 1,
                        BlockFace.SOUTH,
                        HouseArchetype.WORKSHOP_HOUSE,
                        false,
                        0,
                        2,
                        false
                ),
                new LotCandidate(
                        LotRole.HOUSE_SINGLE,
                        cx - unit + 5,
                        southLaneZ + 1,
                        BlockFace.NORTH,
                        HouseArchetype.COTTAGE,
                        false,
                        0,
                        2,
                        false
                ),
                new LotCandidate(
                        LotRole.HOUSE_TWO_STORY,
                        cx + unit - 8,
                        southLaneZ + 1,
                        BlockFace.NORTH,
                        HouseArchetype.FAMILY_HOUSE,
                        false,
                        0,
                        2,
                        false
                )
        );

        List<LotCandidate> optionalHouses = createResidentialCandidates(
                settings,
                cx,
                cz,
                unit,
                southLaneZ,
                northLaneZ,
                outerStreetX,
                farHouseX
        );

        int maxAvailableHouses = coreHouses.size()
                + optionalHouses.size();
        int configuredMax = Math.min(
                settings.houseCountMax(),
                maxAvailableHouses
        );
        int configuredMin = Math.min(
                configuredMax,
                Math.max(settings.houseCountMin(), coreHouses.size())
        );
        int targetHouseCount = configuredMin;
        if (configuredMax > configuredMin) {
            /*
             * Biais vers une ville fournie, sans rendre houseCountMin
             * inopérant ni toujours forcer la valeur maximale.
             */
            int midpoint = (configuredMin + configuredMax + 1) / 2;
            targetHouseCount = midpoint
                    + random.nextInt(configuredMax - midpoint + 1);
        }

        List<LotCandidate> mandatory = new ArrayList<>(fixed);
        mandatory.addAll(coreHouses);
        List<LotPlan> lots = materializeLots(
                mandatory,
                optionalHouses,
                streets,
                settings,
                targetHouseCount,
                random
        );

        for (LotPlan lot : lots) {
            if (!hasRoadAccess(lot, streets)) {
                throw new IllegalStateException(
                        "Lot sans accès réel à une rue : "
                                + lot.role()
                                + " @ "
                                + lot.centerX()
                                + ","
                                + lot.centerZ()
                );
            }
        }

        List<PassagePlan> passages = planCoveredPassages(
                streets,
                lots,
                settings,
                random
        );

        List<LandmarkType> landmarkPool = new ArrayList<>(
                List.of(
                        LandmarkType.STATUE,
                        LandmarkType.GARDEN,
                        LandmarkType.CHERRY
                )
        );
        Collections.shuffle(landmarkPool, random);
        List<LandmarkType> landmarks = List.copyOf(landmarkPool);

        Map<String, Location> anchors = createAnchors(
                center.getWorld(),
                center.getBlockY(),
                center,
                streets,
                lots
        );
        Bounds bounds = computeBounds(
                lots,
                streets,
                passages,
                cx,
                cz,
                settings.effectivePlazaSize()
        );

        return new VillageLayoutPlan(
                List.copyOf(lots),
                List.copyOf(streets),
                List.copyOf(passages),
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
    public static boolean hasRoadAccess(LotPlan lot,
                                        List<StreetPlan> streets) {
        return hasRoadAccess(lot)
                && streets != null
                && streets.stream().anyMatch(street ->
                street.contains(
                        lot.frontageX(),
                        lot.frontageZ(),
                        0
                ));
    }

    private static void addMedievalAlleys(List<StreetPlan> streets,
                                           int cx,
                                           int cz,
                                           int outerStreetX,
                                           int plazaHalf) {
        int innerEdge = plazaHalf + 12;
        int westX = cx - outerStreetX;
        int eastX = cx + outerStreetX;

        /*
         * Les ruelles sont volontairement séparées autour de la place : elles
         * débouchent dans le centre sans traverser le puits ou les étals.
         */
        streets.add(new StreetPlan(
                westX,
                cz - 10,
                cx - innerEdge,
                cz - 10,
                StreetType.ALLEY,
                0
        ));
        streets.add(new StreetPlan(
                cx + innerEdge,
                cz - 10,
                eastX,
                cz - 10,
                StreetType.ALLEY,
                0
        ));
        streets.add(new StreetPlan(
                westX,
                cz + 12,
                cx - innerEdge,
                cz + 12,
                StreetType.ALLEY,
                0
        ));
        streets.add(new StreetPlan(
                cx + innerEdge,
                cz + 12,
                eastX,
                cz + 12,
                StreetType.ALLEY,
                0
        ));

        // Deux venelles verticales raccourcissent les trajets entre quartiers.
        int alleyX = Math.max(plazaHalf + 13, outerStreetX / 2);
        streets.add(new StreetPlan(
                cx - alleyX,
                cz - 10,
                cx - alleyX,
                cz + 12,
                StreetType.FOOTPATH,
                0
        ));
        streets.add(new StreetPlan(
                cx + alleyX,
                cz - 10,
                cx + alleyX,
                cz + 12,
                StreetType.FOOTPATH,
                0
        ));
    }

    private static List<LotCandidate> createResidentialCandidates(
            VillageLayoutSettings settings,
            int cx,
            int cz,
            int unit,
            int southLaneZ,
            int northLaneZ,
            int outerStreetX,
            int farHouseX) {
        List<LotCandidate> candidates = new ArrayList<>();
        List<Integer> columnOffsets = symmetricOffsets(
                settings.cols(),
                unit
        );

        int sequence = 0;
        for (int offset : columnOffsets) {
            if (Math.abs(offset) < unit - 2) {
                continue;
            }

            candidates.add(houseCandidate(
                    cx + offset,
                    southLaneZ - 1,
                    BlockFace.SOUTH,
                    sequence++,
                    0,
                    2
            ));
            candidates.add(houseCandidate(
                    cx + offset,
                    southLaneZ + 1,
                    BlockFace.NORTH,
                    sequence++,
                    0,
                    2
            ));
            candidates.add(houseCandidate(
                    cx + offset,
                    northLaneZ - 1,
                    BlockFace.SOUTH,
                    sequence++,
                    1,
                    2
            ));
            candidates.add(houseCandidate(
                    cx + offset,
                    northLaneZ + 1,
                    BlockFace.NORTH,
                    sequence++,
                    0,
                    2
            ));
        }

        /*
         * Les deux extrémités des fronts sont légèrement décalées pour casser
         * la symétrie et occuper la largeur ajoutée par cols.
         */
        candidates.add(houseCandidate(
                cx - farHouseX,
                southLaneZ - 1,
                BlockFace.SOUTH,
                sequence++,
                0,
                2
        ));
        candidates.add(houseCandidate(
                cx + farHouseX,
                southLaneZ - 1,
                BlockFace.SOUTH,
                sequence++,
                0,
                2
        ));
        candidates.add(houseCandidate(
                cx - farHouseX + 4,
                northLaneZ - 1,
                BlockFace.SOUTH,
                sequence++,
                1,
                2
        ));
        candidates.add(houseCandidate(
                cx + farHouseX - 4,
                northLaneZ - 1,
                BlockFace.SOUTH,
                sequence++,
                1,
                2
        ));

        List<Integer> rowOffsets = symmetricOffsets(
                settings.rows(),
                Math.max(10, unit / 2)
        );
        for (int offset : rowOffsets) {
            if (Math.abs(offset) > unit + 4) {
                continue;
            }
            int roadZ = cz + offset;

            // Façades extérieures et intérieures de la rue occidentale.
            candidates.add(houseCandidate(
                    cx - outerStreetX,
                    roadZ,
                    BlockFace.EAST,
                    sequence++,
                    Math.floorMod(sequence, 2),
                    2
            ));
            candidates.add(houseCandidate(
                    cx - outerStreetX,
                    roadZ,
                    BlockFace.WEST,
                    sequence++,
                    0,
                    1
            ));

            // Façades extérieures et intérieures de la rue orientale.
            candidates.add(houseCandidate(
                    cx + outerStreetX,
                    roadZ,
                    BlockFace.WEST,
                    sequence++,
                    Math.floorMod(sequence, 2),
                    2
            ));
            candidates.add(houseCandidate(
                    cx + outerStreetX,
                    roadZ,
                    BlockFace.EAST,
                    sequence++,
                    0,
                    1
            ));
        }

        return candidates;
    }

    private static LotCandidate houseCandidate(int roadX,
                                                int roadZ,
                                                BlockFace facing,
                                                int sequence,
                                                int terraceY,
                                                int yardDepth) {
        HouseArchetype[] archetypes = HouseArchetype.values();
        HouseArchetype archetype = archetypes[
                Math.floorMod(sequence, archetypes.length)
                ];
        LotRole role = sequence % 3 == 0
                ? LotRole.HOUSE_TWO_STORY
                : LotRole.HOUSE_SINGLE;
        return new LotCandidate(
                role,
                roadX,
                roadZ,
                facing,
                archetype,
                true,
                terraceY,
                yardDepth,
                sequence % 4 == 0
        );
    }

    private static List<Integer> symmetricOffsets(int count,
                                                   int step) {
        List<Integer> offsets = new ArrayList<>();
        double center = (count - 1) / 2.0D;
        for (int index = 0; index < count; index++) {
            offsets.add((int) Math.round((index - center) * step));
        }
        return offsets;
    }

    private static List<LotPlan> materializeLots(
            List<LotCandidate> mandatory,
            List<LotCandidate> optional,
            List<StreetPlan> streets,
            VillageLayoutSettings settings,
            int targetHouseCount,
            Random random) {
        List<LotPlan> lots = new ArrayList<>();

        for (LotCandidate candidate : mandatory) {
            HouseSpec spec = createSpecForCandidate(
                    candidate,
                    settings,
                    random
            );
            LotPlan resolved = resolveCandidate(
                    lots.size(),
                    settings.cols(),
                    candidate,
                    spec,
                    lots,
                    streets
            );
            if (resolved == null) {
                throw new IllegalStateException(
                        "Impossible d'implanter le lot obligatoire "
                                + candidate.role()
                );
            }
            lots.add(resolved);
        }

        List<LotCandidate> shuffled = new ArrayList<>(optional);
        Collections.shuffle(shuffled, random);
        for (LotCandidate candidate : shuffled) {
            if (houseCount(lots) >= targetHouseCount) {
                break;
            }

            HouseSpec spec = createSpecForCandidate(
                    candidate,
                    settings,
                    random
            );
            LotPlan resolved = resolveCandidate(
                    lots.size(),
                    settings.cols(),
                    candidate,
                    spec,
                    lots,
                    streets
            );
            if (resolved != null) {
                lots.add(resolved);
            }
        }

        /*
         * houseCountMin reste une cible, pas une raison d'annuler toute la
         * commande. Avec une combinaison extrême (petite grille, maisons très
         * larges), les contrôles de collision peuvent légitimement éliminer
         * certains candidats. Le plan maximal sûr est alors préférable à une
         * ville partiellement construite suivie d'une exception.
         */
        return lots;
    }

    private static LotPlan resolveCandidate(int index,
                                            int columns,
                                            LotCandidate candidate,
                                            HouseSpec originalSpec,
                                            List<LotPlan> existing,
                                            List<StreetPlan> streets) {
        List<HouseSpec> specs = originalSpec == null
                ? Collections.singletonList(null)
                : List.of(
                        originalSpec,
                        stabilizeSpec(originalSpec)
                );

        for (HouseSpec spec : specs) {
            for (int offset : NUDGE_OFFSETS) {
                int roadX = candidate.roadX();
                int roadZ = candidate.roadZ();
                if (candidate.facing() == BlockFace.NORTH
                        || candidate.facing() == BlockFace.SOUTH) {
                    roadX += offset;
                } else {
                    roadZ += offset;
                }

                LotPlan probe = createLotPlan(
                        index / Math.max(1, columns),
                        index % Math.max(1, columns),
                        roadX,
                        roadZ,
                        candidate.role(),
                        candidate.facing(),
                        spec,
                        candidate.terraceY(),
                        candidate.yardDepth(),
                        candidate.cornerLot()
                );
                if (!overlapsExisting(existing, probe)
                        && hasRoadAccess(probe, streets)) {
                    return probe;
                }
            }
        }
        return null;
    }

    private static boolean overlapsExisting(List<LotPlan> lots,
                                            LotPlan probe) {
        return lots.stream().anyMatch(existing ->
                existing.overlapsWithGap(probe, MIN_GAP)
        );
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

    private static HouseSpec createSpecForCandidate(
            LotCandidate candidate,
            VillageLayoutSettings settings,
            Random random) {
        return switch (candidate.role()) {
            case HOUSE_SINGLE -> createHouseSpec(
                    candidate.archetype(),
                    false,
                    candidate.terraceY(),
                    settings,
                    random
            );
            case HOUSE_TWO_STORY -> createHouseSpec(
                    candidate.archetype(),
                    true,
                    candidate.terraceY(),
                    settings,
                    random
            );
            case INN -> new HouseSpec(
                    HouseArchetype.TOWNHOUSE,
                    Math.max(11, settings.houseBig()),
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
                    Math.max(9, settings.houseSmall()),
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
            case TOWN_HALL -> new HouseSpec(
                    HouseArchetype.TOWNHOUSE,
                    Math.max(13, settings.houseBig()),
                    11,
                    7,
                    true,
                    RoofStyle.GABLE,
                    2,
                    0,
                    Material.DARK_OAK_PLANKS,
                    2,
                    3,
                    true,
                    true,
                    candidate.terraceY(),
                    YardStyle.FENCED
            );
            case CARPENTER -> new HouseSpec(
                    HouseArchetype.WORKSHOP_HOUSE,
                    Math.max(10, settings.houseSmall()),
                    9,
                    5,
                    false,
                    RoofStyle.SHED,
                    2,
                    0,
                    Material.SPRUCE_PLANKS,
                    1,
                    1,
                    true,
                    false,
                    candidate.terraceY(),
                    YardStyle.WOODPILE
            );
            default -> null;
        };
    }

    private static List<PassagePlan> planCoveredPassages(
            List<StreetPlan> streets,
            List<LotPlan> lots,
            VillageLayoutSettings settings,
            Random random) {
        List<PassagePlan> result = new ArrayList<>();
        int target = settings.passageBudget();

        for (StreetPlan street : streets) {
            if (street.type() != StreetType.ALLEY
                    || result.size() >= target) {
                continue;
            }

            int length = street.horizontal()
                    ? street.maxX() - street.minX()
                    : street.maxZ() - street.minZ();
            if (length < 12) {
                continue;
            }

            int[] fractions = {1, 2, 3};
            for (int fraction : fractions) {
                if (result.size() >= target) {
                    break;
                }

                int x = street.horizontal()
                        ? street.minX() + (length * fraction) / 4
                        : street.startX();
                int z = street.horizontal()
                        ? street.startZ()
                        : street.minZ() + (length * fraction) / 4;

                PassagePlan passage = new PassagePlan(
                        x,
                        z,
                        street.horizontal()
                                ? BlockFace.EAST
                                : BlockFace.SOUTH,
                        street.halfWidth(),
                        random.nextBoolean()
                                ? PassageStyle.TIMBER
                                : PassageStyle.STONE
                );
                if (passageFits(passage, lots, result)) {
                    result.add(passage);
                }
            }
        }
        return result;
    }

    private static boolean passageFits(PassagePlan passage,
                                       List<LotPlan> lots,
                                       List<PassagePlan> existing) {
        for (LotPlan lot : lots) {
            for (int x = passage.structureMinX();
                 x <= passage.structureMaxX();
                 x++) {
                for (int z = passage.structureMinZ();
                     z <= passage.structureMaxZ();
                     z++) {
                    if (x >= lot.reservedMinX()
                            && x <= lot.reservedMaxX()
                            && z >= lot.reservedMinZ()
                            && z <= lot.reservedMaxZ()) {
                        return false;
                    }
                }
            }
        }

        for (PassagePlan other : existing) {
            int dx = passage.centerX() - other.centerX();
            int dz = passage.centerZ() - other.centerZ();
            if (dx * dx + dz * dz < 100) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Location> createAnchors(
            World world,
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
                        new Location(
                                world,
                                main.startX(),
                                y,
                                main.startZ()
                        )
                ));

        putLotAnchor(
                anchors,
                "church",
                world,
                y,
                lots,
                LotRole.CHURCH
        );
        putLotAnchor(
                anchors,
                "forge",
                world,
                y,
                lots,
                LotRole.FORGE
        );
        putLotAnchor(
                anchors,
                "inn",
                world,
                y,
                lots,
                LotRole.INN
        );
        putLotAnchor(
                anchors,
                "bakery",
                world,
                y,
                lots,
                LotRole.BAKERY
        );
        putLotAnchor(
                anchors,
                "town_hall",
                world,
                y,
                lots,
                LotRole.TOWN_HALL
        );
        putLotAnchor(
                anchors,
                "carpenter",
                world,
                y,
                lots,
                LotRole.CARPENTER
        );
        putLotAnchor(
                anchors,
                "stable",
                world,
                y,
                lots,
                LotRole.STABLE
        );
        putLotAnchor(
                anchors,
                "market",
                world,
                y,
                lots,
                LotRole.MARKET
        );
        putLotAnchor(
                anchors,
                "farm",
                world,
                y,
                lots,
                LotRole.FARM
        );
        putLotAnchor(
                anchors,
                "pen",
                world,
                y,
                lots,
                LotRole.PEN
        );
        putLotAnchor(
                anchors,
                "service_yard",
                world,
                y,
                lots,
                LotRole.SERVICE_YARD
        );

        int residenceIndex = 0;
        for (LotPlan lot : lots) {
            if (!lot.isHouse()) {
                continue;
            }
            anchors.put(
                    "residence_" + residenceIndex++,
                    new Location(
                            world,
                            lot.centerX() + 0.5D,
                            y + lot.terraceY() + 1.0D,
                            lot.centerZ() + 0.5D
                    )
            );
        }

        Location mayor = anchors.getOrDefault(
                "town_hall",
                anchors.getOrDefault("inn", center)
        ).clone();
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
                        || candidate.role()
                        == LotRole.HOUSE_TWO_STORY) {
                    count++;
                }
            } else if (item instanceof LotPlan lot
                    && lot.isHouse()) {
                count++;
            }
        }
        return count;
    }

    private static Bounds computeBounds(List<LotPlan> lots,
                                        List<StreetPlan> streets,
                                        List<PassagePlan> passages,
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
            minX = Math.min(
                    minX,
                    street.minX() - street.halfWidth() - 1
            );
            maxX = Math.max(
                    maxX,
                    street.maxX() + street.halfWidth() + 1
            );
            minZ = Math.min(
                    minZ,
                    street.minZ() - street.halfWidth() - 1
            );
            maxZ = Math.max(
                    maxZ,
                    street.maxZ() + street.halfWidth() + 1
            );
        }
        for (PassagePlan passage : passages) {
            minX = Math.min(minX, passage.structureMinX());
            maxX = Math.max(maxX, passage.structureMaxX());
            minZ = Math.min(minZ, passage.structureMinZ());
            maxZ = Math.max(maxZ, passage.structureMaxZ());
        }
        return new Bounds(minX, maxX, minZ, maxZ);
    }

    private static HouseSpec createHouseSpec(
            HouseArchetype archetype,
            boolean forceTwoStory,
            int terraceY,
            VillageLayoutSettings settings,
            Random random) {
        Material accent = VillageStyle.pickAccentPlanks(random);
        int small = settings.houseSmall();
        int big = settings.houseBig();

        return switch (archetype) {
            case COTTAGE -> new HouseSpec(
                    archetype,
                    Math.max(8, small - 1),
                    Math.max(8, small - 1)
                            + random.nextInt(2),
                    4,
                    false,
                    random.nextBoolean()
                            ? RoofStyle.GABLE
                            : RoofStyle.HIP,
                    2,
                    random.nextInt(3) - 1,
                    accent,
                    random.nextInt(3),
                    random.nextInt(4),
                    true,
                    random.nextBoolean(),
                    terraceY,
                    random.nextBoolean()
                            ? YardStyle.FLOWERS
                            : YardStyle.KITCHEN_GARDEN
            );
            case TOWNHOUSE -> new HouseSpec(
                    archetype,
                    Math.max(7, small - 2),
                    Math.max(9, small),
                    6,
                    true,
                    random.nextBoolean()
                            ? RoofStyle.GABLE
                            : RoofStyle.SHED,
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
                    Math.max(10, big - 1),
                    Math.max(9, small)
                            + random.nextInt(2),
                    5,
                    forceTwoStory || random.nextBoolean(),
                    random.nextBoolean()
                            ? RoofStyle.OFFSET_GABLE
                            : RoofStyle.HIP,
                    2,
                    random.nextInt(3) - 1,
                    accent,
                    random.nextInt(3),
                    random.nextInt(4),
                    true,
                    random.nextBoolean(),
                    terraceY,
                    random.nextBoolean()
                            ? YardStyle.FENCED
                            : YardStyle.KITCHEN_GARDEN
            );
            case WORKSHOP_HOUSE -> new HouseSpec(
                    archetype,
                    Math.max(9, small),
                    Math.max(8, small - 1)
                            + random.nextInt(2),
                    5,
                    forceTwoStory && random.nextBoolean(),
                    random.nextBoolean()
                            ? RoofStyle.SHED
                            : RoofStyle.GABLE,
                    2,
                    random.nextInt(3) - 1,
                    accent,
                    random.nextInt(3),
                    random.nextInt(4),
                    random.nextBoolean(),
                    random.nextBoolean(),
                    terraceY,
                    random.nextBoolean()
                            ? YardStyle.WOODPILE
                            : YardStyle.FENCED
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
            Map<LotRole, int[]> sizes =
                    new EnumMap<>(LotRole.class);
            sizes.put(
                    LotRole.CHURCH,
                    new int[]{15, 19, 4}
            );
            sizes.put(
                    LotRole.FORGE,
                    new int[]{13, 11, 3}
            );
            sizes.put(
                    LotRole.STABLE,
                    new int[]{15, 11, 3}
            );
            sizes.put(
                    LotRole.FARM,
                    new int[]{11, 11, 3}
            );
            sizes.put(
                    LotRole.PEN,
                    new int[]{10, 10, 3}
            );
            sizes.put(
                    LotRole.MARKET,
                    new int[]{7, 7, 2}
            );
            sizes.put(
                    LotRole.GREEN,
                    new int[]{8, 8, 3}
            );
            sizes.put(
                    LotRole.SERVICE_YARD,
                    new int[]{10, 9, 3}
            );
            sizes.put(
                    LotRole.DECOR,
                    new int[]{7, 7, 3}
            );
            int[] dimensions = sizes.getOrDefault(
                    role,
                    new int[]{8, 8, 3}
            );
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
                buildX = roadX - (width / 2)
                        + lateralOffset;
                buildZ = roadZ + frontSetback;
                frontageZ = roadZ - 1;
                frontageX = roadX + lateralOffset;
            }
            case SOUTH -> {
                buildX = roadX - (width / 2)
                        + lateralOffset;
                buildZ = roadZ - frontSetback - depth;
                frontageZ = roadZ + 1;
                frontageX = roadX + lateralOffset;
            }
            case EAST -> {
                buildX = roadX - frontSetback - width;
                buildZ = roadZ - (depth / 2)
                        + lateralOffset;
                frontageX = roadX + 1;
                frontageZ = roadZ + lateralOffset;
            }
            case WEST -> {
                buildX = roadX + frontSetback;
                buildZ = roadZ - (depth / 2)
                        + lateralOffset;
                frontageX = roadX - 1;
                frontageZ = roadZ + lateralOffset;
            }
            default -> throw new IllegalArgumentException(
                    "Orientation de lot non horizontale : "
                            + facing
            );
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

    private static int terrace(VillageLayoutSettings settings,
                                int desired) {
        return Math.min(
                Math.max(0, desired),
                settings.terrainMaxStep()
        );
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
