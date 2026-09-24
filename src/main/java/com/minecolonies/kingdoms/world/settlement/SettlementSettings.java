package com.minecolonies.kingdoms.world.settlement;

import java.util.EnumMap;
import java.util.Map;

public record SettlementSettings(
    int regionSize,
    int densityPercent,
    int minimumDistance,
    int candidateCount,
    int sampleRadius,
    int maximumSlope,
    int maximumRoughness,
    Map<SettlementType, Integer> typeWeights)
{
    public SettlementSettings
    {
        if (regionSize < 128 || densityPercent < 0 || densityPercent > 100 || minimumDistance < 0
            || candidateCount < 1 || candidateCount > 64 || sampleRadius < 1 || maximumSlope < 0
            || maximumRoughness < 0)
        {
            throw new IllegalArgumentException("Invalid settlement settings");
        }
        final EnumMap<SettlementType, Integer> copy = new EnumMap<>(SettlementType.class);
        copy.putAll(typeWeights);
        for (final SettlementType type : SettlementType.values()) copy.putIfAbsent(type, 0);
        if (copy.values().stream().mapToInt(Integer::intValue).sum() <= 0 || copy.values().stream().anyMatch(v -> v < 0))
        {
            throw new IllegalArgumentException("At least one non-negative settlement type weight is required");
        }
        typeWeights = Map.copyOf(copy);
    }

    public static SettlementSettings defaults()
    {
        return new SettlementSettings(1024, 70, 640, 4, 32, 10, 18,
            Map.of(SettlementType.VILLAGE, 45, SettlementType.TOWN, 25, SettlementType.TRADING_TOWN, 15,
                SettlementType.CASTLE, 8, SettlementType.FORT, 7));
    }
}
