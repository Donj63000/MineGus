package org.example.mineur;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MiningBlockPolicyTest {

    @Test
    void rejectsAirLiquidsAndTechnicalBlocksAtIteratorLevel() {
        assertFalse(MiningBlockPolicy.isCandidate(Material.AIR));
        assertFalse(MiningBlockPolicy.isCandidate(Material.WATER));
        assertFalse(MiningBlockPolicy.isCandidate(Material.LAVA));
        assertFalse(MiningBlockPolicy.isCandidate(Material.BEDROCK));
        assertFalse(MiningBlockPolicy.isCandidate(Material.END_PORTAL_FRAME));
        assertTrue(MiningBlockPolicy.isCandidate(Material.STONE));
        assertTrue(MiningBlockPolicy.isCandidate(Material.DIAMOND_ORE));
    }

    @Test
    void rejectsPersistentTileStatesAtFinalValidation() {
        Block chest = mock(Block.class);
        when(chest.getType()).thenReturn(Material.CHEST);
        when(chest.isLiquid()).thenReturn(false);
        when(chest.getState()).thenReturn(mock(TileState.class));

        Block stone = mock(Block.class);
        when(stone.getType()).thenReturn(Material.STONE);
        when(stone.isLiquid()).thenReturn(false);
        when(stone.getState()).thenReturn(mock(BlockState.class));

        assertFalse(MiningBlockPolicy.isMineable(chest));
        assertTrue(MiningBlockPolicy.isMineable(stone));
    }

    @Test
    void recognizesOverworldNetherAndAncientOres() {
        assertTrue(MiningBlockPolicy.isOre(Material.DEEPSLATE_DIAMOND_ORE));
        assertTrue(MiningBlockPolicy.isOre(Material.NETHER_QUARTZ_ORE));
        assertTrue(MiningBlockPolicy.isOre(Material.ANCIENT_DEBRIS));
        assertFalse(MiningBlockPolicy.isOre(Material.DEEPSLATE));
    }
}
