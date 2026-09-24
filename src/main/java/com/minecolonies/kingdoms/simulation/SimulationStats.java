package com.minecolonies.kingdoms.simulation;

public record SimulationStats(
    long coloniesProcessed,
    long batchesProcessed,
    double averageColonyDurationNanos,
    long maxColonyDurationNanos,
    long economyUpdates,
    long aiEvaluations,
    int pendingColonies)
{
}
