package com.minecolonies.kingdoms.world.settlement.layout;

public record LocalStreetSettings(int searchPadding, int maximumSearchNodes, int maximumTerrainAdjustment,
    int maximumBridgeSpan, int maximumStreetLength, int joinsConsidered)
{
    public LocalStreetSettings
    {
        if (searchPadding < 0 || maximumSearchNodes < 16 || maximumTerrainAdjustment < 0
            || maximumBridgeSpan < 0 || maximumStreetLength < 2 || joinsConsidered < 1)
            throw new IllegalArgumentException("Invalid local street settings");
    }

    public static LocalStreetSettings defaults()
    {
        return new LocalStreetSettings(10, 4_096, 2, 5, 192, 8);
    }
}
