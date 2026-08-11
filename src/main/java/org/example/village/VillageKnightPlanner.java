package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/**
 * Transforme le plan architectural du village en itinéraires de garnison.
 *
 * <p>Les postes fixes sont dérivés de la géométrie réellement construite. Les
 * patrouilles urbaines suivent le graphe des rues et chaque section praticable
 * du chemin de ronde peut recevoir ses propres archers, même lorsqu'une porte
 * ou le donjon coupe la boucle complète.</p>
 */
public final class VillageKnightPlanner {

    private static final int MAX_SEGMENT_LENGTH = 10;
    private static final int URBAN_WALK_STEPS = 9;
    private static final int RAMPART_SAMPLE_STEP = 8;
    private static final int MIN_RAMPART_RUN_BLOCKS = 8;
    private static final int MAX_RAMPART_ROUTE_POINTS = 480;
    private static final int POST_SEARCH_RADIUS = 2;

    private static final Set<Material> DANGEROUS_GROUND = Set.of(
            Material.LAVA,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.MAGMA_BLOCK,
            Material.CACTUS,
            Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE,
            Material.SWEET_BERRY_BUSH,
            Material.POWDER_SNOW
    );

    private VillageKnightPlanner() {
    }

    /**
     * Calcule le plan complet d'un village venant d'être généré.
     */
    public static VillageKnightGarrison plan(
            int villageId,
            Location villageCenter,
            VillageLayoutPlan layout,
            Map<String, Location> anchors,
            int radiusX,
            int radiusZ,
            int baseY,
            VillageKnightSettings settings) {
        Objects.requireNonNull(villageCenter, "villageCenter");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(settings, "settings");

        World world = Objects.requireNonNull(
                villageCenter.getWorld(),
                "Le monde du village est obligatoire."
        );
        long now = System.currentTimeMillis();
        List<VillageKnightGarrison.PatrolRoute> routes =
                new ArrayList<>();
        List<VillageKnightGarrison.KnightSlot> slots =
                new ArrayList<>();
        int nextRouteId = 0;

        nextRouteId = appendSentryRoutes(
                world,
                villageId,
                villageCenter,
                layout,
                anchors,
                radiusX,
                radiusZ,
                baseY,
                settings,
                true,
                nextRouteId,
                routes,
                slots,
                now
        );

        StreetGraph graph = StreetGraph.from(layout.streets());
        List<Node> mainComponent = graph.largestComponent();
        List<List<VillageKnightGarrison.BlockPoint>> urbanRoutes =
                buildUrbanRoutes(
                        world,
                        graph,
                        mainComponent,
                        villageCenter,
                        baseY,
                        settings.urbanPatrolCount(),
                        villageId,
                        settings.localTargetSearchRadius()
                );
        nextRouteId = appendUrbanRoutes(
                world.getUID(),
                villageId,
                urbanRoutes,
                settings.soldiersPerPatrol(),
                nextRouteId,
                routes,
                slots,
                now
        );

        List<List<VillageKnightGarrison.BlockPoint>> rampartRoutes =
                settings.wallArchersEnabled()
                        ? buildRampartRoutes(
                        world,
                        villageCenter,
                        radiusX,
                        radiusZ,
                        baseY
                )
                        : List.of();
        appendRampartRoutes(
                world.getUID(),
                villageId,
                rampartRoutes,
                settings.wallArchersEnabled()
                        ? settings.wallArcherCount()
                        : 0,
                nextRouteId,
                routes,
                slots,
                now
        );

        if (slots.isEmpty()) {
            return null;
        }

        return new VillageKnightGarrison(
                VillageKnightGarrison.CURRENT_PLAN_VERSION,
                villageId,
                world.getUID(),
                world.getName(),
                new VillageKnightGarrison.BlockPoint(
                        villageCenter.getBlockX(),
                        baseY + 1,
                        villageCenter.getBlockZ()
                ),
                Math.max(4, radiusX),
                Math.max(4, radiusZ),
                now,
                isRampartPlanningAreaLoaded(
                        world,
                        villageCenter,
                        radiusX,
                        radiusZ,
                        baseY
                ),
                routes,
                slots
        );
    }

