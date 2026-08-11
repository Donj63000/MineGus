package org.example.mineur;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Je traite une file d'opérations par lots sans dépendre de Bukkit.
 *
 * <p>Je laisse l'index sur l'opération bloquante afin qu'un retry reprenne
 * exactement au bon endroit, sans rejouer les opérations déjà validées.</p>
 */
public final class TunnelOperationBatch {

    private TunnelOperationBatch() {
        // Je garde cette classe strictement utilitaire.
    }

    public enum StepResult {
        ADVANCED,
        RETRY_LATER,
        DENIED
    }

    public enum Status {
        COMPLETE,
        IN_PROGRESS,
        RETRY_LATER,
        DENIED
    }

    public record Result<T>(int nextIndex, Status status, T blockedItem) {

        public Result {
            if (nextIndex < 0) {
                throw new IllegalArgumentException("L'index suivant est invalide.");
            }
            Objects.requireNonNull(status, "status");
            if ((status == Status.RETRY_LATER || status == Status.DENIED)
                    != (blockedItem != null)) {
                throw new IllegalArgumentException(
                        "Une opération bloquée doit conserver son élément."
                );
            }
        }
    }

    public static <T> Result<T> process(List<T> items,
                                        int startIndex,
                                        int maximumOperations,
                                        Function<T, StepResult> operation) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(operation, "operation");
        if (startIndex < 0 || startIndex > items.size()) {
            throw new IllegalArgumentException("L'index de reprise est invalide.");
        }
        if (maximumOperations < 1) {
            throw new IllegalArgumentException("Le budget doit être positif.");
        }

        int index = startIndex;
        int remaining = maximumOperations;
        while (index < items.size() && remaining-- > 0) {
            T item = Objects.requireNonNull(
                    items.get(index),
                    "Une opération du tunnel est nulle."
            );
            StepResult result = Objects.requireNonNull(
                    operation.apply(item),
                    "Le résultat d'une opération du tunnel est nul."
            );
            if (result == StepResult.ADVANCED) {
                index++;
                continue;
            }
            return new Result<>(
                    index,
                    result == StepResult.RETRY_LATER
                            ? Status.RETRY_LATER
                            : Status.DENIED,
                    item
            );
        }

        return new Result<>(
                index,
                index >= items.size() ? Status.COMPLETE : Status.IN_PROGRESS,
                null
        );
    }
}
