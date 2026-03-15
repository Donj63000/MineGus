package org.example;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobManagerManualXpTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private ServerMock server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            MockBukkit.unmock();
        }
    }

    @Test
    void grantsXpOnlyForManualPickaxeMiningAndLevelsUpAtConfiguredThreshold() {
        server = MockBukkit.mock();
        MinePlugin plugin = MockBukkit.load(MinePlugin.class);
        JobManager jobManager = plugin.getJobManager();

        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        UUID playerId = UUID.randomUUID();

        AtomicReference<ItemStack> heldItem = new AtomicReference<>(mockItem(Material.DIAMOND_PICKAXE));
        List<String> actionbars = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        AtomicReference<String> title = new AtomicReference<>();
        AtomicReference<String> subtitle = new AtomicReference<>();

        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenAnswer(invocation -> heldItem.get());
        when(player.getLocation()).thenReturn(new Location(null, 0, 64, 0));

        doAnswer(invocation -> {
            Component component = invocation.getArgument(0);
            actionbars.add(PLAIN.serialize(component));
            return null;
        }).when(player).sendActionBar(any(Component.class));
        doAnswer(invocation -> {
            messages.add(invocation.getArgument(0));
            return null;
        }).when(player).sendMessage(anyString());
        doAnswer(invocation -> {
            title.set(invocation.getArgument(0));
            subtitle.set(invocation.getArgument(1));
            return null;
        }).when(player).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());

        assertEquals(0, jobManager.grantMiningXp(player, Material.DIAMOND_ORE));

        jobManager.onCommand(player, null, "job", new String[]{"mineur"});

        heldItem.set(mockItem(Material.STICK));
        assertEquals(0, jobManager.grantMiningXp(player, Material.DIAMOND_ORE));

        heldItem.set(mockItem(Material.DIAMOND_PICKAXE));
        assertEquals(0, jobManager.grantMiningXp(player, Material.DIRT));

        for (int i = 0; i < 20; i++) {
            assertEquals(10, jobManager.grantMiningXp(player, Material.DIAMOND_ORE));
        }

        assertEquals(200L, jobManager.getXpForPlayer(playerId));
        assertEquals(2, jobManager.getLevelForPlayer(playerId));
        assertTrue(actionbars.stream().anyMatch(line -> line.contains("+10 XP Mineur")));
        assertTrue(actionbars.stream().anyMatch(line -> line.contains("Niv. 2")));
        assertTrue(messages.stream().anyMatch(line -> line.contains("niveau 2")));
        assertEquals("Niveau 2", stripColors(title.get()));
        assertTrue(stripColors(subtitle.get()).contains("+1"));
    }

    private static ItemStack mockItem(Material material) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        return stack;
    }

    private static String stripColors(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\u00A7.", "");
    }
}