    /**
     * Convertit une sauvegarde historique sans charger le moindre chunk.
     *
     * <p>Les anciennes routes urbaines et de rempart sont conservées afin de ne
     * pas perdre les informations architecturales déjà calculées. Les postes
     * fixes, eux, sont remplacés par les quatre catégories explicites du plan
     * version 2.</p>
     */
    static VillageKnightGarrison upgradeLegacy(
            VillageKnightGarrison legacy,
            World world,
            VillageKnightSettings settings) {
        Objects.requireNonNull(legacy, "legacy");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(settings, "settings");
        if (!legacy.requiresPlanMigration()) {
            return legacy;
        }

        int baseY = legacy.center().y() - 1;
        Location center = new Location(
                world,
                legacy.center().x() + 0.5D,
                baseY,
                legacy.center().z() + 0.5D
        );
        VillageLayoutPlan syntheticLayout = new VillageLayoutPlan(
                List.of(),
                List.of(),
                List.of(),
                new VillageLayoutPlan.Bounds(
                        legacy.center().x() - legacy.radiusX(),
                        legacy.center().x() + legacy.radiusX(),
                        legacy.center().z() - legacy.radiusZ(),
                        legacy.center().z() + legacy.radiusZ()
                ),
                Map.of(),
                0,
                List.of()
        );
        Map<String, Location> anchors = new LinkedHashMap<>();
        anchors.put("plaza", center.clone().add(0.0D, 1.0D, 0.0D));
        Location gate = WallBuilder.gateAnchor(
                center,
                legacy.radiusX(),
                legacy.radiusZ(),
                baseY
        );
        Location keep = KeepBuilder.keepAnchor(
                center,
                legacy.radiusZ(),
                baseY
        );
        if (gate != null) {
            anchors.put("gate", gate);
        }
        if (keep != null) {
            anchors.put("keep", keep);
        }

        long now = System.currentTimeMillis();
        List<VillageKnightGarrison.PatrolRoute> routes =
                new ArrayList<>();
        List<VillageKnightGarrison.KnightSlot> slots =
                new ArrayList<>();
        int nextRouteId = appendSentryRoutes(
                world,
                legacy.villageId(),
                center,
                syntheticLayout,
                anchors,
                legacy.radiusX(),
                legacy.radiusZ(),
                baseY,
                settings,
                false,
                0,
                routes,
                slots,
                now
        );

        List<List<VillageKnightGarrison.BlockPoint>> urban =
                legacy.routes().stream()
                        .filter(route -> route.kind()
                                == VillageKnightGarrison.RouteKind.URBAN)
                        .map(VillageKnightGarrison.PatrolRoute::waypoints)
                        .toList();
        if (urban.isEmpty() && settings.urbanPatrolCount() > 0) {
            urban = List.of(List.of(legacy.center()));
        }
        List<List<VillageKnightGarrison.BlockPoint>> selectedUrban =
                repeatRoutes(urban, settings.urbanPatrolCount());
        nextRouteId = appendUrbanRoutes(
                world.getUID(),
                legacy.villageId(),
                selectedUrban,
                settings.soldiersPerPatrol(),
                nextRouteId,
                routes,
                slots,
                now
        );

        List<List<VillageKnightGarrison.BlockPoint>> ramparts =
                distinctLegacyRoutes(
                        legacy,
                        VillageKnightGarrison.RouteKind.RAMPART
                );
        appendRampartRoutes(
                world.getUID(),
                legacy.villageId(),
                ramparts,
                settings.wallArchersEnabled()
                        ? settings.wallArcherCount()
                        : 0,
                nextRouteId,
                routes,
                slots,
                now
        );

        if (slots.isEmpty()) {
            return null;
        }
        return new VillageKnightGarrison(
                VillageKnightGarrison.CURRENT_PLAN_VERSION,
                legacy.villageId(),
                legacy.worldId(),
                legacy.worldName(),
                legacy.center(),
                legacy.radiusX(),
                legacy.radiusZ(),
                legacy.createdAtMillis(),
                false,
                routes,
                slots
        );
    }

    /**
     * Recalcule uniquement les remparts lorsque leur couronne de chunks est
     * enfin chargée. Les entités des sentinelles et des patrouilles sont
     * conservées grâce à la réutilisation de leurs créneaux.
     *
     * @return un nouveau plan, ou {@code null} si les chunks requis manquent.
     */
    static VillageKnightGarrison refreshRampartsIfLoaded(
            VillageKnightGarrison garrison,
            World world,
            VillageKnightSettings settings) {
        Objects.requireNonNull(garrison, "garrison");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(settings, "settings");
        if (garrison.rampartPlanComplete()) {
            return garrison;
        }

        int baseY = garrison.center().y() - 1;
        Location center = new Location(
                world,
                garrison.center().x() + 0.5D,
                baseY,
                garrison.center().z() + 0.5D
        );
        if (!isRampartPlanningAreaLoaded(
                world,
                center,
                garrison.radiusX(),
                garrison.radiusZ(),
                baseY
        )) {
            return null;
        }

        List<VillageKnightGarrison.PatrolRoute> routes =
                garrison.routes().stream()
                        .filter(route -> route.kind()
                                != VillageKnightGarrison.RouteKind.RAMPART)
                        .collect(java.util.stream.Collectors.toCollection(
                                ArrayList::new
                        ));
        List<VillageKnightGarrison.KnightSlot> slots =
                garrison.slots().stream()
                        .filter(slot -> {
                            VillageKnightGarrison.PatrolRoute route =
                                    garrison.route(slot.routeId());
                            return route != null
                                    && route.kind()
                                    != VillageKnightGarrison.RouteKind.RAMPART;
                        })
                        .collect(java.util.stream.Collectors.toCollection(
                                ArrayList::new
                        ));

        int nextRouteId = routes.stream()
                .mapToInt(VillageKnightGarrison.PatrolRoute::routeId)
                .max()
                .orElse(-1) + 1;
        List<List<VillageKnightGarrison.BlockPoint>> ramparts =
                settings.wallArchersEnabled()
                        ? buildRampartRoutes(
                        world,
                        center,
                        garrison.radiusX(),
                        garrison.radiusZ(),
                        baseY
                )
                        : List.of();
        appendRampartRoutes(
                world.getUID(),
                garrison.villageId(),
                ramparts,
                settings.wallArchersEnabled()
                        ? settings.wallArcherCount()
                        : 0,
                nextRouteId,
                routes,
                slots,
                System.currentTimeMillis()
        );

        /*
         * Une configuration composée uniquement d'archers peut devenir vide si
         * le rempart a été détruit. Dans ce cas, on conserve l'ancien état au
         * lieu de fabriquer une garnison invalide.
         */
        if (slots.isEmpty()) {
            return null;
        }
        return new VillageKnightGarrison(
                VillageKnightGarrison.CURRENT_PLAN_VERSION,
                garrison.villageId(),
                garrison.worldId(),
                garrison.worldName(),
                garrison.center(),
                garrison.radiusX(),
                garrison.radiusZ(),
                garrison.createdAtMillis(),
                true,
                routes,
                slots
        );
    }

    private static int appendSentryRoutes(
            World world,
            int villageId,
            Location center,
            VillageLayoutPlan layout,
            Map<String, Location> suppliedAnchors,
            int radiusX,
            int radiusZ,
            int baseY,
            VillageKnightSettings settings,
            boolean validateWorld,
            int firstRouteId,
            List<VillageKnightGarrison.PatrolRoute> routes,
            List<VillageKnightGarrison.KnightSlot> slots,
            long now) {
        Map<String, Location> anchors =
                suppliedAnchors == null || suppliedAnchors.isEmpty()
                        ? layout.anchors()
                        : suppliedAnchors;
        List<SentryPost> posts = buildSentryPosts(
                world,
                center,
                anchors,
                radiusX,
                radiusZ,
                baseY,
                settings,
                validateWorld
        );

        int routeId = firstRouteId;
        for (SentryPost post : posts) {
            routes.add(new VillageKnightGarrison.PatrolRoute(
                    routeId,
                    post.kind(),
                    List.of(post.point()),
                    0,
                    now
            ));
            slots.add(newSlot(
                    world.getUID(),
                    villageId,
                    routeId,
                    VillageKnightGarrison.UnitKind.SWORDSMAN,
                    0,
                    post.point()
            ));
            routeId++;
        }
        return routeId;
    }

