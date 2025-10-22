package org.example.mineur;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Distributes drops across the configured containers and drops the remainder if
 * everything is full.
 */
public final class InventoryRouter {

    private final List<Block> targets = new ArrayList<>();
    private int roundRobin = 0;

    public InventoryRouter(List<Block> blocks) {
        if (blocks == null) {
            return;
        }
        for (Block block : blocks) {
            if (block != null) {
                targets.add(block);
            }
        }
    }

    public boolean hasTargets() {
        return !targets.isEmpty();
    }

    /**
     * Attempts to deposit the provided items inside the targets.
     *
     * @return items that could not fit.
     */
    public List<ItemStack> deposit(List<ItemStack> items) {
        List<ItemStack> pending = new ArrayList<>();
        if (items != null) {
            for (ItemStack item : items) {
                if (item != null && item.getAmount() > 0) {
                    pending.add(item.clone());
                }
            }
        }
        if (pending.isEmpty() || targets.isEmpty()) {
            return pending;
        }

        int attempts = 0;
        while (!pending.isEmpty() && attempts < targets.size()) {
            Block block = targets.get(roundRobin);
            roundRobin = (roundRobin + 1) % targets.size();
            attempts++;

            if (!(block.getState() instanceof Container container)) {
                continue;
            }
            Inventory inventory = container.getInventory();
            List<ItemStack> leftovers = new ArrayList<>();
            for (ItemStack stack : pending) {
                Map<Integer, ItemStack> result = inventory.addItem(stack);
                if (!result.isEmpty()) {
                    leftovers.addAll(result.values());
                }
            }
            pending = leftovers;
        }

        return pending;
    }

    public void dropOnGround(World world, Location fallback, List<ItemStack> items) {
        if (world == null || items == null || items.isEmpty()) {
            return;
        }
        Location dropLocation;
        if (!targets.isEmpty()) {
            dropLocation = targets.get(0).getLocation().add(0.5, 1.1, 0.5);
        } else if (fallback != null) {
            dropLocation = fallback.clone();
        } else {
            dropLocation = world.getSpawnLocation().add(0, 1.0, 0);
        }
        for (ItemStack item : items) {
            if (item == null || item.getAmount() <= 0) {
                continue;
            }
            world.dropItem(dropLocation, item);
        }
    }
}
