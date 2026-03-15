package org.example.village;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class VillageEntityManagerAnchorTest {

    @Test
    @SuppressWarnings("unchecked")
    void merchantAnchorsUseSpecializedVillageLocations() throws Exception {
        Field anchorsField = VillageEntityManager.class.getDeclaredField("VILLAGE_ANCHORS");
        anchorsField.setAccessible(true);
        Map<Integer, Map<String, Location>> villageAnchors = (Map<Integer, Map<String, Location>>) anchorsField.get(null);

        World world = mock(World.class);
        Map<String, Location> anchors = new HashMap<>();
        anchors.put("service_yard", new Location(world, 10, 64, 10));
        anchors.put("forge", new Location(world, 20, 65, 5));
        anchors.put("farm", new Location(world, -8, 64, 14));
        anchors.put("pen", new Location(world, -16, 64, -4));
        villageAnchors.put(42, anchors);

        Class<?> merchantTypeClass = Class.forName("org.example.village.VillageEntityManager$MerchantType");
        Method anchorMethod = VillageEntityManager.class.getDeclaredMethod("merchantAnchor", int.class, merchantTypeClass, Location.class);
        anchorMethod.setAccessible(true);

        Object minerType = Enum.valueOf((Class<Enum>) merchantTypeClass, "MINER");
        Object farmerType = Enum.valueOf((Class<Enum>) merchantTypeClass, "FARMER");
        Object breederType = Enum.valueOf((Class<Enum>) merchantTypeClass, "BREEDER");
        Object blacksmithType = Enum.valueOf((Class<Enum>) merchantTypeClass, "BLACKSMITH");
        Location fallback = new Location(world, 0, 64, 0);

        Location miner = (Location) anchorMethod.invoke(null, 42, minerType, fallback);
        Location farmer = (Location) anchorMethod.invoke(null, 42, farmerType, fallback);
        Location breeder = (Location) anchorMethod.invoke(null, 42, breederType, fallback);
        Location blacksmith = (Location) anchorMethod.invoke(null, 42, blacksmithType, fallback);

        assertEquals(10.5, miner.getX());
        assertEquals(65.0, miner.getY());
        assertEquals(14.5, farmer.getZ());
        assertEquals(-3.5, breeder.getZ());
        assertEquals(20.5, blacksmith.getX());

        villageAnchors.remove(42);
    }
}
