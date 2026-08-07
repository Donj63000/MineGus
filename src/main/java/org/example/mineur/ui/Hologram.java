package org.example.mineur.ui;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;

/**
 * Encapsule l'hologramme transitoire affiché au-dessus du mineur.
 */
public final class Hologram {

    private ArmorStand armorStand;

    public void show(Location location, String text) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        hide();
        armorStand = (ArmorStand) location.getWorld().spawnEntity(
                location.clone().add(0, 0.8, 0),
                EntityType.ARMOR_STAND
        );
        armorStand.setInvisible(true);
        armorStand.setMarker(true);
        armorStand.setCustomNameVisible(true);
        armorStand.setCustomName(text);
        armorStand.setGravity(false);
        armorStand.setSmall(true);
        /*
         * Un crash ne doit pas laisser d'ArmorStand orphelin enregistré dans
         * le chunk ; l'hologramme est purement transitoire.
         */
        armorStand.setPersistent(false);
    }

    public void hide() {
        ArmorStand current = armorStand;
        armorStand = null;
        if (current != null && !current.isDead()) {
            current.remove();
        }
    }
}
