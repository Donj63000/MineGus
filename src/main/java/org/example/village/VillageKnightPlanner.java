package org.example.village;

import org.bukkit.Location;
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
 * <p>Les patrouilles urbaines suivent le graphe réel des rues. Elles ne sont
 * donc pas calculées comme de simples rectangles susceptibles de traverser une
 * maison. Les archers ne sont ajoutés que si le chemin de ronde construit est
 * effectivement praticable.</p>
 */
public final class VillageKnightPlanner {

    private static final int MAX_SEGMENT_LENGTH = 10;
    private static final int URBAN_WALK_STEPS = 9;
    private static final int RAMPART_SAMPLE_STEP = 8;
    private static final int MAX_RAMPART_ROUTE_POINTS = 480;
    private static final double MIN_RAMPART_VALID_RATIO = 0.65D;

    private VillageKnightPlanner() {
    }

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

        List<VillageKnightGarrison.PatrolRoute> routes =
                new ArrayList<>();
        List<VillageKnightGarrison.KnightSlot> slots =
                new ArrayList<>();
        int nextRouteId = 0;

        StreetGraph graph = StreetGraph.from(layout.streets());
        List<Node> mainComponent = graph.largestComponent();

        /*
         * Les postes fixes utilisent d'abord les lieux les plus importants,
         * puis sont rabattus sur le nœud de rue le plus proche pour ne jamais
         * placer un chevalier au milieu d'un bâtiment.
         */
        List<VillageKnightGarrison.BlockPoint> strategicPoints =
                buildStrategicPoints(
                        layout,
                        anchors,
                        villageCenter,
                        mainComponent,
                        baseY,
                        settings.strategicGuardCount()
                );
        for (VillageKnightGarrison.BlockPoint point : strategicPoints) {
            int routeId = nextRouteId++;
            routes.add(new VillageKnightGarrison.PatrolRoute(
                    routeId,
                    VillageKnightGarrison.RouteKind.STRATEGIC,
                    List.of(point),
                    0,
                    System.currentTimeMillis()
            ));
            slots.add(newSlot(
                    world.getUID(),
                    villageId,
                    routeId,
                    VillageKnightGarrison.UnitKind.SWORDSMAN,
                    0,
                    point
            ));
        }

        List<List<VillageKnightGarrison.BlockPoint>> urbanRoutes =
                buildUrbanRoutes(
                        graph,
                        mainComponent,
                        villageCenter,
                        baseY,
                        settings.urbanPatrolCount(),
                        villageId
                );
        for (List<VillageKnightGarrison.BlockPoint> points : urbanRoutes) {
            int routeId = nextRouteId++;
            routes.add(new VillageKnightGarrison.PatrolRoute(
                    routeId,
                    VillageKnightGarrison.RouteKind.URBAN,
                    points,
                    0,
                    System.currentTimeMillis()
            ));
            for (int formationIndex = 0;
                 formationIndex < settings.soldiersPerPatrol();
                 formationIndex++) {
                slots.add(newSlot(
                        world.getUID(),
                        villageId,
                        routeId,
                        VillageKnightGarrison.UnitKind.SWORDSMAN,
                        formationIndex,
                        points.get(0)
                ));
            }
        }

        /*
         * Un archer reçoit sa propre progression sur la même boucle. Des
         * départs espacés évitent que tous les tireurs se regroupent au même
         * angle de la muraille.
         */
        if (settings.wallArchersEnabled()
                && settings.wallArcherCount() > 0) {
            List<VillageKnightGarrison.BlockPoint> rampart =
                    buildRampartRoute(
                            world,
                            villageCenter,
                            radiusX,
                            radiusZ,
                            baseY
                    );
            if (isRampartUsable(world, rampart)) {
                int archerCount = settings.wallArcherCount();
                for (int archerIndex = 0;
                     archerIndex < archerCount;
                     archerIndex++) {
                    int routeId = nextRouteId++;
                    int startIndex = Math.floorMod(
                            archerIndex * rampart.size()
                                    / Math.max(1, archerCount),
                            rampart.size()
                    );
                    routes.add(new VillageKnightGarrison.PatrolRoute(
                            routeId,
                            VillageKnightGarrison.RouteKind.RAMPART,
                            rampart,
                            startIndex,
                            System.currentTimeMillis()
                    ));
                    slots.add(newSlot(
                            world.getUID(),
                            villageId,
                            routeId,
                            VillageKnightGarrison.UnitKind.ARCHER,
                            0,
                            rampart.get(startIndex)
                    ));
                }
            }
        }

