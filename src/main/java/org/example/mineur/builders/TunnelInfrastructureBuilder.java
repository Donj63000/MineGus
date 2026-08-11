package org.example.mineur.builders;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Je planifie l'infrastructure d'un tunnel sans lire ni modifier un monde.
 *
 * <p>Je conserve ici une géométrie pure : l'appelant reste responsable des
 * événements de protection, des snapshots et de l'application des blocs.</p>
 */
public final class TunnelInfrastructureBuilder {

    public static final int MAX_FLUID_INSPECTIONS = 1_000_000;
    public static final int MAX_DECORATION_OPERATIONS = 1_000_000;

    private static final Material PERMANENT_SEAL_MATERIAL =
            Material.LIGHT_BLUE_STAINED_GLASS;
    private static final Material TEMPORARY_SEAL_MATERIAL = Material.GLASS;
    private static final Material SUPPORT_POST_MATERIAL = Material.SPRUCE_FENCE;
    private static final Material SUPPORT_BEAM_MATERIAL =
            Material.STRIPPED_SPRUCE_LOG;
    private static final Material LIGHT_MATERIAL = Material.SHROOMLIGHT;
    private static final Material PLATFORM_MATERIAL = Material.SPRUCE_PLANKS;

    private TunnelInfrastructureBuilder() {
        // Je garde cette classe strictement utilitaire.
    }

    public enum PlacementKind {
        PERMANENT_SEAL,
        TEMPORARY_SEAL,
        SUPPORT_POST,
        SUPPORT_BEAM,
        LIGHT,
        PLATFORM,
        CHEST,
        WORKSTATION
    }

    public record BlockPos(int x, int y, int z) {

        public BlockPos offset(int dx, int dy, int dz) {
            return new BlockPos(
                    Math.addExact(x, dx),
                    Math.addExact(y, dy),
                    Math.addExact(z, dz)
            );
        }
    }

    /**
     * Je représente un volume intérieur inclusif.
     */
    public record Volume(int minX,
                         int maxX,
                         int minY,
                         int maxY,
                         int minZ,
                         int maxZ) {

        public Volume {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException(
                        "Les bornes du volume de tunnel sont invalides."
                );
            }
        }

        public long sizeX() {
            return inclusiveSpan(minX, maxX);
        }

        public long sizeY() {
            return inclusiveSpan(minY, maxY);
        }

        public long sizeZ() {
            return inclusiveSpan(minZ, maxZ);
        }

        public long blockCount() {
            return multiplyExact(sizeX(), sizeY(), sizeZ());
        }

