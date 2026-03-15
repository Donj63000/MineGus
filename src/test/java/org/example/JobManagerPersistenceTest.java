package org.example;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobManagerPersistenceTest {

    private ServerMock server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            MockBukkit.unmock();
        }
    }

    @Test
    void saveJobsSyncPersistsLatestMinerProgress() {
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

        jobManager.onCommand(player, null, "job", new String[]{"mineur"});
        int gained = jobManager.getXpForBlock(Material.DIAMOND_ORE);
        assertTrue(gained > 0);
        jobManager.grantMiningXp(player, Material.DIAMOND_ORE);
        jobManager.grantMiningXp(player, Material.DIAMOND_ORE);
        jobManager.saveJobsSync();

        File jobsFile = new File(plugin.getDataFolder(), "jobs.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(jobsFile);

        assertEquals("MINEUR", yaml.getString("players." + playerId + ".job"));
        assertEquals(gained * 2L, yaml.getLong("players." + playerId + ".xp"));
    }
}
