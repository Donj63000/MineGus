package org.example.mineur.ui;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

/**
 * Lightweight hologram wrapper (optional feature).
 */
public final class Hologram {

    private ArmorStand armorStand;

    public void show(Location location, String text) {
        if (location == null) {
            return;
        }
        hide();
        armorStand = (ArmorStand) location.getWorld().spawnEntity(location.clone().add(0, 0.8, 0), EntityType.ARMOR_STAND);
        armorStand.setInvisible(true);
        armorStand.setMarker(true);
        armorStand.setCustomNameVisible(true);
        armorStand.setCustomName(text);
        armorStand.setGravity(false);
        armorStand.setSmall(true);
    }

    public void hide() {
        if (armorStand != null && !armorStand.isDead()) {
            armorStand.remove();
            armorStand = null;
        }
    }
}
