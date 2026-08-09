package org.example.tutorial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TutorialManagerCommandTest {

    @Test
    void onlyTutorialCommandCanRunInsideVisit() {
        assertTrue(TutorialManager.isTutoCommand("/tuto"));
        assertTrue(TutorialManager.isTutoCommand(" /TUTO "));
        assertTrue(TutorialManager.isTutoCommand("/mineplugin:tuto"));
        assertTrue(TutorialManager.isTutoCommand("/tuto argument-inutile"));

        assertFalse(TutorialManager.isTutoCommand(null));
        assertFalse(TutorialManager.isTutoCommand(""));
        assertFalse(TutorialManager.isTutoCommand("/village"));
        assertFalse(TutorialManager.isTutoCommand("/tutoriel"));
        assertFalse(TutorialManager.isTutoCommand("/minecraft:help"));
        assertFalse(TutorialManager.isTutoCommand("/evil:tuto"));
    }
}
