package com.minecolonies.kingdoms.caravan;

public record CaravanSettings(
    boolean enabled,
    double materializationRadius,
    double dematerializationRadius,
    int maxPhysicalCaravans,
    int maxPhysicalCaravansPerPlayer,
    int updateIntervalTicks,
    double localWaypointDistance,
    int stuckTimeoutTicks,
    int pathRetryIntervalTicks,
    boolean debugNames)
{
    public CaravanSettings
    {
        if (materializationRadius <= 0.0D || dematerializationRadius <= materializationRadius
            || maxPhysicalCaravans < 1 || maxPhysicalCaravansPerPlayer < 1
            || updateIntervalTicks < 1 || localWaypointDistance <= 0.0D
            || stuckTimeoutTicks < 1 || pathRetryIntervalTicks < 1)
        {
            throw new IllegalArgumentException("Invalid caravan settings");
        }
    }
}
