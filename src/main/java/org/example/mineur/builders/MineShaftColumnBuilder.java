package org.example.mineur.builders;

import org.bukkit.Axis;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Lantern;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Entretient la colonne d'accès verticale située sous la salle des coffres.
 *
 * <p>La classe ne casse jamais de bloc. Elle complète uniquement les volumes
 * déjà excavés par le mineur : une ressource encore présente, un liquide ou un
 * bloc placé par le joueur reste donc intact. La géométrie est déterministe
 * afin qu'un redémarrage puisse reconstruire les éléments manquants sans
 * persister une seconde copie des coordonnées.</p>
 */
public final class MineShaftColumnBuilder {

    public static final int DEFAULT_LIGHT_INTERVAL = 5;
    public static final int MINIMUM_LIGHT_INTERVAL = 3;
    public static final int MAXIMUM_LIGHT_INTERVAL = 16;

    private static final Set<Material> MANAGED_MATERIALS = Collections.unmodifiableSet(
            EnumSet.of(
                    Material.STRIPPED_DARK_OAK_LOG,
                    Material.STRIPPED_SPRUCE_LOG,
                    Material.LADDER,
                    Material.LANTERN,
                    Material.SHROOMLIGHT
            )
    );

    private MineShaftColumnBuilder() {
        // Classe utilitaire.
    }

    /**
     * Géométrie horizontale persistante du puits.
     *
     * <p>Le bloc d'échelle reste dans l'emprise minée. Son support est lui aussi
     * placé dans cette emprise dès que la sélection fait au moins deux blocs.
     * Une sélection 1x1 conserve un repli sûr : l'échelle s'appuie sur la paroi
     * naturelle voisine, sans que le plugin ne creuse hors de la zone choisie.</p>
     */
    public record Layout(int mineMinX,
                         int mineMaxX,
                         int mineMinZ,
                         int mineMaxZ,
                         int ladderX,
                         int ladderZ,
                         int supportX,
                         int supportZ,
                         BlockFace supportDirection,
                         BlockFace ladderFacing,
                         boolean sideLantern,
                         int lightX,
                         int lightZ,
                         Axis lightBeamAxis) {

        public Layout {
            Objects.requireNonNull(supportDirection, "supportDirection");
            Objects.requireNonNull(ladderFacing, "ladderFacing");
            Objects.requireNonNull(lightBeamAxis, "lightBeamAxis");

            if (mineMinX > mineMaxX || mineMinZ > mineMaxZ) {
                throw new IllegalArgumentException("Emprise horizontale de puits invalide.");
            }
            if (!isHorizontal(supportDirection)
                    || ladderFacing != supportDirection.getOppositeFace()) {
                throw new IllegalArgumentException("Orientation de l'échelle incohérente.");
            }
            if (Math.abs((long) supportX - ladderX)
                    + Math.abs((long) supportZ - ladderZ) != 1L) {
                throw new IllegalArgumentException(
                        "Le support doit être adjacent au bloc d'échelle."
                );
            }
            if (ladderX < mineMinX || ladderX > mineMaxX
                    || ladderZ < mineMinZ || ladderZ > mineMaxZ) {
                throw new IllegalArgumentException(
                        "L'échelle doit rester dans l'emprise de la mine."
                );
            }
            if (sideLantern) {
                if (lightX < mineMinX || lightX > mineMaxX
                        || lightZ < mineMinZ || lightZ > mineMaxZ
                        || Math.abs((long) lightX - supportX)
                        + Math.abs((long) lightZ - supportZ) != 1L
                        || (lightX == ladderX && lightZ == ladderZ)) {
                    throw new IllegalArgumentException(
                            "La console lumineuse du puits est invalide."
                    );
                }
            }
        }

        public boolean contains(int x, int z) {
            return x >= mineMinX && x <= mineMaxX
                    && z >= mineMinZ && z <= mineMaxZ;
        }

        public boolean isRelevantHorizontal(int x, int z) {
            return (x == ladderX && z == ladderZ)
                    || (x == supportX && z == supportZ)
                    || (sideLantern && x == lightX && z == lightZ);
        }
    }

