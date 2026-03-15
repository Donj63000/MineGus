package org.example;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobManagerHudTest {

    private ServerMock server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            MockBukkit.unmock();
        }
    }

    @Test
    void bossBarAppearsOnlyWhileMiningAndHidesAfterDelay() throws Exception {
        server = MockBukkit.mock();
        MinePlugin plugin = MockBukkit.load(MinePlugin.class);
        JobManager jobManager = plugin.getJobManager();
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack pickaxe = mock(ItemStack.class);
        UUID playerId = UUID.randomUUID();

        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(pickaxe);
        when(pickaxe.getType()).thenReturn(Material.DIAMOND_PICKAXE);
        when(player.getLocation()).thenReturn(new Location(null, 0, 64, 0));

        @SuppressWarnings("unchecked")
        Map<UUID, BossBar> bars = (Map<UUID, BossBar>) field(jobManager, "bars").get(jobManager);

        jobManager.onCommand(player, null, "job", new String[]{"mineur"});
        assertFalse(bars.containsKey(playerId));

        jobManager.grantMiningXp(player, Material.DIAMOND_ORE);
        assertTrue(bars.containsKey(playerId));

        server.getScheduler().performTicks(40);
        jobManager.grantMiningXp(player, Material.DIAMOND_ORE);
        server.getScheduler().performTicks(40);
        assertTrue(bars.containsKey(playerId));

        server.getScheduler().performTicks(40);
        assertFalse(bars.containsKey(playerId));
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
