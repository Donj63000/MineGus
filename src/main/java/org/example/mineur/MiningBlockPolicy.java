package org.example.mineur;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Politique unique de sélection des blocs pouvant être cassés par un mineur.
 *
 * <p>Centraliser cette règle évite qu'un mode (carrière, branche, tunnel ou
 * veine) détruise des blocs techniques qu'un autre mode protège. Les blocs
 * possédant un état persistant (coffres, panneaux, spawners, ruches, etc.) sont
 * volontairement exclus afin de ne jamais supprimer silencieusement leur
 * contenu ou leurs données.</p>
 */
public final class MiningBlockPolicy {

    private static final Set<Material> PROTECTED_MATERIALS = EnumSet.of(
            Material.BEDROCK,
            Material.BARRIER,
            Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK,
            Material.STRUCTURE_BLOCK,
            Material.STRUCTURE_VOID,
            Material.JIGSAW,
            Material.END_PORTAL,
            Material.END_PORTAL_FRAME,
            Material.END_GATEWAY,
            Material.NETHER_PORTAL,
            Material.MOVING_PISTON,
            Material.LIGHT,
            Material.REINFORCED_DEEPSLATE,
            Material.WATER,
            Material.LAVA,
            Material.BUBBLE_COLUMN,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.POWDER_SNOW
    );

    private MiningBlockPolicy() {
        // Classe utilitaire.
    }

    /**
     * Filtre rapide utilisable par les itérateurs, sans créer de BlockState.
     */
    public static boolean isCandidate(Material material) {
        return material != null
                && material.isBlock()
                && !material.isAir()
                && !PROTECTED_MATERIALS.contains(material);
    }

    /**
     * Vérification finale juste avant la casse.
     */
    public static boolean isMineable(Block block) {
        if (block == null || !isCandidate(block.getType()) || block.isLiquid()) {
            return false;
        }

        BlockState state = block.getState();
        return !(state instanceof TileState);
    }

    /**
     * Calcule les drops comme si le bloc avait été miné avec l'outil virtuel.
     */
    public static List<ItemStack> computeDrops(Block block, ItemStack tool) {
        List<ItemStack> drops = new ArrayList<>();
        if (block == null || !isMineable(block)) {
            return drops;
        }

        Collection<ItemStack> computed = tool == null
                ? block.getDrops()
                : block.getDrops(tool);
        for (ItemStack item : computed) {
            if (item != null && item.getAmount() > 0 && item.getType() != Material.AIR) {
                drops.add(item.clone());
            }
        }
        return drops;
    }

    public static boolean isOre(Material material) {
        if (material == null) {
            return false;
        }
        return material.name().endsWith("_ORE") || material == Material.ANCIENT_DEBRIS;
    }
}
