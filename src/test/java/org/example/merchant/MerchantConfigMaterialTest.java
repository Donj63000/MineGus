package org.example.merchant;

import org.bukkit.Material;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantConfigMaterialTest {

    private static Object resolver;
    private static Method resolveToList;

    @BeforeAll
    static void prepareResolver() throws Exception {
        Class<?> resolverClass = Class.forName("org.example.merchant.MerchantManager$MaterialResolver");
        Constructor<?> ctor = resolverClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        resolver = ctor.newInstance();
        resolveToList = resolverClass.getDeclaredMethod("resolveToList", String.class);
        resolveToList.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private static List<Material> materialsFor(String token) throws Exception {
        return (List<Material>) resolveToList.invoke(resolver, token);
    }

    @Test
    @DisplayName("Terracotta category uses valid clay inputs")
    void terracottaInputsAreValid() throws Exception {
        List<Material> materials = materialsFor("CLAY");
        assertFalse(materials.isEmpty(), "Expected CLAY to resolve to at least one material");
        assertTrue(materials.contains(Material.CLAY), "Resolved materials should contain CLAY");
    }

    @Test
    @DisplayName("Ocean category prismarine inputs resolve correctly")
    void oceanInputsAreValid() throws Exception {
        List<Material> materials = materialsFor("PRISMARINE");
        assertTrue(materials.contains(Material.PRISMARINE), "Resolved materials should contain PRISMARINE");
    }

    @Test
    @DisplayName("Vegetal category pale moss entries resolve with new block names")
    void paleMossMaterialsAreValid() throws Exception {
        List<Material> mossBlock = materialsFor("PALE_MOSS_BLOCK");
        assertTrue(mossBlock.contains(Material.PALE_MOSS_BLOCK), "Resolved materials should contain PALE_MOSS_BLOCK");

        List<Material> hangingMoss = materialsFor("PALE_HANGING_MOSS");
        assertTrue(hangingMoss.contains(Material.PALE_HANGING_MOSS), "Resolved materials should contain PALE_HANGING_MOSS");
    }

    @Test
    @DisplayName("Special admin outputs use supported block ids")
    void specialAdminOutputsAreReachable() throws Exception {
        List<Material> trialSpawner = materialsFor("TRIAL_SPAWNER");
        assertTrue(trialSpawner.contains(Material.TRIAL_SPAWNER), "Resolved materials should contain TRIAL_SPAWNER");

        List<Material> vault = materialsFor("VAULT");
        assertTrue(vault.contains(Material.VAULT), "Resolved materials should contain VAULT");
    }
}