    /**
     * Écrit un bloc après les contrôles propres au serveur (claims, bordure,
     * propriétaire connecté, etc.).
     */
    @FunctionalInterface
    public interface PlacementHandler {

        boolean place(Block target,
                      Material material,
                      Consumer<BlockData> dataConfigurer);
    }

    /**
     * Calcule une échelle centrale et choisit la paroi offrant le plus de place
     * pour son poteau. Les calculs utilisent des {@code long} afin de refuser
     * proprement les coordonnées qui déborderaient un entier.
     */
    public static Layout createLayout(int mineMinX,
                                      int mineMinZ,
                                      int width,
                                      int length) {
        if (width < 1 || length < 1) {
            throw new IllegalArgumentException("Dimensions de puits invalides.");
        }

        int mineMaxX = checkedCoordinate(
                (long) mineMinX + width - 1L,
                "X maximal du puits"
        );
        int mineMaxZ = checkedCoordinate(
                (long) mineMinZ + length - 1L,
                "Z maximal du puits"
        );
        int ladderX = checkedCoordinate(
                Math.floorDiv((long) mineMinX + mineMaxX, 2L),
                "X de l'échelle"
        );
        int ladderZ = checkedCoordinate(
                Math.floorDiv((long) mineMinZ + mineMaxZ, 2L),
                "Z de l'échelle"
        );

        BlockFace supportDirection = chooseSupportDirection(
                mineMinX,
                mineMaxX,
                mineMinZ,
                mineMaxZ,
                ladderX,
                ladderZ
        );
        int supportX = checkedCoordinate(
                (long) ladderX + supportDirection.getModX(),
                "X du poteau"
        );
        int supportZ = checkedCoordinate(
                (long) ladderZ + supportDirection.getModZ(),
                "Z du poteau"
        );

        BlockFace firstSide = clockwise(supportDirection);
        BlockFace secondSide = firstSide.getOppositeFace();
        BlockFace lightDirection = null;
        for (BlockFace candidate : new BlockFace[]{firstSide, secondSide}) {
            int candidateX = checkedCoordinate(
                    (long) supportX + candidate.getModX(),
                    "X de la console lumineuse"
            );
            int candidateZ = checkedCoordinate(
                    (long) supportZ + candidate.getModZ(),
                    "Z de la console lumineuse"
            );
            if (candidateX >= mineMinX && candidateX <= mineMaxX
                    && candidateZ >= mineMinZ && candidateZ <= mineMaxZ
                    && (candidateX != ladderX || candidateZ != ladderZ)) {
                lightDirection = candidate;
                break;
            }
        }

        boolean sideLantern = lightDirection != null;
        int lightX = supportX;
        int lightZ = supportZ;
        Axis beamAxis = Axis.X;
        if (sideLantern) {
            lightX = checkedCoordinate(
                    (long) supportX + lightDirection.getModX(),
                    "X de la lumière"
            );
            lightZ = checkedCoordinate(
                    (long) supportZ + lightDirection.getModZ(),
                    "Z de la lumière"
            );
            beamAxis = lightDirection.getModX() != 0 ? Axis.X : Axis.Z;
        }

        return new Layout(
                mineMinX,
                mineMaxX,
                mineMinZ,
                mineMaxZ,
                ladderX,
                ladderZ,
                supportX,
                supportZ,
                supportDirection,
                supportDirection.getOppositeFace(),
                sideLantern,
                lightX,
                lightZ,
                beamAxis
        );
    }

    public static int normalizeLightInterval(int configured) {
        return Math.max(
                MINIMUM_LIGHT_INTERVAL,
                Math.min(MAXIMUM_LIGHT_INTERVAL, configured)
        );
    }