    private static List<SentryPost> buildSentryPosts(
            World world,
            Location center,
            Map<String, Location> anchors,
            int radiusX,
            int radiusZ,
            int baseY,
            VillageKnightSettings settings,
            boolean validateWorld) {
        List<SentryPost> result = new ArrayList<>();
        Set<VillageKnightGarrison.BlockPoint> used =
                new LinkedHashSet<>();

        Location gateAnchor = anchors.get("gate");
        if (gateAnchor == null) {
            gateAnchor = WallBuilder.gateAnchor(
                    center,
                    radiusX,
                    radiusZ,
                    baseY
            );
        }
        appendPostCategory(
                world,
                VillageKnightGarrison.RouteKind.GATE_SENTRY,
                gateCandidates(gateAnchor, baseY),
                settings.gateGuardCount(),
                validateWorld,
                used,
                result
        );

        Location plaza = anchors.getOrDefault("plaza", center);
        appendPostCategory(
                world,
                VillageKnightGarrison.RouteKind.PLAZA_SENTRY,
                plazaCandidates(plaza, baseY),
                settings.plazaGuardCount(),
                validateWorld,
                used,
                result
        );

        appendPostCategory(
                world,
                VillageKnightGarrison.RouteKind.KEEP_ENTRANCE_SENTRY,
                KeepBuilder.entranceGuardAnchors(
                        center,
                        radiusZ,
                        baseY
                ),
                settings.keepEntranceGuardCount(),
                validateWorld,
                used,
                result
        );
        appendPostCategory(
                world,
                VillageKnightGarrison.RouteKind.KEEP_INTERIOR_SENTRY,
                KeepBuilder.interiorGuardAnchors(
                        center,
                        radiusZ,
                        baseY
                ),
                settings.keepInteriorGuardCount(),
                validateWorld,
                used,
                result
        );

        /*
         * Les quatre sentinelles fiscales sont imposées par la structure de la
         * chambre forte, pas par le quota général de la garnison. Leur ensemble
         * de séparation est volontairement indépendant : un poste situé vingt
         * blocs sous un garde du donjon peut partager ses coordonnées X/Z sans
         * représenter le même emplacement.
         */
        List<Location> treasuryCandidates = new ArrayList<>();
        for (int index = 0;
             index < VillageTreasury.REQUIRED_GUARD_COUNT;
             index++) {
            Location anchor = anchors.get("treasury-guard-" + index);
            if (anchor != null) {
                treasuryCandidates.add(anchor);
            }
        }
        appendPostCategory(
                world,
                VillageKnightGarrison.RouteKind.TREASURY_SENTRY,
                treasuryCandidates,
                VillageTreasury.REQUIRED_GUARD_COUNT,
                validateWorld,
                new LinkedHashSet<>(),
                result
        );
        return result;
    }

    private static List<Location> gateCandidates(
            Location anchor,
            int baseY) {
        if (anchor == null) {
            return List.of();
        }
        int x = anchor.getBlockX();
        int z = anchor.getBlockZ();
        double y = baseY + 1.0D;
        return List.of(
                new Location(anchor.getWorld(), x - 2 + 0.5D, y, z + 0.5D),
                new Location(anchor.getWorld(), x + 2 + 0.5D, y, z + 0.5D),
                new Location(anchor.getWorld(), x - 3 + 0.5D, y, z + 1.5D),
                new Location(anchor.getWorld(), x + 3 + 0.5D, y, z + 1.5D)
        );
    }

    private static List<Location> plazaCandidates(
            Location anchor,
            int baseY) {
        if (anchor == null) {
            return List.of();
        }
        int x = anchor.getBlockX();
        int z = anchor.getBlockZ();
        double y = baseY + 1.0D;
        return List.of(
                new Location(anchor.getWorld(), x - 4 + 0.5D, y, z + 0.5D),
                new Location(anchor.getWorld(), x + 4 + 0.5D, y, z + 0.5D),
                new Location(anchor.getWorld(), x + 0.5D, y, z - 4 + 0.5D),
                new Location(anchor.getWorld(), x + 0.5D, y, z + 4 + 0.5D)
        );
    }

    private static void appendPostCategory(
            World world,
            VillageKnightGarrison.RouteKind kind,
            List<Location> candidates,
            int desiredCount,
            boolean validateWorld,
            Set<VillageKnightGarrison.BlockPoint> used,
            List<SentryPost> result) {
        if (desiredCount <= 0 || candidates == null) {
            return;
        }

        int added = 0;
        int salt = kind.ordinal() * 31;
        for (Location candidate : candidates) {
            if (added >= desiredCount || candidate == null) {
                break;
            }
            VillageKnightGarrison.BlockPoint preferred =
                    VillageKnightGarrison.BlockPoint.from(candidate);
            VillageKnightGarrison.BlockPoint selected =
                    validateWorld
                            ? findSafeStandingPoint(
                            world,
                            preferred,
                            POST_SEARCH_RADIUS,
                            salt + added
                    )
                            : preferred;
            if (selected == null
                    || !isSeparatedFrom(used, selected, 2)) {
                continue;
            }
            used.add(selected);
            result.add(new SentryPost(kind, selected));
            added++;
        }
    }

    private static boolean isSeparatedFrom(
            Collection<VillageKnightGarrison.BlockPoint> points,
            VillageKnightGarrison.BlockPoint candidate,
            int minimumDistance) {
        long minimumSq = (long) minimumDistance * minimumDistance;
        for (VillageKnightGarrison.BlockPoint point : points) {
            if (point.y() == candidate.y()
                    && point.horizontalDistanceSquared(candidate)
                    < minimumSq) {
                return false;
            }
        }
        return true;
    }

