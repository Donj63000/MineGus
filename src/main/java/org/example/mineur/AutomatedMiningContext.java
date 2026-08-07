package org.example.mineur;

import java.util.function.Supplier;

/**
 * Marque temporairement les événements déclenchés par le mineur automatisé.
 *
 * <p>Le contexte est local au thread principal et toujours nettoyé dans un
 * {@code finally}. Les modules internes peuvent ainsi éviter de comptabiliser
 * la casse automatisée comme une action manuelle du joueur.</p>
 */
public final class AutomatedMiningContext {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private AutomatedMiningContext() {
        // Classe utilitaire.
    }

    public static boolean isActive() {
        return DEPTH.get() > 0;
    }

    public static <T> T call(Supplier<T> action) {
        int previous = DEPTH.get();
        DEPTH.set(previous + 1);
        try {
            return action.get();
        } finally {
            if (previous == 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(previous);
            }
        }
    }
}
