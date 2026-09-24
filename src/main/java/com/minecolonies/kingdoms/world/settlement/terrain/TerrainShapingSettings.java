package com.minecolonies.kingdoms.world.settlement.terrain;

public record TerrainShapingSettings(int maximumCutDepth, int maximumFillDepth,
    int maximumPadHeightVariance, int maximumRetainingWallHeight, int maximumTerrainWorkVolume,
    int clearancePadding)
{
    public TerrainShapingSettings
    {
        if (maximumCutDepth < 0 || maximumFillDepth < 0 || maximumPadHeightVariance < 0
            || maximumRetainingWallHeight < 0 || maximumTerrainWorkVolume < 0
            || clearancePadding < 0 || clearancePadding > 8)
            throw new IllegalArgumentException("Invalid terrain shaping settings");
    }

    public static TerrainShapingSettings defaults()
    {
        return new TerrainShapingSettings(4, 4, 8, 5, 4_096, 2);
    }
}