    private static int appendUrbanRoutes(
            UUID worldId,
            int villageId,
            List<List<VillageKnightGarrison.BlockPoint>> urbanRoutes,
            int soldiersPerPatrol,
            int firstRouteId,
            List<VillageKnightGarrison.PatrolRoute> routes,
            List<VillageKnightGarrison.KnightSlot> slots,
            long now) {
        int routeId = firstRouteId;
        for (List<VillageKnightGarrison.BlockPoint> points : urbanRoutes) {
            if (points == null || points.isEmpty()) {
                continue;
            }
            routes.add(new VillageKnightGarrison.PatrolRoute(
                    routeId,
                    VillageKnightGarrison.RouteKind.URBAN,
                    points,
                    0,
                    now
            ));
            for (int formationIndex = 0;
                 formationIndex < soldiersPerPatrol;
                 formationIndex++) {
                slots.add(newSlot(
                        worldId,
                        villageId,
                        routeId,
                        VillageKnightGarrison.UnitKind.SWORDSMAN,
                        formationIndex,
                        points.get(0)
                ));
            }
            routeId++;
        }
        return routeId;
    }

    private static int appendRampartRoutes(
            UUID worldId,
            int villageId,
            List<List<VillageKnightGarrison.BlockPoint>> rampartRoutes,
            int archerCount,
            int firstRouteId,
            List<VillageKnightGarrison.PatrolRoute> routes,
            List<VillageKnightGarrison.KnightSlot> slots,
            long now) {
        if (archerCount <= 0
                || rampartRoutes == null
                || rampartRoutes.isEmpty()) {
            return firstRouteId;
        }

        int routeId = firstRouteId;
        int sectionCount = rampartRoutes.size();
        for (int archerIndex = 0;
             archerIndex < archerCount;
             archerIndex++) {
            int sectionIndex = archerIndex % sectionCount;
            List<VillageKnightGarrison.BlockPoint> section =
                    rampartRoutes.get(sectionIndex);
            if (section == null || section.size() < 2) {
                continue;
            }

            int ordinalOnSection = archerIndex / sectionCount;
            int archersOnSection = (archerCount - sectionIndex
                    + sectionCount - 1) / sectionCount;
            int startIndex = Math.floorMod(
                    ordinalOnSection * section.size()
                            / Math.max(1, archersOnSection),
                    section.size()
            );
            routes.add(new VillageKnightGarrison.PatrolRoute(
                    routeId,
                    VillageKnightGarrison.RouteKind.RAMPART,
                    section,
                    startIndex,
                    now
            ));
            slots.add(newSlot(
                    worldId,
                    villageId,
                    routeId,
                    VillageKnightGarrison.UnitKind.ARCHER,
                    0,
                    section.get(startIndex)
            ));
            routeId++;
        }
        return routeId;
    }

    private static VillageKnightGarrison.KnightSlot newSlot(
            UUID worldId,
            int villageId,
            int routeId,
            VillageKnightGarrison.UnitKind kind,
            int formationIndex,
            VillageKnightGarrison.BlockPoint initialPosition) {
        String stableName = worldId + ":" + villageId + ":"
                + routeId + ":" + formationIndex + ":" + kind;
        UUID slotId = UUID.nameUUIDFromBytes(
                stableName.getBytes(StandardCharsets.UTF_8)
        );
        return new VillageKnightGarrison.KnightSlot(
                slotId,
                routeId,
                kind,
                formationIndex,
                null,
                0L,
                initialPosition
        );
    }

    private static List<List<VillageKnightGarrison.BlockPoint>>
    repeatRoutes(
            List<List<VillageKnightGarrison.BlockPoint>> source,
            int desiredCount) {
        if (desiredCount <= 0 || source == null || source.isEmpty()) {
            return List.of();
        }
        List<List<VillageKnightGarrison.BlockPoint>> result =
                new ArrayList<>(desiredCount);
        for (int index = 0; index < desiredCount; index++) {
            result.add(source.get(index % source.size()));
        }
        return result;
    }

    private static List<List<VillageKnightGarrison.BlockPoint>>
    distinctLegacyRoutes(
            VillageKnightGarrison garrison,
            VillageKnightGarrison.RouteKind kind) {
        List<List<VillageKnightGarrison.BlockPoint>> result =
                new ArrayList<>();
        for (VillageKnightGarrison.PatrolRoute route
                : garrison.routes()) {
            if (route.kind() == kind
                    && !result.contains(route.waypoints())) {
                result.add(route.waypoints());
            }
        }
        return result;
    }

    private static List<List<VillageKnightGarrison.BlockPoint>>
    buildUrbanRoutes(
            World world,
            StreetGraph graph,
            List<Node> mainComponent,
            Location center,
            int baseY,
            int desiredRoutes,
            int villageId,
            int localSearchRadius) {
        if (desiredRoutes <= 0) {
            return List.of();
        }

        if (mainComponent.isEmpty()) {
            VillageKnightGarrison.BlockPoint centerPoint =
                    new VillageKnightGarrison.BlockPoint(
                            center.getBlockX(),
                            baseY + 1,
                            center.getBlockZ()
                    );
            VillageKnightGarrison.BlockPoint safe =
                    findSafeStandingPoint(
                            world,
                            centerPoint,
                            localSearchRadius,
                            villageId
                    );
            return List.of(List.of(
                    safe != null ? safe : centerPoint
            ));
        }

        List<Node> starts = chooseSpreadStarts(
                mainComponent,
                desiredRoutes,
                center.getBlockX(),
                center.getBlockZ()
        );
        List<List<VillageKnightGarrison.BlockPoint>> result =
                new ArrayList<>();

        for (int routeIndex = 0;
             routeIndex < desiredRoutes;
             routeIndex++) {
            Node start = starts.get(routeIndex % starts.size());
            List<Node> walk = graph.closedWalk(
                    start,
                    URBAN_WALK_STEPS + routeIndex,
                    villageId * 31 + routeIndex
            );
            List<VillageKnightGarrison.BlockPoint> expanded =
                    expandOrthogonalWalk(walk, baseY + 1);
            if (expanded.size() == 1 && mainComponent.size() > 1) {
                Node farthest = farthestNode(mainComponent, start);
                expanded = expandOrthogonalWalk(
                        List.of(start, farthest, start),
                        baseY + 1
                );
            }
            List<VillageKnightGarrison.BlockPoint> safe =
                    sanitizeUrbanRoute(
                            world,
                            expanded,
                            localSearchRadius,
                            villageId + routeIndex * 17
                    );
            if (!safe.isEmpty()) {
                result.add(safe);
            }
        }
        return result;
    }

