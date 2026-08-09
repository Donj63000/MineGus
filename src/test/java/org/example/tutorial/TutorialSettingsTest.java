package org.example.tutorial;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TutorialSettingsTest {

    @Test
    void unsafeAndExcessiveValuesAreNormalized() {
        TutorialSettings settings = new TutorialSettings(
                true,
                999,
                2.0D,
                Double.NaN,
                2,
                100L,
                1,
                -20,
                "../Mon Monde très long et dangereux",
                50.0D,
                -1L,
                42L
        );

        assertEquals(32, settings.maxConcurrentSessions());
        assertEquals(5.0D, settings.recallDistance());
        assertEquals(0.06D, settings.walkSpeed());
        assertEquals(100, settings.blocksPerTick());
        assertEquals(1_000_000L, settings.buildBudgetNanos());
        assertEquals(100, settings.chunkLoadTimeoutTicks());
        assertEquals(0, settings.autoExitDelayTicks());
        assertEquals(400.0D, settings.worldBorderSize());
        assertEquals(23_999L, settings.worldTime());
        assertTrue(settings.worldNamePrefix().endsWith("_"));
        assertTrue(settings.worldNamePrefix().matches("[a-z0-9_-]+"));
        assertTrue(settings.worldNamePrefix().length() <= 24);
    }

    @Test
    void configurationUsesDocumentedDefaults() {
        TutorialSettings settings =
                TutorialSettings.from(new YamlConfiguration());

        assertTrue(settings.enabled());
        assertEquals(4, settings.maxConcurrentSessions());
        assertEquals(15.0D, settings.recallDistance());
        assertEquals(225.0D, settings.recallDistanceSquared());
        assertEquals(0.17D, settings.walkSpeed());
        assertEquals(1_000, settings.blocksPerTick());
        assertEquals(6_000_000L, settings.buildBudgetNanos());
        assertEquals(400, settings.chunkLoadTimeoutTicks());
        assertEquals(300, settings.autoExitDelayTicks());
        assertEquals("minegus_tuto_", settings.worldNamePrefix());
    }

    @Test
    void explicitSeedIsStableAndAutomaticSeedIsNeverZero() {
        TutorialSettings fixed = defaultsWithSeed(123_456L);
        TutorialSettings automatic = defaultsWithSeed(0L);

        assertEquals(123_456L, fixed.seedForNewWorld());
        assertNotEquals(0L, automatic.seedForNewWorld());
    }

    private static TutorialSettings defaultsWithSeed(long seed) {
        return new TutorialSettings(
                true,
                4,
                15.0D,
                0.17D,
                1_000,
                6_000_000L,
                400,
                300,
                "minegus_tuto_",
                800.0D,
                6_000L,
                seed
        );
    }
}
