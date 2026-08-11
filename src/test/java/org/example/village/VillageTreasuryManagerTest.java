package org.example.village;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.ArgumentCaptor;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VillageTreasuryManagerTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        /*
         * ItemStack#getItemMeta délègue à l'ItemFactory du serveur. Le serveur
         * minimal MockBukkit rend donc le test identique à l'exécution Paper.
         */
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            MockBukkit.unmock();
        }
    }

    @Test
    void randomPoolIncludesSpawnerPotionsAndTechnicalItems() {
        assertTrue(VillageTreasuryManager.eligibleMaterials()
                .contains(Material.SPAWNER));
        assertTrue(VillageTreasuryManager.eligibleMaterials()
                .contains(Material.POTION));
        assertTrue(VillageTreasuryManager.eligibleMaterials()
                .contains(Material.COMMAND_BLOCK));
        assertFalse(VillageTreasuryManager.eligibleMaterials()
                .stream()
                .anyMatch(Material::isAir));
        assertFalse(VillageTreasuryManager.eligibleMaterials()
                .stream()
                .anyMatch(Material::isLegacy));
    }

    @Test
    void generatedTaxAlwaysRespectsItsOwnStackLimit() {
        VillageTreasurySettings settings =
                new VillageTreasurySettings(
                        true,
                        1,
                        60,
                        20L,
                        512,
                        256,
                        1,
                        1,
                        64,
                        6,
                        true,
                        30
                );
        Random random = new Random(4_321L);

        for (int index = 0; index < 2_000; index++) {
            ItemStack item = VillageTreasuryManager.createRandomTaxItem(
                    random,
                    settings
            );
            assertFalse(item.getType().isAir());
            assertTrue(item.getAmount() >= 1);
            assertTrue(item.getAmount() <= item.getMaxStackSize());
        }
    }

    @Test
    void insertsAStackAtomicallyAcrossSimilarAndEmptySlots() {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{
                new ItemStack(Material.GOLD_INGOT, 60),
                null
        });

        assertTrue(VillageTreasuryManager.insertWholeStack(
                inventory,
                new ItemStack(Material.GOLD_INGOT, 10)
        ));

        ArgumentCaptor<ItemStack[]> captor =
                ArgumentCaptor.forClass(ItemStack[].class);
        verify(inventory).setStorageContents(captor.capture());
        ItemStack[] written = captor.getValue();
        assertEquals(64, written[0].getAmount());
        assertEquals(6, written[1].getAmount());
    }

    @Test
    void leavesTheInventoryUntouchedWhenTheWholeTaxDoesNotFit() {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{
                new ItemStack(Material.DIAMOND, 64)
        });

        assertFalse(VillageTreasuryManager.insertWholeStack(
                inventory,
                new ItemStack(Material.GOLD_INGOT, 1)
        ));
        verify(inventory, never()).setStorageContents(
                org.mockito.ArgumentMatchers.any(ItemStack[].class)
        );
    }
}
