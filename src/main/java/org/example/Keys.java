package org.example;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Centralise les {@link NamespacedKey} utilisés par le plugin.
 * Initialiser une seule fois via {@link #init(JavaPlugin)}.
 */
public final class Keys {

    private static NamespacedKey workerType;
    private static NamespacedKey workerId;
    private static NamespacedKey hutId;
    private static NamespacedKey guardFor;
    private static NamespacedKey royalGuardType;
    private static NamespacedKey royalGuardOwner;
    private static NamespacedKey royalGuardSlot;
    private static NamespacedKey villageKnightType;
    private static NamespacedKey villageKnightVillage;
    private static NamespacedKey villageKnightSlot;
    private static NamespacedKey villageKnightRole;

    private Keys() {
    }

    public static void init(JavaPlugin plugin) {
        if (workerType != null) {
            return;
        }
        workerType = new NamespacedKey(plugin, "worker_type");
        workerId = new NamespacedKey(plugin, "worker_id");
        hutId = new NamespacedKey(plugin, "hut_id");
        guardFor = new NamespacedKey(plugin, "guard_for");
        royalGuardType = new NamespacedKey(plugin, "royal_guard_type");
        royalGuardOwner = new NamespacedKey(plugin, "royal_guard_owner");
        royalGuardSlot = new NamespacedKey(plugin, "royal_guard_slot");
        villageKnightType = new NamespacedKey(plugin, "village_knight_type");
        villageKnightVillage = new NamespacedKey(plugin, "village_knight_village");
        villageKnightSlot = new NamespacedKey(plugin, "village_knight_slot");
        villageKnightRole = new NamespacedKey(plugin, "village_knight_role");
    }

    public static NamespacedKey workerType() {
        ensureInitialized();
        return workerType;
    }

    public static NamespacedKey workerId() {
        ensureInitialized();
        return workerId;
    }

    public static NamespacedKey hutId() {
        ensureInitialized();
        return hutId;
    }

    public static NamespacedKey guardFor() {
        ensureInitialized();
        return guardFor;
    }

    public static NamespacedKey royalGuardType() {
        ensureInitialized();
        return royalGuardType;
    }

    public static NamespacedKey royalGuardOwner() {
        ensureInitialized();
        return royalGuardOwner;
    }

    public static NamespacedKey royalGuardSlot() {
        ensureInitialized();
        return royalGuardSlot;
    }

    public static NamespacedKey villageKnightType() {
        ensureInitialized();
        return villageKnightType;
    }

    public static NamespacedKey villageKnightVillage() {
        ensureInitialized();
        return villageKnightVillage;
    }

    public static NamespacedKey villageKnightSlot() {
        ensureInitialized();
        return villageKnightSlot;
    }

    public static NamespacedKey villageKnightRole() {
        ensureInitialized();
        return villageKnightRole;
    }

    private static void ensureInitialized() {
        if (workerType == null) {
            throw new IllegalStateException("Keys.init(plugin) doit être appelé avant utilisation.");
        }
    }
}