    private static List<VillageKnightGarrison.BlockPoint>
    sanitizeUrbanRoute(
            World world,
            List<VillageKnightGarrison.BlockPoint> points,
            int radius,
            int salt) {
        List<VillageKnightGarrison.BlockPoint> result =
                new ArrayList<>();
        if (points == null) {
            return result;
        }
        for (int index = 0; index < points.size(); index++) {
            VillageKnightGarrison.BlockPoint safe =
                    findSafeStandingPoint(
                            world,
                            points.get(index),
                            radius,
                            salt + index
                    );
            if (safe != null) {
                appendDistinct(result, safe);
            }
        }
        if (result.size() > 1
                && result.get(0).equals(result.get(result.size() - 1))) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    private static List<Node> chooseSpreadStarts(
            List<Node> nodes,
            int desired,
            int centerX,
            int centerZ) {
        List<Node> starts = new ArrayList<>();
        Node first = nodes.stream()
                .max(Comparator.comparingLong(node ->
                        squaredDistance(
                                node.x(),
                                node.z(),
                                centerX,
                                centerZ
                        )))
                .orElse(nodes.get(0));
        starts.add(first);

        while (starts.size() < desired && starts.size() < nodes.size()) {
            Node next = nodes.stream()
                    .filter(node -> !starts.contains(node))
                    .max(Comparator.comparingLong(node -> {
                        long nearest = Long.MAX_VALUE;
                        for (Node selected : starts) {
                            nearest = Math.min(
                                    nearest,
                                    squaredDistance(
                                            node.x(),
                                            node.z(),
                                            selected.x(),
                                            selected.z()
                                    )
                            );
                        }
                        return nearest;
                    }))
                    .orElse(null);
            if (next == null) {
                break;
            }
            starts.add(next);
        }
        return starts;
    }

    private static Node farthestNode(List<Node> nodes, Node origin) {
        return nodes.stream()
                .max(Comparator.comparingLong(node ->
                        squaredDistance(
                                node.x(),
                                node.z(),
                                origin.x(),
                                origin.z()
                        )))
                .orElse(origin);
    }

    private static List<VillageKnightGarrison.BlockPoint>
    expandOrthogonalWalk(List<Node> nodes, int feetY) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }

        List<VillageKnightGarrison.BlockPoint> result =
                new ArrayList<>();
        appendDistinct(result, new VillageKnightGarrison.BlockPoint(
                nodes.get(0).x(),
                feetY,
                nodes.get(0).z()
        ));

        for (int i = 1; i < nodes.size(); i++) {
            Node from = nodes.get(i - 1);
            Node to = nodes.get(i);
            if (from.x() != to.x() && from.z() != to.z()) {
                /*
                 * Cette branche protège aussi un futur graphe contenant une
                 * connexion diagonale : le trajet reste alors Manhattan.
                 */
                appendSegment(
                        result,
                        from.x(),
                        from.z(),
                        to.x(),
                        from.z(),
                        feetY
                );
                appendSegment(
                        result,
                        to.x(),
                        from.z(),
                        to.x(),
                        to.z(),
                        feetY
                );
            } else {
                appendSegment(
                        result,
                        from.x(),
                        from.z(),
                        to.x(),
                        to.z(),
                        feetY
                );
            }
        }

        if (result.size() > 1
                && result.get(0).equals(result.get(result.size() - 1))) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    private static void appendSegment(
            List<VillageKnightGarrison.BlockPoint> result,
            int fromX,
            int fromZ,
            int toX,
            int toZ,
            int feetY) {
        int dx = Integer.compare(toX, fromX);
        int dz = Integer.compare(toZ, fromZ);
        int length = Math.max(
                Math.abs(toX - fromX),
                Math.abs(toZ - fromZ)
        );
        if (length == 0) {
            return;
        }

        for (int distance = MAX_SEGMENT_LENGTH;
             distance < length;
             distance += MAX_SEGMENT_LENGTH) {
            appendDistinct(
                    result,
                    new VillageKnightGarrison.BlockPoint(
                            fromX + dx * distance,
                            feetY,
                            fromZ + dz * distance
                    )
            );
        }
        appendDistinct(
                result,
                new VillageKnightGarrison.BlockPoint(
                        toX,
                        feetY,
                        toZ
                )
        );
    }

    private static void appendDistinct(
            List<VillageKnightGarrison.BlockPoint> points,
            VillageKnightGarrison.BlockPoint point) {
        if (point != null
                && (points.isEmpty()
                || !points.get(points.size() - 1).equals(point))) {
            points.add(point);
        }
    }