        if (slots.isEmpty()) {
            return null;
        }

        int protectedRadiusX = Math.max(4, radiusX);
        int protectedRadiusZ = Math.max(4, radiusZ);
        return new VillageKnightGarrison(
                villageId,
                world.getUID(),
                world.getName(),
                new VillageKnightGarrison.BlockPoint(
                        villageCenter.getBlockX(),
                        baseY + 1,
                        villageCenter.getBlockZ()
                ),
                protectedRadiusX,
                protectedRadiusZ,
                System.currentTimeMillis(),
                routes,
                slots
        );
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

    private static List<VillageKnightGarrison.BlockPoint>
    buildStrategicPoints(VillageLayoutPlan layout,
                         Map<String, Location> suppliedAnchors,
                         Location center,
                         List<Node> streetNodes,
                         int baseY,
                         int desiredCount) {
        if (desiredCount <= 0) {
            return List.of();
        }

        Map<String, Location> anchors =
                suppliedAnchors == null || suppliedAnchors.isEmpty()
                        ? layout.anchors()
                        : suppliedAnchors;

        List<String> priorities = List.of(
                "gate",
                "keep",
                "plaza",
                "market",
                "town_hall",
                "church",
                "forge",
                "inn",
                "stable"
        );
        List<VillageKnightGarrison.BlockPoint> result =
                new ArrayList<>();

        for (String key : priorities) {
            if (result.size() >= desiredCount) {
                break;
            }
            Location anchor = anchors.get(key);
            Node nearest = anchor != null
                    ? nearestNode(streetNodes,
                    anchor.getBlockX(),
                    anchor.getBlockZ())
                    : null;

            int x;
            int z;
            int y = baseY + 1;
            if ("gate".equals(key) || "keep".equals(key)) {
                /*
                 * Ces deux ancres sont explicitement construites comme points
                 * d'arrivée sûrs par WallBuilder et KeepBuilder.
                 */
                if (anchor == null) {
                    continue;
                }
                x = anchor.getBlockX();
                z = anchor.getBlockZ();
                y = "gate".equals(key)
                        ? baseY + 1
                        : Math.max(baseY + 1, anchor.getBlockY());
            } else if (nearest != null) {
                x = nearest.x();
                z = nearest.z();
            } else if ("plaza".equals(key)) {
                x = center.getBlockX();
                z = center.getBlockZ();
            } else {
                continue;
            }

            VillageKnightGarrison.BlockPoint candidate =
                    new VillageKnightGarrison.BlockPoint(x, y, z);
            if (isFarEnough(result, candidate, 4)) {
                result.add(candidate);
            }
        }

        /*
         * Une configuration demandant davantage de postes que d'ancres
         * architecturales utilise des extrémités de rue bien espacées.
         */
        if (result.size() < desiredCount) {
            List<Node> spread = new ArrayList<>(streetNodes);
            spread.sort(Comparator.comparingLong(
                    node -> -squaredDistance(
                            node.x(),
                            node.z(),
                            center.getBlockX(),
                            center.getBlockZ()
                    )
            ));
            for (Node node : spread) {
                if (result.size() >= desiredCount) {
                    break;
                }
                VillageKnightGarrison.BlockPoint candidate =
                        new VillageKnightGarrison.BlockPoint(
                                node.x(),
                                baseY + 1,
                                node.z()
                        );
                if (isFarEnough(result, candidate, 8)) {
                    result.add(candidate);
                }
            }
        }
        return result;
    }

    private static boolean isFarEnough(
            Collection<VillageKnightGarrison.BlockPoint> existing,
            VillageKnightGarrison.BlockPoint candidate,
            int minimumDistance) {
        long minimumSq = (long) minimumDistance * minimumDistance;
        for (VillageKnightGarrison.BlockPoint point : existing) {
            if (point.horizontalDistanceSquared(candidate) < minimumSq) {
                return false;
            }
        }
        return true;
    }