        public boolean contains(BlockPos position) {
            return position != null
                    && position.x() >= minX
                    && position.x() <= maxX
                    && position.y() >= minY
                    && position.y() <= maxY
                    && position.z() >= minZ
                    && position.z() <= maxZ;
        }
    }

    /**
     * Je borne tous les paramètres qui peuvent multiplier les placements.
     */
    public record Settings(int supportSpacing,
                           int firstSupportOffset,
                           int lightEveryFrames,
                           int centralAisleWidth,
                           int arrivalPlatformDepth,
                           int maximumOperations) {

        public Settings {
            if (supportSpacing < 1 || supportSpacing > 1_024) {
                throw new IllegalArgumentException(
                        "L'espacement des cadres doit être compris entre 1 et 1024."
                );
            }
            if (firstSupportOffset < 0 || firstSupportOffset > 1_024) {
                throw new IllegalArgumentException(
                        "Le décalage du premier cadre doit être compris entre 0 et 1024."
                );
            }
            if (lightEveryFrames < 1 || lightEveryFrames > 1_024) {
                throw new IllegalArgumentException(
                        "L'intervalle lumineux doit être compris entre 1 et 1024."
                );
            }
            if (centralAisleWidth < 1
                    || centralAisleWidth > 255
                    || (centralAisleWidth & 1) == 0) {
                throw new IllegalArgumentException(
                        "L'allée centrale doit avoir une largeur impaire entre 1 et 255."
                );
            }
            if (arrivalPlatformDepth < 3 || arrivalPlatformDepth > 1_024) {
                throw new IllegalArgumentException(
                        "La profondeur des plateformes doit être comprise entre 3 et 1024."
                );
            }
            if (maximumOperations < 1
                    || maximumOperations > MAX_DECORATION_OPERATIONS) {
                throw new IllegalArgumentException(
                        "Le budget de décoration est invalide."
                );
            }
        }

        public static Settings defaults() {
            return new Settings(5, 3, 1, 3, 4, 20_000);
        }
    }

    public record Placement(BlockPos position,
                            Material material,
                            PlacementKind kind) {

        public Placement {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(kind, "kind");
            if (!material.isBlock() || material.isAir()) {
                throw new IllegalArgumentException(
                        "Un placement doit utiliser un bloc matériel non vide."
                );
            }
        }
    }

    public record FluidPlan(Volume volume,
                            BlockFace direction,
                            Set<BlockPos> drainedInterior,
                            List<Placement> permanentSeals,
                            List<Placement> temporarySeals) {

        public FluidPlan {
            Objects.requireNonNull(volume, "volume");
            requireCardinal(direction);
            drainedInterior = immutableSet(drainedInterior);
            permanentSeals = immutableList(permanentSeals);
            temporarySeals = immutableList(temporarySeals);
            requirePositionsInside(volume, drainedInterior);
            requirePlacementsInside(
                    volume,
                    permanentSeals,
                    PlacementKind.PERMANENT_SEAL
            );
            requirePlacementsInside(
                    volume,
                    temporarySeals,
                    PlacementKind.TEMPORARY_SEAL
            );
            requireUniquePositions(permanentSeals, temporarySeals);
        }

        public List<Placement> allSeals() {
            List<Placement> seals = new ArrayList<>(
                    permanentSeals.size() + temporarySeals.size()
            );
            seals.addAll(permanentSeals);
            seals.addAll(temporarySeals);
            return List.copyOf(seals);
        }
    }

    /**
     * Je parcours progressivement les positions à inspecter sans conserver de
     * référence vers un monde Bukkit. L'appelant fournit les lectures et choisit
     * ainsi le nombre maximal d'accès effectués pendant chaque tick.
     */
    public static final class FluidScanner {

        private final Volume volume;
        private final BlockFace direction;
        private final Material permanentSealMaterial;
        private final Material temporarySealMaterial;
        private final OrientedVolume oriented;
        private final FluidInspectionLayout layout;
        private final long interiorSliceSize;
        private final long sideWallRowSize;
        private final Set<BlockPos> drained = new LinkedHashSet<>();
        private final Map<BlockPos, Placement> permanent = new LinkedHashMap<>();
        private final Map<BlockPos, Placement> temporary = new LinkedHashMap<>();

        private long inspectionsCompleted;
        private FluidPlan completedPlan;

        private FluidScanner(Volume volume,
                             BlockFace direction,
                             boolean sealEntrance,
                             Material permanentSealMaterial,
                             Material temporarySealMaterial) {
            this.volume = Objects.requireNonNull(volume, "volume");
            requireCardinal(direction);
            requireSolidBlock(permanentSealMaterial, "scellement permanent");
            requireSolidBlock(temporarySealMaterial, "scellement temporaire");
            this.direction = direction;
            this.permanentSealMaterial = permanentSealMaterial;
            this.temporarySealMaterial = temporarySealMaterial;
            this.oriented = new OrientedVolume(volume, direction);
            this.layout = fluidInspectionLayout(oriented, sealEntrance);
            this.interiorSliceSize = multiplyExact(
                    oriented.width(),
                    oriented.height()
            );
            this.sideWallRowSize = multiplyExact(2L, oriented.height());
        }

        /**
         * Je garde cette surcharge pour les mondes où seule la présence d'un
         * fluide constitue une menace sur les limites du tunnel.
         */
        public boolean scan(int maximumLookups,
                            Predicate<BlockPos> fluidLookup) {
            return scan(maximumLookups, fluidLookup, fluidLookup);
        }

        /**
         * Je consomme au plus le budget demandé. Les fluides intérieurs et
         * l'entrée utilisent le premier prédicat ; les parois, le plafond et le
         * front utilisent le second afin de pouvoir fermer aussi une ouverture
         * de grotte susceptible d'amener un fluide plus tard.
         */
        public boolean scan(int maximumLookups,
                            Predicate<BlockPos> fluidLookup,
                            Predicate<BlockPos> boundaryThreatLookup) {
            if (maximumLookups < 1) {
                throw new IllegalArgumentException(
                        "Le budget de lectures doit être strictement positif."
                );
            }
            Objects.requireNonNull(fluidLookup, "fluidLookup");
            Objects.requireNonNull(boundaryThreatLookup, "boundaryThreatLookup");

            int remaining = maximumLookups;
            while (remaining > 0
                    && inspectionsCompleted < layout.totalInspections()) {
                inspect(
                        inspectionsCompleted,
                        fluidLookup,
                        boundaryThreatLookup
                );
                inspectionsCompleted++;
                remaining--;
            }
            if (inspectionsCompleted == layout.totalInspections()
                    && completedPlan == null) {
                completedPlan = new FluidPlan(
                        volume,
                        direction,
                        drained,
                        List.copyOf(permanent.values()),
                        List.copyOf(temporary.values())
                );
            }
            return completedPlan != null;
        }

        public boolean isComplete() {
            return completedPlan != null;
        }

        public long inspectionsCompleted() {
            return inspectionsCompleted;
        }

        public long totalInspections() {
            return layout.totalInspections();
        }

        /**
         * Je ne publie aucune collection partielle : le plan devient accessible
         * uniquement lorsque toutes les lectures ont été effectuées.
         */
        public FluidPlan result() {
            if (completedPlan == null) {
                throw new IllegalStateException(
                        "Le scan des fluides n'est pas encore terminé."
                );
            }
            return completedPlan;
        }

        private void inspect(long inspectionIndex,
                             Predicate<BlockPos> fluidLookup,
                             Predicate<BlockPos> boundaryThreatLookup) {
            long width = oriented.width();
            long height = oriented.height();

            if (inspectionIndex < layout.interiorEnd()) {
                long longitudinal = inspectionIndex / interiorSliceSize;
                long remainder = inspectionIndex % interiorSliceSize;
                long transverse = remainder / height;
                long y = (long) volume.minY() + remainder % height;
                BlockPos position = oriented.position(
                        transverse,
                        y,
                        longitudinal
                );
                if (fluidLookup.test(position)) {
                    drained.add(position);
                }
                return;
            }

            if (inspectionIndex < layout.sideWallsEnd()) {
                long localIndex = inspectionIndex - layout.interiorEnd();
                long longitudinal = localIndex / sideWallRowSize;
                long rowIndex = localIndex % sideWallRowSize;
                long y = (long) volume.minY() + rowIndex / 2L;
                boolean leftWall = (rowIndex & 1L) == 0L;
                long inspectedTransverse = leftWall ? -1L : width;
                long sealedTransverse = leftWall ? 0L : width - 1L;
                addSealIfMatched(
                        permanent,
                        oriented.position(
                                inspectedTransverse,
                                y,
                                longitudinal
                        ),
                        oriented.position(
                                sealedTransverse,
                                y,
                                longitudinal
                        ),
                        permanentSealMaterial,
                        PlacementKind.PERMANENT_SEAL,
                        boundaryThreatLookup,
                        Set.of()
                );
                return;
            }

            if (inspectionIndex < layout.ceilingEnd()) {
                long localIndex = inspectionIndex - layout.sideWallsEnd();
                long longitudinal = localIndex / width;
                long transverse = localIndex % width;
                long ceilingY = checkedAdd(volume.maxY(), 1L, "Y du plafond");
                addSealIfMatched(
                        permanent,
                        oriented.position(transverse, ceilingY, longitudinal),
                        oriented.position(
                                transverse,
                                volume.maxY(),
                                longitudinal
                        ),
                        permanentSealMaterial,
                        PlacementKind.PERMANENT_SEAL,
                        boundaryThreatLookup,
                        Set.of()
                );
                return;
            }

            if (inspectionIndex < layout.entranceEnd()) {
                long localIndex = inspectionIndex - layout.ceilingEnd();
                inspectFace(
                        localIndex,
                        -1L,
                        0L,
                        permanentSealMaterial,
                        PlacementKind.PERMANENT_SEAL,
                        fluidLookup,
                        permanent,
                        Set.of()
                );
                return;
            }

            long localIndex = inspectionIndex - layout.entranceEnd();
            inspectFace(
                    localIndex,
                    oriented.length(),
                    oriented.length() - 1L,
                    temporarySealMaterial,
                    PlacementKind.TEMPORARY_SEAL,
                    boundaryThreatLookup,
                    temporary,
                    permanent.keySet()
            );
        }

        private void inspectFace(long localIndex,
                                 long inspectedLongitudinal,
                                 long sealedLongitudinal,
                                 Material material,
                                 PlacementKind kind,
                                 Predicate<BlockPos> lookup,
                                 Map<BlockPos, Placement> destination,
                                 Set<BlockPos> protectedPositions) {
            long height = oriented.height();
            long transverse = localIndex / height;
            long y = (long) volume.minY() + localIndex % height;
            addSealIfMatched(
                    destination,
                    oriented.position(transverse, y, inspectedLongitudinal),
                    oriented.position(transverse, y, sealedLongitudinal),
                    material,
                    kind,
                    lookup,
                    protectedPositions
            );
        }
    }

    public record Plan(Volume volume,
                       BlockFace direction,
                       boolean arrivalRoom,
                       List<Placement> placements) {

        public Plan {
            Objects.requireNonNull(volume, "volume");
            requireCardinal(direction);
            placements = immutableList(placements);
            requirePlacementsInside(volume, placements, null);
            requireUniquePositions(placements, List.of());
        }

        public List<Placement> placementsOfKind(PlacementKind kind) {
            Objects.requireNonNull(kind, "kind");
            return placements.stream()
                    .filter(placement -> placement.kind() == kind)
                    .toList();
        }
    }

    /**
     * Je vérifie qu'un scan de fluides peut être planifié sans dépasser
     * la limite globale. Je réutilise volontairement le calcul du scanner afin
     * que ce garde-fou ne puisse pas diverger de la géométrie réellement lue.
     */
    public static boolean isFluidInspectionPlanWithinBudget(
            Volume volume,
            BlockFace direction,
            boolean sealEntrance) {
        Objects.requireNonNull(volume, "volume");
        requireCardinal(direction);
        try {
            return fluidInspectionCount(volume, direction, sealEntrance)
                    <= MAX_FLUID_INSPECTIONS;
        } catch (IllegalArgumentException exception) {
            // Je considère aussi tout débordement numérique comme hors budget.
            return false;
        }
    }

    /**
     * Je publie le nombre exact de lectures nécessaires afin que l'appelant
     * puisse appliquer un plafond opérationnel plus strict que la limite globale.
     */
    public static long fluidInspectionCount(Volume volume,
                                            BlockFace direction,
                                            boolean sealEntrance) {
        Objects.requireNonNull(volume, "volume");
        requireCardinal(direction);
        return calculateFluidInspectionLayout(
                new OrientedVolume(volume, direction),
                sealEntrance
        ).totalInspections();
    }

    /**
     * Je reconstruis en O(1) le prisme de tous les tronçons réguliers déjà
     * atteints. Le premier volume spécial (salle d'arrivée) reste volontairement
     * séparé, car il peut être plus large et plus haut que le corridor.
     */
    public static Volume historicalCorridorVolume(Volume currentSection,
                                                  BlockFace direction,
                                                  int sectionCount) {
        Objects.requireNonNull(currentSection, "currentSection");
        requireCardinal(direction);
        if (sectionCount < 1) {
            throw new IllegalArgumentException(
                    "Le nombre de tronçons historiques doit être positif."
            );
        }

        OrientedVolume oriented = new OrientedVolume(currentSection, direction);
        long previousLength = multiplyExact(
                sectionCount - 1L,
                oriented.length()
        );
        int minX = currentSection.minX();
        int maxX = currentSection.maxX();
        int minZ = currentSection.minZ();
        int maxZ = currentSection.maxZ();
        switch (direction) {
            case NORTH -> maxZ = checkedCoordinate(
                    (long) maxZ + previousLength,
                    "Z maximum de l'historique"
            );
            case SOUTH -> minZ = checkedCoordinate(
                    (long) minZ - previousLength,
                    "Z minimum de l'historique"
            );
            case WEST -> maxX = checkedCoordinate(
                    (long) maxX + previousLength,
                    "X maximum de l'historique"
            );
            case EAST -> minX = checkedCoordinate(
                    (long) minX - previousLength,
                    "X minimum de l'historique"
            );
            default -> throw new IllegalArgumentException(
                    "La direction du tunnel doit être cardinale."
            );
        }
        return new Volume(
                minX,
                maxX,
                currentSection.minY(),
                currentSection.maxY(),
                minZ,
                maxZ
        );
    }

    /**
     * Je classe chaque fluide intérieur ou limitrophe sans effectuer la moindre
     * écriture Bukkit.
     */
    public static FluidPlan planFluids(Volume volume,
                                       BlockFace direction,
                                       boolean sealEntrance,
                                       Predicate<BlockPos> fluidLookup) {
        return planFluids(
                volume,
                direction,
                sealEntrance,
                PERMANENT_SEAL_MATERIAL,
                TEMPORARY_SEAL_MATERIAL,
                fluidLookup,
                fluidLookup
        );
    }

    /**
     * Je distingue le fluide à drainer d'une ouverture extérieure qui devra
     * elle aussi être vitrée avant qu'un fluide plus lointain ne l'atteigne.
     */
    public static FluidPlan planFluids(
            Volume volume,
            BlockFace direction,
            boolean sealEntrance,
            Predicate<BlockPos> fluidLookup,
            Predicate<BlockPos> boundaryThreatLookup) {
        return planFluids(
                volume,
                direction,
                sealEntrance,
                PERMANENT_SEAL_MATERIAL,
                TEMPORARY_SEAL_MATERIAL,
                fluidLookup,
                boundaryThreatLookup
        );
    }

    /**
     * Je permets à l'intégration de choisir les deux matériaux sans introduire
     * de dépendance à une configuration ou à un monde dans ce planificateur.
     */
    public static FluidPlan planFluids(Volume volume,
                                       BlockFace direction,
                                       boolean sealEntrance,
                                       Material permanentSealMaterial,
                                       Material temporarySealMaterial,
                                       Predicate<BlockPos> fluidLookup) {
        return planFluids(
                volume,
                direction,
                sealEntrance,
                permanentSealMaterial,
                temporarySealMaterial,
                fluidLookup,
                fluidLookup
        );
    }

    /**
     * Je conserve le planificateur synchrone comme une façade compatible qui
     * termine le même scanner incrémental en une seule passe bornée.
     */
    public static FluidPlan planFluids(
            Volume volume,
            BlockFace direction,
            boolean sealEntrance,
            Material permanentSealMaterial,
            Material temporarySealMaterial,
            Predicate<BlockPos> fluidLookup,
            Predicate<BlockPos> boundaryThreatLookup) {
        Objects.requireNonNull(fluidLookup, "fluidLookup");
        Objects.requireNonNull(boundaryThreatLookup, "boundaryThreatLookup");
        FluidScanner scanner = createFluidScanner(
                volume,
                direction,
                sealEntrance,
                permanentSealMaterial,
                temporarySealMaterial
        );
        scanner.scan(
                MAX_FLUID_INSPECTIONS,
                fluidLookup,
                boundaryThreatLookup
        );
        return scanner.result();
    }

    public static FluidScanner createFluidScanner(Volume volume,
                                                  BlockFace direction,
                                                  boolean sealEntrance) {
        return createFluidScanner(
                volume,
                direction,
                sealEntrance,
                PERMANENT_SEAL_MATERIAL,
                TEMPORARY_SEAL_MATERIAL
        );
    }

    /**
     * Je prépare uniquement l'état géométrique ; aucune lecture n'est lancée
     * avant le premier appel explicite à {@link FluidScanner#scan}.
     */
    public static FluidScanner createFluidScanner(
            Volume volume,
            BlockFace direction,
            boolean sealEntrance,
            Material permanentSealMaterial,
            Material temporarySealMaterial) {
        return new FluidScanner(
                volume,
                direction,
                sealEntrance,
                permanentSealMaterial,
                temporarySealMaterial
        );
    }

    /**
     * Je produis des cadres directionnels et, si demandé, deux plateformes
     * latérales équipées sans occuper les deux blocs bas de l'allée centrale.
     */
    public static Plan planDecorations(Volume volume,
                                       BlockFace direction,
                                       boolean arrivalRoom,
                                       Settings settings,
                                       Collection<Placement> permanentSeals) {
        Objects.requireNonNull(volume, "volume");
        requireCardinal(direction);
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(permanentSeals, "permanentSeals");

        OrientedVolume oriented = new OrientedVolume(volume, direction);
        validateDecorationGeometry(oriented, arrivalRoom, settings);
        ensureDecorationBudget(oriented, arrivalRoom, settings);

        Map<BlockPos, Placement> placements = new LinkedHashMap<>();
        Set<BlockPos> reservedPositions = new LinkedHashSet<>();
        for (Placement seal : permanentSeals) {
            if (seal == null
                    || seal.kind() != PlacementKind.PERMANENT_SEAL
                    || !volume.contains(seal.position())) {
                throw new IllegalArgumentException(
                        "Seuls les scellements permanents du volume sont acceptés."
                );
            }
            if (!reservedPositions.add(seal.position())) {
                throw new IllegalArgumentException(
                        "Deux scellements permanents réservent la même position."
                );
            }
        }

        int aisleStart = centeredStart(
                oriented.width(),
                settings.centralAisleWidth()
        );
        int aisleEnd = aisleStart + settings.centralAisleWidth() - 1;

        int frameIndex = 0;
        for (long longitudinal = settings.firstSupportOffset();
                longitudinal < oriented.length();
                longitudinal += settings.supportSpacing()) {
            boolean illuminated = frameIndex % settings.lightEveryFrames() == 0;
            addFrame(
                    placements,
                    reservedPositions,
                    oriented,
                    longitudinal,
                    illuminated,
                    settings.maximumOperations()
            );
            frameIndex++;
        }

        if (arrivalRoom) {
            addArrivalRoom(
                    placements,
                    reservedPositions,
                    oriented,
                    settings,
                    aisleStart,
                    aisleEnd
            );
        }

        assertCentralPassageClear(
                placements.values(),
                oriented,
                aisleStart,
                aisleEnd
        );
        return new Plan(
                volume,
                direction,
                arrivalRoom,
                List.copyOf(placements.values())
        );
    }

    private static void addFrame(Map<BlockPos, Placement> placements,
                                 Set<BlockPos> reservedPositions,
                                 OrientedVolume oriented,
                                 long longitudinal,
                                 boolean illuminated,
                                 int budget) {
        Volume volume = oriented.volume();
        for (long y = volume.minY(); y < volume.maxY(); y++) {
            putDecoration(
                    placements,
                    reservedPositions,
                    new Placement(
                            oriented.position(0L, y, longitudinal),
                            SUPPORT_POST_MATERIAL,
                            PlacementKind.SUPPORT_POST
                    ),
                    budget
            );
            putDecoration(
                    placements,
                    reservedPositions,
                    new Placement(
                            oriented.position(
                                    oriented.width() - 1L,
                                    y,
                                    longitudinal
                            ),
                            SUPPORT_POST_MATERIAL,
                            PlacementKind.SUPPORT_POST
                    ),
                    budget
            );
        }

        long lightTransverse = (oriented.width() - 1L) / 2L;
        for (long transverse = 0;
                transverse < oriented.width();
                transverse++) {
            boolean light = illuminated && transverse == lightTransverse;
            putDecoration(
                    placements,
                    reservedPositions,
                    new Placement(
                            oriented.position(
                                    transverse,
                                    volume.maxY(),
                                    longitudinal
                            ),
                            light ? LIGHT_MATERIAL : SUPPORT_BEAM_MATERIAL,
                            light
                                    ? PlacementKind.LIGHT
                                    : PlacementKind.SUPPORT_BEAM
                    ),
                    budget
            );
        }
    }

    private static void addArrivalRoom(Map<BlockPos, Placement> placements,
                                       Set<BlockPos> reservedPositions,
                                       OrientedVolume oriented,
                                       Settings settings,
                                       int aisleStart,
                                       int aisleEnd) {
        long depth = Math.min(
                oriented.length(),
                settings.arrivalPlatformDepth()
        );
        for (long longitudinal = 0; longitudinal < depth; longitudinal++) {
            for (long transverse = 0;
                    transverse < oriented.width();
                    transverse++) {
                if (transverse >= aisleStart && transverse <= aisleEnd) {
                    continue;
                }
                putDecoration(
                        placements,
                        reservedPositions,
                        new Placement(
                                oriented.position(
                                        transverse,
                                        oriented.volume().minY(),
                                        longitudinal
                                ),
                                PLATFORM_MATERIAL,
                                PlacementKind.PLATFORM
                        ),
                        settings.maximumOperations()
                );
            }
        }

        long leftStation = aisleStart - 1L;
        long rightStation = aisleEnd + 1L;
        long furnishingY = checkedAdd(
                oriented.volume().minY(),
                1L,
                "Y du mobilier"
        );
        long chestDepth = 1L;
        long workstationDepth = 2L;

        putDecoration(
                placements,
                reservedPositions,
                new Placement(
                        oriented.position(leftStation, furnishingY, chestDepth),
                        Material.CHEST,
                        PlacementKind.CHEST
                ),
                settings.maximumOperations()
        );
        putDecoration(
                placements,
                reservedPositions,
                new Placement(
                        oriented.position(rightStation, furnishingY, chestDepth),
                        Material.CHEST,
                        PlacementKind.CHEST
                ),
                settings.maximumOperations()
        );
        putDecoration(
                placements,
                reservedPositions,
                new Placement(
                        oriented.position(
                                leftStation,
                                furnishingY,
                                workstationDepth
                        ),
                        Material.CRAFTING_TABLE,
                        PlacementKind.WORKSTATION
                ),
                settings.maximumOperations()
        );
        putDecoration(
                placements,
                reservedPositions,
                new Placement(
                        oriented.position(
                                rightStation,
                                furnishingY,
                                workstationDepth
                        ),
                        Material.BLAST_FURNACE,
                        PlacementKind.WORKSTATION
                ),
                settings.maximumOperations()
        );
    }

    private static void assertCentralPassageClear(
            Collection<Placement> placements,
            OrientedVolume oriented,
            int aisleStart,
            int aisleEnd) {
        int passageMaxY = Math.min(
                oriented.volume().maxY(),
                Math.addExact(oriented.volume().minY(), 1)
        );
        for (Placement placement : placements) {
            BlockPos position = placement.position();
            if (placement.kind() == PlacementKind.PERMANENT_SEAL
                    || !oriented.volume().contains(position)
                    || position.y() > passageMaxY) {
                continue;
            }
            long transverse = oriented.transverseIndex(position);
            if (transverse >= aisleStart && transverse <= aisleEnd) {
                throw new IllegalArgumentException(
                        "Un placement bloquerait l'allée centrale en "
                                + position + "."
                );
            }
        }
    }

    private static void validateDecorationGeometry(OrientedVolume oriented,
                                                   boolean arrivalRoom,
                                                   Settings settings) {
        if (oriented.height() < 3L) {
            throw new IllegalArgumentException(
                    "Le tunnel doit offrir au moins trois blocs de hauteur."
            );
        }
        long minimumWidth = settings.centralAisleWidth()
                + (arrivalRoom ? 4L : 2L);
        if (oriented.width() < minimumWidth) {
            throw new IllegalArgumentException(
                    "Le tunnel est trop étroit pour conserver l'allée centrale."
            );
        }
        if (arrivalRoom
                && oriented.length() < settings.arrivalPlatformDepth()) {
            throw new IllegalArgumentException(
                    "Le tunnel est trop court pour la salle d'arrivée."
            );
        }
        if (arrivalRoom
                && settings.firstSupportOffset() >= oriented.length()) {
            throw new IllegalArgumentException(
                    "La salle d'arrivée doit contenir au moins un cadre lumineux."
            );
        }
    }

    private static FluidInspectionLayout fluidInspectionLayout(
            OrientedVolume oriented,
            boolean sealEntrance) {
        FluidInspectionLayout layout = calculateFluidInspectionLayout(
                oriented,
                sealEntrance
        );
        if (layout.totalInspections() > MAX_FLUID_INSPECTIONS) {
            throw new IllegalArgumentException(
                    "Le plan de fluides dépasserait le budget de "
                            + MAX_FLUID_INSPECTIONS + " inspections."
            );
        }
        return layout;
    }

    private static FluidInspectionLayout calculateFluidInspectionLayout(
            OrientedVolume oriented,
            boolean sealEntrance) {
        long interior = oriented.volume().blockCount();
        long sideWalls = multiplyExact(
                2L,
                oriented.length(),
                oriented.height()
        );
        long ceiling = multiplyExact(oriented.width(), oriented.length());
        long face = multiplyExact(oriented.width(), oriented.height());
        long sideWallsEnd = addExact(interior, sideWalls);
        long ceilingEnd = addExact(sideWallsEnd, ceiling);
        long entranceEnd = sealEntrance
                ? addExact(ceilingEnd, face)
                : ceilingEnd;
        long total = addExact(entranceEnd, face);
        return new FluidInspectionLayout(
                interior,
                sideWallsEnd,
                ceilingEnd,
                entranceEnd,
                total
        );
    }

    private static void ensureDecorationBudget(OrientedVolume oriented,
                                               boolean arrivalRoom,
                                               Settings settings) {
        long frameCount = frameCount(oriented, settings);
        long perFrame = addExact(
                multiplyExact(2L, oriented.height() - 1L),
                oriented.width()
        );
        long estimated = multiplyExact(frameCount, perFrame);
        if (arrivalRoom) {
            long platform = multiplyExact(
                    settings.arrivalPlatformDepth(),
                    oriented.width() - settings.centralAisleWidth()
            );
            estimated = addExact(estimated, platform, 4L);
        }
        if (estimated > settings.maximumOperations()) {
            throw new IllegalArgumentException(
                    "Le plan de décoration demanderait " + estimated
                            + " opérations pour un budget de "
                            + settings.maximumOperations() + "."
            );
        }
    }

    private static long frameCount(OrientedVolume oriented,
                                   Settings settings) {
        long firstSupport = settings.firstSupportOffset();
        if (firstSupport >= oriented.length()) {
            return 0L;
        }
        return ((oriented.length() - 1L - firstSupport)
                / settings.supportSpacing()) + 1L;
    }

    private static void addSealIfMatched(Map<BlockPos, Placement> destination,
                                         BlockPos inspectedPosition,
                                         BlockPos sealedPosition,
                                         Material material,
                                         PlacementKind kind,
                                         Predicate<BlockPos> lookup,
                                         Set<BlockPos> protectedPositions) {
        if (!lookup.test(inspectedPosition)
                || protectedPositions.contains(sealedPosition)) {
            return;
        }
        Placement placement = new Placement(sealedPosition, material, kind);
        Placement previous = destination.putIfAbsent(sealedPosition, placement);
        if (previous != null && !previous.equals(placement)) {
            throw new IllegalArgumentException(
                    "Deux scellements incompatibles ciblent "
                            + sealedPosition + "."
            );
        }
    }

    /**
     * Je mémorise les fins exclusives de chaque phase pour reprendre un scan à
     * l'inspection exacte suivante sans reparcourir les phases déjà terminées.
     */
    private record FluidInspectionLayout(long interiorEnd,
                                         long sideWallsEnd,
                                         long ceilingEnd,
                                         long entranceEnd,
                                         long totalInspections) {
    }

    private static void requireSolidBlock(Material material, String label) {
        Objects.requireNonNull(material, label);
        if (!material.isBlock() || material.isAir()) {
            throw new IllegalArgumentException(
                    "Le matériau du " + label + " doit être un bloc non vide."
            );
        }
    }

    private static void putDecoration(Map<BlockPos, Placement> placements,
                                      Set<BlockPos> reservedPositions,
                                      Placement placement,
                                      int budget) {
        if (reservedPositions.contains(placement.position())) {
            // Je ne redemande jamais la pose d'un scellement déjà géré.
            return;
        }
        Placement previous = placements.get(placement.position());
        if (previous != null) {
            // Je laisse toujours le premier élément de décor planifié prioritaire.
            return;
        }
        placements.put(placement.position(), placement);
        if (placements.size() > budget) {
            throw new IllegalArgumentException(
                    "Le budget de placements est dépassé."
            );
        }
    }

    private static int centeredStart(long width, int aisleWidth) {
        long start = (width - aisleWidth) / 2L;
        return Math.toIntExact(start);
    }

    private static void requireCardinal(BlockFace direction) {
        if (direction != BlockFace.NORTH
                && direction != BlockFace.SOUTH
                && direction != BlockFace.EAST
                && direction != BlockFace.WEST) {
            throw new IllegalArgumentException(
                    "La direction du tunnel doit être cardinale."
            );
        }
    }

    private static void requireUniquePositions(List<Placement> first,
                                               List<Placement> second) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        for (Placement placement : first) {
            if (placement == null || !positions.add(placement.position())) {
                throw new IllegalArgumentException(
                        "Le plan contient un placement nul ou dupliqué."
                );
            }
        }
        for (Placement placement : second) {
            if (placement == null || !positions.add(placement.position())) {
                throw new IllegalArgumentException(
                        "Le plan contient un placement nul ou dupliqué."
                );
            }
        }
    }

    private static void requirePositionsInside(Volume volume,
                                               Collection<BlockPos> positions) {
        for (BlockPos position : positions) {
            if (!volume.contains(position)) {
                throw new IllegalArgumentException(
                        "Une position drainée est hors du volume."
                );
            }
        }
    }

    private static void requirePlacementsInside(
            Volume volume,
            Collection<Placement> placements,
            PlacementKind expectedKind) {
        for (Placement placement : placements) {
            if (placement == null
                    || !volume.contains(placement.position())
                    || (expectedKind != null
                    && placement.kind() != expectedKind)) {
                throw new IllegalArgumentException(
                        "Un placement est invalide ou hors du volume."
                );
            }
        }
    }

    private static <T> Set<T> immutableSet(Collection<T> source) {
        Objects.requireNonNull(source, "source");
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    private static <T> List<T> immutableList(Collection<T> source) {
        Objects.requireNonNull(source, "source");
        return List.copyOf(source);
    }

    private static long inclusiveSpan(int minimum, int maximum) {
        return (long) maximum - minimum + 1L;
    }

    private static long checkedAdd(int base, long offset, String label) {
        long value = (long) base + offset;
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(label + " hors limites entières.");
        }
        return value;
    }

    private static int checkedCoordinate(long value, String label) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(label + " hors limites entières.");
        }
        return (int) value;
    }

    private static long multiplyExact(long... factors) {
        long result = 1L;
        try {
            for (long factor : factors) {
                result = Math.multiplyExact(result, factor);
            }
            return result;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Le volume dépasse les limites numériques.",
                    exception
            );
        }
    }

    private static long addExact(long... values) {
        long result = 0L;
        try {
            for (long value : values) {
                result = Math.addExact(result, value);
            }
            return result;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Le budget dépasse les limites numériques.",
                    exception
            );
        }
    }

    /**
     * Je transforme les coordonnées locales en coordonnées monde pour les
     * quatre rotations sans dupliquer la géométrie métier.
     */
    private record OrientedVolume(Volume volume, BlockFace direction) {

        private OrientedVolume {
            Objects.requireNonNull(volume, "volume");
            requireCardinal(direction);
            long orientedWidth = direction == BlockFace.NORTH
                    || direction == BlockFace.SOUTH
                    ? volume.sizeX()
                    : volume.sizeZ();
            long orientedLength = direction == BlockFace.NORTH
                    || direction == BlockFace.SOUTH
                    ? volume.sizeZ()
                    : volume.sizeX();
            if (orientedWidth > Integer.MAX_VALUE
                    || orientedLength > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Le volume orienté est trop vaste."
                );
            }
        }

        long width() {
            return direction == BlockFace.NORTH
                    || direction == BlockFace.SOUTH
                    ? volume.sizeX()
                    : volume.sizeZ();
        }

        long length() {
            return direction == BlockFace.NORTH
                    || direction == BlockFace.SOUTH
                    ? volume.sizeZ()
                    : volume.sizeX();
        }

        long height() {
            return volume.sizeY();
        }

        BlockPos position(long transverse,
                          long y,
                          long longitudinal) {
            int resolvedY = checkedCoordinate(y, "Y orienté");
            return switch (direction) {
                case SOUTH -> new BlockPos(
                        checkedCoordinate(
                                checkedAdd(
                                        volume.minX(),
                                        transverse,
                                        "X orienté"
                                ),
                                "X orienté"
                        ),
                        resolvedY,
                        checkedCoordinate(
                                checkedAdd(
                                        volume.minZ(),
                                        longitudinal,
                                        "Z orienté"
                                ),
                                "Z orienté"
                        )
                );
                case NORTH -> new BlockPos(
                        checkedCoordinate(
                                checkedAdd(
                                        volume.minX(),
                                        transverse,
                                        "X orienté"
                                ),
                                "X orienté"
                        ),
                        resolvedY,
                        checkedCoordinate(
                                checkedAdd(
                                        volume.maxZ(),
                                        -longitudinal,
                                        "Z orienté"
                                ),
                                "Z orienté"
                        )
                );
                case EAST -> new BlockPos(
                        checkedCoordinate(
                                checkedAdd(
                                        volume.minX(),
                                        longitudinal,
                                        "X orienté"
                                ),
                                "X orienté"
                        ),
                        resolvedY,
                        checkedCoordinate(
                                checkedAdd(
                                        volume.minZ(),
                                        transverse,
                                        "Z orienté"
                                ),
                                "Z orienté"
                        )
                );
                case WEST -> new BlockPos(
                        checkedCoordinate(
                                checkedAdd(
                                        volume.maxX(),
                                        -longitudinal,
                                        "X orienté"
                                ),
                                "X orienté"
                        ),
                        resolvedY,
                        checkedCoordinate(
                                checkedAdd(
                                        volume.minZ(),
                                        transverse,
                                        "Z orienté"
                                ),
                                "Z orienté"
                        )
                );
                default -> throw new IllegalStateException(
                        "Direction non cardinale après validation."
                );
            };
        }

        long transverseIndex(BlockPos position) {
            return direction == BlockFace.NORTH
                    || direction == BlockFace.SOUTH
                    ? (long) position.x() - volume.minX()
                    : (long) position.z() - volume.minZ();
        }
    }
}
