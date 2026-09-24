package com.minecolonies.kingdoms.world.settlement.structure;

public record TerrainPlacementConstraints(int perimeterPadding, int approachLength, int maximumHeightVariation,
    double maximumGrade, int maximumCutFill, int maximumWaterColumns, int maximumUnsupportedCorners)
{
    public TerrainPlacementConstraints
    {
        if (perimeterPadding < 0 || approachLength < 1 || maximumHeightVariation < 0 || maximumGrade < 0.0D
            || maximumCutFill < 0 || maximumWaterColumns < 0 || maximumUnsupportedCorners < 0)
            throw new IllegalArgumentException("Invalid terrain placement constraints");
    }

    public static TerrainPlacementConstraints settlementDefault()
    {
        return new TerrainPlacementConstraints(2, 5, 4, 0.35D, 2, 0, 0);
    }
}
