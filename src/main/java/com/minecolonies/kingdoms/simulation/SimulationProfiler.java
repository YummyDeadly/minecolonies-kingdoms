package com.minecolonies.kingdoms.simulation;

public final class SimulationProfiler
{
    private long coloniesProcessed;
    private long batchesProcessed;
    private double averageColonyDurationNanos;
    private long maxColonyDurationNanos;
    private long economyUpdates;
    private long aiEvaluations;

    public void recordColony(final long durationNanos, final boolean economyUpdated, final boolean aiEvaluated)
    {
        coloniesProcessed++;
        averageColonyDurationNanos += (durationNanos - averageColonyDurationNanos) / coloniesProcessed;
        maxColonyDurationNanos = Math.max(maxColonyDurationNanos, durationNanos);
        if (economyUpdated)
        {
            economyUpdates++;
        }
        if (aiEvaluated)
        {
            aiEvaluations++;
        }
    }

    public void recordBatch(final int processed)
    {
        if (processed > 0)
        {
            batchesProcessed++;
        }
    }

    public SimulationStats snapshot(final int pendingColonies)
    {
        return new SimulationStats(
            coloniesProcessed,
            batchesProcessed,
            averageColonyDurationNanos,
            maxColonyDurationNanos,
            economyUpdates,
            aiEvaluations,
            pendingColonies);
    }

    public void reset()
    {
        coloniesProcessed = 0L;
        batchesProcessed = 0L;
        averageColonyDurationNanos = 0.0D;
        maxColonyDurationNanos = 0L;
        economyUpdates = 0L;
        aiEvaluations = 0L;
    }
}
