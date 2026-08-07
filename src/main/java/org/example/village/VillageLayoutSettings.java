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

    /**
     * Nombre total d'éléments interstitiels, arbres garantis compris.
     *
     * <p>Les valeurs restent bornées par la carte d'occupation : augmenter ce
     * budget densifie le village sans autoriser de collision avec une rue ou
     * une parcelle bâtie.</p>
     */
    public int decorationBudget() {
        return switch (decorDensity) {
            case "none", "off" -> 0;
            case "low", "light" -> 16;
            case "high", "dense" -> 42;
            default -> 28;
        };
    }

    /**
     * Réserve une part du budget aux arbres avant le tirage des petits décors.
     * Sans cette passe, un village pouvait ne recevoir presque aucun arbre
     * malgré une densité élevée à cause du hasard du sélecteur décoratif.
     */
    public int treeBudget() {
        return switch (decorDensity) {
            case "none", "off" -> 0;
            case "low", "light" -> 4;
            case "high", "dense" -> 10;
            default -> 7;
        };
    }

    private static int makeOdd(int value) {
        return value % 2 == 0 ? value + 1 : value;
    }
}
