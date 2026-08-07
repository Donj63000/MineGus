package org.example.mineur.builders;

import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.example.mineur.AutomatedMiningContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Conçoit puis construit le chevalement et la cabane de stockage d'une mine.
 *
 * <p>La géométrie est calculée sans toucher au monde. La construction réelle
 * est ensuite validée et appliquée comme une transaction : si un emplacement
 * est occupé, si un plugin de protection refuse un bloc ou si une exception
 * survient, tous les blocs déjà modifiés sont restaurés.</p>
 */
public final class MineCabinBuilder {

    public static final int STRUCTURE_VERSION = 1;

    private static final int PRIORITY_FLOOR = 10;
    private static final int PRIORITY_ACCENT = 20;
    private static final int PRIORITY_WALL = 30;
    private static final int PRIORITY_FRAME = 40;
    private static final int PRIORITY_DECORATION = 50;
    private static final int PRIORITY_ROOF = 60;
    private static final int PRIORITY_ATTACHMENT = 70;
    private static final int PRIORITY_CONTAINER = 80;

    /*
     * Seuls les blocs décoratifs sans valeur de stockage sont remplaçables.
     * Les liquides, feuilles, bûches, cultures et blocs techniques sont
     * volontairement refusés pour ne jamais détruire une construction ou une
     * ressource du joueur à son insu.
     */
    private static final Set<String> REPLACEABLE_MATERIALS = Set.of(
            "SHORT_GRASS",
            "TALL_GRASS",
            "FERN",
            "LARGE_FERN",
            "DEAD_BUSH",
            "DANDELION",
            "POPPY",
            "BLUE_ORCHID",
            "ALLIUM",
            "AZURE_BLUET",
            "RED_TULIP",
            "ORANGE_TULIP",
            "WHITE_TULIP",
            "PINK_TULIP",
            "OXEYE_DAISY",
            "CORNFLOWER",
            "LILY_OF_THE_VALLEY",
            "WITHER_ROSE",
            "TORCHFLOWER",
            "PITCHER_PLANT",
            "PINK_PETALS",
            "BROWN_MUSHROOM",
            "RED_MUSHROOM",
            "SNOW",
            "VINE",
            "GLOW_LICHEN",
            "HANGING_ROOTS",
            "CRIMSON_ROOTS",
            "WARPED_ROOTS",
            "NETHER_SPROUTS"
    );

    private static final Set<BlockFace> HORIZONTAL_FACES = EnumSet.of(
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST
    );

    private final JavaPlugin plugin;
    private final Logger logger;

    public MineCabinBuilder(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
    }

    /**
     * Paramètres bornés de la structure. Les tailles de cabane sont rendues
     * impaires afin de conserver un axe central net pour les portes, les ponts
     * et le faîtage.
     */
    public record Settings(int platformHeight,
                           int minimumCabinSize,
                           int maximumCabinSize,
                           int wallHeight,
                           int doubleChestPairs,
                           int maximumFoundationDepth,
                           int maximumPlannedBlocks) {

        public Settings {
            platformHeight = clamp(platformHeight, 4, 6);
            minimumCabinSize = normalizeOdd(clamp(minimumCabinSize, 11, 25));
            maximumCabinSize = normalizeOdd(clamp(maximumCabinSize, minimumCabinSize, 31));
            wallHeight = clamp(wallHeight, 4, 7);
            doubleChestPairs = clamp(doubleChestPairs, 4, 64);
            maximumFoundationDepth = clamp(maximumFoundationDepth, 1, 16);
            maximumPlannedBlocks = clamp(maximumPlannedBlocks, 1_000, 50_000);
        }
    }

    /**
     * Coordonnée entière indépendante de Bukkit, pratique pour tester le plan
     * sans monde chargé.
     */
    public record BlockPos(int x, int y, int z) {

        public BlockPos offset(int dx, int dy, int dz) {
            return new BlockPos(
                    checkedCoordinate((long) x + dx, "X"),
                    checkedCoordinate((long) y + dy, "Y"),
                    checkedCoordinate((long) z + dz, "Z")
            );
        }
    }

    /**
     * Emprise persistable de la structure, échelles et débords de toit inclus.
     */
    public record Bounds(int minX, int maxX,
                         int minY, int maxY,
                         int minZ, int maxZ) {

        public Bounds {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("Emprise de cabane invalide.");
            }
        }

        public long horizontalArea() {
            return ((long) maxX - minX + 1L) * ((long) maxZ - minZ + 1L);
        }

