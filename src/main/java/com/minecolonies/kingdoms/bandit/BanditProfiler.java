package com.minecolonies.kingdoms.bandit;

/** Bounded counters and timing for the bandit manager (no samples retained). */
public final class BanditProfiler
{
    private long cycles;
    private long totalNanos;
    private long maximumNanos;
    private long warmMaximumNanos;
    private long warmSlowCycles;
    private long lastSlowCycle;
    private long evaluations;
    private long planned;
    private long activated;
    private long abstractResolutions;
    private long physicalResolutions;
    private long overruns;
    private long materializations;
    private long materializationFailures;
    private long dematerializations;
    private long banditDeaths;
    private long fled;
    private long stalls;
    private long campsEstablished;
    private long campRecruits;
    private long campWorks;

    public record Stats(long cycles, double averageNanos, long maximumNanos, long evaluations, long planned, long activated,
        long abstractResolutions, long physicalResolutions, long overruns, long materializations, long materializationFailures,
        long dematerializations, long banditDeaths, long fled, long stalls, long campsEstablished, long campRecruits, long campWorks,
        long warmMaximumNanos, long warmSlowCycles, long lastSlowCycle) {}

    void cycle(final long nanos)
    {
        cycles++;
        totalNanos += nanos;
        maximumNanos = Math.max(maximumNanos, nanos);
        // Separate the first minute of class loading/JIT from recurring gameplay cost; retain no samples.
        if (cycles > 60)
        {
            warmMaximumNanos = Math.max(warmMaximumNanos, nanos);
            if (nanos >= 10_000_000L) warmSlowCycles++;
        }
        if (nanos >= 10_000_000L) lastSlowCycle = cycles;
    }

    void evaluation() { evaluations++; }
    void planned() { planned++; }
    void activated() { activated++; }
    void abstractResolution() { abstractResolutions++; }
    void physicalResolution() { physicalResolutions++; }
    void overrun() { overruns++; }
    void materialized() { materializations++; }
    void materializationFailure() { materializationFailures++; }
    void dematerialized() { dematerializations++; }
    void banditDeath() { banditDeaths++; }
    void fled() { fled++; }
    void stalled() { stalls++; }
    void campsEstablished(final int count) { campsEstablished += count; }
    void campRecruit() { campRecruits++; }
    void campWork() { campWorks++; }

    void reset()
    {
        cycles = totalNanos = maximumNanos = evaluations = planned = activated = abstractResolutions = physicalResolutions = 0L;
        overruns = materializations = materializationFailures = dematerializations = banditDeaths = fled = stalls = 0L;
        campsEstablished = campRecruits = campWorks = 0L;
        warmMaximumNanos = warmSlowCycles = lastSlowCycle = 0L;
    }

    public Stats snapshot()
    {
        return new Stats(cycles, cycles == 0 ? 0.0D : (double) totalNanos / cycles, maximumNanos, evaluations, planned, activated,
            abstractResolutions, physicalResolutions, overruns, materializations, materializationFailures, dematerializations,
            banditDeaths, fled, stalls, campsEstablished, campRecruits, campWorks, warmMaximumNanos, warmSlowCycles, lastSlowCycle);
    }
}
