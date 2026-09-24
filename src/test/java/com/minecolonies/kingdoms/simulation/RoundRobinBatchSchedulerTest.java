package com.minecolonies.kingdoms.simulation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoundRobinBatchSchedulerTest
{
    @Test
    void respectsBatchSizeAndInvokesControllerCallback()
    {
        final RoundRobinBatchScheduler<Integer> scheduler = new RoundRobinBatchScheduler<>();
        final List<Integer> controllerCalls = new ArrayList<>();
        scheduler.refill(List.of(3, 1, 2), Comparator.naturalOrder());

        final BatchExecutionResult first = scheduler.process(2, Long.MAX_VALUE, controllerCalls::add);

        assertEquals(2, first.processed());
        assertEquals(List.of(1, 2), controllerCalls);
        assertEquals(1, scheduler.remaining());
    }
}