    /**
     * Répare ou prolonge toutes les couches déjà excavées, du niveau de départ
     * jusqu'à la profondeur demandée. La boucle est bornée par la hauteur réelle
     * du monde, ce qui rend l'opération sûre lors d'une reprise après crash.
     */
    public static void maintainRange(World world,
                                     Layout layout,
                                     int baseY,
                                     int minimumY,
                                     int lightInterval,
                                     PlacementHandler placer) {
        if (world == null || layout == null || placer == null) {
            return;
        }

        int safeBaseY = Math.min(baseY, world.getMaxHeight() - 1);
        int safeMinimumY = Math.max(minimumY, world.getMinHeight());
        if (safeMinimumY > safeBaseY) {
            return;
        }

        for (int y = safeBaseY; y >= safeMinimumY; y--) {
            maintainLayer(world, layout, baseY, y, lightInterval, placer);
        }
    }

    /**
     * Complète une couche sans remplacer de roche non minée.
     *
     * <p>Le poteau sombre porte l'échelle. Tous les cinq niveaux par défaut,
     * une console en épicéa reçoit une lanterne suspendue. Lorsque la sélection
     * est trop étroite pour cette console, un shroomlight chaud est intégré au
     * poteau : l'accès reste éclairé sans élargir artificiellement la mine.</p>
     */
    public static void maintainLayer(World world,
                                     Layout layout,
                                     int baseY,
                                     int y,
                                     int lightInterval,
                                     PlacementHandler placer) {
        if (world == null
                || layout == null
                || placer == null
                || y < world.getMinHeight()
                || y >= world.getMaxHeight()) {
            return;
        }

        int safeLightInterval = normalizeLightInterval(lightInterval);
        boolean lightLevel = isLightLevel(baseY, y, safeLightInterval);
        Material supportMaterial = lightLevel && !layout.sideLantern()
                ? Material.SHROOMLIGHT
                : Material.STRIPPED_DARK_OAK_LOG;

        Block support = world.getBlockAt(layout.supportX(), y, layout.supportZ());
        ensureBlock(
                support,
                supportMaterial,
                data -> setAxis(data, Axis.Y),
                placer
        );

        /*
         * Dans l'emprise de la carrière, l'échelle attend le poteau en bois :
         * l'accrocher temporairement à la roche la ferait tomber (et produire
         * un objet gratuit) lorsque cette roche sera minée quelques instants
         * plus tard. Le repli 1x1 peut, lui, utiliser la paroi extérieure qui
         * ne sera jamais parcourue par l'itérateur.
         */
        Material resolvedSupport = support.getType();
        boolean timberSupport = resolvedSupport == Material.STRIPPED_DARK_OAK_LOG
                || resolvedSupport == Material.SHROOMLIGHT;
        boolean permanentOuterWall = !layout.contains(
                layout.supportX(),
                layout.supportZ()
        ) && resolvedSupport.isSolid();
        if (timberSupport || permanentOuterWall) {
            Block ladder = world.getBlockAt(layout.ladderX(), y, layout.ladderZ());
            ensureBlock(
                    ladder,
                    Material.LADDER,
                    data -> setFacing(data, layout.ladderFacing()),
                    placer
            );
        }

        if (!lightLevel || !layout.sideLantern() || y + 1 >= world.getMaxHeight()) {
            return;
        }

        Block beam = world.getBlockAt(layout.lightX(), y + 1, layout.lightZ());
        boolean beamReady = ensureBlock(
                beam,
                Material.STRIPPED_SPRUCE_LOG,
                data -> setAxis(data, layout.lightBeamAxis()),
                placer
        );
        if (!beamReady || !beam.getType().isSolid()) {
            return;
        }

        Block lantern = world.getBlockAt(layout.lightX(), y, layout.lightZ());
        ensureBlock(
                lantern,
                Material.LANTERN,
                data -> {
                    if (data instanceof Lantern lanternData) {
                        lanternData.setHanging(true);
                    }
                },
                placer
        );
    }

