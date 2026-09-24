package com.minecolonies.kingdoms.bandit;

/**
 * Bounded bandit-camp configuration (see the {@code [bandits.camps]} config section).
 *
 * @param threshold            road threat at or above which bandits start to settle near the road
 * @param pressureEvaluations  consecutive threat evaluations at or above the threshold before a camp appears
 * @param maxCamps             active camps in the whole world
 * @param minStrength          bandits in a new camp at the threshold
 * @param maxStrength          bandits in a new camp at maximum threat
 * @param lifetimeTicks        a camp nobody clears breaks up after this long
 * @param respawnCooldownTicks after a camp is cleared or breaks up, no new camp on that road for this long
 * @param threatContribution   threat an active camp adds to its road's target
 * @param recruitIntervalTicks an abstract camp regains one lost bandit this often, up to its strength
 * @param structures           whether camps place their small physical structure
 * @param maxInhabitedTicks    a camp structure is placed only in chunks players have spent less than this much time in
 */
public record CampSettings(boolean enabled, double threshold, int pressureEvaluations, int maxCamps, int minStrength,
    int maxStrength, long lifetimeTicks, long respawnCooldownTicks, double threatContribution, long recruitIntervalTicks,
    boolean structures, long maxInhabitedTicks)
{
    public CampSettings
    {
        if (threshold <= 0.0D || threshold > 100.0D || pressureEvaluations < 1 || maxCamps < 0 || minStrength < 1
            || maxStrength < minStrength || lifetimeTicks < 1_200L || respawnCooldownTicks < 0L || threatContribution < 0.0D
            || recruitIntervalTicks < 200L || maxInhabitedTicks < 0L)
            throw new IllegalArgumentException("Invalid bandit camp settings");
    }

    public static CampSettings defaults()
    {
        return new CampSettings(true, 45.0D, 3, 4, 4, 8, 120_000L, 72_000L, 15.0D, 24_000L, true, 24_000L);
    }

    public static CampSettings disabled()
    {
        final CampSettings d = defaults();
        return new CampSettings(false, d.threshold, d.pressureEvaluations, d.maxCamps, d.minStrength, d.maxStrength, d.lifetimeTicks,
            d.respawnCooldownTicks, d.threatContribution, d.recruitIntervalTicks, d.structures, d.maxInhabitedTicks);
    }
}
