package com.minecolonies.kingdoms.world;

public record WorldGenerationStats(long regionsPlanned, long settlementsPlanned, long roadsPlanned,
    long settlementChunksGenerated, long roadChunksGenerated, long blocksChanged,
    long planningRuns, double averagePlanningDurationNanos, long maximumPlanningDurationNanos)
{
}
