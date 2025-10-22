package org.example.mineur;

import org.bukkit.block.Block;

/**
 * Contract for iterating over blocks to mine.
 */
public interface MiningIterator {
    Block next();
    boolean hasNext();
    MiningCursor cursor();
}
