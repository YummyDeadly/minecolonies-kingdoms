package com.minecolonies.kingdoms.citizen;

/** Cumulative counters and a running timing aggregate; no unbounded samples are kept. */
public final class CitizenProfiler
{
    public record Stats(long materialized, long dematerialized, long failedSpawns, long deaths, long stuckRecoveries,
        long cycles, double averageNanos, long maximumNanos) {}

    private long materialized;
    private long dematerialized;
    private long failedSpawns;
    private long deaths;
    private long stuckRecoveries;
    private long cycles;
    private double averageNanos;
    private long maximumNanos;

    public void materialized() { materialized++; }
    public void dematerialized() { dematerialized++; }
    public void failedSpawn() { failedSpawns++; }
    public void death() { deaths++; }
    public void stuckRecovery() { stuckRecoveries++; }

    public void cycle(final long nanos)
    {
        cycles++;
        averageNanos += (nanos - averageNanos) / cycles;
        maximumNanos = Math.max(maximumNanos, nanos);
    }

    public Stats snapshot()
    {
        return new Stats(materialized, dematerialized, failedSpawns, deaths, stuckRecoveries, cycles, averageNanos, maximumNanos);
    }

    public void reset()
    {
        materialized = dematerialized = failedSpawns = deaths = stuckRecoveries = cycles = 0L;
        averageNanos = 0.0D;
        maximumNanos = 0L;
    }
}
