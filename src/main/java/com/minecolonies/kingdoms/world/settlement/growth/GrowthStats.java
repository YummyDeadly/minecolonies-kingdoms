package com.minecolonies.kingdoms.world.settlement.growth;

public record GrowthStats(long evaluations, long stageTransitions, long buildingsPlanned,
    long buildingsCompleted, long blockedBuilds, long physicalChunksProcessed,
    long populationGrowthEvents, double averageEvaluationNanos, long maximumEvaluationNanos)
{
}
