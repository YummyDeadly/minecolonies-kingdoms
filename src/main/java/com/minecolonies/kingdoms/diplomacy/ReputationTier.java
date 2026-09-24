package com.minecolonies.kingdoms.diplomacy;

/**
 * A player's standing with one faction, derived from the persisted value in {@code Faction.reputation()}
 * (-100..100). Tiers gate contracts and scale their rewards; they never change simulation state by themselves.
 */
public enum ReputationTier
{
    HOSTILE(-100, "Hostile", 0.0D),
    UNFRIENDLY(-59, "Unfriendly", 0.8D),
    NEUTRAL(-19, "Neutral", 1.0D),
    FRIENDLY(20, "Friendly", 1.1D),
    HONORED(60, "Honored", 1.25D),
    REVERED(90, "Revered", 1.4D);

    private final int minimum;
    private final String displayName;
    private final double rewardMultiplier;

    ReputationTier(final int minimum, final String displayName, final double rewardMultiplier)
    {
        this.minimum = minimum;
        this.displayName = displayName;
        this.rewardMultiplier = rewardMultiplier;
    }

    public static ReputationTier of(final int value)
    {
        final ReputationTier[] tiers = values();
        for (int index = tiers.length - 1; index > 0; index--)
            if (value >= tiers[index].minimum) return tiers[index];
        return HOSTILE;
    }

    public int minimum() { return minimum; }
    public String displayName() { return displayName; }
    public double rewardMultiplier() { return rewardMultiplier; }
    public boolean contractsAllowed() { return this != HOSTILE; }
}
