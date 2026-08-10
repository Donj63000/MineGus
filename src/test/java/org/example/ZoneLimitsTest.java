package org.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneLimitsTest {

    @Test
    void agricultureRefuseLesZonesTropPetitesOuTropGrandes() {
        assertFalse(Agriculture.areDimensionsAllowed(2, 64));
        assertTrue(Agriculture.areDimensionsAllowed(3, 3));
        assertTrue(Agriculture.areDimensionsAllowed(64, 64));
        assertFalse(Agriculture.areDimensionsAllowed(65, 1));
        assertFalse(Agriculture.areDimensionsAllowed(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }


    @Test
    void agricultureAdapteLesCoffresSansChevauchement() {
        assertEquals(0, Agriculture.chestCountPerCorner(2));
        assertEquals(3, Agriculture.chestCountPerCorner(3));
        assertEquals(4, Agriculture.chestCountPerCorner(4));
        assertEquals(4, Agriculture.chestCountPerCorner(5));
        assertEquals(5, Agriculture.chestCountPerCorner(6));
        assertEquals(5, Agriculture.chestCountPerCorner(7));
        assertEquals(6, Agriculture.chestCountPerCorner(8));
        assertEquals(6, Agriculture.chestCountPerCorner(64));
    }

    @Test
    void elevageRefuseLesZonesTropPetitesOuTropGrandes() {
        assertFalse(Eleveur.areDimensionsAllowed(7, 64));
        assertTrue(Eleveur.areDimensionsAllowed(8, 8));
        assertTrue(Eleveur.areDimensionsAllowed(64, 64));
        assertFalse(Eleveur.areDimensionsAllowed(64, 65));
        assertFalse(Eleveur.areDimensionsAllowed(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void foretRefuseLesZonesTropPetitesOuTropGrandes() {
        assertFalse(Foret.areDimensionsAllowed(3, 64));
        assertTrue(Foret.areDimensionsAllowed(4, 4));
        assertTrue(Foret.areDimensionsAllowed(64, 64));
        assertFalse(Foret.areDimensionsAllowed(65, 64));
        assertFalse(Foret.areDimensionsAllowed(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
