package org.example.mineur;

/**
 * Supported mining layouts. Additional iterators can be added later.
 */
public enum MiningPattern {
    QUARRY,     // couche par couche (par défaut)
    BRANCH,     // galerie 2x1 tous les N blocs (à faire après)
    TUNNEL,     // couloir directionnel
    VEIN_FIRST  // vidage des veines trouvées (BFS local)
}