    /**
     * Découpe la couronne du rempart en autant de sections praticables que
     * nécessaire. Une ouverture de châtelet ne supprime donc plus les trois
     * autres côtés de la muraille.
     */
    private static List<List<VillageKnightGarrison.BlockPoint>>
    buildRampartRoutes(
            World world,
            Location center,
            int radiusX,
            int radiusZ,
            int baseY) {
        List<VillageKnightGarrison.BlockPoint> ring =
                buildDenseRampartRing(
                        center,
                        radiusX,
                        radiusZ,
                        baseY
                );
        if (ring.size() < MIN_RAMPART_RUN_BLOCKS) {
            return List.of();
        }

        boolean[] walkable = new boolean[ring.size()];
        int firstBlocked = -1;
        for (int index = 0; index < ring.size(); index++) {
            walkable[index] = isWalkableRampartPoint(
                    world,
                    ring.get(index)
            );
            if (!walkable[index] && firstBlocked < 0) {
                firstBlocked = index;
            }
        }

        List<List<VillageKnightGarrison.BlockPoint>> denseRuns =
                new ArrayList<>();
        if (firstBlocked < 0) {
            denseRuns.add(ring);
        } else {
            List<VillageKnightGarrison.BlockPoint> current =
                    new ArrayList<>();
            for (int offset = 1; offset <= ring.size(); offset++) {
                int index = (firstBlocked + offset) % ring.size();
                if (walkable[index]) {
                    current.add(ring.get(index));
                } else {
                    appendRampartRun(denseRuns, current);
                    current = new ArrayList<>();
                }
            }
            appendRampartRun(denseRuns, current);
        }

        List<List<VillageKnightGarrison.BlockPoint>> result =
                new ArrayList<>();
        boolean closedLoop = firstBlocked < 0;
        for (List<VillageKnightGarrison.BlockPoint> dense : denseRuns) {
            List<VillageKnightGarrison.BlockPoint> sampled =
                    sampleRampartRun(dense, closedLoop);
            if (isRampartUsable(world, sampled)) {
                result.add(sampled);
            }
        }
        return result;
    }

    private static void appendRampartRun(
            List<List<VillageKnightGarrison.BlockPoint>> result,
            List<VillageKnightGarrison.BlockPoint> run) {
        if (run != null && run.size() >= MIN_RAMPART_RUN_BLOCKS) {
            result.add(List.copyOf(run));
        }
    }

    private static List<VillageKnightGarrison.BlockPoint>
    buildDenseRampartRing(
            Location center,
            int radiusX,
            int radiusZ,
            int baseY) {
        int cx = center.getBlockX();
        int cz = center.getBlockZ();
        int safeRx = Math.max(4, radiusX);
        int safeRz = Math.max(4, radiusZ);

        int minX = cx - safeRx - WallBuilder.WALL_THICKNESS + 1;
        int maxX = cx + safeRx + WallBuilder.WALL_THICKNESS - 1;
        int minZ = cz - safeRz - WallBuilder.WALL_THICKNESS + 1;
        int maxZ = cz + safeRz + WallBuilder.WALL_THICKNESS - 1;
        int feetY = baseY + WallBuilder.WALL_HEIGHT + 1;
        int northZ = minZ + 1;
        int eastX = maxX - 1;
        int southZ = maxZ - 1;
        int westX = minX + 1;

        List<VillageKnightGarrison.BlockPoint> denseRing =
                new ArrayList<>();
        appendDenseRampartSide(
                denseRing,
                westX,
                northZ,
                eastX,
                northZ,
                feetY
        );
        appendDenseRampartSide(
                denseRing,
                eastX,
                northZ,
                eastX,
                southZ,
                feetY
        );
        appendDenseRampartSide(
                denseRing,
                eastX,
                southZ,
                westX,
                southZ,
                feetY
        );
        appendDenseRampartSide(
                denseRing,
                westX,
                southZ,
                westX,
                northZ,
                feetY
        );
        if (denseRing.size() > 1
                && denseRing.get(0).equals(
                denseRing.get(denseRing.size() - 1)
        )) {
            denseRing.remove(denseRing.size() - 1);
        }
        return denseRing;
    }

    private static void appendDenseRampartSide(
            List<VillageKnightGarrison.BlockPoint> result,
            int fromX,
            int fromZ,
            int toX,
            int toZ,
            int feetY) {
        int dx = Integer.compare(toX, fromX);
        int dz = Integer.compare(toZ, fromZ);
        int length = Math.max(
                Math.abs(toX - fromX),
                Math.abs(toZ - fromZ)
        );
        for (int distance = 0; distance <= length; distance++) {
            appendDistinct(
                    result,
                    new VillageKnightGarrison.BlockPoint(
                            fromX + dx * distance,
                            feetY,
                            fromZ + dz * distance
                    )
            );
        }
    }

    private static List<VillageKnightGarrison.BlockPoint>
    sampleRampartRun(
            List<VillageKnightGarrison.BlockPoint> dense,
            boolean closedLoop) {
        if (dense == null || dense.size() < 2) {
            return List.of();
        }

        int maximumForwardPoints = closedLoop
                ? MAX_RAMPART_ROUTE_POINTS
                : MAX_RAMPART_ROUTE_POINTS / 2;
        int sampleStep = Math.max(
                RAMPART_SAMPLE_STEP,
                (int) Math.ceil(
                        dense.size()
                                / (double) Math.max(
                                2,
                                maximumForwardPoints
                        )
                )
        );

        List<VillageKnightGarrison.BlockPoint> forward =
                new ArrayList<>();
        forward.add(dense.get(0));
        int lastAdded = 0;
        for (int index = 1; index < dense.size(); index++) {
            boolean last = index == dense.size() - 1;
            boolean turn = !last && directionChanges(
                    dense.get(index - 1),
                    dense.get(index),
                    dense.get(index + 1)
            );
            if (last || turn || index - lastAdded >= sampleStep) {
                appendDistinct(forward, dense.get(index));
                lastAdded = index;
            }
        }

        if (forward.size() < 2) {
            return List.of();
        }
        if (closedLoop) {
            return forward;
        }

        List<VillageKnightGarrison.BlockPoint> pingPong =
                new ArrayList<>(Math.min(
                        MAX_RAMPART_ROUTE_POINTS,
                        forward.size() * 2
                ));
        pingPong.addAll(forward);
        for (int index = forward.size() - 2; index >= 1; index--) {
            appendDistinct(pingPong, forward.get(index));
        }
        return pingPong.size() <= MAX_RAMPART_ROUTE_POINTS
                ? pingPong
                : List.of();
    }

