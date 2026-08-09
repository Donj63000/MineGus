package org.example.forestry2;

import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.example.MinePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ForestryModuleTest {

    private ServerMock server;

    @AfterEach
    void nettoyerServeur() {
        ForestryModule.shutdown();
        SharedChunkTickets.clearForTests();
        if (server != null) {
            MockBukkit.unmock();
        }
    }

    @Test
    void jeLieLesDeuxCommandesAuMemeAdaptateurSansDupliquerLeMoteur() {
        server = MockBukkit.mock();
        MinePlugin plugin = MockBukkit.load(MinePlugin.class);

        assertNotNull(plugin.getForet());
        var adapter = ForestryModule.activeExecutorForTests();
        assertNotNull(adapter);

        PluginCommand foret = plugin.getCommand("foret");
        PluginCommand bucheron = plugin.getCommand("bucheron");
        assertNotNull(foret);
        assertNotNull(bucheron);
        assertSame(adapter, foret.getExecutor());
        assertSame(adapter, foret.getTabCompleter());
        assertSame(adapter, bucheron.getExecutor());
        assertSame(adapter, bucheron.getTabCompleter());

        ForestryModule.install(plugin);
        assertSame(adapter, ForestryModule.activeExecutorForTests());
    }

    @Test
    void jeConserveExclusivementLeMoteurHistoriqueQuandV2EstDesactive() {
        server = MockBukkit.mock();
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("forester-v2.enabled", false);

        MinePlugin plugin = MockBukkit.loadWithConfig(MinePlugin.class, configuration);

        assertNotNull(plugin.getForet());
        assertNull(ForestryModule.activeExecutorForTests());
        assertSame(plugin.getForet(), plugin.getCommand("foret").getExecutor());
    }

    @Test
    void jeDeclareLesPermissionsForestieresAvecDesValeursSures() {
        server = MockBukkit.mock();
        MockBukkit.load(MinePlugin.class);

        Permission use = server.getPluginManager().getPermission("minegus.forester.use");
        Permission admin = server.getPluginManager().getPermission("minegus.forester.admin");
        Permission bypass = server.getPluginManager().getPermission("minegus.forester.bypass");

        assertNotNull(use);
        assertNotNull(admin);
        assertNotNull(bypass);
        assertEquals(PermissionDefault.TRUE, use.getDefault());
        assertEquals(PermissionDefault.OP, admin.getDefault());
        assertEquals(PermissionDefault.OP, bypass.getDefault());
    }
}
