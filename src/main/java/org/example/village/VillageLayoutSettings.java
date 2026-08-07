package org.example.village;

import java.util.Locale;

/**
 * Paramètres normalisés utilisés par le planificateur du village.
 *
 * <p>La normalisation est volontairement centralisée ici afin qu'une ancienne
 * configuration ou une valeur saisie trop petite ne produise jamais de rues
 * écrasées, de place paire ou de bâtiments qui se chevauchent.</p>
 */
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
    public VillageLayoutSettings {
        layoutStyle = layoutStyle == null || layoutStyle.isBlank()
                ? "semi_organic"
                : layoutStyle.trim().toLowerCase(Locale.ROOT);
        rows = Math.max(3, rows);
        cols = Math.max(3, cols);
        houseSmall = Math.max(7, houseSmall);
        houseBig = Math.max(houseSmall, houseBig);
        spacing = Math.max(16, spacing);
        roadHalf = Math.max(1, roadHalf);
        plazaSize = makeOdd(Math.max(13, plazaSize));
        houseCountMin = Math.max(8, houseCountMin);
        houseCountMax = Math.max(houseCountMin, houseCountMax);
        mainStreetHalf = Math.max(2, mainStreetHalf);
        sideStreetHalf = Math.max(1, sideStreetHalf);
        terrainMaxStep = Math.max(0, Math.min(3, terrainMaxStep));
        decorDensity = decorDensity == null || decorDensity.isBlank()
                ? "medium"
                : decorDensity.trim().toLowerCase(Locale.ROOT);
    }

    public int maxHouseFootprint() {
        return Math.max(houseSmall, houseBig);
    }

    /**
     * Espacement entre deux axes de quartier.
     *
     * <p>L'ancienne formule additionnait l'empreinte et {@code spacing}, ce qui
     * transformait la valeur 20 en pas de 31 blocs. La valeur de configuration
     * représente désormais bien un pas centre-à-centre minimal.</p>
     */
    public int effectiveLotSpacing() {
        return Math.max(maxHouseFootprint() + 7, spacing);
    }

    public int effectivePlazaSize() {
        return plazaSize;
    }

    public int decorationBudget() {
        return switch (decorDensity) {
            case "none", "off" -> 0;
            case "low", "light" -> 12;
            case "high", "dense" -> 34;
            default -> 22;
        };
    }

    private static int makeOdd(int value) {
        return value % 2 == 0 ? value + 1 : value;
    }
}