    private static boolean directionChanges(
            VillageKnightGarrison.BlockPoint previous,
            VillageKnightGarrison.BlockPoint current,
            VillageKnightGarrison.BlockPoint next) {
        int firstX = Integer.compare(current.x(), previous.x());
        int firstZ = Integer.compare(current.z(), previous.z());
        int secondX = Integer.compare(next.x(), current.x());
        int secondZ = Integer.compare(next.z(), current.z());
        return firstX != secondX || firstZ != secondZ;
    }

    private static boolean isRampartUsable(
            World world,
            List<VillageKnightGarrison.BlockPoint> points) {
        if (world == null || points == null || points.size() < 2
                || points.size() > MAX_RAMPART_ROUTE_POINTS) {
            return false;
        }

        for (int index = 0; index < points.size(); index++) {
            VillageKnightGarrison.BlockPoint from =
                    points.get(index);
            VillageKnightGarrison.BlockPoint to =
                    points.get((index + 1) % points.size());
            if (!isWalkableRampartSegment(world, from, to)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWalkableRampartSegment(
            World world,
            VillageKnightGarrison.BlockPoint from,
            VillageKnightGarrison.BlockPoint to) {
        if (from == null || to == null || from.y() != to.y()
                || (from.x() != to.x() && from.z() != to.z())) {
            return false;
        }

        int dx = Integer.compare(to.x(), from.x());
        int dz = Integer.compare(to.z(), from.z());
        int length = Math.max(
                Math.abs(to.x() - from.x()),
                Math.abs(to.z() - from.z())
        );
        for (int distance = 0; distance <= length; distance++) {
            VillageKnightGarrison.BlockPoint point =
                    new VillageKnightGarrison.BlockPoint(
                            from.x() + dx * distance,
                            from.y(),
                            from.z() + dz * distance
                    );
            if (!isWalkableRampartPoint(world, point)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWalkableRampartPoint(
            World world,
            VillageKnightGarrison.BlockPoint point) {
        return isSafeStandingPoint(world, point);
    }

    private static VillageKnightGarrison.BlockPoint
    findSafeStandingPoint(
            World world,
            VillageKnightGarrison.BlockPoint preferred,
            int radius,
            int salt) {
        if (world == null || preferred == null) {
            return null;
        }
        List<int[]> offsets = horizontalOffsets(
                Math.max(0, radius),
                salt
        );
        for (int[] offset : offsets) {
            VillageKnightGarrison.BlockPoint candidate =
                    new VillageKnightGarrison.BlockPoint(
                            preferred.x() + offset[0],
                            preferred.y(),
                            preferred.z() + offset[1]
                    );
            if (isSafeStandingPoint(world, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<int[]> horizontalOffsets(
            int radius,
            int salt) {
        List<int[]> result = new ArrayList<>();
        result.add(new int[]{0, 0});
        for (int ring = 1; ring <= radius; ring++) {
            List<int[]> current = new ArrayList<>();
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                        current.add(new int[]{dx, dz});
                    }
                }
            }
            if (!current.isEmpty()) {
                int rotation = Math.floorMod(salt, current.size());
                for (int index = 0; index < current.size(); index++) {
                    result.add(current.get(
                            (index + rotation) % current.size()
                    ));
                }
            }
        }
        return result;
    }

    static boolean isSafeStandingPoint(
            World world,
            VillageKnightGarrison.BlockPoint point) {
        if (world == null || point == null
                || !world.isChunkLoaded(point.x() >> 4, point.z() >> 4)
                || point.y() <= world.getMinHeight()
                || point.y() + 1 >= world.getMaxHeight()) {
            return false;
        }
        Block ground = world.getBlockAt(
                point.x(),
                point.y() - 1,
                point.z()
        );
        Block feet = world.getBlockAt(
                point.x(),
                point.y(),
                point.z()
        );
        Block head = world.getBlockAt(
                point.x(),
                point.y() + 1,
                point.z()
        );
        return ground.getType().isSolid()
                && !DANGEROUS_GROUND.contains(ground.getType())
                && !DANGEROUS_GROUND.contains(feet.getType())
                && !DANGEROUS_GROUND.contains(head.getType())
                && feet.isPassable()
                && head.isPassable()
                && !feet.isLiquid()
                && !head.isLiquid();
    }

    private static boolean isRampartPlanningAreaLoaded(
            World world,
            Location center,
            int radiusX,
            int radiusZ,
            int baseY) {
        if (world == null || center == null) {
            return false;
        }
        for (VillageKnightGarrison.BlockPoint point
                : buildDenseRampartRing(
                center,
                radiusX,
                radiusZ,
                baseY
        )) {
            if (!world.isChunkLoaded(point.x() >> 4, point.z() >> 4)) {
                return false;
            }
        }
        return true;
    }

    private static long squaredDistance(
            int firstX,
            int firstZ,
            int secondX,
            int secondZ) {
        long dx = (long) firstX - secondX;
        long dz = (long) firstZ - secondZ;
        return dx * dx + dz * dz;
    }

    private record SentryPost(
            VillageKnightGarrison.RouteKind kind,
            VillageKnightGarrison.BlockPoint point) {
    }

    private record Node(int x, int z) {
    }

    /**
     * Graphe non orienté des intersections et extrémités de rues.
     */
    private static final class StreetGraph {
        private final Map<Node, Set<Node>> adjacency =
                new LinkedHashMap<>();

        static StreetGraph from(
                List<VillageLayoutPlan.StreetPlan> streets) {
            StreetGraph graph = new StreetGraph();
            if (streets == null || streets.isEmpty()) {
                return graph;
            }

            Map<VillageLayoutPlan.StreetPlan, Set<Node>> pointsByStreet =
                    new LinkedHashMap<>();
            for (VillageLayoutPlan.StreetPlan street : streets) {
                if (street == null) {
                    continue;
                }
                Set<Node> points = new LinkedHashSet<>();
                points.add(new Node(street.startX(), street.startZ()));
                points.add(new Node(street.endX(), street.endZ()));
                pointsByStreet.put(street, points);
            }

            List<VillageLayoutPlan.StreetPlan> usable =
                    new ArrayList<>(pointsByStreet.keySet());
            for (int firstIndex = 0;
                 firstIndex < usable.size();
                 firstIndex++) {
                VillageLayoutPlan.StreetPlan first =
                        usable.get(firstIndex);
                for (int secondIndex = firstIndex + 1;
                     secondIndex < usable.size();
                     secondIndex++) {
                    VillageLayoutPlan.StreetPlan second =
                            usable.get(secondIndex);
                    Node intersection = intersection(first, second);
                    if (intersection != null) {
                        pointsByStreet.get(first).add(intersection);
                        pointsByStreet.get(second).add(intersection);
                    }
                }
            }

            for (Map.Entry<VillageLayoutPlan.StreetPlan, Set<Node>> entry
                    : pointsByStreet.entrySet()) {
                VillageLayoutPlan.StreetPlan street = entry.getKey();
                List<Node> ordered = new ArrayList<>(entry.getValue());
                ordered.sort(street.horizontal()
                        ? Comparator.comparingInt(Node::x)
                        : Comparator.comparingInt(Node::z));
                for (Node node : ordered) {
                    graph.adjacency.computeIfAbsent(
                            node,
                            ignored -> new LinkedHashSet<>()
                    );
                }
                for (int index = 1; index < ordered.size(); index++) {
                    graph.connect(
                            ordered.get(index - 1),
                            ordered.get(index)
                    );
                }
            }
            return graph;
        }

        private static Node intersection(
                VillageLayoutPlan.StreetPlan first,
                VillageLayoutPlan.StreetPlan second) {
            if (first.horizontal() == second.horizontal()) {
                return null;
            }
            VillageLayoutPlan.StreetPlan horizontal =
                    first.horizontal() ? first : second;
            VillageLayoutPlan.StreetPlan vertical =
                    first.horizontal() ? second : first;
            int x = vertical.startX();
            int z = horizontal.startZ();
            if (x < horizontal.minX()
                    || x > horizontal.maxX()
                    || z < vertical.minZ()
                    || z > vertical.maxZ()) {
                return null;
            }
            return new Node(x, z);
        }

        private void connect(Node first, Node second) {
            if (first.equals(second)) {
                return;
            }
            adjacency.computeIfAbsent(
                    first,
                    ignored -> new LinkedHashSet<>()
            ).add(second);
            adjacency.computeIfAbsent(
                    second,
                    ignored -> new LinkedHashSet<>()
            ).add(first);
        }

        List<Node> largestComponent() {
            List<Node> largest = new ArrayList<>();
            Set<Node> visited = new HashSet<>();
            for (Node start : adjacency.keySet()) {
                if (!visited.add(start)) {
                    continue;
                }
                List<Node> component = new ArrayList<>();
                Deque<Node> queue = new ArrayDeque<>();
                queue.add(start);
                while (!queue.isEmpty()) {
                    Node current = queue.removeFirst();
                    component.add(current);
                    for (Node neighbour : adjacency.getOrDefault(
                            current,
                            Set.of()
                    )) {
                        if (visited.add(neighbour)) {
                            queue.addLast(neighbour);
                        }
                    }
                }
                if (component.size() > largest.size()) {
                    largest = component;
                }
            }
            return largest;
        }

        List<Node> closedWalk(Node start,
                              int desiredSteps,
                              int salt) {
            if (start == null || !adjacency.containsKey(start)) {
                return List.of();
            }

            List<Node> walk = new ArrayList<>();
            Set<Node> visited = new LinkedHashSet<>();
            Node previous = null;
            Node current = start;
            walk.add(current);
            visited.add(current);

            int safeSteps = Math.max(2, desiredSteps);
            for (int step = 0; step < safeSteps; step++) {
                int stepIndex = step;
                List<Node> neighbours = new ArrayList<>(
                        adjacency.getOrDefault(current, Set.of())
                );
                if (neighbours.isEmpty()) {
                    break;
                }
                Node oldPrevious = previous;
                Node oldCurrent = current;
                neighbours.sort(Comparator
                        .comparingInt((Node node) ->
                                visited.contains(node) ? 1 : 0)
                        .thenComparingInt(node ->
                                node.equals(oldPrevious) ? 1 : 0)
                        .thenComparingLong(node ->
                                deterministicScore(
                                        node,
                                        oldCurrent,
                                        salt,
                                        stepIndex
                                )));
                Node next = neighbours.get(0);
                previous = current;
                current = next;
                walk.add(current);
                visited.add(current);
            }

            List<Node> home = shortestPath(current, start);
            for (int index = 1; index < home.size(); index++) {
                walk.add(home.get(index));
            }
            return walk;
        }

        private long deterministicScore(Node candidate,
                                        Node current,
                                        int salt,
                                        int step) {
            long value = 17L * candidate.x()
                    + 31L * candidate.z()
                    + 13L * current.x()
                    + 7L * current.z()
                    + 97L * salt
                    + step;
            return Math.floorMod(value, 10_007L);
        }

        private List<Node> shortestPath(Node start, Node goal) {
            if (start.equals(goal)) {
                return List.of(start);
            }

            Queue<Node> queue = new ArrayDeque<>();
            Map<Node, Node> parent = new HashMap<>();
            queue.add(start);
            parent.put(start, null);

            while (!queue.isEmpty()) {
                Node current = queue.remove();
                for (Node neighbour : adjacency.getOrDefault(
                        current,
                        Set.of()
                )) {
                    if (parent.containsKey(neighbour)) {
                        continue;
                    }
                    parent.put(neighbour, current);
                    if (neighbour.equals(goal)) {
                        return reconstructPath(parent, goal);
                    }
                    queue.add(neighbour);
                }
            }
            return List.of(start);
        }

        private List<Node> reconstructPath(
                Map<Node, Node> parent,
                Node goal) {
            List<Node> reversed = new ArrayList<>();
            Node current = goal;
            while (current != null) {
                reversed.add(current);
                current = parent.get(current);
            }
            List<Node> result = new ArrayList<>();
            for (int index = reversed.size() - 1;
                 index >= 0;
                 index--) {
                result.add(reversed.get(index));
            }
            return result;
        }
    }
}
