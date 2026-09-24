package com.minecolonies.kingdoms.world.road;

public record RoadSettings(int softMaximumDistance, int hardMaximumDistance, int maximumDegree,
    int gridSize, int searchMargin, int maximumPathNodes, double slopeCost, double waterCost, double elevationCost,
    double maximumGroundGrade, int maximumBridgeSpan, int maximumBridgeBankDelta, int ravineDepthThreshold,
    int maximumFinePathPoints, int physicalRefinementRadius, boolean clearReplaceableVegetation)
{
    public RoadSettings(final int softMaximumDistance, final int hardMaximumDistance, final int maximumDegree,
        final int gridSize, final int searchMargin, final int maximumPathNodes, final double slopeCost,
        final double waterCost, final double elevationCost)
    {
        this(softMaximumDistance, hardMaximumDistance, maximumDegree, gridSize, searchMargin, maximumPathNodes,
            slopeCost, waterCost, elevationCost, 1.0D, 64, 3, 5, 16_384, 3, false);
    }

    public RoadSettings
    {
        if (softMaximumDistance < 1 || hardMaximumDistance < softMaximumDistance || maximumDegree < 1
            || gridSize < 8 || searchMargin < 0 || maximumPathNodes < 64 || slopeCost < 0 || waterCost < 0 || elevationCost < 0
            || maximumGroundGrade < 0 || maximumGroundGrade > 1.0D || maximumBridgeSpan < 1 || maximumBridgeBankDelta < 0
            || ravineDepthThreshold < 1 || maximumFinePathPoints < 64 || physicalRefinementRadius < 0 || physicalRefinementRadius > 8)
            throw new IllegalArgumentException("Invalid road settings");
    }

    public static RoadSettings defaults()
    {
        return new RoadSettings(1_536, 2_048, 4, 64, 256, 2_500, 10.0D, 120.0D, 1.5D);
    }
}
