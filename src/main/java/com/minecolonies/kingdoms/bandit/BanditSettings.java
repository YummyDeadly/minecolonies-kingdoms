package com.minecolonies.kingdoms.bandit;

/** Bounded bandit configuration (see the {@code [bandits]} config section; camps in {@code [bandits.camps]}). */
public record BanditSettings(boolean enabled, int evaluationIntervalTicks, int materializationRadius, int dematerializationRadius,
    int maxBanditsPerEncounter, int maxPhysicalBanditsGlobal, int maxPhysicalBanditsPerPlayer, int settlementExclusionRadius,
    double baseThreat, double threatStep, long encounterCooldownTicks, double ambushChanceAtMaxThreat, long abstractResolveTicks,
    double roadblockThreshold, long roadblockLifetimeTicks, long suppressionTicks, int maxActiveEncounters, CampSettings camps)
{
    public BanditSettings
    {
        if (evaluationIntervalTicks < 20 || materializationRadius < 8 || dematerializationRadius <= materializationRadius
            || maxBanditsPerEncounter < 1 || maxPhysicalBanditsGlobal < 0 || maxPhysicalBanditsPerPlayer < 0
            || settlementExclusionRadius < 0 || baseThreat < 0.0D || threatStep <= 0.0D || encounterCooldownTicks < 0L
            || ambushChanceAtMaxThreat < 0.0D || ambushChanceAtMaxThreat > 1.0D || abstractResolveTicks < 20L
            || roadblockThreshold <= 0.0D || roadblockLifetimeTicks < 20L || suppressionTicks < 0L || maxActiveEncounters < 0
            || camps == null)
            throw new IllegalArgumentException("Invalid bandit settings");
    }

    /** Phase 8 settings with default camps. */
    public BanditSettings(final boolean enabled, final int evaluationIntervalTicks, final int materializationRadius,
        final int dematerializationRadius, final int maxBanditsPerEncounter, final int maxPhysicalBanditsGlobal,
        final int maxPhysicalBanditsPerPlayer, final int settlementExclusionRadius, final double baseThreat, final double threatStep,
        final long encounterCooldownTicks, final double ambushChanceAtMaxThreat, final long abstractResolveTicks,
        final double roadblockThreshold, final long roadblockLifetimeTicks, final long suppressionTicks, final int maxActiveEncounters)
    {
        this(enabled, evaluationIntervalTicks, materializationRadius, dematerializationRadius, maxBanditsPerEncounter,
            maxPhysicalBanditsGlobal, maxPhysicalBanditsPerPlayer, settlementExclusionRadius, baseThreat, threatStep,
            encounterCooldownTicks, ambushChanceAtMaxThreat, abstractResolveTicks, roadblockThreshold, roadblockLifetimeTicks,
            suppressionTicks, maxActiveEncounters, CampSettings.defaults());
    }

    public static BanditSettings defaults()
    {
        return new BanditSettings(true, 1_200, 48, 80, 5, 24, 10, 192, 5.0D, 5.0D, 6_000L, 0.6D, 2_400L, 70.0D, 24_000L,
            24_000L, 16);
    }

    public BanditSettings withCamps(final CampSettings value)
    {
        return new BanditSettings(enabled, evaluationIntervalTicks, materializationRadius, dematerializationRadius, maxBanditsPerEncounter,
            maxPhysicalBanditsGlobal, maxPhysicalBanditsPerPlayer, settlementExclusionRadius, baseThreat, threatStep,
            encounterCooldownTicks, ambushChanceAtMaxThreat, abstractResolveTicks, roadblockThreshold, roadblockLifetimeTicks,
            suppressionTicks, maxActiveEncounters, value);
    }
}
