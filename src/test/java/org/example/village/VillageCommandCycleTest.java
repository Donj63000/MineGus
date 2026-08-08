package org.example.village;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.example.MinePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VillageCommandCycleTest {

    private ServerMock server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            MockBukkit.unmock();
        }
    }

    @Test
    @Disabled("MockBukkit 1.21 registry support is incomplete for world/player creation in this repository.")
    void villageGenerateThenUndoCleansTrackedBlocksAndEntities() {
        server = MockBukkit.mock();
        MinePlugin plugin = MockBukkit.load(MinePlugin.class);
        PlayerMock player = server.addPlayer();
        WorldMock world = (WorldMock) player.getWorld();
        player.teleport(new Location(world, 0, 64, 0));

        server.dispatchCommand(player, "village");
        server.getScheduler().performTicks(300);

        assertTrue(world.getBlockAt(0, 64, 0).getType() != Material.AIR);

        server.dispatchCommand(player, "village undo");
        server.getScheduler().performTicks(20);

        long tagged = world.getEntities().stream().filter(e -> e.hasMetadata(VillageEntityManager.TAG)).count();
        assertTrue(tagged == 0);
    }
}
