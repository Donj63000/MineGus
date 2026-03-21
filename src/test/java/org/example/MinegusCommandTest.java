package org.example;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MinegusCommandTest {

    private ServerMock server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            MockBukkit.unmock();
        }
    }

    @Test
    void helpContainsAllCommandFamiliesAndMarkers() {
        String help = plainText(MinegusCommand.buildHelpLines());

        assertTrue(help.contains("/ping"));
        assertTrue(help.contains("/army"));
        assertTrue(help.contains("/mineur"));
        assertTrue(help.contains("/mineur aide"));
        assertTrue(help.contains("/champ"));
        assertTrue(help.contains("/foret"));
        assertTrue(help.contains("/eleveur"));
        assertTrue(help.contains("/village"));
        assertTrue(help.contains("/armure"));
        assertTrue(help.contains("/marchand"));
        assertTrue(help.contains("/job"));
        assertTrue(help.contains("/minegus fix <forestier|golems>"));
        assertTrue(help.contains("[permission]"));
        assertTrue(help.contains("[admin]"));
    }

    @Test
    void minegusWithoutPermissionDisplaysHelp() {
        MinegusCommand command = new MinegusCommand(null);
        CommandSender sender = mock(CommandSender.class);
        List<String> messages = captureMessages(sender);

        command.onCommand(sender, null, "minegus", new String[0]);

        String help = plainText(messages);
        assertTrue(help.contains("Commandes disponibles"));
        assertTrue(help.contains("/ping"));
        assertTrue(help.contains("/marchand"));
        assertTrue(help.contains("/minegus fix <forestier|golems>"));
    }

    @Test
    void fixRequiresAdminPermissionButStillShowsHelp() {
        MinegusCommand command = new MinegusCommand(null);
        CommandSender sender = mock(CommandSender.class);
        List<String> messages = captureMessages(sender);
        when(sender.hasPermission(MinegusCommand.ADMIN_PERMISSION)).thenReturn(false);

        command.onCommand(sender, null, "minegus", new String[]{"fix", "golems"});

        String text = plainText(messages);
        assertTrue(text.contains("Tu n'as pas la permission"));
        assertTrue(text.contains("/minegus fix <forestier|golems>"));
        assertTrue(text.contains("Commandes disponibles"));
    }

    @Test
    void pluginYmlLeavesMinegusCommandPublic() {
        server = MockBukkit.mock();
        MinePlugin plugin = MockBukkit.load(MinePlugin.class);

        assertNotNull(plugin.getCommand("minegus"));
        assertNull(plugin.getCommand("minegus").getPermission());
    }

    private static List<String> captureMessages(CommandSender sender) {
        List<String> messages = new ArrayList<>();
        doAnswer(invocation -> {
            messages.add(invocation.getArgument(0));
            return null;
        }).when(sender).sendMessage(anyString());
        return messages;
    }

    private static String plainText(List<String> lines) {
        return ChatColor.stripColor(String.join("\n", lines));
    }
}
