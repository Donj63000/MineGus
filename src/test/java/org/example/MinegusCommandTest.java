package org.example;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(help.contains("/tuto"));
        assertTrue(help.contains("/army"));
        assertTrue(help.contains("/garde"));
        assertTrue(help.contains("/mineur"));
        assertTrue(help.contains("/mineur aide"));
        assertTrue(help.contains("/champ"));
        assertTrue(help.contains("/foret"));
        assertTrue(help.contains("/eleveur"));
        assertTrue(help.contains("/village"));
        assertTrue(help.contains("/armure"));
        assertTrue(help.contains("/marchand"));
        assertTrue(help.contains("/niveau"));
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
        assertTrue(help.contains("/tuto"));
        assertTrue(help.contains("/garde"));
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
    void pluginYmlRegistersGuardCommandAndLeavesMinegusPublic() {
        server = MockBukkit.mock();
        MinePlugin plugin = MockBukkit.load(MinePlugin.class);

        assertNotNull(plugin.getCommand("minegus"));
        assertNull(plugin.getCommand("minegus").getPermission());
        assertNotNull(plugin.getCommand("garde"));
        assertEquals("mineplugin.garde.use", plugin.getCommand("garde").getPermission());
        assertNotNull(plugin.getCommand("tuto"));
        assertEquals("mineplugin.tuto.use", plugin.getCommand("tuto").getPermission());
        assertNotNull(plugin.getCommand("niveau"));
        assertEquals("mineplugin.army.use", plugin.getCommand("army").getPermission());
        assertEquals("mineplugin.champ.use", plugin.getCommand("champ").getPermission());
        assertEquals("mineplugin.eleveur.use", plugin.getCommand("eleveur").getPermission());
        assertEquals("mineplugin.village.use", plugin.getCommand("village").getPermission());
        assertEquals("mineplugin.armure.use", plugin.getCommand("armure").getPermission());
        /*
         * /marchand possède plusieurs permissions selon la sous-commande :
         * le descripteur global doit donc rester public et laisser l'exécuteur
         * effectuer le contrôle précis.
         */
        assertNull(plugin.getCommand("marchand").getPermission());
        assertNotNull(plugin.getGlobalProgressionManager());
        Permission progressionAdmin =
                server.getPluginManager().getPermission(
                        "mineplugin.progression.admin");
        assertNotNull(progressionAdmin);
        assertEquals(PermissionDefault.OP, progressionAdmin.getDefault());
        Permission guardPermission = server.getPluginManager().getPermission("mineplugin.garde.use");
        assertNotNull(guardPermission);
        assertEquals(PermissionDefault.TRUE, guardPermission.getDefault());
        Permission tutorialPermission = server.getPluginManager().getPermission("mineplugin.tuto.use");
        assertNotNull(tutorialPermission);
        assertEquals(PermissionDefault.TRUE, tutorialPermission.getDefault());

        for (String permissionName : List.of(
                "mineplugin.army.use",
                "mineplugin.champ.use",
                "mineplugin.champ.admin",
                "mineplugin.eleveur.use",
                "mineplugin.eleveur.admin",
                "mineplugin.village.use",
                "mineplugin.village.admin",
                "mineplugin.armure.use")) {
            Permission permission = server.getPluginManager().getPermission(permissionName);
            assertNotNull(permission, () -> "Permission absente : " + permissionName);
            assertEquals(PermissionDefault.OP, permission.getDefault(), permissionName);
        }
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
