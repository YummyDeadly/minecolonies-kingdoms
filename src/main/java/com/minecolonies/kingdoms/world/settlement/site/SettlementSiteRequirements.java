package com.minecolonies.kingdoms.world.settlement.site;

import com.minecolonies.kingdoms.world.settlement.SettlementType;

public record SettlementSiteRequirements(int analysisRadius, int sampleSpacing,
    double minimumBuildableFraction, double minimumConnectedFraction, double maximumWaterFraction,
    int maximumElevationSpan, int maximumSampleStep, long maximumTerrainWork)
{
    public SettlementSiteRequirements
    {
        if (analysisRadius < 8 || sampleSpacing < 2 || minimumBuildableFraction < 0.0D
            || minimumBuildableFraction > 1.0D || minimumConnectedFraction < 0.0D
            || minimumConnectedFraction > 1.0D || maximumWaterFraction < 0.0D
            || maximumWaterFraction > 1.0D || maximumElevationSpan < 0 || maximumSampleStep < 0
            || maximumTerrainWork < 0L)
            throw new IllegalArgumentException("Invalid settlement site requirements");
    }

    public static SettlementSiteRequirements forType(final SettlementType type, final int configuredRadius)
    {
        final int radius = Math.max(configuredRadius, type.footprintRadius() + 16);
        // A 9x9 grid (81 samples) covers the whole starter/expansion area. Generator height queries are the
        // dominant planning cost (about 1-2 ms each on a noise generator), so area coverage uses wide spacing.
        final int spacing = Math.max(4, Math.min(14, radius / 4));
        return switch (type)
        {
            // Water caps allow one waterfront side; the buildable/connected minima reject islands and thin shores.
            case VILLAGE -> new SettlementSiteRequirements(radius, spacing, 0.55D, 0.50D, 0.30D, 16, 7, 2_600L);
            case TOWN -> new SettlementSiteRequirements(radius, spacing, 0.62D, 0.58D, 0.24D, 18, 7, 3_200L);
            case TRADING_TOWN -> new SettlementSiteRequirements(radius, spacing, 0.62D, 0.58D, 0.28D, 18, 7, 3_100L);
            case CASTLE -> new SettlementSiteRequirements(radius, spacing, 0.70D, 0.66D, 0.15D, 20, 8, 4_200L);
            case FORT -> new SettlementSiteRequirements(radius, spacing, 0.58D, 0.52D, 0.25D, 20, 8, 3_500L);
        };
    }
}
