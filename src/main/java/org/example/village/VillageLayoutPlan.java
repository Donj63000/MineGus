package org.example.village;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.util.List;
import java.util.Map;

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
        public boolean horizontal() {
            return startZ == endZ;
        }
    }

    public record Bounds(int minX, int maxX, int minZ, int maxZ) {
        public int centerX() {
            return (minX + maxX) / 2;
        }

        public int centerZ() {
            return (minZ + maxZ) / 2;
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
        public boolean isHouse() {
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

        public boolean overlapsWithGap(LotPlan other, int gap) {
            return this.minX() - gap <= other.maxX()
                    && this.maxX() + gap >= other.minX()
                    && this.minZ() - gap <= other.maxZ()
                    && this.maxZ() + gap >= other.minZ();
        }
    }
}