    /**
     * Empêche l'itérateur de carrière de sélectionner de nouveau un bloc posé
     * par le puits après une reprise sur un ancien checkpoint.
     */
    public static boolean isManagedBlock(Layout layout,
                                         int baseY,
                                         Block block) {
        if (layout == null || block == null || block.getY() > baseY) {
            return false;
        }

        Material material = block.getType();
        if (block.getX() == layout.ladderX()
                && block.getZ() == layout.ladderZ()) {
            return material == Material.LADDER;
        }
        if (block.getX() == layout.supportX()
                && block.getZ() == layout.supportZ()) {
            return material == Material.STRIPPED_DARK_OAK_LOG
                    || material == Material.SHROOMLIGHT;
        }
        return layout.sideLantern()
                && block.getX() == layout.lightX()
                && block.getZ() == layout.lightZ()
                && (material == Material.STRIPPED_SPRUCE_LOG
                || material == Material.LANTERN);
    }

    public static boolean isManagedMaterial(Material material) {
        return material != null && MANAGED_MATERIALS.contains(material);
    }

    static boolean isLightLevel(int baseY, int y, int interval) {
        int safeInterval = normalizeLightInterval(interval);
        long depth = (long) baseY - y;
        return depth > 0L && depth % safeInterval == 0L;
    }

    private static boolean ensureBlock(Block block,
                                       Material material,
                                       Consumer<BlockData> configurer,
                                       PlacementHandler placer) {
        Material current = block.getType();
        if (current == material) {
            configureExisting(block, configurer);
            return true;
        }
        if (!current.isAir() && !isManagedMaterial(current)) {
            return false;
        }
        return placer.place(block, material, configurer);
    }

    private static void configureExisting(Block block,
                                          Consumer<BlockData> configurer) {
        if (configurer == null) {
            return;
        }
        BlockData data = block.getBlockData();
        configurer.accept(data);
        block.setBlockData(data, false);
    }

    private static void setAxis(BlockData data, Axis axis) {
        if (data instanceof Orientable orientable
                && orientable.getAxes().contains(axis)) {
            orientable.setAxis(axis);
        }
    }

    private static void setFacing(BlockData data, BlockFace facing) {
        if (data instanceof Directional directional
                && directional.getFaces().contains(facing)) {
            directional.setFacing(facing);
        }
    }

    private static BlockFace chooseSupportDirection(int minX,
                                                    int maxX,
                                                    int minZ,
                                                    int maxZ,
                                                    int centerX,
                                                    int centerZ) {
        BlockFace best = null;
        long bestRoom = Long.MIN_VALUE;

        BlockFace[] preference = {
                BlockFace.EAST,
                BlockFace.SOUTH,
                BlockFace.WEST,
                BlockFace.NORTH
        };
        for (BlockFace face : preference) {
            long room = switch (face) {
                case EAST -> (long) maxX - centerX;
                case WEST -> (long) centerX - minX;
                case SOUTH -> (long) maxZ - centerZ;
                case NORTH -> (long) centerZ - minZ;
                default -> Long.MIN_VALUE;
            };
            if (room > bestRoom) {
                bestRoom = room;
                best = face;
            }
        }

        /*
         * En 1x1 aucune paroi voisine n'appartient à la sélection. Le poteau
         * reste à l'est et n'est matérialisé que si ce bloc devient de l'air ;
         * la roche naturelle n'est jamais remplacée silencieusement.
         */
        return bestRoom > 0L ? best : BlockFace.EAST;
    }

    private static BlockFace clockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> throw new IllegalArgumentException("Face non horizontale.");
        };
    }

    private static boolean isHorizontal(BlockFace face) {
        return face == BlockFace.NORTH
                || face == BlockFace.SOUTH
                || face == BlockFace.EAST
                || face == BlockFace.WEST;
    }

    private static int checkedCoordinate(long value, String label) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(label + " hors limites entières.");
        }
        return (int) value;
    }
}
