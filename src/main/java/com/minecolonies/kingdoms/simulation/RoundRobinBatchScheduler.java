package com.minecolonies.kingdoms.simulation;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Queue;
import java.util.function.Consumer;

public final class RoundRobinBatchScheduler<T>
{
    private final Queue<T> pending = new ArrayDeque<>();

    public void refill(final Collection<T> values, final Comparator<T> comparator)
    {
        pending.clear();
        values.stream().sorted(comparator).forEach(pending::add);
    }

    public BatchExecutionResult process(
        final int batchSize,
        final long maxWorkNanos,
        final Consumer<T> consumer)
    {
        if (batchSize <= 0 || maxWorkNanos <= 0L)
        {
            throw new IllegalArgumentException("Batch limits must be positive");
        }
        Objects.requireNonNull(consumer, "consumer");
        final long started = System.nanoTime();
        int processed = 0;
        while (processed < batchSize && !pending.isEmpty())
        {
            consumer.accept(pending.remove());
            processed++;
            if (System.nanoTime() - started >= maxWorkNanos)
            {
                break;
            }
        }
        return new BatchExecutionResult(processed, System.nanoTime() - started);
    }

    public int remaining()
    {
        return pending.size();
    }

    public boolean isEmpty()
    {
        return pending.isEmpty();
    }

    public void clear()
    {
        pending.clear();
    }
}