        public boolean contains(BlockPos position) {
            return position != null
                    && position.x >= minX && position.x <= maxX
                    && position.y >= minY && position.y <= maxY
                    && position.z >= minZ && position.z <= maxZ;
        }
    }

    /**
     * Deux moitiés explicitement orientées d'un coffre double.
     */
    public record ChestPair(BlockPos first, BlockPos second, BlockFace facing) {

        public ChestPair {
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(second, "second");
            Objects.requireNonNull(facing, "facing");
            if (!HORIZONTAL_FACES.contains(facing)) {
                throw new IllegalArgumentException("Orientation de coffre non horizontale.");
            }
            int distance = Math.abs(first.x - second.x)
                    + Math.abs(first.y - second.y)
                    + Math.abs(first.z - second.z);
            if (distance != 1 || first.y != second.y) {
                throw new IllegalArgumentException("Les deux moitiés du coffre doivent être adjacentes.");
            }
        }
    }

    /**
     * Plan immuable. Les détails de configuration des BlockData restent privés,
     * mais les matériaux et positions utiles sont exposés pour l'audit et les
     * tests de géométrie.
     */
    public static final class Plan {

        private final int baseY;
        private final int deckY;
        private final int cabinMinX;
        private final int cabinMaxX;
        private final int cabinMinZ;
        private final int cabinMaxZ;
        private final int frameMinX;
        private final int frameMaxX;
        private final int frameMinZ;
        private final int frameMaxZ;
        private final Settings settings;
        private final Bounds bounds;
        private final Map<BlockPos, Placement> placements;
        private final Set<BlockPos> clearances;
        private final List<ChestPair> chestPairs;
        private final List<BlockPos> foundationPosts;

        private Plan(int baseY,
                     int deckY,
                     int cabinMinX,
                     int cabinMaxX,
                     int cabinMinZ,
                     int cabinMaxZ,
                     int frameMinX,
                     int frameMaxX,
                     int frameMinZ,
                     int frameMaxZ,
                     Settings settings,
                     Bounds bounds,
                     Map<BlockPos, Placement> placements,
                     Set<BlockPos> clearances,
                     List<ChestPair> chestPairs,
                     List<BlockPos> foundationPosts) {
            this.baseY = baseY;
            this.deckY = deckY;
            this.cabinMinX = cabinMinX;
            this.cabinMaxX = cabinMaxX;
            this.cabinMinZ = cabinMinZ;
            this.cabinMaxZ = cabinMaxZ;
            this.frameMinX = frameMinX;
            this.frameMaxX = frameMaxX;
            this.frameMinZ = frameMinZ;
            this.frameMaxZ = frameMaxZ;
            this.settings = settings;
            this.bounds = bounds;
            this.placements = Map.copyOf(placements);
            this.clearances = Set.copyOf(clearances);
            this.chestPairs = List.copyOf(chestPairs);
            this.foundationPosts = List.copyOf(foundationPosts);
        }

        public int baseY() {
            return baseY;
        }

        public int deckY() {
            return deckY;
        }

        public int cabinMinX() {
            return cabinMinX;
        }

        public int cabinMaxX() {
            return cabinMaxX;
        }

        public int cabinMinZ() {
            return cabinMinZ;
        }

        public int cabinMaxZ() {
            return cabinMaxZ;
        }

        public int frameMinX() {
            return frameMinX;
        }

        public int frameMaxX() {
            return frameMaxX;
        }

        public int frameMinZ() {
            return frameMinZ;
        }

        public int frameMaxZ() {
            return frameMaxZ;
        }

        public Settings settings() {
            return settings;
        }

        public Bounds bounds() {
            return bounds;
        }

        public List<ChestPair> chestPairs() {
            return chestPairs;
        }

        public int chestBlockCount() {
            return chestPairs.size() * 2;
        }

        public int placedBlockCount() {
            return placements.size();
        }

        public int touchedBlockCount() {
            return placements.size() + clearances.size()
                    + foundationPosts.size() * settings.maximumFoundationDepth();
        }

        public Material materialAt(BlockPos position) {
            Placement placement = placements.get(position);
            return placement != null ? placement.material : null;
        }

        /**
         * Retourne l'orientation attendue d'un bloc directionnel du plan.
         * Une valeur nulle signifie que le bloc n'est pas directionnel.
         */
        public BlockFace facingAt(BlockPos position) {
            Placement placement = placements.get(position);
            return placement != null ? placement.facing : null;
        }

        public Set<BlockPos> placedPositions() {
            return placements.keySet();
        }

        public List<BlockPos> chestPositions() {
            List<BlockPos> result = new ArrayList<>(chestBlockCount());
            for (ChestPair pair : chestPairs) {
                result.add(pair.first);
                result.add(pair.second);
            }
            return List.copyOf(result);
        }
    }

    /**
     * Résultat d'une validation pouvant être présenté directement au joueur.
     */
    public record ValidationResult(boolean valid, String message) {

        public static ValidationResult success() {
            return new ValidationResult(true, "");
        }

        public static ValidationResult failure(String message) {
            return new ValidationResult(false, Objects.requireNonNullElse(message, "Validation refusée."));
        }
    }

    /**
     * Transaction conservée jusqu'à l'activation complète du mineur. Le
     * {@link #commit()} rend la construction définitive ; {@link #rollback()}
     * restaure les snapshots en ordre inverse.
     */
    public static final class BuildResult {

        private final Logger logger;
        private final List<BlockState> snapshots;
        private final List<Location> chestLocations;
        private boolean closed;

        private BuildResult(Logger logger,
                            List<BlockState> snapshots,
                            List<Location> chestLocations) {
            this.logger = logger;
            this.snapshots = new ArrayList<>(snapshots);
            this.chestLocations = List.copyOf(chestLocations);
        }

        public List<Location> chestLocations() {
            return chestLocations;
        }

        public boolean isClosed() {
            return closed;
        }

        public void commit() {
            if (closed) {
                return;
            }
            closed = true;
            snapshots.clear();
        }

        public void rollback() {
            if (closed) {
                return;
            }
            closed = true;
            for (int index = snapshots.size() - 1; index >= 0; index--) {
                BlockState snapshot = snapshots.get(index);
                try {
                    if (!snapshot.update(true, false)) {
                        snapshot.getBlock().setType(snapshot.getType(), false);
                        snapshot.getBlock().setBlockData(snapshot.getBlockData(), false);
                    }
                } catch (RuntimeException exception) {
                    logger.log(
                            Level.SEVERE,
                            "[Mineur] Impossible de restaurer le bloc de cabane en "
                                    + format(snapshot.getBlock()) + ".",
                            exception
                    );
                }
            }
            snapshots.clear();
        }
    }

    /**
     * Produit une cabane centrée au-dessus de la sélection. Le grand cadre suit
     * toujours les dimensions de la mine ; la pièce fermée reste carrée et
     * plafonnée afin de préserver une toiture harmonieuse ainsi qu'un coût de
     * construction borné sur les carrières de grande taille.
     */
    public static Plan createPlan(int mineMinX,
                                  int baseY,
                                  int mineMinZ,
                                  int width,
                                  int length,
                                  Settings settings) {
        Objects.requireNonNull(settings, "settings");
        if (width < 1 || length < 1) {
            throw new IllegalArgumentException("Dimensions de mine invalides.");
        }

        int mineMaxX = checkedCoordinate((long) mineMinX + width - 1L, "X maximal de la mine");
        int mineMaxZ = checkedCoordinate((long) mineMinZ + length - 1L, "Z maximal de la mine");
        int deckY = checkedCoordinate((long) baseY + settings.platformHeight(), "hauteur de plateforme");

        int scaledCabinWidth = scaledCabinSize(
                width,
                settings.minimumCabinSize(),
                settings.maximumCabinSize()
        );
        int scaledCabinLength = scaledCabinSize(
                length,
                settings.minimumCabinSize(),
                settings.maximumCabinSize()
        );

        /*
         * La pièce fermée reste carrée : un rectangle 17x15 perd deux
         * emplacements de coffres autour de l'axe des portes. Dimensionner le
         * pavillon sur le plus grand côté garantit une circulation symétrique,
         * une toiture équilibrée et la capacité de stockage annoncée, même
         * pour une sélection très longue et étroite.
         */
        int cabinSide = Math.max(scaledCabinWidth, scaledCabinLength);
        int cabinWidth = cabinSide;
        int cabinLength = cabinSide;

        /*
         * Les méthodes d'assemblage travaillent ensuite avec des entiers et
         * plusieurs bornes inclusives. Valider dès maintenant les extrêmes
         * verticaux interdit tout rebouclage en cas de coordonnées forgées,
         * avant même de commencer à remplir le plan.
         */
        int roofLevels = (cabinSide + 1) / 2;
        checkedCoordinate(
                (long) deckY + settings.wallHeight() + 1L + roofLevels,
                "hauteur maximale de la toiture"
        );
        checkedCoordinate(
                (long) baseY - settings.maximumFoundationDepth(),
                "profondeur minimale des fondations"
        );

        int cabinMinX = centeredMinimum(mineMinX, mineMaxX, cabinWidth, "X minimal de la cabane");
        int cabinMaxX = checkedCoordinate((long) cabinMinX + cabinWidth - 1L, "X maximal de la cabane");
        int cabinMinZ = centeredMinimum(mineMinZ, mineMaxZ, cabinLength, "Z minimal de la cabane");
        int cabinMaxZ = checkedCoordinate((long) cabinMinZ + cabinLength - 1L, "Z maximal de la cabane");

        int frameMinX = checkedCoordinate(Math.min((long) mineMinX - 2L, (long) cabinMinX - 2L), "X minimal du cadre");
        int frameMaxX = checkedCoordinate(Math.max((long) mineMaxX + 2L, (long) cabinMaxX + 2L), "X maximal du cadre");
        int frameMinZ = checkedCoordinate(Math.min((long) mineMinZ - 2L, (long) cabinMinZ - 2L), "Z minimal du cadre");
        int frameMaxZ = checkedCoordinate(Math.max((long) mineMaxZ + 2L, (long) cabinMaxZ + 2L), "Z maximal du cadre");

        /*
         * Plusieurs boucles utilisent une borne inclusive. Refuser la valeur
         * Integer.MAX_VALUE empêche le dernier incrément de reboucler vers
         * Integer.MIN_VALUE sur une sauvegarde ou une sélection forgée.
         */
        if (frameMaxX == Integer.MAX_VALUE || frameMaxZ == Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "La structure est trop proche de la limite entière du monde."
            );
        }

        PlanAssembler assembler = new PlanAssembler(
                mineMinX,
                mineMaxX,
                mineMinZ,
                mineMaxZ,
                baseY,
                deckY,
                cabinMinX,
                cabinMaxX,
                cabinMinZ,
                cabinMaxZ,
                frameMinX,
                frameMaxX,
                frameMinZ,
                frameMaxZ,
                settings
        );
        return assembler.assemble();
    }

    /**
     * Contrôle les bornes qui ne nécessitent aucune lecture de bloc. Cette
     * méthode peut donc être appelée avant de charger les chunks de la session.
     */
    public ValidationResult validateEnvelope(World world,
                                             Plan plan,
                                             int maximumChunks) {
        if (world == null || plan == null) {
            return ValidationResult.failure("Monde ou plan de cabane absent.");
        }

        int minimumY = world.getMinHeight();
        int maximumY = world.getMaxHeight();
        if (plan.baseY < minimumY || plan.bounds.maxY >= maximumY) {
            return ValidationResult.failure(
                    "La cabane nécessite l'espace vertical Y "
                            + plan.baseY + " à " + plan.bounds.maxY
                            + " dans ce monde (" + minimumY + " à " + (maximumY - 1) + ")."
            );
        }

        Location[] borderCorners = {
                new Location(world, plan.bounds.minX + 0.5D, plan.baseY, plan.bounds.minZ + 0.5D),
                new Location(world, plan.bounds.minX + 0.5D, plan.baseY, plan.bounds.maxZ + 0.5D),
                new Location(world, plan.bounds.maxX + 0.5D, plan.baseY, plan.bounds.minZ + 0.5D),
                new Location(world, plan.bounds.maxX + 0.5D, plan.baseY, plan.bounds.maxZ + 0.5D)
        };
        for (Location corner : borderCorners) {
            if (!world.getWorldBorder().isInside(corner)) {
                return ValidationResult.failure("Le chevalement dépasserait la bordure du monde.");
            }
        }

        long chunkWidth = ((long) (plan.bounds.maxX >> 4) - (plan.bounds.minX >> 4)) + 1L;
        long chunkLength = ((long) (plan.bounds.maxZ >> 4) - (plan.bounds.minZ >> 4)) + 1L;
        long chunkCount = chunkWidth * chunkLength;
        int safeMaximum = Math.max(1, maximumChunks);
        if (chunkCount > safeMaximum) {
            return ValidationResult.failure(
                    "La cabane demanderait " + chunkCount
                            + " chunks chargés, au-delà de la limite " + safeMaximum + "."
            );
        }

        if (plan.touchedBlockCount() > plan.settings.maximumPlannedBlocks()) {
            return ValidationResult.failure(
                    "La structure prévoit " + plan.touchedBlockCount()
                            + " opérations de blocs, au-delà de la limite "
                            + plan.settings.maximumPlannedBlocks() + "."
            );
        }
        return ValidationResult.success();
    }

    /**
     * Applique le plan après une seconde validation au plus près de l'écriture.
     */
    public BuildResult build(World world,
                             Plan plan,
                             Player actor,
                             boolean fireProtectionEvents) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(plan, "plan");
        if (fireProtectionEvents && actor == null) {
            throw new IllegalStateException(
                    "Le propriétaire doit être connecté pour vérifier les protections de construction."
            );
        }

        ResolvedBuild resolved = resolve(world, plan);
        ValidationResult occupancy = validateOccupancy(world, plan, resolved);
        if (!occupancy.valid()) {
            throw new IllegalStateException(occupancy.message());
        }

        List<BlockState> snapshots = snapshotTouchedBlocks(world, resolved);
        BuildResult result = new BuildResult(logger, snapshots, List.of());
        try {
            clearDecorations(world, plan, resolved, actor, fireProtectionEvents);
            applyPlacements(world, resolved.placements, actor, fireProtectionEvents);
            refreshConnectedBlocks(world, resolved.placements.values());
            reapplyDoubleChestData(world, plan, resolved.placements);
            validateDoubleChests(world, plan.chestPairs);

            List<Location> chestLocations = new ArrayList<>(plan.chestBlockCount());
            for (ChestPair pair : plan.chestPairs) {
                chestLocations.add(toLocation(world, pair.first));
                chestLocations.add(toLocation(world, pair.second));
            }

            /*
             * Le BuildResult final partage les mêmes snapshots. Le résultat
             * temporaire est fermé sans restauration pour transférer la
             * responsabilité du rollback à l'appelant.
             */
            result.closed = true;
            result.snapshots.clear();
            return new BuildResult(logger, snapshots, chestLocations);
        } catch (RuntimeException exception) {
            result.rollback();
            throw exception;
        }
    }

    private ResolvedBuild resolve(World world, Plan plan) {
        LinkedHashMap<BlockPos, Placement> placements = new LinkedHashMap<>(plan.placements);
        int minimumSearchY = Math.max(
                world.getMinHeight(),
                plan.baseY - plan.settings.maximumFoundationDepth()
        );

        for (BlockPos post : plan.foundationPosts) {
            int supportY = Integer.MIN_VALUE;
            for (int y = plan.baseY; y >= minimumSearchY; y--) {
                Block block = world.getBlockAt(post.x, y, post.z);
                Material material = block.getType();
                if (material.isAir() || isReplaceable(material)) {
                    continue;
                }
                if (isStableFoundation(block)) {
                    supportY = y;
                    break;
                }
                throw new IllegalStateException(
                        "Fondation instable ou protégée en " + format(block)
                                + " (" + material + ")."
                );
            }
            if (supportY == Integer.MIN_VALUE) {
                throw new IllegalStateException(
                        "Aucun sol stable sous le pilier en "
                                + post.x + ", " + plan.baseY + ", " + post.z
                                + " dans les " + plan.settings.maximumFoundationDepth()
                                + " blocs autorisés."
                );
            }

            for (int y = supportY + 1; y <= plan.baseY; y++) {
                BlockPos position = new BlockPos(post.x, y, post.z);
                placements.putIfAbsent(
                        position,
                        Placement.simple(position, Material.STONE_BRICKS, PRIORITY_FLOOR)
                );
            }
        }
        return new ResolvedBuild(placements, plan.clearances);
    }

    private ValidationResult validateOccupancy(World world,
                                               Plan plan,
                                               ResolvedBuild resolved) {
        Set<BlockPos> touched = new HashSet<>();
        touched.addAll(resolved.placements.keySet());
        touched.addAll(resolved.clearances);

        for (BlockPos position : touched) {
            if (position.y < world.getMinHeight() || position.y >= world.getMaxHeight()) {
                return ValidationResult.failure(
                        "Bloc de structure hors hauteur en "
                                + position.x + ", " + position.y + ", " + position.z + "."
                );
            }
            if (!world.isChunkLoaded(position.x >> 4, position.z >> 4)) {
                return ValidationResult.failure(
                        "Le chunk de la cabane n'est pas chargé en "
                                + position.x + ", " + position.z + "."
                );
            }

            Block block = world.getBlockAt(position.x, position.y, position.z);
            Material current = block.getType();
            if (!current.isAir() && !isReplaceable(current)) {
                return ValidationResult.failure(
                        "L'emplacement " + format(block) + " est occupé par " + current
                                + ". Libère le volume de la cabane avant de relancer /mineur."
                );
            }
        }

        Set<BlockPos> plannedChestBlocks = new HashSet<>(plan.chestPositions());
        for (BlockPos chestPosition : plannedChestBlocks) {
            for (BlockFace face : HORIZONTAL_FACES) {
                BlockPos adjacentPosition = chestPosition.offset(
                        face.getModX(),
                        0,
                        face.getModZ()
                );
                if (plannedChestBlocks.contains(adjacentPosition)) {
                    continue;
                }
                Material adjacent = world.getBlockAt(
                        adjacentPosition.x,
                        adjacentPosition.y,
                        adjacentPosition.z
                ).getType();
                if (adjacent == Material.CHEST || adjacent == Material.TRAPPED_CHEST) {
                    return ValidationResult.failure(
                            "Un coffre existant toucherait le stockage automatique en "
                                    + adjacentPosition.x + ", " + adjacentPosition.y + ", "
                                    + adjacentPosition.z + "."
                    );
                }
            }
        }
        return ValidationResult.success();
    }

    private List<BlockState> snapshotTouchedBlocks(World world, ResolvedBuild resolved) {
        Set<BlockPos> touched = new LinkedHashSet<>();
        touched.addAll(resolved.clearances);
        touched.addAll(resolved.placements.keySet());

        List<BlockPos> ordered = new ArrayList<>(touched);
        ordered.sort(BlockPositionComparator.INSTANCE);

        List<BlockState> snapshots = new ArrayList<>(ordered.size());
        for (BlockPos position : ordered) {
            snapshots.add(world.getBlockAt(position.x, position.y, position.z).getState());
        }
        return snapshots;
    }

    private void clearDecorations(World world,
                                  Plan plan,
                                  ResolvedBuild resolved,
                                  Player actor,
                                  boolean fireProtectionEvents) {
        List<BlockPos> ordered = new ArrayList<>(resolved.clearances);
        ordered.removeAll(resolved.placements.keySet());
        ordered.sort(BlockPositionComparator.INSTANCE);

        for (BlockPos position : ordered) {
            Block block = world.getBlockAt(position.x, position.y, position.z);
            if (block.getType().isAir()) {
                continue;
            }
            if (!isReplaceable(block.getType())) {
                throw new IllegalStateException(
                        "Le dégagement de la cabane a été occupé en " + format(block) + "."
                );
            }
            if (fireProtectionEvents) {
                BlockBreakEvent event = new BlockBreakEvent(block, actor);
                event.setDropItems(false);
                event.setExpToDrop(0);
                AutomatedMiningContext.call(() -> {
                    plugin.getServer().getPluginManager().callEvent(event);
                    return null;
                });
                if (event.isCancelled()) {
                    throw new IllegalStateException(
                            "Un plugin de protection refuse le dégagement en " + format(block) + "."
                    );
                }
            }
            block.setType(Material.AIR, false);
        }
    }

    private void applyPlacements(World world,
                                 Map<BlockPos, Placement> placements,
                                 Player actor,
                                 boolean fireProtectionEvents) {
        List<Placement> ordered = new ArrayList<>(placements.values());
        ordered.sort(Comparator
                .comparingInt((Placement placement) -> placement.priority)
                .thenComparing(placement -> placement.position, BlockPositionComparator.INSTANCE));

        for (Placement placement : ordered) {
            Block block = world.getBlockAt(
                    placement.position.x,
                    placement.position.y,
                    placement.position.z
            );
            Material current = block.getType();
            if (!current.isAir() && !isReplaceable(current)) {
                throw new IllegalStateException(
                        "L'emplacement a été occupé pendant la construction en " + format(block) + "."
                );
            }

            BlockState replacedState = block.getState();
            block.setType(placement.material, false);
            BlockData data = block.getBlockData();
            placement.configurer.configure(data);
            if (data instanceof Waterlogged waterlogged) {
                waterlogged.setWaterlogged(false);
            }
            block.setBlockData(data, false);

            if (fireProtectionEvents) {
                Block placedAgainst = findPlacedAgainst(block);
                BlockPlaceEvent event = new BlockPlaceEvent(
                        block,
                        replacedState,
                        placedAgainst,
                        new ItemStack(placement.material, 1),
                        actor,
                        true,
                        EquipmentSlot.HAND
                );
                AutomatedMiningContext.call(() -> {
                    plugin.getServer().getPluginManager().callEvent(event);
                    return null;
                });
                if (event.isCancelled() || !event.canBuild()) {
                    throw new IllegalStateException(
                            "Un plugin de protection refuse la construction en " + format(block) + "."
                    );
                }
            }

            if (block.getType() != placement.material) {
                throw new IllegalStateException(
                        "Le bloc posé en " + format(block)
                                + " a été modifié par un autre plugin."
                );
            }
        }
    }

    private Block findPlacedAgainst(Block block) {
        Block below = block.getRelative(BlockFace.DOWN);
        if (!below.getType().isAir()) {
            return below;
        }

        /*
         * Les lanternes suspendues et les chaînes sont fixées au plafond. Le
         * bloc supérieur doit donc être annoncé au BlockPlaceEvent avant de
         * chercher un support latéral, faute de quoi certains plugins de
         * protection pourraient évaluer la pose contre un faux support vide.
         */
        Block above = block.getRelative(BlockFace.UP);
        if (!above.getType().isAir()) {
            return above;
        }

        for (BlockFace face : HORIZONTAL_FACES) {
            Block adjacent = block.getRelative(face);
            if (!adjacent.getType().isAir()) {
                return adjacent;
            }
        }
        return below;
    }

    private void refreshConnectedBlocks(World world,
                                        Collection<Placement> placements) {
        Set<BlockPos> candidates = new LinkedHashSet<>();
        for (Placement placement : placements) {
            if (placement.material.name().endsWith("_FENCE")
                    || placement.material.name().endsWith("_GLASS_PANE")
                    || placement.material == Material.IRON_BARS) {
                candidates.add(placement.position);
            }
        }

        for (BlockPos position : candidates) {
            Block block = world.getBlockAt(position.x, position.y, position.z);
            BlockData data = block.getBlockData();
            if (!(data instanceof MultipleFacing multipleFacing)) {
                continue;
            }

            for (BlockFace face : HORIZONTAL_FACES) {
                if (!multipleFacing.getAllowedFaces().contains(face)) {
                    continue;
                }
                Block adjacent = block.getRelative(face);
                multipleFacing.setFace(
                        face,
                        shouldConnect(block.getType(), adjacent.getType())
                );
            }
            block.setBlockData(multipleFacing, false);
        }
    }

    private boolean shouldConnect(Material source, Material adjacent) {
        if (adjacent.isAir()) {
            return false;
        }
        String sourceName = source.name();
        String adjacentName = adjacent.name();
        if (sourceName.endsWith("_FENCE")) {
            return adjacentName.endsWith("_FENCE")
                    || adjacentName.endsWith("_FENCE_GATE")
                    || adjacent.isOccluding();
        }
        if (sourceName.endsWith("_GLASS_PANE") || source == Material.IRON_BARS) {
            return adjacentName.endsWith("_GLASS_PANE")
                    || adjacent == Material.IRON_BARS
                    || adjacent.isOccluding();
        }
        return adjacent.isOccluding();
    }

    private void reapplyDoubleChestData(World world,
                                        Plan plan,
                                        Map<BlockPos, Placement> placements) {
        /*
         * Certains moteurs normalisent temporairement la première moitié en
         * coffre simple tant que sa partenaire n'existe pas. Une seconde passe
         * après la pose des deux blocs garantit les types LEFT/RIGHT attendus.
         */
        for (ChestPair pair : plan.chestPairs) {
            for (BlockPos position : List.of(pair.first, pair.second)) {
                Placement placement = placements.get(position);
                Block block = world.getBlockAt(position.x, position.y, position.z);
                if (placement == null || block.getType() != Material.CHEST) {
                    throw new IllegalStateException(
                            "Moitié de coffre absente en " + format(block) + "."
                    );
                }
                BlockData data = block.getBlockData();
                placement.configurer.configure(data);
                if (data instanceof Waterlogged waterlogged) {
                    waterlogged.setWaterlogged(false);
                }
                block.setBlockData(data, false);
            }
        }
    }

    private void validateDoubleChests(World world,
                                      List<ChestPair> chestPairs) {
        for (ChestPair pair : chestPairs) {
            Block firstBlock = world.getBlockAt(pair.first.x, pair.first.y, pair.first.z);
            Block secondBlock = world.getBlockAt(pair.second.x, pair.second.y, pair.second.z);
            if (!(firstBlock.getBlockData() instanceof Chest firstData)
                    || !(secondBlock.getBlockData() instanceof Chest secondData)
                    || firstData.getFacing() != pair.facing
                    || secondData.getFacing() != pair.facing
                    || firstData.getType() == Chest.Type.SINGLE
                    || secondData.getType() == Chest.Type.SINGLE
                    || firstData.getType() == secondData.getType()) {
                throw new IllegalStateException(
                        "Le coffre double n'a pas pu être assemblé en " + format(firstBlock) + "."
                );
            }

            /*
             * Paper expose normalement un DoubleChest immédiatement. Le test
             * de BlockData ci-dessus reste l'autorité, car certains serveurs de
             * test ne matérialisent le holder combiné qu'au tick suivant.
             */
            InventoryHolder holder = ((org.bukkit.block.Chest) firstBlock.getState())
                    .getInventory()
                    .getHolder();
            if (holder != null && !(holder instanceof DoubleChest)
                    && logger.isLoggable(Level.FINE)) {
                logger.fine("[Mineur] Holder de coffre encore simple en "
                        + format(firstBlock) + " ; les BlockData sont néanmoins cohérentes.");
            }
        }
    }

    private static Location toLocation(World world, BlockPos position) {
        return new Location(world, position.x, position.y, position.z);
    }

    private static boolean isReplaceable(Material material) {
        return material != null && REPLACEABLE_MATERIALS.contains(material.name());
    }

    private static boolean isStableFoundation(Block block) {
        if (block == null || block.getState() instanceof TileState) {
            return false;
        }
        Material material = block.getType();
        return material.isSolid()
                && material.isOccluding()
                && material != Material.TNT
                && material != Material.MAGMA_BLOCK;
    }

    private static int scaledCabinSize(int mineSize, int minimum, int maximum) {
        long preferred = (long) mineSize + 6L;
        int bounded = (int) Math.max(minimum, Math.min((long) maximum, preferred));
        return normalizeOdd(bounded);
    }

    private static int centeredMinimum(int mineMinimum,
                                       int mineMaximum,
                                       int size,
                                       String label) {
        long doubledCenter = (long) mineMinimum + mineMaximum;
        long minimum = Math.floorDiv(doubledCenter - (size - 1L), 2L);
        return checkedCoordinate(minimum, label);
    }

    private static int normalizeOdd(int value) {
        return (value & 1) == 0 ? value + 1 : value;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int checkedCoordinate(long value, String label) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(label + " hors limites entières.");
        }
        return (int) value;
    }

    private static String format(Block block) {
        return block.getX() + ", " + block.getY() + ", " + block.getZ();
    }

    @FunctionalInterface
    private interface BlockDataConfigurer {

        void configure(BlockData data);
    }

    private record Placement(BlockPos position,
                             Material material,
                             int priority,
                             BlockFace facing,
                             BlockDataConfigurer configurer) {

        private Placement {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(configurer, "configurer");
        }

        private static Placement simple(BlockPos position,
                                        Material material,
                                        int priority) {
            return new Placement(position, material, priority, null, ignored -> {
                // Aucun BlockData spécialisé.
            });
        }
    }

    private record ResolvedBuild(Map<BlockPos, Placement> placements,
                                 Set<BlockPos> clearances) {
    }

    private enum BlockPositionComparator implements Comparator<BlockPos> {
        INSTANCE;

        @Override
        public int compare(BlockPos first, BlockPos second) {
            int yOrder = Integer.compare(first.y, second.y);
            if (yOrder != 0) {
                return yOrder;
            }
            int xOrder = Integer.compare(first.x, second.x);
            return xOrder != 0 ? xOrder : Integer.compare(first.z, second.z);
        }
    }

    /**
     * Assembleur interne de la géométrie. Les méthodes suivent l'ordre visuel :
     * socle, chevalement, plateforme, cabane, toiture puis stockage.
     */
    private static final class PlanAssembler {

        private final int mineMinX;
        private final int mineMaxX;
        private final int mineMinZ;
        private final int mineMaxZ;
        private final int baseY;
        private final int groundY;
        private final int deckY;
        private final int cabinMinX;
        private final int cabinMaxX;
        private final int cabinMinZ;
        private final int cabinMaxZ;
        private final int frameMinX;
        private final int frameMaxX;
        private final int frameMinZ;
        private final int frameMaxZ;
        private final int centerX;
        private final int centerZ;
        private final Settings settings;

        private final LinkedHashMap<BlockPos, Placement> placements = new LinkedHashMap<>();
        private final LinkedHashSet<BlockPos> clearances = new LinkedHashSet<>();
        private final List<ChestPair> chestPairs = new ArrayList<>();
        private final List<BlockPos> foundationPosts = new ArrayList<>();

        private PlanAssembler(int mineMinX,
                              int mineMaxX,
                              int mineMinZ,
                              int mineMaxZ,
                              int baseY,
                              int deckY,
                              int cabinMinX,
                              int cabinMaxX,
                              int cabinMinZ,
                              int cabinMaxZ,
                              int frameMinX,
                              int frameMaxX,
                              int frameMinZ,
                              int frameMaxZ,
                              Settings settings) {
            this.mineMinX = mineMinX;
            this.mineMaxX = mineMaxX;
            this.mineMinZ = mineMinZ;
            this.mineMaxZ = mineMaxZ;
            this.baseY = baseY;
            this.groundY = checkedCoordinate((long) baseY + 1L, "hauteur du socle");
            this.deckY = deckY;
            this.cabinMinX = cabinMinX;
            this.cabinMaxX = cabinMaxX;
            this.cabinMinZ = cabinMinZ;
            this.cabinMaxZ = cabinMaxZ;
            this.frameMinX = frameMinX;
            this.frameMaxX = frameMaxX;
            this.frameMinZ = frameMinZ;
            this.frameMaxZ = frameMaxZ;
            this.centerX = (int) Math.floorDiv((long) cabinMinX + cabinMaxX, 2L);
            this.centerZ = (int) Math.floorDiv((long) cabinMinZ + cabinMaxZ, 2L);
            this.settings = settings;
        }

        private Plan assemble() {
            addGroundFoundation();
            addFourPosts();
            addUpperPlatform();
            addCabin();
            addRoof();
            addStorage();
            reserveWalkableVolumes();

            clearances.removeAll(placements.keySet());
            Bounds bounds = computeBounds();

            int worstCaseOperations = placements.size()
                    + clearances.size()
                    + foundationPosts.size() * settings.maximumFoundationDepth();
            if (worstCaseOperations > settings.maximumPlannedBlocks()) {
                throw new IllegalArgumentException(
                        "La cabane dépasserait la limite de "
                                + settings.maximumPlannedBlocks() + " blocs planifiés."
                );
            }
            if (chestPairs.size() != settings.doubleChestPairs()) {
                throw new IllegalArgumentException(
                        "La cabane ne peut contenir que " + chestPairs.size()
                                + " coffres doubles sur les "
                                + settings.doubleChestPairs() + " demandés."
                );
            }

            return new Plan(
                    baseY,
                    deckY,
                    cabinMinX,
                    cabinMaxX,
                    cabinMinZ,
                    cabinMaxZ,
                    frameMinX,
                    frameMaxX,
                    frameMinZ,
                    frameMaxZ,
                    settings,
                    bounds,
                    placements,
                    clearances,
                    chestPairs,
                    foundationPosts
            );
        }

        private void addGroundFoundation() {
            addWoodenRing(frameMinX, frameMaxX, frameMinZ, frameMaxZ, groundY, 2);
            addWoodenRing(
                    checkedCoordinate((long) mineMinX - 2L, "socle ouest"),
                    checkedCoordinate((long) mineMaxX + 2L, "socle est"),
                    checkedCoordinate((long) mineMinZ - 2L, "socle nord"),
                    checkedCoordinate((long) mineMaxZ + 2L, "socle sud"),
                    groundY,
                    2
            );
            addGroundConnectors();
            addStoneFootingPads();
            addInnerMineRailing();
            addOuterRailing(groundY, false);
        }

        private void addWoodenRing(int minX,
                                   int maxX,
                                   int minZ,
                                   int maxZ,
                                   int y,
                                   int thickness) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int edgeDistance = Math.min(
                            Math.min(x - minX, maxX - x),
                            Math.min(z - minZ, maxZ - z)
                    );
                    if (edgeDistance >= thickness) {
                        continue;
                    }
                    if (edgeDistance == 0) {
                        Axis axis = (z == minZ || z == maxZ) ? Axis.X : Axis.Z;
                        putOrientable(x, y, z, Material.STRIPPED_SPRUCE_LOG, axis, PRIORITY_ACCENT);
                    } else {
                        putSimple(x, y, z, Material.SPRUCE_PLANKS, PRIORITY_FLOOR);
                    }
                }
            }
        }

        private void addGroundConnectors() {
            int mineDeckMinX = checkedCoordinate((long) mineMinX - 2L, "pont ouest de la mine");
            int mineDeckMaxX = checkedCoordinate((long) mineMaxX + 2L, "pont est de la mine");
            int mineDeckMinZ = checkedCoordinate((long) mineMinZ - 2L, "pont nord de la mine");
            int mineDeckMaxZ = checkedCoordinate((long) mineMaxZ + 2L, "pont sud de la mine");

            for (int x = frameMinX; x <= mineDeckMinX; x++) {
                addHorizontalPathCell(x, centerZ, groundY, true);
            }
            for (int x = mineDeckMaxX; x <= frameMaxX; x++) {
                addHorizontalPathCell(x, centerZ, groundY, true);
            }
            for (int z = frameMinZ; z <= mineDeckMinZ; z++) {
                addVerticalPathCell(centerX, z, groundY, true);
            }
            for (int z = mineDeckMaxZ; z <= frameMaxZ; z++) {
                addVerticalPathCell(centerX, z, groundY, true);
            }
        }

        private void addStoneFootingPads() {
            for (Corner corner : corners()) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int x = checkedCoordinate((long) corner.x + dx, "socle de pilier X");
                        int z = checkedCoordinate((long) corner.z + dz, "socle de pilier Z");
                        putSimple(x, groundY, z, Material.POLISHED_ANDESITE, PRIORITY_ACCENT);
                    }
                }
                foundationPosts.add(new BlockPos(corner.x, baseY, corner.z));
            }
        }

        private void addInnerMineRailing() {
            int west = checkedCoordinate((long) mineMinX - 1L, "garde-corps ouest");
            int east = checkedCoordinate((long) mineMaxX + 1L, "garde-corps est");
            int north = checkedCoordinate((long) mineMinZ - 1L, "garde-corps nord");
            int south = checkedCoordinate((long) mineMaxZ + 1L, "garde-corps sud");
            int railY = checkedCoordinate((long) groundY + 1L, "hauteur de garde-corps");

            for (int x = west; x <= east; x++) {
                putSimple(x, railY, north, Material.SPRUCE_FENCE, PRIORITY_DECORATION);
                putSimple(x, railY, south, Material.SPRUCE_FENCE, PRIORITY_DECORATION);
            }
            for (int z = north; z <= south; z++) {
                putSimple(west, railY, z, Material.SPRUCE_FENCE, PRIORITY_DECORATION);
                putSimple(east, railY, z, Material.SPRUCE_FENCE, PRIORITY_DECORATION);
            }

            addStandingLantern(west, railY + 1, north);
            addStandingLantern(east, railY + 1, north);
            addStandingLantern(west, railY + 1, south);
            addStandingLantern(east, railY + 1, south);
        }

        private void addOuterRailing(int floorY, boolean upper) {
            int railY = checkedCoordinate((long) floorY + 1L, "hauteur de rambarde");
            int interval = upper ? 8 : 7;

            for (int x = frameMinX; x <= frameMaxX; x++) {
                addOuterRailCell(x, frameMinZ, railY, BlockFace.NORTH, interval, upper);
                addOuterRailCell(x, frameMaxZ, railY, BlockFace.SOUTH, interval, upper);
            }
            for (int z = frameMinZ + 1; z < frameMaxZ; z++) {
                addOuterRailCell(frameMinX, z, railY, BlockFace.WEST, interval, upper);
                addOuterRailCell(frameMaxX, z, railY, BlockFace.EAST, interval, upper);
            }

            if (!upper) {
                addFenceGate(centerX, railY, frameMinZ, BlockFace.NORTH);
                addFenceGate(centerX, railY, frameMaxZ, BlockFace.SOUTH);
                addFenceGate(frameMinX, railY, centerZ, BlockFace.WEST);
                addFenceGate(frameMaxX, railY, centerZ, BlockFace.EAST);
            }
        }

        private void addOuterRailCell(int x,
                                      int z,
                                      int railY,
                                      BlockFace side,
                                      int lanternInterval,
                                      boolean upper) {
            if (isCentralOpening(x, z, side, upper)
                    || isLadderOpening(x, z, side)) {
                return;
            }
            putSimple(x, railY, z, Material.DARK_OAK_FENCE, PRIORITY_DECORATION);

            int coordinate = (side == BlockFace.NORTH || side == BlockFace.SOUTH) ? x : z;
            int minimum = (side == BlockFace.NORTH || side == BlockFace.SOUTH) ? frameMinX : frameMinZ;
            if (Math.floorMod(coordinate - minimum, lanternInterval) == 0
                    && !isFrameCorner(x, z)) {
                addStandingLantern(x, railY + 1, z);
            }
        }

        private void addFourPosts() {
            for (Corner corner : corners()) {
                for (int y = groundY; y <= deckY + 1; y++) {
                    putOrientable(
                            corner.x,
                            y,
                            corner.z,
                            Material.STRIPPED_DARK_OAK_LOG,
                            Axis.Y,
                            PRIORITY_FRAME
                    );
                }
                addCornerBrackets(corner);
                addLadder(corner);
                addStandingLantern(corner.x, deckY + 2, corner.z);
            }
        }

        private void addCornerBrackets(Corner corner) {
            int bracketY = deckY - 1;
            int innerX = checkedCoordinate((long) corner.x + corner.inwardX, "console X");
            int innerZ = checkedCoordinate((long) corner.z + corner.inwardZ, "console Z");

            putStairs(
                    innerX,
                    bracketY,
                    corner.z,
                    Material.DARK_OAK_STAIRS,
                    corner.inwardX > 0 ? BlockFace.WEST : BlockFace.EAST,
                    Stairs.Half.TOP,
                    PRIORITY_FRAME
            );
            putStairs(
                    corner.x,
                    bracketY,
                    innerZ,
                    Material.DARK_OAK_STAIRS,
                    corner.inwardZ > 0 ? BlockFace.NORTH : BlockFace.SOUTH,
                    Stairs.Half.TOP,
                    PRIORITY_FRAME
            );
        }

        private void addLadder(Corner corner) {
            boolean northSide = corner.z == frameMinZ;
            int ladderZ = checkedCoordinate(
                    (long) corner.z + (northSide ? -1L : 1L),
                    "position d'échelle"
            );
            /*
             * La propriété facing d'une échelle pointe vers sa face visible :
             * son support se trouve donc dans la direction opposée. L'échelle
             * nord regarde vers le nord et s'appuie au sud sur le pilier ; la
             * logique est symétrique au sud.
             */
            BlockFace facing = northSide ? BlockFace.NORTH : BlockFace.SOUTH;

            for (int y = groundY + 1; y <= deckY + 1; y++) {
                putDirectional(
                        corner.x,
                        y,
                        ladderZ,
                        Material.LADDER,
                        facing,
                        PRIORITY_ATTACHMENT
                );
            }

            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0) {
                    continue;
                }
                int x = checkedCoordinate((long) corner.x + dx, "palier d'échelle");
                putSimple(x, groundY, ladderZ, Material.SPRUCE_PLANKS, PRIORITY_FLOOR);
                putSimple(x, deckY, ladderZ, Material.SPRUCE_PLANKS, PRIORITY_FLOOR);
                reserveColumn(x, groundY + 1, groundY + 2, ladderZ);
                reserveColumn(x, deckY + 1, deckY + 2, ladderZ);
            }
        }

        private void addUpperPlatform() {
            addWoodenRing(frameMinX, frameMaxX, frameMinZ, frameMaxZ, deckY, 2);
            addCabinFloor();
            addUpperBridges();
            addOuterRailing(deckY, true);
            addBalconyRailing();
            addBridgeRailings();
        }

        private void addCabinFloor() {
            for (int x = cabinMinX - 1; x <= cabinMaxX + 1; x++) {
                for (int z = cabinMinZ - 1; z <= cabinMaxZ + 1; z++) {
                    boolean border = x == cabinMinX - 1 || x == cabinMaxX + 1
                            || z == cabinMinZ - 1 || z == cabinMaxZ + 1;
                    if (border) {
                        Axis axis = (z == cabinMinZ - 1 || z == cabinMaxZ + 1)
                                ? Axis.X
                                : Axis.Z;
                        putOrientable(
                                x,
                                deckY,
                                z,
                                Material.STRIPPED_SPRUCE_LOG,
                                axis,
                                PRIORITY_ACCENT
                        );
                    } else {
                        putSimple(x, deckY, z, Material.SPRUCE_PLANKS, PRIORITY_FLOOR);
                    }
                }
            }
        }

        private void addUpperBridges() {
            for (int z = frameMinZ; z <= cabinMinZ - 1; z++) {
                addVerticalPathCell(centerX, z, deckY, false);
            }
            for (int z = cabinMaxZ + 1; z <= frameMaxZ; z++) {
                addVerticalPathCell(centerX, z, deckY, false);
            }
            for (int x = frameMinX; x <= cabinMinX - 1; x++) {
                addHorizontalPathCell(x, centerZ, deckY, false);
            }
            for (int x = cabinMaxX + 1; x <= frameMaxX; x++) {
                addHorizontalPathCell(x, centerZ, deckY, false);
            }
        }

        private void addVerticalPathCell(int center,
                                         int z,
                                         int y,
                                         boolean ground) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = checkedCoordinate((long) center + dx, "largeur de passerelle");
                Material material = dx == 0
                        ? Material.SPRUCE_PLANKS
                        : Material.STRIPPED_SPRUCE_LOG;
                if (dx == 0) {
                    putSimple(x, y, z, material, PRIORITY_FLOOR);
                } else {
                    putOrientable(x, y, z, material, Axis.Z, PRIORITY_ACCENT);
                }
                reserveColumn(x, y + 1, y + (ground ? 2 : 3), z);
            }
        }

        private void addHorizontalPathCell(int x,
                                           int center,
                                           int y,
                                           boolean ground) {
            for (int dz = -1; dz <= 1; dz++) {
                int z = checkedCoordinate((long) center + dz, "largeur de passerelle");
                Material material = dz == 0
                        ? Material.SPRUCE_PLANKS
                        : Material.STRIPPED_SPRUCE_LOG;
                if (dz == 0) {
                    putSimple(x, y, z, material, PRIORITY_FLOOR);
                } else {
                    putOrientable(x, y, z, material, Axis.X, PRIORITY_ACCENT);
                }
                reserveColumn(x, y + 1, y + (ground ? 2 : 3), z);
            }
        }

        private void addBalconyRailing() {
            int y = deckY + 1;
            int minX = cabinMinX - 1;
            int maxX = cabinMaxX + 1;
            int minZ = cabinMinZ - 1;
            int maxZ = cabinMaxZ + 1;

            for (int x = minX; x <= maxX; x++) {
                if (Math.abs(x - centerX) > 1) {
                    putSimple(x, y, minZ, Material.DARK_OAK_FENCE, PRIORITY_DECORATION);
                    putSimple(x, y, maxZ, Material.DARK_OAK_FENCE, PRIORITY_DECORATION);
                }
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                if (Math.abs(z - centerZ) > 1) {
                    putSimple(minX, y, z, Material.DARK_OAK_FENCE, PRIORITY_DECORATION);
                    putSimple(maxX, y, z, Material.DARK_OAK_FENCE, PRIORITY_DECORATION);
                }
            }
        }

        private void addBridgeRailings() {
            int y = deckY + 1;
            int westRailX = centerX - 2;
            int eastRailX = centerX + 2;
            for (int z = frameMinZ + 2; z < cabinMinZ - 1; z++) {
                putSimple(westRailX, y, z, Material.DARK_OAK_FENCE, PRIORITY_DECORATION);
                putSimple(eastRailX, y, z, Material.DARK_OAK_FENCE, PRIORITY_DECORATION);
            }
            for (int z = cabinMaxZ + 2; z <= frameMaxZ - 2; z++) {
                putSimple(westRailX, y, z, Material.DARK_OAK_FENCE, PRIORITY_DECORATION);
                putSimple(eastRailX, y, z, Material.DARK_OAK_FENCE, PRIORITY_DECORATION);
            }

            int northRailZ = centerZ - 2;
            int southRailZ = centerZ + 2;
            for (int x = frameMinX + 2; x < cabinMinX - 1; x++) {
                putSimple(x, y, northRailZ, Material.DARK_OAK_FENCE, PRIORITY_DECORATION);
                putSimple(x, y, southRailZ, Material.DARK_OAK_FENCE, PRIORITY_DECORATION);
            }
            for (int x = cabinMaxX + 2; x <= frameMaxX - 2; x++) {
                putSimple(x, y, northRailZ, Material.DARK_OAK_FENCE, PRIORITY_DECORATION);
                putSimple(x, y, southRailZ, Material.DARK_OAK_FENCE, PRIORITY_DECORATION);
            }
        }

        private void addCabin() {
            addCabinWalls();
            addCabinDoors();
            addCabinCarpet();
            addInteriorLighting();
            addExteriorDoorLights();
        }

        private void addCabinWalls() {
            int wallTop = deckY + settings.wallHeight();
            for (int y = deckY + 1; y <= wallTop; y++) {
                for (int x = cabinMinX; x <= cabinMaxX; x++) {
                    addWallCell(x, y, cabinMinZ, true);
                    addWallCell(x, y, cabinMaxZ, true);
                }
                for (int z = cabinMinZ + 1; z < cabinMaxZ; z++) {
                    addWallCell(cabinMinX, y, z, false);
                    addWallCell(cabinMaxX, y, z, false);
                }
            }
        }

        private void addWallCell(int x,
                                 int y,
                                 int z,
                                 boolean horizontalWall) {
            int wallTop = deckY + settings.wallHeight();
            boolean corner = (x == cabinMinX || x == cabinMaxX)
                    && (z == cabinMinZ || z == cabinMaxZ);
            int along = horizontalWall ? x - cabinMinX : z - cabinMinZ;
            boolean verticalBeam = corner || Math.floorMod(along, 4) == 0;
            boolean topBeam = y == wallTop;
            boolean window = !verticalBeam
                    && !topBeam
                    && (y == deckY + 2 || y == deckY + 3)
                    && !isDoorColumn(x, z);

            if (verticalBeam) {
                putOrientable(
                        x,
                        y,
                        z,
                        Material.STRIPPED_DARK_OAK_LOG,
                        Axis.Y,
                        PRIORITY_FRAME
                );
            } else if (topBeam) {
                putOrientable(
                        x,
                        y,
                        z,
                        Material.STRIPPED_SPRUCE_LOG,
                        horizontalWall ? Axis.X : Axis.Z,
                        PRIORITY_FRAME
                );
            } else if (window) {
                putSimple(
                        x,
                        y,
                        z,
                        Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                        PRIORITY_WALL
                );
            } else {
                putSimple(x, y, z, Material.SPRUCE_PLANKS, PRIORITY_WALL);
            }
        }

        private void addCabinDoors() {
            addDoor(centerX, deckY + 1, cabinMinZ, BlockFace.NORTH);
            addDoor(centerX, deckY + 1, cabinMaxZ, BlockFace.SOUTH);

            putStairs(
                    centerX,
                    deckY + 3,
                    cabinMinZ - 1,
                    Material.DARK_OAK_STAIRS,
                    BlockFace.NORTH,
                    Stairs.Half.TOP,
                    PRIORITY_DECORATION
            );
            putStairs(
                    centerX,
                    deckY + 3,
                    cabinMaxZ + 1,
                    Material.DARK_OAK_STAIRS,
                    BlockFace.SOUTH,
                    Stairs.Half.TOP,
                    PRIORITY_DECORATION
            );
        }

        private void addCabinCarpet() {
            for (int z = cabinMinZ + 1; z <= cabinMaxZ - 1; z++) {
                putSimple(centerX, deckY + 1, z, Material.RED_CARPET, PRIORITY_DECORATION);
            }
            for (int x = centerX - 1; x <= centerX + 1; x++) {
                for (int z = centerZ - 1; z <= centerZ + 1; z++) {
                    putSimple(x, deckY + 1, z, Material.BROWN_CARPET, PRIORITY_DECORATION);
                }
            }
        }

        private void addInteriorLighting() {
            int beamY = deckY + settings.wallHeight();
            int lanternY = beamY - 1;
            int offsetX = Math.max(2, (cabinMaxX - cabinMinX) / 4);
            int offsetZ = Math.max(2, (cabinMaxZ - cabinMinZ) / 4);

            /*
             * Deux poutres apparentes portent quatre lanternes. Contrairement
             * à une chaîne flottante, chaque luminaire possède ainsi un support
             * réel et reste stable lors des mises à jour de blocs.
             */
            for (int z : new int[]{centerZ - offsetZ, centerZ + offsetZ}) {
                for (int x = cabinMinX; x <= cabinMaxX; x++) {
                    putOrientable(
                            x,
                            beamY,
                            z,
                            Material.STRIPPED_SPRUCE_LOG,
                            Axis.X,
                            PRIORITY_FRAME
                    );
                }
                addHangingLantern(centerX - offsetX, lanternY, z);
                addHangingLantern(centerX + offsetX, lanternY, z);
            }
        }

        private void addExteriorDoorLights() {
            int lanternY = deckY + 3;
            int chainY = lanternY + 1;
            int canopyY = chainY + 1;
            int leftX = centerX - 2;
            int rightX = centerX + 2;

            for (int z : new int[]{cabinMinZ - 1, cabinMaxZ + 1}) {
                for (int x = centerX - 3; x <= centerX + 3; x++) {
                    putOrientable(
                            x,
                            canopyY,
                            z,
                            Material.STRIPPED_SPRUCE_LOG,
                            Axis.X,
                            PRIORITY_FRAME
                    );
                }
                for (int x : new int[]{leftX, rightX}) {
                    putOrientable(x, chainY, z, Material.CHAIN, Axis.Y, PRIORITY_ATTACHMENT);
                    addHangingLantern(x, lanternY, z);
                }
            }
        }

        private void addRoof() {
            int width = cabinMaxX - cabinMinX + 1;
            int length = cabinMaxZ - cabinMinZ + 1;
            if (width <= length) {
                addRoofSlopingAlongX();
            } else {
                addRoofSlopingAlongZ();
            }
        }

        private void addRoofSlopingAlongX() {
            int roofBaseY = deckY + settings.wallHeight() + 1;
            int eaveMinX = cabinMinX - 1;
            int eaveMaxX = cabinMaxX + 1;
            int eaveMinZ = cabinMinZ - 1;
            int eaveMaxZ = cabinMaxZ + 1;
            int levels = (eaveMaxX - eaveMinX) / 2;

            for (int level = 0; level < levels; level++) {
                int y = roofBaseY + level;
                int westX = eaveMinX + level;
                int eastX = eaveMaxX - level;
                for (int z = eaveMinZ; z <= eaveMaxZ; z++) {
                    putStairs(
                            westX,
                            y,
                            z,
                            Material.DARK_OAK_STAIRS,
                            BlockFace.WEST,
                            Stairs.Half.BOTTOM,
                            PRIORITY_ROOF
                    );
                    putStairs(
                            eastX,
                            y,
                            z,
                            Material.DARK_OAK_STAIRS,
                            BlockFace.EAST,
                            Stairs.Half.BOTTOM,
                            PRIORITY_ROOF
                    );
                }
                addGableRowX(cabinMinZ, y, westX + 1, eastX - 1, level);
                addGableRowX(cabinMaxZ, y, westX + 1, eastX - 1, level);
            }

            int ridgeX = eaveMinX + levels;
            int ridgeY = roofBaseY + levels;
            for (int z = eaveMinZ; z <= eaveMaxZ; z++) {
                putOrientable(
                        ridgeX,
                        ridgeY,
                        z,
                        Material.STRIPPED_DARK_OAK_LOG,
                        Axis.Z,
                        PRIORITY_ROOF
                );
            }
        }

        private void addGableRowX(int z,
                                  int y,
                                  int minX,
                                  int maxX,
                                  int level) {
            if (minX > maxX) {
                return;
            }
            for (int x = minX; x <= maxX; x++) {
                boolean centerWindow = level >= 1
                        && level <= 2
                        && Math.abs(x - centerX) <= 1;
                if (centerWindow) {
                    putSimple(
                            x,
                            y,
                            z,
                            Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                            PRIORITY_WALL
                    );
                } else if (x == centerX) {
                    putOrientable(
                            x,
                            y,
                            z,
                            Material.STRIPPED_SPRUCE_LOG,
                            Axis.Y,
                            PRIORITY_FRAME
                    );
                } else {
                    putSimple(x, y, z, Material.SPRUCE_PLANKS, PRIORITY_WALL);
                }
            }
        }

        private void addRoofSlopingAlongZ() {
            int roofBaseY = deckY + settings.wallHeight() + 1;
            int eaveMinX = cabinMinX - 1;
            int eaveMaxX = cabinMaxX + 1;
            int eaveMinZ = cabinMinZ - 1;
            int eaveMaxZ = cabinMaxZ + 1;
            int levels = (eaveMaxZ - eaveMinZ) / 2;

            for (int level = 0; level < levels; level++) {
                int y = roofBaseY + level;
                int northZ = eaveMinZ + level;
                int southZ = eaveMaxZ - level;
                for (int x = eaveMinX; x <= eaveMaxX; x++) {
                    putStairs(
                            x,
                            y,
                            northZ,
                            Material.DARK_OAK_STAIRS,
                            BlockFace.NORTH,
                            Stairs.Half.BOTTOM,
                            PRIORITY_ROOF
                    );
                    putStairs(
                            x,
                            y,
                            southZ,
                            Material.DARK_OAK_STAIRS,
                            BlockFace.SOUTH,
                            Stairs.Half.BOTTOM,
                            PRIORITY_ROOF
                    );
                }
                addGableRowZ(cabinMinX, y, northZ + 1, southZ - 1, level);
                addGableRowZ(cabinMaxX, y, northZ + 1, southZ - 1, level);
            }

            int ridgeZ = eaveMinZ + levels;
            int ridgeY = roofBaseY + levels;
            for (int x = eaveMinX; x <= eaveMaxX; x++) {
                putOrientable(
                        x,
                        ridgeY,
                        ridgeZ,
                        Material.STRIPPED_DARK_OAK_LOG,
                        Axis.X,
                        PRIORITY_ROOF
                );
            }
        }

        private void addGableRowZ(int x,
                                  int y,
                                  int minZ,
                                  int maxZ,
                                  int level) {
            if (minZ > maxZ) {
                return;
            }
            for (int z = minZ; z <= maxZ; z++) {
                boolean centerWindow = level >= 1
                        && level <= 2
                        && Math.abs(z - centerZ) <= 1;
                if (centerWindow) {
                    putSimple(
                            x,
                            y,
                            z,
                            Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                            PRIORITY_WALL
                    );
                } else if (z == centerZ) {
                    putOrientable(
                            x,
                            y,
                            z,
                            Material.STRIPPED_SPRUCE_LOG,
                            Axis.Y,
                            PRIORITY_FRAME
                    );
                } else {
                    putSimple(x, y, z, Material.SPRUCE_PLANKS, PRIORITY_WALL);
                }
            }
        }

        private void addStorage() {
            List<ChestPair> candidates = storageCandidates();
            if (candidates.size() < settings.doubleChestPairs()) {
                throw new IllegalArgumentException(
                        "La cabine " + (cabinMaxX - cabinMinX + 1) + "x"
                                + (cabinMaxZ - cabinMinZ + 1)
                                + " n'offre que " + candidates.size()
                                + " emplacements de coffres doubles."
                );
            }

            for (int index = 0; index < settings.doubleChestPairs(); index++) {
                ChestPair pair = candidates.get(index);
                chestPairs.add(pair);
                putChestPair(pair);
            }
        }

        private List<ChestPair> storageCandidates() {
            List<ChestPair> result = new ArrayList<>();
            int y = deckY + 1;
            int startZ = cabinMinZ + 2;
            int endZ = cabinMaxZ - 2;
            int startX = cabinMinX + 2;
            int endX = cabinMaxX - 2;

            List<Integer> zStarts = spacedPairStarts(startZ, endZ);
            List<Integer> xStarts = spacedPairStartsAroundDoor(
                    startX,
                    endX,
                    centerX
            );
            int rounds = Math.max(zStarts.size(), xStarts.size());

            for (int index = 0; index < rounds; index++) {
                if (index < zStarts.size()) {
                    int z = zStarts.get(index);
                    result.add(new ChestPair(
                            new BlockPos(cabinMinX + 1, y, z),
                            new BlockPos(cabinMinX + 1, y, z + 1),
                            BlockFace.EAST
                    ));
                    result.add(new ChestPair(
                            new BlockPos(cabinMaxX - 1, y, z),
                            new BlockPos(cabinMaxX - 1, y, z + 1),
                            BlockFace.WEST
                    ));
                }
                if (index < xStarts.size()) {
                    int x = xStarts.get(index);
                    if (x != centerX && x + 1 != centerX) {
                        result.add(new ChestPair(
                                new BlockPos(x, y, cabinMinZ + 1),
                                new BlockPos(x + 1, y, cabinMinZ + 1),
                                BlockFace.SOUTH
                        ));
                        result.add(new ChestPair(
                                new BlockPos(x, y, cabinMaxZ - 1),
                                new BlockPos(x + 1, y, cabinMaxZ - 1),
                                BlockFace.NORTH
                        ));
                    }
                }
            }
            return result;
        }

        private List<Integer> spacedPairStarts(int minimum, int maximum) {
            List<Integer> starts = new ArrayList<>();
            for (int value = minimum; value + 1 <= maximum; value += 3) {
                starts.add(value);
            }
            return starts;
        }

        /**
         * Répartit les coffres de la façade de part et d'autre de la porte.
         *
         * <p>Une progression depuis un seul bord produit seulement trois
         * paires dans une pièce de 17 blocs, car la quatrième recouvre l'axe
         * central. En partant symétriquement des deux extrémités, l'allée de
         * la porte reste libre sans perdre de capacité.</p>
         */
        private List<Integer> spacedPairStartsAroundDoor(int minimum,
                                                         int maximum,
                                                         int doorAxis) {
            List<Integer> left = new ArrayList<>();
            for (int value = minimum; value + 1 < doorAxis; value += 3) {
                left.add(value);
            }

            List<Integer> right = new ArrayList<>();
            for (int value = maximum - 1; value > doorAxis; value -= 3) {
                right.add(value);
            }

            List<Integer> balanced = new ArrayList<>(left.size() + right.size());
            int rounds = Math.max(left.size(), right.size());
            for (int index = 0; index < rounds; index++) {
                if (index < left.size()) {
                    balanced.add(left.get(index));
                }
                if (index < right.size()) {
                    balanced.add(right.get(index));
                }
            }
            return balanced;
        }

        private void putChestPair(ChestPair pair) {
            BlockFace direction = directionBetween(pair.first, pair.second);
            BlockFace clockwise = clockwise(pair.facing);
            Chest.Type firstType;
            Chest.Type secondType;
            if (direction == clockwise) {
                firstType = Chest.Type.LEFT;
                secondType = Chest.Type.RIGHT;
            } else if (direction == clockwise.getOppositeFace()) {
                firstType = Chest.Type.RIGHT;
                secondType = Chest.Type.LEFT;
            } else {
                throw new IllegalArgumentException("Orientation impossible du coffre double.");
            }

            putChest(pair.first, pair.facing, firstType);
            putChest(pair.second, pair.facing, secondType);
        }

        private void reserveWalkableVolumes() {
            /*
             * Tout bloc de sol créé doit garder deux blocs de hauteur libre.
             * Les emplacements réellement occupés par une paroi, un coffre ou
             * une décoration seront retirés du set de dégagement à la fin.
             */
            List<BlockPos> floors = new ArrayList<>();
            for (Placement placement : placements.values()) {
                if (placement.position.y == groundY || placement.position.y == deckY) {
                    Material material = placement.material;
                    if (material == Material.SPRUCE_PLANKS
                            || material == Material.STRIPPED_SPRUCE_LOG
                            || material == Material.STRIPPED_DARK_OAK_LOG
                            || material == Material.POLISHED_ANDESITE) {
                        floors.add(placement.position);
                    }
                }
            }
            for (BlockPos floor : floors) {
                reserve(floor.x, floor.y + 1, floor.z);
                reserve(floor.x, floor.y + 2, floor.z);
            }

            for (int x = cabinMinX + 1; x <= cabinMaxX - 1; x++) {
                for (int z = cabinMinZ + 1; z <= cabinMaxZ - 1; z++) {
                    for (int y = deckY + 1; y <= deckY + settings.wallHeight() - 1; y++) {
                        reserve(x, y, z);
                    }
                }
            }
        }

        private Bounds computeBounds() {
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;

            Set<BlockPos> all = new HashSet<>(placements.keySet());
            all.addAll(clearances);
            for (BlockPos position : all) {
                minX = Math.min(minX, position.x);
                maxX = Math.max(maxX, position.x);
                minY = Math.min(minY, position.y);
                maxY = Math.max(maxY, position.y);
                minZ = Math.min(minZ, position.z);
                maxZ = Math.max(maxZ, position.z);
            }
            if (all.isEmpty()) {
                throw new IllegalStateException("Plan de cabane vide.");
            }
            return new Bounds(minX, maxX, minY, maxY, minZ, maxZ);
        }

        private List<Corner> corners() {
            return List.of(
                    new Corner(frameMinX, frameMinZ, 1, 1),
                    new Corner(frameMaxX, frameMinZ, -1, 1),
                    new Corner(frameMinX, frameMaxZ, 1, -1),
                    new Corner(frameMaxX, frameMaxZ, -1, -1)
            );
        }

        private boolean isDoorColumn(int x, int z) {
            return x == centerX && (z == cabinMinZ || z == cabinMaxZ);
        }

        private boolean isCentralOpening(int x,
                                         int z,
                                         BlockFace side,
                                         boolean upper) {
            int radius = upper ? 2 : 0;
            if (side == BlockFace.NORTH || side == BlockFace.SOUTH) {
                return Math.abs(x - centerX) <= radius;
            }
            return Math.abs(z - centerZ) <= radius;
        }

        private boolean isLadderOpening(int x, int z, BlockFace side) {
            if (side == BlockFace.NORTH && z == frameMinZ) {
                return x <= frameMinX + 1 || x >= frameMaxX - 1;
            }
            if (side == BlockFace.SOUTH && z == frameMaxZ) {
                return x <= frameMinX + 1 || x >= frameMaxX - 1;
            }
            return false;
        }

        private boolean isFrameCorner(int x, int z) {
            return (x == frameMinX || x == frameMaxX)
                    && (z == frameMinZ || z == frameMaxZ);
        }

        private void addStandingLantern(int x, int y, int z) {
            putLantern(x, y, z, false);
        }

        private void addHangingLantern(int x, int y, int z) {
            putLantern(x, y, z, true);
        }

        private void addDoor(int x,
                             int lowerY,
                             int z,
                             BlockFace facing) {
            putDoorHalf(
                    x,
                    lowerY,
                    z,
                    facing,
                    Bisected.Half.BOTTOM,
                    Door.Hinge.LEFT
            );
            putDoorHalf(
                    x,
                    lowerY + 1,
                    z,
                    facing,
                    Bisected.Half.TOP,
                    Door.Hinge.LEFT
            );
        }

        private void addFenceGate(int x,
                                  int y,
                                  int z,
                                  BlockFace facing) {
            BlockPos position = new BlockPos(x, y, z);
            put(new Placement(
                    position,
                    Material.SPRUCE_FENCE_GATE,
                    PRIORITY_ATTACHMENT,
                    facing,
                    data -> {
                        if (data instanceof Gate gate) {
                            gate.setFacing(facing);
                            gate.setOpen(false);
                            gate.setInWall(false);
                        }
                    }
            ));
        }

        private void putDoorHalf(int x,
                                 int y,
                                 int z,
                                 BlockFace facing,
                                 Bisected.Half half,
                                 Door.Hinge hinge) {
            BlockPos position = new BlockPos(x, y, z);
            put(new Placement(
                    position,
                    Material.SPRUCE_DOOR,
                    PRIORITY_ATTACHMENT,
                    facing,
                    data -> {
                        if (data instanceof Door door) {
                            door.setFacing(facing);
                            door.setHalf(half);
                            door.setHinge(hinge);
                            door.setOpen(false);
                            door.setPowered(false);
                        }
                    }
            ));
        }

        private void putChest(BlockPos position,
                              BlockFace facing,
                              Chest.Type type) {
            put(new Placement(
                    position,
                    Material.CHEST,
                    PRIORITY_CONTAINER,
                    facing,
                    data -> {
                        if (data instanceof Chest chest) {
                            chest.setFacing(facing);
                            chest.setType(type);
                        }
                    }
            ));
        }

        private void putLantern(int x,
                                int y,
                                int z,
                                boolean hanging) {
            BlockPos position = new BlockPos(x, y, z);
            put(new Placement(
                    position,
                    Material.LANTERN,
                    PRIORITY_ATTACHMENT,
                    null,
                    data -> {
                        if (data instanceof Lantern lantern) {
                            lantern.setHanging(hanging);
                        }
                    }
            ));
        }

        private void putStairs(int x,
                               int y,
                               int z,
                               Material material,
                               BlockFace facing,
                               Stairs.Half half,
                               int priority) {
            BlockPos position = new BlockPos(x, y, z);
            put(new Placement(
                    position,
                    material,
                    priority,
                    facing,
                    data -> {
                        if (data instanceof Stairs stairs) {
                            stairs.setFacing(facing);
                            stairs.setHalf(half);
                            stairs.setShape(Stairs.Shape.STRAIGHT);
                        }
                    }
            ));
        }

        private void putDirectional(int x,
                                    int y,
                                    int z,
                                    Material material,
                                    BlockFace facing,
                                    int priority) {
            BlockPos position = new BlockPos(x, y, z);
            put(new Placement(
                    position,
                    material,
                    priority,
                    facing,
                    data -> {
                        if (data instanceof Directional directional) {
                            directional.setFacing(facing);
                        }
                    }
            ));
        }

        private void putOrientable(int x,
                                   int y,
                                   int z,
                                   Material material,
                                   Axis axis,
                                   int priority) {
            BlockPos position = new BlockPos(x, y, z);
            put(new Placement(
                    position,
                    material,
                    priority,
                    null,
                    data -> {
                        if (data instanceof Orientable orientable
                                && orientable.getAxes().contains(axis)) {
                            orientable.setAxis(axis);
                        }
                    }
            ));
        }

        private void putSimple(int x,
                               int y,
                               int z,
                               Material material,
                               int priority) {
            BlockPos position = new BlockPos(x, y, z);
            put(Placement.simple(position, material, priority));
        }

        private void put(Placement placement) {
            Placement existing = placements.get(placement.position);
            if (existing == null || placement.priority >= existing.priority) {
                placements.put(placement.position, placement);
            }
        }

        private void reserveColumn(int x,
                                   int minY,
                                   int maxY,
                                   int z) {
            for (int y = minY; y <= maxY; y++) {
                reserve(x, y, z);
            }
        }

        private void reserve(int x, int y, int z) {
            clearances.add(new BlockPos(x, y, z));
        }

        private BlockFace directionBetween(BlockPos first, BlockPos second) {
            int dx = second.x - first.x;
            int dz = second.z - first.z;
            if (dx == 1 && dz == 0) {
                return BlockFace.EAST;
            }
            if (dx == -1 && dz == 0) {
                return BlockFace.WEST;
            }
            if (dx == 0 && dz == 1) {
                return BlockFace.SOUTH;
            }
            if (dx == 0 && dz == -1) {
                return BlockFace.NORTH;
            }
            throw new IllegalArgumentException("Positions non adjacentes.");
        }

        private BlockFace clockwise(BlockFace face) {
            return switch (face) {
                case NORTH -> BlockFace.EAST;
                case EAST -> BlockFace.SOUTH;
                case SOUTH -> BlockFace.WEST;
                case WEST -> BlockFace.NORTH;
                default -> throw new IllegalArgumentException("Face non horizontale.");
            };
        }

        private record Corner(int x, int z, int inwardX, int inwardZ) {
        }
    }
}
