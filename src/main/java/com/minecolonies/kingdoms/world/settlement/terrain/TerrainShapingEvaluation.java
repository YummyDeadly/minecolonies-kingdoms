package com.minecolonies.kingdoms.world.settlement.terrain;

import java.util.Optional;

public record TerrainShapingEvaluation(TerrainShapingPlan plan, TerrainShapingRejection rejection,
    int samples, int cutVolume, int fillVolume, int maximumCutDepth, int maximumFillDepth)
{
    public boolean accepted() { return plan != null && rejection == TerrainShapingRejection.NONE; }
    public Optional<TerrainShapingPlan> acceptedPlan() { return Optional.ofNullable(plan); }
    public static TerrainShapingEvaluation rejected(final TerrainShapingRejection reason, final int samples,
        final int cut, final int fill, final int maxCut, final int maxFill)
    {
        return new TerrainShapingEvaluation(null, reason, samples, cut, fill, maxCut, maxFill);
    }
}
