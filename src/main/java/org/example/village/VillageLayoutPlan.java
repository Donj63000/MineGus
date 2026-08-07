package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.util.List;
import java.util.Map;

/**
 * Modèle immuable du village avant toute écriture dans le monde.
 *
 * <p>Le plan contient à la fois la voirie, les emprises bâties et les réserves
 * architecturales. Les réserves incluent notamment les ailes latérales des
 * grandes maisons ; elles permettent au planificateur de prévenir les
 * collisions avant la construction.</p>
 */
public record VillageLayoutPlan(
        List<LotPlan> lots,
        List<StreetPlan> streets,
        Bounds bounds,
        Map<String, Location> anchors,
        int houseCount,
        List<LandmarkType> landmarks
) {
    public enum LotRole {
        CHURCH,
        FORGE,
        INN,
        BAKERY,
        HOUSE_SINGLE,
        HOUSE_TWO_STORY,
        FARM,
        PEN,
        DECOR,
        MARKET,
        GREEN,
        SERVICE_YARD
    }

    public enum LandmarkType {
        STATUE,
        GARDEN,
        CHERRY
    }

    public enum StreetType {
        MAIN,
        SIDE,
        FOOTPATH
    }

    public enum HouseArchetype {
        COTTAGE,
        TOWNHOUSE,
        FAMILY_HOUSE,
        WORKSHOP_HOUSE
    }

    public enum RoofStyle {
        GABLE,
        HIP,
        OFFSET_GABLE,
        SHED
    }

    public enum YardStyle {
        FLOWERS,
        WOODPILE,
        FENCED,
        KITCHEN_GARDEN
    }

    public record HouseSpec(
            HouseArchetype archetype,
            int footprintWidth,
            int footprintDepth,
            int wallHeight,
            boolean twoStory,
            RoofStyle roofStyle,
            int frontSetback,
            int lateralOffset,
            Material accentMaterial,
            int facadeVariant,
            int interiorVariant,
            boolean hasPorch,
            boolean hasDormer,
            int foundationStep,
            YardStyle yardStyle
    ) { }

    public record StreetPlan(
            int startX,
            int startZ,
            int endX,
            int endZ,
            StreetType type,
            int halfWidth
    ) {
        public StreetPlan {
            if (startX != endX && startZ != endZ) {
                throw new IllegalArgumentException("Une rue doit être orthogonale.");
            }
            type = type == null ? StreetType.SIDE : type;
            halfWidth = Math.max(0, halfWidth);
        }

        public boolean horizontal() {
            return startZ == endZ;
        }

        public int minX() {
            return Math.min(startX, endX);
        }

        public int maxX() {
            return Math.max(startX, endX);
        }

        public int minZ() {
            return Math.min(startZ, endZ);
        }

        public int maxZ() {
            return Math.max(startZ, endZ);
        }

        /**
         * Indique si une cellule appartient à la chaussée, avec une marge
         * optionnelle utilisée par la décoration et les tests de raccordement.
         */
        public boolean contains(int x, int z, int extraWidth) {
            int extra = Math.max(0, extraWidth);
            if (horizontal()) {
                return x >= minX() - extra
                        && x <= maxX() + extra
                        && Math.abs(z - startZ) <= halfWidth + extra;
            }
            return z >= minZ() - extra
                    && z <= maxZ() + extra
                    && Math.abs(x - startX) <= halfWidth + extra;
        }
    }

    public record Bounds(int minX, int maxX, int minZ, int maxZ) {
        public Bounds {
            if (minX > maxX || minZ > maxZ) {
                throw new IllegalArgumentException("Bornes de village invalides.");
            }
        }

        public int centerX() {
            return (minX + maxX) / 2;
        }

        public int centerZ() {
            return (minZ + maxZ) / 2;
        }

        public int width() {
            return maxX - minX + 1;
        }

        public int depth() {
            return maxZ - minZ + 1;
        }

        public Bounds expand(int amount) {
            int safe = Math.max(0, amount);
            return new Bounds(minX - safe, maxX + safe, minZ - safe, maxZ + safe);
        }
    }

    public record LotPlan(
            int row,
            int col,
            int centerX,
            int centerZ,
            LotRole role,
            BlockFace facing,
            int buildX,
            int buildZ,
            int footprintWidth,
            int footprintDepth,
            int frontageX,
            int frontageZ,
            HouseSpec houseSpec,
            int terraceY,
            int yardDepth,
            boolean cornerLot
    ) {
        private static final int WING_PROJECTION = 3;

        public LotPlan {
            role = role == null ? LotRole.DECOR : role;
            facing = isHorizontalFacing(facing) ? facing : BlockFace.SOUTH;
            footprintWidth = Math.max(1, footprintWidth);
            footprintDepth = Math.max(1, footprintDepth);
            terraceY = Math.max(0, terraceY);
            yardDepth = Math.max(0, yardDepth);
        }

        /**
         * Une maison au sens démographique est uniquement un logement.
         * L'auberge et la boulangerie utilisent aussi un {@link HouseSpec},
         * mais ne doivent pas gonfler le nombre d'habitations.
         */
        public boolean isHouse() {
            return role == LotRole.HOUSE_SINGLE || role == LotRole.HOUSE_TWO_STORY;
        }

        public boolean hasBuildingSpec() {
            return houseSpec != null;
        }

        public boolean isBuildable() {
            return role != LotRole.GREEN && role != LotRole.DECOR;
        }

        public int minX() {
            return buildX;
        }

        public int maxX() {
            return buildX + footprintWidth - 1;
        }

        public int minZ() {
            return buildZ;
        }

        public int maxZ() {
            return buildZ + footprintDepth - 1;
        }

        /**
         * Côté choisi pour l'aile secondaire des maisons familiales et
         * ateliers. La parité de variante produit de la diversité tout en
         * restant déterministe.
         */
        public BlockFace wingSide() {
            if (!hasWing()) {
                return BlockFace.SELF;
            }
            return houseSpec.facadeVariant() % 2 == 0
                    ? VillageStyle.leftOf(facing)
                    : VillageStyle.rightOf(facing);
        }

        public boolean hasWing() {
            return houseSpec != null
                    && (houseSpec.archetype() == HouseArchetype.FAMILY_HOUSE
                    || houseSpec.archetype() == HouseArchetype.WORKSHOP_HOUSE);
        }

        public int reservedMinX() {
            BlockFace wing = wingSide();
            return minX() + Math.min(0, wing.getModX() * WING_PROJECTION);
        }

        public int reservedMaxX() {
            BlockFace wing = wingSide();
            return maxX() + Math.max(0, wing.getModX() * WING_PROJECTION);
        }

        public int reservedMinZ() {
            BlockFace wing = wingSide();
            return minZ() + Math.min(0, wing.getModZ() * WING_PROJECTION);
        }

        public int reservedMaxZ() {
            BlockFace wing = wingSide();
            return maxZ() + Math.max(0, wing.getModZ() * WING_PROJECTION);
        }

        public int siteMinX() {
            return reservedMinX() - yardDepth;
        }

        public int siteMaxX() {
            return reservedMaxX() + yardDepth;
        }

        public int siteMinZ() {
            return reservedMinZ() - yardDepth;
        }

        public int siteMaxZ() {
            return reservedMaxZ() + yardDepth;
        }

        public int doorX() {
            return switch (facing) {
                case EAST -> maxX();
                case WEST -> minX();
                case NORTH, SOUTH -> buildX + footprintWidth / 2;
                default -> buildX + footprintWidth / 2;
            };
        }

        public int doorZ() {
            return switch (facing) {
                case NORTH -> minZ();
                case SOUTH -> maxZ();
                case EAST, WEST -> buildZ + footprintDepth / 2;
                default -> buildZ + footprintDepth / 2;
            };
        }

        public int frontStepX() {
            return doorX() + facing.getModX();
        }

        public int frontStepZ() {
            return doorZ() + facing.getModZ();
        }

        public boolean overlaps(LotPlan other) {
            return this.minX() <= other.maxX()
                    && this.maxX() >= other.minX()
                    && this.minZ() <= other.maxZ()
                    && this.maxZ() >= other.minZ();
        }

        /**
         * Collision entre les enveloppes architecturales réelles. Contrairement
         * à l'ancienne implémentation, l'aile d'une maison est prise en compte.
         */
        public boolean overlapsWithGap(LotPlan other, int gap) {
            int safeGap = Math.max(0, gap);
            return this.reservedMinX() - safeGap <= other.reservedMaxX()
                    && this.reservedMaxX() + safeGap >= other.reservedMinX()
                    && this.reservedMinZ() - safeGap <= other.reservedMaxZ()
                    && this.reservedMaxZ() + safeGap >= other.reservedMinZ();
        }

        private static boolean isHorizontalFacing(BlockFace face) {
            return face == BlockFace.NORTH
                    || face == BlockFace.SOUTH
                    || face == BlockFace.EAST
                    || face == BlockFace.WEST;
        }
    }
}
