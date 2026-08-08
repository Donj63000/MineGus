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
        /*
         * Les plafonds protègent le thread serveur contre une configuration
         * accidentelle qui demanderait plusieurs centaines de lots ou une
         * terrasse de plusieurs millions de blocs.
         */
        rows = clamp(rows, 3, 9);
        cols = clamp(cols, 3, 11);
        houseSmall = clamp(houseSmall, 7, 13);
        houseBig = clamp(Math.max(houseSmall, houseBig), houseSmall, 15);
        spacing = clamp(spacing, 16, 32);
        roadHalf = clamp(roadHalf, 1, 4);
        plazaSize = makeOdd(clamp(plazaSize, 13, 21));
        houseCountMin = clamp(houseCountMin, 8, 64);
        houseCountMax = clamp(Math.max(houseCountMin, houseCountMax), houseCountMin, 64);
        mainStreetHalf = clamp(mainStreetHalf, 2, 4);
        sideStreetHalf = clamp(sideStreetHalf, 1, 2);
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

    /**
     * Nombre total d'éléments interstitiels, arbres garantis compris.
     *
     * <p>Les valeurs restent bornées par la carte d'occupation : augmenter ce
     * budget densifie le village sans autoriser de collision avec une rue ou
     * une parcelle bâtie.</p>
     */
    public int decorationBudget() {
        int districtCells = rows * cols;
        return switch (decorDensity) {
            case "none", "off" -> 0;
            case "low", "light" -> Math.min(48, 12 + districtCells / 2);
            case "high", "dense" -> Math.min(112, 36 + districtCells * 2);
            default -> Math.min(80, 22 + districtCells);
        };
    }

    /**
     * Réserve une part du budget aux arbres avant le tirage des petits décors.
     * La valeur grandit avec la surface du plan afin que les quartiers ajoutés
     * ne deviennent pas une étendue minérale.
     */
    public int treeBudget() {
        return switch (decorDensity) {
            case "none", "off" -> 0;
            case "low", "light" -> Math.min(8, 2 + (rows + cols) / 3);
            case "high", "dense" -> Math.min(18, 6 + (rows + cols));
            default -> Math.min(13, 4 + (rows + cols) / 2);
        };
    }

    /**
     * Nombre cible de passages couverts. Le planificateur peut en retenir
     * moins si aucune implantation sûre n'est disponible.
     */
    public int passageBudget() {
        return Math.min(8, Math.max(3, (rows + cols) / 2));
    }

    private static int makeOdd(int value) {
        return value % 2 == 0 ? value + 1 : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
