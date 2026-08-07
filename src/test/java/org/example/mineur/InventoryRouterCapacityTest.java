package org.example.mineur;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryRouterCapacityTest {

    @Test
    void countsDoubleChestCapacityOnlyOnceAcrossDifferentWrappers() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());

        Container left = containerHalf(world, 10, 64, 10);
        Container right = containerHalf(world, 11, 64, 10);

        Inventory firstWrapper = oneSlotInventory(doubleChest(left, right));
        Inventory secondWrapper = oneSlotInventory(doubleChest(left, right));

        Block firstTarget = mock(Block.class);
        Container firstTargetState = mock(Container.class);
        when(firstTarget.getState()).thenReturn(firstTargetState);
        when(firstTargetState.getInventory()).thenReturn(firstWrapper);

        Block secondTarget = mock(Block.class);
        Container secondTargetState = mock(Container.class);
        when(secondTarget.getState()).thenReturn(secondTargetState);
        when(secondTargetState.getInventory()).thenReturn(secondWrapper);

        /*
         * Une seule case de 64 est disponible. Sans identité canonique du
         * double coffre, les deux wrappers feraient croire que 128 places sont
         * libres et autoriseraient à tort la casse d'un bloc donnant 65 objets.
         */
        ItemStack oversizedDrop = mock(ItemStack.class);
        when(oversizedDrop.getType()).thenReturn(Material.STONE);
        when(oversizedDrop.getAmount()).thenReturn(65);
        when(oversizedDrop.getMaxStackSize()).thenReturn(64);
        when(oversizedDrop.clone()).thenReturn(oversizedDrop);

        InventoryRouter router = new InventoryRouter(List.of(firstTarget, secondTarget));

        assertFalse(router.canFitAll(List.of(oversizedDrop)));
    }

    @Test
    void rejectsDoubleChestWhenOneHalfIsNotAuthorized() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());

        Container authorized = containerHalf(world, 20, 64, 20);
        Container foreign = containerHalf(world, 21, 64, 20);
        DoubleChest holder = doubleChest(authorized, foreign);
        Inventory combined = oneSlotInventory(holder);

        Block target = authorized.getBlock();
        when(target.getState()).thenReturn(authorized);
        when(authorized.getInventory()).thenReturn(combined);

        InventoryRouter router = new InventoryRouter(
                List.of(target),
                container -> container == authorized
        );

        /*
         * La cible elle-même est signée, mais l'inventaire combiné contient une
         * moitié étrangère. Le routeur doit refuser l'ensemble avant tout dépôt.
         */
        assertFalse(router.hasTargets());
    }

    private Inventory oneSlotInventory(DoubleChest holder) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn(holder);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{null});
        when(inventory.getMaxStackSize()).thenReturn(64);
        return inventory;
    }

    private DoubleChest doubleChest(Container left, Container right) {
        DoubleChest doubleChest = mock(DoubleChest.class);
        when(doubleChest.getLeftSide()).thenReturn(left);
        when(doubleChest.getRightSide()).thenReturn(right);
        return doubleChest;
    }

    private Container containerHalf(World world, int x, int y, int z) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);

        Container container = mock(Container.class);
        when(container.getBlock()).thenReturn(block);
        return container;
    }
}