    private static List<List<VillageKnightGarrison.BlockPoint>>
    buildUrbanRoutes(StreetGraph graph,
                     List<Node> mainComponent,
                     Location center,
                     int baseY,
                     int desiredRoutes,
                     int villageId) {
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
            return List.of(List.of(centerPoint));
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
            result.add(expanded);
        }
        return result;
    }

    private static List<Node> chooseSpreadStarts(List<Node> nodes,
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
                 * Cette branche ne devrait pas être utilisée par le graphe des
                 * rues, mais garantit un trajet Manhattan sûr si un futur
                 * planificateur ajoute une connexion diagonale.
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

        /*
         * La progression de route est cyclique : conserver une copie du point
         * initial en fin de liste ferait marquer deux arrivées successives.
         */
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
        if (points.isEmpty()
                || !points.get(points.size() - 1).equals(point)) {
            points.add(point);
        }
    }

    private static List<VillageKnightGarrison.BlockPoint>
    buildRampartRoute(World world,
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

        /*
         * La dalle du chemin de ronde est à baseY + WALL_HEIGHT. L'entité doit
         * avoir ses pieds exactement un bloc au-dessus, au milieu des trois
         * blocs d'épaisseur afin de rester entre parapet et créneaux.
         */
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
        if (denseRing.size() < 8) {
            return List.of();
        }

        boolean completeLoop = true;
        for (VillageKnightGarrison.BlockPoint point : denseRing) {
            if (!isWalkableRampartPoint(world, point)) {
                completeLoop = false;
                break;
            }
        }

        List<VillageKnightGarrison.BlockPoint> usable =
                completeLoop
                        ? denseRing
                        : longestWalkableRampartRun(world, denseRing);
        int minimumCoverage = (int) Math.ceil(
                denseRing.size() * MIN_RAMPART_VALID_RATIO
        );
        if (usable.size() < Math.max(8, minimumCoverage)) {
            return List.of();
        }

        /*
         * Une brèche, typiquement celle du châtelet, transforme la boucle en
         * chemin ouvert. Le trajet est alors mis en aller-retour : sa fermeture
         * logique ne saute jamais d'une extrémité à l'autre au-dessus du vide.
         */
        return sampleRampartRun(usable, completeLoop);
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
    longestWalkableRampartRun(
            World world,
            List<VillageKnightGarrison.BlockPoint> ring) {
        int size = ring.size();
        boolean[] walkable = new boolean[size];
        for (int index = 0; index < size; index++) {
            walkable[index] = isWalkableRampartPoint(
                    world,
                    ring.get(index)
            );
        }

        int bestStart = -1;
        int bestLength = 0;
        int currentStart = 0;
        int currentLength = 0;

        /*
         * Deux passages sur le tableau permettent de reconnaître une section
         * continue qui traverse la jonction fin/début de la représentation.
         */
        for (int index = 0; index < size * 2; index++) {
            if (walkable[index % size] && currentLength < size) {
                if (currentLength == 0) {
                    currentStart = index;
                }
                currentLength++;
                if (currentLength > bestLength) {
                    bestStart = currentStart;
                    bestLength = currentLength;
                }
            } else {
                currentLength = 0;
            }
        }

        if (bestStart < 0 || bestLength <= 0) {
            return List.of();
        }
        List<VillageKnightGarrison.BlockPoint> result =
                new ArrayList<>(bestLength);
        for (int offset = 0; offset < bestLength; offset++) {
            result.add(ring.get((bestStart + offset) % size));
        }
        return result;
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
        if (world == null || points == null || points.size() < 4
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
                && feet.isPassable()
                && head.isPassable()
                && !feet.isLiquid()
                && !head.isLiquid();
    }

    private static Node nearestNode(List<Node> nodes, int x, int z) {
        return nodes.stream()
                .min(Comparator.comparingLong(node ->
                        squaredDistance(node.x(), node.z(), x, z)))
                .orElse(null);
    }

    private static long squaredDistance(int firstX,
                                        int firstZ,
                                        int secondX,
                                        int secondZ) {
        long dx = (long) firstX - secondX;
        long dz = (long) firstZ - secondZ;
        return dx * dx + dz * dz;
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
