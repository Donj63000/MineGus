package org.example.mineur;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TunnelOperationBatchTest {

    @Test
    void respectsTheBudgetAndResumesWithoutReplayingCompletedItems() {
        List<Integer> items = new ArrayList<>();
        for (int index = 0; index < 130; index++) {
            items.add(index);
        }
        List<Integer> processed = new ArrayList<>();

        TunnelOperationBatch.Result<Integer> first = TunnelOperationBatch.process(
                items,
                0,
                64,
                item -> {
                    processed.add(item);
                    return TunnelOperationBatch.StepResult.ADVANCED;
                }
        );
        TunnelOperationBatch.Result<Integer> second = TunnelOperationBatch.process(
                items,
                first.nextIndex(),
                64,
                item -> {
                    processed.add(item);
                    return TunnelOperationBatch.StepResult.ADVANCED;
                }
        );
        TunnelOperationBatch.Result<Integer> third = TunnelOperationBatch.process(
                items,
                second.nextIndex(),
                64,
                item -> {
                    processed.add(item);
                    return TunnelOperationBatch.StepResult.ADVANCED;
                }
        );

        assertAll(
                () -> assertEquals(64, first.nextIndex()),
                () -> assertEquals(TunnelOperationBatch.Status.IN_PROGRESS,
                        first.status()),
                () -> assertEquals(128, second.nextIndex()),
                () -> assertEquals(TunnelOperationBatch.Status.IN_PROGRESS,
                        second.status()),
                () -> assertEquals(130, third.nextIndex()),
                () -> assertEquals(TunnelOperationBatch.Status.COMPLETE,
                        third.status()),
                () -> assertEquals(items, processed),
                () -> assertNull(third.blockedItem())
        );
    }

    @Test
    void keepsTheBlockingIndexForRetryAndDenial() {
        AtomicInteger calls = new AtomicInteger();
        List<Integer> items = List.of(8, 9, 10, 11);

        TunnelOperationBatch.Result<Integer> retry = TunnelOperationBatch.process(
                items,
                1,
                4,
                item -> {
                    calls.incrementAndGet();
                    return item == 10
                            ? TunnelOperationBatch.StepResult.RETRY_LATER
                            : TunnelOperationBatch.StepResult.ADVANCED;
                }
        );
        TunnelOperationBatch.Result<Integer> denied = TunnelOperationBatch.process(
                items,
                retry.nextIndex(),
                4,
                item -> TunnelOperationBatch.StepResult.DENIED
        );

        assertAll(
                () -> assertEquals(2, retry.nextIndex()),
                () -> assertEquals(10, retry.blockedItem()),
                () -> assertEquals(TunnelOperationBatch.Status.RETRY_LATER,
                        retry.status()),
                () -> assertEquals(2, denied.nextIndex()),
                () -> assertEquals(10, denied.blockedItem()),
                () -> assertEquals(TunnelOperationBatch.Status.DENIED,
                        denied.status()),
                () -> assertEquals(2, calls.get())
        );
    }

    @Test
    void rejectsInvalidIndexesBudgetsAndNullResults() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TunnelOperationBatch.process(
                                List.of(1),
                                -1,
                                1,
                                ignored -> TunnelOperationBatch.StepResult.ADVANCED
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TunnelOperationBatch.process(
                                List.of(1),
                                0,
                                0,
                                ignored -> TunnelOperationBatch.StepResult.ADVANCED
                        )
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> TunnelOperationBatch.process(
                                List.of(1),
                                0,
                                1,
                                ignored -> null
                        )
                )
        );
    }
}
