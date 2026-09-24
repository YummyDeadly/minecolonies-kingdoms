package com.minecolonies.kingdoms.world.settlement.growth;

public final class GrowthProfiler
{
    private long evaluations;
    private long stageTransitions;
    private long buildingsPlanned;
    private long buildingsCompleted;
    private long blockedBuilds;
    private long physicalChunksProcessed;
    private long populationGrowthEvents;
    private long totalEvaluationNanos;
    private long maximumEvaluationNanos;

    public void evaluation(final long nanos) { evaluations++; totalEvaluationNanos += Math.max(0L, nanos); maximumEvaluationNanos = Math.max(maximumEvaluationNanos, nanos); }
    public void stageTransition() { stageTransitions++; }
    public void buildingPlanned() { buildingsPlanned++; }
    public void buildingCompleted() { buildingsCompleted++; }
    public void blockedBuild() { blockedBuilds++; }
    public void physicalChunk() { physicalChunksProcessed++; }
    public void populationGrowth() { populationGrowthEvents++; }
    public GrowthStats snapshot()
    {
        return new GrowthStats(evaluations, stageTransitions, buildingsPlanned, buildingsCompleted, blockedBuilds,
            physicalChunksProcessed, populationGrowthEvents, evaluations == 0 ? 0.0D : totalEvaluationNanos / (double) evaluations,
            maximumEvaluationNanos);
    }
    public void reset()
    {
        evaluations = stageTransitions = buildingsPlanned = buildingsCompleted = blockedBuilds = 0L;
        physicalChunksProcessed = populationGrowthEvents = totalEvaluationNanos = maximumEvaluationNanos = 0L;
    }
}
