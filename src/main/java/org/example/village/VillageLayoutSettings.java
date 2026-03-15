package org.example.village;

public record VillageLayoutSettings(
        String layoutStyle,
        int rows,
        int cols,
        int houseSmall,
        int houseBig,
        int spacing,
        int roadHalf,
        int plazaSize,
        int houseCountMin,
        int houseCountMax,
        int mainStreetHalf,
        int sideStreetHalf,
        int terrainMaxStep,
        String decorDensity
) {
    public int maxHouseFootprint() {
        return Math.max(houseSmall, houseBig);
    }
}
