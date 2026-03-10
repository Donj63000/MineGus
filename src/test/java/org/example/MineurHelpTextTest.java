package org.example;

import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MineurHelpTextTest {

    @Test
    void helpContainsAllSubCommandsAndAliases() {
        List<String> lines = Mineur.buildUsageLines(-58, false);
        String help = String.join("\n", lines);

        assertTrue(help.contains("/mineur"));
        assertTrue(help.contains("/mineur aide"));
        assertTrue(help.contains("/mineur help"));
        assertTrue(help.contains("/mineur !aide"));

        assertTrue(help.contains("/mineur vitesse <lent|normal|rapide>"));
        assertTrue(help.contains("/mineur speed <slow|normal|fast>"));

        assertTrue(help.contains("/mineur pattern <carriere|branche|tunnel|veine>"));
        assertTrue(help.contains("/mineur mode <...>"));
        assertTrue(help.contains("/mineur patron <...>"));

        assertTrue(help.contains("/mineur pause"));
        assertTrue(help.contains("/mineur reprendre"));
        assertTrue(help.contains("/mineur resume"));
        assertTrue(help.contains("/mineur play"));

        assertTrue(help.contains("/mineur stop"));
        assertTrue(help.contains("/mineur arreter"));
        assertTrue(help.contains("/mineur off"));

        assertTrue(help.contains("/mineur info"));
        assertTrue(help.contains("/mineur status"));

        assertTrue(help.contains("/mineur autoriser <joueur>"));
        assertTrue(help.contains("/mineur trust <joueur>"));
    }

    @Test
    void helpReflectsRuntimeConfigurationValues() {
        String helpDisabled = ChatColor.stripColor(String.join("\n", Mineur.buildUsageLines(-58, false)));
        assertTrue(helpDisabled.contains("stop-at-y="));
        assertTrue(helpDisabled.contains("-58"));
        assertTrue(helpDisabled.contains("pose auto=desactivee"));

        String helpEnabled = ChatColor.stripColor(String.join("\n", Mineur.buildUsageLines(-42, true)));
        assertTrue(helpEnabled.contains("-42"));
        assertTrue(helpEnabled.contains("pose auto=activee"));
    }
}
