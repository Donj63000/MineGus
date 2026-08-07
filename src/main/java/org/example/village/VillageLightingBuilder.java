package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

/**
 * Ajoute l'éclairage final du village.
 *
 * <p>La classe sépare volontairement deux responsabilités :</p>
 * <ul>
 *     <li>les lanternes visibles, qui doivent enrichir l'architecture ;</li>
 *     <li>l'audit anti-spawn, qui corrige uniquement les surfaces encore
 *     insuffisamment éclairées avec des blocs {@link Material#LIGHT}
 *     invisibles.</li>
 * </ul>
 *
 * <p>Toutes les opérations Bukkit sont ajoutées à la file de génération
 * existante. Elles restent donc exécutées sur le thread principal, après les
 * bâtiments et la muraille, et bénéficient du rollback de
 * {@code VillageGenerationSession} via le callback {@code setBlock}.</p>
 */
public final class VillageLightingBuilder {

    private static final int DEFAULT_HIDDEN_LIGHT_LEVEL = 12;
    private static final int DEFAULT_TARGET_BLOCK_LIGHT = 2;
    private static final int DEFAULT_SCAN_TILE_SIZE = 7;
    private static final int DEFAULT_SCAN_HEIGHT = 30;
    private static final int DEFAULT_PERIMETER_SPACING = 14;
    private static final int DEFAULT_MAX_HIDDEN_LIGHTS = 2048;
    private static final int[] LIGHT_PLACEMENT_OFFSETS = {2, 1, 0};
    private static final int[][] CARDINAL_DIRECTIONS = {
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1}
    };

    private VillageLightingBuilder() {
    }

    /**
     * Ajoute l'éclairage décoratif puis l'audit anti-spawn à la fin de la file.
     *
     * @param plugin    plugin propriétaire de la configuration ;
     * @param center    centre géométrique du village ;
     * @param layout    plan déjà retenu pour les rues et les lots ;
     * @param rx        demi-largeur intérieure utilisée par la muraille ;
     * @param rz        demi-profondeur intérieure utilisée par la muraille ;
     * @param baseY     niveau du sol principal ;
     * @param queue     file de génération exécutée par lots ;
     * @param setBlock  écriture suivie par la session pour permettre l'undo.
     */
    public static void enqueue(JavaPlugin plugin,
                               Location center,
                               VillageLayoutPlan layout,
                               int rx,
                               int rz,
                               int baseY,
                               Queue<Runnable> queue,
                               TerrainManager.SetBlock setBlock) {
        if (plugin == null || center == null || queue == null || setBlock == null) {
            return;
        }

        World world = center.getWorld();
        if (world == null || rx < 4 || rz < 4) {
            return;
        }

        LightingSettings settings = LightingSettings.from(plugin.getConfig());
        if (!settings.enabled()) {
            return;
        }

        LightingRunState state = new LightingRunState();

        /*
         * Les lumières visibles sont posées avant l'audit. L'audit lit ensuite
         * la lumière de bloc réellement produite et ne complète que les zones
         * restées sombres.
         */
        if (settings.decorative()) {
            List<DecorativeLightAnchor> anchors = planDecorativeLights(
                    center,
                    layout,
                    rx,
                    rz,
                    settings.perimeterSpacing()
            );
            state.plannedDecorativeLights = anchors.size();
            for (DecorativeLightAnchor anchor : anchors) {
                queue.add(() -> placeDecorativeLight(
                        world,
                        anchor,
                        baseY,
                        setBlock,
                        state
                ));
            }
        }

        if (!settings.spawnProof()) {
            return;
        }

        /*
         * outerBounds inclut les tours et le châtelet. Scanner cette emprise
         * couvre donc également les chemins de ronde et une petite couronne
         * autour de la muraille, où un monstre serait immédiatement visible
         * depuis le village.
         */
        int[] outerBounds = WallBuilder.outerBounds(center, rx, rz);
        List<ScanTile> tiles = createScanTiles(
                outerBounds[0],
                outerBounds[1],
                outerBounds[2],
                outerBounds[3],
                settings.scanTileSize()
        );
        state.plannedTiles = tiles.size();

        for (ScanTile tile : tiles) {
            queue.add(() -> auditTile(
                    world,
                    tile,
                    baseY,
                    settings,
                    setBlock,
                    state
            ));
        }

        queue.add(() -> logAuditResult(plugin, settings, state));
    }

    /**
     * Produit un plan déterministe de lumières visibles. Cette méthode ne lit
     * pas le monde et peut donc être testée sans serveur Bukkit.
     */
    static List<DecorativeLightAnchor> planDecorativeLights(Location center,
                                                             VillageLayoutPlan layout,
                                                             int rx,
                                                             int rz,
                                                             int perimeterSpacing) {
        if (center == null || rx < 4 || rz < 4) {
            return List.of();
        }

        int centerX = center.getBlockX();
        int centerZ = center.getBlockZ();
        int spacing = clamp(perimeterSpacing, 8, 24);
        Map<Long, DecorativeLightAnchor> anchors = new LinkedHashMap<>();

        // Quatre lanternes suspendues transforment le puits en cœur lumineux.
        for (int dx : new int[]{-1, 1}) {
            for (int dz : new int[]{-1, 1}) {
                putAnchor(
                        anchors,
                        new DecorativeLightAnchor(
                                centerX + dx,
                                centerZ + dz,
                                DecorativeLightKind.WELL_HANGING,
                                0,
                                0
                        )
                );
            }
        }

        int northZ = centerZ - rz + 3;
        int southZ = centerZ + rz - 3;
        int westX = centerX - rx + 3;
        int eastX = centerX + rx - 3;

        for (int x : centeredPositions(
                centerX - rx + 5,
                centerX + rx - 5,
                spacing
        )) {
            addPerimeterAnchor(
                    anchors,
                    layout,
                    centerX,
                    centerZ,
                    rx,
                    rz,
                    new DecorativeLightAnchor(
                            x,
                            northZ,
                            DecorativeLightKind.PERIMETER_BOLLARD,
                            0,
                            1
                    )
            );
            addPerimeterAnchor(
                    anchors,
                    layout,
                    centerX,
                    centerZ,
                    rx,
                    rz,
                    new DecorativeLightAnchor(
                            x,
                            southZ,
                            DecorativeLightKind.PERIMETER_BOLLARD,
                            0,
                            -1
                    )
            );
        }

        for (int z : centeredPositions(
                centerZ - rz + 5,
                centerZ + rz - 5,
                spacing
        )) {
            addPerimeterAnchor(
                    anchors,
                    layout,
                    centerX,
                    centerZ,
                    rx,
                    rz,
                    new DecorativeLightAnchor(
                            westX,
                            z,
                            DecorativeLightKind.PERIMETER_BOLLARD,
                            1,
                            0
                    )
            );
            addPerimeterAnchor(
                    anchors,
                    layout,
                    centerX,
                    centerZ,
                    rx,
                    rz,
                    new DecorativeLightAnchor(
                            eastX,
                            z,
                            DecorativeLightKind.PERIMETER_BOLLARD,
                            -1,
                            0
                    )
            );
        }

        return List.copyOf(anchors.values());
    }

    /**
     * Découpe une emprise inclusive en tuiles disjointes. Chaque tuile reste
     * volontairement petite afin de respecter le budget temporel de la file de
     * génération et d'éviter une longue pause du thread serveur.
     */
    static List<ScanTile> createScanTiles(int minX,
                                          int maxX,
                                          int minZ,
                                          int maxZ,
                                          int requestedTileSize) {
        if (minX > maxX || minZ > maxZ) {
            return List.of();
        }

        int tileSize = clamp(requestedTileSize, 4, 10);
        List<ScanTile> tiles = new ArrayList<>();
        for (int x = minX; x <= maxX; x += tileSize) {
            int tileMaxX = Math.min(maxX, x + tileSize - 1);
            for (int z = minZ; z <= maxZ; z += tileSize) {
                int tileMaxZ = Math.min(maxZ, z + tileSize - 1);
                tiles.add(new ScanTile(x, tileMaxX, z, tileMaxZ));
            }
        }
        return List.copyOf(tiles);
    }

    /**
     * Rayon horizontal conservateur offert par une lumière placée deux blocs
     * au-dessus d'une surface. Il est exposé au package pour les tests.
     */
    static int conservativeCoverageRadius(int lightLevel, int targetLevel) {
        int normalizedLight = clamp(lightLevel, 4, 15);
        int normalizedTarget = clamp(targetLevel, 1, normalizedLight - 3);
        return Math.max(0, normalizedLight - normalizedTarget - 3);
    }

    private static void addPerimeterAnchor(
            Map<Long, DecorativeLightAnchor> anchors,
            VillageLayoutPlan layout,
            int centerX,
            int centerZ,
            int rx,
            int rz,
            DecorativeLightAnchor anchor) {
        if (!isPerimeterAnchorFree(
                anchor.x(),
                anchor.z(),
                layout,
                centerX,
                centerZ,
                rx,
                rz
        )) {
            return;
        }
        putAnchor(anchors, anchor);
    }

    private static boolean isPerimeterAnchorFree(int x,
                                                  int z,
                                                  VillageLayoutPlan layout,
                                                  int centerX,
                                                  int centerZ,
                                                  int rx,
                                                  int rz) {
        if (x <= centerX - rx || x >= centerX + rx
                || z <= centerZ - rz || z >= centerZ + rz) {
            return false;
        }

        /*
         * Le portail et son parvis restent totalement dégagés. La grande rue
         * est également filtrée plus bas par StreetPlan#contains.
         */
        if (z >= centerZ + rz - 6 && Math.abs(x - centerX) <= 6) {
            return false;
        }

        if (layout == null) {
            return true;
        }

        for (VillageLayoutPlan.LotPlan lot : layout.lots()) {
            if (x >= lot.siteMinX() - 2
                    && x <= lot.siteMaxX() + 2
                    && z >= lot.siteMinZ() - 2
                    && z <= lot.siteMaxZ() + 2) {
                return false;
            }
        }

        for (VillageLayoutPlan.StreetPlan street : layout.streets()) {
            if (street.contains(x, z, 2)) {
                return false;
            }
        }

        return true;
    }

    private static void putAnchor(Map<Long, DecorativeLightAnchor> anchors,
                                  DecorativeLightAnchor anchor) {
        anchors.putIfAbsent(horizontalKey(anchor.x(), anchor.z()), anchor);
    }

    private static List<Integer> centeredPositions(int min,
                                                   int max,
                                                   int spacing) {
        if (min > max) {
            return List.of();
        }

        int length = max - min + 1;
        if (length <= spacing) {
            return List.of(min + (length - 1) / 2);
        }

        List<Integer> positions = new ArrayList<>();
        int first = min + spacing / 2;
        int last = max - spacing / 2;
        for (int value = first; value <= last; value += spacing) {
            positions.add(value);
        }

        if (positions.isEmpty()) {
            positions.add((min + max) / 2);
        }
        return positions;
    }

    private static void placeDecorativeLight(World world,
                                             DecorativeLightAnchor anchor,
                                             int baseY,
                                             TerrainManager.SetBlock setBlock,
                                             LightingRunState state) {
        boolean placed = switch (anchor.kind()) {
            case WELL_HANGING -> placeWellLantern(
                    world,
                    anchor.x(),
                    baseY,
                    anchor.z(),
                    setBlock
            );
            case PERIMETER_BOLLARD -> placePerimeterBollard(
                    world,
                    anchor,
                    baseY,
                    setBlock
            );
        };
        if (placed) {
            state.decorativeLights++;
        }
    }

    private static boolean placeWellLantern(World world,
                                            int x,
                                            int baseY,
                                            int z,
                                            TerrainManager.SetBlock setBlock) {
        int lanternY = baseY + 2;
        int chainY = baseY + 3;
        int supportY = baseY + 4;
        if (!insideWorld(world, lanternY)
                || !insideWorld(world, chainY)
                || !insideWorld(world, supportY)) {
            return false;
        }

        Block support = world.getBlockAt(x, supportY, z);
        Block chain = world.getBlockAt(x, chainY, z);
        Block lantern = world.getBlockAt(x, lanternY, z);
        if (isUnoccupiedAir(support)
                || !isUnoccupiedAir(chain)
                || !isUnoccupiedAir(lantern)) {
            return false;
        }

        setBlock.set(x, chainY, z, Material.CHAIN);
        setBlock.set(x, lanternY, z, Material.LANTERN);
        configureHangingLantern(world, x, lanternY, z);
        return world.getBlockAt(x, lanternY, z).getType() == Material.LANTERN;
    }

    private static boolean placePerimeterBollard(
            World world,
            DecorativeLightAnchor anchor,
            int baseY,
            TerrainManager.SetBlock setBlock) {
        int postY = baseY + 1;
        int lanternY = baseY + 2;
        if (!insideWorld(world, postY) || !insideWorld(world, lanternY)) {
            return false;
        }

        Block floor = world.getBlockAt(anchor.x(), baseY, anchor.z());
        Block post = world.getBlockAt(anchor.x(), postY, anchor.z());
        Block lantern = world.getBlockAt(anchor.x(), lanternY, anchor.z());
        if (!floor.getType().isSolid()
                || !isUnoccupiedAir(post)
                || !isUnoccupiedAir(lantern)) {
            return false;
        }

        setBlock.set(
                anchor.x(),
                postY,
                anchor.z(),
                Material.MOSSY_COBBLESTONE_WALL
        );
        setBlock.set(anchor.x(), lanternY, anchor.z(), Material.LANTERN);

        /*
         * Un tapis de mousse du côté intérieur raccorde le luminaire au jardin
         * sans créer un nouvel obstacle pour les villageois.
         */
        int mossX = anchor.x() + anchor.inwardX();
        int mossZ = anchor.z() + anchor.inwardZ();
        if (insideWorld(world, postY)
                && world.getBlockAt(mossX, baseY, mossZ).getType().isSolid()
                && isUnoccupiedAir(world.getBlockAt(mossX, postY, mossZ))) {
            setBlock.set(mossX, postY, mossZ, Material.MOSS_CARPET);
        }

        return world.getBlockAt(
                anchor.x(),
                lanternY,
                anchor.z()
        ).getType() == Material.LANTERN;
    }

    private static void configureHangingLantern(World world,
                                                 int x,
                                                 int y,
                                                 int z) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() != Material.LANTERN) {
            return;
        }

        BlockData data = Material.LANTERN.createBlockData();
        if (data instanceof Lantern lantern) {
            lantern.setHanging(true);
            block.setBlockData(lantern, false);
        }
    }

    private static void auditTile(World world,
                                  ScanTile tile,
                                  int baseY,
                                  LightingSettings settings,
                                  TerrainManager.SetBlock setBlock,
                                  LightingRunState state) {
        if (state.limitReached) {
            return;
        }
        state.processedTiles++;

        Set<SurfaceCell> darkSurfaces = findDarkSpawnSurfaces(
                world,
                tile,
                baseY,
                settings
        );
        state.darkSurfacesDetected += darkSurfaces.size();
        if (darkSurfaces.isEmpty()) {
            return;
        }

        for (Set<SurfaceCell> component : connectedComponents(darkSurfaces)) {
            illuminateComponent(
                    world,
                    component,
                    settings,
                    setBlock,
                    state
            );
            if (state.limitReached) {
                return;
            }
        }
    }

    private static Set<SurfaceCell> findDarkSpawnSurfaces(
            World world,
            ScanTile tile,
            int baseY,
            LightingSettings settings) {
        Set<SurfaceCell> result = new LinkedHashSet<>();
        int minFeetY = Math.max(baseY + 1, world.getMinHeight() + 1);
        int maxFeetY = Math.min(
                baseY + settings.scanHeight(),
                world.getMaxHeight() - 2
        );

        if (minFeetY > maxFeetY) {
            return result;
        }

        for (int x = tile.minX(); x <= tile.maxX(); x++) {
            for (int z = tile.minZ(); z <= tile.maxZ(); z++) {
                for (int feetY = minFeetY; feetY <= maxFeetY; feetY++) {
                    Block floor = world.getBlockAt(x, feetY - 1, z);
                    if (!floor.getType().isSolid()) {
                        continue;
                    }

                    Block feet = world.getBlockAt(x, feetY, z);
                    Block head = world.getBlockAt(x, feetY + 1, z);
                    if (!isPassableDrySpace(feet)
                            || !isPassableDrySpace(head)
                            || feet.getLightFromBlocks()
                            >= settings.targetBlockLight()) {
                        continue;
                    }

                    /*
                     * Le test est volontairement plus large que la règle
                     * interne de chaque type de monstre : mieux vaut éclairer
                     * quelques escaliers ou dalles en trop que laisser une
                     * terrasse valide dans l'obscurité.
                     */
                    result.add(new SurfaceCell(x, feetY, z));
                }
            }
        }
        return result;
    }

    private static boolean isPassableDrySpace(Block block) {
        return block.isPassable() && !block.isLiquid();
    }

    /**
     * N'écrase jamais un bloc LIGHT déjà présent. Material#isAir couvre les
     * variantes AIR/CAVE_AIR/VOID_AIR, tandis que l'exclusion explicite
     * protège une lumière invisible préexistante et ses BlockData.
     */
    private static boolean isUnoccupiedAir(Block block) {
        return block.getType().isAir()
                && block.getType() != Material.LIGHT;
    }

    private static List<Set<SurfaceCell>> connectedComponents(
            Set<SurfaceCell> cells) {
        List<Set<SurfaceCell>> components = new ArrayList<>();
        Set<SurfaceCell> unvisited = new LinkedHashSet<>(cells);

        while (!unvisited.isEmpty()) {
            SurfaceCell start = unvisited.iterator().next();
            Set<SurfaceCell> component = new LinkedHashSet<>();
            ArrayDeque<SurfaceCell> frontier = new ArrayDeque<>();
            frontier.add(start);
            unvisited.remove(start);

            while (!frontier.isEmpty()) {
                SurfaceCell current = frontier.removeFirst();
                component.add(current);
                forEachNeighbour(current, neighbour -> {
                    if (unvisited.remove(neighbour)) {
                        frontier.addLast(neighbour);
                    }
                });
            }
            components.add(component);
        }

        return components;
    }

    private static void illuminateComponent(
            World world,
            Set<SurfaceCell> component,
            LightingSettings settings,
            TerrainManager.SetBlock setBlock,
            LightingRunState state) {
        Set<SurfaceCell> remaining = new LinkedHashSet<>(component);
        while (!remaining.isEmpty()) {
            if (state.hiddenLights >= settings.maxHiddenLights()) {
                state.limitReached = true;
                state.unresolvedDarkSurfaces += remaining.size();
                return;
            }

            LightPlacement placement = selectLightPlacement(world, remaining);
            if (placement == null) {
                state.unresolvedDarkSurfaces += remaining.size();
                return;
            }

            if (!placeInvisibleLight(
                    world,
                    placement,
                    settings.hiddenLightLevel(),
                    setBlock
            )) {
                /*
                 * Un autre élément a pu occuper la cellule entre le scan et la
                 * pose. On retire seulement ce candidat puis on poursuit avec
                 * le reste du composant.
                 */
                remaining.remove(placement.surface());
                state.unresolvedDarkSurfaces++;
                continue;
            }

            state.hiddenLights++;
            removeCoveredSurfaces(
                    component,
                    remaining,
                    placement,
                    settings.hiddenLightLevel(),
                    settings.targetBlockLight()
            );
        }
    }

    private static LightPlacement selectLightPlacement(
            World world,
            Set<SurfaceCell> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }

        double averageX = candidates.stream()
                .mapToInt(SurfaceCell::x)
                .average()
                .orElse(0.0D);
        double averageY = candidates.stream()
                .mapToInt(SurfaceCell::feetY)
                .average()
                .orElse(0.0D);
        double averageZ = candidates.stream()
                .mapToInt(SurfaceCell::z)
                .average()
                .orElse(0.0D);

        List<SurfaceCell> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator
                .comparingInt(SurfaceCell::x)
                .thenComparingInt(SurfaceCell::feetY)
                .thenComparingInt(SurfaceCell::z));

        LightPlacement best = null;
        double bestScore = Double.MAX_VALUE;
        for (SurfaceCell candidate : ordered) {
            int lightY = findAvailableLightY(world, candidate);
            if (lightY == Integer.MIN_VALUE) {
                continue;
            }

            double dx = candidate.x() - averageX;
            double dy = candidate.feetY() - averageY;
            double dz = candidate.z() - averageZ;
            double score = dx * dx + dy * dy + dz * dz;
            if (score < bestScore) {
                bestScore = score;
                best = new LightPlacement(candidate, lightY);
            }
        }
        return best;
    }

    private static int findAvailableLightY(World world,
                                           SurfaceCell surface) {
        /*
         * La priorité va à une cellule au-dessus de la tête : le bloc LIGHT
         * reste ainsi invisible et ne remplace ni fleurs, ni tapis, ni petit
         * mobilier au niveau des pieds.
         */
        for (int offset : LIGHT_PLACEMENT_OFFSETS) {
            int y = surface.feetY() + offset;
            if (insideWorld(world, y)
                    && isUnoccupiedAir(world.getBlockAt(
                            surface.x(),
                            y,
                            surface.z()
                    ))) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static boolean placeInvisibleLight(
            World world,
            LightPlacement placement,
            int requestedLevel,
            TerrainManager.SetBlock setBlock) {
        SurfaceCell surface = placement.surface();
        Block target = world.getBlockAt(
                surface.x(),
                placement.lightY(),
                surface.z()
        );
        if (!isUnoccupiedAir(target)) {
            return false;
        }

        /*
         * setBlock mémorise d'abord l'AIR original pour /village undo. La
         * modification de BlockData ci-dessous ne change ensuite que le niveau
         * du même bloc déjà suivi par la session.
         */
        setBlock.set(
                surface.x(),
                placement.lightY(),
                surface.z(),
                Material.LIGHT
        );

        Block lightBlock = world.getBlockAt(
                surface.x(),
                placement.lightY(),
                surface.z()
        );
        if (lightBlock.getType() != Material.LIGHT) {
            return false;
        }

        BlockData data = Material.LIGHT.createBlockData();
        if (data instanceof Levelled levelled) {
            levelled.setLevel(clamp(requestedLevel, 0, 15));
            lightBlock.setBlockData(levelled, false);
        }
        return true;
    }

    private static void removeCoveredSurfaces(
            Set<SurfaceCell> component,
            Set<SurfaceCell> remaining,
            LightPlacement placement,
            int lightLevel,
            int targetLevel) {
        int maxDistance = Math.max(0, lightLevel - targetLevel - 1);
        int initialDistance = Math.abs(
                placement.lightY() - placement.surface().feetY()
        );

        Map<SurfaceCell, Integer> distances = new HashMap<>();
        PriorityQueue<CellDistance> frontier = new PriorityQueue<>(
                Comparator.comparingInt(CellDistance::distance)
        );
        distances.put(placement.surface(), initialDistance);
        frontier.add(new CellDistance(
                placement.surface(),
                initialDistance
        ));

        while (!frontier.isEmpty()) {
            CellDistance current = frontier.poll();
            int knownDistance = distances.getOrDefault(
                    current.cell(),
                    Integer.MAX_VALUE
            );
            if (current.distance() != knownDistance) {
                continue;
            }
            if (current.distance() > maxDistance) {
                break;
            }

            remaining.remove(current.cell());
            forEachNeighbour(current.cell(), neighbour -> {
                if (!component.contains(neighbour)) {
                    return;
                }

                int edgeCost = 1 + Math.abs(
                        neighbour.feetY() - current.cell().feetY()
                );
                int candidateDistance = current.distance() + edgeCost;
                if (candidateDistance > maxDistance) {
                    return;
                }

                int previous = distances.getOrDefault(
                        neighbour,
                        Integer.MAX_VALUE
                );
                if (candidateDistance < previous) {
                    distances.put(neighbour, candidateDistance);
                    frontier.add(new CellDistance(
                            neighbour,
                            candidateDistance
                    ));
                }
            });
        }

        /*
         * Même avec une configuration volontairement très faible, la cellule
         * qui porte la source ne doit jamais maintenir la boucle active.
         */
        remaining.remove(placement.surface());
    }

    private static void forEachNeighbour(SurfaceCell cell,
                                         java.util.function.Consumer<SurfaceCell> consumer) {
        for (int[] direction : CARDINAL_DIRECTIONS) {
            for (int deltaY = -1; deltaY <= 1; deltaY++) {
                consumer.accept(new SurfaceCell(
                        cell.x() + direction[0],
                        cell.feetY() + deltaY,
                        cell.z() + direction[1]
                ));
            }
        }
    }

    private static void logAuditResult(JavaPlugin plugin,
                                       LightingSettings settings,
                                       LightingRunState state) {
        if (state.limitReached || state.unresolvedDarkSurfaces > 0) {
            plugin.getLogger().warning(
                    "Audit lumineux du village incomplet : "
                            + state.hiddenLights
                            + " lumières invisibles posées, "
                            + "au moins "
                            + state.unresolvedDarkSurfaces
                            + " surfaces sombres non corrigées ; "
                            + state.processedTiles
                            + "/"
                            + state.plannedTiles
                            + " tuiles analysées. "
                            + "Vérifiez village.lighting.max-hidden-lights "
                            + "(actuel : "
                            + settings.maxHiddenLights()
                            + ")."
            );
            return;
        }

        plugin.getLogger().fine(
                "Éclairage du village terminé : "
                        + state.decorativeLights
                        + "/"
                        + state.plannedDecorativeLights
                        + " lanternes décoratives, "
                        + state.hiddenLights
                        + " lumières invisibles, "
                        + state.darkSurfacesDetected
                        + " surfaces sombres initialement détectées sur "
                        + state.plannedTiles
                        + " tuiles."
        );
    }

    private static boolean insideWorld(World world, int y) {
        return y >= world.getMinHeight() && y < world.getMaxHeight();
    }

    private static long horizontalKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    enum DecorativeLightKind {
        WELL_HANGING,
        PERIMETER_BOLLARD
    }

    record DecorativeLightAnchor(int x,
                                 int z,
                                 DecorativeLightKind kind,
                                 int inwardX,
                                 int inwardZ) {
    }

    record ScanTile(int minX, int maxX, int minZ, int maxZ) {
        ScanTile {
            if (minX > maxX || minZ > maxZ) {
                throw new IllegalArgumentException(
                        "Tuile d'éclairage invalide."
                );
            }
        }

        boolean contains(int x, int z) {
            return x >= minX && x <= maxX
                    && z >= minZ && z <= maxZ;
        }
    }

    private record SurfaceCell(int x, int feetY, int z) {
    }

    private record LightPlacement(SurfaceCell surface, int lightY) {
    }

    private record CellDistance(SurfaceCell cell, int distance) {
    }

    private record LightingSettings(boolean enabled,
                                    boolean decorative,
                                    boolean spawnProof,
                                    int hiddenLightLevel,
                                    int targetBlockLight,
                                    int scanTileSize,
                                    int scanHeight,
                                    int perimeterSpacing,
                                    int maxHiddenLights) {

        private static LightingSettings from(FileConfiguration config) {
            boolean enabled = config.getBoolean(
                    "village.lighting.enabled",
                    true
            );
            int hiddenLevel = clamp(
                    config.getInt(
                            "village.lighting.hidden-light-level",
                            DEFAULT_HIDDEN_LIGHT_LEVEL
                    ),
                    4,
                    15
            );
            int targetLevel = clamp(
                    config.getInt(
                            "village.lighting.target-min-block-light",
                            DEFAULT_TARGET_BLOCK_LIGHT
                    ),
                    1,
                    hiddenLevel - 3
            );

            return new LightingSettings(
                    enabled,
                    enabled && config.getBoolean(
                            "village.lighting.decorative",
                            true
                    ),
                    enabled && config.getBoolean(
                            "village.lighting.spawn-proof",
                            true
                    ),
                    hiddenLevel,
                    targetLevel,
                    clamp(
                            config.getInt(
                                    "village.lighting.scan-tile-size",
                                    DEFAULT_SCAN_TILE_SIZE
                            ),
                            4,
                            10
                    ),
                    clamp(
                            config.getInt(
                                    "village.lighting.scan-height",
                                    DEFAULT_SCAN_HEIGHT
                            ),
                            16,
                            40
                    ),
                    clamp(
                            config.getInt(
                                    "village.lighting.perimeter-lantern-spacing",
                                    DEFAULT_PERIMETER_SPACING
                            ),
                            8,
                            24
                    ),
                    clamp(
                            config.getInt(
                                    "village.lighting.max-hidden-lights",
                                    DEFAULT_MAX_HIDDEN_LIGHTS
                            ),
                            64,
                            4096
                    )
            );
        }
    }

    private static final class LightingRunState {
        private int plannedDecorativeLights;
        private int decorativeLights;
        private int plannedTiles;
        private int processedTiles;
        private int darkSurfacesDetected;
        private int hiddenLights;
        private int unresolvedDarkSurfaces;
        private boolean limitReached;
    }
}
