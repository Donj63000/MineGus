package org.example.progression;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.example.MinePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalProgressionPersistenceTest {

    private ServerMock server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            MockBukkit.unmock();
        }
    }

    @Test
    void grantedExperienceIsClampedLeveledAndPersistedByUuid() {
        server = MockBukkit.mock();
        MinePlugin plugin = MockBukkit.load(MinePlugin.class);
        GlobalProgressionManager progression =
                plugin.getGlobalProgressionManager();

        UUID playerId = UUID.randomUUID();
        World world = mock(World.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("TesteurProgression");
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(player.getLocation())
                .thenReturn(new Location(world, 0.0D, 64.0D, 0.0D));

        long gained = progression.grantExperience(
                player,
                200L,
                "Test");
        progression.saveProgressSync();

        assertEquals(200L, gained);
        assertEquals(200L, progression.getExperience(playerId));
        assertEquals(2, progression.getLevel(playerId));

        File dataFile =
                new File(plugin.getDataFolder(), "progression.yml");
        assertTrue(dataFile.isFile());

        YamlConfiguration yaml =
                YamlConfiguration.loadConfiguration(dataFile);
        assertEquals(2, yaml.getInt("schema-version"));
        String root = "players." + playerId;
        assertEquals("TesteurProgression",
                yaml.getString(root + ".name"));
        assertEquals(200L, yaml.getLong(root + ".xp"));
    }
}
