package org.example.mineur;

import org.bukkit.block.Block;

/**
 * Contrat commun des parcours de minage.
 */
public interface MiningIterator {

    /**
     * Nombre maximal de coordonnées inspectées lors d'un appel à {@link #next()}.
     *
     * <p>Cette limite empêche une carrière déjà vide de monopoliser le thread
     * principal pendant plusieurs milliers de blocs en un seul tick.</p>
     */
    int DEFAULT_SCAN_BUDGET = 256;

    Block next();

    boolean hasNext();

    MiningCursor cursor();
}
