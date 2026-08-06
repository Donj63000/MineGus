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

    private static void ensureInitialized() {
        if (workerType == null) {
            throw new IllegalStateException("Keys.init(plugin) doit être appelé avant utilisation.");
        }
    }
}
