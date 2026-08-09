package org.example.tutorial;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TutorialResourceTest {

    @Test
    void pluginDescriptorRegistersCommandAndPermission() throws IOException {
        String pluginYml = resource("plugin.yml");

        assertTrue(pluginYml.contains("\n  tuto:\n"));
        assertTrue(pluginYml.contains("permission: mineplugin.tuto.use"));
        assertTrue(pluginYml.contains("\n  mineplugin.tuto.use:\n"));
    }

    @Test
    void defaultConfigurationContainsSafetyBudgets() throws IOException {
        String config = resource("config.yml");

        assertTrue(config.contains("\ntutorial:\n"));
        assertTrue(config.contains("recall-distance: 15.0"));
        assertTrue(config.contains("max-concurrent-sessions: 4"));
        assertTrue(config.contains("build-time-budget-ms: 6.0"));
        assertTrue(config.contains("name-prefix: \"minegus_tuto_\""));
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = TutorialResourceTest.class
                .getClassLoader()
                .getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Ressource absente : " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        }
    }
}
