package com.minecolonies.kingdoms.caravan;

public record CaravanStats(
    int physicalCaravans,
    long materializations,
    long dematerializations,
    long pathFailures,
    double averagePhysicalLifetimeTicks,
    long stuckRecoveries)
{
}
