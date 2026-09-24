package com.minecolonies.kingdoms.caravan;

final class CaravanProfiler
{
    private long materializations;
    private long dematerializations;
    private long pathFailures;
    private long lifetimeTicks;
    private long completedLifetimes;
    private long stuckRecoveries;

    void materialized() { materializations++; }
    void dematerialized(final long lifetime) { dematerializations++; recordLifetime(lifetime); }
    void pathFailure() { pathFailures++; }
    void stuckRecovery() { stuckRecoveries++; }
    void recordLifetime(final long lifetime) { lifetimeTicks += Math.max(0L, lifetime); completedLifetimes++; }

    CaravanStats snapshot(final int physical)
    {
        return new CaravanStats(physical, materializations, dematerializations, pathFailures,
            completedLifetimes == 0L ? 0.0D : lifetimeTicks / (double) completedLifetimes, stuckRecoveries);
    }

    void reset()
    {
        materializations = 0L;
        dematerializations = 0L;
        pathFailures = 0L;
        lifetimeTicks = 0L;
        completedLifetimes = 0L;
        stuckRecoveries = 0L;
    }
}
