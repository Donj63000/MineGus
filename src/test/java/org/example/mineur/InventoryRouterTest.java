package org.example.mineur;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import be.seeseemelk.mockbukkit.block.BlockMock;
import be.seeseemelk.mockbukkit.block.state.ChestMock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Disabled("MockBukkit 1.21.x ne fournit pas encore les registres nécessaires (Material/BlockType)")
class InventoryRouterTest {

    @Test
    void depositSpreadsItemsAcrossContainersWhenFirstIsFull() {
        Block firstChestBlock = chestBlock(0, 65, 0);
        Chest firstChest = (Chest) firstChestBlock.getState();
        fillCompletely(firstChest);

        Block secondChestBlock = chestBlock(1, 65, 0);

        InventoryRouter router = new InventoryRouter(List.of(firstChestBlock, secondChestBlock));
        ItemStack deposit = new ItemStack(Material.DIRT, 32);

        List<ItemStack> leftovers = router.deposit(List.of(deposit));

        assertTrue(leftovers.isEmpty(), "All items should have been stored");
        Chest secondChest = (Chest) secondChestBlock.getState();
        ItemStack stored = secondChest.getInventory().getItem(0);
        assertNotNull(stored);
        assertEquals(Material.DIRT, stored.getType());
        assertEquals(32, stored.getAmount());
    }

    @Test
    void depositReturnsLeftoversWhenContainersAreFull() {
        Block firstChestBlock = chestBlock(2, 65, 0);
        fillCompletely((Chest) firstChestBlock.getState());

        Block secondChestBlock = chestBlock(3, 65, 0);
        fillCompletely((Chest) secondChestBlock.getState());

        InventoryRouter router = new InventoryRouter(List.of(firstChestBlock, secondChestBlock));
        ItemStack deposit = new ItemStack(Material.IRON_ORE, 48);

        List<ItemStack> leftovers = router.deposit(List.of(deposit));

        assertEquals(1, leftovers.size());
        ItemStack leftover = leftovers.get(0);
        assertEquals(Material.IRON_ORE, leftover.getType());
        assertEquals(48, leftover.getAmount());
    }

    @Test
    void dropOnGroundUsesTargetLocationOffset() {
        Block chestBlock = chestBlock(5, 70, -3);
        InventoryRouter router = new InventoryRouter(List.of(chestBlock));

        World worldMock = mock(World.class);
        when(worldMock.dropItem(any(Location.class), any(ItemStack.class))).thenReturn(null);

        ItemStack diamonds = new ItemStack(Material.DIAMOND, 2);
        router.dropOnGround(worldMock, null, List.of(diamonds));

        ArgumentCaptor<Location> locationCaptor = ArgumentCaptor.forClass(Location.class);
        verify(worldMock).dropItem(locationCaptor.capture(), eq(diamonds));

        Location dropLocation = locationCaptor.getValue();
        assertEquals(chestBlock.getX() + 0.5, dropLocation.getX());
        assertEquals(chestBlock.getY() + 1.1, dropLocation.getY());
        assertEquals(chestBlock.getZ() + 0.5, dropLocation.getZ());
    }

    @Test
    void dropOnGroundUsesFallbackWhenNoTargets() {
        InventoryRouter router = new InventoryRouter(null);
        World worldMock = mock(World.class);
        when(worldMock.dropItem(any(Location.class), any(ItemStack.class))).thenReturn(null);

        Location fallback = new Location(worldMock, 8.0, 64.0, 4.0);
        ItemStack carrots = new ItemStack(Material.CARROT, 12);

        router.dropOnGround(worldMock, fallback, List.of(carrots));

        ArgumentCaptor<Location> locationCaptor = ArgumentCaptor.forClass(Location.class);
        verify(worldMock).dropItem(locationCaptor.capture(), eq(carrots));

        Location dropLocation = locationCaptor.getValue();
        assertEquals(fallback.getX(), dropLocation.getX());
        assertEquals(fallback.getY(), dropLocation.getY());
        assertEquals(fallback.getZ(), dropLocation.getZ());
        assertEquals(64.0, fallback.getY(), "Fallback location must remain unchanged");
    }

    @Test
    void dropOnGroundSkipsInvalidItems() {
        InventoryRouter router = new InventoryRouter(null);
        World worldMock = mock(World.class);
        when(worldMock.dropItem(any(Location.class), any(ItemStack.class))).thenReturn(null);
        when(worldMock.getSpawnLocation()).thenReturn(new Location(worldMock, 0, 64, 0));

        List<ItemStack> items = List.of(null, new ItemStack(Material.COAL, 0));
        router.dropOnGround(worldMock, null, items);

        verify(worldMock, never()).dropItem(any(Location.class), any(ItemStack.class));
    }

    private Block chestBlock(int x, int y, int z) {
        return new StaticChestBlock(new Location(null, x, y, z));
    }

    private static final class StaticChestBlock extends BlockMock {
        private final ChestMock chest;

        private StaticChestBlock(Location location) {
            super(Material.CHEST, location);
            this.chest = new ChestMock(Material.CHEST);
        }

        @Override
        public ChestMock getState() {
            return chest;
        }
    }

    private void fillCompletely(Chest chest) {
        for (int i = 0; i < chest.getInventory().getSize(); i++) {
            chest.getInventory().setItem(i, new ItemStack(Material.STONE, 64));
        }
    }
}
